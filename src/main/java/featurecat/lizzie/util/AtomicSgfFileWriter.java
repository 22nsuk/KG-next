package featurecat.lizzie.util;

import featurecat.lizzie.logging.SgfObservation;
import java.io.IOException;
import java.nio.file.Path;

/** Writes a captured SGF without exposing a truncated destination, even when replacement fails. */
public final class AtomicSgfFileWriter {
  private AtomicSgfFileWriter() {}

  @FunctionalInterface
  interface Replacement {
    void replace(Path staged, Path target) throws IOException;
  }

  public static void write(Path target, String sgf) throws IOException {
    write(target, sgf, AtomicTextFileWriter::replaceAtomically);
  }

  static void write(Path target, String sgf, Replacement replacement) throws IOException {
    Path destination = target.toAbsolutePath().normalize();
    try {
      AtomicTextFileWriter.write(destination, sgf, replacement::replace);
      SgfObservation.record("save", "ok", destination.toString(), null);
    } catch (IOException | RuntimeException failure) {
      SgfObservation.record("save", "failed", destination.toString(), failure);
      throw failure;
    }
  }
}
