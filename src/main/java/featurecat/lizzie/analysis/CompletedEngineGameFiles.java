package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.util.AtomicSgfFileWriter;
import featurecat.lizzie.util.AtomicTextFileWriter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes detached completed-game payloads; never reads a board, engine, or Swing component. */
final class CompletedEngineGameFiles {
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.ENGINE);
  static final Executor WORKER =
      new ThreadPoolExecutor(
          1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
          task -> {
            Thread thread = new Thread(task, "lizzie-engine-game-save");
            thread.setDaemon(true);
            return thread;
          });

  @FunctionalInterface
  interface Writer {
    void write(Path path, String text, boolean sgf) throws IOException;
  }

  static final Writer ATOMIC_WRITER =
      (path, text, sgf) -> {
        Files.createDirectories(path.getParent());
        if (sgf) AtomicSgfFileWriter.write(path, text);
        else AtomicTextFileWriter.write(path, text);
      };

  // The image is an exclusively owned pixel copy captured on the EDT, never a live UI cache.
  record Request(
      Path directory, String primaryFolder, String fallbackFolder,
      String sgfName, String sgf, String summaryName, String summary,
      Path savedSgf, Path savedSummary, BufferedImage image) {
    boolean hasWrites() {
      return (sgf != null && savedSgf == null) || (summary != null && savedSummary == null);
    }
  }

  /** Includes successful writes even when a later file fails, so retries never replay commits. */
  record Result(Path sgf, Path summary, Path lastTarget, Exception failure) {}

  private CompletedEngineGameFiles() {}

  static Result write(Request request, Writer writer) {
    Path sgf = request.savedSgf();
    Path summary = request.savedSummary();
    Path[] target = {request.directory()};
    try {
      if (request.sgf() != null && sgf == null) {
        sgf = write(request, request.sgfName(), request.sgf(), true, writer, target);
        if (request.image() != null) {
          Path image = sgf.resolveSibling(sgf.getFileName().toString().replaceFirst("\\.sgf$", ".png"));
          try {
            if (!ImageIO.write(request.image(), "png", image.toFile())) {
              throw new IOException("No PNG writer is available");
            }
          } catch (IOException | RuntimeException failure) {
            // The graph is best effort; an image failure cannot undo a committed SGF.
            LOG.warn("Engine-game graph image could not be saved", failure);
          }
        }
      }
      if (request.summary() != null && summary == null) {
        summary = write(request, request.summaryName(), request.summary(), false, writer, target);
      }
      return new Result(sgf, summary, target[0], null);
    } catch (IOException | RuntimeException failure) {
      return new Result(sgf, summary, target[0], failure);
    }
  }

  private static Path write(
      Request request, String name, String text, boolean sgf, Writer writer, Path[] target)
      throws IOException {
    try {
      return write(request.directory(), request.primaryFolder(), name, text, sgf, writer, target);
    } catch (IOException | RuntimeException primaryFailure) {
      if (request.primaryFolder().equals(request.fallbackFolder())) throw primaryFailure;
      try {
        return write(request.directory(), request.fallbackFolder(), name, text, sgf, writer, target);
      } catch (IOException | RuntimeException fallbackFailure) {
        fallbackFailure.addSuppressed(primaryFailure);
        throw fallbackFailure;
      }
    }
  }

  private static Path write(
      Path directory, String folder, String name, String text, boolean sgf, Writer writer,
      Path[] target) throws IOException {
    target[0] = Path.of(directory.toString(), folder, name);
    writer.write(target[0], text, sgf);
    return target[0];
  }
}
