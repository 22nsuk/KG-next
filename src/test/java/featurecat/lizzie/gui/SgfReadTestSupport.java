package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** Blocks the real read worker, retaining the production read, ownership and parser path. */
public final class SgfReadTestSupport {
  private SgfReadTestSupport() {}

  public static BlockedRead block() throws Exception { return new BlockedRead(); }

  public static final class BlockedRead implements AutoCloseable {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Field field;
    private final SgfReadQueue.Reader original;

    private BlockedRead() throws Exception {
      field = SgfReadQueue.class.getDeclaredField("reader");
      field.setAccessible(true);
      original = (SgfReadQueue.Reader) field.get(SgfReadQueue.SHARED);
      field.set(SgfReadQueue.SHARED, (SgfReadQueue.Reader) path -> {
        assertFalse(SwingUtilities.isEventDispatchThread());
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

    @Override public void close() throws Exception {
      release();
      if (started.getCount() == 0) assertTrue(finished.await(10, TimeUnit.SECONDS));
      field.set(SgfReadQueue.SHARED, original);
      SwingUtilities.invokeAndWait(() -> {});
    }
  }
}
