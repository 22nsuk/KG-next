package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataGoSetupPreferencesTest {
  private static final List<String> PATH_KEYS =
      List.of(
          "katago-auto-setup-engine-path",
          "katago-auto-setup-gtp-config-path",
          "katago-auto-setup-analysis-config-path",
          "katago-auto-setup-weight-path");

  @TempDir Path directory;
  private Config previousConfig;
  private Config config;

  @BeforeEach
  void initializeConfig() throws IOException {
    previousConfig = Lizzie.config;
    Files.writeString(directory.resolve("PROJECT_INFO.txt"), "test setup");
    config = ConfigTestHelper.createForTests(directory);
    config.uiConfig = new JSONObject().put("unrelated-setting", "keep");
    config.leelazConfig = new JSONObject();
    config.config =
        new JSONObject().put("ui", config.uiConfig).put("leelaz", config.leelazConfig);
    for (String key : PATH_KEYS) config.uiConfig.put(key, "previous " + key);
    config.save();
    Lizzie.config = config;
  }

  @AfterEach
  void restoreConfig() {
    Lizzie.config = previousConfig;
  }

  @Test
  void selectedPathsAreSavedTogetherAndPreserveUnrelatedSettings() throws Exception {
    var selected = selectedFiles();

    KataGoAutoSetupHelper.rememberSelectedLocalKataGo(selected);

    JSONObject saved = readConfig().getJSONObject("ui");
    List<Path> paths =
        List.of(
            selected.enginePath,
            selected.gtpConfigPath,
            selected.analysisConfigPath,
            selected.activeWeightPath);
    for (int i = 0; i < PATH_KEYS.size(); i++) {
      String expected = paths.get(i).toAbsolutePath().normalize().toString();
      assertEquals(expected, saved.getString(PATH_KEYS.get(i)));
      assertEquals(expected, config.uiConfig.getString(PATH_KEYS.get(i)));
    }
    assertEquals("keep", saved.getString("unrelated-setting"));
    assertSame(config.uiConfig, config.config.getJSONObject("ui"));
  }

  @Test
  void missingSelectedPathsRemovePreviouslyRememberedValues() throws Exception {
    var selected = KataGoAutoSetupHelper.inspectSelectedLocalKataGo(null, null, null);

    KataGoAutoSetupHelper.rememberSelectedLocalKataGo(selected);

    JSONObject saved = readConfig().getJSONObject("ui");
    for (String key : PATH_KEYS) {
      assertFalse(config.uiConfig.has(key), key);
      assertFalse(saved.has(key), key);
    }
    assertEquals("keep", saved.getString("unrelated-setting"));
  }

  @Test
  void failedPathSavePreservesPreviousRootSectionsAndSavedSettings() throws Exception {
    var selected = selectedFiles();
    JSONObject previousRoot = config.config;
    JSONObject previousUi = config.uiConfig;
    JSONObject previousLeelaz = config.leelazConfig;
    String previous = previousRoot.toString();
    Path preserved = blockConfigReplacement();

    assertThrows(IOException.class, () -> KataGoAutoSetupHelper.rememberSelectedLocalKataGo(selected));

    assertSame(previousRoot, config.config);
    assertSame(previousUi, config.uiConfig);
    assertSame(previousLeelaz, config.leelazConfig);
    assertEquals(previous, config.config.toString());
    assertTrue(new JSONObject(Files.readString(preserved)).similar(previousRoot));
    assertEquals("keep", Files.readString(Path.of(config.getConfigFilePath()).resolve("keep")));
  }

  @Test
  void failedQuickModelToggleDoesNotPublishRuntimeOrJsonChanges() throws Exception {
    config.quickAnalysisLightweightModelEnabled = true;
    config.uiConfig.put("quick-analysis-lightweight-model-enabled", true);
    config.save();
    JSONObject previousRoot = config.config;
    Path preserved = blockConfigReplacement();

    assertThrows(IOException.class, () -> KataGoAutoSetupHelper.setQuickAnalysisModelEnabled(false));

    assertTrue(config.quickAnalysisLightweightModelEnabled);
    assertSame(previousRoot, config.config);
    assertTrue(config.uiConfig.getBoolean("quick-analysis-lightweight-model-enabled"));
    assertTrue(new JSONObject(Files.readString(preserved)).similar(config.config));
  }

  private KataGoAutoSetupHelper.LocalKataGoDiscoveryResult selectedFiles() throws IOException {
    Path engine = Files.writeString(directory.resolve("selected-katago"), "");
    Path gtp = Files.writeString(directory.resolve("gtp.cfg"), "");
    Files.writeString(directory.resolve("analysis.cfg"), "");
    Path weight = Files.writeString(directory.resolve("selected.bin.gz"), "");
    return KataGoAutoSetupHelper.inspectSelectedLocalKataGo(engine, gtp, weight);
  }

  private JSONObject readConfig() throws IOException {
    return new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
  }

  private Path blockConfigReplacement() throws IOException {
    Path file = Path.of(config.getConfigFilePath());
    Path preserved = Files.move(file, directory.resolve("previous-config.txt"));
    Files.createDirectory(file);
    Files.writeString(file.resolve("keep"), "keep");
    return preserved;
  }
}
