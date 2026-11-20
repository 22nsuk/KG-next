package featurecat.lizzie.analysis;

import static featurecat.lizzie.analysis.HiddenCommandFixture.assertNoFragment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.logging.DiagnosticBundleExporter;
import featurecat.lizzie.logging.DiagnosticBundleRequest;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.TraceScope;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.util.Utils;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole diagnostic bundle of a session that ran a user-hidden engine. The session has the
 * engine's saved settings, a real engine process that echoes its command on stderr and replies to
 * {@code version} with private text, a raw GTP trace, records logged from the engine's reader
 * threads, and records an earlier build left in the same logs. No entry of the bundle may carry
 * any of that text. A visible-command twin shows the same text does reach the bundle, so the hidden
 * test cannot pass vacuously.
 */
class HiddenEngineDiagnosticBundleTest {
  /** The engine's {@code version} reply, which is not a version KG-next can read. */
  private static final String VERSION_CANARY = "PrivateVersionCanary";

  private static final String STDERR_CANARY = "PrivateStderrCanary";
  private static final String OLD_BUILD_CANARY = "OldBuildCanary";

  /**
   * Parts of the launch command that survive the exporter's own path and secret redaction for a
   * visible command, so their absence from a hidden session's bundle is this application's doing: a
   * model file name and an override value.
   */
  private static final List<String> COMMAND_CANARIES =
      List.of("QuietNet-b40", "PrivateProvider-88");

  private static final DateTimeFormatter LOG_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  @TempDir Path tempDir;

  private Config oldConfig;
  private Leelaz oldPrimary;
  private Leelaz oldSecondary;
  private EngineManager oldManager;
  private Board oldBoard;
  private LizzieFrame oldFrame;
  private Menu oldMenu;
  private GtpConsolePane oldConsole;
  private int oldWidth;
  private int oldHeight;
  private int oldIndex;
  private int oldIndex2;
  private boolean oldEmpty;
  private LoggingRuntime runtime;
  private Leelaz engine;

