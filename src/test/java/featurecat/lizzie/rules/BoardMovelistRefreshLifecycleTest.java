package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardMovelistRefreshLifecycleTest {
  @Test
  void refreshUsesItsBoardInstanceWhenTheGlobalBoardIsUnavailable() throws Exception {
    Board previousBoard = Lizzie.board;
    TrackingBoard board = trackingBoard(singleNodeHistory());
    try {
      Lizzie.board = null;

      board.setMovelistAll();

      assertTrue(board.firstUpdate.await(2, TimeUnit.SECONDS), "instance refresh should run.");
      board.awaitWorkers();
      assertNull(board.failure.get(), "refresh must not dereference the global board.");
    } finally {
      try {
        board.close();
      } finally {
        Lizzie.board = previousBoard;
      }
    }
  }

  @Test
  void replacingHistoryCancelsAnInFlightRefreshBeforeItVisitsMoreNodes() throws Exception {
    Board previousBoard = Lizzie.board;
    TrackingBoard board = trackingBoard(twoNodeHistory());
    try {
      Lizzie.board = board;
      board.blockFirstUpdate = true;
      board.setMovelistAll();
      assertTrue(
          board.firstUpdate.await(2, TimeUnit.SECONDS), "refresh should reach its first node.");

      board.setHistory(singleNodeHistory());
      board.releaseFirstUpdate.countDown();

      assertFalse(
          board.secondUpdate.await(500, TimeUnit.MILLISECONDS),
          "the stale refresh must stop after its history is replaced.");
      assertEquals(1, board.updates.get(), "only the already-running update may finish.");
    } finally {
      try {
        board.close();
      } finally {
        Lizzie.board = previousBoard;
      }
    }
  }

  @Test
  void completionRunsOnlyAfterEveryMoveListNodeIsUpdated() throws Exception {
    Board previousBoard = Lizzie.board;
    TrackingBoard board = trackingBoard(twoNodeHistory());
    CountDownLatch completed = new CountDownLatch(1);
    try {
      Lizzie.board = board;

      board.setMovelistAll(completed::countDown);

      assertTrue(completed.await(2, TimeUnit.SECONDS), "refresh completion should be delivered.");
      assertEquals(2, board.updates.get(), "completion must observe a fully rebuilt move list.");
    } finally {
      try {
        board.close();
      } finally {
        Lizzie.board = previousBoard;
      }
    }
  }

  @Test
  void staleRefreshDoesNotDeliverItsCompletionAfterHistoryReplacement() throws Exception {
    Board previousBoard = Lizzie.board;
    TrackingBoard board = trackingBoard(twoNodeHistory());
    CountDownLatch completed = new CountDownLatch(1);
    try {
      Lizzie.board = board;
      board.blockFirstUpdate = true;
      board.setMovelistAll(completed::countDown);
      assertTrue(
          board.firstUpdate.await(2, TimeUnit.SECONDS), "refresh should reach its first node.");

      board.setHistory(singleNodeHistory());
      board.releaseFirstUpdate.countDown();

      assertFalse(
          completed.await(500, TimeUnit.MILLISECONDS),
          "a refresh for replaced history must not publish a stale completion.");
    } finally {
      try {
        board.close();
      } finally {
        Lizzie.board = previousBoard;
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void burstRequestsShareOneWorkerAndOneFollowUpWithoutDroppingCompletions(boolean concurrent)
      throws Exception {
    try (TrackingBoard board = trackingBoard(twoNodeHistory())) {
      board.blockFirstUpdate = true;
      List<Integer> completedAt = new CopyOnWriteArrayList<>();
      CountDownLatch completed = new CountDownLatch(3);
      Runnable completion =
          () -> {
            completedAt.add(board.updates.get());
            completed.countDown();
          };
      board.setMovelistAll(completion);
      assertTrue(board.firstUpdate.await(5, TimeUnit.SECONDS));
      board.setMovelistAll(completion);
      if (concurrent) {
        ExecutorService producers = Executors.newFixedThreadPool(4);
        try {
          List<Future<?>> requests = new ArrayList<>();
          for (int producer = 0; producer < 4; producer++) {
            requests.add(
                producers.submit(
                    () -> {
                      for (int request = 0; request < 250; request++) board.setMovelistAll();
                    }));
          }
          for (Future<?> request : requests) request.get(5, TimeUnit.SECONDS);
        } finally {
          producers.shutdownNow();
          assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS));
        }
      } else {
        for (int request = 0; request < 1000; request++) board.setMovelistAll();
      }
      board.setMovelistAll(completion);
      board.setMovelistAll(); // A plain progress request must not erase pending completions.
      board.releaseFirstUpdate.countDown();

      assertTrue(completed.await(5, TimeUnit.SECONDS));
      board.awaitWorkers();
      assertEquals(
          List.of(3, 3, 3),
          completedAt,
          "each completion must follow the complete pass covering its request");
      assertEquals(3, board.updates.get(), "stop the superseded pass at its next node boundary");
      assertEquals(1, board.workers.size(), "a burst must not spawn one worker per request");
      assertNull(board.failure.get());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void historyReplacementRetiresActiveAndWaitingCompletionsButKeepsTheNewRequest(
      boolean restoreOriginal) throws Exception {
    BoardHistoryList original = twoNodeHistory();
    try (TrackingBoard board = trackingBoard(original)) {
      Board.ClearStateSnapshot snapshot = board.captureClearState();
      board.blockFirstUpdate = true;
      AtomicInteger staleCompletions = new AtomicInteger();
      board.setMovelistAll(staleCompletions::incrementAndGet);
      assertTrue(board.firstUpdate.await(5, TimeUnit.SECONDS));
      board.setMovelistAll(staleCompletions::incrementAndGet);

      board.setHistory(singleNodeHistory());
      if (restoreOriginal) board.restoreClearState(snapshot);
      CountDownLatch completed = new CountDownLatch(1);
      board.setMovelistAll(completed::countDown);
      board.releaseFirstUpdate.countDown();

      assertTrue(completed.await(5, TimeUnit.SECONDS));
      board.awaitWorkers();
      assertEquals(0, staleCompletions.get(), "restoring the same object must not revive old work");
      assertEquals(restoreOriginal ? 3 : 2, board.updates.get());
      assertEquals(1, board.workers.size());
      assertNull(board.failure.get());
    }
  }

  @Test
  void completionCanRequestAnotherPassFromAnotherThreadWithoutHoldingQueueOrBoardLocks()
      throws Exception {
    try (TrackingBoard board = trackingBoard(twoNodeHistory())) {
      ExecutorService requester = Executors.newSingleThreadExecutor();
      CountDownLatch completed = new CountDownLatch(1);
      try {
        board.setMovelistAll(
            () -> {
              assertFalse(Thread.holdsLock(board));
              try {
                requester
                    .submit(() -> board.setMovelistAll(completed::countDown))
                    .get(5, TimeUnit.SECONDS);
              } catch (Exception failure) {
                throw new AssertionError(
                    "a completion must not hold refresh ownership locks", failure);
              }
            });
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        board.awaitWorkers();
        assertEquals(4, board.updates.get());
        assertEquals(1, board.workers.size());
        assertNull(board.failure.get());
      } finally {
        requester.shutdownNow();
        assertTrue(requester.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void failedUpdateDoesNotPublishSuccessOrLoseAnAlreadyQueuedNewHistory() throws Exception {
    try (TrackingBoard board = trackingBoard(twoNodeHistory())) {
      board.blockFirstUpdate = true;
      IllegalStateException failure = new IllegalStateException("failed metadata update");
      board.firstUpdateFailure = failure;
      AtomicInteger staleCompletions = new AtomicInteger();
      board.setMovelistAll(staleCompletions::incrementAndGet);
      assertTrue(board.firstUpdate.await(5, TimeUnit.SECONDS));
      board.setHistory(singleNodeHistory());
      CountDownLatch completed = new CountDownLatch(1);
      board.setMovelistAll(completed::countDown);
      board.releaseFirstUpdate.countDown();

      assertTrue(completed.await(5, TimeUnit.SECONDS));
      board.awaitWorkers();
      assertEquals(0, staleCompletions.get());
      assertEquals(2, board.updates.get());
      assertSame(failure, board.failure.get());
    }
  }

  @Test
  void interruptedPassDropsItsCompletionButRetainsTheWaitingRequest() throws Exception {
    try (TrackingBoard board = trackingBoard(twoNodeHistory())) {
      board.blockFirstUpdate = true;
      AtomicInteger interruptedCompletion = new AtomicInteger();
      CountDownLatch completed = new CountDownLatch(1);
      board.setMovelistAll(interruptedCompletion::incrementAndGet);
      assertTrue(board.firstUpdate.await(5, TimeUnit.SECONDS));
      board.setMovelistAll(completed::countDown);
      board.workers.get(0).interrupt();
      board.releaseFirstUpdate.countDown();

      assertTrue(completed.await(5, TimeUnit.SECONDS));
      board.awaitWorkers();
      assertEquals(0, interruptedCompletion.get());
      assertEquals(3, board.updates.get());
      assertNull(board.failure.get());
    }
  }

  @Test
  void failedCompletionDoesNotDropItsMergedPeerOrReentrantRequest() throws Exception {
    try (TrackingBoard board = trackingBoard(twoNodeHistory())) {
      board.blockFirstUpdate = true;
      board.setMovelistAll();
      assertTrue(board.firstUpdate.await(5, TimeUnit.SECONDS));
      IllegalStateException failure = new IllegalStateException("failed completion");
      CountDownLatch completed = new CountDownLatch(2);
      board.setMovelistAll(
          () -> {
            board.setMovelistAll(completed::countDown);
            throw failure;
          });
      board.setMovelistAll(completed::countDown);
      board.releaseFirstUpdate.countDown();

      assertTrue(completed.await(5, TimeUnit.SECONDS));
      board.awaitWorkers();
      assertEquals(5, board.updates.get());
      assertSame(failure, board.failure.get());
    }
  }

  private static TrackingBoard trackingBoard(BoardHistoryList history) throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.updates = new AtomicInteger();
    board.firstUpdate = new CountDownLatch(1);
    board.secondUpdate = new CountDownLatch(1);
    board.releaseFirstUpdate = new CountDownLatch(1);
    board.workers = new CopyOnWriteArrayList<>();
    board.failure = new AtomicReference<>();
    board.setHistory(history);
    return board;
  }

  private static BoardHistoryList singleNodeHistory() {
    return new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight));
  }

  private static BoardHistoryList twoNodeHistory() {
    BoardHistoryList history = singleNodeHistory();
    history.add(BoardData.empty(Board.boardWidth, Board.boardHeight));
    return history;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class TrackingBoard extends Board implements AutoCloseable {
    private AtomicInteger updates;
    private CountDownLatch firstUpdate;
    private CountDownLatch secondUpdate;
    private CountDownLatch releaseFirstUpdate;
    private volatile boolean blockFirstUpdate;
    private RuntimeException firstUpdateFailure;
    private List<Thread> workers;
    private AtomicReference<Throwable> failure;

    private TrackingBoard() {
      super();
    }

    @Override
    public void updateMovelist(BoardHistoryNode node) {
      Thread worker = Thread.currentThread();
      if (!workers.contains(worker)) {
        worker.setUncaughtExceptionHandler((thread, error) -> failure.compareAndSet(null, error));
        workers.add(worker);
      }
      int update = updates.incrementAndGet();
      if (update == 1) {
        firstUpdate.countDown();
        if (blockFirstUpdate) {
          try {
            assertTrue(
                releaseFirstUpdate.await(5, TimeUnit.SECONDS), "test must release the worker");
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        }
        if (firstUpdateFailure != null) throw firstUpdateFailure;
      } else {
        secondUpdate.countDown();
      }
    }

    private void awaitWorkers() throws InterruptedException {
      for (int index = 0; index < workers.size(); index++) {
        Thread worker = workers.get(index);
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(worker.isAlive(), "owned refresh worker must settle before fixture teardown");
      }
    }

    @Override
    public void close() throws InterruptedException {
      releaseFirstUpdate.countDown();
      awaitWorkers();
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
