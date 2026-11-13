package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Blocks the real read worker, retaining the production read, ownership and parser path. */
public final class SgfReadTestSupport {
  private SgfReadTestSupport() {}

  public static BlockedRead block() throws Exception { return new BlockedRead(); }

  /** Wait for real reads, their EDT application, and any selection queued by completion. */
  public static void awaitPending() throws Exception {
    assertFalse(SwingUtilities.isEventDispatchThread());
    Field workerField = SgfReadQueue.class.getDeclaredField("worker");
    Field activeField = SgfReadQueue.class.getDeclaredField("active");
    Field waitingField = SgfReadQueue.class.getDeclaredField("waiting");
    Field finalizationField = LizzieFrame.class.getDeclaredField("localSgfLoadFinalization");
    workerField.setAccessible(true);
    activeField.setAccessible(true);
    waitingField.setAccessible(true);
    finalizationField.setAccessible(true);
    Executor worker = (Executor) workerField.get(SgfReadQueue.SHARED);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    AtomicBoolean idle = new AtomicBoolean();
    AtomicReference<CompletableFuture<?>> finalization = new AtomicReference<>();
    do {
      var drained = new CompletableFuture<Void>();
      SwingUtilities.invokeAndWait(() -> worker.execute(() ->
          SwingUtilities.invokeLater(() -> drained.complete(null))));
      long remaining = deadline - System.nanoTime();
      assertTrue(remaining > 0, "SGF reads did not settle");
      drained.get(remaining, TimeUnit.NANOSECONDS);
      SwingUtilities.invokeAndWait(() -> {
        try {
          idle.set(activeField.get(SgfReadQueue.SHARED) == null
              && waitingField.get(SgfReadQueue.SHARED) == null);
          finalization.set(Lizzie.frame == null ? null
              : (CompletableFuture<?>) finalizationField.get(Lizzie.frame));
        } catch (IllegalAccessException failure) {
          throw new AssertionError(failure);
        }
      });
      if (finalization.get() != null) {
        remaining = deadline - System.nanoTime();
        assertTrue(remaining > 0, "SGF finalization did not settle");
        finalization.get().get(remaining, TimeUnit.NANOSECONDS);
      }
    } while (!idle.get() || finalization.get() != null);
  }

  public static final class BlockedRead implements AutoCloseable {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicInteger reads = new AtomicInteger();
    private final Field field;
    private final SgfReadQueue.Reader original;

    private BlockedRead() throws Exception {
      field = SgfReadQueue.class.getDeclaredField("reader");
      field.setAccessible(true);
      original = (SgfReadQueue.Reader) field.get(SgfReadQueue.SHARED);
      field.set(SgfReadQueue.SHARED, (SgfReadQueue.Reader) path -> {
        assertFalse(SwingUtilities.isEventDispatchThread());
        reads.incrementAndGet();
        started.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Read was not released");
          return original.read(path);
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IOException(failure);
        } finally {
          finished.countDown();
        }
      });
    }

    public void awaitStarted() throws Exception { assertTrue(started.await(10, TimeUnit.SECONDS)); }
    public void release() { release.countDown(); }
    public int reads() { return reads.get(); }

    @Override public void close() throws Exception {
      release();
      if (started.getCount() == 0) assertTrue(finished.await(10, TimeUnit.SECONDS));
      field.set(SgfReadQueue.SHARED, original);
      awaitPending();
    }
  }
}
