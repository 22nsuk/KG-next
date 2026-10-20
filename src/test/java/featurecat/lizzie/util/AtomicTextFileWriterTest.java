package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AtomicTextFileWriterTest {
  @TempDir Path directory;

  @Test
  void replacesBatchResultWithExactUtf8BytesAndLineEndings() throws Exception {
    Path target = directory.resolve("대국 결과.txt");
    AtomicTextFileWriter.write(target, "previous batch result with a longer tail\n");
    String result = "흑 1승\r\n백 0승\n";

    AtomicTextFileWriter.write(target, result);

    assertArrayEquals(result.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
    assertOnlyDestinationExists(target);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void replacementFailureKeepsBatchResultAndRemovesStagedText(boolean unsupportedAtomicMove)
      throws Exception {
    Path target = directory.resolve("batch-result.txt");
    byte[] original = "previous results\r\n".getBytes(StandardCharsets.UTF_8);
    Files.write(target, original);
    String result = "새 대국 결과\r\n";
    IOException failure =
        unsupportedAtomicMove
            ? new AtomicMoveNotSupportedException("staged", target.toString(), "unsupported")
            : new AccessDeniedException(target.toString());

    IOException actual =
        assertThrows(
            IOException.class,
            () ->
                AtomicTextFileWriter.write(
                    target,
                    result,
                    (staged, destination) -> {
                      assertEquals(target, destination);
                      assertEquals(target.getParent(), staged.getParent());
                      assertArrayEquals(
                          result.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(staged));
                      assertArrayEquals(original, Files.readAllBytes(destination));
                      throw failure;
                    }));

    assertSame(failure, actual);
    assertArrayEquals(original, Files.readAllBytes(target));
    assertOnlyDestinationExists(target);
  }

  private void assertOnlyDestinationExists(Path target) throws IOException {
    try (var files = Files.list(directory)) {
      assertEquals(List.of(target), files.toList());
    }
  }
}
