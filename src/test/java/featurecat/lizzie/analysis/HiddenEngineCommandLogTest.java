package featurecat.lizzie.analysis;

import static featurecat.lizzie.analysis.HiddenCommandFixture.COMMAND;
import static featurecat.lizzie.analysis.HiddenCommandFixture.DLL_FAILURE_LINE;
import static featurecat.lizzie.analysis.HiddenCommandFixture.PROCESS_CREATE_FAILURE;
import static featurecat.lizzie.analysis.HiddenCommandFixture.assertNoCanary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.util.Utils;
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
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A user-hidden ({@code encryption||}) engine command must not reach the engine log or the startup
 * status through the failure text, stderr tail, bootstrap facts or process details of its launch.
 */
class HiddenEngineCommandLogTest {
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
  void hiddenProcessCreateFailureLeavesNoCommandFragmentInLogOrStatus() throws Exception {
    String app = launchMissingEngine(true);
    String detail = Lizzie.engineStartupStatus.snapshot().detail;

    assertNoCanary(app);
    assertTrue(app.contains("engine event=failed reason=Cannot run program"), app);
    assertTrue(app.contains("model=hidden-command config=hidden-command"), app);
    assertTrue(app.contains("engineType=katago"), app);
    assertNoCanary(detail);
    assertTrue(detail.contains("Cannot run program"), detail);
    assertTrue(detail.contains(EngineCommandVisibility.HIDDEN_VALUE), detail);
  }

  @Test
  void visibleProcessCreateFailureStillNamesTheCommandItLaunched() throws Exception {
    String app = launchMissingEngine(false);
    String detail = Lizzie.engineStartupStatus.snapshot().detail;

    // The same launch without encryption keeps its paths: the hidden test above is not vacuous.
    assertTrue(app.contains("HiddenVault7"), app);
    assertTrue(app.contains("model=secret-net-b40.bin.gz config=host-token-q9.cfg"), app);
    assertTrue(detail.contains("HiddenVault7"), detail);
  }

  @Test
  void hiddenFailureAndStopScrubTheRecentStderrTail() throws Exception {
    Leelaz engine = new Leelaz("encryption||" + Utils.doEncrypt2(COMMAND));
    Method remember =
        Leelaz.class.getDeclaredMethod("rememberRecentLine", ArrayDeque.class, String.class);
    remember.setAccessible(true);
    ArrayDeque<?> stderr = (ArrayDeque<?>) field(engine, "recentStderrLines");
    remember.invoke(engine, stderr, DLL_FAILURE_LINE);
    remember.invoke(engine, stderr, "Loading model D:\\models\\secret-net-b40.bin.gz");
    remember.invoke(engine, stderr, "Rejected override serverPass=Q9pass");

    invoke(engine, "noteEngineFailed", PROCESS_CREATE_FAILURE);
    invoke(engine, "noteEngineStarted");
    invoke(engine, "noteEngineStopped");
    String app = awaitApp();

    assertNoCanary(app);
    assertEquals(2, occurrences(app, "engine event=stderr facts="), app);
    assertTrue(app.contains("cudnn64_9.dll"), app);
    assertTrue(app.contains("engine event=failed reason=java.io.IOException: Cannot run"), app);
    assertTrue(app.contains("model=hidden-command"), app);
  }

  @Test
  void hiddenCommandWithoutLocalProcessLogsNoProcessDetails() throws Exception {
    // SSH and remote-compute engines register without a local process on every ponder start.
    Object owner = new Object();
    AnalysisResourceCoordinator.processStarted(
        owner,
        AnalysisResourceCoordinator.Purpose.MAIN_BOARD,
        COMMAND,
        null,
        EngineCommandVisibility.HIDDEN);
    AnalysisResourceCoordinator.processStopped(
        owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, null);
    String app = awaitApp();

    assertNoCanary(app);
    assertTrue(
        app.contains("engine event=process-started purpose=MAIN_BOARD pid=-1 command=<hidden-command>"),
        app);
    assertTrue(app.contains("model=hidden-command config=hidden-command"), app);
  }

  /**
   * Starts a saved command whose executable directory exists but whose executable does not, so the
   * JDK reports {@code Cannot run program "<argv0>" (in directory "<cwd>")}.
   */
  private String launchMissingEngine(boolean hidden) throws Exception {
    Path vault = Files.createDirectories(tempDir.resolve("HiddenVault7").resolve("engines"));
    Path models = Files.createDirectories(tempDir.resolve("models"));
    String command =
        "\""
            + vault.resolve("katago")
            + "\" gtp -model \""
            + models.resolve("secret-net-b40.bin.gz")
            + "\" -config \""
            + tempDir.resolve("HiddenVault7").resolve("host-token-q9.cfg")
            + "\" -override-config numSearchThreads=8,serverPass=Q9pass";
    String saved = hidden ? "encryption||" + Utils.doEncrypt2(command) : command;
    EngineData data = new EngineData();
    data.commands = saved;
    data.name = "Hidden engine";
    data.width = data.height = 19;
    Utils.saveEngineSettings(new ArrayList<>(List.of(data)));
    Leelaz engine = new Leelaz(saved);
    assertEquals(
        hidden ? EngineCommandVisibility.HIDDEN : EngineCommandVisibility.VISIBLE,
        engine.engineCommandVisibility());
    Lizzie.leelaz = engine;

    engine.startEngine(0);

    EngineStartupStatus.State state = Lizzie.engineStartupStatus.snapshot().state;
    assertTrue(
        state == EngineStartupStatus.State.NEEDS_REPAIR
            || state == EngineStartupStatus.State.START_FAILED,
        String.valueOf(state));
    return awaitApp();
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
