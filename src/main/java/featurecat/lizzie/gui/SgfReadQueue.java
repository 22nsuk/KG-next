package featurecat.lizzie.gui;

import featurecat.lizzie.util.SgfFileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;

/** At most one active read and one waiting selection. All request state belongs to the EDT. */
final class SgfReadQueue {
  @FunctionalInterface
  interface Reader {
    String read(Path path) throws IOException;
  }

  static final SgfReadQueue SHARED = new SgfReadQueue(
      Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lizzie-sgf-read");
        thread.setDaemon(true);
        return thread;
      }), SgfFileReader::read);

  private final Executor worker;
  private final Reader reader;
  private Request active;
  private Request waiting;

  private record Request(Path path, CompletableFuture<String> result) {}

  SgfReadQueue(Executor worker, Reader reader) {
    this.worker = worker;
    this.reader = reader;
  }

  CompletableFuture<String> read(Path path) {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("SGF read requests belong to the event thread");
    }
    Request request = new Request(path, new CompletableFuture<>());
    Request replaced = waiting;
    waiting = request;
    if (replaced != null) replaced.result().cancel(false);
    if (active != null) active.result().cancel(false);
    startNext();
    return request.result();
  }

  private void startNext() {
    if (active != null || waiting == null) return;
    Request request = waiting;
    waiting = null;
    active = request;
    try {
      worker.execute(() -> {
        String content = null;
        Throwable failure = null;
        try {
          content = reader.read(request.path());
        } catch (IOException | RuntimeException | Error caught) {
          failure = caught;
        }
        String value = content;
        Throwable error = failure;
        SwingUtilities.invokeLater(() -> finish(request, value, error));
      });
    } catch (RuntimeException rejected) {
      finish(request, null, rejected);
    }
  }

  private void finish(Request request, String content, Throwable failure) {
    // Keep the active slot until callbacks settle: a callback may enqueue another selection.
    try {
      if (failure == null) request.result().complete(content);
      else request.result().completeExceptionally(failure);
    } finally {
      active = null;
      startNext();
    }
  }
}
