package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.SgfSaveCoordinator;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PlayedGameSaveSafetyTest {
  private static final int TIMEOUT_SECONDS = 10;
  private static final String COMPLETED_GAME =
      "(;SZ[5]PB[이전 흑]PW[이전 백]C[original root];B[aa]C[first move]"
          + "(;W[bb]C[main line];B[cc])(;W[dd]C[variation];B[ee]))";
  private static final String NEXT_GAME = "(;SZ[5]PB[next black]PW[next white];B[ee])";

  @TempDir Path directory;
  private RulesLayerTestHarness rules;
  private SaveFailureFrame frame;
  private Config config;
  private ThreadPoolExecutor worker;
  private File previousCurrentFile;
  private File activeFile;
  private boolean previousRawComment;
  private String lastFolder;

  @BeforeEach
  void prepare() throws Exception {
    awaitPendingSaves();
    rules = RulesLayerTestHarness.open();
    previousCurrentFile = LizzieFrame.curFile;
    previousRawComment = LizzieFrame.isSavingRawComment;
    config = ConfigTestHelper.createForTests(directory);
    lastFolder = directory.resolve("previous-browse-folder").toString();
    config.persisted =
        new JSONObject().put("filesystem", new JSONObject().put("last-folder", lastFolder));
    config.readKomi = true;
    config.showComment = true;
    config.initialMaxScoreLead = 10;
    Lizzie.config = config;
    frame = RulesLayerTestHarness.allocate(SaveFailureFrame.class);
    frame.failures = new CopyOnWriteArrayList<>();
    Lizzie.frame = frame;
    activeFile = directory.resolve("currently-open.sgf").toFile();
    LizzieFrame.curFile = activeFile;
    worker = saveWorker();
    CompletableFuture.runAsync(() -> {}, worker).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    onEdt(() -> Lizzie.board.setHistory(SGFParser.parseSgf(COMPLETED_GAME, true)));
  }

  @AfterEach
  void restore() throws Exception {
    try {
      awaitPendingSaves();
      onEdt(() -> {});
    } finally {
      LizzieFrame.curFile = previousCurrentFile;
      LizzieFrame.isSavingRawComment = previousRawComment;
      if (rules != null) rules.close();
    }
  }

  @Test
  void requestCapturesNormalExportBeforeTheWriterAndTheNextGame() throws Exception {
    Lizzie.leelaz.isKatago = true;
    Lizzie.leelaz.rulesSummary = KataGoRules.Summary.CHINESE;
    Board completedBoard = Lizzie.board;
    BoardHistoryList completedHistory = completedBoard.getHistory();
    AtomicReference<CompletableFuture<Void>> pending = new AtomicReference<>();
    try (PausedTask paused = new PausedTask(worker)) {
      onEdt(
          () -> {
            completedHistory.setHead(
                completedHistory.getStart().next().orElseThrow().getVariation(1).orElseThrow());
            LizzieFrame.isSavingRaw = true;
            LizzieFrame.isSavingRawComment = false;
            frame.autoSavePlayedGame();
            pending.set(SgfSaveCoordinator.pendingSaves());
            assertFalse(pending.get().isDone(), "accepted I/O must remain visible to shutdown");
            assertTrue(LizzieFrame.isSavingRaw);
            assertFalse(LizzieFrame.isSavingRawComment);
            assertTrue(savedGames().isEmpty(), "a blocked worker must not reserve a file on EDT");
            assertFalse(Files.exists(directory.resolve("MyGames")),
                "directory creation also belongs to the worker");

            completedHistory.getGameInfo().setPlayerBlack("edited after capture");
            completedHistory.getStart().getData().comment = "edited root";
            Lizzie.board = boardWithHistory(SGFParser.parseSgf(NEXT_GAME, true));
            LizzieFrame.isSavingRaw = false;
            LizzieFrame.isSavingRawComment = true;
            assertActiveFileUnchanged();
          });
      assertFalse(pending.get().isDone());
      paused.release();
      pending.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    List<Path> files = savedGames();
    assertEquals(1, files.size());
    String saved = Files.readString(files.get(0));
    BoardHistoryList replayed = SGFParser.parseSgf(saved, true);
    assertEquals("이전 흑", replayed.getGameInfo().getPlayerBlack());
    assertEquals("이전 백", replayed.getGameInfo().getPlayerWhite());
    BoardHistoryNode root = replayed.getStart();
    assertTrue(root.getData().comment.contains("original root"));
    assertTrue(root.getData().comment.contains(Lizzie.resourceBundle.getString("SGFParse.rules")));
    assertTrue(
        root.getData().comment.contains(
            Lizzie.resourceBundle.getString("LizzieFrame.currentRules.chinese")));
    BoardHistoryNode first = root.next().orElseThrow();
    assertMove(first, 0, 0);
    assertEquals("first move", first.getData().comment);
    assertEquals(2, first.numberOfChildren());
    BoardHistoryNode main = first.getVariation(0).orElseThrow();
    assertMove(main, 1, 1);
    assertEquals("main line", main.getData().comment);
    assertMove(main.next().orElseThrow(), 2, 2);
    BoardHistoryNode variation = first.getVariation(1).orElseThrow();
    assertMove(variation, 3, 3);
    assertEquals("variation", variation.getData().comment);
    assertMove(variation.next().orElseThrow(), 4, 4);
    assertFalse(saved.contains("edited after capture"));
    assertFalse(saved.contains("edited root"));
    assertFalse(saved.contains("next black"));
    assertFalse(LizzieFrame.isSavingRaw);
    assertTrue(LizzieFrame.isSavingRawComment);
    assertTrue(frame.failures.isEmpty());
    assertActiveFileUnchanged();
  }

  @Test
  void sameDisplayNameRequestsKeepEverySnapshotAndEveryExistingByte() throws Exception {
    Path existing = directory.resolve("MyGames/existing.sgf");
    Files.createDirectories(existing.getParent());
    byte[] existingBytes = "(;SZ[5]C[previous saved game])\r\n".getBytes(StandardCharsets.UTF_8);
    Files.write(existing, existingBytes);
    try (PausedTask paused = new PausedTask(worker)) {
      onEdt(
          () -> {
            for (int request = 0; request < 3; request++) {
              Lizzie.board.getHistory().getStart().getData().comment = "request " + request;
              frame.autoSavePlayedGame();
            }
            assertFalse(SgfSaveCoordinator.pendingSaves().isDone());
            assertEquals(List.of(existing), savedGames(), "queued requests have not touched disk");
            Lizzie.board.getHistory().getStart().getData().comment = "later edit";
          });
      paused.release();
      awaitPendingSaves();
    }
    List<Path> files = savedGames();
    assertEquals(4, files.size());
    assertArrayEquals(existingBytes, Files.readAllBytes(existing));
    Set<String> capturedComments = new HashSet<>();
    for (Path target : files) {
      if (target.equals(existing)) continue;
      assertTrue(target.getFileName().toString().endsWith(".sgf"));
      BoardHistoryList replayed = SGFParser.parseSgf(Files.readString(target), true);
      assertEquals("이전 흑", replayed.getGameInfo().getPlayerBlack());
      assertEquals("이전 백", replayed.getGameInfo().getPlayerWhite());
      capturedComments.add(replayed.getStart().getData().comment);
    }
    assertEquals(Set.of("request 0", "request 1", "request 2"), capturedComments);
    assertTrue(frame.failures.isEmpty());
    assertActiveFileUnchanged();
  }

  @Test
  void directoryCreationFailureIsReportedWithoutReplacingTheBlockingFile() throws Exception {
    Path blockingFile = directory.resolve("MyGames");
    byte[] original = "this is a user file".getBytes(StandardCharsets.UTF_8);
    Files.write(blockingFile, original);
    AtomicReference<CompletableFuture<Void>> failedSave = new AtomicReference<>();
    onEdt(() -> {
      frame.autoSavePlayedGame();
      failedSave.set(SgfSaveCoordinator.pendingSaves());
    });
    ExecutionException failure = assertThrows(ExecutionException.class,
        () -> failedSave.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertInstanceOf(IOException.class, failure.getCause());
    assertFailureReported();
    assertTrue(Files.isRegularFile(blockingFile));
    assertArrayEquals(original, Files.readAllBytes(blockingFile));
    assertActiveFileUnchanged();
  }

  @Test
  void fullRealSaveQueueReportsRejectionWithoutCreatingAReservation() throws Exception {
    Path existing = directory.resolve("MyGames/existing.sgf");
    Files.createDirectories(existing.getParent());
    byte[] original = "(;SZ[5]C[keep this SGF])".getBytes(StandardCharsets.UTF_8);
    Files.write(existing, original);
    CountDownLatch queuedTasksFinished = new CountDownLatch(16);
    AtomicReference<CompletableFuture<Void>> rejectedSave = new AtomicReference<>();
    try (PausedTask paused = new PausedTask(worker)) {
      try {
        assertEquals(16, worker.getQueue().remainingCapacity());
        for (int index = 0; index < 16; index++) worker.execute(queuedTasksFinished::countDown);
        onEdt(
            () -> {
              frame.autoSavePlayedGame();
              rejectedSave.set(SgfSaveCoordinator.pendingSaves());
              assertFalse(rejectedSave.get().isDone());
              assertEquals(List.of(existing), savedGames(), "rejection must not touch disk");
            });
        // Rejection completion is owned by the EDT and must not wait for the busy disk worker.
        ExecutionException rejection =
            assertThrows(
                ExecutionException.class,
                () -> rejectedSave.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, rejection.getCause());
        assertFailureReported();
        assertInstanceOf(RejectedExecutionException.class, frame.failures.get(0).getCause());
        assertSame(rejection.getCause(), frame.failures.get(0).getCause());
        assertEquals(List.of(existing), savedGames());
        assertArrayEquals(original, Files.readAllBytes(existing));
        assertActiveFileUnchanged();
      } finally {
        paused.release();
        assertTrue(queuedTasksFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void captureRuntimeFailureRestoresExportFlagsAndCreatesNoFile() throws Exception {
    BoardHistoryNode failingRoot =
        new BoardHistoryNode(BoardData.empty(5, 5)) {
          @Override
          BoardHistoryNode copyForSave() {
            throw new IllegalStateException("injected snapshot failure");
          }
        };
    onEdt(
        () -> {
          Lizzie.board.getHistory().setHead(failingRoot);
          LizzieFrame.isSavingRaw = true;
          LizzieFrame.isSavingRawComment = true;
          frame.autoSavePlayedGame();
          assertTrue(LizzieFrame.isSavingRaw);
          assertTrue(LizzieFrame.isSavingRawComment);
        });
    awaitPendingSaves();
    assertFailureReported();
    assertInstanceOf(IllegalStateException.class, frame.failures.get(0).getCause());
    assertFalse(Files.exists(directory.resolve("MyGames")));
    assertActiveFileUnchanged();
  }

  @Test
  void historyReplacedDuringCaptureCannotSaveTheOldNameWithTheNewTree() throws Exception {
    Board completedBoard = Lizzie.board;
    BoardHistoryList nextHistory = SGFParser.parseSgf(NEXT_GAME, true);
    completedBoard
        .getHistory()
        .setGameInfo(
            new GameInfo() {
              @Override
              public String getSaveFileName() {
                completedBoard.setHistory(nextHistory);
                return "completed game";
              }
            });
    onEdt(frame::autoSavePlayedGame);
    awaitPendingSaves();
    assertFailureReported();
    assertSame(nextHistory, completedBoard.getHistory());
    assertTrue(savedGames().isEmpty());
    assertActiveFileUnchanged();
  }

  enum Replacement {
    BOARD,
    HISTORY,
    ROOT
  }

  @ParameterizedTest
  @EnumSource(Replacement.class)
  void offEdtRequestRejectsTheGameReplacedBeforeItsEdtCapture(Replacement replacement)
      throws Exception {
    BoardHistoryList original = Lizzie.board.getHistory();
    ObservedHistory history = new ObservedHistory(original.getStart().getData());
    history.setHead(original.getEnd());
    history.setGameInfo(original.getGameInfo());
    ObservedBoard board = boardWithHistory(history);
    BoardHistoryList nextHistory = SGFParser.parseSgf(NEXT_GAME, true);
    onEdt(() -> Lizzie.board = board);
    var caller = Executors.newSingleThreadExecutor();
    CompletableFuture<Void> request = new CompletableFuture<>();
    try (PausedTask edt = new PausedTask(SwingUtilities::invokeLater)) {
      // This mutation is ahead of the save in the EDT queue, but after the caller's identity read.
      SwingUtilities.invokeLater(
          () -> {
            switch (replacement) {
              case BOARD -> Lizzie.board = boardWithHistory(nextHistory);
              case HISTORY -> board.setHistory(nextHistory);
              case ROOT -> {
                history.setHead(nextHistory.getStart());
                history.setGameInfo(nextHistory.getGameInfo());
              }
            }
          });
      caller.execute(
          () -> {
            board.requestThread = Thread.currentThread();
            history.requestThread = Thread.currentThread();
            try {
              frame.autoSavePlayedGame();
              request.complete(null);
            } catch (Throwable failure) {
              request.completeExceptionally(failure);
            }
          });
      assertTrue(board.historyRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      // Observe the returned root too, so ROOT replacement cannot race the caller's root lookup.
      assertTrue(history.rootRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertFalse(request.isDone(), "the off-EDT entry must await its capture decision");
      edt.release();
      request.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      awaitPendingSaves();
      assertFailureReported();
      assertTrue(savedGames().isEmpty());
      assertActiveFileUnchanged();
    } finally {
      caller.shutdown();
      assertTrue(caller.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
  }

  private void assertActiveFileUnchanged() {
    assertSame(activeFile, LizzieFrame.curFile);
    assertEquals(lastFolder, config.persisted.getJSONObject("filesystem").getString("last-folder"));
  }

  private void assertFailureReported() {
    assertEquals(1, frame.failures.size());
    assertFalse(frame.failureOffEdt, "failure notification belongs to the EDT");
  }

  private List<Path> savedGames() throws IOException {
    Path games = directory.resolve("MyGames");
    if (!Files.isDirectory(games)) return List.of();
    try (var entries = Files.list(games)) {
      return entries.sorted().toList();
    }
  }

  private static void assertMove(BoardHistoryNode node, int x, int y) {
    assertArrayEquals(new int[] {x, y}, node.getData().lastMove.orElseThrow());
  }

  private static ObservedBoard boardWithHistory(BoardHistoryList history) {
    ObservedBoard board = RulesLayerTestHarness.allocate(ObservedBoard.class);
    board.startStonelist = new ArrayList<>();
    board.movelistwr = new ArrayList<>();
    board.historyRead = new CountDownLatch(1);
    board.setHistory(history);
    return board;
  }

  private static ThreadPoolExecutor saveWorker() throws ReflectiveOperationException {
    // Keep the real coordinator/queue/writer intact; only pause or saturate its actual executor.
    Field savesField = SgfSaveCoordinator.class.getDeclaredField("SAVES");
    savesField.setAccessible(true);
    Object saves = savesField.get(null);
    Field workerField = saves.getClass().getDeclaredField("worker");
    workerField.setAccessible(true);
    return assertInstanceOf(ThreadPoolExecutor.class, workerField.get(saves));
  }

  private static void awaitPendingSaves() throws Exception {
    SgfSaveCoordinator.pendingSaves().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }

  private static void onEdt(CheckedAction action) throws Exception {
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              action.run();
              return null;
            });
    SwingUtilities.invokeLater(task);
    task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static final class PausedTask implements AutoCloseable {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    PausedTask(Executor executor) throws Exception {
      executor.execute(
          () -> {
            entered.countDown();
            try {
              assertTrue(release.await(30, TimeUnit.SECONDS), "test did not release its blocker");
              completion.complete(null);
            } catch (Throwable failure) {
              completion.completeExceptionally(failure);
            }
          });
      boolean started = false;
      try {
        started = entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(started, "executor did not start the blocker");
      } finally {
        if (!started) release();
      }
    }

    void release() {
      release.countDown();
    }

    @Override
    public void close() throws Exception {
      release();
      completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  static class ObservedBoard extends Board {
    volatile Thread requestThread;
    CountDownLatch historyRead;

    @Override
    public BoardHistoryList getHistory() {
      BoardHistoryList captured = super.getHistory();
      if (Thread.currentThread() == requestThread) historyRead.countDown();
      return captured;
    }

    @Override
    public void clearAfterMove() {}
  }

  static class ObservedHistory extends BoardHistoryList {
    volatile Thread requestThread;
    final CountDownLatch rootRead = new CountDownLatch(1);

    ObservedHistory(BoardData data) {
      super(data);
    }

    @Override
    public BoardHistoryNode getStart() {
      BoardHistoryNode captured = super.getStart();
      if (Thread.currentThread() == requestThread) rootRead.countDown();
      return captured;
    }
  }

  static class SaveFailureFrame extends LizzieFrame {
    List<IOException> failures;
    volatile boolean failureOffEdt;

    @Override
    protected void savedGameEditFailed(IOException failure) {
      failures.add(failure);
      if (!SwingUtilities.isEventDispatchThread()) failureOffEdt = true;
    }

    @Override
    public void refresh() {}

    @Override
    public void refresh(int mode) {}

    @Override
    public void setPlayers(String whitePlayer, String blackPlayer) {}

    @Override
    public void resetTitle() {}

    @Override
    public void setResult(String result) {}

    @Override
    public void tryToResetByoTime() {}

    @Override
    public void clearKataEstimate() {}
  }
}
