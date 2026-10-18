package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineFollowController;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.gui.VariationTreeTrialCapture;
import featurecat.lizzie.util.AtomicSgfFileWriter;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WebTrialRecordPreservationTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {
      "(;SZ[19]C[anchor])",
      "(;SZ[19];B[dd]C[anchor])",
      "(;SZ[19];B[dd];W[]C[anchor])",
      "(;SZ[19];B[dd];AE[dd]AW[cc]PL[B]C[anchor])"
  })
  void trialPlaceholdersRemainSaveableWithoutChangingTheMainline(String initial) throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false)) {
      configure();
      BoardHistoryList history = SGFParser.parseSgf(initial, true);
      f.board.setHistory(history);
      BoardHistoryNode anchor = history.getEnd();
      history.setHead(anchor);
      f.manager.setMainlineTailSupplier(() -> anchor);
      BoardData original = anchor.getData().clone();
      String before = save(f.board, true, true);
      String beforeRaw = save(f.board, true, false);

      f.enter();
      BoardData dummy = anchor.variations.get(0).getData();
      assertTrue(dummy.dummy);
      assertTrue(dummy.isPassNode());
      assertTrue(dummy.lastMove.isEmpty());
      assertEquals(original.blackToPlay, dummy.blackToPlay);
      assertEquals(original.moveNumber, dummy.moveNumber);
      assertArrayEquals(original.stones, dummy.stones);
      assertEquals(original.zobrist, dummy.zobrist);
      assertEquals("", dummy.comment);
      assertEquals(0, dummy.getPlayouts());
      assertTrue(dummy.getProperties().isEmpty());
      assertTrue(save(f.board, false, false).contains("DD[true]"));
      f.send(f.owner, "exit_trial");
      assertTrue(anchor.variations.isEmpty(), "unused mainline reservation is removed");
      assertEquals(before, save(f.board, true, true));

      f.enter();
      f.send(f.owner, "trial_move", "x", 4, "y", 4);
      BoardHistoryNode firstTrial = f.display.get();
      f.send(f.owner, "trial_reset");
      f.send(f.owner, "trial_navigate", "childIndex", 0);
      assertSame(anchor, f.display.get(), "the dummy cannot become the trial cursor");
      f.send(f.owner, "trial_move", "x", 5, "y", 5);
      f.send(f.owner, "exit_trial");

      assertSame(anchor, history.getCurrentHistoryNode());
      assertSame(anchor, history.getMainEnd());
      assertEquals(original.blackToPlay, anchor.getData().blackToPlay);
      assertEquals(original.moveNumber, anchor.getData().moveNumber);
      assertArrayEquals(original.stones, anchor.getData().stones);
      assertEquals(3, anchor.variations.size());
      assertTrue(anchor.variations.get(0).getData().dummy);
      assertSame(firstTrial, anchor.variations.get(1));

      String normal = save(f.board, false, false);
      String raw = save(f.board, true, false);
      String comments = save(f.board, true, true);
      assertEquals(1, occurrences(normal, "C[anchor]"));
      // Raw export retains root metadata but omits ordinary move comments; keep that contract.
      assertEquals(occurrences(beforeRaw, "C[anchor]"), occurrences(raw, "C[anchor]"));
      assertEquals(1, occurrences(comments, "C[anchor]"));
      Path savedFile = directory.resolve("trial.sgf");
      AtomicSgfFileWriter.write(savedFile, raw);
      BoardHistoryList restored = SGFParser.parseSgf(Files.readString(savedFile), true);
      BoardHistoryNode restoredAnchor = restored.getMainEnd();
      assertArrayEquals(original.stones, restoredAnchor.getData().stones);
      assertEquals(original.blackToPlay, restoredAnchor.getData().blackToPlay);
      assertEquals(3, restoredAnchor.variations.size());
      assertTrue(restoredAnchor.variations.get(0).getData().dummy);
      Stone expectedColor = original.blackToPlay ? Stone.BLACK : Stone.WHITE;
      for (int index = 1; index <= 2; index++) {
        BoardData move = restoredAnchor.variations.get(index).getData();
        assertTrue(move.isMoveNode());
        assertEquals(expectedColor, move.lastMoveColor);
        assertArrayEquals(new int[] {index + 3, index + 3}, move.lastMove.orElseThrow());
        assertEquals(!original.blackToPlay, move.blackToPlay);
      }
    }
  }

  @Test
  void existingMainlineAndBranchesAreReusedAndRoundTripInTheirOriginalOrder() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false)) {
      configure();
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[19];B[dd](;W[ee]C[mainline])(;W[ff]C[branch]))", true);
      f.board.setHistory(history);
      BoardHistoryNode anchor = history.getStart().next().orElseThrow();
      history.setHead(anchor);
      var children = List.copyOf(anchor.variations);
      String before = save(f.board, true, true);
      f.enter();
      f.send(f.owner, "trial_move", "x", 5, "y", 5);
      assertSame(children.get(1), f.display.get());
      f.send(f.owner, "trial_reset");
      f.send(f.owner, "trial_move", "x", 4, "y", 4);
      assertSame(children.get(0), f.display.get());
      f.send(f.owner, "exit_trial");
      assertEquals(children, anchor.variations);
      assertEquals(before, save(f.board, true, true));
    }
  }

  @ParameterizedTest
  @CsvSource({
      "board, enter", "history, enter", "board, move", "history, move",
      "board, exit", "history, exit", "board, force", "history, force",
      "board, idle", "history, idle", "board, stop", "history, stop"
  })
  void everyStructuralTransitionWaitsForTheSnapshotOwner(String owner, String operation)
      throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false)) {
      if (!operation.equals("enter")) f.enter();
      Runnable command = switch (operation) {
        case "force" -> f.manager::forceExitTrial;
        case "idle" -> f.events.timers.get(0).callback;
        case "stop" -> f.manager::stop;
        default -> {
          f.enqueue(f.owner, operation.equals("move") ? "trial_move" : operation + "_trial",
              "x", 3, "y", 3);
          yield f.events.work.remove();
        }
      };
      FutureTask<Void> completion = new FutureTask<>(command, null);
      Thread worker = new Thread(completion, "test-trial-" + operation);
      worker.setDaemon(true);
      Object monitor = owner.equals("board") ? f.board : f.board.getHistory();
      try {
        synchronized (monitor) {
          var originalChildren = List.copyOf(f.anchor.variations);
          worker.start();
          awaitBlockedOn(worker, monitor, completion);
          assertEquals(originalChildren, f.anchor.variations);
          assertEquals(operation.equals("enter") ? "" : "owner",
              CompletableFuture.supplyAsync(f.manager::getCurrentTrialOwner)
                  .get(3, TimeUnit.SECONDS),
              "waiting for Board/history must not retain the manager monitor");
        }
        completion.get(5, TimeUnit.SECONDS);
        assertEquals(operation.equals("enter") ? 1 : operation.equals("move") ? 2 : 0,
            f.anchor.variations.size());
      } finally {
        worker.join(5000);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"history", "server"})
  void waitingCommandRevalidatesItsOriginalHistoryAndConnection(String replacement)
      throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      WebBoardServer nextServer = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 2);
      f.enqueue(f.owner, "trial_move", "x", 3, "y", 3);
      FutureTask<Void> completion = new FutureTask<>(f.events.work.remove(), null);
      Thread worker = new Thread(completion, "test-waiting-web-command");
      worker.setDaemon(true);
      try {
        synchronized (f.board) {
          worker.start();
          awaitBlockedOn(worker, f.board, completion);
          if (replacement.equals("history")) {
            f.board.setHistory(new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight)));
          } else {
            f.manager.attachWebSocketServer(nextServer);
          }
        }
        completion.get(5, TimeUnit.SECONDS);
        assertEquals(1, f.anchor.variations.size());
        assertSame(f.anchor, f.display.get());
        assertTrue(f.events.publications.isEmpty());
      } finally {
        worker.join(5000);
        nextServer.stop(1000);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"board", "history"})
  void directEntryOnALiveBranchStillUsesThatHistorysSnapshotOwners(String owner) throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false)) {
      configure();
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[19];B[dd](;W[ee])(;W[ff]))", true);
      f.board.setHistory(history);
      BoardHistoryNode trunk = history.getStart().next().orElseThrow();
      BoardHistoryNode current = trunk.variations.get(0);
      BoardHistoryNode branch = trunk.variations.get(1);
      history.setHead(current);
      assertTrue(f.manager.enterTrial("owner", branch));
      FutureTask<Void> completion = new FutureTask<>(() -> f.manager.applyTrialMove("owner", 6, 6), null);
      Thread worker = new Thread(completion, "test-direct-branch-trial");
      worker.setDaemon(true);
      Object monitor = owner.equals("board") ? f.board : history;
      try {
        synchronized (monitor) {
          worker.start();
          awaitBlockedOn(worker, monitor, completion);
          assertEquals(1, branch.variations.size());
        }
        completion.get(5, TimeUnit.SECONDS);
        assertEquals(2, branch.variations.size());
        assertSame(current, history.getCurrentHistoryNode());
      } finally {
        worker.join(5000);
      }
    }
  }

  @Test
  void anchorDisplacedDuringEngineAdmissionIsRejectedWithoutAPlaceholderOrLeakedReservation()
      throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false)) {
      configure();
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[19];B[dd])", true);
      f.board.setHistory(history);
      BoardHistoryNode root = history.getStart();
      AtomicBoolean displaced = new AtomicBoolean();
      BoardHistoryNode anchor = new BoardHistoryNode(history.getEnd().getData()) {
        @Override public Optional<BoardHistoryNode> previous() {
          // This is the detached representation produced by addOrGoto(changeMove=true).
          return displaced.get() ? null : super.previous();
        }
      };
      root.variations.set(0, anchor);
      root.setPreviousForChild(anchor);
      history.setHead(anchor);
      Leelaz engine = new Leelaz("") {
        @Override public EngineModeReservation beginEngineModeReservation() {
          EngineModeReservation reservation = super.beginEngineModeReservation();
          if (reservation != null && !displaced.get()) {
            synchronized (f.board) {
              synchronized (history) {
                root.variations.clear();
                history.setHead(root);
                displaced.set(true);
              }
            }
          }
          return reservation;
        }
      };
      Lizzie.leelaz = engine;
      f.send(f.owner, "enter_trial");
      assertEquals("engine_busy", f.owner.messages.get(0).getString("reason"));
      assertEquals("", f.manager.getCurrentTrialOwner());
      assertNull(f.display.get());
      assertTrue(anchor.variations.isEmpty());
      Leelaz.EngineModeReservation next = engine.beginEngineModeReservation();
      assertNotNull(next);
      next.close();
    }
  }

  @Test
  void engineDisplayAndPublishEffectsRunAfterReleasingTheTreeAndManager() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false)) {
      List<String> engineEvents = new ArrayList<>();
      f.manager.setOverrideSinkForTest(node -> {
        assertOutsideStateLocks(f);
        f.display.set(node);
      });
      f.manager.setDesktopRefresherForTest(() -> assertOutsideStateLocks(f));
      f.events.beforePublish = () -> assertOutsideStateLocks(f);
      // Every submitting method is overridden; no engine executor work is needed in this test.
      f.manager.setEngineFollowController(new EngineFollowController(null) {
        @Override public void onTrialEnter(BoardHistoryNode anchor) {
          assertOutsideStateLocks(f);
          engineEvents.add("enter");
        }
        @Override public void onTrialDisplayNodeChanged(BoardHistoryNode node) {
          assertOutsideStateLocks(f);
          engineEvents.add("display");
        }
        @Override public void onTrialExit(BoardHistoryNode tail) {
          assertOutsideStateLocks(f);
          engineEvents.add("exit");
        }
      });
      f.enter();
      f.send(f.owner, "trial_move", "x", 3, "y", 3);
      f.send(f.owner, "trial_reset");
      f.send(f.owner, "trial_navigate", "direction", "forward");
      f.send(f.owner, "exit_trial");
      assertEquals(List.of("enter", "display", "display", "display", "exit"), engineEvents);
      assertNull(f.display.get());
    }
  }

  @Test
  void actualRenderCaptureFinishesBeforeTheQueuedTrialMoveCanChangeItsChildren() throws Exception {
    try (LiveTrial live = new LiveTrial()) {
      AtomicReference<Future<?>> committed = new AtomicReference<>();
      VariationTreeTrialCapture.capture(live.f.board, live.f.anchor, () -> {
        live.f.enqueue(live.f.owner, "trial_move", "x", 3, "y", 3);
        committed.set(live.executor.submit(() -> {}));
        awaitBlockedOn(live.thread.get(), live.f.board, committed.get());
        assertEquals(1, live.f.anchor.variations.size());
      });
      committed.get().get(5, TimeUnit.SECONDS);
      assertEquals(2, live.f.anchor.variations.size());
    }
  }

  @Test
  void actualSgfFileCannotMixBranchesFromDifferentTrialTransitions() throws Exception {
    try (LiveTrial live = new LiveTrial()) {
      configure();
      WebTrialTestFixture f = live.f;
      f.enqueue(f.owner, "trial_move", "x", 3, "y", 3);
      live.drain();
      CountDownLatch readingChild = new CountDownLatch(1);
      CountDownLatch releaseCapture = new CountDownLatch(1);
      AtomicBoolean pause = new AtomicBoolean(true);
      BoardHistoryNode first = new BoardHistoryNode(f.display.get().getData()) {
        @Override public List<BoardHistoryNode> getVariations() {
          if (SwingUtilities.isEventDispatchThread() && pause.compareAndSet(true, false)) {
            readingChild.countDown();
            await(releaseCapture);
          }
          return super.getVariations();
        }
      };
      synchronized (f.board) {
        synchronized (f.board.getHistory()) {
          f.anchor.variations.set(1, first);
          f.anchor.setPreviousForChild(first);
        }
      }
      f.enqueue(f.owner, "trial_reset");
      f.enqueue(f.owner, "trial_navigate", "childIndex", 1);
      live.drain();
      CompletableFuture<String> captured = captureAsync(f.board, true, false);
      try {
        assertTrue(readingChild.await(5, TimeUnit.SECONDS));
        // B then C must not become a save containing C under A but missing the earlier B sibling.
        f.enqueue(f.owner, "trial_reset");
        f.enqueue(f.owner, "trial_move", "x", 4, "y", 4);
        f.enqueue(f.owner, "trial_reset");
        f.enqueue(f.owner, "trial_navigate", "childIndex", 1);
        f.enqueue(f.owner, "trial_move", "x", 5, "y", 5);
        Future<?> committed = live.executor.submit(() -> {});
        awaitBlockedOn(live.thread.get(), f.board.getHistory(), committed);
        releaseCapture.countDown();
        String saved = captured.get(5, TimeUnit.SECONDS);
        committed.get(5, TimeUnit.SECONDS);
        assertEquals(3, f.anchor.variations.size());
        assertEquals(1, first.variations.size());
        Path file = directory.resolve("consistent-trial.sgf");
        AtomicSgfFileWriter.write(file, saved);
        String onDisk = Files.readString(file);
        assertTrue(onDisk.contains(";B[dd]"));
        assertFalse(onDisk.contains(";B[ee]"));
        assertFalse(onDisk.contains(";W[ff]"));
        BoardHistoryList restored = SGFParser.parseSgf(onDisk, true);
        assertEquals(2, restored.getStart().variations.size());
        assertTrue(restored.getStart().variations.get(0).getData().dummy);
        assertTrue(restored.getStart().variations.get(1).variations.isEmpty());
      } finally {
        releaseCapture.countDown();
        captured.handle((value, failure) -> null).get(5, TimeUnit.SECONDS);
      }
    }
  }

  private void configure() {
    Lizzie.config = ConfigTestHelper.createForTests(directory);
    Lizzie.config.appendWinrateToComment = false;
  }

  private static void assertOutsideStateLocks(WebTrialTestFixture f) {
    assertFalse(Thread.holdsLock(f.board), "callback retained Board");
    assertFalse(Thread.holdsLock(f.board.getHistory()), "callback retained history");
    assertFalse(Thread.holdsLock(f.manager), "callback retained manager");
  }

  private static int occurrences(String value, String token) {
    return (value.length() - value.replace(token, "").length()) / token.length();
  }

  private static String save(Board board, boolean raw, boolean comments) throws Exception {
    return captureAsync(board, raw, comments).get(5, TimeUnit.SECONDS);
  }

  private static CompletableFuture<String> captureAsync(Board board, boolean raw, boolean comments) {
    CompletableFuture<String> result = new CompletableFuture<>();
    SwingUtilities.invokeLater(() -> {
      try {
        result.complete(SGFParser.saveSnapshot(board, raw, comments, false));
      } catch (Throwable failure) {
        result.completeExceptionally(failure);
      }
    });
    return result;
  }

  private static void awaitBlockedOn(Thread thread, Object monitor, Future<?> completion) {
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        if (completion.isDone()) {
          completion.get();
          fail("trial changed the live tree while its snapshot owner was still reading");
        }
        var info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
        if (info != null && info.getThreadState() == Thread.State.BLOCKED
            && info.getLockInfo() != null
            && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor)) return;
        Thread.sleep(1); // Observe the actual monitor wait; elapsed time is not the race oracle.
      }
      fail("trial never reached the expected snapshot monitor");
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  private static final class LiveTrial implements AutoCloseable {
    final WebTrialTestFixture f = new WebTrialTestFixture(false, true);
    final AtomicReference<Thread> thread = new AtomicReference<>();
    final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
      Thread worker = new Thread(task, "test-live-web-records");
      worker.setDaemon(true);
      thread.set(worker);
      return worker;
    });

    LiveTrial() throws Exception {
      WebBoardDataCollector collector = new WebBoardDataCollector(executor,
          () -> f.display.get() == null ? f.anchor : f.display.get(), () -> f.anchor);
      f.manager.setCollectorForTest(collector);
      f.enqueue(f.owner, "enter_trial");
      drain();
      f.sessionId = f.owner.messages.stream()
          .filter(message -> "trial_granted".equals(message.optString("type")))
          .findFirst().orElseThrow().getString("sessionId");
    }

    void drain() throws Exception {
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Override public void close() throws Exception {
      f.close();
    }
  }
}
