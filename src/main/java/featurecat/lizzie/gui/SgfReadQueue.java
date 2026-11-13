package featurecat.lizzie.gui;

import featurecat.lizzie.rules.GIBParser;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.SgfDocument;
import featurecat.lizzie.util.SgfFileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;

/** SGF/GIB preparation: one active request and one latest waiting selection, owned by the EDT. */
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

  sealed interface PreparedFile {
    boolean apply(boolean showHint);
  }

  record PreparedSgf(
      SgfDocument document, SGFParser.ParseOptions options, SGFParser.PreparedGame game)
      implements PreparedFile {
    @Override public boolean apply(boolean showHint) {
      return SGFParser.loadPreparedFile(game, showHint, false);
    }
  }

  private record PreparedGib(GIBParser.PreparedGame game) implements PreparedFile {
    @Override public boolean apply(boolean showHint) {
      return GIBParser.loadPreparedGame(game);
    }
  }

  private record Request(Path path, CompletableFuture<PreparedFile> result) {}

  SgfReadQueue(Executor worker, Reader reader) {
    this.worker = worker;
    this.reader = reader;
  }

  CompletableFuture<PreparedFile> prepare(Path path) {
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
    SGFParser.ParseOptions options = SGFParser.ParseOptions.capture();
    try {
      worker.execute(() -> {
        PreparedFile prepared = null;
        Throwable failure = null;
        try {
          String content = reader.read(request.path());
          // Superseded reads keep the active slot but need not start parsing or building a tree.
          if (!request.result().isCancelled()) {
            if (request.path().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gib")) {
              var game = GIBParser.prepareFileContent(content);
              if (game != null) prepared = new PreparedGib(game);
            } else {
              SgfDocument document = SGFParser.prepareFileContent(content);
              if (document != null) prepared = prepareSgf(document, options);
            }
          }
        } catch (IOException | RuntimeException | Error caught) {
          failure = caught;
        }
        PreparedFile value = prepared;
        Throwable error = failure;
        SwingUtilities.invokeLater(() -> finish(request, value, error));
      });
    } catch (RuntimeException rejected) {
      finish(request, null, rejected);
    }
  }

  private static PreparedSgf prepareSgf(SgfDocument document, SGFParser.ParseOptions options) {
    return new PreparedSgf(document, options, SGFParser.prepareFileGame(document, options));
  }

  private void finish(Request request, PreparedFile prepared, Throwable failure) {
    if (failure == null && !request.result().isCancelled() && prepared instanceof PreparedSgf sgf) {
      SGFParser.ParseOptions current = SGFParser.ParseOptions.capture();
      if (!sgf.options().equals(current)) {
        // Import preferences used to be read on application. Rebuild from the same syntax so a
        // setting changed during preparation still takes effect without another file read.
        try {
          worker.execute(() -> {
            PreparedFile rebuilt = null;
            Throwable error = null;
            try {
              if (!request.result().isCancelled()) rebuilt = prepareSgf(sgf.document(), current);
            } catch (RuntimeException | Error caught) {
              error = caught;
            }
            PreparedFile value = rebuilt;
            Throwable caught = error;
            SwingUtilities.invokeLater(() -> finish(request, value, caught));
          });
        } catch (RuntimeException rejected) {
          finish(request, null, rejected);
        }
        return;
      }
      if (sgf.game() == null) prepared = null;
    }
    // Keep the active slot until callbacks settle: a callback may enqueue another selection.
    try {
      if (failure == null) request.result().complete(prepared);
      else request.result().completeExceptionally(failure);
    } finally {
      active = null;
      startNext();
    }
  }
}
