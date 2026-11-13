package featurecat.lizzie.gui;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** One decode through EDT publication, the current list's thumbnails, and one latest hover. */
final class SavedGamePreviewQueue {
  @FunctionalInterface
  interface Reader {
    BufferedImage read(Path path, int size) throws IOException;
  }

  private static final class Shared {
    static final Executor WORKER = Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "saved-game-preview");
      thread.setDaemon(true);
      return thread;
    });
  }

  private final Executor worker;
  private final Reader reader;
  private final ArrayDeque<Request> thumbnails = new ArrayDeque<>();
  private Request hover;
  private Request active;

  SavedGamePreviewQueue() {
    this(Shared.WORKER, SavedGamePreviewQueue::readImage);
  }

  SavedGamePreviewQueue(Executor worker, Reader reader) {
    this.worker = worker;
    this.reader = reader;
  }

  void thumbnail(Path path, BooleanSupplier current, Consumer<BufferedImage> publish) {
    requireEventThread();
    thumbnails.addLast(new Request(path, 300, current, publish));
    startNext();
  }

  void hover(Path path, BooleanSupplier current, Consumer<BufferedImage> publish) {
    cancelHover();
    hover = new Request(path, 600, current, publish);
    startNext();
  }

  void cancelHover() {
    requireEventThread();
    hover = null;
    if (active != null && active.size == 600) active.cancel();
  }

  void clear() {
    requireEventThread();
    thumbnails.clear();
    hover = null;
    if (active != null) active.cancel();
  }

  private void startNext() {
    if (active != null) return;
    do {
      active = hover != null ? hover : thumbnails.pollFirst();
      if (active == hover) hover = null;
      if (active == null) return;
    } while (!active.isCurrent());
    prepare(active);
  }

  private void prepare(Request request) {
    if (!request.isCurrent()) {
      finish(request, null, SgfSaveCoordinator.acceptedSaveRevision());
      return;
    }
    // Read a stable saved record, including its metadata publication. A failed save can
    // still have replaced its image; optional previews may retry the settled disk state.
    var saves = SgfSaveCoordinator.pendingSaves();
    if (!saves.isDone()) {
      saves.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> prepare(request)));
      return;
    }
    long revision = SgfSaveCoordinator.acceptedSaveRevision();
    try {
      worker.execute(() -> {
        BufferedImage image = null;
        try {
          image = reader.read(request.path, request.size);
        } catch (NoSuchFileException absent) {
          // A saved record does not require a preview file.
        } catch (IOException | RuntimeException failure) {
          org.slf4j.LoggerFactory.getLogger(SavedGamePreviewQueue.class)
              .warn("Could not read saved-game preview: {}", request.path, failure);
        }
        BufferedImage result = image;
        SwingUtilities.invokeLater(() -> finish(request, result, revision));
      });
    } catch (RuntimeException rejected) {
      org.slf4j.LoggerFactory.getLogger(SavedGamePreviewQueue.class)
          .warn("Could not schedule saved-game preview: {}", request.path, rejected);
      finish(request, null, revision);
    }
  }

  private void finish(Request request, BufferedImage image, long revision) {
    if (request.isCurrent() && revision != SgfSaveCoordinator.acceptedSaveRevision()) {
      // Do not strand a valid label if an unrelated save was accepted during decoding.
      prepare(request);
      return;
    }
    try {
      if (request.isCurrent()) request.publish.accept(image);
    } finally {
      active = null;
      startNext();
    }
  }

  static BufferedImage readImage(Path path, int size) throws IOException {
    try (var input = Files.newInputStream(path)) {
      BufferedImage image = ImageIO.read(input);
      if (image == null) throw new IOException("Unsupported saved-game preview image");
      return new AffineTransformOp(AffineTransform.getScaleInstance(
          size / (double) image.getWidth(), size / (double) image.getHeight()), null)
          .filter(image, null);
    }
  }

  private static void requireEventThread() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("Saved-game previews belong to the event thread");
    }
  }

  private static final class Request {
    final Path path;
    final int size;
    BooleanSupplier current;
    Consumer<BufferedImage> publish;

    Request(Path path, int size, BooleanSupplier current, Consumer<BufferedImage> publish) {
      this.path = path;
      this.size = size;
      this.current = current;
      this.publish = publish;
    }

    boolean isCurrent() { return current != null && current.getAsBoolean(); }

    void cancel() {
      current = null;
      publish = null;
    }
  }
}
