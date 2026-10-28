package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/** Controls the real shared save worker while callers retain their production save paths. */
public final class SgfSaveTestSupport {
  private SgfSaveTestSupport() {}

  public static BlockedWriter blockWorker() throws Exception {
    return new BlockedWriter();
  }

  /** Hold one real disk completion before EDT publication, without occupying the EDT. */
  public static HeldPublication holdNextPublication() throws Exception {
    awaitPending();
    return new HeldPublication();
  }

  public static final class HeldPublication implements AutoCloseable {
    private final SgfSaveQueue queue;
    private final Field ownerField;
    private final Executor owner;
    private final CompletableFuture<Runnable> publication = new CompletableFuture<>();
    private final AtomicBoolean claimed = new AtomicBoolean();
    private boolean released;

    private HeldPublication() throws Exception {
      Field saves = SgfSaveCoordinator.class.getDeclaredField("SAVES");
      saves.setAccessible(true);
      queue = (SgfSaveQueue) saves.get(null);
      ownerField = SgfSaveQueue.class.getDeclaredField("owner");
      ownerField.setAccessible(true);
      owner = (Executor) ownerField.get(queue);
      ownerField.set(queue, (Executor) task -> {
        // Preparation is dispatched from the EDT. Only disk completion comes from the worker.
        if (!SwingUtilities.isEventDispatchThread() && claimed.compareAndSet(false, true)) {
          publication.complete(task);
        } else {
          owner.execute(task);
        }
      });
    }

    public void awaitWritten() throws Exception {
      publication.get(5, TimeUnit.SECONDS);
    }

    public void release() throws Exception {
      if (released) return;
      released = true;
      ownerField.set(queue, owner);
      publication.thenAccept(owner::execute);
    }

    @Override
    public void close() throws Exception {
      release();
      awaitPending();
    }
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
