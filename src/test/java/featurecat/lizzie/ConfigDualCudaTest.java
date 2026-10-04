package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.util.BundledKataGoProfile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

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

  @Test
  void editedOrRemoteEntriesArePreservedAndOnlyOneReplacementIsAdded() throws Exception {
    Path root = bundle("custom");
    JSONArray engines = new JSONArray();
    register(root, engines);
    JSONObject edited = engines.getJSONObject(0);
    String custom = edited.getString("command") + " -override-config numSearchThreads=8";
    edited.put("command", custom);
    register(root, engines);
    register(root, engines);
    assertEquals(2, engines.length());
    assertEquals(custom, edited.getString("command"));
    assertFalse(BundledKataGoProfile.isManagedCuda13(edited));
    JSONObject remote = engines.getJSONObject(1).put("useJavaSSH", true);
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
