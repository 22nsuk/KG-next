package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.SgfSaveTestSupport;
import featurecat.lizzie.gui.TempGameData;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutomaticSaveRecoveryTest {
  private static final String OLD = "(;SZ[5]C[OLD_EXIT];B[aa])";
  private static final String NEW = "(;SZ[5]C[NEW_PERIODIC];B[aa];W[bb])";
  private static final String CURRENT = "(;SZ[5]C[CURRENT_SESSION];B[dd])";
  @TempDir Path directory;
  private RulesLayerTestHarness rules;
  private RecoveryFrame frame;
  private Config config;
  private String originalMetadata;
  private File previousCurFile;

  @BeforeEach
  void prepare() throws Exception {
    SgfSaveTestSupport.awaitPending();
    rules = RulesLayerTestHarness.open();
    previousCurFile = LizzieFrame.curFile;
    config = ConfigTestHelper.createForTests(directory);
    config.autoResume = true;
    config.saveBoardConfig =
        new JSONObject()
            .put("save-auto-game-index1", 1)
            .put("save-auto-game-index2", -5)
            .put("save-auto-game-time1", "old time")
            .put("save-auto-game-time2", "new time")
            .put("save-auto-game-move-number1", 1)
            .put("save-auto-game-move-number2", 2)
            .put("save-auto-game-move-list1", "old cursor")
            .put("save-auto-game-move-list2", "new cursor");
    config.saveBoard = new JSONObject().put("save", config.saveBoardConfig).put("other", "keep");
    Files.createDirectories(directory.resolve("save"));
    config.saveTempBoard();
    originalMetadata = Files.readString(metadataFile());
    Files.writeString(slot(1, "sgf"), OLD);
    Files.writeString(slot(2, "sgf"), NEW);
    ImageIO.write(
        new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "bmp", slot(1, "bmp").toFile());
    ImageIO.write(
        new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "bmp", slot(2, "bmp").toFile());
    Lizzie.config = config;
    frame = RulesLayerTestHarness.allocate(RecoveryFrame.class);
    Lizzie.frame = frame;
    setCurrentBoard();
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

  @Test
  void periodicSaveAndStartupRecoveryPreserveSetupTurnDummySiblingAndAnalysis() throws Exception {
    // Complete the previous recovery first, then create a new crash-recovery record through
    // the production atomic save path rather than writing a hand-made recovery file.
    frame.restoreStartupGame(new String[0]);
    String sgf = "(;SZ[5]HA[2]KM[0.5]AB[aa][ee]PL[W]C[SETUP]"
        + "LZ[KataGo 45 123 2.5 1.2 0 rootVisits=123\n"
        + "move B4 visits 200 winrate 5500 pv B4]"
        + "(;W[bb]C[REAL];B[dd])(;DD[true]C[PLACEHOLDER]LB[cc:X]))";
    Lizzie.board.setHistory(SGFParser.parseSgf(sgf, true));
    BoardHistoryNode originalRoot = Lizzie.board.getHistory().getStart();
    assertFalse(originalRoot.getData().blackToPlay);
    assertEquals(123, originalRoot.getData().getPlayouts());
    frame.saveAutoGame(2);
    String saved = Files.readString(slot(2, "sgf"));
    assertTrue(saved.contains("DD[true]"));
    assertEquals(-5, config.saveBoardConfig.getInt("save-auto-game-index2"));
    String savedCursor = config.saveBoardConfig.getString("save-auto-game-move-list2");
    assertSame(originalRoot, Lizzie.board.getHistory().getStart(), "save must not replace the live tree");

    // Reload the disk manifest, as a new process would, retaining the older exit record.
    config.saveBoard = new JSONObject(Files.readString(metadataFile()));
    config.saveBoardConfig = config.saveBoard.getJSONObject("save");
    setCurrentBoard();
    frame = RulesLayerTestHarness.allocate(RecoveryFrame.class);
    Lizzie.frame = frame;
    frame.restoreStartupGame(new String[0]);
    assertEquals(saved, frame.loadedSgf);
    assertEquals(saved, Files.readString(slot(1, "sgf")));
    assertEquals(saved, Files.readString(slot(2, "sgf")), "recovery source stays intact");
    assertEquals(savedCursor, config.saveBoardConfig.getString("save-auto-game-move-list1"));
    assertEquals(-1, config.saveBoardConfig.getInt("save-auto-game-index2"));
    BoardHistoryNode recovered = Lizzie.board.getHistory().getStart();
    assertFalse(recovered.getData().blackToPlay);
    assertEquals(0.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertEquals(Stone.BLACK, recovered.getData().stones[Board.getIndex(0, 0)]);
    assertEquals(Stone.BLACK, recovered.getData().stones[Board.getIndex(4, 4)]);
    assertEquals(123, recovered.getData().getPlayouts());
    assertEquals(123, recovered.getData().rootVisits);
    assertEquals(200, recovered.getData().bestMoves.get(0).playouts);
    assertEquals(2, recovered.getVariations().size());
    BoardHistoryNode real = recovered.getVariations().get(0);
    BoardHistoryNode dummy = recovered.getVariations().get(1);
    assertEquals(Stone.WHITE, real.getData().lastMoveColor);
    assertEquals("REAL", real.getData().comment);
    assertTrue(dummy.getData().dummy);
    assertEquals("PLACEHOLDER", dummy.getData().comment);
    assertSame(real.getVariations().get(0), Lizzie.board.getHistory().getCurrentHistoryNode());
    assertEquals(Stone.BLACK, Lizzie.board.getData().lastMoveColor);
  }

  @Test
  void startupRecoversBeforeResumeAndExitCannotOverwriteWithTheOldGame() throws Exception {
    frame.restoreStartupGame(new String[0]);
    assertEquals(slot(1, "sgf").toFile(), frame.loadedFile);
    assertEquals(NEW, frame.loadedSgf);
    assertEquals("new time", frame.metadataAtLoad.getString("save-auto-game-time1"));
    assertEquals(2, Lizzie.board.getCurrentMovenumber());
    assertRecovered();
    assertEquals(5, ImageIO.read(slot(1, "bmp").toFile()).getWidth());
    frame.saveAutoGame(1);
    assertTrue(Files.readString(slot(1, "sgf")).contains("NEW_PERIODIC"));
    assertFalse(Files.readString(slot(1, "sgf")).contains("OLD_EXIT"));
    assertEquals(NEW, Files.readString(slot(2, "sgf")));
  }

  @Test
  void recoveryUsesConfiguredWorkDirectoryEvenWhenAutoResumeIsDisabled() throws Exception {
    assertNotEquals(Path.of("").toAbsolutePath(), directory.toAbsolutePath());
    config.autoResume = false;
    frame.restoreStartupGame(new String[0]);
    assertNull(frame.loadedFile);
    assertRecovered();
    assertTrue(SGFParser.saveToString(false).contains("CURRENT_SESSION"));
    frame.saveAutoGame(2);
    assertEquals(NEW, Files.readString(slot(1, "sgf")));
    assertTrue(Files.readString(slot(2, "sgf")).contains("CURRENT_SESSION"));
    // A current-session -5 marker must not be treated as another startup recovery.
    Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5]C[AFTER_PERIODIC];B[cc])", true));
    frame.saveAutoGame(1);
    assertTrue(Files.readString(slot(1, "sgf")).contains("AFTER_PERIODIC"));
    assertEquals(-1, config.saveBoardConfig.getInt("save-auto-game-index2"));
  }

  @Test
  void savesBeforeStartupSelectionCannotOverwriteEitherSlot() throws Exception {
    assertFalse(frame.saveAutoGame(2));
    assertFalse(frame.saveAutoGame(1));
    assertEquals(OLD, Files.readString(slot(1, "sgf")));
    assertEquals(NEW, Files.readString(slot(2, "sgf")));
    assertEquals(originalMetadata, Files.readString(metadataFile()));
  }

  @Test
  void deferredStartupKeepsBothSlotsProtectedUntilTheContinuationRuns() throws Exception {
    Field cleanup = LizzieFrame.class.getDeclaredField("analysisControlCleanupInProgress");
    cleanup.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            cleanup.setBoolean(frame, true);
          } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
          }
        });
    CompletableFuture<Void> startup = frame.restoreStartupGameAsync(new String[0]);
    SwingUtilities.invokeAndWait(() -> {});
    assertFalse(startup.isDone());
    assertFalse(frame.saveAutoGame(2));
    assertFalse(frame.saveAutoGame(1));
    assertNull(frame.loadedFile);
    assertEquals(OLD, Files.readString(slot(1, "sgf")));
    assertEquals(NEW, Files.readString(slot(2, "sgf")));
    assertEquals(originalMetadata, Files.readString(metadataFile()));
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            cleanup.setBoolean(frame, false);
            var finish =
                LizzieFrame.class.getDeclaredMethod("finishDeferredKifuOpenAfterQuickAnalysisRestore");
            finish.setAccessible(true);
            assertEquals(true, finish.invoke(frame));
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    startup.get(5, TimeUnit.SECONDS);
    assertRecovered();
    assertTrue(frame.saveAutoGame(1));
    assertTrue(Files.readString(slot(1, "sgf")).contains("NEW_PERIODIC"));
  }

  @Test
  void supersededStartupSettlesItsFutureAndProtectsRecoveryUntilSelectionIsRetried() throws Exception {
    Field cleanup = LizzieFrame.class.getDeclaredField("analysisControlCleanupInProgress");
    cleanup.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            cleanup.setBoolean(frame, true);
          } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
          }
        });
    CompletableFuture<Void> startup = frame.restoreStartupGameAsync(new String[0]);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            var defer = LizzieFrame.class.getDeclaredMethod(
                "deferKifuOpenUntilAutomaticQuickAnalysisRestored", Runnable.class);
            defer.setAccessible(true);
            assertEquals(true, defer.invoke(frame, (Runnable) this::setCurrentBoard));
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    assertThrows(CancellationException.class, () -> startup.get(5, TimeUnit.SECONDS));
    assertFalse(frame.saveAutoGame(1));
    assertFalse(frame.saveAutoGame(2));
    assertEquals(OLD, Files.readString(slot(1, "sgf")));
    assertEquals(NEW, Files.readString(slot(2, "sgf")));
    assertEquals(originalMetadata, Files.readString(metadataFile()));
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            cleanup.setBoolean(frame, false);
            var finish = LizzieFrame.class.getDeclaredMethod("finishDeferredKifuOpenAfterQuickAnalysisRestore");
            finish.setAccessible(true);
            assertEquals(true, finish.invoke(frame));
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    frame.restoreStartupGameAsync(new String[] {"read"}).get(5, TimeUnit.SECONDS);
    assertRecovered();
    assertTrue(SGFParser.saveToString(false).contains("CURRENT_SESSION"));
    assertTrue(frame.saveAutoGame(2));
    assertEquals(NEW, Files.readString(slot(1, "sgf")));
    assertTrue(Files.readString(slot(2, "sgf")).contains("CURRENT_SESSION"));
  }

  @Test
  void blockedRecoveryLeavesTheEventThreadResponsiveAndBothSlotsProtected() throws Exception {
    AtomicBoolean responsive = new AtomicBoolean();
    try (var blocked = SgfSaveTestSupport.blockWorker()) {
      CompletableFuture<Void> startup = frame.restoreStartupGameAsync(new String[0]);
      SwingUtilities.invokeAndWait(() -> responsive.set(true));
      assertTrue(responsive.get());
      assertFalse(startup.isDone());
      assertFalse(frame.saveAutoGameAsync(1).get(5, TimeUnit.SECONDS));
      assertFalse(frame.saveAutoGameAsync(2).get(5, TimeUnit.SECONDS));
      assertNull(frame.loadedFile);
      assertEquals(OLD, Files.readString(slot(1, "sgf")));
      assertEquals(NEW, Files.readString(slot(2, "sgf")));
      assertEquals(originalMetadata, Files.readString(metadataFile()));
      blocked.release();
      startup.get(5, TimeUnit.SECONDS);
    }
    assertRecovered();
    assertEquals(NEW, frame.loadedSgf);
  }

  @Test
  void startupWithoutAPendingRecoveryDoesNotWaitForTheSaveWorker() throws Exception {
    config.autoResume = false;
    config.saveBoardConfig.put("save-auto-game-index2", -1);
    config.saveTempBoard();
    String metadata = Files.readString(metadataFile());
    try (var blocked = SgfSaveTestSupport.blockWorker()) {
      frame.restoreStartupGameAsync(new String[] {"read"}).get(5, TimeUnit.SECONDS);
      assertEquals(OLD, Files.readString(slot(1, "sgf")));
      assertEquals(NEW, Files.readString(slot(2, "sgf")));
      assertEquals(metadata, Files.readString(metadataFile()));
    }
  }

  @Test
  void recoveryCompletesWithoutReplacingAGameOpenedWhileItsIoWasPending() throws Exception {
    try (var blocked = SgfSaveTestSupport.blockWorker()) {
      CompletableFuture<Void> startup = frame.restoreStartupGameAsync(new String[0]);
      SwingUtilities.invokeAndWait(
          () -> Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5]C[user selection];B[ee])", true)));
      BoardHistoryNode selected = Lizzie.board.getHistory().getStart();
      assertFalse(startup.isDone());
      blocked.release();
      startup.get(5, TimeUnit.SECONDS);
      assertSame(selected, Lizzie.board.getHistory().getStart());
    }
    assertRecovered();
    assertNull(frame.loadedFile, "startup completion must not open an older selection");
    assertTrue(SGFParser.saveToString(false).contains("user selection"));
    assertTrue(frame.saveAutoGame(2), "the selected game is usable after recovery completion");
    assertEquals(NEW, Files.readString(slot(1, "sgf")));
    assertTrue(Files.readString(slot(2, "sgf")).contains("user selection"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"move", "moveThenBack", "comment"})
  void recoveryPreservesEditsWithinTheSameHistoryWhileItsIoIsPending(String edit) throws Exception {
    Board board = Lizzie.board;
    BoardHistoryList history = board.getHistory();
    BoardHistoryNode root = history.getStart();
    config.playSound = false;
    SwingUtilities.invokeAndWait(() -> history.setHead(history.getEnd()));
    BoardHistoryNode original = history.getCurrentHistoryNode();
    long originalRevision = board.getContextRevision();
    AtomicReference<String> editedGame = new AtomicReference<>();
    try (var blocked = SgfSaveTestSupport.blockWorker()) {
      CompletableFuture<Void> startup = frame.restoreStartupGameAsync(new String[0]);
      SwingUtilities.invokeAndWait(() -> {
        if (edit.equals("comment")) {
          board.comment("user comment while recovery waits");
          assertSame(original, history.getCurrentHistoryNode());
          assertEquals(originalRevision, board.getContextRevision(), "comment edits are unversioned");
        } else {
          board.place(4, 4);
          assertNotSame(original, history.getCurrentHistoryNode(), "the real placement must commit");
          assertEquals(Stone.WHITE, board.getData().stones[Board.getIndex(4, 4)]);
          if (edit.equals("moveThenBack")) {
            assertTrue(board.previousMove(false));
            assertSame(original, history.getCurrentHistoryNode());
            assertNotEquals(originalRevision, board.getContextRevision());
          }
        }
        assertSame(history, board.getHistory());
        assertSame(root, history.getStart(), "these edits do not replace the history root");
        try {
          editedGame.set(SGFParser.saveSnapshot(board, true, true, false));
        } catch (IOException failure) {
          throw new AssertionError(failure);
        }
      });
      BoardHistoryNode selected = history.getCurrentHistoryNode();
      assertFalse(startup.isDone());
      assertEquals(originalMetadata, Files.readString(metadataFile()));
      blocked.release();
      startup.get(5, TimeUnit.SECONDS);
      assertSame(history, board.getHistory());
      assertSame(root, history.getStart());
      assertSame(selected, history.getCurrentHistoryNode());
    }
    assertRecovered();
    assertNull(frame.loadedFile, "recovery completion must not replace the edited live game");
    SwingUtilities.invokeAndWait(() -> {
      try {
        assertEquals(editedGame.get(), SGFParser.saveSnapshot(board, true, true, false));
      } catch (IOException failure) {
        throw new AssertionError(failure);
      }
    });
    assertTrue(frame.saveAutoGame(2));
    assertEquals(NEW, Files.readString(slot(1, "sgf")), "the previous session remains recoverable");
    String saved = Files.readString(slot(2, "sgf"));
    assertTrue(saved.contains("CURRENT_SESSION"));
    assertTrue(saved.contains(edit.equals("comment") ? "user comment while recovery waits" : ";W[ee]"));
  }

  @Test
  void missingPeriodicSourceKeepsThePreviousExitRecordAndMetadata() throws Exception {
    Files.delete(slot(2, "sgf"));
    byte[] preview = Files.readAllBytes(slot(1, "bmp"));
    frame.restoreStartupGame(new String[0]);
    assertEquals(OLD, frame.loadedSgf);
    assertEquals(OLD, Files.readString(slot(1, "sgf")));
    assertArrayEquals(preview, Files.readAllBytes(slot(1, "bmp")));
    assertEquals(originalMetadata, Files.readString(metadataFile()));
    assertEquals("old time", frame.getTempGameList().get(0).time);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not an SGF"})
  void invalidPeriodicSourceDoesNotReplaceTheExitRecord(String invalid) throws Exception {
    Files.writeString(slot(2, "sgf"), invalid);
    config.autoResume = false;
    frame.restoreStartupGame(new String[0]);
    assertFalse(frame.saveAutoGame(2));
    assertFalse(frame.saveAutoGame(1));
    assertEquals(OLD, Files.readString(slot(1, "sgf")));
    assertEquals(invalid, Files.readString(slot(2, "sgf")));
    assertEquals(originalMetadata, Files.readString(metadataFile()));
  }

  @Test
  void unreadablePeriodicSourceIsNotTreatedAsMissing() throws Exception {
    Files.delete(slot(2, "sgf"));
    Files.createDirectory(slot(2, "sgf"));
    Path blocker = slot(2, "sgf").resolve("keep");
    Files.writeString(blocker, "do not delete");
    frame.restoreStartupGame(new String[0]);
    assertFalse(frame.saveAutoGame(2));
    assertFalse(frame.saveAutoGame(1));
    assertEquals("do not delete", Files.readString(blocker));
    assertEquals(OLD, Files.readString(slot(1, "sgf")));
    assertEquals(originalMetadata, Files.readString(metadataFile()));
    assertEquals(1, frame.recoveryFailures);
  }

  @Test
  void absentPeriodicMetadataDoesNotInheritTheOldExitCursor() throws Exception {
    config.saveBoardConfig.remove("save-auto-game-time2");
    config.saveBoardConfig.remove("save-auto-game-move-number2");
    config.saveBoardConfig.remove("save-auto-game-move-list2");
    config.saveTempBoard();
    frame.restoreStartupGame(new String[0]);
    TempGameData entry = frame.getTempGameList().get(0);
    assertEquals("", entry.time);
    assertEquals(0, entry.curMoveNumer);
    assertEquals("", entry.moves);
    assertEquals(NEW, Files.readString(slot(1, "sgf")));
  }

  @Test
  void failedSgfPromotionResumesTheSourceAndProtectsBothAutomaticWritesUntilRetry() throws Exception {
    Files.delete(slot(1, "sgf"));
    Files.createDirectory(slot(1, "sgf"));
    Path blocker = slot(1, "sgf").resolve("keep");
    Files.writeString(blocker, "do not delete");
    frame.restoreStartupGame(new String[0]);
    assertEquals(slot(2, "sgf").toFile(), frame.loadedFile);
    assertEquals(NEW, frame.loadedSgf);
    assertPendingRecord();
    setCurrentBoard();
    assertFalse(frame.saveAutoGame(2));
    assertFalse(frame.saveAutoGame(1));
    assertEquals("do not delete", Files.readString(blocker));
    assertEquals(NEW, Files.readString(slot(2, "sgf")));
    assertEquals(originalMetadata, Files.readString(metadataFile()));
    assertEquals(1, frame.recoveryFailures);
    Files.delete(blocker);
    Files.delete(slot(1, "sgf"));
    // A subsequent periodic save retries recovery before reusing source2.
    frame.saveAutoGame(2);
    assertEquals(NEW, Files.readString(slot(1, "sgf")));
    assertTrue(Files.readString(slot(2, "sgf")).contains("CURRENT_SESSION"));
    assertEquals("new time", config.saveBoardConfig.getString("save-auto-game-time1"));
  }

  @Test
  void failedMetadataCommitRetainsTheSourceAndSupportsRestartRecovery() throws Exception {
    Field filename = Config.class.getDeclaredField("saveBoardFilename");
    filename.setAccessible(true);
    Path blocked = directory.resolve("blocked-metadata");
    Files.createDirectory(blocked);
    Files.writeString(blocked.resolve("keep"), "do not delete");
    filename.set(config, blocked.toString());
    frame.restoreStartupGame(new String[0]);
    assertEquals(slot(2, "sgf").toFile(), frame.loadedFile);
    assertPendingRecord();
    setCurrentBoard();
    frame.saveAutoGame(2);
    frame.saveAutoGame(1);
    assertEquals(NEW, Files.readString(slot(2, "sgf")));
    assertEquals(originalMetadata, Files.readString(metadataFile()));
    assertEquals(-5, config.saveBoardConfig.getInt("save-auto-game-index2"));
    filename.set(config, metadataFile().toString());
    // Simulate restart from the unchanged disk manifest after SGF copy succeeded.
    config.saveBoard = new JSONObject(Files.readString(metadataFile()));
    config.saveBoardConfig = config.saveBoard.getJSONObject("save");
    frame = RulesLayerTestHarness.allocate(RecoveryFrame.class);
    Lizzie.frame = frame;
    frame.restoreStartupGame(new String[0]);
    assertRecovered();
    assertEquals(NEW, frame.loadedSgf);
    frame.saveAutoGame(1);
    assertTrue(Files.readString(slot(1, "sgf")).contains("NEW_PERIODIC"));
  }

  @Test
  void successfulRecoveryIsNotRepeatedAfterRestartWithAnInactiveSource() throws Exception {
    frame.restoreStartupGame(new String[0]);
    setCurrentBoard();
    frame.saveAutoGame(1);
    assertEquals(NEW, Files.readString(slot(2, "sgf")));
    config.saveBoard = new JSONObject(Files.readString(metadataFile()));
    config.saveBoardConfig = config.saveBoard.getJSONObject("save");
    frame = RulesLayerTestHarness.allocate(RecoveryFrame.class);
    Lizzie.frame = frame;
    frame.restoreStartupGame(new String[0]);
    assertTrue(frame.loadedSgf.contains("CURRENT_SESSION"));
    assertFalse(frame.loadedSgf.contains("NEW_PERIODIC"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void optionalPreviewFailureOrAbsenceDoesNotPreventRecovery(boolean blocked) throws Exception {
    if (blocked) {
      Files.delete(slot(1, "bmp"));
      Files.createDirectory(slot(1, "bmp"));
      Files.writeString(slot(1, "bmp").resolve("keep"), "do not delete");
    } else {
      Files.delete(slot(2, "bmp"));
    }
    frame.restoreStartupGame(new String[0]);
    assertRecovered();
    assertFalse(Files.isRegularFile(slot(1, "bmp")));
  }

  private void assertPendingRecord() {
    TempGameData entry = frame.getTempGameList().get(0);
    assertEquals(2, entry.index);
    assertEquals("new time", entry.time);
    assertEquals(2, entry.curMoveNumer);
    assertEquals("new cursor", entry.moves);
  }

  private void assertRecovered() throws IOException {
    assertEquals(NEW, Files.readString(slot(1, "sgf")));
    assertEquals(NEW, Files.readString(slot(2, "sgf")));
    JSONObject persisted = new JSONObject(Files.readString(metadataFile()));
    assertEquals("keep", persisted.getString("other"));
    assertTrue(persisted.getJSONObject("save").similar(config.saveBoardConfig));
    assertEquals(1, config.saveBoardConfig.getInt("save-auto-game-index1"));
    assertEquals(-1, config.saveBoardConfig.getInt("save-auto-game-index2"));
    TempGameData entry = frame.getTempGameList().get(0);
    assertEquals(1, entry.index);
    assertEquals("new time", entry.time);
    assertEquals(2, entry.curMoveNumer);
    assertEquals("new cursor", entry.moves);
    try (var files = Files.walk(directory)) {
      assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
    }
  }

  private void setCurrentBoard() {
    Lizzie.board.setHistory(SGFParser.parseSgf(CURRENT, true));
  }

  private Path metadataFile() {
    return directory.resolve("save/save");
  }

  private Path slot(int index, String extension) {
    return directory.resolve("save/autoGame" + index + "." + extension);
  }

  static class RecoveryFrame extends LizzieFrame {
    File loadedFile;
    String loadedSgf;
    JSONObject metadataAtLoad;
    int recoveryFailures;

    @Override
    protected void savedGameEditFailed(IOException failure) {
      assertTrue(SwingUtilities.isEventDispatchThread());
      recoveryFailures++;
    }

    @Override
    public boolean loadFile(File file, boolean fromTemp, boolean showHint) {
      assertTrue(SwingUtilities.isEventDispatchThread());
      try {
        loadedFile = file;
        loadedSgf = Files.readString(file.toPath());
        metadataAtLoad =
            new JSONObject(
                    Files.readString(
                        Lizzie.config.getWorkDirectory().toPath().resolve("save/save")))
                .getJSONObject("save");
        BoardHistoryList history = SGFParser.parseSgf(loadedSgf, true);
        if (history == null) return false;
        Lizzie.board.setHistory(history);
        return true;
      } catch (IOException failure) {
        throw new AssertionError(failure);
      }
    }

    @Override
    public void refresh() {}

    @Override
    public void refresh(int mode) {}

    @Override
    public void clearKataEstimate() {}

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void refreshProblemListSnapshot() {}

    @Override
    public Image saveMainBoardToImageOri() {
      return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    }
  }
}
