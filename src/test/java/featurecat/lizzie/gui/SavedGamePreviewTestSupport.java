package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.ImagingOpException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/** Controls the real image decoder while retaining list/popup listeners and EDT publication. */
public final class SavedGamePreviewTestSupport {
  private SavedGamePreviewTestSupport() {}

  public static BlockedRead block(LizzieFrame frame) throws Exception {
    return new BlockedRead(frame, false);
  }

  public static BlockedRead failAfterDecode(LizzieFrame frame) throws Exception {
    return new BlockedRead(frame, true);
  }

  public static void clear(LizzieFrame frame) throws Exception {
    onEdt(() -> {
      Method method = LizzieFrame.class.getDeclaredMethod("clearSavedGamePreviews");
      method.setAccessible(true);
      method.invoke(frame);
      return null;
    });
    awaitPending(frame);
  }

  public static void awaitPending(LizzieFrame frame) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!onEdt(() -> {
      SavedGamePreviewQueue queue = queue(frame);
      return value(queue, "active") == null && value(queue, "hover") == null
          && ((Collection<?>) value(queue, "thumbnails")).isEmpty();
    })) {
      assertTrue(System.nanoTime() < deadline, "Saved-game images did not settle");
      Thread.sleep(10);
    }
  }

  private static SavedGamePreviewQueue queue(LizzieFrame frame) throws Exception {
    Method method = LizzieFrame.class.getDeclaredMethod("savedGamePreviews");
    method.setAccessible(true);
    return (SavedGamePreviewQueue) method.invoke(frame);
  }

  private static Object value(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    assertFalse(SwingUtilities.isEventDispatchThread());
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(5, TimeUnit.SECONDS);
  }

  public static final class BlockedRead implements AutoCloseable {
    private final LizzieFrame frame;
    private final SavedGamePreviewQueue queue;
    private final SavedGamePreviewQueue.Reader original;
    private final Field reader;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean first = new AtomicBoolean(true);
    private final AtomicInteger reads = new AtomicInteger();

    private BlockedRead(LizzieFrame frame, boolean fail) throws Exception {
      this.frame = frame;
      awaitPending(frame);
      queue = onEdt(() -> queue(frame));
      reader = SavedGamePreviewQueue.class.getDeclaredField("reader");
      reader.setAccessible(true);
      original = (SavedGamePreviewQueue.Reader) reader.get(queue);
      reader.set(queue, (SavedGamePreviewQueue.Reader) (path, size) -> {
        assertFalse(SwingUtilities.isEventDispatchThread(), "Image I/O must not occupy the EDT");
        reads.incrementAndGet();
        var image = original.read(path, size);
        if (first.compareAndSet(true, false)) {
          started.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Preview was not released");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
          }
        }
        if (fail) throw new ImagingOpException("Controlled preview processing failure");
        return image;
      });
    }

    public void awaitStarted() throws Exception { assertTrue(started.await(5, TimeUnit.SECONDS)); }
    public void release() { release.countDown(); }
    public int reads() { return reads.get(); }

    @Override public void close() throws Exception {
      release();
      try {
        awaitPending(frame);
      } finally {
        reader.set(queue, original);
      }
    }
  }
}
