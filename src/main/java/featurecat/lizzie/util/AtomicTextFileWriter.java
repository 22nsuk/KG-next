package featurecat.lizzie.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Writes captured UTF-8 text without exposing a truncated destination. */
public final class AtomicTextFileWriter {
  private AtomicTextFileWriter() {}

  @FunctionalInterface
  interface Replacement {
    void replace(Path staged, Path target) throws IOException;
  }

  public static void write(Path target, String text) throws IOException {
    write(target, text, AtomicTextFileWriter::replaceAtomically);
  }

  static void write(Path target, String text, Replacement replacement) throws IOException {
    Path destination = target.toAbsolutePath().normalize();
    Path staged = null;
    try {
      staged = Files.createTempFile(destination.getParent(), ".lizzie-text-", ".tmp");
      try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      replacement.replace(staged, destination);
    } finally {
      if (staged != null) Files.deleteIfExists(staged);
    }
  }

  static void replaceAtomically(Path staged, Path destination) throws IOException {
    // Do not fall back to a delete/copy or non-atomic replace: a failed save must retain
    // the original. Unsupported filesystems report a normal save failure instead.
    Files.move(
        staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }
}
