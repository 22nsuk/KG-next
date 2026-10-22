package featurecat.lizzie;

import static featurecat.lizzie.FileAccessTestSupport.denyParentWrite;
import static featurecat.lizzie.FileAccessTestSupport.denyRead;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigRetiredShareAccountTest {
  private static final List<String> RETIRED_UI_KEYS =
      List.of(
          "up-load-user", "up-load-passwd", "share-label-1", "share-label-2", "share-label-3",
          "share-label-4", "share-label-5", "share-public", "share");
  @TempDir Path tempDir;

  @Test
  void startupRemovesRetiredSettingsFromDiskAndPreservesOtherUserSettings() throws Exception {
    Config initial = legacyProfile();
    Path main = Path.of(initial.getConfigFilePath());
    Path persist = Path.of(initial.getPersistFilePath());
    JSONObject before = read(main);
    JSONObject persistedBefore = read(persist);

    Config loaded = ConfigTestHelper.createBootstrapped(tempDir);

    JSONObject expected = new JSONObject(before.toString());
    for (String key : RETIRED_UI_KEYS) expected.getJSONObject("ui").remove(key);
    JSONObject expectedPersisted = new JSONObject(persistedBefore.toString());
    expectedPersisted.getJSONObject("ui-persist").remove("public-kifu-search");
    assertEquals(expected.toMap(), read(main).toMap(), "Only the retired main settings may change");
    assertTrue(expectedPersisted.similar(read(persist)), "Keep other persisted layout settings");
    for (String key : RETIRED_UI_KEYS) assertFalse(loaded.uiConfig.has(key), key);
    assertFalse(loaded.persistedUi.has("public-kifu-search"));
    assertFalse(Files.readString(main).contains("legacy-test-password"));
    assertEquals("preserved-fox-user", loaded.uiConfig.getString("last-fox-name"));
    assertEquals(0.35, loaded.persistedUi.getDouble("leftover-left-share"));

    byte[] mainAfter = Files.readAllBytes(main);
    byte[] persistAfter = Files.readAllBytes(persist);
    ConfigTestHelper.createBootstrapped(tempDir);
    assertArrayEquals(mainAfter, Files.readAllBytes(main), "Migration must be idempotent");
    assertArrayEquals(persistAfter, Files.readAllBytes(persist));
    try (var files = Files.walk(tempDir)) {
      assertFalse(files.anyMatch(p -> p.getFileName().toString().contains("unreadable-backup")),
          "Valid settings must not be copied to a credential-bearing recovery backup");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unreadableLegacySettingsAreNotResetOrReplaced(boolean persisted) throws Exception {
    Config initial = legacyProfile();
    Path target = Path.of(persisted ? initial.getPersistFilePath() : initial.getConfigFilePath());
    byte[] original = Files.readAllBytes(target);
    try (AutoCloseable ignored = denyRead(target)) {
      assertThrows(IOException.class, () -> Files.readAllBytes(target));
      assertThrows(IOException.class, () -> ConfigTestHelper.createBootstrapped(tempDir));
    }
    assertArrayEquals(original, Files.readAllBytes(target));
  }

  @Test
  void failedMigrationWritePreservesTheReadableSourceAndCanBeRetried() throws Exception {
    Config initial = legacyProfile();
    Path main = Path.of(initial.getConfigFilePath());
    Path persist = Path.of(initial.getPersistFilePath());
    // Migrate the persisted-only key first so this failure targets the credential file.
    JSONObject persisted = read(persist);
    persisted.getJSONObject("ui-persist").remove("public-kifu-search");
    Files.writeString(persist, persisted.toString(2));
    byte[] original = Files.readAllBytes(main);
    byte[] persistOriginal = Files.readAllBytes(persist);
    try (AutoCloseable ignored = denyParentWrite(main.getParent())) {
      assertThrows(IOException.class, () -> Files.createTempFile(main.getParent(), "probe", ".tmp"));
      assertThrows(IOException.class, () -> ConfigTestHelper.createBootstrapped(tempDir));
    }
    assertArrayEquals(original, Files.readAllBytes(main));
    assertArrayEquals(persistOriginal, Files.readAllBytes(persist));
    ConfigTestHelper.createBootstrapped(tempDir);
    assertFalse(read(main).getJSONObject("ui").has("up-load-passwd"));
  }

  @Test
  void jsonNullRetiredValuesAreRemovedWithoutRemovingUnrelatedNulls() throws Exception {
    Config initial = ConfigTestHelper.createBootstrapped(tempDir);
    Path main = Path.of(initial.getConfigFilePath());
    JSONObject root = read(main);
    for (String key : RETIRED_UI_KEYS) root.getJSONObject("ui").put(key, JSONObject.NULL);
    root.getJSONObject("ui").put("user-null-setting", JSONObject.NULL);
    Files.writeString(main, root.toString(2));
    Config loaded = ConfigTestHelper.createBootstrapped(tempDir);
    for (String key : RETIRED_UI_KEYS) assertFalse(loaded.uiConfig.has(key), key);
    assertTrue(read(main).getJSONObject("ui").has("user-null-setting"));
  }

  private Config legacyProfile() throws Exception {
    Config config = ConfigTestHelper.createBootstrapped(tempDir);
    config.save();
    Path main = Path.of(config.getConfigFilePath());
    JSONObject root = read(main);
    JSONObject ui = root.getJSONObject("ui");
    for (String key : RETIRED_UI_KEYS) ui.put(key, "legacy-" + key);
    ui.put("up-load-passwd", "legacy-test-password");
    ui.put("share", true);
    ui.put("share-public", false);
    ui.put("last-fox-name", "preserved-fox-user");
    ui.put("custom-user-setting", new JSONObject().put("items", new JSONArray().put("keep")));
    root.put("custom-section", new JSONObject().put("share", "not-a-service-setting"));
    root.getJSONObject("leelaz").put("user-engine-marker", "preserve-engine-settings");
    Files.writeString(main, root.toString(2));
    Path persist = Path.of(config.getPersistFilePath());
    JSONObject persisted = read(persist);
    persisted.getJSONObject("ui-persist")
        .put("public-kifu-search", new JSONArray().put(10).put(20).put(800).put(600))
        .put("leftover-left-share", 0.35);
    Files.writeString(persist, persisted.toString(2));
    return config;
  }

  private static JSONObject read(Path path) throws IOException {
    return new JSONObject(Files.readString(path));
  }
}