  @BeforeEach
  void setUp() throws Exception {
    oldConfig = Lizzie.config;
    oldPrimary = Lizzie.leelaz;
    oldSecondary = Lizzie.leelaz2;
    oldManager = Lizzie.engineManager;
    oldBoard = Lizzie.board;
    oldFrame = Lizzie.frame;
    oldMenu = LizzieFrame.menu;
    oldConsole = Lizzie.gtpConsole;
    oldWidth = Board.boardWidth;
    oldHeight = Board.boardHeight;
    oldIndex = EngineManager.currentEngineNo;
    oldIndex2 = EngineManager.currentEngineNo2;
    oldEmpty = EngineManager.isEmpty;
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (engine != null && engine.isStarted()) engine.forceQuit();
      SwingUtilities.invokeAndWait(() -> {});
    } finally {
      LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
      Lizzie.engineStartupStatus.ready();
      Lizzie.config = oldConfig;
      Lizzie.setPrimaryEngine(oldPrimary);
      Lizzie.leelaz2 = oldSecondary;
      Lizzie.setEngineManager(oldManager);
      Lizzie.board = oldBoard;
      Lizzie.frame = oldFrame;
      LizzieFrame.menu = oldMenu;
      Lizzie.gtpConsole = oldConsole;
      Board.boardWidth = oldWidth;
      Board.boardHeight = oldHeight;
      Zobrist.init();
      EngineManager.currentEngineNo = oldIndex;
      EngineManager.currentEngineNo2 = oldIndex2;
      EngineManager.isEmpty = oldEmpty;
    }
  }

  @Test
  void bundleOfAHiddenEngineSessionCarriesNoPrivateText() throws Exception {
    String saved = runSession(true);
    Map<String, String> bundle = exportBundle();

    List<String> privateText = new ArrayList<>(COMMAND_CANARIES);
    privateText.addAll(List.of(VERSION_CANARY, STDERR_CANARY, OLD_BUILD_CANARY));
    privateText.addAll(blobPieces(saved));
    bundle.forEach((name, text) -> assertNoFragment(name + ":\n" + text, privateText));

    String app = bundle.get("logs/lizzie/app.log");
    // Recorded on the engine's reader thread, which has no session of its own.
    assertTrue(
        app.contains("engine event=version-unparsed error=java.lang.NumberFormatException: "
            + "<hidden-command>"),
        app);
    assertTrue(app.contains("command=<hidden-command>"), app);
    assertTrue(app.contains("model=hidden-command config=hidden-command"), app);
    assertTrue(app.contains("old-application-evidence"), app);
    assertTrue(trace(bundle).contains("gtp raw response=<hidden-command>"), trace(bundle));
    JSONObject savedEngine =
        new JSONObject(bundle.get("snapshots/config.json"))
            .getJSONObject("leelaz")
            .getJSONArray("engine-settings-list")
            .getJSONObject(0);
    assertEquals("hidden-command", savedEngine.getString("kind"));
    assertEquals("hidden-command", savedEngine.getString("executable"));
    assertOldBuildRecordsCounted(bundle);
  }

  @Test
  void bundleOfAVisibleEngineSessionCarriesItsText() throws Exception {
    runSession(false);
    Map<String, String> bundle = exportBundle();
    String all = String.join("\n", bundle.values());

    for (String canary : COMMAND_CANARIES)
      assertTrue(all.contains(canary), canary + " in " + bundle.get("logs/lizzie/app.log"));
    assertTrue(bundle.get("logs/lizzie/app.log").contains(VERSION_CANARY));
    assertTrue(all.contains(STDERR_CANARY));
    assertTrue(trace(bundle).contains(VERSION_CANARY), trace(bundle));
    // The rule for an earlier build's records does not depend on this session's engine.
    assertNoFragment(all, List.of(OLD_BUILD_CANARY));
    assertOldBuildRecordsCounted(bundle);
  }

  /** Runs one session to the engine's stop and returns the saved command. */
  private String runSession(boolean hidden) throws Exception {
    writeOldBuildRecords();
    runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    runtime.captureStandardError();
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    prepareApplication();

    String saved = saveEngine(hidden, canaryEngineCommand());
    engine = new NamedEngine(saved);
    assertEquals(
        hidden ? EngineCommandVisibility.HIDDEN : EngineCommandVisibility.VISIBLE,
        engine.engineCommandVisibility());
    EngineManager manager = new EngineManager(new ArrayList<>(List.of(engine)));
    Lizzie.setEngineManager(manager);
    assertTrue(manager.switchEngineIfAvailable(0, true));
    await(engine::isLoaded);
    await(() -> appLog().contains("engine event=version-unparsed"));
    String unparsed =
        appLog().lines().filter(line -> line.contains("version-unparsed")).findFirst().orElse("");
    assertTrue(unparsed.contains("session=" + runtime.applicationLogSessionId()), unparsed);
    engine.forceQuit();
    await(() -> !engine.isStarted());
    awaitIdle();
    return saved;
  }

  private Map<String, String> exportBundle() throws Exception {
    awaitIdle();
    LoggingRuntime.TraceSessionSnapshot trace = runtime.traceSessionSnapshot();
    DiagnosticBundleRequest request =
        new DiagnosticBundleRequest(runtime, trace.scopes(), Lizzie.config.config, null, "next-dev");
    Path zip =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
            .export(request);
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        if (!entry.isDirectory())
          entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    assertTrue(entries.containsKey("logs/lizzie/app.log"), entries.keySet().toString());
    return entries;
  }

  /** What an earlier build wrote for a hidden command, beside a record every rule keeps. */
  private void writeOldBuildRecords() throws IOException {
    Path logs = Files.createDirectories(tempDir.resolve("logs"));
    String at = LocalDateTime.now().minusMinutes(5).format(LOG_TIMESTAMP);
    Files.writeString(
        logs.resolve("app.log"),
        at
            + " WARN  [lizzie.engine] session=old-build engine event=failed reason=Cannot run"
            + " program \""
            + OLD_BUILD_CANARY
            + "-exe\"\n"
            + at
            + " ERROR [lizzie.app.stderr]  java.io.IOException: "
            + OLD_BUILD_CANARY
            + "-stack\n"
            + "\tat featurecat.lizzie.analysis.Leelaz.startEngine(Leelaz.java:1)\n"
            + at
            + " INFO  [lizzie.app] session=old-build old-application-evidence\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        logs.resolve("crash.log"),
        at
            + " ERROR [lizzie.crash] session=old-build uncaught exception thread=main\n"
            + "java.lang.IllegalStateException: "
            + OLD_BUILD_CANARY
            + "-crash\n"
            + "\tat featurecat.lizzie.Lizzie.main(Lizzie.java:1)\n",
        StandardCharsets.UTF_8);
  }

  private static void assertOldBuildRecordsCounted(Map<String, String> bundle) {
    JSONObject sources = new JSONObject(bundle.get("manifest.json")).getJSONObject("sources");
    JSONObject app = sources.getJSONObject("lizzie-app").getJSONObject("sensitiveRecordsExcluded");
    JSONObject crash =
        sources.getJSONObject("lizzie-crash").getJSONObject("sensitiveRecordsExcluded");
    assertEquals(1, app.getInt("otherSession"), app.toString());
    assertEquals(1, app.getInt("noSession"), app.toString());
    assertEquals(1, crash.getInt("otherSession"), crash.toString());
    assertEquals(0, crash.getInt("noSession"), crash.toString());
  }

  private static String trace(Map<String, String> bundle) {
    return bundle.entrySet().stream()
        .filter(entry -> entry.getKey().contains("engine-trace"))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse("");
  }

  /** A child JVM speaking GTP, with arguments naming a private model and setting. */
  private static String canaryEngineCommand() {
    String java =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    return "\""
        + java
        + "\" -cp \""
        + Path.of("target", "test-classes").toAbsolutePath()
        + "\" "
        + CanaryGtpEngine.class.getName()
        + " gtp -model QuietNet-b40.bin.gz -override-config onnxProvider=PrivateProvider-88";
  }

  /** Saves the engine through the engine settings writer, as the engine editor does. */
  private static String saveEngine(boolean hidden, String command) {
    String saved = hidden ? "encryption||" + Utils.doEncrypt2(command) : command;
    EngineData data = new EngineData();
    data.commands = saved;
    data.name = "Canary engine";
    data.width = data.height = 19;
    Utils.saveEngineSettings(new ArrayList<>(List.of(data)));
    return saved;
  }

  /** The saved blob and its pieces: a key every copy of the application holds opens it. */
  private static List<String> blobPieces(String saved) {
    String blob = saved.substring("encryption||".length());
    List<String> pieces = new ArrayList<>(List.of(blob));
    for (String piece : blob.split("[^A-Za-z0-9]+")) if (piece.length() >= 8) pieces.add(piece);
    return pieces;
  }

  private void prepareApplication() throws Exception {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir);
    Lizzie.config.config = new JSONObject();
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.config.leelazConfig = new JSONObject();
    Lizzie.config.extraMode = ExtraMode.Normal;
    Lizzie.config.fastChange = true;
    Lizzie.config.autoCheckEngineAlive = false;
    Lizzie.setPrimaryEngine(null);
    Lizzie.leelaz2 = null;
    LizzieFrame.menu = null;
    Board.boardWidth = Board.boardHeight = 19;
    Zobrist.init();
    Board board = allocate(Board.class);
    board.startStonelist = new ArrayList<>();
    board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
    Lizzie.board = board;
    Lizzie.frame = allocate(QuietFrame.class);
    Lizzie.gtpConsole = allocate(QuietConsole.class);
    EngineManager.currentEngineNo = EngineManager.currentEngineNo2 = -1;
    EngineManager.isEmpty = true;
  }

  private String appLog() {
    try {
      Path file = tempDir.resolve("logs/app.log");
      return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      return "";
    }
  }

  private void awaitIdle() throws Exception {
    Method awaitIdle =
        LoggingRuntime.class.getDeclaredMethod("awaitIdle", long.class, TimeUnit.class);
    awaitIdle.setAccessible(true);
    assertTrue((Boolean) awaitIdle.invoke(runtime, 10L, TimeUnit.SECONDS));
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(condition.getAsBoolean(), "the session did not reach the expected point");
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class NamedEngine extends Leelaz {
    NamedEngine(String command) throws IOException {
      super(command);
    }

    @Override
    public String getEngineName(int index) {
      return "Canary engine";
    }

    @Override
    long engineStartupSynchronizationTimeoutMillis() {
      return 10_000;
    }
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
    public void reSetLoc() {}

    @Override
    public void resetTitle() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }
  }

  /**
   * A GTP engine that names itself KataGo, echoes its arguments on stderr as engines report the
   * files they load, and replies to {@code version} with text that is not a version.
   */
  public static final class CanaryGtpEngine {
    private CanaryGtpEngine() {}

    public static void main(String[] args) throws IOException {
      PrintStream err =
          new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
      PrintStream out =
          new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
      err.println("Loading " + String.join(" ", args));
      err.println(STDERR_CANARY + " model loaded");
      BufferedReader in =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      for (String line; (line = in.readLine()) != null; ) {
        String[] words = line.trim().split("\\s+");
        int first = words.length > 0 && words[0].matches("\\d+") ? 1 : 0;
        String id = first == 1 ? words[0] : "";
        String command = first < words.length ? words[first] : "";
        String answer =
            switch (command) {
              case "name" -> "KataGo";
              case "version" -> VERSION_CANARY + ".7";
              case "list_commands" ->
                  "name\nversion\nlist_commands\nkomi\nboardsize\nclear_board\nplay\nquit";
              default -> "";
            };
        out.print("=" + id + " " + answer + "\n\n");
        out.flush();
        if (command.equals("quit")) break;
      }
    }
  }
}
