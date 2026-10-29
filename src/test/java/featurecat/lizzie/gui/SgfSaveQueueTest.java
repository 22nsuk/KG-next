package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SgfSaveQueueTest {
  @Test
  void busyWriterDoesNotBlockEventThreadAndOnlyReceivesFrozenSnapshot() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean completedOnEdt = new AtomicBoolean();
    try {
      SgfSaveQueue queue =
          new SgfSaveQueue(
              worker,
              SwingUtilities::invokeLater,
              (path, snapshot) -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                assertEquals("captured", snapshot);
                writing.countDown();
                try {
                  assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                  throw new IOException(failure);
                }
              });
      CompletableFuture<Void>[] result = new CompletableFuture[1];
      SwingUtilities.invokeAndWait(
          () ->
              result[0] =
                  queue.submit(
                      Path.of("a.sgf"),
                      "captured",
                      () -> true,
                      () -> completedOnEdt.set(SwingUtilities.isEventDispatchThread()),
                      error -> fail(error)));
      assertTrue(writing.await(5, TimeUnit.SECONDS));
      assertFalse(result[0].isDone());
      SwingUtilities.invokeAndWait(() -> assertFalse(result[0].isDone()));
      release.countDown();
      result[0].get(5, TimeUnit.SECONDS);
      assertTrue(completedOnEdt.get());
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  void replacedBoardStillSavesItsSnapshotButCannotChangeNewBoardsCurrentFile() {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    AtomicBoolean current = new AtomicBoolean(true);
    AtomicInteger writes = new AtomicInteger();
    AtomicInteger effects = new AtomicInteger();
    SgfSaveQueue queue =
        new SgfSaveQueue(worker::add, Runnable::run, (path, snapshot) -> writes.incrementAndGet());
    var result =
        queue.submit(
            Path.of("old.sgf"),
            "old board",
            current::get,
            effects::incrementAndGet,
            error -> fail(error));
    current.set(false);
    worker.remove().run();
    assertTrue(result.isDone());
    assertEquals(1, writes.get());
    assertEquals(0, effects.get());
  }

  @Test
  void repeatedSavesCommitInOrderLeavingTheLatestCurrentFile() {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    ArrayDeque<Runnable> owner = new ArrayDeque<>();
    List<String> writes = new ArrayList<>();
    List<String> effects = new ArrayList<>();
    SgfSaveQueue queue =
        new SgfSaveQueue(worker::add, owner::add, (path, snapshot) -> writes.add(snapshot));
    queue.submit(
        Path.of("same.sgf"), "first", () -> true, () -> effects.add("first"), error -> fail(error));
    queue.submit(
        Path.of("same.sgf"),
        "edited",
        () -> true,
        () -> effects.add("edited"),
        error -> fail(error));
    var pending = queue.pending();
    assertFalse(pending.isDone());
    assertTrue(worker.isEmpty(), "owner preparation precedes disk work");
    owner.remove().run();
    worker.remove().run();
    assertEquals(List.of("first"), writes);
    assertTrue(worker.isEmpty(), "the next save must wait for owner publication");
    assertTrue(effects.isEmpty());
    owner.remove().run();
    assertEquals(List.of("first"), effects);
    owner.remove().run();
    worker.remove().run();
    assertEquals(List.of("first", "edited"), writes);
    assertFalse(pending.isDone(), "Shutdown must also await owner-thread completion");
    owner.remove().run();
    assertEquals(List.of("first", "edited"), effects);
    assertTrue(pending.isDone());
  }

  @Test
  void shutdownBarrierRetainsFailuresUntilAllCapturedUiCompletionsFinish() {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    ArrayDeque<Runnable> owner = new ArrayDeque<>();
    AtomicInteger failures = new AtomicInteger();
    AtomicInteger successes = new AtomicInteger();
    SgfSaveQueue queue =
        new SgfSaveQueue(
            worker::add,
            owner::add,
            (path, snapshot) -> {
              if (snapshot.equals("failed")) throw new IOException("disk full");
            });
    queue.submit(
        Path.of("failed.sgf"),
        "failed",
        () -> true,
        () -> fail("failed save must not succeed"),
        error -> failures.incrementAndGet());
    queue.submit(
        Path.of("saved.sgf"),
        "saved",
        () -> true,
        successes::incrementAndGet,
        error -> fail(error));
    var pending = queue.pending();

    owner.remove().run();
    worker.remove().run();
    owner.remove().run();
    assertFalse(pending.isDone(), "The later captured save still needs its UI completion");
    owner.remove().run();
    worker.remove().run();
    assertFalse(pending.isDone());
    owner.remove().run();

    CompletionException failure = assertThrows(CompletionException.class, pending::join);
    assertEquals("disk full", failure.getCause().getMessage());
    assertEquals(1, failures.get());
    assertEquals(1, successes.get());
    assertDoesNotThrow(() -> queue.pending().join(), "Already reported failures must not be sticky");
  }

  @Test
  void finishedFailureIsRemovedBeforeAShutdownContinuationCanRetry() {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    SgfSaveQueue queue =
        new SgfSaveQueue(
            worker::add,
            Runnable::run,
            (path, snapshot) -> {
              throw new IOException("disk full");
            });
    queue.submit(Path.of("failed.sgf"), "snapshot", () -> true, () -> fail("success"), error -> {});
    var pending = queue.pending();
    var nextBarrier = pending.handle((ignored, failure) -> queue.pending());

    worker.remove().run();

    assertThrows(CompletionException.class, pending::join);
    assertDoesNotThrow(() -> nextBarrier.join().join());
  }

  @Test
  void failureAndRejectedTaskNeverRunSuccessOrLeaveShutdownPending() {
    AtomicInteger errors = new AtomicInteger();
    SgfSaveQueue failed =
        new SgfSaveQueue(
            Runnable::run,
            Runnable::run,
            (path, snapshot) -> {
              throw new IOException("disk full");
            });
    assertTrue(
        failed
            .submit(
                Path.of("a.sgf"),
                "a",
                () -> true,
                () -> fail("success"),
                error -> errors.incrementAndGet())
            .isCompletedExceptionally());
    assertTrue(failed.pending().isDone());
    SgfSaveQueue rejected =
        new SgfSaveQueue(
            task -> {
              throw new RejectedExecutionException();
            },
            Runnable::run,
            (path, snapshot) -> fail("must not write"));
    assertTrue(
        rejected
            .submit(
                Path.of("a.sgf"),
                "a",
                () -> true,
                () -> fail("success"),
                error -> errors.incrementAndGet())
            .isCompletedExceptionally());
    assertTrue(rejected.pending().isDone());
    assertEquals(2, errors.get());
  }

  @Test
  void queuedPreparationObservesThePreviousOwnerPublicationBeforeItWrites() {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    ArrayDeque<Runnable> owner = new ArrayDeque<>();
    AtomicInteger published = new AtomicInteger();
    AtomicInteger preparations = new AtomicInteger();
    List<Integer> written = new ArrayList<>();
    SgfSaveQueue queue = new SgfSaveQueue(worker::add, owner::add, (path, text) -> fail("unused"));
    var first = queue.submit(() -> 1, () -> true, published::set, error -> fail(error));
    var second = queue.submitPrepared(
        () -> {
          preparations.incrementAndGet();
          int captured = published.get();
          return () -> {
            written.add(captured);
            return captured;
          };
        }, () -> true, value -> published.set(value + 1), error -> fail(error));
    var pending = queue.pending();

    assertEquals(0, preparations.get());
    assertTrue(worker.isEmpty());
    owner.remove().run();
    assertEquals(0, preparations.get(), "queued metadata cannot be captured during prior I/O");
    worker.remove().run();
    assertEquals(0, published.get());
    assertEquals(0, preparations.get(), "disk completion does not publish owner state");
    assertFalse(first.isDone());
    assertFalse(second.isDone());
    assertFalse(pending.isDone());

    owner.remove().run();
    assertTrue(first.isDone());
    assertEquals(1, first.join());
    assertEquals(1, published.get());
    assertEquals(0, preparations.get());
    assertFalse(pending.isDone());
    owner.remove().run();
    assertEquals(1, preparations.get());
    worker.remove().run();
    assertEquals(List.of(1), written, "the next write uses metadata from the committed predecessor");
    assertFalse(pending.isDone(), "both requests include owner publication in their completion");
    owner.remove().run();

    assertTrue(second.isDone());
    assertEquals(1, second.join());
    assertEquals(2, published.get());
    assertTrue(pending.isDone());
    assertDoesNotThrow(pending::join);
  }

  @Test
  void boundedQueueRejectsTheEighteenthRequestOnOwnerAndContinuesAfterFailure() {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    ArrayDeque<Runnable> owner = new ArrayDeque<>();
    AtomicBoolean onOwner = new AtomicBoolean();
    List<Integer> writes = new ArrayList<>();
    List<Integer> published = new ArrayList<>();
    List<Integer> failed = new ArrayList<>();
    SgfSaveQueue queue = new SgfSaveQueue(
        worker::add,
        task -> owner.add(() -> {
          onOwner.set(true);
          try {
            task.run();
          } finally {
            onOwner.set(false);
          }
        }),
        (path, text) -> fail("unused"));
    List<CompletableFuture<Integer>> requests = new ArrayList<>();
    for (int index = 0; index < 18; index++) {
      int request = index;
      requests.add(queue.submit(
          () -> {
            assertFalse(onOwner.get());
            writes.add(request);
            if (request == 0) throw new IOException("first write failed");
            return request;
          }, () -> true,
          value -> {
            assertTrue(onOwner.get());
            published.add(value);
          },
          error -> {
            assertTrue(onOwner.get(), "capacity rejection and write failure belong to owner");
            failed.add(request);
          }));
    }
    var pending = queue.pending();
    assertFalse(requests.get(17).isDone(), "rejection also waits for its owner callback");
    while (!owner.isEmpty()) owner.remove().run();
    assertTrue(requests.get(17).isCompletedExceptionally());
    CompletionException rejection = assertThrows(CompletionException.class, requests.get(17)::join);
    assertInstanceOf(RejectedExecutionException.class, rejection.getCause());
    assertEquals(List.of(17), failed);
    assertFalse(pending.isDone());

    for (int index = 0; index < 17; index++) {
      assertEquals(1, worker.size(), "only one accepted write can be active");
      worker.remove().run();
      assertFalse(requests.get(index).isDone());
      assertFalse(pending.isDone(), "the last owner callback remains part of the barrier");
      while (!owner.isEmpty()) owner.remove().run();
    }
    assertTrue(worker.isEmpty());
    assertEquals(java.util.stream.IntStream.range(0, 17).boxed().toList(), writes);
    assertEquals(java.util.stream.IntStream.range(1, 17).boxed().toList(), published);
    assertEquals(List.of(17, 0), failed);
    assertTrue(requests.stream().allMatch(CompletableFuture::isDone));
    assertInstanceOf(IOException.class,
        assertThrows(CompletionException.class, requests.get(0)::join).getCause());
    assertTrue(pending.isDone());
    assertThrows(CompletionException.class, pending::join);
    assertTrue(queue.pending().isDone());
    assertDoesNotThrow(() -> queue.pending().join(), "reported failures do not poison later barriers");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preparationFailureSkipsDiskWorkAndReleasesTheFollowingSave(boolean unchecked)
      throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    AtomicInteger writes = new AtomicInteger();
    AtomicBoolean failureOnEdt = new AtomicBoolean();
    AtomicBoolean successOnEdt = new AtomicBoolean();
    try {
      SgfSaveQueue queue = new SgfSaveQueue(
          worker, SwingUtilities::invokeLater, (path, text) -> fail("unused"));
      var failed = queue.<Integer>submitPrepared(
          () -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            if (unchecked) throw new IllegalStateException("capture failed");
            throw new IOException("capture failed");
          }, () -> true, value -> fail("failed capture must not succeed"),
          error -> failureOnEdt.set(SwingUtilities.isEventDispatchThread()));
      var next = queue.submit(
          () -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            return writes.incrementAndGet();
          }, () -> true, value -> successOnEdt.set(SwingUtilities.isEventDispatchThread()),
          error -> fail(error));
      var pending = queue.pending();

      assertEquals(1, next.get(5, TimeUnit.SECONDS));
      assertTrue(failed.isDone());
      CompletionException failure = assertThrows(CompletionException.class, failed::join);
      assertEquals("capture failed", failure.getCause().getMessage());
      if (unchecked) assertInstanceOf(IllegalStateException.class, failure.getCause());
      else assertInstanceOf(IOException.class, failure.getCause());
      assertTrue(failureOnEdt.get());
      assertTrue(successOnEdt.get());
      assertEquals(1, writes.get());
      // If capture already failed before the barrier was taken, that reported failure is not sticky.
      pending.handle((value, error) -> null).get(5, TimeUnit.SECONDS);
      assertDoesNotThrow(() -> queue.pending().join());
    } finally {
      worker.shutdownNow();
    }
  }
}
