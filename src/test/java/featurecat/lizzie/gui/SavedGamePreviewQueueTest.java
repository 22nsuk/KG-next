package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("Exercises preview retry against the shared save publication boundary")
class SavedGamePreviewQueueTest {
  @Test
  void currentListAndLatestHoverShareOneActiveDecodeAndPublishOnlyOnEdt() throws Exception {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    List<String> reads = new ArrayList<>();
    List<String> published = new ArrayList<>();
    SavedGamePreviewQueue queue = new SavedGamePreviewQueue(worker::addLast, (path, size) -> {
      assertFalse(SwingUtilities.isEventDispatchThread());
      reads.add(path.toString());
      return new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    });
    SwingUtilities.invokeAndWait(() -> {
      thumbnail(queue, "first", published);
      thumbnail(queue, "second", published);
      for (int i = 0; i < 100; i++) {
        String name = "hover-" + i;
        queue.hover(Path.of(name), () -> true, image -> {
          assertTrue(SwingUtilities.isEventDispatchThread());
          assertEquals(600, image.getWidth());
          published.add(name);
        });
      }
      assertEquals(1, worker.size(), "Only one decode may be submitted to the worker");
    });
    runNext(worker);
    assertEquals(List.of("first"), published);
    runNext(worker);
    assertEquals(List.of("first", "hover-99"), published);
    SwingUtilities.invokeAndWait(() -> {
      queue.clear(); // Retire the already-submitted second thumbnail and the old panel.
      thumbnail(queue, "new-panel", published);
    });
    runNext(worker);
    assertEquals(List.of("first", "hover-99"), published);
    runNext(worker);
    assertEquals(List.of("first", "hover-99", "new-panel"), published);
    assertEquals(List.of("first", "hover-99", "second", "new-panel"), reads);
    assertTrue(worker.isEmpty());
  }

  @Test
  void saveAcceptedDuringDecodeRetriesInsteadOfPublishingOldImageOrStrandingLabel() throws Exception {
    SgfSaveTestSupport.awaitPending();
    AtomicInteger reads = new AtomicInteger();
    CompletableFuture<BufferedImage> published = new CompletableFuture<>();
    SavedGamePreviewQueue queue = new SavedGamePreviewQueue(Runnable::run, (path, size) -> {
      // This worker is driven on the EDT only to deterministically accept a save inside
      // decoding. Production worker placement is checked by the real-window blocked read.
      int attempt = reads.incrementAndGet();
      if (attempt == 1) {
        SgfSaveCoordinator.submitSave(() -> null, () -> true, ignored -> {}, failure -> fail(failure));
      }
      BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
      image.setRGB(0, 0, attempt);
      return image;
    });
    SwingUtilities.invokeAndWait(() -> queue.thumbnail(Path.of("record"), () -> true, image -> {
      assertTrue(SwingUtilities.isEventDispatchThread());
      published.complete(image);
    }));
    assertEquals(2, published.get(5, TimeUnit.SECONDS).getRGB(0, 0) & 0xffffff);
    assertEquals(2, reads.get());
    SgfSaveTestSupport.awaitPending();
  }

  @Test
  void failedImageDoesNotBlockNextThumbnailAndCancelledHoverCannotPublish() throws Exception {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    List<String> published = new ArrayList<>();
    SavedGamePreviewQueue queue = new SavedGamePreviewQueue(worker::addLast, (path, size) -> {
      if (path.toString().equals("broken")) throw new java.awt.image.ImagingOpException("broken");
      return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    });
    SwingUtilities.invokeAndWait(() -> {
      queue.thumbnail(Path.of("broken"), () -> true, image -> {
        assertNull(image);
        published.add("unavailable");
      });
      thumbnail(queue, "healthy", published);
    });
    runNext(worker);
    SwingUtilities.invokeAndWait(() -> {
      queue.hover(Path.of("dismissed"), () -> true, image -> fail("Cancelled hover published"));
      queue.cancelHover();
    });
    runNext(worker);
    assertEquals(List.of("unavailable", "healthy"), published);
    assertTrue(worker.isEmpty());
  }

  private static void thumbnail(SavedGamePreviewQueue queue, String name, List<String> published) {
    queue.thumbnail(Path.of(name), () -> true, image -> {
      assertTrue(SwingUtilities.isEventDispatchThread());
      assertNotNull(image);
      published.add(name);
    });
  }

  private static void runNext(ArrayDeque<Runnable> worker) throws Exception {
    Runnable[] task = new Runnable[1];
    SwingUtilities.invokeAndWait(() -> task[0] = worker.removeFirst());
    task[0].run();
    SwingUtilities.invokeAndWait(() -> {});
  }
}
