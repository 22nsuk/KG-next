package featurecat.lizzie.theme;

import static featurecat.lizzie.FileAccessTestSupport.denyParentWrite;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemeAtomicSaveTest {
  @TempDir Path directory;

  @Test
  void createsMissingParentsAndSavesUtf8WithBlankOverridesRemoved() throws Exception {
    Path themeDirectory = directory.resolve("새 테마").resolve("nested");
    Path target = themeDirectory.resolve("theme.txt");
    Theme theme = themeAt(themeDirectory);
    theme.config.put("font-name", "한글 글꼴 日本語");
    theme.config.put("background-image", "  ");
    theme.config.put("board-image", "");
    theme.config.put("nullable", JSONObject.NULL);
    theme.config.put("pure-board-color", new JSONArray("[217,152,77]"));
    assertFalse(Files.exists(themeDirectory));

    theme.save();

    JSONObject saved = new JSONObject(Files.readString(target, StandardCharsets.UTF_8));
    assertEquals("한글 글꼴 日本語", saved.getString("font-name"));
    assertEquals(List.of(217, 152, 77), saved.getJSONArray("pure-board-color").toList());
    assertFalse(saved.has("background-image"));
    assertFalse(saved.has("board-image"));
    assertTrue(saved.has("nullable"));
    assertTrue(saved.isNull("nullable"));
    assertTrue(saved.similar(theme.config));
    assertOnlyTargetExists(target);
  }

  @Test
  void replacesExistingThemeWithoutLeavingThePreviousTail() throws Exception {
    Path target = directory.resolve("theme.txt");
    Files.writeString(
        target,
        new JSONObject().put("old-setting", "previous value ".repeat(100)).toString(2),
        StandardCharsets.UTF_8);
    Theme theme = themeAt(directory);
    theme.config.put("font-name", "새 글꼴");
    byte[] expected = theme.config.toString(2).getBytes(StandardCharsets.UTF_8);

    theme.save();

    assertArrayEquals(expected, Files.readAllBytes(target));
    assertOnlyTargetExists(target);
  }

  @Test
  void parentWriteFailurePreservesFileAndEditsUntilRetry() throws Exception {
    Path target = directory.resolve("theme.txt");
    byte[] original = "{\"font-name\":\"기존 글꼴\"}\r\n".getBytes(StandardCharsets.UTF_8);
    Files.write(target, original);
    Theme theme = themeAt(directory);
    theme.config.put("font-name", "편집한 글꼴");
    theme.config.put("background-image", "  ");

    try (AutoCloseable ignored = denyParentWrite(directory)) {
      assertThrows(IOException.class, () -> Files.createTempFile(directory, "probe-", ".tmp"));

      IOException failure = assertThrows(IOException.class, theme::save);

      assertTrue(failure.getMessage().contains(target.toString()));
      assertInstanceOf(IOException.class, failure.getCause());
      assertArrayEquals(original, Files.readAllBytes(target));
      assertEquals("편집한 글꼴", theme.config.getString("font-name"));
      assertEquals("  ", theme.config.getString("background-image"));
      assertOnlyTargetExists(target);
    }

    theme.save();

    JSONObject saved = new JSONObject(Files.readString(target, StandardCharsets.UTF_8));
    assertEquals("편집한 글꼴", saved.getString("font-name"));
    assertFalse(saved.has("background-image"));
    assertOnlyTargetExists(target);
  }

  @Test
  void serializationFailurePreservesFileAndEditsUntilRetry() throws Exception {
    Path target = directory.resolve("theme.txt");
    byte[] original = "{\"font-name\":\"기존 글꼴\"}\r\n".getBytes(StandardCharsets.UTF_8);
    Files.write(target, original);
    Theme theme = themeAt(directory);
    JSONException serializationFailure = new JSONException("theme value cannot be serialized");
    JSONString invalidValue =
        () -> {
          throw serializationFailure;
        };
    theme.config.put("font-name", "편집한 글꼴");
    theme.config.put("background-image", "  ");
    theme.config.put("invalid-value", invalidValue);

    Exception failure = assertThrows(Exception.class, theme::save);

    assertArrayEquals(original, Files.readAllBytes(target));
    assertInstanceOf(IOException.class, failure);
    assertTrue(failure.getMessage().contains(target.toString()));
    assertTrue(hasCause(failure, serializationFailure));
    assertEquals("편집한 글꼴", theme.config.getString("font-name"));
    assertEquals("  ", theme.config.getString("background-image"));
    assertTrue(theme.config.has("invalid-value"));
    assertOnlyTargetExists(target);

    theme.config.remove("invalid-value");
    theme.save();

    JSONObject saved = new JSONObject(Files.readString(target, StandardCharsets.UTF_8));
    assertEquals("편집한 글꼴", saved.getString("font-name"));
    assertFalse(saved.has("background-image"));
    assertOnlyTargetExists(target);
  }

  @Test
  void fileBlockingParentDirectoryIsReportedAndCanBeRetried() throws Exception {
    Path blockedParent = directory.resolve("theme-parent");
    byte[] original = "Keep this existing file.".getBytes(StandardCharsets.UTF_8);
    Files.write(blockedParent, original);
    Path themeDirectory = blockedParent.resolve("nested");
    Path target = themeDirectory.resolve("theme.txt");
    Theme theme = themeAt(themeDirectory);
    theme.config.put("font-name", "편집한 글꼴");

    IOException failure = assertThrows(IOException.class, theme::save);

    assertTrue(failure.getMessage().contains(target.toString()));
    assertInstanceOf(IOException.class, failure.getCause());
    assertArrayEquals(original, Files.readAllBytes(blockedParent));
    assertEquals("편집한 글꼴", theme.config.getString("font-name"));

    Files.delete(blockedParent);
    theme.save();

    JSONObject saved = new JSONObject(Files.readString(target, StandardCharsets.UTF_8));
    assertEquals("편집한 글꼴", saved.getString("font-name"));
    assertOnlyTargetExists(target);
  }

  private static Theme themeAt(Path themeDirectory) {
    Theme theme = new Theme();
    theme.path = themeDirectory.toString() + File.separator;
    return theme;
  }

  private static void assertOnlyTargetExists(Path target) throws IOException {
    try (var files = Files.list(target.getParent())) {
      assertEquals(List.of(target), files.toList());
    }
  }

  private static boolean hasCause(Throwable failure, Throwable expected) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current == expected) return true;
    }
    return false;
  }
}
