package featurecat.lizzie.util;

import featurecat.lizzie.logging.SgfPhase;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.mozilla.universalchardet.UniversalDetector;

/** File bytes and decoding only; safe to use without the application's board/engine context. */
public final class SgfFileReader {
  private SgfFileReader() {}

  public static String read(Path path) throws IOException {
    byte[] bytes;
    try (var phase = new SgfPhase("file-read")) {
      bytes = Files.readAllBytes(path);
    }
    try (var phase = new SgfPhase("detect-decode")) {
      UniversalDetector detector = new UniversalDetector(null);
      for (int offset = 0; offset < bytes.length && !detector.isDone(); offset += 4096) {
        detector.handleData(bytes, offset, Math.min(4096, bytes.length - offset));
      }
      detector.dataEnd();
      String encoding = detector.getDetectedCharset();
      if (encoding == null) encoding = "UTF-8";
      // Keep the file loader's established Chinese legacy-encoding fallback.
      if (encoding.equals("WINDOWS-1252")) encoding = "GB18030";
      return new String(bytes, Charset.forName(encoding));
    }
  }
}
