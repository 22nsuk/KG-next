package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.SGFParser;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

class SgfCopyDesktopTest {
  private static final String LATEST = "(;SZ[5]C[클릭 시점의 주석];B[aa](;W[bb])(;W[cc]))";

  @Test
  void copiesTheClickTimeSnapshotAndOnlyPublishesTheLatestRequest() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.runWithStartupTimeout(
        getClass(), "sgf-copy", List.of(), List.of(), 120, 90);
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) {
    try {
      runProbe(Path.of(args[0]), Path.of(args[1]));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void runProbe(Path work, Path result) throws Exception {
    Files.writeString(work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":3,"
            + "\"auto-save-exit\":false,\"resume-previous-game\":false,"
            + "\"auto-check-engine-alive\":false,\"auto-quick-analyze-on-load\":false,"
            + "\"analysis-engine-preload\":false,\"play-sound\":false}}");
    System.setProperty("lizzie.work.dir", work.toString());
    DesktopProbeProcess.phase(result, "application-starting");
    Lizzie.main(new String[0]);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    Field startup = field(LizzieFrame.class, "autoSaveStartupPrepared");
    while (!edt(() -> Lizzie.frame != null && Lizzie.frame.isShowing()
        && startup.getBoolean(Lizzie.frame))) {
      assertTrue(System.nanoTime() < deadline, "application did not start");
      Thread.sleep(10);
    }
    edt(() -> {
      for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
      Lizzie.setPrimaryEngine(null);
      Lizzie.config.loadSgfLast = true;
      assertTrue(SGFParser.loadFromString(LATEST, false));
      return null;
    });
    DesktopProbeProcess.applicationReady(result);
    String expected = edt(() -> SGFParser.saveToString(false));
    edt(() -> {
      assertTrue(SGFParser.loadFromString("(;SZ[5]C[older request];B[dd])", false));
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection("unchanged"), null);
      return null;
    });
    SgfSaveQueue queue = (SgfSaveQueue) field(LizzieFrame.class, "sgfCopies").get(null);
    Executor worker = (Executor) field(SgfSaveQueue.class, "worker").get(queue);
    long saveRevision = SgfSaveCoordinator.acceptedSaveRevision();
    Path jfr = result.resolveSibling("copy.jfr");
    DesktopProbeProcess.phase(result, "copy-ownership");
    try (var recording = new Recording(); var first = new WorkerBlock(worker)) {
      recording.enable("kg.sgf.Phase").withoutThreshold();
      recording.start();
      first.awaitStarted();
      edt(() -> { Lizzie.frame.copySgf(); return null; });
      // Ensure the first request has queued its serializer behind the first worker block.
      edt(() -> null);
      try (var second = new WorkerBlock(worker)) {
        edt(() -> {
          assertTrue(SGFParser.loadFromString(LATEST, false));
          Lizzie.frame.copySgf();
          Lizzie.board.getHistory().getStart().getData().setAuthoredComment("later edit");
          return null;
        });
        assertEquals("unchanged", clipboard());
        assertTrue(SgfSaveCoordinator.pendingSaves().isDone());
        assertEquals(saveRevision, SgfSaveCoordinator.acceptedSaveRevision());
        first.release();
        second.awaitStarted();
        edt(() -> null); // The older serialization has published or retired on the EDT.
        assertEquals("unchanged", clipboard(), "an older copy cannot publish after the newer click");
        second.release();
        queue.pending().get(20, TimeUnit.SECONDS);
      }
      assertEquals(expected, clipboard(), "later live edits cannot alter the clicked snapshot");
      recording.stop();
      recording.dump(jfr);
    }
    var serialized = RecordingFile.readAllEvents(jfr).stream()
        .filter(event -> event.getEventType().getName().equals("kg.sgf.Phase")
            && event.getString("phase").equals("save-serialize")).toList();
    assertEquals(2, serialized.size());
    for (var event : serialized) assertEquals("lizzie-sgf-copy", event.getThread().getJavaName());

    DesktopProbeProcess.phase(result, "copy-image-ownership");
    assertImageCopyWins(queue, worker, () -> Lizzie.frame.saveMainBoardToClipboard());
    assertImageCopyWins(queue, worker, () -> Lizzie.frame.copySubBoard());
    // A new SGF request after an image copy must still be allowed to publish.
    edt(() -> {
      assertTrue(SGFParser.loadFromString(LATEST, false));
      Lizzie.frame.copySgf();
      return null;
    });
    queue.pending().get(20, TimeUnit.SECONDS);
    assertEquals(expected, clipboard());

    DesktopProbeProcess.phase(result, "copy-window-close");
    try (var blocked = new WorkerBlock(worker)) {
      blocked.awaitStarted();
      edt(() -> {
        Lizzie.frame.copySgf();
        Lizzie.frame.dispose();
        return null;
      });
      blocked.release();
      queue.pending().get(20, TimeUnit.SECONDS);
      assertEquals(expected, clipboard(), "a closed frame cannot publish its pending copy");
    }
    Files.writeString(result, "PASS");
  }

  private static void assertImageCopyWins(SgfSaveQueue queue, Executor worker, Runnable copyImage)
      throws Exception {
    try (var blocked = new WorkerBlock(worker)) {
      blocked.awaitStarted();
      BufferedImage copied = edt(() -> {
        // A sentinel ensures a no-op image action cannot satisfy the assertions.
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection("before image"), null);
        Lizzie.frame.copySgf();
        copyImage.run();
        return clipboardImage();
      });
      blocked.release();
      queue.pending().get(20, TimeUnit.SECONDS);
      BufferedImage retained = edt(SgfCopyDesktopTest::clipboardImage);
      assertEquals(copied.getWidth(), retained.getWidth());
      assertEquals(copied.getHeight(), retained.getHeight());
      assertArrayEquals(
          copied.getRGB(0, 0, copied.getWidth(), copied.getHeight(), null, 0, copied.getWidth()),
          retained.getRGB(0, 0, retained.getWidth(), retained.getHeight(), null, 0, retained.getWidth()),
          "an older SGF copy cannot replace a later board image");
    }
  }

  private static BufferedImage clipboardImage() throws Exception {
    var contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
    assertTrue(contents.isDataFlavorSupported(DataFlavor.imageFlavor),
        "the later board image must remain on the clipboard");
    Image image = (Image) contents.getTransferData(DataFlavor.imageFlavor);
    BufferedImage snapshot = new BufferedImage(
        image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = snapshot.createGraphics();
    try { graphics.drawImage(image, 0, 0, null); }
    finally { graphics.dispose(); }
    return snapshot;
  }

  private static String clipboard() throws Exception {
    return edt(() -> (String) Toolkit.getDefaultToolkit().getSystemClipboard()
        .getData(DataFlavor.stringFlavor));
  }

  private static Field field(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(20, TimeUnit.SECONDS);
  }

  private static final class WorkerBlock implements AutoCloseable {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);

    WorkerBlock(Executor worker) {
      worker.execute(() -> {
        started.countDown();
        try {
          assertTrue(release.await(20, TimeUnit.SECONDS), "copy worker was not released");
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failure);
        } finally { finished.countDown(); }
      });
    }

    void awaitStarted() throws Exception { assertTrue(started.await(20, TimeUnit.SECONDS)); }
    void release() { release.countDown(); }
    @Override public void close() throws Exception {
      release();
      assertTrue(finished.await(20, TimeUnit.SECONDS));
    }
  }
}
