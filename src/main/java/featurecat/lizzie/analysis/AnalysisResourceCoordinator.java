package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.EngineBootstrapFacts;
import featurecat.lizzie.logging.EngineObservation;
import featurecat.lizzie.logging.PersistenceSanitizer;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Coordinates foreground and auxiliary KataGo processes and emits opt-in diagnostics. */
public final class AnalysisResourceCoordinator {
  public enum Purpose {
    MAIN_BOARD,
    AUTO_QUICK_ANALYSIS,
    PRELOADED_QUICK_ANALYSIS,
    USER_QUICK_ANALYSIS,
    WHOLE_GAME_ANALYSIS,
    OTHER,
    COMPARE,
    PRELOAD,
    ENGINE_GAME
  }

  public enum ForegroundDecision {
    NONE,
    RELEASE_IDLE_SECONDARY,
    PREEMPT_AUTOMATIC_SECONDARY,
    KEEP_USER_TASK,
    SHARED_ENGINE
  }

  private static final Pattern SENSITIVE_ARGUMENT =
      Pattern.compile(
          "(?i)(password|passwd|token|secret|authorization|session|cookie)(\\s*[=:]\\s*|\\s+)([^\\s]+)");
  private static final Pattern URL_SECRET =
      Pattern.compile(
          "(?i)([?&](?:token|key|secret|password|session)=)[^&\\s]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern SET_PARAM =
      Pattern.compile("^\\s*kata-set-param\\s+([^\\s]+)\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern FILE_OPTION =
      Pattern.compile("(?i)(?<!\\S)(-{1,2}(?:model|config)\\s+)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s]+)");
  private static final Pattern HOME_DATA_DIR = Pattern.compile("(?i)(homeDataDir=)([^,\\s]+)");
  private static final Pattern LEADING_EXECUTABLE =
      Pattern.compile("^(\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s]+)");
  private static final Map<Object, Sample> FOREGROUND_SAMPLES = new java.util.WeakHashMap<>();
  // Also guards the pending creations and tracked physical processes.
  private static final Map<Object, EngineLaunch> REGISTERED_PROCESSES =
      new java.util.WeakHashMap<>();
  private static final Set<Process> ACTIVE_LOCAL_COMPUTE_PROCESSES =
      Collections.newSetFromMap(new IdentityHashMap<Process, Boolean>());
  private static int localCreationsInProgress;

  private AnalysisResourceCoordinator() {}

  public static ForegroundDecision decideForegroundStart(
      boolean sharedEngine,
      boolean localDedicatedProcess,
      boolean analysisInProgress,
      boolean automaticBackgroundTask) {
    if (sharedEngine) {
      return ForegroundDecision.SHARED_ENGINE;
    }
    if (!localDedicatedProcess) {
      return ForegroundDecision.NONE;
    }
    if (!analysisInProgress) {
      return ForegroundDecision.RELEASE_IDLE_SECONDARY;
    }
    if (automaticBackgroundTask) {
      return ForegroundDecision.PREEMPT_AUTOMATIC_SECONDARY;
    }
    return ForegroundDecision.KEEP_USER_TASK;
  }

  public static void processStarted(
      Object owner, Purpose purpose, String command, Process process) {
    Purpose role = normalizedPurpose(purpose);
    if (process != null) {
      // Compatibility callers can supply an already-running child. Account for it before
      // inspecting the executable or emitting bootstrap diagnostics.
      synchronized (REGISTERED_PROCESSES) {
        ACTIVE_LOCAL_COMPUTE_PROCESSES.add(process);
      }
    }
    EngineBootstrapFacts facts = EngineStartupBootstrap.factsFor(command, role.name());
    synchronized (REGISTERED_PROCESSES) {
      EngineLaunch previous = REGISTERED_PROCESSES.get(owner);
      if (previous != null && previous.process == process
          && (process != null || previous.engineId.equals(EngineObservation.identityFor(owner)))) {
        return;
      }
      String id = process == null
          ? EngineObservation.ensureStarted(owner, role.name(), facts)
          : EngineObservation.restartInstance(owner, role.name(), facts);
      new EngineLaunch(role, id).publishStarted(owner, process, command);
    }
  }

  public static Process startAuxiliaryLocalProcess(ProcessBuilder builder) throws IOException {
    return startAuxiliaryLocalProcess(builder::start);
  }

  static Process startAuxiliaryLocalProcess(EngineLaunch.LocalProcessFactory factory)
      throws IOException {
    synchronized (REGISTERED_PROCESSES) {
      if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
        throw new IOException("KataGo tuning is using the local compute device.");
      }
      localCreationsInProgress++;
    }
    return startReservedLocalProcess(factory);
  }

  private static Process startReservedLocalProcess(EngineLaunch.LocalProcessFactory factory)
      throws IOException {
    Process child = null;
    try {
      child = factory.start();
      return child;
    } finally {
      synchronized (REGISTERED_PROCESSES) {
        // A benchmark cannot observe a gap between the pending factory and its live child.
        localCreationsInProgress--;
        if (child != null) ACTIVE_LOCAL_COMPUTE_PROCESSES.add(child);
      }
    }
  }

  /** One launch attempt owns its physical start/stop pair, including after the owner is reused. */
  static final class EngineLaunch {
    private enum State { PENDING, CREATING_LOCAL, STARTED, CLOSED }

    @FunctionalInterface
    interface LocalProcessFactory {
      Process start() throws IOException;
    }

    private final Purpose purpose;
    private final String engineId;
    // Guarded by REGISTERED_PROCESSES so the owner index and the event transition agree.
    private State state = State.PENDING;
    private Process process;
    private long pid;

    EngineLaunch(Purpose purpose, String engineId) {
      this.purpose = normalizedPurpose(purpose);
      this.engineId = engineId;
    }

    String engineId() {
      return engineId;
    }

    Purpose purpose() {
      return purpose;
    }

    Process startLocalProcess(Object owner, LocalProcessFactory factory, String command)
        throws IOException {
      synchronized (REGISTERED_PROCESSES) {
        if (state != State.PENDING) {
          throw new IllegalStateException("Engine start has already been retired");
        }
        state = State.CREATING_LOCAL;
        localCreationsInProgress++;
      }
      Process child = null;
      try {
        child = startReservedLocalProcess(factory);
        return child;
      } finally {
        finishLocalCreation(owner, child, command);
      }
    }

    private void finishLocalCreation(Object owner, Process child, String command) {
      boolean retired;
      synchronized (REGISTERED_PROCESSES) {
        if (child == null) {
          state = State.CLOSED;
          return;
        }
        retired = state == State.CLOSED;
        publishStarted(owner, child, command);
        if (retired) recordStopped();
      }
      if (retired) trackUntilExit(child);
    }

    void started(Object owner, Process process, String command) {
      synchronized (REGISTERED_PROCESSES) {
        if (state != State.PENDING) return;
        if (process != null) ACTIVE_LOCAL_COMPUTE_PROCESSES.add(process);
        publishStarted(owner, process, command);
      }
    }

    private void publishStarted(Object owner, Process process, String command) {
      this.process = process;
      if (state != State.CLOSED) {
        state = State.STARTED;
        if (engineId.equals(EngineObservation.identityFor(owner))) {
          REGISTERED_PROCESSES.put(owner, this);
        }
      }
      pid = processId(process);
      try {
        EngineObservation.markStartupStage(engineId, EngineObservation.STAGE_PROCESS_STARTED);
        if (EngineObservation.engineDiagnosticsEnabled()) {
          EngineObservation.recordProcessDetails(
              engineId, "process-started", purpose.name(), pid, diagnosticCommand(command));
        }
      } catch (RuntimeException ignored) {
        // Diagnostic publication cannot invalidate a successful launch.
      }
    }

    void closed(Object owner) {
      Process stoppedProcess;
      synchronized (REGISTERED_PROCESSES) {
        boolean hadCarrier = state == State.STARTED;
        state = State.CLOSED;
        if (!hadCarrier) return;
        REGISTERED_PROCESSES.remove(owner, this);
        stoppedProcess = process;
        recordStopped();
      }
      if (stoppedProcess != null) trackUntilExit(stoppedProcess);
    }

    /** End this logical launch even if startup never attached a local or remote carrier. */
    void finishObservation(Object owner) {
      Object sample = captureForegroundSampleIdentity(owner);
      closed(owner);
      if (EngineObservation.discardIdentityIfCurrent(owner, engineId)) {
        removeForegroundSample(owner, sample);
        EngineObservation.recordStopped(engineId, "stopped");
      }
    }

    private void recordStopped() {
      try {
        EngineObservation.recordProcessDetails(
            engineId, "process-stopped", purpose.name(), pid, null);
      } catch (RuntimeException ignored) {
        // Resource accounting must still settle if the log sink fails.
      }
    }
  }

  private static void trackUntilExit(Process process) {
    if (!isProcessAlive(process)) {
      removeTrackedLocalProcess(process);
      return;
    }
    try {
      process.onExit().whenComplete(
          (ignored, failure) -> {
            if (!isProcessAlive(process)) removeTrackedLocalProcess(process);
          });
    } catch (RuntimeException unsupportedExitNotification) {
      // activeLocalComputeProcessCount() also prunes exited children.
    }
  }

  public static void processStopped(Object owner, Purpose purpose, Process process) {
    EngineLaunch registered = registrationFor(owner, process);
    if (registered == null) {
      if (process != null) trackUntilExit(process);
      return;
    }
    registered.finishObservation(owner);
  }

  /** Compatibility entry point for callers that already detached their logical engine ID. */
  public static void processStoppedAfterIdentityClaim(
      Object owner, Purpose purpose, Process process, String engineId, Object expectedForegroundSample) {
    EngineLaunch registered = registrationFor(owner, process);
    if (registered != null && (process != null || registered.engineId.equals(engineId))) {
      registered.closed(owner);
    } else if (process != null) {
      trackUntilExit(process);
    }
    removeForegroundSample(owner, expectedForegroundSample);
  }

  private static EngineLaunch registrationFor(Object owner, Process process) {
    synchronized (REGISTERED_PROCESSES) {
      EngineLaunch registered = REGISTERED_PROCESSES.get(owner);
      return registered != null && registered.process == process ? registered : null;
    }
  }

  static Object captureForegroundSampleIdentity(Object owner) {
    synchronized (FOREGROUND_SAMPLES) {
      return FOREGROUND_SAMPLES.get(owner);
    }
  }

  static boolean isRegisteredProcessIdentityForTesting(Object owner, Process process) {
    synchronized (REGISTERED_PROCESSES) {
      EngineLaunch registered = REGISTERED_PROCESSES.get(owner);
      return registered != null && registered.process == process;
    }
  }

  static void removeForegroundSample(Object owner, Object expectedForegroundSample) {
    synchronized (FOREGROUND_SAMPLES) {
      if (expectedForegroundSample != null
          && FOREGROUND_SAMPLES.get(owner) == expectedForegroundSample) {
        FOREGROUND_SAMPLES.remove(owner);
      }
    }
  }

  /** Returns whether a local analysis process is being created or is still alive. */
  public static boolean hasActiveLocalComputeProcess() {
    return activeLocalComputeProcessCount() > 0;
  }

  public static boolean hasActiveLocalComputeOtherThan(Object owner) {
    synchronized (REGISTERED_PROCESSES) {
      // A pending child has no published owner registration to exempt.
      if (localCreationsInProgress > 0) return true;
      EngineLaunch registration = REGISTERED_PROCESSES.get(owner);
      Process owned = registration == null ? null : registration.process;
      for (Process process : ACTIVE_LOCAL_COMPUTE_PROCESSES) {
        if (process != owned && isProcessAlive(process)) return true;
      }
      return false;
    }
  }

  static int activeLocalComputeProcessCount() {
    synchronized (REGISTERED_PROCESSES) {
      int alive = localCreationsInProgress;
      Iterator<Process> processes = ACTIVE_LOCAL_COMPUTE_PROCESSES.iterator();
      while (processes.hasNext()) {
        Process process = processes.next();
        if (!isProcessAlive(process)) {
          processes.remove();
        } else {
          alive++;
        }
      }
      return alive;
    }
  }

  static int rawLocalComputeProcessCountForTesting() {
    synchronized (REGISTERED_PROCESSES) {
      return ACTIVE_LOCAL_COMPUTE_PROCESSES.size();
    }
  }

  private static boolean isProcessAlive(Process process) {
    try {
      return process != null && process.isAlive();
    } catch (RuntimeException invalidProcess) {
      return false;
    }
  }

  private static void removeTrackedLocalProcess(Process process) {
    synchronized (REGISTERED_PROCESSES) {
      ACTIVE_LOCAL_COMPUTE_PROCESSES.remove(process);
    }
  }

  public static void commandSent(Object owner, Purpose purpose, String command) {
    if (!EngineObservation.engineDiagnosticsEnabled()) {
      return;
    }
    commandSentForInstance(EngineObservation.identityFor(owner), purpose, command);
  }

  static void recordAuxiliaryCommandSent(String engineId, Purpose purpose, String command) {
    try {
      commandSentForInstance(engineId, purpose, command);
    } catch (RuntimeException observationFailure) {
      // A diagnostic failure cannot undo a successful transport write.
      observationFailure.printStackTrace();
    }
  }

  /** Records delivery using the identity captured for the physical write. */
  static void commandSentForInstance(String engineId, Purpose purpose, String command) {
    if (!EngineObservation.engineDiagnosticsEnabled() || command == null) {
      return;
    }
    GtpCommandText parsed = GtpCommandText.parse(command);
    String purposeName = normalizedPurpose(purpose).name();
    Matcher matcher = SET_PARAM.matcher(parsed.payload());
    if (matcher.matches()) {
      EngineObservation.recordProcessDetails(
          engineId,
          "dynamic-parameter",
          purposeName,
          -1L,
          matcher.group(1) + "=" + redactCommand(matcher.group(2)));
    } else if (parsed.verb().equals("kata-analyze")) {
      EngineObservation.recordProcessDetails(
          engineId, "analysis-started", purposeName, -1L, "kata-analyze");
    }
  }

  public static void foregroundPausedForAuxiliary(
      Object foregroundOwner, Purpose auxiliaryPurpose) {
    if (!EngineObservation.engineDiagnosticsEnabled()) {
      return;
    }
    EngineObservation.recordProcessDetails(
        EngineObservation.identityFor(foregroundOwner),
        "foreground-paused",
        normalizedPurpose(auxiliaryPurpose).name(),
        -1L,
        null);
  }

  public static void foregroundPlayoutSample(Object owner, int playouts) {
    if (!EngineObservation.engineDiagnosticsEnabled() || owner == null || playouts < 0) {
      return;
    }
    long now = System.nanoTime();
    Sample previous;
    synchronized (FOREGROUND_SAMPLES) {
      previous = FOREGROUND_SAMPLES.get(owner);
      if (previous == null || playouts <= previous.playouts) {
        FOREGROUND_SAMPLES.put(owner, new Sample(playouts, now));
        return;
      }
      if (now - previous.nanoTime < 250_000_000L) {
        return;
      }
      FOREGROUND_SAMPLES.put(owner, new Sample(playouts, now));
    }
    EngineObservation.recordThroughput(
        EngineObservation.identityFor(owner),
        playouts,
        (playouts - previous.playouts) / elapsedSeconds(previous.nanoTime, now));
  }

  static double elapsedSeconds(long previousNanoTime, long currentNanoTime) {
    return Math.max(0L, currentNanoTime - previousNanoTime) / 1_000_000_000.0;
  }

  static String redactCommand(String command) {
    if (command == null) {
      return "";
    }
    String redacted = new PersistenceSanitizer().sanitize(command);
    redacted = SENSITIVE_ARGUMENT.matcher(redacted).replaceAll("$1$2<redacted>");
    return URL_SECRET.matcher(redacted).replaceAll("$1<redacted>");
  }

  static String diagnosticCommand(String command) {
    String redacted = redactCommand(command);
    Matcher fileOptions = FILE_OPTION.matcher(redacted);
    StringBuffer sanitized = new StringBuffer();
    while (fileOptions.find()) {
      fileOptions.appendReplacement(
          sanitized,
          Matcher.quoteReplacement(
              fileOptions.group(1) + diagnosticFileName(unquote(fileOptions.group(2)))));
    }
    fileOptions.appendTail(sanitized);
    String withoutHome = HOME_DATA_DIR.matcher(sanitized.toString()).replaceAll("$1<local-path>");
    Matcher executable = LEADING_EXECUTABLE.matcher(withoutHome);
    if (!executable.find()) {
      return withoutHome;
    }
    String firstToken = unquote(executable.group(2));
    if (!looksLikeLocalPath(firstToken)) {
      return withoutHome;
    }
    return executable.replaceFirst(
        Matcher.quoteReplacement(executable.group(1) + diagnosticFileName(firstToken)));
  }

  static Map<String, String> parseDynamicParameters(Iterable<String> commands) {
    Map<String, String> parameters = new LinkedHashMap<>();
    if (commands == null) {
      return parameters;
    }
    for (String command : commands) {
      Matcher matcher = SET_PARAM.matcher(command == null ? "" : command);
      if (matcher.matches()) {
        parameters.put(matcher.group(1), redactCommand(matcher.group(2)));
      }
    }
    return parameters;
  }

  static String fileSha256(Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      return "";
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      try (java.io.InputStream input = Files.newInputStream(path)) {
        int read;
        while ((read = input.read(buffer)) >= 0) {
          digest.update(buffer, 0, read);
        }
      }
      StringBuilder hash = new StringBuilder();
      for (byte value : digest.digest()) {
        hash.append(String.format(Locale.ROOT, "%02x", value));
      }
      return hash.toString();
    } catch (IOException | NoSuchAlgorithmException e) {
      return "";
    }
  }

  private static Purpose normalizedPurpose(Purpose purpose) {
    return purpose == null ? Purpose.OTHER : purpose;
  }

  private static long processId(Process process) {
    try {
      return process == null ? -1L : process.pid();
    } catch (RuntimeException e) {
      return -1L;
    }
  }

  private static boolean looksLikeLocalPath(String value) {
    return value != null
        && (value.contains("/") || value.contains("\\") || value.matches("^[A-Za-z]:.*"));
  }

  private static String diagnosticFileName(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    String normalized = value.replace('\\', '/');
    int separator = normalized.lastIndexOf('/');
    return separator >= 0 ? normalized.substring(separator + 1) : normalized;
  }

  private static String unquote(String value) {
    if (value == null || value.length() < 2) {
      return value == null ? "" : value;
    }
    char first = value.charAt(0);
    char last = value.charAt(value.length() - 1);
    return (first == last && (first == '\"' || first == '\''))
        ? value.substring(1, value.length() - 1)
        : value;
  }

  private static final class Sample {
    private final int playouts;
    private final long nanoTime;

    private Sample(int playouts, long nanoTime) {
      this.playouts = playouts;
      this.nanoTime = nanoTime;
    }
  }
}
