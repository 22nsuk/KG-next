package featurecat.lizzie;

import static featurecat.lizzie.FileAccessTestSupport.denyParentWrite;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.theme.Theme;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigThemeResetTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "named theme: {0}")
  @ValueSource(booleans = {false, true})
  void failedColorResetPreservesSavedValuesAndRetriesAfterRepair(boolean namedTheme)
      throws Exception {
    String previousPathPrefix = Theme.pathPrefix;
    Path themeRoot = Files.createDirectory(directory.resolve("themes"));
    Path profile = Files.createDirectory(directory.resolve("profile"));
    Theme.pathPrefix = themeRoot.toString() + File.separator;
    try {
      Config config = ConfigTestHelper.createForTests(profile);
      JSONObject ui = customRules().put("theme", namedTheme ? "사용자 테마" : "");
      ui.put("unrelated-ui-setting", "keep UI value");
      config.uiConfig = ui;
      config.leelazConfig = new JSONObject().put("unrelated-engine-setting", "keep engine value");
      config.config =
          new JSONObject()
              .put("ui", ui)
              .put("leelaz", config.leelazConfig)
              .put("unrelated-root-setting", "keep root value");
      config.save();
      Path configFile = Path.of(config.getConfigFilePath());
      byte[] originalConfig = Files.readAllBytes(configFile);
      JSONObject originalUi = new JSONObject(ui.toString());

      Path themeFile = themeRoot.resolve("사용자 테마").resolve("theme.txt");
      byte[] originalTheme =
          (customRules().put("font-name", "보존할 글꼴").toString(2) + "\r\n")
              .getBytes(StandardCharsets.UTF_8);
      if (namedTheme) {
        Files.createDirectories(themeFile.getParent());
        Files.write(themeFile, originalTheme);
      }
      Path destination = namedTheme ? themeFile : configFile;

      try (AutoCloseable ignored = denyParentWrite(destination.getParent())) {
        assertThrows(
            IOException.class,
            () -> Files.createTempFile(destination.getParent(), "probe-", ".tmp"));

        assertThrows(IOException.class, config::resetBlunderColor);

        assertArrayEquals(originalConfig, Files.readAllBytes(configFile));
        assertTrue(originalUi.similar(config.uiConfig));
        if (namedTheme) assertArrayEquals(originalTheme, Files.readAllBytes(themeFile));
      }

      config.resetBlunderColor();

      if (namedTheme) {
        JSONObject savedTheme =
            new JSONObject(Files.readString(themeFile, StandardCharsets.UTF_8));
        assertDefaultRules(savedTheme);
        assertEquals("보존할 글꼴", savedTheme.getString("font-name"));
        assertArrayEquals(originalConfig, Files.readAllBytes(configFile));
        assertTrue(originalUi.similar(config.uiConfig));
      } else {
        JSONObject savedConfig =
            new JSONObject(Files.readString(configFile, StandardCharsets.UTF_8));
        assertDefaultRules(savedConfig.getJSONObject("ui"));
        assertDefaultRules(config.uiConfig);
        assertEquals(
            "keep UI value", savedConfig.getJSONObject("ui").getString("unrelated-ui-setting"));
        assertEquals(
            "keep engine value",
            savedConfig.getJSONObject("leelaz").getString("unrelated-engine-setting"));
        assertEquals("keep root value", savedConfig.getString("unrelated-root-setting"));
        assertFalse(Files.exists(themeRoot.resolve("theme.txt")));
      }
    } finally {
      Theme.pathPrefix = previousPathPrefix;
    }
  }

  private static JSONObject customRules() {
    return new JSONObject()
        .put("blunder-winrate-thresholds", new JSONArray("[-8,0,100]"))
        .put("blunder-node-colors", new JSONArray("[[1,2,3],[4,5,6],[7,8,9]]"));
  }

  private static void assertDefaultRules(JSONObject saved) {
    assertEquals(
        new JSONArray("[-24,-12,-6,-3,-1,3,100]").toList(),
        saved.getJSONArray("blunder-winrate-thresholds").toList());
    assertEquals(
        new JSONArray(
                "[[155,25,150],[208,16,19],[200,140,50],[180,180,0],[140,202,34],[0,220,0],[0,210,210]]")
            .toList(),
        saved.getJSONArray("blunder-node-colors").toList());
  }
}
