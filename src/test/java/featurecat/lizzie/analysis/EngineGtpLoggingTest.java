package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.EngineBootstrapFacts;
import featurecat.lizzie.logging.EngineObservation;
import featurecat.lizzie.logging.LogStream;
import featurecat.lizzie.logging.LoggingChildProcess;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.TraceScope;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EngineGtpLoggingTest {
  private static final String RAW_MOVE = "T03_RAW_MOVE";
  private static final String INFO_CANARY = "T03_INFO_CANARY";
  private static final String PRIVATE_MODEL = "PrivateModel-71.bin.gz";
  private static final String PRIVATE_CONFIG = "PrivateConfig-82.cfg";
  private static final String PRIVATE_PROVIDER = "_openvino_";
  private static final String PRIVATE_BACKEND = "_opencl_";

  @TempDir Path tempDir;
  private Config previousConfig;
  private Leelaz previousLeelaz;
  private GtpConsolePane previousConsole;
  private Board previousBoard;
  private LizzieFrame previousFrame;

  @AfterEach
  void tearDown() {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    Lizzie.config = previousConfig;
    Lizzie.leelaz = previousLeelaz;
    Lizzie.gtpConsole = previousConsole;
    Lizzie.board = previousBoard;
    Lizzie.frame = previousFrame;
  }

  @Test
  void engineLifecycleUsesNewIdentitiesAndReadyWithoutRawProtocol() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.isLoaded = false;
    Method markLoaded = Leelaz.class.getDeclaredMethod("markEngineLoaded");
    markLoaded.setAccessible(true);
    markLoaded.invoke(engine);
    String first = EngineObservation.identityFor(engine);
    EngineObservation.restartInstance(engine, "MAIN_BOARD");
    String second = EngineObservation.identityFor(engine);
    awaitLogs(runtime);

    assertNotEquals(first, second);
    String app = readApp();
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engine event=started"), app);
    assertTrue(app.contains("engine event=ready"), app);
    assertTrue(app.contains("engine=" + first), app);
    assertTrue(app.contains("engine=" + second), app);
    assertTrue(app.contains("purpose=MAIN_BOARD"), app);
    assertFalse(app.contains("gtp raw"), app);
  }

  @Test
  void startupFailureAllocatesEngineIdentityWithoutStartedEvent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    engine.setEngineCommand(
        "\"C:\\\\Users\\\\Player\\\\secret\\\\katago.exe\" gtp "
            + "-model \"C:\\\\Users\\\\Player\\\\secret\\\\model.bin.gz\"");
    Method fail = Leelaz.class.getDeclaredMethod("noteEngineFailed", String.class);
    fail.setAccessible(true);
    fail.invoke(engine, "ssh login failed");
    awaitLogs(runtime);
    String app = readApp();
    assertTrue(app.contains("engine event=failed reason=ssh login failed"), app);
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engineType=katago"), app);
    assertTrue(app.contains("source=user-configured"), app);
    assertTrue(app.contains("purpose=MAIN_BOARD"), app);
    assertTrue(app.contains("model=model.bin.gz"), app);
    assertFalse(app.contains("engine event=started"), app);
    assertFalse(app.contains("Player"), app);
    assertFalse(app.contains("secret"), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=failed");
    assertTrue(app.contains("engine=eng-"), app);
  }

  @Test
  void processStartFailureKeepsBootstrapIdentityWithoutStartedEvent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    Method fail = Leelaz.class.getDeclaredMethod("noteEngineFailed", String.class);
    fail.setAccessible(true);
    fail.invoke(engine, "process start failed");
    awaitLogs(runtime);
    String app = readApp();
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engine event=failed reason=process start failed"), app);
    assertTrue(app.contains("engineType=unknown"), app);
    assertTrue(app.contains("backend=unknown"), app);
    assertTrue(app.contains("onnxProvider=unknown"), app);
    assertFalse(app.contains("engine event=started"), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=failed");
  }

  @Test
  void exitBeforeReadyReusesBootstrapIdentity() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    Method started = Leelaz.class.getDeclaredMethod("noteEngineStarted");
    started.setAccessible(true);
    started.invoke(engine);
    String identity = EngineObservation.identityFor(engine);
    Method fail = Leelaz.class.getDeclaredMethod("noteEngineFailed", String.class);
    fail.setAccessible(true);
    fail.invoke(engine, "exit-before-ready");
    awaitLogs(runtime);
    String app = readApp();
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engine event=started"), app);
    assertTrue(app.contains("engine event=failed reason=exit-before-ready"), app);
    assertTrue(identity != null && app.contains("engine=" + identity), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=failed");
    assertSameEngineIdentity(app, "event=started", "event=failed");
  }

  @Test
  void bundledLaunchBootstrapRecordsSourceAndAvailableBackend() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    Path enginePath =
        tempDir
            .resolve("engines")
            .resolve("katago")
            .resolve("windows-x64-nvidia")
            .resolve("katago.exe");
    Files.createDirectories(enginePath.getParent());
    Files.writeString(enginePath, "", StandardCharsets.UTF_8);
    engine.setEngineCommand(
        "\""
            + enginePath.toAbsolutePath().normalize()
            + "\" gtp -model kata1.bin.gz -config gtp.cfg");
    Method started = Leelaz.class.getDeclaredMethod("noteEngineStarted");
    started.setAccessible(true);
    started.invoke(engine);
    Method loaded = Leelaz.class.getDeclaredMethod("markEngineLoaded");
    loaded.setAccessible(true);
    engine.isLoaded = false;
    loaded.invoke(engine);
    awaitLogs(runtime);
    String app = readApp();
    String identity = EngineObservation.identityFor(engine);
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("source=bundled"), app);
    assertTrue(app.contains("backend=nvidia"), app);
    assertTrue(app.contains("engineType=katago"), app);
    assertEquals(
        KataGoRuntimeHelper.resolveNvidiaBackend(enginePath),
        EngineBootstrapFacts.fromCommand(engine.engineCommand, "MAIN_BOARD").backend());
    assertEquals(
        "unknown",
        EngineBootstrapFacts.fromCommand(engine.engineCommand, "MAIN_BOARD").onnxProvider());
    assertTrue(app.contains("engine event=ready"), app);
    assertTrue(identity != null && app.contains("engine=" + identity), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=ready");
    String bootstrapLine = eventLine(app, "event=bootstrap");
    assertTrue(bootstrapLine != null && !bootstrapLine.contains(tempDir.toString()), app);
  }

  @Test
  void consecutiveFailuresAndRetryMintDistinctIdentities() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    Method fail = Leelaz.class.getDeclaredMethod("noteEngineFailed", String.class);
    fail.setAccessible(true);
    fail.invoke(engine, "ssh login failed");
    fail.invoke(engine, "ssh login failed");
    Method started = Leelaz.class.getDeclaredMethod("noteEngineStarted");
    started.setAccessible(true);
    started.invoke(engine);
    awaitLogs(runtime);
    String app = readApp();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("engine=(eng-[0-9a-f-]+)").matcher(app);
    java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    assertTrue(ids.size() >= 3, app);
    assertTrue(app.contains("engine event=failed"), app);
    assertTrue(app.contains("engine event=started"), app);
    String successId = EngineObservation.identityFor(engine);
    assertTrue(successId != null && ids.contains(successId), app);
  }

  @ParameterizedTest
  @CsvSource({
    "false, _opencl_, opencl",
    "true, _opencl_, hidden-command",
    "true, cpu, cpu",
    "true, rocm-gfx103x, rocm-gfx103x",
    "true, rocm-gfx110x, rocm-gfx110x",
    "true, rocm-gfx1151, rocm-gfx1151",
    "true, rocm-gfx120x, rocm-gfx120x",
    "true, _cpu_, hidden-command",
    "true, rocm-gfx103x-private, hidden-command",
    "true, rocm-gfx999x, hidden-command"
  })
  void realStartFailureBootstrapHonorsCommandVisibilityWithDiagnosticsOff(
      boolean hidden, String marker, String expectedBackend) throws Exception {
    String app = logFailedStart(privateLaunchCommand(marker), hidden).app();
    assertLaunchBootstrap(app, hidden, expectedBackend);
  }

  @ParameterizedTest
  @CsvSource({
    "true, , , unknown, unknown",
    "true, openvino, , onnx, openvino",
    "true, openvino, openvino-npu, onnx, openvino-npu",
    "false, , , onnx, directml"
  })
  void bootstrapProviderEvidenceRespectsCommandVisibility(
      boolean hidden, String marker, String override, String backend, String provider)
      throws Exception {
    if (marker != null) {
      Files.writeString(tempDir.resolve("lizzieyzy-next-engine-backend.txt"), marker + "\n");
    }
    String configFile = "onnxProvider=directml";
    String command =
        "\"" + tempDir.resolve("katago") + "\" gtp -model model.bin.gz -config " + configFile;
    if (override != null) {
      command += " -override-config onnxProvider=" + override;
    }
    String app = logFailedStart(command, hidden).app();
    String line = eventLine(app, "event=bootstrap");
    assertNotNull(line, app);
    assertTrue(line.contains(" backend=" + backend + " "), line);
    assertTrue(line.contains(" onnxProvider=" + provider + " "), line);
    if (hidden) {
      assertFalse(line.contains(configFile), line);
      assertTrue(line.contains("config=hidden-command"), line);
    }
  }

  @Test
  void bootstrapUsesExecutableSelectedFromTheLaunchRoot() throws Exception {
    String program = "katago-launch-root-probe.exe";
    Files.createFile(tempDir.resolve(program)); // Selected by prepare(), then rejected at start.
    Files.writeString(tempDir.resolve("lizzieyzy-next-engine-backend.txt"), "rocm-gfx110x");
    String previous = System.setProperty("user.dir", tempDir.toString());
    try {
      FailedStart start = logFailedStart(program + " gtp -model test.bin.gz", false);
      assertEquals(tempDir.resolve(program), Path.of(start.launch().getString("executable")));
      String line = eventLine(start.app(), "event=bootstrap");
      assertNotNull(line, start.app());
      assertTrue(line.contains(" backend=rocm-gfx110x "), line);
      assertTrue(line.contains(" model=test.bin.gz "), line);
    } finally {
      System.setProperty("user.dir", previous);
    }
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void bundledBootstrapUsesFinalDirectoryForRelativePath() throws Exception {
    LoggingChildProcess.Result result =
        LoggingChildProcess.runWithTimeout(
            tempDir, 30_000, tempDir, Map.of("PATH", "engines/katago/bin"), BundledDirectoryProbe.class);
    assertEquals(0, result.exitCode(), result.output());
  }

  public static final class BundledDirectoryProbe {
    public static void main(String[] args) throws Exception {
      EngineGtpLoggingTest test = new EngineGtpLoggingTest();
      test.tempDir = Path.of(args[0]);
      Path study = Files.createDirectory(test.tempDir.resolve("study"));
      Path config = Files.createFile(study.resolve("gtp.cfg"));
      String command = "katago gtp -config \"" + config + "\"";
      for (Path directory : List.of(study, test.tempDir)) {
        Path bin = Files.createDirectories(directory.resolve("engines/katago/bin"));
        Files.writeString(bin.resolve("lizzieyzy-next-engine-backend.txt"),
            directory.equals(study) ? "cpu" : "opencl");
        Path engine = Files.writeString(bin.resolve("katago"), "#!/missing-interpreter\n");
        assertTrue(engine.toFile().setExecutable(true));
      }
      try {
        // The first observation survives a process-create failure and retains the hiding policy.
        FailedStart start = test.logFailedStart(command, true);
        String bootstrap = eventLine(start.app(), "event=bootstrap");
        assertTrue(bootstrap.contains(" backend=opencl "), bootstrap);
        assertTrue(bootstrap.contains("config=hidden-command"), bootstrap);

        // A real analysis child records its cwd independently of the startup log.
        for (Path directory : List.of(study, test.tempDir)) {
          String backend = directory.equals(study) ? "cpu" : "opencl";
          Files.writeString(
              directory.resolve("engines/katago/bin/katago"),
              "#!/bin/sh\nprintf '%s\\n' " + backend + " \"$PWD\" > \"$3.started\"\nread -r done\n");
        }
        Lizzie.config.analysisEngineCommand = command.replace(" gtp ", " analysis ");
        Lizzie.config.analysisReuseCurrentEngine = false;
        AnalysisEngine analysis = new AnalysisEngine(true);
        try {
          Process process = analysis.process;
          assertNotNull(process);
          process.getOutputStream().write('\n');
          process.getOutputStream().flush();
          assertTrue(process.waitFor(5, TimeUnit.SECONDS));
          assertEquals(0, process.exitValue());
          List<String> actual = Files.readAllLines(study.resolve("gtp.cfg.started"));
          assertEquals("opencl", actual.get(0));
          assertTrue(Files.isSameFile(test.tempDir, Path.of(actual.get(1))));
          awaitLogs(LoggingRuntime.current().orElseThrow());
          List<String> events = test.readApp().lines()
              .filter(line -> line.contains("event=bootstrap ") && line.contains("purpose=PRELOADED_QUICK_ANALYSIS "))
              .toList();
          assertEquals(1, events.size(), events::toString);
          assertTrue(events.get(0).contains(" backend=opencl "), events.get(0));
        } finally {
          analysis.shutdown();
        }
      } finally {
        test.tearDown();
      }
    }
  }

  private record FailedStart(String app, JSONObject launch) {}

  private FailedStart logFailedStart(String command, boolean hidden) throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.config.leelazConfig = new JSONObject();
    Lizzie.config.config = new JSONObject();
    String saved = savedCommand(command, hidden);
    EngineData data = new EngineData();
    data.name = "Startup logging test";
    data.commands = saved;
    data.width = data.height = 19;
    Utils.saveEngineSettings(new ArrayList<>(List.of(data)));
    Leelaz engine = new Leelaz(saved);
    Lizzie.leelaz = engine;
    Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
    Lizzie.frame = allocate(QuietFrame.class);
    assertEquals(command, engine.engineCommand);
    assertEquals(hidden, engine.engineCommandVisibility().isHidden());
    Field firstLaunch = Lizzie.class.getDeclaredField("firstLaunchSession");
    firstLaunch.setAccessible(true);
    boolean previousFirstLaunch = firstLaunch.getBoolean(null);
    firstLaunch.setBoolean(null, true);
    try {
      engine.startEngine(0);
      Field attemptField = Leelaz.class.getDeclaredField("startupDiagnosticAttempt");
      attemptField.setAccessible(true);
      EngineStartupDiagnostics.Attempt attempt =
          (EngineStartupDiagnostics.Attempt) attemptField.get(engine);
      assertNotNull(attempt);
      assertEquals("process-create", attempt.snapshot().toJson().getString("phase"));
      awaitLogs(runtime);
      String app = readApp();
      assertSameEngineIdentity(app, "event=bootstrap", "event=failed");
      assertFalse(app.contains("event=process-started"), app);
      return new FailedStart(app, attempt.snapshot().toJson().getJSONObject("launch"));
    } finally {
      firstLaunch.setBoolean(null, previousFirstLaunch);
      Lizzie.engineStartupStatus.ready();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "true,  false, noteEngineStarted, hidden-command",
    "false, false, noteEngineStarted, opencl",
    "true,  false, noteEngineFailed,  hidden-command",
    "false, false, noteEngineFailed,  opencl",
    "true,  true,  noteEngineStarted, unknown",
    "false, true,  noteEngineFailed,  onnx"
  })
  void fallbackBootstrapUsesOnlyLaunchEvidence(
      boolean hidden, boolean javaSsh, String entry, String expectedBackend) throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    engine.useJavaSSH = javaSsh;
    engine.setEngineCommand(savedCommand(privateLaunchCommand(), hidden));
    if (entry.equals("noteEngineFailed")) {
      Method fail = Leelaz.class.getDeclaredMethod(entry, String.class);
      fail.setAccessible(true);
      fail.invoke(engine, "startup failed");
    } else {
      Method started = Leelaz.class.getDeclaredMethod(entry);
      started.setAccessible(true);
      started.invoke(engine);
    }
    awaitLogs(runtime);
    String app = readApp();
    assertLaunchBootstrap(app, hidden, expectedBackend);
    assertSameEngineIdentity(
        app, "event=bootstrap", entry.equals("noteEngineFailed") ? "event=failed" : "event=started");
  }

  @ParameterizedTest
  @CsvSource({
    "true,  true,  hidden-command",
    "false, true,  opencl",
    "true,  false, unknown",
    "false, false, onnx"
  })
  void processStartObservationHonorsCommandVisibility(
      boolean hidden, boolean local, String expectedBackend) throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(LoggingSettings.defaults());
    String command = privateLaunchCommand();
    Object owner = new Object();
    Process process =
        local
            ? new ProcessBuilder(
                    Path.of(
                            System.getProperty("java.home"),
                            "bin",
                            System.getProperty("os.name").startsWith("Windows")
                                ? "java.exe"
                                : "java")
                        .toString(),
                    "-version")
                .redirectErrorStream(true)
                .redirectOutput(tempDir.resolve("java-version.log").toFile())
                .start()
            : null;
    try {
      AnalysisResourceCoordinator.processStarted(
          owner,
          AnalysisResourceCoordinator.Purpose.MAIN_BOARD,
          command,
          process,
          hidden ? EngineCommandVisibility.HIDDEN : EngineCommandVisibility.VISIBLE);
      String identity = EngineObservation.identityFor(owner);
      assertNotNull(identity);
      awaitLogs(runtime);
      String app = readApp();
      assertLaunchBootstrap(app, hidden, expectedBackend);
      assertSameEngineIdentity(app, "event=bootstrap", "event=process-started");
      String processLine = eventLine(app, "event=process-started");
      assertNotNull(processLine, app);
      assertTrue(processLine.contains("engine=" + identity), processLine);
      assertTrue(processLine.contains("purpose=MAIN_BOARD"), processLine);
      assertTrue(processLine.contains("pid=" + (local ? process.pid() : -1)), processLine);
      if (hidden) {
        assertTrue(processLine.contains("command=<hidden-command>"), processLine);
        assertNoPrivateLaunchValue(processLine);
      } else {
        assertTrue(processLine.contains("command=u7x gtp"), processLine);
        assertTrue(processLine.contains(PRIVATE_MODEL), processLine);
        assertTrue(processLine.contains(PRIVATE_CONFIG), processLine);
        assertTrue(processLine.contains(PRIVATE_PROVIDER), processLine);
      }
    } finally {
      try {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
          assertTrue(process.waitFor(10, TimeUnit.SECONDS), "java -version child did not terminate");
        }
      } finally {
        AnalysisResourceCoordinator.processStopped(
            owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, process);
      }
    }
  }

  @Test
  void gtpSummaryOmitsRawArgumentsAndTraceCapturesThem() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    Leelaz engine = prepareEngine();
    ExactSnapshotRestoreProtocolFixture.install(
        engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommand("play B " + RAW_MOVE);
    awaitLogs(runtime);
    String appBeforeTrace = readApp();
    assertTrue(appBeforeTrace.contains("gtp command=play outcome=sent"), appBeforeTrace);
    assertTrue(appBeforeTrace.contains("gtp command=play outcome=ok"), appBeforeTrace);
    assertFalse(appBeforeTrace.contains(RAW_MOVE), appBeforeTrace);
    assertFalse(Files.exists(tempDir.resolve("logs/engine-trace.log")));

    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    engine.sendCommand("play B " + RAW_MOVE);
    // Raw-stream persistence is a logging contract, independent of analysis ownership. The
    // parser now deliberately quarantines an unowned info line, so exercise the observation seam
    // directly instead of manufacturing an invalid analysis owner in this logging-only test.
    EngineObservation.traceRawStream(
        EngineObservation.identityFor(engine), null, "info move D4 visits 1 " + INFO_CANARY);
    awaitLogs(runtime);
    runtime.stopFullTrace();
    awaitLogs(runtime);

    String app = readApp();
    assertFalse(app.contains(RAW_MOVE), app);
    assertFalse(app.contains(INFO_CANARY), app);
    String trace = Files.readString(tempDir.resolve("logs/engine-trace.log"));
    assertTrue(trace.contains("gtp raw command="), trace);
    assertTrue(trace.contains("play B " + RAW_MOVE), trace);
    assertTrue(trace.contains("gtp raw stream="), trace);
    assertTrue(trace.contains(INFO_CANARY), trace);
    assertTrue(trace.contains("Full Trace session started"), trace);
  }

  @Test
  void engineAndGtpModulesAreIndependent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommand("name");
    awaitLogs(runtime);
    String engineOnly = readApp();
    assertTrue(engineOnly.contains("engine event=queue"), engineOnly);
    assertFalse(engineOnly.contains("gtp command=name"), engineOnly);

    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    engine.sendCommand("version");
    awaitLogs(runtime);
    String gtpOnly = readApp();
    assertTrue(gtpOnly.contains("gtp command=version outcome=sent"), gtpOnly);
  }

  @Test
  void staleResponseKeepsOriginalEngineIdentity() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    String first = EngineObservation.identityFor(engine);
    engine.sendCommand("name");
    String second = EngineObservation.restartInstance(engine, "MAIN_BOARD");
    engine.processCommandResponseLineForTest("=");
    awaitLogs(runtime);

    String app = readApp();
    int sent = app.indexOf("gtp command=name outcome=sent");
    int ok = app.indexOf("gtp command=name outcome=ok");
    assertTrue(sent >= 0, app);
    assertTrue(ok >= 0, app);
    String sentLine = app.substring(Math.max(0, sent - 120), sent + 40);
    String okLine = app.substring(Math.max(0, ok - 120), ok + 40);
    assertTrue(sentLine.contains("engine=" + first), sentLine);
    assertTrue(okLine.contains("engine=" + first), okLine);
    assertFalse(okLine.contains("engine=" + second), okLine);
  }

  @Test
  void protocolCommandIdentityIsReusedWhenPresent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommandWithResponseForTest("kata-set-param numSearchThreads 12", () -> {});
    engine.processCommandResponseLineForTest("=700000000");
    awaitLogs(runtime);

    String app = readApp();
    assertTrue(app.contains("command=700000000"), app);
    assertTrue(app.contains("gtp command=kata-set-param"), app);
    assertFalse(app.contains("numSearchThreads 12"), app);
  }

  @Test
  void stalledTraceQueueDoesNotBlockCommandSend() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(32, 1, 1, 1, 7, 10_000, 1_000));
    installGlobals();
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(true));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    CountDownLatch gate = new CountDownLatch(1);
    Method block =
        LoggingRuntime.class.getDeclaredMethod(
            "blockPersistenceForTests", LogStream.class, CountDownLatch.class);
    block.setAccessible(true);
    block.invoke(runtime, LogStream.ENGINE_TRACE, gate);
    org.slf4j.LoggerFactory.getLogger("lizzie.engine.trace").info("block-one");
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    long began = System.nanoTime();
    engine.sendCommand("play B " + RAW_MOVE);
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
    gate.countDown();
    assertTrue(elapsed < 500, "send blocked for " + elapsed + "ms");
  }

  @Test
  void disabledTraceDoesNotFormatRawCommand() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommand("play B " + RAW_MOVE);
    awaitLogs(runtime);
    assertFalse(readApp().contains(RAW_MOVE), readApp());
    assertFalse(Files.exists(tempDir.resolve("logs/engine-trace.log")));
  }

  private LoggingRuntime startRuntime() throws Exception {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    installGlobals();
    return runtime;
  }

  private void installGlobals() throws Exception {
    previousConfig = Lizzie.config;
    previousLeelaz = Lizzie.leelaz;
    previousConsole = Lizzie.gtpConsole;
    previousBoard = Lizzie.board;
    previousFrame = Lizzie.frame;
    Lizzie.config = ConfigTestHelper.createForTests(tempDir);
    Lizzie.gtpConsole = null;
    Lizzie.frame = allocate(LizzieFrame.class);
  }

  private Leelaz prepareEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = true;
    engine.isCheckingName = true;
    engine.commandLists.addAll(List.of("stop", "kata-analyze", "name", "version"));
    setField(engine, "endGetCommandList", true);
    engine.installCommandOutputForTest(new ByteArrayOutputStream());
    Lizzie.leelaz = engine;
    return engine;
  }

  private String readApp() throws Exception {
    Path file = tempDir.resolve("logs/app.log");
    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  private String privateLaunchCommand() throws Exception {
    return privateLaunchCommand(PRIVATE_BACKEND);
  }

  private String privateLaunchCommand(String marker) throws Exception {
    Path executable = tempDir.resolve("PrivateLaunch-35").resolve("u7x");
    Files.createDirectories(executable.getParent());
    Files.writeString(
        executable.getParent().resolve("lizzieyzy-next-engine-backend.txt"),
        marker + "\n",
        StandardCharsets.UTF_8);
    return "\""
        + executable.toAbsolutePath()
        + "\" gtp -model "
        + PRIVATE_MODEL
        + " -config "
        + PRIVATE_CONFIG
        + " -override-config onnxProvider="
        + PRIVATE_PROVIDER;
  }

  private static String savedCommand(String command, boolean hidden) {
    return hidden ? "encryption||" + Utils.doEncrypt2(command) : command;
  }

  private static void assertLaunchBootstrap(String app, boolean hidden) {
    assertLaunchBootstrap(app, hidden, hidden ? "hidden-command" : "opencl");
  }

  private static void assertLaunchBootstrap(String app, boolean hidden, String expectedBackend) {
    String line = eventLine(app, "event=bootstrap");
    assertNotNull(line, app);
    assertNotNull(engineIdOnEventLine(app, "event=bootstrap"), app);
    assertTrue(line.contains("purpose=MAIN_BOARD"), line);
    assertTrue(line.contains("source=user-configured"), line);
    assertTrue(line.contains("engineType=unknown"), line);
    assertTrue(line.contains(" backend=" + expectedBackend + " "), line);
    if (hidden) {
      assertNoPrivateLaunchValue(line);
      assertTrue(line.contains("onnxProvider=hidden-command"), line);
      assertTrue(line.contains("model=hidden-command config=hidden-command"), line);
    } else {
      assertTrue(line.contains(" onnxProvider=openvino "), line);
      assertTrue(line.contains("model=" + PRIVATE_MODEL), line);
      assertTrue(line.contains("config=" + PRIVATE_CONFIG), line);
    }
  }

  private static void assertNoPrivateLaunchValue(String line) {
    for (String value :
        List.of(
            "u7x",
            "PrivateLaunch-35",
            PRIVATE_MODEL,
            PRIVATE_CONFIG,
            PRIVATE_PROVIDER,
            PRIVATE_BACKEND)) {
      assertFalse(line.contains(value), line);
    }
  }

  private static void assertSameEngineIdentity(String app, String firstEvent, String secondEvent) {
    String first = engineIdOnEventLine(app, firstEvent);
    String second = engineIdOnEventLine(app, secondEvent);
    assertTrue(
        first != null && first.equals(second),
        firstEvent + "=" + first + " " + secondEvent + "=" + second + "\n" + app);
  }

  private static String eventLine(String app, String eventToken) {
    for (String line : app.split("\n")) {
      if (line.contains(eventToken)) {
        return line;
      }
    }
    return null;
  }

  private static String engineIdOnEventLine(String app, String eventToken) {
    String line = eventLine(app, eventToken);
    if (line == null) {
      return null;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("engine=(eng-[0-9a-f-]+)").matcher(line);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static void awaitLogs(LoggingRuntime runtime) throws Exception {
    Method method =
        LoggingRuntime.class.getDeclaredMethod("awaitIdle", long.class, TimeUnit.class);
    method.setAccessible(true);
    assertTrue(
        (Boolean) method.invoke(runtime, 10L, TimeUnit.SECONDS),
        "All submitted log events must reach disk before asserting engine identities");
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super(null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addLine(String line) {}

    @Override
    public void addErrorLine(String line) {}
  }

  private static final class QuietFrame extends LizzieFrame {
    @Override
    public void refresh() {}

    @Override
    public void resetTitle() {}
  }
}
