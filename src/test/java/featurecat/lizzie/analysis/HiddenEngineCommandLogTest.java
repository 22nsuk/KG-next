package featurecat.lizzie.analysis;

import static featurecat.lizzie.analysis.HiddenCommandFixture.assertNoFragment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A user-hidden ({@code encryption||}) engine command must not reach the engine log, the startup
 * status or the failure a launch hands to its caller. Each test uses one of the shapes that defeat
 * removing known command text from free text: a short executable name, a word a shell split off an
 * argument, and arguments spelled like the log's own vocabulary. A visible-command twin of each
 * shows the same text does reach the log, so the hidden test cannot pass vacuously.
 */
class HiddenEngineCommandLogTest {
  /** Executable name too short for any fragment search, in a directory naming the command. */
  private static final String SHORT_EXECUTABLE = "u7x";

  private static final List<String> LAUNCH_FRAGMENTS =
      List.of(SHORT_EXECUTABLE, "PrivateVault-31", "secret-net-b40", "host-token-q9", "Q9pass");

  /** Arguments spelled like app.log vocabulary, which a text scrub would rewrite. */
  private static final List<String> VOCABULARY_ARGUMENTS =
      List.of("engine", "event=failed", "MAIN_BOARD", "bootstrap", "stderr");

  @TempDir Path tempDir;
  private Config previousConfig;
  private Leelaz previousLeelaz;
  private GtpConsolePane previousConsole;
  private LizzieFrame previousFrame;
  private boolean previousFirstLaunchSession;
  private LoggingRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
    previousConfig = Lizzie.config;
    previousLeelaz = Lizzie.leelaz;
    previousConsole = Lizzie.gtpConsole;
    previousFrame = Lizzie.frame;
    previousFirstLaunchSession = firstLaunchSession();
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    // The ENGINE module also persists the DEBUG stderr tail and process details.
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    Lizzie.config = ConfigTestHelper.createForTests(tempDir);
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.config.leelazConfig = new JSONObject();
    Lizzie.config.config = new JSONObject();
    Lizzie.gtpConsole = allocate(QuietConsole.class);
    Lizzie.frame = allocate(QuietFrame.class);
    // A first-launch session does not open the engine editor after a failed start.
    setFirstLaunchSession(true);
  }

  @AfterEach
  void tearDown() throws Exception {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    Lizzie.engineStartupStatus.ready();
    setFirstLaunchSession(previousFirstLaunchSession);
    Lizzie.config = previousConfig;
    Lizzie.leelaz = previousLeelaz;
    Lizzie.gtpConsole = previousConsole;
    Lizzie.frame = previousFrame;
  }

  @Test
  void hiddenProcessCreateFailureKeepsOnlyStructuralFacts() throws Exception {
    Leelaz engine = engineFor(true, missingEngineCommand());

    engine.startEngine(0);
    String app = awaitApp();
    String detail = Lizzie.engineStartupStatus.snapshot().detail;

    assertNoFragment(app, LAUNCH_FRAGMENTS);
    assertNoFragment(detail, LAUNCH_FRAGMENTS);
    // The operating system's reason and the log's own vocabulary survive.
    assertTrue(
        Pattern.compile(
                "engine event=failed reason=Cannot run program <hidden-command>"
                    + "(?: \\(in directory <hidden-command>\\))?: (?!<hidden-command>)\\S")
            .matcher(app)
            .find(),
        app);
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("purpose=MAIN_BOARD"), app);
    assertTrue(app.contains("model=hidden-command config=hidden-command"), app);
    assertTrue(detail.contains("Cannot run program <hidden-command>"), detail);
  }

  @Test
  void visibleProcessCreateFailureStillNamesItsShortExecutable() throws Exception {
    Leelaz engine = engineFor(false, missingEngineCommand());

    engine.startEngine(0);
    String app = awaitApp();

    assertTrue(app.contains(SHORT_EXECUTABLE + "\""), app);
    assertTrue(app.contains("model=secret-net-b40.bin.gz config=host-token-q9.cfg"), app);
    assertTrue(Lizzie.engineStartupStatus.snapshot().detail.contains("PrivateVault-31"));
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void hiddenStderrTailCarriesNoWordTheShellSplitOffAnArgument() throws Exception {
    String shellLine = shellReportFor("exec PrivateEngineCanary");
    Leelaz engine = engineWithRecentStderr(true, shellLine);

    invoke(engine, "noteEngineFailed", "exit-before-ready");
    invoke(engine, "noteEngineStarted");
    invoke(engine, "noteEngineStopped");
    String app = awaitApp();

    assertNoFragment(app, List.of("PrivateEngineCanary"));
    assertEquals(2, occurrences(app, "engine event=stderr facts=<hidden-command>"), app);
    assertTrue(app.contains("engine event=failed reason=exit-before-ready"), app);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void visibleStderrTailKeepsTheShellReport() throws Exception {
    String shellLine = shellReportFor("exec PrivateEngineCanary");
    Leelaz engine = engineWithRecentStderr(false, shellLine);

    invoke(engine, "noteEngineFailed", "exit-before-ready");
    String app = awaitApp();

    assertTrue(app.contains("PrivateEngineCanary: not found"), app);
  }

  @Test
  void hiddenLaunchFailureLeavingStartEngineIsDisclosedAndUnchained() throws Exception {
    IOException original =
        new IOException(
            "connect failed for " + SHORT_EXECUTABLE + " under PrivateVault-31",
            new IOException("Q9pass rejected"));
    Leelaz engine = remoteEngineFailingWith(true, original);

    IOException thrown = assertThrows(IOException.class, () -> engine.startEngine(0));
    String app = awaitApp();
    String detail = Lizzie.engineStartupStatus.snapshot().detail;

    assertEquals(EngineCommandVisibility.HIDDEN_VALUE, thrown.getMessage());
    assertNull(thrown.getCause(), "a printed cause would carry the original message");
    assertNoFragment(app, LAUNCH_FRAGMENTS);
    assertNoFragment(detail, LAUNCH_FRAGMENTS);
    assertTrue(app.contains("engine event=failed reason=<hidden-command>"), app);
  }

  @Test
  void visibleLaunchFailureLeavingStartEngineIsTheOriginal() throws Exception {
    IOException original = new IOException("connect failed for " + SHORT_EXECUTABLE);
    Leelaz engine = remoteEngineFailingWith(false, original);

    assertSame(original, assertThrows(IOException.class, () -> engine.startEngine(0)));
  }

  @Test
  void hiddenCommandWithoutLocalProcessLogsOnlyTheMarker() throws Exception {
    // SSH and remote-compute engines register without a local process on every ponder start.
    Object owner = new Object();
    AnalysisResourceCoordinator.processStarted(
        owner,
        AnalysisResourceCoordinator.Purpose.MAIN_BOARD,
        missingEngineCommand(),
        null,
        EngineCommandVisibility.HIDDEN);
    AnalysisResourceCoordinator.processStopped(
        owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, null);
    String app = awaitApp();

    assertNoFragment(app, LAUNCH_FRAGMENTS);
    assertTrue(
        app.contains(
            "engine event=process-started purpose=MAIN_BOARD pid=-1 command=<hidden-command>"),
        app);
    assertTrue(app.contains("model=hidden-command config=hidden-command"), app);
  }

  /**
   * A command whose executable directory exists but whose short executable does not, so the JDK
   * reports {@code Cannot run program "<argv0>" (in directory "<cwd>")}.
   */
  private String missingEngineCommand() throws IOException {
    Path vault = Files.createDirectories(tempDir.resolve("PrivateVault-31").resolve("engines"));
    Path models = Files.createDirectories(tempDir.resolve("models"));
    return "\""
        + vault.resolve(SHORT_EXECUTABLE)
        + "\" gtp -model \""
        + models.resolve("secret-net-b40.bin.gz")
        + "\" -config \""
        + tempDir.resolve("PrivateVault-31").resolve("host-token-q9.cfg")
        + "\" "
        + String.join(" ", VOCABULARY_ARGUMENTS)
        + " -override-config numSearchThreads=8,serverPass=Q9pass";
  }

  private Leelaz engineFor(boolean hidden, String command) throws Exception {
    String saved = saveEngine(hidden, command);
    Leelaz engine = new Leelaz(saved);
    assertVisibility(hidden, engine);
    Lizzie.leelaz = engine;
    return engine;
  }

  private Leelaz remoteEngineFailingWith(boolean hidden, IOException failure) throws Exception {
    String saved = saveEngine(hidden, RemoteComputeConfig.COMMAND_CUSTOM_WS);
    Leelaz engine =
        new Leelaz(saved) {
          @Override
          protected EngineTransport createRemoteTransport() throws IOException {
            throw failure;
          }
        };
    assertVisibility(hidden, engine);
    Lizzie.leelaz = engine;
    return engine;
  }

  private Leelaz engineWithRecentStderr(boolean hidden, String line) throws Exception {
    Leelaz engine = engineFor(hidden, "sh -c \"exec PrivateEngineCanary\"");
    Method remember =
        Leelaz.class.getDeclaredMethod("rememberRecentLine", ArrayDeque.class, String.class);
    remember.setAccessible(true);
    remember.invoke(engine, field(engine, "recentStderrLines"), line);
    return engine;
  }

  private static String saveEngine(boolean hidden, String command) {
    String saved = hidden ? "encryption||" + Utils.doEncrypt2(command) : command;
    EngineData data = new EngineData();
    data.commands = saved;
    data.name = "Hidden engine";
    data.width = data.height = 19;
    Utils.saveEngineSettings(new ArrayList<>(List.of(data)));
    return saved;
  }

  private static void assertVisibility(boolean hidden, Leelaz engine) {
    assertEquals(
        hidden ? EngineCommandVisibility.HIDDEN : EngineCommandVisibility.VISIBLE,
        engine.engineCommandVisibility());
  }

  /** What the shell itself prints for {@code script}: words it split off the argument. */
  private static String shellReportFor(String script) throws Exception {
    Process shell = new ProcessBuilder("sh", "-c", script).start();
    shell.getOutputStream().close();
    String report = new String(shell.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(shell.waitFor(10, TimeUnit.SECONDS));
    String line = report.strip().lines().findFirst().orElse("");
    assertTrue(line.contains("PrivateEngineCanary"), report);
    assertTrue(!line.contains(script), "the report should not quote the argument whole: " + line);
    return line;
  }

  private String awaitApp() throws Exception {
    Method awaitIdle =
        LoggingRuntime.class.getDeclaredMethod("awaitIdle", long.class, TimeUnit.class);
    awaitIdle.setAccessible(true);
    assertTrue((Boolean) awaitIdle.invoke(runtime, 10L, TimeUnit.SECONDS));
    Path file = tempDir.resolve("logs/app.log");
    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  private static int occurrences(String text, String token) {
    int count = 0;
    for (int index = text.indexOf(token); index >= 0; index = text.indexOf(token, index + 1)) {
      count++;
    }
    return count;
  }

  private static void invoke(Leelaz engine, String name, Object... arguments) throws Exception {
    Class<?>[] types = new Class<?>[arguments.length];
    for (int index = 0; index < arguments.length; index++) types[index] = String.class;
    Method method = Leelaz.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    method.invoke(engine, arguments);
  }

  private static Object field(Leelaz engine, String name) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(engine);
  }

  private static boolean firstLaunchSession() throws Exception {
    Field field = Lizzie.class.getDeclaredField("firstLaunchSession");
    field.setAccessible(true);
    return field.getBoolean(null);
  }

  private static void setFirstLaunchSession(boolean value) throws Exception {
    Field field = Lizzie.class.getDeclaredField("firstLaunchSession");
    field.setAccessible(true);
    field.setBoolean(null, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class QuietConsole extends GtpConsolePane {
    private QuietConsole() {
      super(null);
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
