package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** Controls the real shared save worker while callers retain their production save paths. */
public final class SgfSaveTestSupport {
  private SgfSaveTestSupport() {}

  public static BlockedWriter blockWorker() throws Exception {
    return new BlockedWriter();
  }

  /** Drains success/failure publication; callers assert their individual operation's outcome. */
  public static void awaitPending() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      long remaining = deadline - System.nanoTime();
      assertTrue(remaining > 0, "accepted saves did not settle");
      SgfSaveCoordinator.pendingSaves().handle((ignored, failure) -> null)
          .get(remaining, TimeUnit.NANOSECONDS);
      SwingUtilities.invokeAndWait(() -> {});
    } while (!SgfSaveCoordinator.pendingSaves().isDone());
  }

  public static final class BlockedWriter implements AutoCloseable {
    private final CountDownLatch release = new CountDownLatch(1);
    private final CompletableFuture<Void> completion;

    private BlockedWriter() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      completion = SgfSaveCoordinator.submitSave(
          () -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            started.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Controlled save worker was not released");
              }
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new IOException(failure);
            }
            return null;
          }, () -> true, ignored -> {}, failure -> {});
      assertTrue(started.await(5, TimeUnit.SECONDS), "shared save worker did not start");
    }

    public void release() {
      release.countDown();
    }

    @Override
    public void close() throws Exception {
      release();
      completion.get(5, TimeUnit.SECONDS);
      awaitPending();
    }
  }
}
