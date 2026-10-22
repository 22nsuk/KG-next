package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.Acceptance;
import featurecat.lizzie.enginegame.BatchSummary;
import featurecat.lizzie.enginegame.EngineGameBatchSpec;
import featurecat.lizzie.enginegame.EngineGameBatchSpecFactory;
import featurecat.lizzie.enginegame.EngineGameChrome;
import featurecat.lizzie.enginegame.EngineGameChromeTransition;
import featurecat.lizzie.enginegame.EngineGameParsedStart;
import featurecat.lizzie.enginegame.EngineGamePlan;
import featurecat.lizzie.enginegame.EngineGamePlans;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameRecord;
import featurecat.lizzie.enginegame.EngineGameRecordContext;
import featurecat.lizzie.enginegame.EngineGameResignPolicy;
import featurecat.lizzie.enginegame.EngineGameSaveSnapshot;
import featurecat.lizzie.enginegame.EngineGamePresentation;
import featurecat.lizzie.enginegame.EngineGameKomiState;
import featurecat.lizzie.enginegame.EngineGameSide;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameTransaction;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.GameActivity;
import featurecat.lizzie.enginegame.GameOutcome;
import featurecat.lizzie.enginegame.OpeningStanding;
import featurecat.lizzie.enginegame.ParticipantBinding;
import featurecat.lizzie.enginegame.Rejection;
import featurecat.lizzie.enginegame.RunState;
import featurecat.lizzie.enginegame.StartFailure;
import featurecat.lizzie.enginegame.StartObserver;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.WinrateGraph;
import featurecat.lizzie.gui.SgfWinLossList;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EngineGameModuleContractTest {
  @TempDir Path directory;
  private static final EngineParticipantIdentity FIRST =
      new EngineParticipantIdentity("black-cmd", "");
  private static final EngineParticipantIdentity SECOND =
      new EngineParticipantIdentity("white-cmd", "");

  private EngineManager previousManager;
  private Leelaz previousPrimary;
  private Config previousConfig;
  private LizzieFrame previousFrame;
  private BottomToolbar previousToolbar;
  private Menu previousMenu;
  private JFontMenu previousEngineMenu;
  private Board previousBoard;
  private WinrateGraph previousWinrateGraph;
  private GtpConsolePane previousGtpConsole;
  private boolean previousEmpty;
  private int previousEngineNo;
  private boolean previousSavingRaw;

  private OccupancyLeelaz black;
  private OccupancyLeelaz white;
  private CountingLeaseEngineManager manager;
  private RecordingObserver observer;

  @BeforeEach
  void installFixture() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
    previousManager = Lizzie.engineManager;
    previousPrimary = Lizzie.leelaz;
    previousConfig = Lizzie.config;
    previousFrame = Lizzie.frame;
    previousToolbar = LizzieFrame.toolbar;
    previousMenu = LizzieFrame.menu;
    previousEngineMenu = Menu.engineMenu;
    previousBoard = Lizzie.board;
    previousWinrateGraph = LizzieFrame.winrateGraph;
    previousGtpConsole = Lizzie.gtpConsole;
    previousEmpty = EngineManager.isEmpty;
    previousEngineNo = EngineManager.currentEngineNo;
    previousSavingRaw = LizzieFrame.isSavingRaw;

    EngineManager.resetEngineGameTransactionStateForTest();
    Config config = allocate(InMemoryConfig.class);
    config.uiConfig = new JSONObject();
    config.leelazConfig = new JSONObject();
    config.newEngineGameHandicap = 0;
    config.newEngineGameKomi = 7.5;
    config.pkAdvanceTimeSettings = false;
    config.enginePkPonder = false;
    config.extraMode = ExtraMode.Normal;
    Lizzie.config = config;
    black = new OccupancyLeelaz();
    white = new OccupancyLeelaz();
    black.bindLiveRuntime();
    white.bindLiveRuntime();
    black.setEngineCommand("black-cmd");
    white.setEngineCommand("white-cmd");
    Lizzie.setPrimaryEngine(black);
    Lizzie.frame = allocate(SilentFrame.class);
    LizzieFrame.toolbar = allocate(SilentToolbar.class);
    LizzieFrame.menu = allocate(SilentMenu.class);
    Menu.engineMenu = new JFontMenu();
    LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
    Board board = allocate(SilentBoard.class);
    board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
    Lizzie.board = board;
    Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
    manager = new CountingLeaseEngineManager(List.of(black, white));
    Lizzie.engineManager = manager;

    EngineManager.isEmpty = false;
    EngineManager.currentEngineNo = 0;
    observer = new RecordingObserver();
    Lizzie.engineGame.replaceChromeForTest(transition -> {});
  }

  @AfterEach
  void restoreFixture() throws Exception {
    Lizzie.engineGame.replaceChromeForTest(null);
    EngineManager.resetEngineGameTransactionStateForTest();
    // startEngineGame queues Menu's preparation update before returning. Let that real callback
    // finish while this fixture's config, frame, and menu still belong together.
    SwingUtilities.invokeAndWait(() -> {});
    Lizzie.engineManager = previousManager;
    EngineManager.isEmpty = previousEmpty;
    EngineManager.currentEngineNo = previousEngineNo;
    Lizzie.setPrimaryEngine(previousPrimary);
    Lizzie.config = previousConfig;
    Lizzie.frame = previousFrame;
    LizzieFrame.toolbar = previousToolbar;
    LizzieFrame.menu = previousMenu;
    Menu.engineMenu = previousEngineMenu;
    Lizzie.board = previousBoard;
    LizzieFrame.winrateGraph = previousWinrateGraph;
    LizzieFrame.isSavingRaw = previousSavingRaw;
    Lizzie.gtpConsole = previousGtpConsole;
  }

  @Test
  void sameIdentityIsRejectedWithoutBatchOrObserver() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder().first(FIRST).second(FIRST).genmove(true).build());

    Acceptance acceptance = Lizzie.engineGame.accept(spec, observer);

    assertInstanceOf(Acceptance.Rejected.class, acceptance);
    assertInstanceOf(
        Rejection.InvalidParticipantCombination.class,
        ((Acceptance.Rejected) acceptance).reason());
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.firstPlan());

    assertNull(Lizzie.engineGame.transaction());
    assertEquals(0, observer.playing);
    assertEquals(List.of(), observer.failures);
  }

  @Test
  void analysisWithoutLimitsIsRejectedWithoutRetainingObserver() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder().first(FIRST).second(SECOND).build());

    Acceptance acceptance = Lizzie.engineGame.accept(spec, observer);

    assertInstanceOf(Acceptance.Rejected.class, acceptance);
    assertInstanceOf(
        Rejection.InvalidAnalysisLimits.class, ((Acceptance.Rejected) acceptance).reason());
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
    assertEquals(0, observer.playing);
    assertEquals(List.of(), observer.failures);
  }

  @Test
  void occupancyFailureIsRejectedWithoutCreatingProductState() throws Exception {
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        black.beginExclusiveGtpLifecycleReservation(new Object());
    assertNotNull(reservation);
    try {
      Acceptance acceptance = Lizzie.engineGame.accept(genmoveSpec(), observer);

      assertInstanceOf(Acceptance.Rejected.class, acceptance);
      assertInstanceOf(
          Rejection.OccupiedLifecycle.class, ((Acceptance.Rejected) acceptance).reason());
      assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
      assertNull(Lizzie.engineGame.firstPlan());

      assertNull(Lizzie.engineGame.transaction());
      assertEquals(1, manager.leaseConflictCount);
      assertEquals(0, observer.playing);
      assertEquals(List.of(), observer.failures);
    } finally {
      reservation.close();
    }
  }

  @Test
  void acceptCreatesBatchPlanTransactionAndPublishesStartingThenPlayingOnce() {
    Acceptance acceptance = Lizzie.engineGame.accept(genmoveSpec(), observer);

    assertInstanceOf(Acceptance.Accepted.class, acceptance);
    assertNotNull(Lizzie.engineGame.firstPlan());
    assertNotNull(Lizzie.engineGame.transaction());
    assertEquals(FIRST, Lizzie.engineGame.firstPlan().black());
    assertEquals(SECOND, Lizzie.engineGame.firstPlan().white());
    assertEquals(0, Lizzie.engineGame.firstPlan().blackIndex());
    assertEquals(1, Lizzie.engineGame.firstPlan().whiteIndex());
    assertEquals(0, observer.playing);
    assertInstanceOf(GameActivity.Starting.class, activity(Lizzie.engineGame.current()));

    Lizzie.engineGame.onOwnerPlaying();
    assertEquals(1, observer.playing);
    assertEquals(List.of(), observer.failures);
    assertInstanceOf(GameActivity.Playing.class, activity(Lizzie.engineGame.current()));

    Lizzie.engineGame.onOwnerPlaying();
    assertEquals(1, observer.playing);
  }

  @Test
  void stopDuringStartingCancelsOncePublishesIdleAndLeavesOwnerRetired() {
    Acceptance acceptance = Lizzie.engineGame.accept(genmoveSpec(), observer);
    assertInstanceOf(Acceptance.Accepted.class, acceptance);
    EngineGameSnapshot held = Lizzie.engineGame.current();
    assertInstanceOf(GameActivity.Starting.class, activity(held));

    Lizzie.engineGame.stop();

    assertEquals(0, observer.playing);
    assertEquals(1, observer.failures.size());
    assertInstanceOf(StartFailure.CancelledByUser.class, observer.failures.get(0));
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
    assertInstanceOf(EngineGameSnapshot.BatchActive.class, held);
    Lizzie.engineGame.onOwnerPlaying();
    Lizzie.engineGame.onOwnerStartFailed(new IllegalStateException("late"));
    assertEquals(0, observer.playing);
    assertEquals(1, observer.failures.size());
  }

  @Test
  void publicSnapshotDoesNotParticipateInOwnerAdmission() {
    assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(genmoveSpec(), observer));
    EngineGameSnapshot starting = Lizzie.engineGame.current();
    Lizzie.engineGame.stop();
    assertInstanceOf(EngineGameSnapshot.BatchActive.class, starting);
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    EngineManager.resetEngineGameTransactionStateForTest();

    assertNotNull(
        EngineManager.beginEngineGameTransaction(
            manager, EngineGamePlans.harness(0, 1, true), null, true));
    assertInstanceOf(GameActivity.Starting.class, activity(starting));
  }

  @Test
  void toggleShowMoveNumberUsesPlayingGenmoveStateNotLegacyFlags() {
    playAccepted(genmoveSpec());
    Lizzie.config.onlyLastMoveNumber = 1;
    Lizzie.config.allowMoveNumber = 1;

    Lizzie.config.toggleShowMoveNumber();

    assertTrue(Lizzie.engineGame.current().playingGenmove());
    assertEquals(-1, Lizzie.config.allowMoveNumber);
  }

  @Test
  void idleSnapshotDoesNotHideActiveOwnerTransaction() {
    assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(genmoveSpec(), observer));
    Lizzie.engineGame.stop();
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    EngineManager.resetEngineGameTransactionStateForTest();

    assertNotNull(
        EngineManager.beginEngineGameTransaction(
            manager, EngineGamePlans.harness(0, 1, true), null, true));
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertTrue(EngineManager.hasActiveEngineGameTransaction());
  }



  @Test
  void pauseResumeAndReviseBatchLimitUpdateProductStateWithoutPlaceholders() {
    assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(analysisSpec(), observer));
    Lizzie.engineGame.onOwnerPlaying();
    EngineGameTransaction txn = Lizzie.engineGame.transaction();
    Lizzie.engineGame.pause();
    GameActivity.Playing paused = (GameActivity.Playing) activity(Lizzie.engineGame.current());
    assertEquals(RunState.PAUSED, paused.runState());
    assertTrue(txn.paused());

    Lizzie.engineGame.resume();
    GameActivity.Playing running = (GameActivity.Playing) activity(Lizzie.engineGame.current());
    assertEquals(RunState.RUNNING, running.runState());
    assertFalse(txn.paused());

    Lizzie.engineGame.reviseBatchLimit(8);
    EngineGameSnapshot.BatchActive active =
        (EngineGameSnapshot.BatchActive) Lizzie.engineGame.current();
    assertEquals(8, active.batch().batchLimit());
  }

  @Test
  void acceptAttachesExactParticipantBindingsFromFrozenPlan() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder()
                .first(FIRST)
                .second(SECOND)
                .visitLimitEnabled(true)
                .firstVisits(100)
                .secondVisits(200)
                .maxMoveLimitEnabled(true)
                .maxMoves(50)
                .firstResign(new EngineGameResignPolicy(10, 3, 5.0))
                .secondResign(new EngineGameResignPolicy(12, 4, 6.0))
                .build());
    assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(spec, observer));

    EngineGameTransaction txn = Lizzie.engineGame.transaction();
    assertNotNull(txn);
    assertNotNull(txn.lifecycle());
    ParticipantBinding black = txn.blackBinding();
    ParticipantBinding white = txn.whiteBinding();
    assertNotNull(black);
    assertNotNull(white);
    assertSame(txn, black.transaction());
    assertSame(txn, white.transaction());
    assertEquals(EngineGameSide.BLACK, black.side());
    assertEquals(EngineGameSide.WHITE, white.side());
    assertEquals(EngineGamePlayMode.ANALYSIS, black.playMode());
    assertEquals(txn.plan().blackLimits(), black.limits());
    assertEquals(txn.plan().whiteLimits(), white.limits());
    assertEquals(100, black.limits().visits());
    assertEquals(200, white.limits().visits());
    assertEquals(50, black.maxGameMoves(19, 19));
    assertEquals(0, black.catalogIndex());
    assertEquals(1, white.catalogIndex());

    assertEquals(100, txn.blackBinding().limits().visits());
    assertEquals(200, txn.whiteBinding().limits().visits());
    assertEquals(50, txn.blackBinding().maxGameMoves(19, 19));
  }

  @Test
  void staleAndDuplicateCompleteCannotMoveCurrentTransaction() {
    assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(genmoveSpec(), observer));
    EngineGameTransaction txn = Lizzie.engineGame.transaction();
    Object owner = txn.lifecycle().ownerToken();

    Lizzie.engineGame.complete(new GameOutcome.DoublePass(), owner, 0);
    assertTrue(txn.alreadyCompleted());
    assertFalse(Lizzie.engineGame.complete(new GameOutcome.DoublePass(), owner, 0));
    assertFalse(
        Lizzie.engineGame.complete(new GameOutcome.Resign(EngineGameSide.BLACK), new Object(), 0));
  }

  @Test
  void successorStartsOnlyAfterRetirementAndPublishesBetweenGames() {
    playAccepted(genmoveBatchSpec(2, false));
    black.pkMoveTimeGame = 1500;
    white.pkMoveTimeGame = 2500;
    EngineGameTransaction first = Lizzie.engineGame.transaction();
    completeCurrent(new GameOutcome.DoublePass());

    assertInstanceOf(GameActivity.BetweenGames.class, activity(Lizzie.engineGame.current()));
    assertSame(first, Lizzie.engineGame.transaction());
    assertEquals(1, Lizzie.engineGame.lastSummary().doublePassGames());
    assertEquals(1500, Lizzie.engineGame.lastSummary().firstTotalTimeMs());
    assertEquals(2500, Lizzie.engineGame.lastSummary().secondTotalTimeMs());
    assertEquals(1, observer.playing);
    assertTrue(Lizzie.engineGame.successorPending());

    Lizzie.engineGame.onOwnerRetired();

    assertFalse(Lizzie.engineGame.successorPending());
    assertNotSame(first, Lizzie.engineGame.transaction());
    assertInstanceOf(GameActivity.Starting.class, activity(Lizzie.engineGame.current()));
    assertEquals(
        2, ((EngineGameSnapshot.BatchActive) Lizzie.engineGame.current()).batch().gameOrdinal());
    assertEquals(1, observer.playing);
    assertEquals(List.of(), observer.failures);
  }

  @Test
  void failedBatchSaveKeepsCompletedGameUntilRetryStartsOneSuccessor() throws Exception {
    EngineGameTransaction transaction = playWithRealSave(true, 2, false);
    Object owner = transaction.lifecycle().ownerToken();
    BoardHistoryList history = Lizzie.board.getHistory();
    BoardHistoryNode root = history.getStart();
    BoardHistoryNode position = history.getCurrentHistoryNode();
    Files.writeString(manager.outputDirectory, "blocked output");

    completeForSave(new GameOutcome.Resign(EngineGameSide.BLACK));

    BatchSummary completed = Lizzie.engineGame.lastSummary();
    assertEquals(1, completed.secondWins());
    assertEquals(1100, completed.firstTotalTimeMs());
    assertEquals(2200, completed.secondTotalTimeMs());
    assertEquals(41, completed.firstTotalVisits());
    assertEquals(73, completed.secondTotalVisits());
    assertEquals(0, manager.successorStarts, "failed persistence must prevent the next game");
    assertTrue(manager.hasPendingEngineGameSave());
    assertTrue(Lizzie.engineGame.current().saveFailed());
    assertSame(history, Lizzie.board.getHistory());
    assertSame(root, history.getStart());
    assertSame(position, history.getCurrentHistoryNode());
    assertEquals(2, history.getMoveNumber());
    assertTrue(root.getData().comment.contains("completed original"));
    assertEquals(41, position.getData().getPlayouts());
    assertEquals(1, manager.saveFailures.size());
    assertTrue(manager.saveSuccesses.isEmpty());
    assertEquals("blocked output", Files.readString(manager.outputDirectory));

    EngineGameSnapshot failed = Lizzie.engineGame.current();
    Object staleOwner = new Object();
    assertFalse(Lizzie.engineGame.onSaveFailed(staleOwner));
    assertFalse(Lizzie.engineGame.onSaveSucceeded(staleOwner));
    assertTrue(Lizzie.engineGame.onOwnerRetired(staleOwner));
    assertTrue(Lizzie.engineGame.onOwnerRetired(owner));
    assertSame(failed, Lizzie.engineGame.current());
    assertSame(transaction, Lizzie.engineGame.transaction());
    assertFalse(Lizzie.engineGame.complete(new GameOutcome.DoublePass(), owner, 0));
    assertFalse(retrySave());
    assertEquals(0, manager.successorStarts);
    assertEquals(2, manager.saveFailures.size());
    assertTotals(completed);

    Files.delete(manager.outputDirectory);
    Files.createDirectory(manager.outputDirectory);
    assertTrue(retrySave());

    assertFalse(manager.hasPendingEngineGameSave());
    assertEquals(1, manager.successorStarts);
    assertEquals(2, manager.lastSuccessorPlan.gameOrdinal());
    assertTotals(completed);
    List<Path> games = savedFiles(".sgf");
    assertEquals(1, games.size());
    assertCompletedSgf(games.get(0));
    assertEquals(1, savedFiles(".txt").size());
    assertTrue(retrySave());
    assertEquals(1, manager.successorStarts, "a settled retry must not start another game");
    assertFalse(Lizzie.engineGame.complete(new GameOutcome.DoublePass(), owner, 0));
    assertTotals(completed);
  }

  @Test
  void successfulFallbackSaveAdvancesWithoutEnteringRetryState() throws Exception {
    playWithRealSave(true, 2, false);
    Files.createDirectory(manager.outputDirectory);
    Path blockedNamedDirectory = manager.outputDirectory.resolve("named-batch");
    Files.writeString(blockedNamedDirectory, "preserved unrelated file");
    Path fallbackDirectory =
        manager.outputDirectory.resolve(Lizzie.engineGame.outputTimestamp());

    completeForSave(new GameOutcome.Resign(EngineGameSide.BLACK));

    assertFalse(manager.hasPendingEngineGameSave());
    assertFalse(Lizzie.engineGame.current().saveFailed());
    assertEquals(1, manager.successorStarts);
    assertTrue(manager.saveFailures.isEmpty());
    assertEquals("preserved unrelated file", Files.readString(blockedNamedDirectory));
    List<Path> games = savedFiles(".sgf");
    assertEquals(1, games.size());
    assertCompletedSgf(games.get(0));
    assertEquals(fallbackDirectory, games.get(0).getParent());
    List<Path> reports = savedFiles(".txt");
    assertEquals(1, reports.size());
    assertEquals(fallbackDirectory, reports.get(0).getParent());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void singleOrFinalGameReportsSaveFailureAndFinishesOnlyAfterRetry(boolean batch)
      throws Exception {
    playWithRealSave(batch, 1, false);
    BoardHistoryList history = Lizzie.board.getHistory();
    Files.writeString(manager.outputDirectory, "blocked output");

    completeForSave(new GameOutcome.Resign(EngineGameSide.BLACK));

    BatchSummary completed = Lizzie.engineGame.lastSummary();
    assertTrue(manager.hasPendingEngineGameSave());
    assertTrue(Lizzie.engineGame.current().saveFailed());
    assertSame(history, Lizzie.board.getHistory());
    assertEquals(0, manager.successorStarts);
    assertEquals(1, manager.saveFailures.size());
    assertTrue(manager.saveSuccesses.isEmpty());

    Files.delete(manager.outputDirectory);
    Files.createDirectory(manager.outputDirectory);
    assertTrue(retrySave());

    assertFalse(manager.hasPendingEngineGameSave());
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertEquals(0, manager.successorStarts);
    assertEquals(1, manager.saveSuccesses.size());
    assertTotals(completed);
    assertEquals(1, savedFiles(".sgf").size());
    assertCompletedSgf(savedFiles(".sgf").get(0));
    assertEquals(batch ? 1 : 0, savedFiles(".txt").size());
  }

  @Test
  void stoppingFailedBatchKeepsSaveRetryButNeverStartsTheSuccessor() throws Exception {
    EngineGameTransaction transaction = playWithRealSave(true, 2, false);
    Files.writeString(manager.outputDirectory, "blocked output");
    completeForSave(new GameOutcome.Resign(EngineGameSide.BLACK));
    BatchSummary completed = Lizzie.engineGame.lastSummary();

    SwingUtilities.invokeAndWait(() -> Lizzie.engineGame.stop());

    assertTrue(manager.hasPendingEngineGameSave());
    assertTrue(Lizzie.engineGame.current().saveFailed());
    assertFalse(Lizzie.engineGame.successorPending());
    assertSame(transaction, Lizzie.engineGame.transaction());
    Acceptance acceptance = Lizzie.engineGame.accept(genmoveSpec(), observer);
    assertInstanceOf(Acceptance.Rejected.class, acceptance);
    assertInstanceOf(
        Rejection.OccupiedLifecycle.class, ((Acceptance.Rejected) acceptance).reason());
    assertSame(transaction, Lizzie.engineGame.transaction());
    Files.delete(manager.outputDirectory);
    Files.createDirectory(manager.outputDirectory);
    assertTrue(retrySave());

    assertFalse(manager.hasPendingEngineGameSave());
    assertEquals(0, manager.successorStarts);
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertTotals(completed);
    assertEquals(1, savedFiles(".sgf").size());
    assertCompletedSgf(savedFiles(".sgf").get(0));
  }

  @Test
  void stopAtSavedSuccessorAdmissionPreventsOrphanEngineOwner() throws Exception {
    EngineGameTransaction completed = playWithRealSave(true, 2, false);
    BoardHistoryList history = Lizzie.board.getHistory();
    BoardHistoryNode position = history.getCurrentHistoryNode();
    int clearCalls = ((SilentBoard) Lizzie.board).engineGameClearCalls;
    assertSame(
        completed.lifecycle().ownerToken(), EngineManager.activeEngineGameTransactionForTest());
    manager.stopBeforeSuccessorAdmission = true;

    completeForSave(new GameOutcome.Resign(EngineGameSide.BLACK));

    assertEquals(1, manager.successorStarts, "the successor reached manager admission once");
    assertInstanceOf(GameActivity.Starting.class, activity(manager.successorSnapshotBeforeStop));
    assertInstanceOf(EngineGameSnapshot.Idle.class, manager.successorSnapshotAfterStop);
    assertNull(manager.successorOwnerBeforeAdmission);
    assertEquals(Boolean.FALSE, manager.stoppedSuccessorAccepted);
    assertNull(manager.successorOwnerAfterAdmission);
    assertNull(EngineManager.activeEngineGameTransactionForTest());
    assertTrue(manager.stoppedSuccessorWorkers.isEmpty(), manager.stoppedSuccessorWorkers.toString());
    assertSame(history, Lizzie.board.getHistory());
    assertSame(position, history.getCurrentHistoryNode());
    assertEquals(2, history.getMoveNumber());
    assertEquals(clearCalls, ((SilentBoard) Lizzie.board).engineGameClearCalls);
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
    assertFalse(Lizzie.engineGame.successorPending());
    assertFalse(manager.hasPendingEngineGameSave());
    assertTrue(manager.saveFailures.isEmpty());
    assertEquals(1, manager.saveSuccesses.size());
    assertEquals(1, savedFiles(".sgf").size());
    assertCompletedSgf(savedFiles(".sgf").get(0));
    assertEquals(1, savedFiles(".txt").size());
  }

  @Test
  void completedSourceBeforePendingSurvivesHistoryReplacementAndRetry() throws Exception {
    EngineGameTransaction completed = playWithRealSave(true, 2, false);
    Board originalBoard = Lizzie.board;
    BoardHistoryList original = originalBoard.getHistory();
    long originalRevision = originalBoard.getContextRevision();
    BoardHistoryList replacement =
        SGFParser.parseSgf(
            "(;SZ[19]RE[B+3.5]C[replacement unchanged];B[cc]C[replacement move];W[dd])",
            true);
    replacement.setHead(replacement.getEnd());
    BoardHistoryNode replacementRoot = replacement.getStart();
    BoardHistoryNode replacementMove = replacementRoot.next().orElseThrow();
    BoardHistoryNode replacementPosition = replacement.getCurrentHistoryNode();
    Runnable assertReplacementUnchanged =
        () -> {
          assertSame(replacement, Lizzie.board.getHistory());
          assertSame(replacementRoot, replacement.getStart());
          assertSame(replacementMove, replacementRoot.next().orElseThrow());
          assertSame(replacementPosition, replacement.getCurrentHistoryNode());
          assertEquals("B+3.5", replacement.getGameInfo().getResult());
          assertEquals("B+3.5", ((SilentFrame) Lizzie.frame).lastResult);
          assertEquals("replacement unchanged", replacementRoot.getData().comment);
          assertEquals("replacement move", replacementMove.getData().comment);
          assertEquals("", replacementPosition.getData().comment);
          assertArrayEquals(new int[] {2, 2}, replacementMove.getData().lastMove.orElseThrow());
          assertArrayEquals(new int[] {3, 3}, replacementPosition.getData().lastMove.orElseThrow());
          assertEquals(2, replacement.getMoveNumber());
          assertNull(replacement.getGameInfo().engineGameRecord());
          assertNull(replacement.getGameInfo().engineGameRecordContext());
        };
    manager.afterStopComment =
        () -> {
          assertTrue(completed.alreadyCompleted());
          assertNotNull(original.getGameInfo().engineGameRecord());
          assertFalse(manager.hasPendingEngineGameSave());
          assertSame(originalBoard, completed.completedPosition().board());
          assertSame(original, completed.completedPosition().history());
          assertEquals(originalRevision, completed.completedPosition().revision());
          assertSame(original, Lizzie.board.getHistory());
          Lizzie.board.setHistory(replacement);
          Lizzie.frame.setResult("B+3.5");
        };
    Files.writeString(manager.outputDirectory, "blocked output");

    completeForSave(new GameOutcome.Resign(EngineGameSide.BLACK));

    assertNull(manager.afterStopComment, "the replacement must happen before Pending is installed");
    assertTrue(manager.hasPendingEngineGameSave());
    assertTrue(Lizzie.engineGame.current().saveFailed());
    assertEquals(0, manager.successorStarts);
    assertEquals(1, manager.saveFailures.size());
    assertReplacementUnchanged.run();
    BatchSummary summary = Lizzie.engineGame.lastSummary();
    EngineGameRecord record = original.getGameInfo().engineGameRecord();
    assertNotNull(record);

    Files.delete(manager.outputDirectory);
    Files.createDirectory(manager.outputDirectory);
    assertTrue(retrySave());

    assertFalse(manager.hasPendingEngineGameSave());
    assertEquals(0, manager.successorStarts);
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertReplacementUnchanged.run();
    assertSame(record, original.getGameInfo().engineGameRecord());
    assertTotals(summary);
    List<Path> games = savedFiles(".sgf");
    assertEquals(1, games.size());
    assertCompletedSgf(games.get(0));
    String sgf = Files.readString(games.get(0));
    assertFalse(sgf.contains("replacement unchanged"), sgf);
    assertFalse(sgf.contains(";B[cc]"), sgf);
    assertFalse(sgf.contains("RE[B+3.5]"), sgf);
    assertEquals(1, savedFiles(".txt").size());
  }

  @Test
  void saveFinalizationDoesNotCompeteWithExistingRetirementContinuation() throws Exception {
    EngineGameTransaction completed = playWithRealSave(true, 1, false);
    EngineManager.EngineGameOwnerTransaction owner =
        (EngineManager.EngineGameOwnerTransaction) completed.lifecycle().ownerToken();
    AtomicInteger externalContinuations = new AtomicInteger();
    AtomicInteger schedulingFailures = new AtomicInteger();
    manager.afterStopComment =
        () -> {
          assertTrue(completed.alreadyCompleted());
          assertFalse(manager.hasPendingEngineGameSave());
          try {
            var register =
                EngineManager.class.getDeclaredMethod(
                    "runAfterEngineGameTransactionRetirement",
                    EngineManager.EngineGameOwnerTransaction.class,
                    Runnable.class,
                    Runnable.class);
            register.setAccessible(true);
            register.invoke(
                null,
                owner,
                (Runnable) externalContinuations::incrementAndGet,
                (Runnable) schedulingFailures::incrementAndGet);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        };

    completeForSave(new GameOutcome.Resign(EngineGameSide.BLACK));

    assertNull(manager.afterStopComment);
    assertEquals(1, externalContinuations.get());
    assertEquals(0, schedulingFailures.get());
    assertTrue(owner.retirementFinishedForTest());
    assertFalse(manager.hasPendingEngineGameSave());
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertEquals(0, manager.successorStarts);
    assertTrue(manager.saveFailures.isEmpty());
    assertEquals(1, manager.saveSuccesses.size());
    assertEquals(1, savedFiles(".sgf").size());
    assertCompletedSgf(savedFiles(".sgf").get(0));
    assertEquals(1, savedFiles(".txt").size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void shutdownCannotExitBeforePendingPublicationOrDuringSaveAttempt(boolean attemptStarted)
      throws Exception {
    EngineGameTransaction completed = playWithRealSave(true, 2, false);
    BoardHistoryList history = Lizzie.board.getHistory();
    Lizzie.config.autoSaveOnExit = false;
    Files.writeString(manager.outputDirectory, "blocked output");
    EngineGameChromeTransition.Kind gatedStage =
        attemptStarted
            ? EngineGameChromeTransition.Kind.SAVE_FAILED
            : EngineGameChromeTransition.Kind.BETWEEN_GAMES;
    AtomicBoolean gateClaimed = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Lizzie.engineGame.replaceChromeForTest(
        transition -> {
          if (transition.kind() == gatedStage && gateClaimed.compareAndSet(false, true)) {
            entered.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS), "shutdown gate was not released");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new AssertionError(interrupted);
            }
          }
        });
    FutureTask<Void> completion =
        new FutureTask<>(
            () -> {
              completeCurrent(new GameOutcome.Resign(EngineGameSide.BLACK));
              return null;
            });
    Thread completionWorker = new Thread(completion, "test-engine-game-completion");
    AtomicInteger exits = new AtomicInteger();
    completionWorker.start();
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS), "completion did not reach the save boundary");
      assertEquals(attemptStarted, manager.hasPendingEngineGameSave());
      assertTrue(completed.alreadyCompleted());
      assertNotNull(history.getGameInfo().engineGameRecord());

      requestShutdown(exits);

      assertEquals(0, exits.get());
      assertEquals(0, ((SilentFrame) Lizzie.frame).shutdownCleanupCalls);
      assertSame(completed, Lizzie.engineGame.transaction());
      assertFalse(Lizzie.engineGame.successorPending());
      assertSame(history, Lizzie.board.getHistory());
    } finally {
      release.countDown();
      completion.get(5, TimeUnit.SECONDS);
      completionWorker.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(completionWorker.isAlive(), "completion worker did not settle after the gate");
    }
    SwingUtilities.invokeAndWait(() -> {});

    assertEquals(0, exits.get());
    assertTrue(manager.hasPendingEngineGameSave());
    assertTrue(Lizzie.engineGame.current().saveFailed());
    assertEquals(0, manager.successorStarts);
    assertSame(history, Lizzie.board.getHistory());
    BatchSummary summary = Lizzie.engineGame.lastSummary();
    Files.delete(manager.outputDirectory);
    Files.createDirectory(manager.outputDirectory);
    assertTrue(retrySave());

    assertEquals(0, exits.get());
    assertEquals(0, ((SilentFrame) Lizzie.frame).shutdownCleanupCalls);
    assertFalse(manager.hasPendingEngineGameSave());
    assertEquals(0, manager.successorStarts);
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertTotals(summary);
    assertEquals(1, savedFiles(".sgf").size());
    assertCompletedSgf(savedFiles(".sgf").get(0));
    assertEquals(1, savedFiles(".txt").size());
  }

  @Test
  void retryAfterHistoryReplacementWritesFrozenGameWithoutTouchingReplacement()
      throws Exception {
    playWithRealSave(true, 2, false);
    Files.writeString(manager.outputDirectory, "blocked output");
    completeForSave(new GameOutcome.Resign(EngineGameSide.BLACK));
    BatchSummary completed = Lizzie.engineGame.lastSummary();
    BoardHistoryList original = Lizzie.board.getHistory();
    original.getStart().getData().comment = "edited after failure";
    BoardHistoryList replacement = SGFParser.parseSgf("(;SZ[19]C[replacement game];B[cc])", true);
    Lizzie.board.setHistory(replacement);

    Files.delete(manager.outputDirectory);
    Files.createDirectory(manager.outputDirectory);
    assertTrue(retrySave());

    assertFalse(manager.hasPendingEngineGameSave());
    assertEquals(0, manager.successorStarts);
    assertSame(replacement, Lizzie.board.getHistory());
    assertEquals("replacement game", replacement.getStart().getData().comment);
    assertTotals(completed);
    List<Path> games = savedFiles(".sgf");
    assertEquals(1, games.size());
    assertCompletedSgf(games.get(0));
    String sgf = Files.readString(games.get(0));
    assertFalse(sgf.contains("edited after failure"), sgf);
    assertFalse(sgf.contains("replacement game"), sgf);
    assertFalse(sgf.contains(";B[cc]"), sgf);
  }

  @Test
  void textRetryDoesNotRewriteCommittedSgfOrRecomputeCompletedStatistics() throws Exception {
    playWithRealSave(true, 2, false);
    String reportName =
        Lizzie.resourceBundle.getString("Leelaz.result")
            + Lizzie.engineGame.outputTimestamp()
            + ".txt";
    Path primaryText = manager.outputDirectory.resolve("named-batch").resolve(reportName);
    Path fallbackText =
        manager.outputDirectory.resolve(Lizzie.engineGame.outputTimestamp()).resolve(reportName);
    Files.createDirectories(primaryText);
    Files.createDirectories(fallbackText);

    completeForSave(new GameOutcome.Resign(EngineGameSide.BLACK));

    assertTrue(manager.hasPendingEngineGameSave());
    assertTrue(Lizzie.engineGame.current().saveFailed());
    assertEquals(0, manager.successorStarts);
    BatchSummary completed = Lizzie.engineGame.lastSummary();
    List<Path> games = savedFiles(".sgf");
    assertEquals(1, games.size());
    Path sgf = games.get(0);
    String originalSgf = Files.readString(sgf);
    FileTime committedTime = FileTime.fromMillis(1_000L);
    Files.setLastModifiedTime(sgf, committedTime);
    black.pkMoveTimeGame = 999_999;
    white.pkMoveTimeGame = 999_999;
    Files.delete(primaryText);
    Files.delete(fallbackText);

    assertTrue(retrySave());

    assertFalse(manager.hasPendingEngineGameSave());
    assertEquals(1, manager.successorStarts);
    assertEquals(committedTime, Files.getLastModifiedTime(sgf));
    assertEquals(originalSgf, Files.readString(sgf));
    assertTotals(completed);
    List<Path> reports = savedFiles(".txt");
    assertEquals(1, reports.size());
    String report = Files.readString(reports.get(0));
    assertTrue(report.contains("0:1"), report);
    assertFalse(report.contains("999.999"), report);
  }

  @Test
  void optionalImageFailureDoesNotTurnCommittedGameIntoFailedSave() throws Exception {
    playWithRealSave(true, 2, true);
    ((SilentFrame) Lizzie.frame).imageFailure = new IllegalStateException("controlled PNG failure");

    completeForSave(new GameOutcome.Resign(EngineGameSide.BLACK));

    assertEquals(1, ((SilentFrame) Lizzie.frame).imageAttempts);
    assertFalse(manager.hasPendingEngineGameSave());
    assertFalse(Lizzie.engineGame.current().saveFailed());
    assertEquals(1, manager.successorStarts);
    assertTrue(manager.saveFailures.isEmpty());
    assertEquals(1, savedFiles(".sgf").size());
    assertCompletedSgf(savedFiles(".sgf").get(0));
    assertEquals(1, savedFiles(".txt").size());
  }

  @Test
  void exchangeColorsAndScoreAttributionAcrossGames() {
    playAccepted(genmoveBatchSpec(2, true));
    assertEquals(FIRST, Lizzie.engineGame.transaction().plan().black());
    completeCurrent(new GameOutcome.Resign(EngineGameSide.BLACK));
    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
    assertEquals(1, Lizzie.engineGame.lastSummary().secondWins());
    assertEquals(1, Lizzie.engineGame.lastSummary().secondWinAsWhite());

    Lizzie.engineGame.onOwnerRetired();
    assertEquals(SECOND, Lizzie.engineGame.transaction().plan().black());
    assertEquals(FIRST, Lizzie.engineGame.transaction().plan().white());
    Lizzie.engineGame.onOwnerPlaying();
    completeCurrent(new GameOutcome.Resign(EngineGameSide.WHITE));

    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
    assertEquals(2, Lizzie.engineGame.lastSummary().secondWins());
    assertEquals(1, Lizzie.engineGame.lastSummary().secondWinAsBlack());
    assertEquals(1, Lizzie.engineGame.lastSummary().secondWinAsWhite());
    assertInstanceOf(GameActivity.BetweenGames.class, activity(Lizzie.engineGame.current()));
    assertFalse(Lizzie.engineGame.successorPending());
    Lizzie.engineGame.onOwnerRetired();
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
  }

  @Test
  void sequentialOpeningsAdvanceCursorAndAttributeResults() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder()
                .first(FIRST)
                .second(SECOND)
                .genmove(true)
                .timeLimitEnabled(true)
                .firstTimeSeconds(2)
                .secondTimeSeconds(2)
                .batch(true)
                .batchLimit(2)
                .sgfOpening(true)
                .sgfOpenings(List.of(List.of(), List.of()))
                .build());
    playAccepted(spec);
    assertEquals(0, Lizzie.engineGame.transaction().plan().openingIndex());
    completeCurrent(new GameOutcome.Resign(EngineGameSide.BLACK));
    OpeningStanding standing = Lizzie.engineGame.lastSummary().openingStandings().get(0);
    assertEquals(0, standing.openingIndex());
    assertEquals(1, standing.secondWinsAsWhite());

    Lizzie.engineGame.onOwnerRetired();
    assertEquals(1, Lizzie.engineGame.transaction().plan().openingIndex());
    assertTrue(Lizzie.engineGame.transaction().plan().openingMoves().isEmpty());
  }

  @Test
  void successorEngineGameInfoCarriesAccumulatedScores() {
    playAccepted(genmoveBatchSpec(2, false));
    completeCurrent(new GameOutcome.Resign(EngineGameSide.BLACK));
    Lizzie.engineGame.onOwnerRetired();
    EngineGamePlan successor = manager.lastSuccessorPlan;
    assertNotNull(successor);
    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
    assertEquals(1, Lizzie.engineGame.lastSummary().secondWins());
  }

  @Test
  void openingStandingsProjectOntoExistingTxtRows() {
    SgfWinLossList firstOpening = new SgfWinLossList();
    firstOpening.SgfNumber = 0;
    SgfWinLossList secondOpening = new SgfWinLossList();
    secondOpening.SgfNumber = 1;
    Lizzie.frame.enginePkSgfWinLoss = new ArrayList<>(List.of(firstOpening, secondOpening));
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder()
                .first(FIRST)
                .second(SECOND)
                .genmove(true)
                .timeLimitEnabled(true)
                .firstTimeSeconds(2)
                .secondTimeSeconds(2)
                .batch(true)
                .batchLimit(2)
                .sgfOpening(true)
                .sgfOpenings(List.of(List.of(), List.of()))
                .build());
    playAccepted(spec);
    completeCurrent(new GameOutcome.Resign(EngineGameSide.BLACK));
    assertEquals(1, firstOpening.engineTwoWinsAsWhite);
    assertEquals(1, firstOpening.engineTwoWins);
    assertEquals(0, secondOpening.engineTwoWins);
  }

  @Test
  void loweringLimitToCurrentOrdinalEndsAfterComplete() {
    playAccepted(genmoveBatchSpec(3, false));
    Lizzie.engineGame.reviseBatchLimit(1);
    completeCurrent(new GameOutcome.DoublePass());
    assertInstanceOf(GameActivity.BetweenGames.class, activity(Lizzie.engineGame.current()));
    assertFalse(Lizzie.engineGame.successorPending());
    Lizzie.engineGame.onOwnerRetired();
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
  }

  @Test
  void loweringLimitDuringBetweenGamesCancelsSuccessor() {
    playAccepted(genmoveBatchSpec(3, false));
    completeCurrent(new GameOutcome.DoublePass());
    assertInstanceOf(GameActivity.BetweenGames.class, activity(Lizzie.engineGame.current()));
    Lizzie.engineGame.reviseBatchLimit(1);
    assertInstanceOf(GameActivity.BetweenGames.class, activity(Lizzie.engineGame.current()));
    assertFalse(Lizzie.engineGame.successorPending());
    Lizzie.engineGame.onOwnerRetired();
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
    assertEquals(1, Lizzie.engineGame.lastSummary().doublePassGames());
  }

  @Test
  void raisingLimitAllowsAdditionalSuccessor() {
    playAccepted(genmoveBatchSpec(1, false));
    Lizzie.engineGame.reviseBatchLimit(2);
    completeCurrent(new GameOutcome.DoublePass());
    assertInstanceOf(GameActivity.BetweenGames.class, activity(Lizzie.engineGame.current()));
    Lizzie.engineGame.onOwnerRetired();
    assertInstanceOf(GameActivity.Starting.class, activity(Lizzie.engineGame.current()));
    assertEquals(2, Lizzie.engineGame.transaction().plan().gameOrdinal());
  }

  @Test
  void userStopDoesNotCountCurrentGameOrStartSuccessor() {
    playAccepted(genmoveBatchSpec(3, false));
    completeCurrent(new GameOutcome.Resign(EngineGameSide.BLACK));
    Lizzie.engineGame.onOwnerRetired();
    Lizzie.engineGame.onOwnerPlaying();
    assertEquals(1, Lizzie.engineGame.lastSummary().secondWins());
    Lizzie.engineGame.stop();
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertEquals(1, Lizzie.engineGame.lastSummary().secondWins());
    assertFalse(Lizzie.engineGame.successorPending());
    Lizzie.engineGame.onOwnerRetired();
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
  }

  @Test
  void laterGameStartupFailureEndsBatchWithoutReusingObserver() {
    playAccepted(genmoveBatchSpec(2, false));
    completeCurrent(new GameOutcome.DoublePass());
    manager.failNextNonFirstStart = true;
    assertFalse(Lizzie.engineGame.onOwnerRetired());
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertEquals(1, observer.playing);
    assertEquals(List.of(), observer.failures);
    assertEquals(1, Lizzie.engineGame.lastSummary().doublePassGames());
  }

  @Test
  void playingAttachesRecordContextToExactHistory() {
    playAccepted(genmoveSpec());
    GameInfo info = Lizzie.board.getHistory().getGameInfo();
    EngineGameRecordContext context = info.engineGameRecordContext();
    assertNotNull(context);
    assertSame(Lizzie.engineGame.transaction().plan(), context.plan());
    assertEquals(FIRST, context.black().identity());
    assertEquals(SECOND, context.white().identity());
    assertEquals(-1, context.openingIndex());
    assertNull(info.engineGameRecord());
  }

  @Test
  void validCompleteFreezesRecordOnceOnTheSameHistory() {
    playAccepted(genmoveSpec());
    black.pkMoveTimeGame = 1100;
    white.pkMoveTimeGame = 2200;
    black.oriEnginename = "BlackEngine";
    white.oriEnginename = "WhiteEngine";
    GameInfo info = Lizzie.board.getHistory().getGameInfo();
    completeCurrent(new GameOutcome.Resign(EngineGameSide.BLACK));
    EngineGameRecord record = info.engineGameRecord();
    assertNotNull(record);
    assertEquals(FIRST, record.context().black().identity());
    assertEquals("BlackEngine", record.blackDisplayName());
    assertEquals("WhiteEngine", record.whiteDisplayName());
    assertEquals(-1, record.openingIndex());
    assertInstanceOf(GameOutcome.Resign.class, record.outcome());
    assertEquals(EngineGameSide.BLACK, ((GameOutcome.Resign) record.outcome()).side());
    assertEquals(1100, record.blackTimeMs());
    assertEquals(2200, record.whiteTimeMs());
    EngineGameRecord frozen = record;
    assertFalse(Lizzie.engineGame.complete(new GameOutcome.DoublePass(), new Object(), 0));
    assertSame(frozen, info.engineGameRecord());
  }

  @Test
  void activeSaveSnapshotDoesNotChangeBatchTotals() {
    playAccepted(genmoveBatchSpec(2, false));
    black.pkMoveTimeGame = 400;
    white.pkMoveTimeGame = 500;
    assertEquals(0, Lizzie.engineGame.lastSummary().firstTotalTimeMs());
    EngineGameSaveSnapshot snapshot = Lizzie.engineGame.captureSaveSnapshot();
    assertNotNull(snapshot);
    assertEquals(400, snapshot.blackTimeMs());
    assertEquals(500, snapshot.whiteTimeMs());
    assertEquals(0, Lizzie.engineGame.lastSummary().firstTotalTimeMs());
    assertEquals(0, Lizzie.engineGame.lastSummary().secondWins());
    assertNull(Lizzie.board.getHistory().getGameInfo().engineGameRecord());
  }

  @Test
  void secondActiveSaveRecapturesSnapshotClocks() throws Exception {
    playAccepted(genmoveSpec());
    black.pkMoveTime = 111;
    white.pkMoveTime = 222;
    assertEquals(111, Lizzie.engineGame.captureSaveSnapshot().blackMoveTimeMs());
    black.pkMoveTime = 333;
    white.pkMoveTime = 444;
    SGFParser.saveToString(false);
    EngineGameSaveSnapshot snapshot =
        Lizzie.board.getHistory().getGameInfo().engineGameSaveSnapshot();
    assertNotNull(snapshot);
    assertEquals(333, snapshot.blackMoveTimeMs());
    assertEquals(444, snapshot.whiteMoveTimeMs());
  }

  @Test
  void completedHistoryDoesNotReadLiveMoveClockDuringSgfFormatting() {
    playAccepted(genmoveSpec());
    GameInfo info = Lizzie.board.getHistory().getGameInfo();
    completeCurrent(new GameOutcome.Resign(EngineGameSide.BLACK));
    assertNotNull(info.engineGameRecord());
    assertNull(info.engineGameSaveSnapshot());

    BoardData current = Lizzie.board.getHistory().getCurrentHistoryNode().getData();
    current.moveNumber = 3;
    current.setPlayouts(1);
    current.comment = "frozen";
    black.pkMoveTime = 9_000;
    white.pkMoveTime = 8_000;

    SGFParser.appendTime();

    assertEquals("frozen", current.comment);
  }


  @Test
  void previousHistoryKeepsPreviousRecordAfterNextGameStarts() {
    playAccepted(genmoveBatchSpec(2, true));
    GameInfo firstInfo = Lizzie.board.getHistory().getGameInfo();
    completeCurrent(new GameOutcome.Resign(EngineGameSide.BLACK));
    EngineGameRecord firstRecord = firstInfo.engineGameRecord();
    assertNotNull(firstRecord);
    assertEquals(FIRST, firstRecord.context().black().identity());

    BoardHistoryList nextHistory = new BoardHistoryList(BoardData.empty(19, 19));
    Lizzie.board.setHistory(nextHistory);
    Lizzie.engineGame.onOwnerRetired();
    Lizzie.engineGame.onOwnerPlaying();
    GameInfo secondInfo = Lizzie.board.getHistory().getGameInfo();
    assertNotSame(firstInfo, secondInfo);
    assertSame(firstRecord, firstInfo.engineGameRecord());
    assertEquals(SECOND, Lizzie.engineGame.transaction().plan().black());
    assertEquals(SECOND, secondInfo.engineGameRecordContext().black().identity());
    completeCurrent(new GameOutcome.Resign(EngineGameSide.WHITE));
    assertSame(firstRecord, firstInfo.engineGameRecord());
    assertEquals(FIRST, firstInfo.engineGameRecord().context().black().identity());
    assertEquals(SECOND, secondInfo.engineGameRecord().context().black().identity());
  }

  @Test
  void repeatedCompletedHistorySaveDoesNotChangeBatchStatistics() {
    playAccepted(genmoveSpec());
    completeCurrent(new GameOutcome.DoublePass());
    assertEquals(1, Lizzie.engineGame.lastSummary().doublePassGames());
    GameInfo info = Lizzie.board.getHistory().getGameInfo();
    assertNotNull(info.engineGameRecord());
    Lizzie.engineGame.captureSaveSnapshot();
    SGFParser.appendGameTimeAndPlayouts();
    SGFParser.appendGameTimeAndPlayouts();
    assertEquals(1, Lizzie.engineGame.lastSummary().doublePassGames());
    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
  }

  @Test
  void userStopDoesNotFreezeANormalRecord() {
    playAccepted(genmoveBatchSpec(3, false));
    GameInfo info = Lizzie.board.getHistory().getGameInfo();
    assertNotNull(info.engineGameRecordContext());
    Lizzie.engineGame.stop();
    assertNull(info.engineGameRecord());
    assertNotNull(info.engineGameRecordContext());
    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().secondWins());
  }

  @Test
  void presentationGuardsMapStartingPlayingPauseResumeAndTerminalIdle() {
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertFalse(Lizzie.engineGame.current().startingOrPlaying());

    assertInstanceOf(
        Acceptance.Accepted.class, Lizzie.engineGame.accept(analysisSpec(), observer));
    EngineGameSnapshot starting = Lizzie.engineGame.current();
    assertTrue(starting.starting());
    assertTrue(starting.startingOrPlaying());
    assertFalse(starting.playing());
    assertFalse(starting.paused());

    Lizzie.engineGame.onOwnerPlaying();
    assertTrue(Lizzie.engineGame.current().playing());
    Lizzie.engineGame.pause();
    assertTrue(Lizzie.engineGame.current().paused());
    Lizzie.engineGame.resume();
    assertFalse(Lizzie.engineGame.current().paused());

    Lizzie.engineGame.stop();
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertFalse(Lizzie.engineGame.current().startingOrPlaying());
  }

  @Test
  void exchangedBatchMapsScoresAndSidesFromSealedSnapshot() {
    playAccepted(genmoveBatchSpec(2, true));
    EngineGameSnapshot first = Lizzie.engineGame.current();
    assertTrue(first.playing());
    assertTrue(first.view().firstIsBlack());

    completeCurrent(new GameOutcome.DoublePass());
    EngineGameSnapshot between = Lizzie.engineGame.current();
    assertTrue(between.betweenGames());
    assertFalse(between.startingOrPlaying());
    assertFalse(EngineGamePresentation.showLiveBatchScores(between));

    Lizzie.engineGame.onOwnerRetired();
    Lizzie.engineGame.onOwnerPlaying();
    EngineGameSnapshot exchanged = Lizzie.engineGame.current();
    assertTrue(exchanged.playing());
    assertFalse(exchanged.view().firstIsBlack());
    assertEquals(SECOND, exchanged.view().black());
    assertTrue(EngineGamePresentation.showLiveBatchScores(exchanged));
    assertEquals(0, EngineGamePresentation.blackWins(exchanged));
    assertEquals(0, EngineGamePresentation.whiteWins(exchanged));

    completeCurrent(new GameOutcome.Resign(EngineGameSide.WHITE));
    assertInstanceOf(GameActivity.BetweenGames.class, activity(Lizzie.engineGame.current()));
    assertFalse(Lizzie.engineGame.current().startingOrPlaying());
    Lizzie.engineGame.onOwnerRetired();
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertFalse(Lizzie.engineGame.current().startingOrPlaying());
  }



  @Test
  void recordingChromePublishesStartingPlayingPauseResumeAndUserStopInOrder() {
    RecordingChrome chrome = new RecordingChrome();
    Lizzie.engineGame.replaceChromeForTest(chrome);
    try {
      assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(analysisSpec(), observer));
      Lizzie.engineGame.onOwnerPlaying();
      Lizzie.engineGame.pause();
      Lizzie.engineGame.resume();
      Lizzie.engineGame.stop();
      assertEquals(
          List.of(
              EngineGameChromeTransition.Kind.STARTING,
              EngineGameChromeTransition.Kind.PLAYING,
              EngineGameChromeTransition.Kind.PAUSED,
              EngineGameChromeTransition.Kind.RESUMED,
              EngineGameChromeTransition.Kind.USER_STOPPED),
          chrome.kinds());
      assertInstanceOf(GameActivity.Starting.class, activity(chrome.events.get(0).snapshot()));
      assertInstanceOf(GameActivity.Playing.class, activity(chrome.events.get(1).snapshot()));
      assertEquals(
          RunState.PAUSED, ((GameActivity.Playing) activity(chrome.events.get(2).snapshot())).runState());
      assertEquals(
          RunState.RUNNING,
          ((GameActivity.Playing) activity(chrome.events.get(3).snapshot())).runState());
      assertInstanceOf(EngineGameSnapshot.Idle.class, chrome.events.get(4).snapshot());
    } finally {
      Lizzie.engineGame.replaceChromeForTest(null);
    }
  }

  @Test
  void recordingChromePublishesBetweenGamesBatchEndAndLaterGameFailure() {
    RecordingChrome chrome = new RecordingChrome();
    Lizzie.engineGame.replaceChromeForTest(chrome);
    try {
      playAccepted(genmoveBatchSpec(2, false));
      completeCurrent(new GameOutcome.DoublePass());
      assertEquals(EngineGameChromeTransition.Kind.BETWEEN_GAMES, chrome.kinds().get(2));
      manager.failNextNonFirstStart = true;
      assertFalse(Lizzie.engineGame.onOwnerRetired());
      assertEquals(EngineGameChromeTransition.Kind.LATER_GAME_FAILED, chrome.lastKind());
      assertInstanceOf(EngineGameSnapshot.Idle.class, chrome.lastSnapshot());
    } finally {
      Lizzie.engineGame.replaceChromeForTest(null);
    }
  }

  @Test
  void recordingChromePublishesStartFailureForFirstGame() {
    RecordingChrome chrome = new RecordingChrome();
    Lizzie.engineGame.replaceChromeForTest(chrome);
    try {
      assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(genmoveSpec(), observer));
      Lizzie.engineGame.onOwnerStartFailed(new IllegalStateException("start"));
      assertEquals(
          List.of(
              EngineGameChromeTransition.Kind.STARTING,
              EngineGameChromeTransition.Kind.START_FAILED),
          chrome.kinds());
      assertInstanceOf(EngineGameSnapshot.Idle.class, chrome.lastSnapshot());
    } finally {
      Lizzie.engineGame.replaceChromeForTest(null);
    }
  }

  @Test
  void recordingChromePublishesBatchEndWhenNoSuccessor() {
    RecordingChrome chrome = new RecordingChrome();
    Lizzie.engineGame.replaceChromeForTest(chrome);
    try {
      playAccepted(genmoveSpec());
      completeCurrent(new GameOutcome.DoublePass());
      assertEquals(EngineGameChromeTransition.Kind.BETWEEN_GAMES, chrome.lastKind());
      assertInstanceOf(GameActivity.BetweenGames.class, activity(chrome.lastSnapshot()));
      Lizzie.engineGame.onOwnerRetired();
      assertEquals(EngineGameChromeTransition.Kind.BATCH_ENDED, chrome.lastKind());
      assertInstanceOf(EngineGameSnapshot.Idle.class, chrome.lastSnapshot());
    } finally {
      Lizzie.engineGame.replaceChromeForTest(null);
    }
  }

  @Test
  void resolvedCatalogSlotsFollowIdentityAfterReorder() throws Exception {
    OccupancyLeelaz alpha = new OccupancyLeelaz();
    OccupancyLeelaz beta = new OccupancyLeelaz();
    alpha.bindLiveRuntime();
    beta.bindLiveRuntime();
    alpha.setEngineCommand("cmd-a");
    beta.setEngineCommand("cmd-b");
    CountingLeaseEngineManager reordered = new CountingLeaseEngineManager(List.of(beta, alpha));
    assertEquals(
        1, reordered.resolveEngineGameParticipant(new EngineParticipantIdentity("cmd-a", "")));
    assertEquals(
        0, reordered.resolveEngineGameParticipant(new EngineParticipantIdentity("cmd-b", "")));
  }

  @Test
  void pauseAndResumeWhileKomiUpdatePendingPreservesPauseIntent() throws Exception {
    manager.manualActivation = true;
    playAccepted(analysisSpec());
    EngineGameTransaction txn = Lizzie.engineGame.transaction();
    activateOwner(txn);

    assertEquals(7.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertNull(Lizzie.engineGame.komiState());
    assertTrue(Lizzie.engineGame.current().playing());
    assertFalse(Lizzie.engineGame.current().paused());

    assertTrue(Lizzie.engineGame.reviseKomi(8.5));
    awaitKomiCommands("8.5");

    EngineGameKomiState pendingState = Lizzie.engineGame.komiState();
    assertNotNull(pendingState);
    assertTrue(pendingState.pending());
    assertEquals(7.5, pendingState.applied());
    assertEquals(8.5, pendingState.target());
    assertNull(pendingState.failure());
    assertEquals(7.5, Lizzie.board.getHistory().getGameInfo().getKomi());

    Lizzie.engineGame.pause();
    assertTrue(Lizzie.engineGame.current().paused());
    assertEquals(
        RunState.PAUSED, ((GameActivity.Playing) activity(Lizzie.engineGame.current())).runState());
    assertTrue(txn.paused());

    Lizzie.engineGame.resume();
    assertFalse(Lizzie.engineGame.current().paused());
    assertEquals(
        RunState.RUNNING, ((GameActivity.Playing) activity(Lizzie.engineGame.current())).runState());
    assertFalse(txn.paused());

    Lizzie.engineGame.pause();
    assertTrue(Lizzie.engineGame.current().paused());
    assertEquals(
        RunState.PAUSED, ((GameActivity.Playing) activity(Lizzie.engineGame.current())).runState());
    assertTrue(txn.paused());

    settleEngineGameCommands(black);
    settleEngineGameCommands(white);
    awaitKomiSettled();

    EngineGameKomiState settledState = Lizzie.engineGame.komiState();
    assertNotNull(settledState);
    assertFalse(settledState.pending());
    assertEquals(8.5, settledState.applied());
    assertEquals(8.5, settledState.target());
    assertNull(settledState.failure());
    assertEquals(8.5, Lizzie.board.getHistory().getGameInfo().getKomi());

    assertTrue(Lizzie.engineGame.current().paused());
    assertEquals(
        RunState.PAUSED, ((GameActivity.Playing) activity(Lizzie.engineGame.current())).runState());
    assertTrue(txn.paused());

    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().secondWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().doublePassGames());
    assertFalse(Lizzie.engineGame.successorPending());
    assertEquals("", Lizzie.board.getHistory().getGameInfo().getResult());
    assertNull(Lizzie.board.getHistory().getGameInfo().engineGameRecord());
    assertNotNull(Lizzie.board.getHistory().getGameInfo().engineGameRecordContext());
    assertFalse(txn.alreadyCompleted());
  }

  @Test
  void komiFailureEndsAcceptedBatchWithoutCompletedGameOrSuccessor() throws Exception {
    manager.manualActivation = true;
    playAccepted(analysisBatchSpec(3));
    EngineGameTransaction txn = Lizzie.engineGame.transaction();
    activateOwner(txn);

    assertEquals(7.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().secondWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().doublePassGames());

    assertTrue(Lizzie.engineGame.reviseKomi(8.5));
    awaitKomiCommands("8.5");

    EngineGameKomiState pendingState = Lizzie.engineGame.komiState();
    assertNotNull(pendingState);
    assertTrue(pendingState.pending());
    assertEquals(7.5, pendingState.applied());
    assertEquals(8.5, pendingState.target());

    settleEngineGameCommands(black);
    int whiteKomiId = commandIdFor(white.commandText(), "komi 8.5");
    white.processCommandResponseLineForTest("?" + whiteKomiId + " unsupported komi");

    awaitKomiFailure();

    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
    assertFalse(Lizzie.engineGame.successorPending());

    EngineGameKomiState failureState = Lizzie.engineGame.komiState();
    assertNotNull(failureState);
    assertFalse(failureState.pending());
    assertNotNull(failureState.failure());
    assertTrue(failureState.failure().contains("unsupported komi"));
    assertEquals(7.5, failureState.applied());
    assertEquals(7.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertEquals("", Lizzie.board.getHistory().getGameInfo().getResult());
    assertNull(Lizzie.board.getHistory().getGameInfo().engineGameRecord());
    assertNotNull(Lizzie.board.getHistory().getGameInfo().engineGameRecordContext());

    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().secondWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().doublePassGames());
    assertEquals(0, Lizzie.engineGame.lastSummary().maxMoveGames());

    Lizzie.engineGame.onOwnerRetired();
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
  }

  @Test
  void stopAndNewGameAreImmuneToOldCompletionAndKomiCallbacks() throws Exception {
    manager.manualActivation = true;
    playAccepted(analysisBatchSpec(3));
    EngineGameTransaction firstTxn = Lizzie.engineGame.transaction();
    EngineManager.EngineGameOwnerTransaction firstOwner =
        (EngineManager.EngineGameOwnerTransaction) firstTxn.lifecycle().ownerToken();
    activateOwner(firstTxn);

    assertTrue(Lizzie.engineGame.reviseKomi(8.5));
    awaitKomiCommands("8.5");
    int lateBlack = commandIdFor(black.commandText(), "komi 8.5");
    int lateWhite = commandIdFor(white.commandText(), "komi 8.5");

    Lizzie.engineGame.stop();

    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.engineGame.transaction());
    assertNull(Lizzie.engineGame.komiState());
    assertFalse(Lizzie.engineGame.successorPending());
    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().secondWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().doublePassGames());
    assertEquals(7.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertEquals("", Lizzie.board.getHistory().getGameInfo().getResult());
    assertNull(Lizzie.board.getHistory().getGameInfo().engineGameRecord());

    assertFalse(Lizzie.engineGame.complete(new GameOutcome.DoublePass(), firstOwner, 0));
    assertEquals(0, Lizzie.engineGame.lastSummary().doublePassGames());
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());

    black.bindLiveRuntime();
    white.bindLiveRuntime();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!firstOwner.retirementFinishedForTest() && System.nanoTime() < deadline) {
      Thread.sleep(5L);
    }
    assertTrue(firstOwner.retirementFinishedForTest(), "previous owner must finish retirement");
    playAccepted(analysisBatchSpec(3));
    EngineGameTransaction secondTxn = Lizzie.engineGame.transaction();
    assertNotSame(firstTxn, secondTxn);
    activateOwner(secondTxn);

    assertTrue(Lizzie.engineGame.current().playing());
    assertNull(Lizzie.engineGame.komiState());
    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().secondWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().doublePassGames());

    black.processCommandResponseLineForTest("=" + lateBlack);
    white.processCommandResponseLineForTest("?" + lateWhite + " late rejection");

    assertNull(Lizzie.engineGame.komiState());
    assertEquals(7.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertTrue(Lizzie.engineGame.current().playing());

    assertFalse(Lizzie.engineGame.complete(new GameOutcome.DoublePass(), firstOwner, 0));
    assertFalse(secondTxn.alreadyCompleted());
    assertTrue(Lizzie.engineGame.current().playing());
    assertEquals(0, Lizzie.engineGame.lastSummary().doublePassGames());
    assertEquals("", Lizzie.board.getHistory().getGameInfo().getResult());
    assertNull(Lizzie.board.getHistory().getGameInfo().engineGameRecord());
    assertNotNull(Lizzie.board.getHistory().getGameInfo().engineGameRecordContext());
    assertSame(secondTxn, Lizzie.engineGame.transaction());
  }
  @Test
  void inFlightGenmoveCompletesBeforeKomiUpdateApplies() throws Exception {
    manager.manualActivation = true;
    playAccepted(genmoveSpec());
    EngineGameTransaction txn = Lizzie.engineGame.transaction();
    activateOwner(txn);
    EngineManager.EngineGameOwnerTransaction owner =
        (EngineManager.EngineGameOwnerTransaction) txn.lifecycle().ownerToken();

    assertTrue(black.genmoveForPk("B", owner));
    assertTrue(Lizzie.engineGame.reviseKomi(8.5));

    EngineGameKomiState pendingState = Lizzie.engineGame.komiState();
    assertNotNull(pendingState);
    assertTrue(pendingState.pending());
    assertEquals(7.5, pendingState.applied());
    assertEquals(8.5, pendingState.target());
    assertEquals(7.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertFalse(black.commandText().contains("komi 8.5"));

    int blackGenmoveId = commandIdFor(black.commandText(), "kata-genmove_analyze B 0");
    black.parseEngineGameLineForTest("=" + blackGenmoveId);
    black.parseEngineGameLineForTest("play D4");
    int whitePlayId = commandIdFor(white.commandText(), "play B D4");
    white.processCommandResponseLineForTest("=" + whitePlayId);

    assertEquals(1, Lizzie.board.getHistory().getMoveNumber());
    assertEquals(7.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertFalse(white.commandText().contains("genmove W"));

    awaitKomiCommands("8.5");
    Lizzie.engineGame.pause();
    settleEngineGameCommands(black);
    settleEngineGameCommands(white);
    awaitKomiSettled();

    EngineGameKomiState settledState = Lizzie.engineGame.komiState();
    assertNotNull(settledState);
    assertFalse(settledState.pending());
    assertEquals(8.5, settledState.applied());
    assertEquals(8.5, settledState.target());
    assertEquals(8.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertEquals(1, Lizzie.board.getHistory().getMoveNumber());
    assertEquals(0, Lizzie.engineGame.lastSummary().firstWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().secondWins());
    assertEquals(0, Lizzie.engineGame.lastSummary().doublePassGames());
    assertEquals("", Lizzie.board.getHistory().getGameInfo().getResult());
    assertNull(Lizzie.board.getHistory().getGameInfo().engineGameRecord());
    assertNotNull(Lizzie.board.getHistory().getGameInfo().engineGameRecordContext());
    assertFalse(txn.alreadyCompleted());
  }

  private static GameActivity activity(EngineGameSnapshot snapshot) {
    return ((EngineGameSnapshot.BatchActive) snapshot).activity();
  }

  private EngineGameTransaction playWithRealSave(boolean batch, int games, boolean image)
      throws Exception {
    manager.outputDirectory = directory.resolve("EngineGames");
    manager.manualActivation = true;
    manager.runWorkersInline = true;
    manager.runRetirementContinuations = true;
    LizzieFrame.isSavingRaw = false;
    Lizzie.board.startStonelist = new ArrayList<>();
    Lizzie.board.movelistwr = new ArrayList<>();
    BoardHistoryList history =
        SGFParser.parseSgf("(;SZ[19]C[completed original];B[aa]C[move annotation];W[bb])", true);
    history.setHead(history.getEnd());
    history.getStart().next().orElseThrow().getData().setPlayouts(73);
    history.getEnd().getData().setPlayouts(41);
    Lizzie.board.setHistory(history);
    black.oriEnginename = "BlackEngine";
    white.oriEnginename = "WhiteEngine";
    black.currentEnginename = "BlackEngine";
    white.currentEnginename = "WhiteEngine";
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder()
                .first(FIRST)
                .second(SECOND)
                .genmove(true)
                .timeLimitEnabled(true)
                .firstTimeSeconds(2)
                .secondTimeSeconds(2)
                .batch(batch)
                .batchLimit(games)
                .batchName("named-batch")
                .autosave(true)
                .saveWinrateImage(image)
                .build());
    assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(spec, observer));
    EngineGameTransaction transaction = Lizzie.engineGame.transaction();
    activateOwner(transaction);
    Lizzie.engineGame.onOwnerPlaying();
    black.pkMoveTimeGame = 1100;
    white.pkMoveTimeGame = 2200;
    return transaction;
  }

  private void completeForSave(GameOutcome outcome) throws Exception {
    SwingUtilities.invokeAndWait(() -> completeCurrent(outcome));
    SwingUtilities.invokeAndWait(() -> {});
  }

  private boolean retrySave() throws Exception {
    boolean[] result = new boolean[1];
    SwingUtilities.invokeAndWait(() -> result[0] = manager.retryEngineGameSave());
    SwingUtilities.invokeAndWait(() -> {});
    return result[0];
  }

  private static void requestShutdown(AtomicInteger exits) throws Exception {
    var shutdown = Lizzie.class.getDeclaredMethod("shutdown", IntConsumer.class);
    shutdown.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            shutdown.invoke(null, (IntConsumer) status -> exits.incrementAndGet());
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
  }

  private List<Path> savedFiles(String suffix) throws IOException {
    try (var files = Files.walk(manager.outputDirectory)) {
      List<Path> saved =
          files.filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(suffix))
              .sorted()
              .toList();
      for (Path path : saved) assertTrue(path.startsWith(directory));
      return saved;
    }
  }

  private static void assertCompletedSgf(Path path) throws IOException {
    String sgf = Files.readString(path);
    assertTrue(sgf.contains("completed original"), sgf);
    assertTrue(sgf.contains("move annotation"), sgf);
    assertTrue(sgf.contains(";B[aa]"), sgf);
    assertTrue(sgf.contains(";W[bb]"), sgf);
    assertNotNull(SGFParser.parseSgf(sgf, true));
  }

  private void assertTotals(BatchSummary expected) {
    BatchSummary actual = Lizzie.engineGame.lastSummary();
    assertEquals(expected.firstWins(), actual.firstWins());
    assertEquals(expected.secondWins(), actual.secondWins());
    assertEquals(expected.doublePassGames(), actual.doublePassGames());
    assertEquals(expected.maxMoveGames(), actual.maxMoveGames());
    assertEquals(expected.firstTotalTimeMs(), actual.firstTotalTimeMs());
    assertEquals(expected.secondTotalTimeMs(), actual.secondTotalTimeMs());
    assertEquals(expected.firstTotalVisits(), actual.firstTotalVisits());
    assertEquals(expected.secondTotalVisits(), actual.secondTotalVisits());
  }

  private void playAccepted(EngineGameBatchSpec spec) {
    assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(spec, observer));
    Lizzie.engineGame.onOwnerPlaying();
  }

  private void completeCurrent(GameOutcome outcome) {
    EngineGameTransaction txn = Lizzie.engineGame.transaction();
    Object owner = txn.lifecycle() == null ? null : txn.lifecycle().ownerToken();
    assertTrue(Lizzie.engineGame.complete(outcome, owner, 0));
  }

  private static EngineGameBatchSpec genmoveBatchSpec(int games, boolean exchange) {
    return EngineGameBatchSpecFactory.from(
        EngineGameParsedStart.builder()
            .first(FIRST)
            .second(SECOND)
            .genmove(true)
            .timeLimitEnabled(true)
            .firstTimeSeconds(2)
            .secondTimeSeconds(2)
            .batch(true)
            .batchLimit(games)
            .exchangeColors(exchange)
            .build());
  }


  private static EngineGameBatchSpec genmoveSpec() {
    return EngineGameBatchSpecFactory.from(
        EngineGameParsedStart.builder()
            .first(FIRST)
            .second(SECOND)
            .genmove(true)
            .timeLimitEnabled(true)
            .firstTimeSeconds(2)
            .secondTimeSeconds(2)
            .build());
  }

  private static EngineGameBatchSpec analysisSpec() {
    return EngineGameBatchSpecFactory.from(
        EngineGameParsedStart.builder()
            .first(FIRST)
            .second(SECOND)
            .visitLimitEnabled(true)
            .firstVisits(10)
            .secondVisits(10)
            .build());
  }

  private static EngineGameBatchSpec analysisBatchSpec(int games) {
    return EngineGameBatchSpecFactory.from(
        EngineGameParsedStart.builder()
            .first(FIRST)
            .second(SECOND)
            .visitLimitEnabled(true)
            .firstVisits(10)
            .secondVisits(10)
            .batch(true)
            .batchLimit(games)
            .build());
  }

  private void activateOwner(EngineGameTransaction txn) {
    EngineManager.EngineGameOwnerTransaction owner =
        (EngineManager.EngineGameOwnerTransaction) txn.lifecycle().ownerToken();
    assertTrue(EngineManager.transitionEngineGameToDispatched(owner));
    assertTrue(
        EngineManager.activateEngineGameTransaction(
            owner,
            black,
            0,
            black.currentEngineIncarnation(),
            white.currentEngineIncarnation()));
  }

  private void awaitKomiCommands(String target) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while ((!black.commandText().contains("komi " + target)
        || !white.commandText().contains("komi " + target)) && System.nanoTime() < deadline) {
      for (OccupancyLeelaz engine : List.of(black, white)) {
        for (String line : engine.commandText().split("\\R")) {
          if (line.matches("\\d+ name")) {
            engine.processCommandResponseLineForTest("=" + line.substring(0, line.indexOf(' ')));
          }
        }
      }
      Thread.sleep(5L);
    }
    assertTrue(black.commandText().contains("komi " + target), black.commandText());
    assertTrue(white.commandText().contains("komi " + target), white.commandText());
  }

  private void settleEngineGameCommands(OccupancyLeelaz engine) {
    int settled = 0;
    while (true) {
      int[] ids = numberedCommandIds(engine.commandText());
      if (ids.length == settled) {
        return;
      }
      for (int index = settled; index < ids.length; index++) {
        engine.processCommandResponseLineForTest("=" + ids[index]);
      }
      settled = ids.length;
    }
  }

  private static void awaitKomiSettled() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (Lizzie.engineGame.komiState() != null
        && Lizzie.engineGame.komiState().pending()
        && System.nanoTime() < deadline) {
      Thread.sleep(5L);
    }
  }

  private static void awaitKomiFailure() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (Lizzie.engineGame.current().playing() && System.nanoTime() < deadline) {
      Thread.sleep(5L);
    }
  }

  private static int[] numberedCommandIds(String output) {
    return output
        .lines()
        .map(String::trim)
        .map(line -> line.split("\\s+", 2)[0])
        .filter(token -> !token.isEmpty() && token.chars().allMatch(Character::isDigit))
        .mapToInt(Integer::parseInt)
        .toArray();
  }

  private static int commandIdFor(String output, String command) {
    return output
        .lines()
        .map(String::trim)
        .filter(line -> line.endsWith(command))
        .map(line -> line.split("\\s+", 2)[0])
        .mapToInt(Integer::parseInt)
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing command '" + command + "' in: " + output));
  }

  private static final class RecordingObserver implements StartObserver {
    private int playing;
    private final List<StartFailure> failures = new ArrayList<>();

    @Override
    public void playing() {
      playing++;
    }

    @Override
    public void startFailed(StartFailure failure) {
      failures.add(failure);
    }
  }

  private static final class RecordingChrome implements EngineGameChrome {
    private final List<EngineGameChromeTransition> events = new ArrayList<>();

    @Override
    public void publish(EngineGameChromeTransition transition) {
      events.add(transition);
    }

    private List<EngineGameChromeTransition.Kind> kinds() {
      List<EngineGameChromeTransition.Kind> kinds = new ArrayList<>();
      for (EngineGameChromeTransition event : events) {
        kinds.add(event.kind());
      }
      return kinds;
    }

    private EngineGameChromeTransition.Kind lastKind() {
      return events.get(events.size() - 1).kind();
    }

    private EngineGameSnapshot lastSnapshot() {
      return events.get(events.size() - 1).snapshot();
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
    return (T) unsafe.allocateInstance(type);
  }

  private static final class CountingLeaseEngineManager extends EngineManager {
    private int leaseConflictCount;
    private boolean runWorkersInline;
    private boolean manualActivation;
    private boolean failNextNonFirstStart;
    private EngineGamePlan lastSuccessorPlan;
    private boolean runRetirementContinuations;
    private int successorStarts;
    private boolean stopBeforeSuccessorAdmission;
    private boolean observingStoppedSuccessor;
    private Boolean stoppedSuccessorAccepted;
    private EngineGameSnapshot successorSnapshotBeforeStop;
    private EngineGameSnapshot successorSnapshotAfterStop;
    private EngineGameOwnerTransaction successorOwnerBeforeAdmission;
    private EngineGameOwnerTransaction successorOwnerAfterAdmission;
    private final List<String> stoppedSuccessorWorkers = new ArrayList<>();
    private Runnable afterStopComment;
    private Path outputDirectory;
    private final List<Path> saveFailures = new ArrayList<>();
    private final List<String> saveSuccesses = new ArrayList<>();

    private CountingLeaseEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    public boolean startEngineGame(EngineGamePlan plan, boolean firstGame) {
      if (!firstGame) {
        successorStarts++;
        lastSuccessorPlan = plan;
        if (stopBeforeSuccessorAdmission) {
          stopBeforeSuccessorAdmission = false;
          successorSnapshotBeforeStop = Lizzie.engineGame.current();
          Lizzie.engineGame.stop();
          successorSnapshotAfterStop = Lizzie.engineGame.current();
          successorOwnerBeforeAdmission = EngineManager.activeEngineGameTransactionForTest();
          boolean inline = runWorkersInline;
          runWorkersInline = false;
          observingStoppedSuccessor = true;
          try {
            // Exercise the real admission after Stop removed this successor's product.
            stoppedSuccessorAccepted = super.startEngineGame(plan, false);
            successorOwnerAfterAdmission = EngineManager.activeEngineGameTransactionForTest();
            return stoppedSuccessorAccepted;
          } finally {
            observingStoppedSuccessor = false;
            runWorkersInline = inline;
          }
        }
      }
      if (manualActivation) {
        EngineGameOwnerTransaction owner =
            EngineManager.beginEngineGameTransaction(this, plan, null, firstGame);
        if (owner != null && outputDirectory != null) {
          // Manual engine activation skips startup I/O, not the real captured output identity.
          try {
            var capture =
                EngineManager.class.getDeclaredMethod(
                    "captureEngineGameOutputIdentity", EngineGameOwnerTransaction.class);
            capture.setAccessible(true);
            capture.invoke(this, owner);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        }
        return owner != null;
      }
      if (!firstGame) {
        if (failNextNonFirstStart) {
          failNextNonFirstStart = false;
          return false;
        }
        return true;
      }
      return super.startEngineGame(plan, firstGame);
    }

    @Override
    protected void showForegroundEngineLeaseInUse() {
      leaseConflictCount++;
    }

    @Override
    protected void dispatchEngineGameUi(Runnable update) {
      update.run();
    }

    @Override
    protected void appendEngineGameStopComment(boolean forceEngineGame) {
      super.appendEngineGameStopComment(forceEngineGame);
      Runnable hook = afterStopComment;
      afterStopComment = null;
      if (hook != null) hook.run();
    }

    @Override
    protected Path engineGameOutputDirectory() {
      return outputDirectory == null ? super.engineGameOutputDirectory() : outputDirectory;
    }

    @Override
    protected void showEngineGameSaveFailure(Path target, Exception failure) {
      saveFailures.add(target);
    }

    @Override
    protected void showEngineGameSaveSuccess(String message) {
      saveSuccesses.add(message);
    }

    @Override
    protected Thread createEngineGameWorker(Runnable task, String name) {
      if (observingStoppedSuccessor) stoppedSuccessorWorkers.add(name);
      if (!runWorkersInline || isEngineGameDeadlineWatcher(name)) {
        return super.createEngineGameWorker(task, name);
      }
      return new Thread(task, name) {
        @Override
        public synchronized void start() {
          run();
        }
      };
    }

    @Override
    protected Thread createEngineGameRetirementContinuationWorker(Runnable task, String name) {
      return new Thread(task, name) {
        @Override
        public synchronized void start() {
          // Ordinary contract tests drive onOwnerRetired explicitly. Save integration cases run
          // the real completion so an unscheduled worker cannot masquerade as a persistence gate.
          if (runRetirementContinuations) run();
        }
      };
    }

    private static boolean isEngineGameDeadlineWatcher(String name) {
      return name != null && name.startsWith("engine-game-deadline-");
    }
  }

  private static final class OccupancyLeelaz extends Leelaz {
    private OccupancyLeelaz() throws Exception {
      super("");
    }

    private ByteArrayOutputStream commandOutput;

    private void bindLiveRuntime() {
      commandOutput = new ByteArrayOutputStream();
      installFreshCommandOutputForTest(commandOutput);
      started = true;
      isLoaded = true;
      width = 19;
      height = 19;
      enableAutoSettleMatchRulesForTest();
    }

    private String commandText() {
      return commandOutput == null ? "" : commandOutput.toString();
    }

    @Override
    public void notPondering() {}

    @Override
    public void ponder() {}

    @Override
    public void ponder(boolean first, boolean white) {}

    @Override
    public void clearBestMoves() {}

    @Override
    public void nameCmd() {}

    @Override
    public void nameCmdfornoponder() {}

    @Override
    public void clearWithoutPonder() {}

    @Override
    public void sendCommand(String command) {}

    @Override
    public String getEngineName(int index) {
      return oriEngineCommand == null || oriEngineCommand.isEmpty() ? "engine" : oriEngineCommand;
    }
  }

  /** Settings writes succeed so shutdown reaches the real engine-game SGF save boundary. */
  private static final class InMemoryConfig extends Config {
    private InMemoryConfig() throws IOException {}

    @Override
    public void persist() {}

    @Override
    public void save() {}
  }

  private static final class SilentFrame extends LizzieFrame {
    private RuntimeException imageFailure;
    private int imageAttempts;
    private int shutdownCleanupCalls;
    private String lastResult;

    @Override
    public CompletableFuture<Void> shutdownHumanSlPreparation() {
      shutdownCleanupCalls++;
      throw new AssertionError("shutdown reached cleanup before the completed game was saved");
    }

    @Override
    public void saveImage(int x, int y, int width, int height, String path) {
      imageAttempts++;
      if (imageFailure != null) throw imageFailure;
    }

    @Override
    public boolean isDisplayable() {
      return true;
    }

    @Override
    public boolean isInputRoutingInitialized() {
      return true;
    }

    @Override
    public void addInput(boolean shouldAdd) {}

    @Override
    public void removeInput(boolean shouldRemove) {}

    @Override
    public void setResult(String result) {
      lastResult = result;
    }

    @Override
    public void resetTitle() {}

    @Override
    public void restoreWRN(boolean isGenmove) {}

    @Override
    public void refresh() {}

    @Override
    public void setPlayers(String whiteName, String blackName) {}

    @Override
    public void updateTitle() {}

    @Override
    public void reSetLoc() {}

    @Override
    public void clearWRNforGame(boolean isGenmove) {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }

    @Override
    public void showUnsupportedWebSocketAdvancedClock() {}
  }

  private static final class SilentToolbar extends BottomToolbar {
    @Override
    public void enableDisabelForEngineGame(boolean enable) {}
  }

  private static final class SilentMenu extends Menu {
    @Override
    public void toggleDoubleMenuGameStatus() {}

    @Override
    public void updateMenuStatusForEngine() {}

    @Override
    public void changeEngineIcon(int index, int mode) {}

    @Override
    public void changeicon(int index) {}
  }


  private static final class SilentBoard extends Board {
    private int engineGameClearCalls;

    @Override
    public void clear(boolean isEngineGame) {
      if (isEngineGame) engineGameClearCalls++;
    }
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super((java.awt.Window) null);
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
}
