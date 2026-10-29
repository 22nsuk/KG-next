package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.SgfSaveTestSupport;
import featurecat.lizzie.util.AutomaticSaveIntent;
import featurecat.lizzie.util.AtomicSgfFileWriter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Stops a separate JVM between actual atomic replacements, bypassing application catch/finally
 * handlers. This checks process interruption, not power-loss durability or a multi-file transaction.
 */
class AutomaticSaveInterruptionTest {
  private static final String OLD = "(;SZ[5]C[OLD_EXIT];B[aa])";
  private static final String NEW = "(;SZ[5]C[NEW_RECORD];B[bb];W[cc](;B[dd])(;B[ee]C[SIBLING]))";
  private static final int INTERRUPTED = 73;
  @TempDir Path directory;
  private RulesLayerTestHarness rules;
  private File previousCurFile;
  private String oldManifest;
  private byte[] oldPreview;

  @BeforeEach
  void prepare() throws Exception {
    SgfSaveTestSupport.awaitPending();
    rules = RulesLayerTestHarness.open();
    previousCurFile = LizzieFrame.curFile;
    Config config = ConfigTestHelper.createForTests(directory);
    config.saveBoardConfig = new JSONObject()
        .put("save-auto-game-index1", 1).put("save-auto-game-index2", -1)
        .put("save-auto-game-time1", "old exit").put("save-auto-game-time2", "old periodic")
        .put("save-auto-game-move-number1", 1).put("save-auto-game-move-number2", 1)
        .put("save-auto-game-move-list1", "old exit cursor")
        .put("save-auto-game-move-list2", "old periodic cursor")
        .put("save-game-index", new JSONArray(List.of(1)))
        .put("save-game-name", new JSONArray(List.of("old manual")))
        .put("save-game-time", new JSONArray(List.of("old manual time")))
        .put("save-game-move-number", new JSONArray(List.of(1)))
        .put("save-game-move-list", new JSONArray(List.of("old manual cursor")));
    config.saveBoard = new JSONObject().put("save", config.saveBoardConfig).put("other", "keep");
    Files.createDirectories(directory.resolve("save"));
    config.saveTempBoard();
    oldManifest = Files.readString(manifest());
    for (String name : List.of("autoGame1", "autoGame2", "game1")) {
      Files.writeString(slot(name, "sgf"), OLD);
      assertTrue(ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB),
          "bmp", slot(name, "bmp").toFile()));
    }
    oldPreview = Files.readAllBytes(slot("autoGame1", "bmp"));
  }

  @AfterEach
  void restore() throws Exception {
    try {
      SgfSaveTestSupport.awaitPending();
    } finally {
      LizzieFrame.curFile = previousCurFile;
      if (rules != null) rules.close();
    }
  }

  @ParameterizedTest
  @CsvSource({"EXIT,false", "EXIT,true", "EXIT_ACTIVE,false", "EXIT_ACTIVE,true",
      "PERIODIC,false", "PERIODIC,true",
      "PERIODIC_ACTIVE,false", "PERIODIC_ACTIVE,true",
      "REPLACE,false", "REPLACE,true", "ADD,false", "ADD,true"})
  void interruptionKeepsCompleteSgfAndExposesOnlyCommittedMetadata(String kind, boolean committed)
      throws Exception {
    String name = name(kind);
    boolean activePeriodic = kind.endsWith("_ACTIVE");
    if (activePeriodic) {
      JSONObject active = new JSONObject(oldManifest);
      active.getJSONObject("save").put("save-auto-game-index2", -5);
      Files.writeString(manifest(), active.toString(2));
      oldManifest = Files.readString(manifest());
    }
    interrupt(kind, committed ? "writeGamePreview" : "write", committed
        ? LizzieFrame.class.getName() : Config.SavedBoardSave.class.getName());

    String saved = Files.readString(slot(name, "sgf"));
    assertCompleteNewGame(saved);
    JSONObject disk = new JSONObject(Files.readString(manifest()));
    boolean automatic = kind.startsWith("EXIT") || kind.startsWith("PERIODIC");
    JSONObject intended = automatic
        ? AutomaticSaveIntent.read(directory).mergeInto(new JSONObject()) : disk.getJSONObject("save");
    assertEquals("keep", disk.getString("other"));
    if (!committed) assertEquals(oldManifest, Files.readString(manifest()));
    if (kind.equals("ADD")) assertFalse(Files.exists(slot(name, "bmp")));
    else assertArrayEquals(oldPreview, Files.readAllBytes(slot(name, "bmp")),
        "abrupt death before preview replacement leaves the previous complete image");

    AutomaticSaveRecoveryTest.RecoveryFrame restarted = restart();
    if (kind.startsWith("PERIODIC")) {
      assertEquals(saved, restarted.loadedSgf);
      assertEquals(saved, Files.readString(slot("autoGame2", "sgf")));
      assertEquals(-1, Lizzie.config.saveBoardConfig.getInt("save-auto-game-index2"));
      assertEquals(3,
          Lizzie.config.saveBoardConfig.getInt("save-auto-game-move-number1"));
      assertEquals(saved, Files.readString(slot("autoGame1", "sgf")));
      assertEquals(intended.getString("save-auto-game-move-list2"),
          Lizzie.config.saveBoardConfig.getString("save-auto-game-move-list1"));
    } else if (kind.startsWith("EXIT")) {
      assertEquals(saved, restarted.loadedSgf);
      assertEquals(3,
          Lizzie.config.saveBoardConfig.getInt("save-auto-game-move-number1"));
      assertEquals(intended.getString("save-auto-game-move-list1"),
          Lizzie.config.saveBoardConfig.getString("save-auto-game-move-list1"));
      if (activePeriodic && !committed) {
        assertEquals(saved, Files.readString(slot("autoGame1", "sgf")));
        assertEquals(OLD, Files.readString(slot("autoGame2", "sgf")));
      }
    } else {
      assertEquals(OLD, restarted.loadedSgf);
      assertEquals(committed ? (kind.equals("ADD") ? 2 : 1) : 1,
          Lizzie.config.saveBoardConfig.getJSONArray("save-game-index").length());
      if (kind.equals("REPLACE")) assertEquals(committed ? 3 : 1,
          Lizzie.config.saveBoardConfig.getJSONArray("save-game-move-number").getInt(0));
    }
    assertNoStagingFiles();

    // Restarted state can save again. An unlisted ADD orphan reserves its identity.
    setCurrentBoard();
    assertTrue(save(restarted, kind));
    String retriedName = kind.equals("ADD") ? "game3" : name;
    assertCompleteNewGame(Files.readString(slot(retriedName, "sgf")));
    assertEquals(8, ImageIO.read(slot(retriedName, "bmp").toFile()).getWidth());
    if (kind.equals("ADD")) assertEquals(saved, Files.readString(slot("game2", "sgf")),
        "retry must preserve the orphan or committed source file, never reuse its identifier");
    assertNoStagingFiles();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void interruptedRecoveryPreservesSourceAndRetriesFromDisk(boolean previewReplaced)
      throws Exception {
    JSONObject pending = new JSONObject(oldManifest);
    pending.getJSONObject("save").put("save-auto-game-index2", -5)
        .put("save-auto-game-move-number2", 3).put("save-auto-game-move-list2", "source cursor");
    Files.writeString(manifest(), pending.toString(2));
    oldManifest = Files.readString(manifest());
    Files.writeString(slot("autoGame2", "sgf"), NEW);
    assertTrue(ImageIO.write(new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "bmp",
        slot("autoGame2", "bmp").toFile()));
    byte[] sourcePreview = Files.readAllBytes(slot("autoGame2", "bmp"));

    interrupt("RECOVER", previewReplaced ? "write" : "recoverAutoSavePreview", previewReplaced
        ? Config.SavedBoardSave.class.getName() : LizzieFrame.class.getName());
    assertEquals(NEW, Files.readString(slot("autoGame1", "sgf")));
    assertEquals(NEW, Files.readString(slot("autoGame2", "sgf")));
    assertEquals(oldManifest, Files.readString(manifest()));
    assertArrayEquals(sourcePreview, Files.readAllBytes(slot("autoGame2", "bmp")));
    assertArrayEquals(previewReplaced ? sourcePreview : oldPreview,
        Files.readAllBytes(slot("autoGame1", "bmp")));

    AutomaticSaveRecoveryTest.RecoveryFrame restarted = restart();
    assertEquals(NEW, restarted.loadedSgf);
    assertEquals(3, Lizzie.board.getCurrentMovenumber());
    assertEquals(-1, Lizzie.config.saveBoardConfig.getInt("save-auto-game-index2"));
    assertEquals("source cursor", Lizzie.config.saveBoardConfig.getString("save-auto-game-move-list1"));
    assertArrayEquals(sourcePreview, Files.readAllBytes(slot("autoGame1", "bmp")));
    assertEquals(NEW, Files.readString(slot("autoGame2", "sgf")));
    setCurrentBoard();
    assertTrue(restarted.saveAutoGame(2));
    assertEquals(NEW, Files.readString(slot("autoGame1", "sgf")));
    assertNoStagingFiles();
  }

  @Test
  void firstPeriodicInterruptedBeforeManifestResumesNewGameBeforeTheNextAutomaticSave()
      throws Exception {
    interrupt("PERIODIC", "write", Config.SavedBoardSave.class.getName());
    String unadvertised = Files.readString(slot("autoGame2", "sgf"));
    assertCompleteNewGame(unadvertised);
    assertEquals(oldManifest, Files.readString(manifest()));

    var restarted = restart();
    assertEquals(unadvertised, restarted.loadedSgf);
    assertEquals(unadvertised, Files.readString(slot("autoGame2", "sgf")));
    // Do not replace the resumed board by hand: the normal next periodic save must keep NEW.
    assertTrue(restarted.saveAutoGame(2));
    assertEquals("NEW_RECORD", SGFParser.parseSgf(Files.readString(slot("autoGame2", "sgf")), true)
        .getStart().getData().comment);
    assertEquals(unadvertised, Files.readString(slot("autoGame1", "sgf")));
    assertNoStagingFiles();
  }

  @ParameterizedTest
  @ValueSource(strings = {"EXIT", "PERIODIC"})
  void interruptedBeforeSgfReplacementKeepsThePreviousRecord(String kind) throws Exception {
    interrupt(kind, "write", AtomicSgfFileWriter.class.getName());
    assertNotNull(AutomaticSaveIntent.read(directory));
    assertEquals(OLD, Files.readString(slot(name(kind), "sgf")));
    assertEquals(oldManifest, Files.readString(manifest()));
    assertEquals(OLD, restart().loadedSgf);
    assertNull(AutomaticSaveIntent.read(directory));
    assertEquals(oldManifest, Files.readString(manifest()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"EXIT_ACTIVE", "PERIODIC"})
  void intentMetadataFailureResumesItsSourceAndBlocksBothWritesUntilRetry(String kind)
      throws Exception {
    if (kind.equals("EXIT_ACTIVE")) {
      JSONObject active = new JSONObject(oldManifest);
      active.getJSONObject("save").put("save-auto-game-index2", -5);
      Files.writeString(manifest(), active.toString(2));
    }
    interrupt(kind, "write", Config.SavedBoardSave.class.getName());
    String saved = Files.readString(slot(name(kind), "sgf"));
    Config config = readConfig(directory);
    config.saveBoardConfig.getJSONArray("save-game-name").put(0, "later rename");
    config.saveTempBoard();
    Field filename = Config.class.getDeclaredField("saveBoardFilename");
    filename.setAccessible(true);
    Path blocked = directory.resolve("blocked-manifest");
    Files.createDirectory(blocked);
    Files.writeString(blocked.resolve("keep"), "keep");
    filename.set(config, blocked.toString());

    var restarted = restart(config, false);
    assertEquals(saved, restarted.loadedSgf);
    assertFalse(restarted.saveAutoGame(1));
    assertFalse(restarted.saveAutoGame(2));
    assertEquals(saved, Files.readString(slot(name(kind), "sgf")));
    assertNotNull(AutomaticSaveIntent.read(directory));
    assertEquals("keep", Files.readString(blocked.resolve("keep")));
    filename.set(config, manifest().toString());
    assertTrue(restarted.saveAutoGame(2));
    assertNull(AutomaticSaveIntent.read(directory));
    assertEquals(saved, Files.readString(slot("autoGame1", "sgf")));
    assertEquals("later rename", config.saveBoardConfig.getJSONArray("save-game-name").getString(0));
  }

  @Test
  void recoveredIntentRetainsPeriodicSourceWhenPromotionFails() throws Exception {
    interrupt("PERIODIC", "write", Config.SavedBoardSave.class.getName());
    String saved = Files.readString(slot("autoGame2", "sgf"));
    Files.delete(slot("autoGame1", "sgf"));
    Files.createDirectory(slot("autoGame1", "sgf"));
    Path blocker = slot("autoGame1", "sgf").resolve("keep");
    Files.writeString(blocker, "keep");
    var restarted = restart(readConfig(directory), false);
    assertEquals(saved, restarted.loadedSgf);
    assertNull(AutomaticSaveIntent.read(directory), "intent metadata committed before promotion");
    assertEquals(-5, Lizzie.config.saveBoardConfig.getInt("save-auto-game-index2"));
    assertFalse(restarted.saveAutoGame(1));
    assertFalse(restarted.saveAutoGame(2));
    assertEquals(saved, Files.readString(slot("autoGame2", "sgf")));
    Files.delete(blocker);
    Files.delete(slot("autoGame1", "sgf"));
    assertTrue(restarted.saveAutoGame(2));
    assertEquals(saved, Files.readString(slot("autoGame1", "sgf")));
  }

  @Test
  void failedIntentCleanupAfterManifestCommitIsRecoverable() throws Exception {
    runWriter("EXIT", "clear", AutomaticSaveIntent.class.getName(), true);
    String saved = Files.readString(slot("autoGame1", "sgf"));
    assertCompleteNewGame(saved);
    assertNotNull(AutomaticSaveIntent.read(directory));
    assertEquals(3, new JSONObject(Files.readString(manifest())).getJSONObject("save")
        .getInt("save-auto-game-move-number1"));
    var restarted = restart();
    assertEquals(saved, restarted.loadedSgf);
    assertNull(AutomaticSaveIntent.read(directory));
    assertTrue(restarted.saveAutoGame(1));
    assertCompleteNewGame(Files.readString(slot("autoGame1", "sgf")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNCHANGED", "COMMENT", "OPENED"})
  void repairedUnreadableIntentRetiresOnlyTheUnchangedFallbackSave(String change) throws Exception {
    interrupt("PERIODIC", "write", Config.SavedBoardSave.class.getName());
    Path marker = directory.resolve("save/automatic-save-intent.json");
    String validIntent = Files.readString(marker);
    Files.delete(marker);
    Files.createDirectory(marker);
    Files.writeString(marker.resolve("keep"), "keep");
    var restarted = restart(readConfig(directory), false);
    assertEquals(OLD, restarted.loadedSgf);
    assertFalse(restarted.saveAutoGame(1));
    assertFalse(restarted.saveAutoGame(2));
    boolean edited = !change.equals("UNCHANGED");
    if (change.equals("OPENED")) {
      Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5]C[USER_EDIT];B[ee])", true));
    } else if (change.equals("COMMENT")) {
      Lizzie.board.getHistory().getStart().getData().comment = "USER_EDIT";
    }
    Files.delete(marker.resolve("keep"));
    Files.delete(marker);
    Files.writeString(marker, validIntent);
    assertEquals(edited, restarted.saveAutoGame(1),
        "an unchanged fallback request is retired after loading the recovered game");
    assertTrue(restarted.saveAutoGame(1));
    assertEquals(edited ? "USER_EDIT" : "NEW_RECORD",
        SGFParser.parseSgf(Files.readString(slot("autoGame1", "sgf")), true)
            .getStart().getData().comment);
  }

  @Test
  void repairedUnreadableExitSourceDoesNotOverwriteItWithTheStartupFallback() throws Exception {
    interrupt("EXIT", "write", Config.SavedBoardSave.class.getName());
    Path target = slot("autoGame1", "sgf");
    String saved = Files.readString(target);
    Files.delete(target);
    Files.createDirectory(target);
    Files.writeString(target.resolve("keep"), "keep");
    var restarted = restart(readConfig(directory), false);
    assertFalse(restarted.saveAutoGame(1));
    assertNotNull(AutomaticSaveIntent.read(directory));
    Files.delete(target.resolve("keep"));
    Files.delete(target);
    Files.writeString(target, saved);
    assertFalse(restarted.saveAutoGame(1), "retire the captured startup fallback after recovery");
    assertEquals(saved, restarted.loadedSgf);
    assertEquals(saved, Files.readString(target));
    assertTrue(restarted.saveAutoGame(1));
    assertCompleteNewGame(Files.readString(target));
  }

  @Test
  void concurrentRetriesRetireEveryCaptureOfTheUnchangedStartupFallback() throws Exception {
    interrupt("EXIT", "write", Config.SavedBoardSave.class.getName());
    Path target = slot("autoGame1", "sgf");
    String saved = Files.readString(target);
    Files.delete(target);
    Files.createDirectory(target);
    Files.writeString(target.resolve("keep"), "keep");
    var restarted = restart(readConfig(directory), false);
    Files.delete(target.resolve("keep"));
    Files.delete(target);
    Files.writeString(target, saved);
    try (var blocked = SgfSaveTestSupport.blockWorker()) {
      var first = restarted.saveAutoGameAsync(1);
      var second = restarted.saveAutoGameAsync(2);
      SwingUtilities.invokeAndWait(() -> {});
      blocked.release();
      assertFalse(first.get(10, TimeUnit.SECONDS));
      assertFalse(second.get(10, TimeUnit.SECONDS));
    }
    assertEquals(saved, restarted.loadedSgf);
    assertEquals(saved, Files.readString(target));
    assertTrue(restarted.saveAutoGame(1));
    assertCompleteNewGame(Files.readString(target));
  }

  @Test
  void uncapturableUnresolvedStartupDoesNotEnableAutomaticWrites() throws Exception {
    Files.writeString(directory.resolve("save/automatic-save-intent.json"), "{broken");
    Config config = readConfig(directory);
    config.autoResume = true;
    Lizzie.config = config;
    var frame = RulesLayerTestHarness.allocate(UncapturableStartupFrame.class);
    Lizzie.frame = frame;
    ExecutionException failure = assertThrows(ExecutionException.class,
        () -> frame.restoreStartupGameAsync(new String[0]).get(10, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertFalse(frame.saveAutoGameAsync(1).get(5, TimeUnit.SECONDS));
    assertFalse(frame.saveAutoGameAsync(2).get(5, TimeUnit.SECONDS));
    assertEquals(OLD, Files.readString(slot("autoGame1", "sgf")));
    assertEquals(OLD, Files.readString(slot("autoGame2", "sgf")));
  }

  @Test
  void changedConfigDoesNotResumeThePreviousOwnersFallback() throws Exception {
    interrupt("PERIODIC", "write", Config.SavedBoardSave.class.getName());
    Path marker = directory.resolve("save/automatic-save-intent.json");
    Files.writeString(marker, "{broken");
    String saved = Files.readString(slot("autoGame2", "sgf"));
    var restarted = restart(readConfig(directory), false);
    File loaded = restarted.loadedFile;
    Path other = directory.resolve("other-config");
    Files.createDirectories(other.resolve("save"));
    Config changed = ConfigTestHelper.createForTests(other);
    changed.saveBoard = new JSONObject(oldManifest);
    changed.saveBoardConfig = changed.saveBoard.getJSONObject("save");
    changed.saveTempBoard();
    Files.writeString(other.resolve("save/autoGame1.sgf"), "(;SZ[5]C[OTHER_CONFIG])");
    Lizzie.config = changed;
    assertTrue(restarted.saveAutoGame(1));
    assertEquals(loaded, restarted.loadedFile, "the old fallback must not trigger a new config load");
    assertEquals("OLD_EXIT", SGFParser.parseSgf(Files.readString(other.resolve("save/autoGame1.sgf")), true)
        .getStart().getData().comment);
    assertEquals(saved, Files.readString(slot("autoGame2", "sgf")));
    assertEquals("{broken", Files.readString(marker));
  }

  private AutomaticSaveRecoveryTest.RecoveryFrame restart() throws Exception {
    return restart(readConfig(directory), true);
  }

  private AutomaticSaveRecoveryTest.RecoveryFrame restart(Config config, boolean success) throws Exception {
    config.autoResume = true;
    Lizzie.config = config;
    Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5]C[FRESH_PROCESS])", true));
    var frame = RulesLayerTestHarness.allocate(StartupFrame.class);
    Lizzie.frame = frame;
    frame.restoreStartupGame(new String[0]);
    assertEquals(success ? 0 : 1, frame.recoveryFailures);
    assertTrue(new JSONObject(Files.readString(manifest())).getJSONObject("save")
        .similar(config.saveBoardConfig));
    return frame;
  }

  private static Config readConfig(Path directory) throws Exception {
    Config config = ConfigTestHelper.createForTests(directory);
    config.saveBoard = new JSONObject(Files.readString(directory.resolve("save/save")));
    config.saveBoardConfig = config.saveBoard.getJSONObject("save");
    return config;
  }

  private static void setCurrentBoard() {
    Lizzie.board.setHistory(SGFParser.parseSgf(NEW, true));
    Lizzie.board.getHistory().setHead(Lizzie.board.getHistory().getEnd());
  }

  private static boolean save(LizzieFrame frame, String kind) {
    switch (kind) {
      case "EXIT":
      case "EXIT_ACTIVE": return frame.saveAutoGame(1);
      case "PERIODIC":
      case "PERIODIC_ACTIVE": return frame.saveAutoGame(2);
      case "REPLACE": return frame.saveTempGame(1, "new manual");
      case "ADD": return frame.addTempGame(2, "new manual");
      default: throw new IllegalArgumentException(kind);
    }
  }

  private static String name(String kind) {
    switch (kind) {
      case "EXIT":
      case "EXIT_ACTIVE": return "autoGame1";
      case "PERIODIC":
      case "PERIODIC_ACTIVE": return "autoGame2";
      case "REPLACE": return "game1";
      case "ADD": return "game2";
      default: throw new IllegalArgumentException(kind);
    }
  }

  private void assertCompleteNewGame(String sgf) {
    BoardHistoryList parsed = SGFParser.parseSgf(sgf, true);
    assertNotNull(parsed);
    assertEquals("NEW_RECORD", parsed.getStart().getData().comment);
    assertEquals(3, parsed.getEnd().getData().moveNumber);
    assertTrue(sgf.contains("C[SIBLING]"));
    assertTrue(sgf.endsWith(")"));
  }

  private Path manifest() { return directory.resolve("save/save"); }
  private Path slot(String name, String extension) {
    return directory.resolve("save/" + name + "." + extension);
  }

  private void assertNoStagingFiles() throws Exception {
    try (var files = Files.walk(directory)) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
  }

  private void interrupt(String action, String method, String owner) throws Exception {
    runWriter(action, method, owner, false);
  }

  private void runWriter(String action, String method, String owner, boolean failCleanup) throws Exception {
    var connector = Bootstrap.virtualMachineManager().defaultConnector();
    var arguments = connector.defaultArguments();
    String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    arguments.get("options").setValue("-cp \"" + classpath + "\"");
    arguments.get("main").setValue(InterruptedWriter.class.getName() + " \"" + directory + "\" " + action
        + (failCleanup ? " fail" : ""));
    VirtualMachine vm = connector.launch(arguments);
    Process process = vm.process();
    try {
      var request = vm.eventRequestManager().createMethodEntryRequest();
      request.addClassFilter(owner);
      request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
      request.enable();
      vm.resume();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      boolean reached = false;
      while (!reached && System.nanoTime() < deadline) {
        EventSet events = vm.eventQueue().remove(1000);
        if (events == null) continue;
        for (Event event : events) {
          if (event instanceof MethodEntryEvent
              && ((MethodEntryEvent) event).method().name().equals(method)) reached = true;
          if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
            fail("Writer exited before interruption point " + owner + "." + method);
          }
        }
        if (!reached) events.resume();
      }
      assertTrue(reached, "Writer never reached " + owner + "." + method);
      if (failCleanup) {
        // Change only whitespace while suspended before clear(): its stale identity guard
        // must report a normal IOException without deleting the still-valid recovery record.
        Path marker = directory.resolve("save/automatic-save-intent.json");
        Files.writeString(marker, Files.readString(marker) + "\n");
        request.disable();
        vm.resume();
      } else {
        // JDI exit terminates the VM without application finally blocks or shutdown hooks.
        vm.exit(INTERRUPTED);
      }
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      assertEquals(failCleanup ? 0 : INTERRUPTED, process.exitValue());
    } finally {
      if (process.isAlive()) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
    }
  }

  /** Separate process entry, using the same production save and startup paths as the GUI. */
  public static final class InterruptedWriter {
    public static void main(String[] args) throws Exception {
      try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
        Lizzie.config = readConfig(Path.of(args[0]));
        var frame = RulesLayerTestHarness.allocate(AutomaticSaveRecoveryTest.RecoveryFrame.class);
        Lizzie.frame = frame;
        setCurrentBoard();
        // Model an already running session whose earlier periodic save activated source2.
        // Startup recovery was already completed; the disk manifest remains active throughout.
        boolean activePeriodic = args[1].endsWith("_ACTIVE");
        if (activePeriodic) Lizzie.config.saveBoardConfig.put("save-auto-game-index2", -1);
        frame.restoreStartupGame(new String[] {"read"});
        if (activePeriodic) Lizzie.config.saveBoardConfig.put("save-auto-game-index2", -5);
        if (!args[1].equals("RECOVER")) {
          assertEquals(args.length < 3, save(frame, args[1]));
        }
      }
      System.exit(0);
    }
  }

  static class StartupFrame extends AutomaticSaveRecoveryTest.RecoveryFrame {
    @Override
    public boolean loadFile(File file, boolean fromTemp, boolean showHint) {
      // Production loadFile reports an unreadable SGF as false, leaving the current board.
      return Files.isRegularFile(file.toPath()) && super.loadFile(file, fromTemp, showHint);
    }
  }

  static class UncapturableStartupFrame extends StartupFrame {
    @Override
    public boolean loadFile(File file, boolean fromTemp, boolean showHint) {
      boolean loaded = super.loadFile(file, fromTemp, showHint);
      if (loaded) Lizzie.board.getHistory().setGameInfo(new GameInfo() {
        @Override public String getPlayerBlack() { throw new IllegalStateException("snapshot unavailable"); }
      });
      return loaded;
    }
  }
}
