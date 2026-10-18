package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.util.BundledKataGoProfile;
import featurecat.lizzie.util.Utils;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigDualCudaTest {
  @TempDir Path temp;

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void fullWindowsBootstrapRegistersBothAndPreservesCuda13Selection() throws Exception {
    Path root = bundle("Windows startup");
    Path primary = root.resolve("engines/katago/windows-x64/katago.exe");
    Files.createDirectories(primary.getParent());
    Files.writeString(primary, "fixture");
    Files.writeString(root.resolve(".lizzie-portable"), "portable");
    String previous = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", root.toString());
      Config config = ConfigTestHelper.createBootstrapped(root);
      JSONArray engines = config.leelazConfig.getJSONArray("engine-settings-list");
      assertEquals(2, engines.length());
      assertTrue(BundledKataGoProfile.isManaged(engines.getJSONObject(0)));
      assertTrue(BundledKataGoProfile.isManagedCuda13(engines.getJSONObject(1)));
      assertEquals(0, config.uiConfig.getInt("default-engine"));
      String cuda13Command = engines.getJSONObject(1).getString("command");
      config.uiConfig.put("default-engine", 1).put("last-engine", 1);
      engines.getJSONObject(0).put("isDefault", false);
      engines.getJSONObject(1).put("isDefault", true);
      config.save();
      Config restored = ConfigTestHelper.createBootstrapped(root);
      JSONArray after = restored.leelazConfig.getJSONArray("engine-settings-list");
      assertEquals(2, after.length());
      assertEquals(1, restored.uiConfig.getInt("default-engine"));
      assertEquals(cuda13Command, after.getJSONObject(1).getString("command"));
      assertTrue(after.getJSONObject(1).getBoolean("isDefault"));
    } finally {
      System.setProperty("user.dir", previous);
    }
  }

  @Test
  void optionalEngineIsIndependentIdempotentAndRebindsAfterMoving() throws Exception {
    Path first = bundle("original 한글");
    JSONArray engines = new JSONArray();
    register(first, engines);
    assertEquals(1, engines.length());
    JSONObject cuda13 = engines.getJSONObject(0);
    String id = cuda13.getString("id");
    String firstCommand = cuda13.getString("command");
    assertTrue(firstCommand.contains("windows-x64-nvidia-cuda13"));
    assertFalse(cuda13.getBoolean("preload"));
    assertFalse(cuda13.getBoolean("isDefault"));
    assertTrue(BundledKataGoProfile.isManagedCuda13(cuda13));
    assertFalse(BundledKataGoProfile.isManaged(cuda13));
    assertFalse(BundledKataGoProfile.canMigrate(cuda13, first));
    cuda13.put("name", "My CUDA13").put("isDefault", true).put("komi", 6.5);
    register(first, engines);
    assertEquals(1, engines.length());
    assertEquals(firstCommand, cuda13.getString("command"));
    Path moved = bundle("moved 폴더");
    engines = new JSONArray(engines.toString());
    register(moved, engines);
    JSONObject repaired = engines.getJSONObject(0);
    assertEquals(id, repaired.getString("id"));
    assertEquals("My CUDA13", repaired.getString("name"));
    assertTrue(repaired.getBoolean("isDefault"));
    assertEquals(6.5, repaired.getDouble("komi"));
    assertFalse(repaired.getString("command").contains(first.toString()));
    assertTrue(repaired.getString("command").contains(moved.resolve("weights/default.bin.gz").toString()));
    assertTrue(BundledKataGoProfile.isManagedCuda13(repaired));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void everyOptionalWeightProfileKeepsItsModelAndSettingsAfterRelocation(boolean removeOldEngine)
      throws Exception {
    Path previous = bundle("previous CUDA13 한글");
    Path current = bundle("current CUDA13 한글");
    String[] models = {"explicit-legacy.bin.gz", "my-study.bin.gz"};
    JSONArray engines = new JSONArray();
    register(previous, engines);
    JSONObject template = new JSONObject(engines.getJSONObject(0).toString());
    engines.remove(0);
    for (int i = 0; i < models.length; i++) {
      for (Path root : new Path[] {previous, current}) {
        Files.writeString(root.resolve("weights").resolve(models[i]), "selected model");
      }
      JSONObject entry = new JSONObject(template.toString())
          .put("id", "cuda13-model-" + i)
          .put("name", "My CUDA13 model " + i)
          .put("isDefault", i == 1)
          .put("preload", true)
          .put("komi", 6.5)
          .put("command", template.getString("command").replace("default.bin.gz", models[i]));
      BundledKataGoProfile.claim(entry);
      engines.put(entry);
    }
    JSONArray before = new JSONArray(engines.toString());
    if (removeOldEngine) {
      Files.delete(previous.resolve("engines/katago/windows-x64-nvidia-cuda13/katago.exe"));
    }
    for (int restart = 0; restart < 3; restart++) {
      register(current, engines);
      assertEquals(2, engines.length());
      for (int i = 0; i < engines.length(); i++) {
        JSONObject expected = new JSONObject(before.getJSONObject(i).toString());
        expected.put("command", expected.getString("command")
            .replace(previous.toString(), current.toString()));
        BundledKataGoProfile.claim(expected);
        assertTrue(expected.similar(engines.getJSONObject(i)));
        assertTrue(BundledKataGoProfile.isManagedCuda13(engines.getJSONObject(i)));
        assertFalse(BundledKataGoProfile.isManaged(engines.getJSONObject(i)));
      }
      engines = new JSONArray(engines.toString());
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void missingSelectedModelFallsBackOnlyWhenNeitherLocationHasIt(
      boolean oldModelExists, boolean relocatedModelExists) throws Exception {
    Path previous = bundle("previous model 한글");
    Path current = bundle("current model 한글");
    Path oldModel = previous.resolve("weights/selected model.bin.gz");
    Path relocatedModel = current.resolve("weights/selected model.bin.gz");
    if (oldModelExists) Files.writeString(oldModel, "selected model");
    if (relocatedModelExists) Files.writeString(relocatedModel, "selected model");
    Files.writeString(previous.resolve("engines/katago/configs/study.cfg"), "study config");
    Files.writeString(current.resolve("engines/katago/configs/study.cfg"), "study config");
    JSONArray engines = new JSONArray();
    register(previous, engines);
    JSONObject selected = engines.getJSONObject(0);
    selected.put("name", "My selected CUDA13").put("isDefault", true)
        .put("preload", true).put("komi", 6.5).put("width", 13).put("height", 9);
    selected.put("command", selected.getString("command")
        .replace("default.bin.gz", "selected model.bin.gz").replace("gtp.cfg", "study.cfg"));
    BundledKataGoProfile.claim(selected);
    JSONObject expected = new JSONObject(selected.toString());
    Path expectedModel = relocatedModelExists ? relocatedModel
        : oldModelExists ? oldModel : current.resolve("weights/default.bin.gz");
    expected.put("command", selected.getString("command")
        .replace(previous.toString(), current.toString())
        .replace(relocatedModel.toString(), expectedModel.toString()));
    BundledKataGoProfile.claim(expected);
    Files.delete(previous.resolve("engines/katago/windows-x64-nvidia-cuda13/katago.exe"));
    Path persisted = current.resolve("save/engines.json");
    for (int restart = 0; restart < 3; restart++) {
      register(current, engines);
      assertEquals(1, engines.length());
      assertTrue(expected.similar(engines.getJSONObject(0)), engines.toString());
      assertTrue(BundledKataGoProfile.isManagedCuda13(engines.getJSONObject(0)));
      assertTrue(Files.isRegularFile(expectedModel));
      Files.writeString(persisted, engines.toString());
      engines = new JSONArray(Files.readString(persisted));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @EnabledOnOs(OS.WINDOWS)
  void missingCuda13ModelIsRepairedAcrossSavedStartupModes(boolean startLast) throws Exception {
    Path root = bundle("missing CUDA13 startup");
    Path primary = root.resolve("engines/katago/windows-x64/katago.exe");
    Files.createDirectories(primary.getParent());
    Files.writeString(primary, "fixture");
    Files.writeString(root.resolve(".lizzie-portable"), "portable");
    String previous = System.getProperty("user.dir");
    Config previousConfig = Lizzie.config;
    try {
      System.setProperty("user.dir", root.toString());
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      JSONArray engines = Lizzie.config.leelazConfig.getJSONArray("engine-settings-list");
      JSONObject cuda13 = engines.getJSONObject(1);
      cuda13.put("name", "My CUDA13").put("preload", true).put("komi", 6.5);
      engines.getJSONObject(0).put("isDefault", startLast);
      cuda13.put("isDefault", !startLast);
      String expectedCommand = cuda13.getString("command");
      cuda13.put("command", expectedCommand.replace("default.bin.gz", "deleted.bin.gz"));
      BundledKataGoProfile.claim(cuda13);
      JSONObject expected = new JSONObject(cuda13.toString()).put("command", expectedCommand);
      BundledKataGoProfile.claim(expected);
      Lizzie.config.uiConfig.put("default-engine", startLast ? 0 : 1).put("last-engine", 1)
          .put("autoload-default", !startLast).put("autoload-last", startLast)
          .put("autoload-empty", false);
      Lizzie.config.save();
      for (int restart = 0; restart < 3; restart++) {
        Lizzie.config = ConfigTestHelper.createBootstrapped(root);
        Utils.applyMaintainedDefaultSettings();
        JSONArray restored = Lizzie.config.leelazConfig.getJSONArray("engine-settings-list");
        assertEquals(2, restored.length());
        assertTrue(expected.similar(restored.getJSONObject(1)), restored.toString());
        assertEquals(startLast ? 0 : 1, Lizzie.config.uiConfig.getInt("default-engine"));
        assertEquals(1, Lizzie.config.uiConfig.getInt("last-engine"));
        assertEquals(startLast, Lizzie.config.uiConfig.getBoolean("autoload-last"));
        assertEquals(!startLast, Lizzie.config.uiConfig.getBoolean("autoload-default"));
        Lizzie.config.save();
      }
    } finally {
      Lizzie.config = previousConfig;
      System.setProperty("user.dir", previous);
    }
  }

  @Test
  void editedOrRemoteEntriesArePreservedAndOnlyOneReplacementIsAdded() throws Exception {
    Path root = bundle("custom");
    JSONArray engines = new JSONArray();
    register(root, engines);
    JSONObject edited = engines.getJSONObject(0);
    String custom = edited.getString("command").replace("default.bin.gz", "missing.bin.gz")
        + " -override-config numSearchThreads=8";
    edited.put("command", custom);
    register(root, engines);
    register(root, engines);
    assertEquals(2, engines.length());
    assertEquals(custom, edited.getString("command"));
    assertFalse(BundledKataGoProfile.isManagedCuda13(edited));
    JSONObject remote = engines.getJSONObject(1).put("useJavaSSH", true);
    remote.put("command", remote.getString("command").replace("default.bin.gz", "missing.bin.gz"));
    String remoteCommand = remote.getString("command");
    register(root, engines);
    register(root, engines);
    assertEquals(3, engines.length());
    assertEquals(remoteCommand, remote.getString("command"));
    assertTrue(remote.getBoolean("useJavaSSH"));
  }

  @Test
  void missingOptionalBinaryDoesNotCreateEntryOrRewriteExistingChoice() throws Exception {
    Path root = bundle("missing");
    JSONArray engines = new JSONArray();
    register(root, engines);
    String before = engines.toString();
    Files.delete(root.resolve("engines/katago/windows-x64-nvidia-cuda13/katago.exe"));
    register(root, engines);
    assertEquals(before, engines.toString());
    JSONArray fresh = new JSONArray();
    register(root, fresh);
    assertEquals(0, fresh.length());
  }

  @Test
  void legacyAlternateCommandCannotBeClaimedAsPrimary() throws Exception {
    Path root = bundle("legacy");
    JSONArray engines = new JSONArray();
    register(root, engines);
    JSONObject legacy = engines.getJSONObject(0);
    legacy.remove("managedProfileType");
    legacy.remove("managedProfileCommand");
    legacy.put("name", "KataGo Auto Setup");
    assertFalse(BundledKataGoProfile.canMigrate(legacy, root));
  }

  private Path bundle(String name) throws Exception {
    Path root = Files.createDirectories(temp.resolve(name));
    Files.createDirectories(root.resolve("save"));
    Path engine = root.resolve("engines/katago/windows-x64-nvidia-cuda13/katago.exe");
    Files.createDirectories(engine.getParent());
    Files.writeString(engine, "fixture");
    Path configs = Files.createDirectories(root.resolve("engines/katago/configs"));
    Files.writeString(configs.resolve("gtp.cfg"), "fixture");
    Files.writeString(configs.resolve("analysis.cfg"), "fixture");
    Files.createDirectories(root.resolve("weights"));
    Files.writeString(root.resolve("weights/default.bin.gz"), "fixture");
    return root;
  }

  private static void register(Path root, JSONArray engines) throws Exception {
    Method method = Config.class.getDeclaredMethod("applyBundledCuda13Default", Path.class, JSONArray.class);
    method.setAccessible(true);
    method.invoke(null, root, engines);
  }
}
