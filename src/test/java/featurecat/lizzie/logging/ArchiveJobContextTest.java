package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.core.ContextBase;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ArchiveJobContextTest {
  @Test
  void allCompressionAndRetentionJobsMustFinishIncludingCancelledRunningJobs() throws Exception {
    var workers = Executors.newFixedThreadPool(2);
    var entered = new CountDownLatch(2);
    var releaseFirst = new CountDownLatch(1);
    var releaseSecond = new CountDownLatch(1);
    var secondReturned = new CountDownLatch(1);
    ArchiveJobContext context = context(workers);
    try {
      var first =
          context
              .getExecutorService()
              .submit(
                  () -> {
                    entered.countDown();
                    // Deliberately model I/O that continues after cancellation interrupts its
                    // thread.
                    boolean released = false;
                    while (!released) {
                      try {
                        released = releaseFirst.await(5, TimeUnit.SECONDS);
                      } catch (InterruptedException ignored) {
                      }
                    }
                  });
      var second =
          context
              .getAlternateExecutorService()
              .submit(
                  () -> {
                    entered.countDown();
                    try {
                      assertTrue(releaseSecond.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                      throw new AssertionError(failure);
                    } finally {
                      secondReturned.countDown();
                    }
                  });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(first.cancel(true));
      releaseSecond.countDown();
      second.get(5, TimeUnit.SECONDS);
      assertTrue(secondReturned.await(5, TimeUnit.SECONDS));
      assertTrue(context.hasPendingJobs(), "Cancelled running compression is still mutating files");
      assertThrows(IOException.class, () -> context.awaitIdle(System.nanoTime(), () -> false));
      releaseFirst.countDown();
      context.awaitIdle(System.nanoTime() + TimeUnit.SECONDS.toNanos(5), () -> false);
      assertFalse(context.hasPendingJobs());
      assertFalse(workers.isShutdown(), "The tracking context does not own executor shutdown");
    } finally {
      releaseFirst.countDown();
      releaseSecond.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void queuedCancellationRejectionAndFailureReleaseCompletionTracking() throws Exception {
    var workers = Executors.newSingleThreadExecutor();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    workers.submit(
        () -> {
          entered.countDown();
          try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
          } catch (InterruptedException failure) {
            throw new AssertionError(failure);
          }
        });
    ArchiveJobContext context = context(workers);
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var queued = context.getExecutorService().submit(() -> fail("Cancelled job must not run"));
      assertTrue(context.hasPendingJobs());
      assertTrue(queued.cancel(false));
      assertFalse(context.hasPendingJobs());
      release.countDown();
      var failed =
          context
              .getExecutorService()
              .submit(
                  () -> {
                    throw new IllegalStateException("failure");
                  });
      assertThrows(
          java.util.concurrent.ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
      context.awaitIdle(System.nanoTime() + TimeUnit.SECONDS.toNanos(5), () -> false);
      workers.shutdown();
      assertThrows(
          java.util.concurrent.RejectedExecutionException.class,
          () -> context.getExecutorService().submit(() -> {}));
      assertFalse(context.hasPendingJobs());
    } finally {
      release.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static ArchiveJobContext context(ExecutorService executor) {
    return new ArchiveJobContext(
        new ContextBase() {
          @Override
          public ExecutorService getExecutorService() {
            return executor;
          }

          @Override
          public ExecutorService getAlternateExecutorService() {
            return executor;
          }
        });
  }
}
