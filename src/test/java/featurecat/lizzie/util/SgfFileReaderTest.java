package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SgfFileReaderTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "UTF-16", "GB18030", "windows-1252", "EUC-KR"})
  void preservesExistingCharsetDetectionAndReplacementBehavior(String encoding) throws Exception {
    String sgf = "(;CA[" + encoding + "]SZ[19]C["
        + "한글 中文 café a\\]b\\\\c\n".repeat(1000) + "];B[aa])";
    Path file = directory.resolve("기보.sgf");
    Files.write(file, sgf.getBytes(Charset.forName(encoding)));
    assertEquals(legacyRead(file), SgfFileReader.read(file));
    if (encoding.equals("UTF-8")) assertEquals(sgf, SgfFileReader.read(file));
  }

  @Test
  void emptyAndMalformedUtf8RetainReaderSemanticsAndMissingFileFails() throws Exception {
    Path file = directory.resolve("test.sgf");
    Files.write(file, new byte[0]);
    assertEquals("", SgfFileReader.read(file));
    Files.write(file, new byte[] {'(', ';', 'C', '[', (byte) 0xEF, (byte) 0xBB, (byte) 0xBF,
        (byte) 0xE2, (byte) 0x82, ']', ')'});
    assertEquals(legacyRead(file), SgfFileReader.read(file));
    assertThrows(IOException.class, () -> SgfFileReader.read(directory.resolve("missing.sgf")));
  }

  private static String legacyRead(Path path) throws IOException {
    String encoding = EncodingDetector.detect(path.toString());
    try (var input = new FileInputStream(path.toFile());
        var reader = new InputStreamReader(input,
            encoding.equals("WINDOWS-1252") ? "GB18030" : encoding)) {
      StringBuilder result = new StringBuilder();
      while (reader.ready()) result.append((char) reader.read());
      return result.toString();
    }
  }
}
