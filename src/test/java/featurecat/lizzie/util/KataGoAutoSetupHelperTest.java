package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

public class KataGoAutoSetupHelperTest {
  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void applyingCudaVariantPreservesSiblingAndManualSettings(boolean cuda13, boolean remoteAnalysis)
      throws Exception {
    Path root = Files.createTempDirectory("katago-cuda-variant-selection");
    Path first = touch(root.resolve("engines/katago/windows-x64/katago.exe"));
    Path second = touch(root.resolve("engines/katago/windows-x64-nvidia-cuda13/katago.exe"));
    Path gtp = touch(root.resolve("engines/katago/configs/gtp.cfg"));
    touch(gtp.resolveSibling("analysis.cfg"));
    Path bundled = touch(root.resolve("weights/default.bin.gz"));
    Path alternate = touch(root.resolve("weights/selected.bin.gz"));
    Path manualEngine = touch(root.resolve("manual/katago"));
    withUserDirAndConfig(root, () -> {
      ArrayList<EngineData> original = new ArrayList<>(List.of(
          engineData("내 CUDA 12", first, gtp, bundled, false),
          engineData("내 CUDA 13", second, gtp, bundled, false),
          engineData("Manual default", manualEngine, gtp, bundled, true)));
      for (int index = 0; index < 2; index++) {
        EngineData entry = original.get(index);
        BundledKataGoProfile.claim(entry);
        entry.preload = true;
        entry.komi = 6.5F;
        entry.width = 13;
        entry.height = 9;
        entry.initialCommand = "kata-set-rules chinese";
      }
      String customAnalysis = remoteAnalysis
          ? "\"engines/katago/windows-x64/katago.exe\" analysis -model \"weights/remote.bin.gz\""
              + " -config \"engines/katago/configs/analysis.cfg\""
          : "my-katago analysis -model my-model.bin.gz";
      JSONObject remoteSettings = new JSONObject()
          .put("useJavaSSH", true).put("ip", "example.invalid").put("port", "2222")
          .put("userName", "fixture-user").put("useKeyGen", true)
          .put("keyGenPath", root.resolve("fixture-key").toString());
      if (remoteAnalysis) {
        Lizzie.config.leelazConfig.put("analysis-engine-ssh-info", remoteSettings);
      }
      Lizzie.config.analysisEngineCommand = customAnalysis;
      Lizzie.config.analysisEngineCommandCustomized = !remoteAnalysis;
      Lizzie.config.uiConfig
          .put("autoload-default", false).put("autoload-empty", true).put("autoload-last", false)
          .put("last-engine", 2)
          .put("analysis-engine-command", customAnalysis)
          .put("analysis-engine-command-customized", !remoteAnalysis);
      Utils.saveEngineSettings(original);
      int target = cuda13 ? 1 : 0;
      Path executable = cuda13 ? second : first;
      var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(original.get(target));
      List<String> expectedIds = original.stream().map(entry -> entry.id).toList();
      for (Path weight : List.of(bundled, alternate)) {
        var result = KataGoAutoSetupHelper.applyAutoSetup(snapshot.withActiveWeight(weight), false);
        assertFalse(result.createdEngine);
        assertEquals(target, result.engineIndex);
        assertEquals(executable, result.snapshot.enginePath);
        assertEquals(weight, result.snapshot.activeWeightPath);
        assertFalse(KataGoAutoSetupHelper.migrateAutoSetupCommandsIfNeeded());
        assertFalse(KataGoAutoSetupHelper.repairBrokenBundledCommandsIfNeeded());
        assertFalse(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());
        ArrayList<EngineData> actual = Utils.getEngineData();
        assertEquals(expectedIds, actual.stream().map(entry -> entry.id).toList());
        for (int index = 0; index < original.size(); index++) {
          JSONObject expected = Utils.engineDataToJson(original.get(index));
          if (index == target) {
            String command = engineData("", executable, gtp, weight, false).commands;
            expected.put("command", command).put("managedProfileCommand", command);
          }
          assertTrue(expected.similar(Utils.engineDataToJson(actual.get(index))),
              "Only the selected variant's generated command may change");
        }
        JSONObject persisted = new JSONObject(Files.readString(Path.of(Lizzie.config.getConfigFilePath())));
        assertPersistedEngineSettings(actual, persisted);
        JSONObject ui = persisted.getJSONObject("ui");
        assertEquals(2, ui.getInt("default-engine"));
        assertEquals(2, ui.getInt("last-engine"));
        assertFalse(ui.getBoolean("autoload-default"));
        assertTrue(ui.getBoolean("autoload-empty"));
        assertFalse(ui.getBoolean("autoload-last"));
        assertEquals(customAnalysis, ui.getString("analysis-engine-command"));
        assertEquals(customAnalysis, Lizzie.config.analysisEngineCommand);
        assertEquals(!remoteAnalysis, ui.getBoolean("analysis-engine-command-customized"));
        if (remoteAnalysis) {
          assertTrue(remoteSettings.similar(persisted.getJSONObject("leelaz")
              .getJSONObject("analysis-engine-ssh-info")));
        }
      }
    });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void creatingMissingCudaVariantDoesNotClaimSiblingOrUserProfiles(boolean cuda13)
      throws Exception {
    Path root = Files.createTempDirectory("katago-new-cuda-variant");
    Path first = touch(root.resolve("engines/katago/windows-x64/katago.exe"));
    Path second = touch(root.resolve("engines/katago/windows-x64-nvidia-cuda13/katago.exe"));
    Path gtp = touch(root.resolve("engines/katago/configs/gtp.cfg"));
    touch(gtp.resolveSibling("analysis.cfg"));
    Path weight = touch(root.resolve("weights/default.bin.gz"));
    Path selected = cuda13 ? second : first;
    Path sibling = cuda13 ? first : second;
    withUserDirAndConfig(root, () -> {
      EngineData other = engineData("Sibling CUDA", sibling, gtp, weight, true);
      EngineData remote = engineData("KataGo Auto Setup", selected, gtp, weight, false);
      EngineData edited = engineData("KataGo Auto Setup", selected, gtp, weight, false);
      for (EngineData entry : List.of(other, remote, edited)) BundledKataGoProfile.claim(entry);
      remote.useJavaSSH = true;
      remote.ip = "example.invalid";
      remote.port = "2222";
      remote.userName = "fixture-user";
      remote.useKeyGen = true;
      remote.keyGenPath = root.resolve("fixture-key").toString();
      edited.commands += " -override-config numSearchThreads=3";
      ArrayList<EngineData> original = new ArrayList<>(List.of(other, remote, edited));
      Lizzie.config.uiConfig
          .put("autoload-default", false).put("autoload-empty", true).put("autoload-last", false);
      Utils.saveEngineSettings(original);
      var snapshot = KataGoAutoSetupHelper.inspectSelectedLocalKataGo(selected, gtp, weight).toSnapshot();
      var created = KataGoAutoSetupHelper.applyAutoSetup(snapshot, false);
      assertTrue(created.createdEngine);
      assertEquals(3, created.engineIndex);
      for (int selection = 0; selection < 2; selection++) {
        var repeated = KataGoAutoSetupHelper.applyAutoSetup(snapshot, false);
        assertFalse(repeated.createdEngine);
        assertEquals(created.engineIndex, repeated.engineIndex);
        ArrayList<EngineData> actual = Utils.getEngineData();
        assertEquals(4, actual.size());
        for (int index = 0; index < original.size(); index++) {
          assertTrue(Utils.engineDataToJson(original.get(index))
              .similar(Utils.engineDataToJson(actual.get(index))),
              "The other variant, SSH fields, and edited command must remain untouched");
        }
        EngineData added = actual.get(created.engineIndex);
        assertEquals(created.snapshot.savedEntryId, added.id);
        assertEquals(engineData("", selected, gtp, weight, false).commands, added.commands);
        assertEquals(cuda13 ? BundledKataGoProfile.CUDA13_TYPE : BundledKataGoProfile.TYPE,
            added.managedProfileType);
        assertEquals(added.commands, added.managedProfileCommand);
        JSONObject persisted = new JSONObject(Files.readString(Path.of(Lizzie.config.getConfigFilePath())));
        assertPersistedEngineSettings(actual, persisted);
        assertEquals(0, persisted.getJSONObject("ui").getInt("default-engine"));
      }
    });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void addedWeightProfileRetainsRenamedOwnerForEachCudaVariant(boolean cuda13) throws Exception {
    Path root = Files.createTempDirectory("katago-cuda-weight-profile");
    Path first = touch(root.resolve("engines/katago/windows-x64/katago.exe"));
    Path second = touch(root.resolve("engines/katago/windows-x64-nvidia-cuda13/katago.exe"));
    Path gtp = touch(root.resolve("engines/katago/configs/gtp.cfg"));
    touch(gtp.resolveSibling("analysis.cfg"));
    Path bundled = touch(root.resolve("weights/default.bin.gz"));
    Path alternate = touch(root.resolve("weights/downloaded.bin.gz"));
    withUserDirAndConfig(root, () -> {
      ArrayList<EngineData> original = new ArrayList<>(List.of(
          engineData("CUDA12", first, gtp, bundled, true),
          engineData("CUDA13", second, gtp, bundled, false)));
      for (EngineData entry : original) BundledKataGoProfile.claim(entry);
      Lizzie.config.uiConfig
          .put("autoload-default", false).put("autoload-empty", true).put("autoload-last", false);
      Utils.saveEngineSettings(original);
      var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(original.get(cuda13 ? 1 : 0));
      var existing = KataGoAutoSetupHelper.addWeightEngineProfile(snapshot);
      assertFalse(existing.createdEngine);
      assertEquals(cuda13 ? 1 : 0, existing.engineIndex);
      assertEquals(cuda13 ? "CUDA13" : "CUDA12", existing.engineName);
      var selected = snapshot.withActiveWeight(alternate);
      var added = KataGoAutoSetupHelper.addWeightEngineProfile(selected);
      assertTrue(added.createdEngine);
      assertEquals(2, added.engineIndex);
      ArrayList<EngineData> entries = Utils.getEngineData();
      EngineData customized = entries.get(added.engineIndex);
      customized.name = "내가 고른 모델";
      customized.preload = true;
      customized.komi = 6.5F;
      customized.initialCommand = "kata-set-rules chinese";
      Utils.saveEngineSettings(entries);
      JSONObject expected = Utils.engineDataToJson(customized);
      var repeated = KataGoAutoSetupHelper.addWeightEngineProfile(selected);
      var reapplied = KataGoAutoSetupHelper.applyAutoSetup(selected, false);
      for (var result : List.of(repeated, reapplied)) {
        assertFalse(result.createdEngine);
        assertEquals(added.engineIndex, result.engineIndex);
        assertEquals(customized.id, result.snapshot.savedEntryId);
        assertEquals(customized.name, result.engineName);
      }
      ArrayList<EngineData> actual = Utils.getEngineData();
      assertEquals(3, actual.size());
      assertTrue(expected.similar(Utils.engineDataToJson(actual.get(added.engineIndex))));
      assertEquals(cuda13 ? BundledKataGoProfile.CUDA13_TYPE : BundledKataGoProfile.TYPE,
          actual.get(added.engineIndex).managedProfileType);
      for (int index = 0; index < original.size(); index++) {
        assertEquals(original.get(index).commands, actual.get(index).commands);
        assertEquals(original.get(index).id, actual.get(index).id);
        assertFalse(actual.get(index).isDefault);
      }
      JSONObject persisted = new JSONObject(Files.readString(Path.of(Lizzie.config.getConfigFilePath())));
      assertPersistedEngineSettings(actual, persisted);
      assertEquals(2, persisted.getJSONObject("ui").getInt("default-engine"));
      assertTrue(persisted.getJSONObject("ui").getBoolean("autoload-empty"));
    });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitSavedProfileSelectionKeepsItsIdentityAmongMatchingCommands(boolean cuda13)
      throws Exception {
    Path root = Files.createTempDirectory("katago-saved-profile-selection");
    Path engine = touch(root.resolve("engines/katago")
        .resolve(cuda13 ? "windows-x64-nvidia-cuda13" : "windows-x64").resolve("katago.exe"));
    Path gtp = touch(root.resolve("engines/katago/configs/gtp.cfg"));
    touch(gtp.resolveSibling("analysis.cfg"));
    Path bundled = touch(root.resolve("weights/default.bin.gz"));
    Path alternate = touch(root.resolve("weights/alternate.bin.gz"));
    withUserDirAndConfig(root, () -> {
      ArrayList<EngineData> original = new ArrayList<>(List.of(
          engineData("First profile", engine, gtp, bundled, true),
          engineData("Selected profile", engine, gtp, bundled, false)));
      for (EngineData entry : original) BundledKataGoProfile.claim(entry);
      original.get(1).preload = true;
      original.get(1).komi = 6.5F;
      Lizzie.config.uiConfig
          .put("autoload-default", false).put("autoload-empty", true).put("autoload-last", false);
      Utils.saveEngineSettings(original);
      var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(original.get(1));
      var applied = KataGoAutoSetupHelper.applyAutoSetup(snapshot, false);
      assertEquals(1, applied.engineIndex);
      assertFalse(applied.createdEngine);
      assertEquals(0, Lizzie.config.uiConfig.getInt("default-engine"));
      var reselected = KataGoAutoSetupHelper.addWeightEngineProfile(snapshot);
      assertEquals(1, reselected.engineIndex);
      assertFalse(reselected.createdEngine);
      var changed = KataGoAutoSetupHelper.applyAutoSetup(snapshot.withActiveWeight(alternate), false);
      assertEquals(1, changed.engineIndex);
      assertFalse(changed.createdEngine);
      ArrayList<EngineData> actual = Utils.getEngineData();
      assertEquals(original.stream().map(entry -> entry.id).toList(),
          actual.stream().map(entry -> entry.id).toList());
      assertEquals(original.get(0).commands, actual.get(0).commands);
      assertEquals("Selected profile", actual.get(1).name);
      assertTrue(actual.get(1).preload);
      assertEquals(6.5F, actual.get(1).komi);
      assertEquals(engineData("", engine, gtp, alternate, true).commands, actual.get(1).commands);
      assertEquals(actual.get(1).commands, actual.get(1).managedProfileCommand);
      JSONObject persisted = new JSONObject(Files.readString(Path.of(Lizzie.config.getConfigFilePath())));
      assertPersistedEngineSettings(actual, persisted);
      assertEquals(1, persisted.getJSONObject("ui").getInt("default-engine"));
    });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void lastEngineStartupPreservesTheSeparateDefaultProfile(boolean differentCudaVariant)
      throws Exception {
    Path root = Files.createTempDirectory("katago-last-engine-preservation");
    Path first = touch(root.resolve("engines/katago/windows-x64/katago.exe"));
    Path last = differentCudaVariant
        ? touch(root.resolve("engines/katago/windows-x64-nvidia-cuda13/katago.exe")) : first;
    Path gtp = touch(root.resolve("engines/katago/configs/gtp.cfg"));
    touch(gtp.resolveSibling("analysis.cfg"));
    Path firstWeight = touch(root.resolve("weights/default.bin.gz"));
    Path lastWeight = touch(root.resolve("weights/last-model.bin.gz"));
    Files.createDirectories(root.resolve("save"));
    withUserDirAndConfig(root, () -> {
      ArrayList<EngineData> entries = new ArrayList<>(List.of(
          engineData("Default profile", first, gtp, firstWeight, true),
          engineData("Last profile", last, gtp, lastWeight, false)));
      for (EngineData entry : entries) BundledKataGoProfile.claim(entry);
      Lizzie.config.uiConfig.put("autoload-default", false)
          .put("autoload-last", true).put("autoload-empty", false)
          .put("default-engine", 0).put("last-engine", 1)
          .put("analysis-engine-command", "my-independent-analysis")
          .put("analysis-engine-command-customized", true);
      Lizzie.config.analysisEngineCommand = "my-independent-analysis";
      Lizzie.config.analysisEngineCommandCustomized = true;
      Utils.saveEngineSettings(entries);
      Lizzie.config.save();
      List<JSONObject> expected = Utils.getEngineData().stream()
          .map(Utils::engineDataToJson).toList();
      for (int restart = 0; restart < 3; restart++) {
        Lizzie.config = ConfigTestHelper.createBootstrapped(root);
        Utils.applyMaintainedDefaultSettings();
        ArrayList<EngineData> actual = Utils.getEngineData();
        assertEquals(2, actual.size());
        for (int index = 0; index < actual.size(); index++) {
          assertTrue(expected.get(index).similar(Utils.engineDataToJson(actual.get(index))),
              "Starting the last engine must preserve the separate default profile");
        }
        assertFalse(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());
        assertFalse(KataGoAutoSetupHelper.repairBrokenBundledCommandsIfNeeded());
        JSONObject persisted = new JSONObject(Files.readString(Path.of(Lizzie.config.getConfigFilePath())));
        assertPersistedEngineSettings(actual, persisted);
        assertEquals(0, persisted.getJSONObject("ui").getInt("default-engine"));
        assertEquals(1, persisted.getJSONObject("ui").getInt("last-engine"));
        assertTrue(persisted.getJSONObject("ui").getBoolean("autoload-last"));
        assertFalse(persisted.getJSONObject("ui").getBoolean("autoload-default"));
      }
    });
  }

  private static void assertPersistedEngineSettings(List<EngineData> engines, JSONObject config) {
    JSONArray persisted = config.getJSONObject("leelaz").getJSONArray("engine-settings-list");
    assertEquals(engines.size(), persisted.length());
    for (int index = 0; index < engines.size(); index++) {
      assertTrue(Utils.engineDataToJson(engines.get(index)).similar(persisted.getJSONObject(index)));
    }
  }

  @Test
  void automaticRepairPreservesBothCudaVariantsWhenBoardAndAnalysisUseDifferentEngines()
      throws Exception {
    Path root = Files.createTempDirectory("katago-dual-cuda-repair");
    Path cuda12 = touch(root.resolve("engines/katago/windows-x64/katago.exe"));
    Path cuda13 = touch(root.resolve("engines/katago/windows-x64-nvidia-cuda13/katago.exe"));
    Path gtp = touch(root.resolve("engines/katago/configs/gtp.cfg"));
    Path analysis = touch(root.resolve("engines/katago/configs/analysis.cfg"));
    Path model = touch(root.resolve("weights/default.bin.gz"));
    withUserDirAndConfig(root, () -> {
      ArrayList<EngineData> entries = new ArrayList<>();
      entries.add(engineData("CUDA12", cuda12, gtp, model, false));
      entries.add(engineData("CUDA13", cuda13, gtp, model, true));
      for (EngineData entry : entries) BundledKataGoProfile.claim(entry);
      for (int selected : new int[] {1, 0}) {
        entries.get(0).isDefault = selected == 0;
        entries.get(1).isDefault = selected == 1;
        Utils.saveEngineSettings(entries);
        Lizzie.config.uiConfig.put("default-engine", selected);
        String analysisCommand = quote(selected == 1 ? cuda12 : cuda13)
            + " analysis -model " + quote(model) + " -config " + quote(analysis)
            + " -quit-without-waiting";
        Lizzie.config.uiConfig.put("analysis-engine-command", analysisCommand);
        assertFalse(KataGoAutoSetupHelper.migrateAutoSetupCommandsIfNeeded());
        assertFalse(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());
        assertFalse(KataGoAutoSetupHelper.repairBrokenBundledCommandsIfNeeded());
        ArrayList<EngineData> after = Utils.getEngineData();
        assertEquals(2, after.size());
        assertEquals(entries.get(0).commands, after.get(0).commands);
        assertEquals(entries.get(1).commands, after.get(1).commands);
        assertEquals(selected, Lizzie.config.uiConfig.getInt("default-engine"));
        assertEquals(analysisCommand, Lizzie.config.uiConfig.getString("analysis-engine-command"));
      }
    });
  }

  @Test
  void switchingPortableWeightRetainsBundledAndDownloadedCatalogCandidates() throws Exception {
    Path root = Files.createTempDirectory("katago-switch-catalog");
    Path app = Files.createDirectories(root.resolve("app"));
    Path work = Files.createDirectories(root.resolve("user-data"));
    touch(root.resolve(".lizzie-portable"));
    Path engine = touch(app.resolve("engines/katago").resolve(testKataGoBinaryName()));
    Path gtp = touch(app.resolve("engines/katago/configs/gtp.cfg"));
    touch(gtp.resolveSibling("analysis.cfg"));
    Path bundled = touch(app.resolve("weights/default.bin.gz"));
    Path downloaded = touch(work.resolve("weights/downloaded.bin.gz"));
    withProcessDirAndConfig(root, work, () -> {
      EngineData entry = engineData("Portable", engine, gtp, bundled, true);
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      KataGoAutoSetupHelper.SetupSnapshot initial = KataGoAutoSetupHelper.inspectLocalSetup();
      KataGoAutoSetupHelper.SetupResult switched =
          KataGoAutoSetupHelper.applyAutoSetup(initial.withActiveWeight(downloaded), false);
      assertEquals(downloaded, switched.snapshot.activeWeightPath);
      assertTrue(switched.snapshot.weightCandidates.contains(bundled),
          "Switching must not turn the bundled model back into a download-only catalog entry");
      assertTrue(switched.snapshot.weightCandidates.contains(downloaded));
      assertEquals(engine, switched.snapshot.enginePath);
      assertEquals(gtp, switched.snapshot.gtpConfigPath);
    });
  }

  @Test
  void discoversCompleteExternalKataGoFromDefaultEngineAsOneCoherentProfile() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-external");
    Path external = Files.createDirectories(root.resolve("外部 KataGo 有空格"));
    Path engine = touch(external.resolve(testKataGoBinaryName()));
    Path configs = Files.createDirectories(external.resolve("configs"));
    Path gtp = touch(configs.resolve("gtp.cfg"));
    Path analysis = touch(configs.resolve("analysis.cfg"));
    Path weight = touch(external.resolve("模型 权重.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Utils.saveEngineSettings(
              new ArrayList<>(List.of(engineData("外部 KataGo", engine, gtp, weight, true))));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.DEFAULT_ENGINE, result.source);
          assertEquals(KataGoAutoSetupHelper.PackageFlavor.EXTERNAL, result.packageFlavor);
          assertEquals(engine, result.enginePath);
          assertEquals(gtp, result.gtpConfigPath);
          assertEquals(analysis, result.analysisConfigPath);
          assertEquals(weight, result.activeWeightPath);
        });
  }

  @Test
  void resolvesRelativeConfigAndWeightAgainstExecutableDirectory() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-relative");
    Path external = Files.createDirectories(root.resolve("engine dir"));
    Path engine = touch(external.resolve(testKataGoBinaryName()));
    Path configs = Files.createDirectories(external.resolve("configs"));
    Path gtp = touch(configs.resolve("gtp.cfg"));
    Path analysis = touch(configs.resolve("analysis.cfg"));
    Path weight = touch(external.resolve("weights").resolve("relative.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          EngineData relative = new EngineData();
          relative.name = "KataGo Relative";
          relative.commands =
              quoteLiteral(root.relativize(engine))
                  + " gtp -model "
                  + quoteLiteral(Path.of("weights", weight.getFileName().toString()))
                  + " -config "
                  + quoteLiteral(Path.of("configs", "gtp.cfg"));
          relative.isDefault = true;
          Utils.saveEngineSettings(new ArrayList<>(List.of(relative)));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(engine, result.enginePath);
          assertEquals(gtp, result.gtpConfigPath);
          assertEquals(analysis, result.analysisConfigPath);
          assertEquals(weight, result.activeWeightPath);
        });
  }

  @Test
  void skipsRemoteDefaultCandidateAndUsesLocalDefaultFlag() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-remote-skip");
    Path local = Files.createDirectories(root.resolve("local"));
    Path engine = touch(local.resolve(testKataGoBinaryName()));
    Path gtp = touch(local.resolve("configs").resolve("gtp.cfg"));
    touch(local.resolve("configs").resolve("analysis.cfg"));
    Path weight = touch(local.resolve("local.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          EngineData remote = new EngineData();
          remote.name = "智子云算力";
          remote.commands = "remote-compute://zhizi/default";
          remote.isDefault = false;
          EngineData localDefault = engineData("KataGo Local", engine, gtp, weight, true);
          Utils.saveEngineSettings(new ArrayList<>(List.of(remote, localDefault)));
          Lizzie.config.uiConfig.put("default-engine", 1);

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(engine, result.enginePath);
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.DEFAULT_ENGINE, result.source);
        });
  }

  @Test
  void startupEngineTakesPriorityOverTheDefaultEngine() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-startup-priority");
    Path startup = Files.createDirectories(root.resolve("startup"));
    Path startupEngine = touch(startup.resolve(testKataGoBinaryName()));
    Path startupGtp = touch(startup.resolve("configs").resolve("gtp.cfg"));
    touch(startup.resolve("configs").resolve("analysis.cfg"));
    Path startupWeight = touch(startup.resolve("startup.bin.gz"));
    Path defaultDir = Files.createDirectories(root.resolve("default"));
    Path defaultEngine = touch(defaultDir.resolve(testKataGoBinaryName()));
    Path defaultGtp = touch(defaultDir.resolve("configs").resolve("gtp.cfg"));
    touch(defaultDir.resolve("configs").resolve("analysis.cfg"));
    Path defaultWeight = touch(defaultDir.resolve("default.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Utils.saveEngineSettings(
              new ArrayList<>(
                  List.of(
                      engineData("Startup KataGo", startupEngine, startupGtp, startupWeight, false),
                      engineData(
                          "Default KataGo", defaultEngine, defaultGtp, defaultWeight, true))));
          Lizzie.config.uiConfig.put("autoload-last", true);
          Lizzie.config.uiConfig.put("last-engine", 0);
          Lizzie.config.uiConfig.put("default-engine", 1);

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertEquals(KataGoAutoSetupHelper.DiscoverySource.STARTUP_ENGINE, result.source);
          assertEquals(startupEngine, result.enginePath);
          assertEquals(startupWeight, result.activeWeightPath);
        });
  }

  @Test
  void discoversRememberedSetupBeforeIndependentAnalysisCommand() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-remembered-priority");
    Path remembered = Files.createDirectories(root.resolve("remembered"));
    Path rememberedEngine = touch(remembered.resolve(testKataGoBinaryName()));
    Path rememberedGtp = touch(remembered.resolve("configs").resolve("gtp.cfg"));
    Path rememberedAnalysis = touch(remembered.resolve("configs").resolve("analysis.cfg"));
    Path rememberedWeight = touch(remembered.resolve("remembered.bin.gz"));
    Path analysisDir = Files.createDirectories(root.resolve("analysis"));
    Path analysisEngine = touch(analysisDir.resolve(testKataGoBinaryName()));
    Path analysisConfig = touch(analysisDir.resolve("configs").resolve("analysis.cfg"));
    touch(analysisDir.resolve("configs").resolve("gtp.cfg"));
    Path analysisWeight = touch(analysisDir.resolve("analysis.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Lizzie.config.uiConfig.put("katago-auto-setup-engine-path", rememberedEngine.toString());
          Lizzie.config.uiConfig.put("katago-auto-setup-gtp-config-path", rememberedGtp.toString());
          Lizzie.config.uiConfig.put(
              "katago-auto-setup-analysis-config-path", rememberedAnalysis.toString());
          Lizzie.config.uiConfig.put("katago-auto-setup-weight-path", rememberedWeight.toString());
          Lizzie.config.uiConfig.put(
              "analysis-engine-command",
              quote(analysisEngine)
                  + " analysis -model "
                  + quote(analysisWeight)
                  + " -config "
                  + quote(analysisConfig));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertEquals(KataGoAutoSetupHelper.DiscoverySource.REMEMBERED_SETUP, result.source);
          assertEquals(rememberedEngine, result.enginePath);
          assertEquals(rememberedWeight, result.activeWeightPath);
        });
  }

  @Test
  void discoversACompleteIndependentAnalysisCommand() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-analysis-command");
    Path engine = touch(root.resolve("analysis").resolve(testKataGoBinaryName()));
    Path analysisConfig =
        touch(root.resolve("analysis").resolve("configs").resolve("analysis.cfg"));
    Path gtpConfig = touch(root.resolve("analysis").resolve("configs").resolve("gtp.cfg"));
    Path weight = touch(root.resolve("analysis").resolve("analysis.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Lizzie.config.uiConfig.put(
              "analysis-engine-command",
              quote(engine)
                  + " analysis -model "
                  + quote(weight)
                  + " -config "
                  + quote(analysisConfig));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.ANALYSIS_COMMAND, result.source);
          assertEquals(engine, result.enginePath);
          assertEquals(gtpConfig, result.gtpConfigPath);
          assertEquals(analysisConfig, result.analysisConfigPath);
          assertEquals(weight, result.activeWeightPath);
        });
  }

  @Test
  void doesNotTreatAnotherGtpEngineAsLocalKataGo() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-non-katago");
    touch(root.resolve("PROJECT_INFO.txt"));
    Path engine = touch(root.resolve("other-engine").resolve("leela-zero"));
    Path config = touch(root.resolve("other-engine").resolve("configs").resolve("gtp.cfg"));
    touch(root.resolve("other-engine").resolve("configs").resolve("analysis.cfg"));
    Path weight = touch(root.resolve("other-engine").resolve("network.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Utils.saveEngineSettings(
              new ArrayList<>(
                  List.of(engineData("Other GTP engine", engine, config, weight, true))));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertEquals(KataGoAutoSetupHelper.DiscoverySource.NONE, result.source);
          assertNull(result.enginePath);
        });
  }

  @Test
  void incompleteExternalProfileFallsBackWithoutMixingBundledFiles() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-no-mix");
    Path external = Files.createDirectories(root.resolve("external"));
    Path externalEngine = touch(external.resolve(testKataGoBinaryName()));
    Path externalGtp = touch(external.resolve("configs").resolve("gtp.cfg"));
    Path externalWeight = touch(external.resolve("external.bin.gz"));
    Path bundledEngine =
        touch(
            root.resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path bundledConfigs =
        Files.createDirectories(root.resolve("engines").resolve("katago").resolve("configs"));
    Path bundledGtp = touch(bundledConfigs.resolve("gtp.cfg"));
    Path bundledAnalysis = touch(bundledConfigs.resolve("analysis.cfg"));
    Path bundledWeight = touch(root.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Utils.saveEngineSettings(
              new ArrayList<>(
                  List.of(
                      engineData(
                          "Incomplete KataGo",
                          externalEngine,
                          externalGtp,
                          externalWeight,
                          true))));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.BUNDLED_PACKAGE, result.source);
          assertEquals(bundledEngine, result.enginePath);
          assertEquals(bundledGtp, result.gtpConfigPath);
          assertEquals(bundledAnalysis, result.analysisConfigPath);
          assertEquals(bundledWeight, result.activeWeightPath);
          assertFalse(result.enginePath.equals(externalEngine));
          assertFalse(result.activeWeightPath.equals(externalWeight));
        });
  }

  @Test
  void recognizesNoEngineAndStandaloneCoreUpdatePackages() throws Exception {
    Path noEngine = Files.createTempDirectory("katago-no-engine-package");
    Files.writeString(
        noEngine.resolve("lizzieyzy-next-installed-manifest.json"),
        "{\"platform\":\"windows\",\"flavor\":\"without.engine\"}");
    withUserDirAndConfig(
        noEngine,
        () ->
            assertEquals(
                KataGoAutoSetupHelper.PackageFlavor.WITHOUT_ENGINE,
                KataGoAutoSetupHelper.inspectLocalKataGo().packageFlavor));

    Path coreUpdate = Files.createTempDirectory("katago-core-update-package");
    Files.writeString(coreUpdate.resolve("lizzieyzy-next-core-update-manifest.json"), "{}");
    withUserDirAndConfig(
        coreUpdate,
        () ->
            assertEquals(
                KataGoAutoSetupHelper.PackageFlavor.CORE_UPDATE_ONLY,
                KataGoAutoSetupHelper.inspectLocalKataGo().packageFlavor));

    Path incomplete = Files.createTempDirectory("katago-incomplete-package");
    Files.writeString(
        incomplete.resolve("lizzieyzy-next-installed-manifest.json"),
        "{\"platform\":\"windows\",\"flavor\":\"opencl\"}");
    withUserDirAndConfig(
        incomplete,
        () ->
            assertEquals(
                KataGoAutoSetupHelper.PackageFlavor.INCOMPLETE_BUNDLE,
                KataGoAutoSetupHelper.inspectLocalKataGo().packageFlavor));
  }

  @Test
  void recognizesEverySupportedCompletePackageFlavor() throws Exception {
    Object[][] cases = {
      {"opencl", KataGoAutoSetupHelper.PackageFlavor.OPENCL},
      {"nvidia", KataGoAutoSetupHelper.PackageFlavor.NVIDIA},
      {"nvidia50.cuda", KataGoAutoSetupHelper.PackageFlavor.NVIDIA50_CUDA},
      {"nvidia.tensorrt", KataGoAutoSetupHelper.PackageFlavor.TENSORRT},
      {"nvidia-tensorrt", KataGoAutoSetupHelper.PackageFlavor.TENSORRT},
      {"NVIDIA TensorRT", KataGoAutoSetupHelper.PackageFlavor.TENSORRT},
      {"cpu", KataGoAutoSetupHelper.PackageFlavor.CPU},
      {"with-katago", KataGoAutoSetupHelper.PackageFlavor.WITH_KATAGO}
    };
    for (Object[] testCase : cases) {
      String flavor = (String) testCase[0];
      Path root = Files.createTempDirectory("katago-package-" + flavor.replace('.', '-'));
      Files.writeString(
          root.resolve("lizzieyzy-next-installed-manifest.json"),
          "{\"platform\":\"windows\",\"flavor\":\"" + flavor + "\"}");
      touch(
          root.resolve("engines")
              .resolve("katago")
              .resolve(detectTestPlatformDir())
              .resolve(testKataGoBinaryName()));
      touch(root.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg"));
      touch(root.resolve("engines").resolve("katago").resolve("configs").resolve("analysis.cfg"));
      touch(root.resolve("weights").resolve("default.bin.gz"));

      withUserDirAndConfig(
          root,
          () ->
              assertEquals(
                  testCase[1], KataGoAutoSetupHelper.inspectLocalKataGo().packageFlavor, flavor));
    }
  }

  @Test
  void discoversJpackagePortableAssetsInsideAppDirectory() throws Exception {
    Path portableRoot = Files.createTempDirectory("katago-jpackage-portable");
    Path app = Files.createDirectories(portableRoot.resolve("app"));
    Path workDir = Files.createDirectories(portableRoot.resolve("user-data"));
    touch(portableRoot.resolve(".lizzie-portable"));
    touch(app.resolve("PROJECT_INFO.txt"));
    Files.writeString(
        app.resolve("lizzieyzy-next-installed-manifest.json"),
        "{\"platform\":\"windows\",\"flavor\":\"opencl\"}");
    Path engine =
        touch(
            app.resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configs =
        Files.createDirectories(app.resolve("engines").resolve("katago").resolve("configs"));
    Path gtp = touch(configs.resolve("gtp.cfg"));
    Path analysis = touch(configs.resolve("analysis.cfg"));
    Path weight = touch(app.resolve("weights").resolve("default.bin.gz"));

    withProcessDirAndConfig(
        portableRoot,
        workDir,
        () -> {
          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(portableRoot, result.appRoot);
          assertEquals(KataGoAutoSetupHelper.PackageFlavor.OPENCL, result.packageFlavor);
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.BUNDLED_PACKAGE, result.source);
          assertEquals(engine, result.enginePath);
          assertEquals(gtp, result.gtpConfigPath);
          assertEquals(analysis, result.analysisConfigPath);
          assertEquals(weight, result.activeWeightPath);
        });
  }

  @Test
  void resolvesSavedRelativeCommandAgainstJpackageAppDirectory() throws Exception {
    Path portableRoot = Files.createTempDirectory("katago-jpackage-relative-command");
    Path app = Files.createDirectories(portableRoot.resolve("app"));
    Path workDir = Files.createDirectories(portableRoot.resolve("user-data"));
    touch(portableRoot.resolve(".lizzie-portable"));
    touch(app.resolve("PROJECT_INFO.txt"));
    Path engine =
        touch(
            app.resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configs =
        Files.createDirectories(app.resolve("engines").resolve("katago").resolve("configs"));
    Path gtp = touch(configs.resolve("gtp.cfg"));
    Path analysis = touch(configs.resolve("analysis.cfg"));
    Path weight = touch(app.resolve("weights").resolve("default.bin.gz"));

    withProcessDirAndConfig(
        portableRoot,
        workDir,
        () -> {
          EngineData relative = new EngineData();
          relative.name = "KataGo Portable";
          relative.commands =
              quoteLiteral(app.relativize(engine))
                  + " gtp -model "
                  + quoteLiteral(app.relativize(weight))
                  + " -config "
                  + quoteLiteral(app.relativize(gtp));
          relative.isDefault = true;
          Utils.saveEngineSettings(new ArrayList<>(List.of(relative)));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.DEFAULT_ENGINE, result.source);
          assertEquals(engine, result.enginePath);
          assertEquals(gtp, result.gtpConfigPath);
          assertEquals(analysis, result.analysisConfigPath);
          assertEquals(weight, result.activeWeightPath);
        });
  }

  @Test
  void relativeEngineCommandDoesNotLeakFromJvmWorkingDirectory() throws Exception {
    Path jvmWorkingDirectory = Path.of("").toAbsolutePath().normalize();
    Path shadowDirectory =
        Files.createTempDirectory(
                Files.createDirectories(jvmWorkingDirectory.resolve("target")),
                "katago-cwd-shadow-")
            .toAbsolutePath()
            .normalize();
    Path shadowEngine = touch(shadowDirectory.resolve(testKataGoBinaryName()));
    Path relativeEngine = jvmWorkingDirectory.relativize(shadowEngine);

    try {
      Path packageRoot = Files.createTempDirectory("katago-contextual-relative-command");
      touch(packageRoot.resolve("PROJECT_INFO.txt"));
      Path workDir = Files.createDirectories(packageRoot.resolve("user-data"));
      Path packagedEngine = touch(packageRoot.resolve(relativeEngine));
      Path configs = Files.createDirectories(packageRoot.resolve("configs"));
      Path gtp = touch(configs.resolve("gtp.cfg"));
      Path analysis = touch(configs.resolve("analysis.cfg"));
      Path weight = touch(packageRoot.resolve("weights").resolve("default.bin.gz"));

      withProcessDirAndConfig(
          packageRoot,
          workDir,
          () -> {
            EngineData relative = new EngineData();
            relative.name = "KataGo contextual relative";
            relative.commands =
                quoteLiteral(relativeEngine)
                    + " gtp -model "
                    + quote(weight)
                    + " -config "
                    + quote(gtp);
            relative.isDefault = true;
            Utils.saveEngineSettings(new ArrayList<>(List.of(relative)));

            KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
                KataGoAutoSetupHelper.inspectLocalKataGo();

            assertTrue(result.isComplete());
            assertEquals(KataGoAutoSetupHelper.DiscoverySource.DEFAULT_ENGINE, result.source);
            assertEquals(packagedEngine, result.enginePath);
            assertEquals(gtp, result.gtpConfigPath);
            assertEquals(analysis, result.analysisConfigPath);
            assertEquals(weight, result.activeWeightPath);
          });
    } finally {
      Files.deleteIfExists(shadowEngine);
      Files.deleteIfExists(shadowDirectory);
    }
  }

  @Test
  void repairsMissingAnalysisConfigOnlyAfterExplicitRequest() throws Exception {
    Path root = Files.createTempDirectory("katago-analysis-repair");
    Path engine = touch(root.resolve("external").resolve(testKataGoBinaryName()));
    Path gtp = touch(root.resolve("external").resolve("configs").resolve("gtp.cfg"));
    Path weight = touch(root.resolve("external").resolve("weight.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          KataGoAutoSetupHelper.SetupSnapshot snapshot =
              KataGoAutoSetupHelper.inspectSelectedLocalKataGo(engine, gtp, weight).toSnapshot();
          assertFalse(snapshot.hasConfigs());
          assertNotNull(snapshot.analysisConfigPath);
          assertFalse(Files.exists(snapshot.analysisConfigPath));

          Path repaired = KataGoAutoSetupHelper.repairAnalysisConfig(snapshot);

          assertNotNull(repaired);
          assertTrue(Files.isRegularFile(repaired));
          assertTrue(Files.size(repaired) > 0L);
        });
  }

  @Test
  void manualExternalSelectionDoesNotBorrowBundledConfigOrWeight() throws Exception {
    Path appRoot = Files.createTempDirectory("katago-manual-no-mix-app");
    touch(appRoot.resolve("PROJECT_INFO.txt"));
    touch(appRoot.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg"));
    touch(appRoot.resolve("weights").resolve("bundled.bin.gz"));
    Path externalRoot = Files.createTempDirectory("katago-manual-no-mix-external");
    Path externalEngine = touch(externalRoot.resolve(testKataGoBinaryName()));

    withUserDirAndConfig(
        appRoot,
        () -> {
          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectSelectedLocalKataGo(externalEngine, null, null);

          assertEquals(externalEngine, result.enginePath);
          assertNull(result.gtpConfigPath);
          assertNull(result.activeWeightPath);
          assertTrue(
              result.missingComponents.contains(KataGoAutoSetupHelper.MissingComponent.GTP_CONFIG));
          assertTrue(
              result.missingComponents.contains(KataGoAutoSetupHelper.MissingComponent.WEIGHT));
        });
  }

  @Test
  void validatesAWorkingKataGoExecutableWithoutBlockingTheCallerIndefinitely() throws Exception {
    assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    Path root = Files.createTempDirectory("katago-version-validation");
    Path engine = root.resolve("katago");
    Files.writeString(engine, "#!/bin/sh\nprintf 'KataGo test version\\n'\n");
    assertTrue(engine.toFile().setExecutable(true));

    KataGoAutoSetupHelper.EngineValidationResult result =
        KataGoAutoSetupHelper.validateLocalEngine(engine, 8L);

    assertEquals(KataGoAutoSetupHelper.EngineValidationStatus.VALID, result.status);
    assertTrue(result.detail.contains("KataGo test version"));
  }

  @Test
  void versionProbeFailurePreservesErrorOutput() throws Exception {
    assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    Path root = Files.createTempDirectory("katago-version-failure");
    Path engine = root.resolve("katago");
    Files.writeString(engine, "#!/bin/sh\nprintf 'missing fixture.dll\\n' >&2\nexit 7\n");
    assertTrue(engine.toFile().setExecutable(true));

    var result = KataGoAutoSetupHelper.validateLocalEngine(engine, 8L);
    assertFalse(result.isValid());
    assertTrue(result.detail.contains("fixture.dll"));
  }

  @Test
  void timesOutAHungKataGoExecutableWithinTheConfiguredBound() throws Exception {
    assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    Path root = Files.createTempDirectory("katago-version-timeout");
    Path engine = root.resolve("katago");
    Files.writeString(engine, "#!/bin/sh\nsleep 30\n");
    assertTrue(engine.toFile().setExecutable(true));

    long startedAt = System.nanoTime();
    KataGoAutoSetupHelper.EngineValidationResult result =
        KataGoAutoSetupHelper.validateLocalEngine(engine, 1L);
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

    assertEquals(KataGoAutoSetupHelper.EngineValidationStatus.TIMED_OUT, result.status);
    assertTrue(elapsedMillis < 10_000L, "hung version probe must remain time-bounded");
  }

  @Test
  void trustsTheMatchingCurrentEngineInsteadOfLaunchingASecondCudaProbe() throws Exception {
    Path root = Files.createTempDirectory("katago-active-engine");
    Path katagoRoot = Files.createDirectories(root.resolve("engines").resolve("katago"));
    Path engineDir = Files.createDirectories(katagoRoot.resolve("windows-x64"));
    Path engine = touch(engineDir.resolve("katago.exe"));
    Files.writeString(katagoRoot.resolve("VERSION.txt"), "KataGo release: v1.17.1\n");

    KataGoAutoSetupHelper.EngineValidationResult result =
        KataGoAutoSetupHelper.validateEngineWithActiveSession(
            engine, 1L, quoteLiteral(engine) + " gtp -model model.bin.gz", true);

    assertEquals(KataGoAutoSetupHelper.EngineValidationStatus.ACTIVE, result.status);
    assertTrue(result.isValid());
    assertEquals("1.17.1", result.kataGoVersion);
  }

  @Test
  void retriesOneTransientVersionProbeFailure() throws Exception {
    assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    Path root = Files.createTempDirectory("katago-version-retry");
    Path marker = root.resolve("first-attempt");
    Path engine = root.resolve("katago");
    Files.writeString(
        engine,
        "#!/bin/sh\n"
            + "if [ ! -f '"
            + marker
            + "' ]; then touch '"
            + marker
            + "'; exit 1; fi\n"
            + "printf 'KataGo v1.17.1\\nUsing CUDA backend\\n'\n");
    assertTrue(engine.toFile().setExecutable(true));

    KataGoAutoSetupHelper.EngineValidationResult result =
        KataGoAutoSetupHelper.validateEngineWithActiveSession(engine, 3L, "", false);

    assertEquals(KataGoAutoSetupHelper.EngineValidationStatus.VALID, result.status);
    assertEquals("1.17.1", result.kataGoVersion);
  }

  @Test
  void doesNotTrustAnActiveSessionUsingADifferentExecutable() throws Exception {
    Path root = Files.createTempDirectory("katago-active-other-engine");
    Path runningEngine = touch(root.resolve("running-katago"));
    Path brokenEngine = touch(root.resolve("broken-katago"));

    KataGoAutoSetupHelper.EngineValidationResult result =
        KataGoAutoSetupHelper.validateEngineWithActiveSession(
            brokenEngine, 1L, quoteLiteral(runningEngine) + " gtp", true);

    assertFalse(result.status == KataGoAutoSetupHelper.EngineValidationStatus.ACTIVE);
    assertFalse(result.isValid());
  }

  @Test
  void weightDisplayNameKeepsUserModelNameAndHidesTrainingHashes() {
    assertEquals(
        "zhizi 28B muonfd2",
        KataGoAutoSetupHelper.resolveWeightDisplayName(
            "kata1-zhizi-b28c512nbt-s12763923712-d5805955894-muonfd2.bin.gz"));
    assertEquals(
        "zhizi 40B",
        KataGoAutoSetupHelper.resolveWeightDisplayName("kata1-zhizi-b40c768nbt-fdx6d.bin.gz"));
    assertEquals(
        "28B",
        KataGoAutoSetupHelper.resolveWeightDisplayName(
            "kata1-b28c512nbt-s12763923712-d5805955894.bin.gz"));
    assertEquals(
        "28B 2026-06",
        KataGoAutoSetupHelper.resolveWeightDisplayName(
            "kata1-b28c512nbt-s13255194368-d5935380940.bin.gz"));
  }

  @Test
  void officialTransformerCatalogPinsAllThreeReleaseAssets() {
    List<KataGoAutoSetupHelper.RemoteWeightInfo> weights =
        KataGoAutoSetupHelper.officialTransformerWeights();

    assertEquals(3, weights.size());
    KataGoAutoSetupHelper.RemoteWeightInfo strongest =
        weights.stream()
            .filter(info -> info.transformerTier == KataGoAutoSetupHelper.TransformerTier.STRONGEST)
            .findFirst()
            .orElseThrow();
    assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL, strongest.modelName);
    assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_SIZE_BYTES, strongest.sizeBytes);
    assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_SHA256, strongest.sha256);
    assertEquals("1.17.0", strongest.minimumKataGoVersion);
    assertTrue(strongest.recommended);
    assertFalse(
        weights.stream()
            .filter(info -> info.transformerTier == KataGoAutoSetupHelper.TransformerTier.BALANCED)
            .findFirst()
            .orElseThrow()
            .recommended);
    assertTrue(weights.stream().allMatch(KataGoAutoSetupHelper.RemoteWeightInfo::isTransformer));
    assertEquals("2026-09-25", strongest.uploadedAt);
    assertEquals(
        "https://media.katagotraining.org/uploaded/networks/models/kata1/"
            + KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_FILE_NAME,
        strongest.downloadUrl);
    assertTrue(
        weights.stream()
            .filter(info -> info != strongest)
            .allMatch(info -> info.downloadUrl.contains("/v1.17.1/")));
  }

  @Test
  void lightweightQuickAnalysisModelMetadataMatchesOfficialRelease() {
    KataGoAutoSetupHelper.RemoteWeightInfo lightweight =
        KataGoAutoSetupHelper.officialTransformerWeights().stream()
            .filter(
                info -> info.transformerTier == KataGoAutoSetupHelper.TransformerTier.LIGHTWEIGHT)
            .findFirst()
            .orElseThrow();

    assertEquals(
        KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_FILE_NAME.replace(".bin.gz", ""),
        lightweight.modelName);
    assertEquals(KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_SIZE_BYTES, lightweight.sizeBytes);
    assertEquals(KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_SHA256, lightweight.sha256);
    assertEquals(KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_DOWNLOAD_URL, lightweight.downloadUrl);
  }

  @Test
  void engineVersionParserDistinguishesOldAndTransformerCapableKataGo() {
    assertEquals("1.17.0", KataGoAutoSetupHelper.parseKataGoVersion("KataGo v1.17.0"));
    assertEquals("1.16.5", KataGoAutoSetupHelper.parseKataGoVersion("KataGo v1.16.5\nUsing CUDA"));
    assertEquals("", KataGoAutoSetupHelper.parseKataGoVersion("unknown engine"));
  }

  @Test
  void recognizesTrainedTransformersWithoutRelabelingOldModels() {
    assertTrue(
        KataGoAutoSetupHelper.isTransformerWeight("kata1-tf3-b11c768-s11500M-d6163M.bin.gz"));
    assertFalse(
        KataGoAutoSetupHelper.resolveWeightDisplayName("kata1-tf3-b11c768-s11500M-d6163M.bin.gz")
            .contains("2026-09-25"));
    assertTrue(
        KataGoAutoSetupHelper.isTransformerWeight("kata1-tf3-b11c768-s11750M-d6216M.bin.gz"));
    assertFalse(
        KataGoAutoSetupHelper.resolveWeightDisplayName("kata1-tf3-b11c768-s11750M-d6216M.bin.gz")
            .contains("2026-09-25"));
    assertTrue(
        KataGoAutoSetupHelper.resolveWeightDisplayName(
                KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_FILE_NAME)
            .contains("2026-09-25"));
    assertTrue(
        KataGoAutoSetupHelper.isTransformerWeight("b11c768h12nbt3tflrs-fson-silu.bin.gz"));
    assertFalse(
        KataGoAutoSetupHelper.isTransformerWeight(
            "kata1-b28c512nbt-s12763923712-d5805955894.bin.gz"));
    assertFalse(
        KataGoAutoSetupHelper.resolveWeightDisplayName("b11c768h12nbt3tflrs-fson-silu.bin.gz")
            .contains("2026-09-25"));
  }

  @Test
  void onlineCatalogKeepsPinnedIntegrityAndRatingWithoutDuplicateB11() throws Exception {
    String model = KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL;
    String html =
        "<table class=\"table mt-3\"><tr><td>"
            + model
            + "</td><td>2026-09-25</td><td>14545.3 Elo</td><td>"
            + officialLink(model)
            + "</td></tr></table>";
    try (FixtureServer server = FixtureServer.start(html.getBytes(StandardCharsets.UTF_8))) {
      String previous = System.getProperty("lizzie.katago.networks.url");
      String previousStrongest = System.getProperty("lizzie.katago.strongest.url");
      try {
        System.setProperty("lizzie.katago.networks.url", server.url());
        // The fixture answers HTML, so the strongest API is unusable and catalog rows remain.
        System.setProperty("lizzie.katago.strongest.url", server.url());
        List<KataGoAutoSetupHelper.RemoteWeightInfo> weights =
            KataGoAutoSetupHelper.fetchOfficialWeights();
        assertEquals(3, weights.size());
        KataGoAutoSetupHelper.RemoteWeightInfo b11 =
            weights.stream().filter(info -> info.modelName.equals(model)).findFirst().orElseThrow();
        assertEquals("14545.3 Elo", b11.eloRating);
        assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_SHA256, b11.sha256);
        assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_SIZE_BYTES, b11.sizeBytes);
      } finally {
        restoreProperty("lizzie.katago.networks.url", previous);
        restoreProperty("lizzie.katago.strongest.url", previousStrongest);
      }
    }
  }

  @Test
  void officialTransformerCatalogRemainsAvailableWhenNetworksPageIsOffline() throws Exception {
    try (ErrorFixtureServer server = ErrorFixtureServer.start()) {
      String previous = System.getProperty("lizzie.katago.networks.url");
      String previousStrongest = System.getProperty("lizzie.katago.strongest.url");
      try {
        System.setProperty("lizzie.katago.networks.url", server.url());
        System.setProperty("lizzie.katago.strongest.url", server.url());
        List<KataGoAutoSetupHelper.RemoteWeightInfo> weights =
            KataGoAutoSetupHelper.fetchOfficialWeights();

        assertEquals(3, weights.size());
        assertTrue(
            weights.stream().allMatch(KataGoAutoSetupHelper.RemoteWeightInfo::isTransformer));
      } finally {
        restoreProperty("lizzie.katago.networks.url", previous);
        restoreProperty("lizzie.katago.strongest.url", previousStrongest);
      }
    }
  }

  // Different from the pinned default: the API may identify another network without a release.
  private static final String STRONGEST_NAME = "kata1-tf3-b11c768-s12002M-d6304M";
  private static final String STRONGEST_SHA256 =
      "93bdb63a3bfae4a70db0cb5265287495ecfc10b1ba1cc6814feeba1cdf055871";

  private static String strongestJson(String name, String modelFile, long bytes, String sha256) {
    return "{\"name\":\""
        + name
        + "\",\"created_at\":\"2026-09-25T02:04:37.536189Z\",\"model_file\":\""
        + modelFile
        + "\",\"model_file_bytes\":"
        + bytes
        + ",\"model_file_sha256\":\""
        + sha256
        + "\"}";
  }

  private static String officialModelUrl(String name) {
    return "https://media.katagotraining.org/uploaded/networks/models/kata1/" + name + ".bin.gz";
  }

  @Test
  void officialStrongestAcceptsTransformerAndClassicNetworksWithIntegrityMetadata()
      throws Exception {
    KataGoAutoSetupHelper.RemoteWeightInfo transformer =
        KataGoAutoSetupHelper.parseOfficialStrongest(
            strongestJson(
                STRONGEST_NAME, officialModelUrl(STRONGEST_NAME), 262039869L, STRONGEST_SHA256));
    assertTrue(transformer.officialStrongest);
    assertEquals(STRONGEST_NAME + ".bin.gz", transformer.fileName());
    assertEquals(262039869L, transformer.sizeBytes);
    assertEquals(STRONGEST_SHA256, transformer.sha256);
    assertEquals("2026-09-25", transformer.uploadedAt);
    assertEquals("1.17.0", transformer.minimumKataGoVersion);
    assertEquals(KataGoAutoSetupHelper.TransformerTier.UNTIERED, transformer.transformerTier);
    assertFalse(transformer.recommended);
    assertTrue(
        KataGoAutoSetupHelper.resolveRemoteWeightDisplayName(transformer)
            .endsWith(" · B11 · 2026-09-25"));

    String classicName = "kata1-b28c512nbt-s12763923712-d5805955894";
    KataGoAutoSetupHelper.RemoteWeightInfo classic =
        KataGoAutoSetupHelper.parseOfficialStrongest(
            strongestJson(classicName, officialModelUrl(classicName), 1000L, STRONGEST_SHA256));
    assertEquals("", classic.minimumKataGoVersion);
    assertFalse(classic.isTransformer());
    assertTrue(
        KataGoAutoSetupHelper.resolveRemoteWeightDisplayName(classic)
            .endsWith(" · B28 · 2026-09-25"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknown-name",
        "foreign-host",
        "insecure-url",
        "different-file",
        "query-url",
        "missing-sha",
        "malformed-sha",
        "numeric-sha",
        "zero-size",
        "negative-size",
        "fractional-size",
        "overflow-size",
        "string-size",
        "missing-size",
        "missing-date",
        "invalid-date",
        "invalid-timestamp",
        "not-json",
      })
  void officialStrongestRejectsUnrecognizedOrUnverifiableNetworks(String variant) {
    JSONObject response =
        new JSONObject(
            strongestJson(STRONGEST_NAME, officialModelUrl(STRONGEST_NAME), 1000L, STRONGEST_SHA256));
    switch (variant) {
      case "unknown-name":
        response.put("name", "kata1-zhizi-b28c512nbt-fdx6d");
        break;
      case "foreign-host":
        response.put("model_file", "https://example.com/" + STRONGEST_NAME + ".bin.gz");
        break;
      case "insecure-url":
        response.put("model_file", officialModelUrl(STRONGEST_NAME).replace("https:", "http:"));
        break;
      case "different-file":
        response.put("model_file", officialModelUrl(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL));
        break;
      case "query-url":
        response.put("model_file", officialModelUrl(STRONGEST_NAME) + "?download=1");
        break;
      case "missing-sha":
        response.remove("model_file_sha256");
        break;
      case "malformed-sha":
        response.put("model_file_sha256", "z".repeat(64));
        break;
      case "numeric-sha":
        response.put("model_file_sha256", new java.math.BigInteger("1".repeat(64)));
        break;
      case "zero-size":
        response.put("model_file_bytes", 0L);
        break;
      case "negative-size":
        response.put("model_file_bytes", -1L);
        break;
      case "fractional-size":
        response.put("model_file_bytes", 1000.5);
        break;
      case "overflow-size":
        response.put("model_file_bytes", new java.math.BigInteger("9223372036854775808"));
        break;
      case "string-size":
        response.put("model_file_bytes", "1000");
        break;
      case "missing-size":
        response.remove("model_file_bytes");
        break;
      case "missing-date":
        response.remove("created_at");
        break;
      case "invalid-date":
        response.put("created_at", "2026-02-30");
        break;
      case "invalid-timestamp":
        response.put("created_at", "2026-09-25Tnot-a-time");
        break;
      default:
        assertEquals("not-json", variant);
    }
    String body = variant.equals("not-json") ? "<html>browsable api</html>" : response.toString();
    assertThrows(IOException.class, () -> KataGoAutoSetupHelper.parseOfficialStrongest(body));
  }

  @Test
  void officialStrongestLeadsCatalogAndTakesOnlineRatingByFile() throws Exception {
    // Eight preferred families fill the table choices, so the b11 rows are never selected;
    // pinned rows must still take their rating from the full table.
    StringBuilder html = new StringBuilder("<table class=\"table mt-3\">");
    for (String family : List.of("b28", "b40", "b60", "b20", "b18", "b15", "b10", "b6")) {
      html.append(officialRow(officialModel(family, 1), "2026-06-01", "13000 Elo"));
    }
    html.append(officialRow(STRONGEST_NAME, "2026-09-25", "14603.4 Elo"))
        .append(
            officialRow(
                KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL, "2026-09-25", "14594.3 Elo"))
        .append("</table>");
    try (RoutesFixtureServer server =
        RoutesFixtureServer.start(
            "/strongest/",
            strongestJson(
                STRONGEST_NAME, officialModelUrl(STRONGEST_NAME), 262039869L, STRONGEST_SHA256),
            "/networks/",
            html.toString())) {
      String previous = System.getProperty("lizzie.katago.networks.url");
      String previousStrongest = System.getProperty("lizzie.katago.strongest.url");
      try {
        System.setProperty("lizzie.katago.networks.url", server.url("/networks/"));
        System.setProperty("lizzie.katago.strongest.url", server.url("/strongest/"));
        List<KataGoAutoSetupHelper.RemoteWeightInfo> weights =
            KataGoAutoSetupHelper.fetchOfficialWeights();

        assertEquals(12, weights.size());
        KataGoAutoSetupHelper.RemoteWeightInfo first = weights.get(0);
        assertTrue(first.officialStrongest);
        assertEquals(STRONGEST_NAME, first.modelName);
        assertEquals("14603.4 Elo", first.eloRating);
        assertEquals(STRONGEST_SHA256, first.sha256);
        KataGoAutoSetupHelper.RemoteWeightInfo catalogB11 = weights.get(1);
        assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL, catalogB11.modelName);
        assertEquals(KataGoAutoSetupHelper.TransformerTier.STRONGEST, catalogB11.transformerTier);
        assertTrue(catalogB11.recommended);
        assertFalse(catalogB11.officialStrongest);
        assertEquals("14594.3 Elo", catalogB11.eloRating);
      } finally {
        restoreProperty("lizzie.katago.networks.url", previous);
        restoreProperty("lizzie.katago.strongest.url", previousStrongest);
      }
    }
  }

  @Test
  void officialStrongestMatchingCatalogFileKeepsPinnedIntegrity() throws Exception {
    String catalogName = KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL;
    KataGoAutoSetupHelper.RemoteWeightInfo strongest =
        KataGoAutoSetupHelper.parseOfficialStrongest(
            strongestJson(catalogName, officialModelUrl(catalogName), 1000L, "0".repeat(64)));

    List<KataGoAutoSetupHelper.RemoteWeightInfo> rows =
        KataGoAutoSetupHelper.withOfficialStrongest(
            KataGoAutoSetupHelper.officialTransformerWeights(), strongest);

    assertEquals(3, rows.size());
    KataGoAutoSetupHelper.RemoteWeightInfo merged = rows.get(0);
    assertTrue(merged.officialStrongest);
    assertEquals(KataGoAutoSetupHelper.TransformerTier.STRONGEST, merged.transformerTier);
    assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_SHA256, merged.sha256);
    assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_SIZE_BYTES, merged.sizeBytes);
    assertTrue(merged.recommended);
    assertEquals(officialModelUrl(catalogName), merged.downloadUrl);
  }

  @Test
  void strongestRefreshDoesNotChangeTheDefaultOrPersistAStaleOnlineResult() throws Exception {
    Path root = Files.createTempDirectory("katago-strongest-refresh");
    Files.writeString(root.resolve("config.txt"), "{\"user-setting\":\"preserve\"}");
    try (RoutesFixtureServer server =
        RoutesFixtureServer.start(
            "/strongest/",
            strongestJson(STRONGEST_NAME, officialModelUrl(STRONGEST_NAME), 1000L, STRONGEST_SHA256),
            "/networks/",
            "<table class=\"table mt-3\"></table>")) {
      withUserDirAndConfig(root, () -> {
        byte[] savedConfig = Files.readAllBytes(root.resolve("config.txt"));
        String memoryConfig = Lizzie.config.config.toString();
        withOfficialWeightSources(server.url("/networks/"), server.url("/strongest/"), () -> {
          assertTrue(KataGoAutoSetupHelper.fetchOfficialWeights().get(0).officialStrongest);
          KataGoAutoSetupHelper.RemoteWeightInfo recommended =
              KataGoAutoSetupHelper.fetchRecommendedWeight();
          assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL, recommended.modelName);
          assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_SHA256, recommended.sha256);

          server.respondAt("/strongest/", 200, "<html>unavailable API</html>");
          List<KataGoAutoSetupHelper.RemoteWeightInfo> refreshed =
              KataGoAutoSetupHelper.fetchOfficialWeights();
          assertEquals(3, refreshed.size());
          assertTrue(refreshed.stream().noneMatch(info -> info.officialStrongest));
          assertTrue(refreshed.stream().noneMatch(info -> info.modelName.equals(STRONGEST_NAME)));
          assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL, refreshed.get(0).modelName);
        });
        assertEquals(memoryConfig, Lizzie.config.config.toString());
        assertArrayEquals(savedConfig, Files.readAllBytes(root.resolve("config.txt")));
        assertFalse(Files.exists(root.resolve("weights").resolve(STRONGEST_NAME + ".bin.gz")));
      });
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 304, 500})
  void strongestMetadataRejectsRedirectsAndNonSuccessfulResponses(int status) throws Exception {
    String validJson =
        strongestJson(STRONGEST_NAME, officialModelUrl(STRONGEST_NAME), 1000L, STRONGEST_SHA256);
    try (RoutesFixtureServer server =
        RoutesFixtureServer.start(
            "/strongest/", validJson,
            "/networks/", "<table class=\"table mt-3\"></table>")) {
      java.util.concurrent.atomic.AtomicInteger redirectRequests =
          new java.util.concurrent.atomic.AtomicInteger();
      server.server.createContext("/redirected/", exchange -> {
        redirectRequests.incrementAndGet();
        byte[] bytes = validJson.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) { body.write(bytes); }
      });
      server.server.removeContext("/strongest/");
      server.server.createContext("/strongest/", exchange -> {
        exchange.getResponseHeaders().set("Location", server.url("/redirected/"));
        byte[] bytes = validJson.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 304 ? -1 : bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
          if (status != 304) body.write(bytes);
        }
      });
      withOfficialWeightSources(server.url("/networks/"), server.url("/strongest/"), () -> {
        List<KataGoAutoSetupHelper.RemoteWeightInfo> weights =
            KataGoAutoSetupHelper.fetchOfficialWeights();
        assertEquals(3, weights.size());
        assertTrue(weights.stream().noneMatch(info -> info.officialStrongest));
        assertEquals(0, redirectRequests.get(), "metadata must come directly from the official API");
      });
    }
  }

  private static void withOfficialWeightSources(
      String networksUrl, String strongestUrl, ThrowingRunnable action) throws Exception {
    String previousNetworks = System.getProperty("lizzie.katago.networks.url");
    String previousStrongest = System.getProperty("lizzie.katago.strongest.url");
    try {
      System.setProperty("lizzie.katago.networks.url", networksUrl);
      System.setProperty("lizzie.katago.strongest.url", strongestUrl);
      action.run();
    } finally {
      restoreProperty("lizzie.katago.networks.url", previousNetworks);
      restoreProperty("lizzie.katago.strongest.url", previousStrongest);
    }
  }

  @Test
  void officialWeightChoicesKeepTwoPerPreferredFamilyAndPrioritizeBadges() throws Exception {
    String latestModel = officialModel("b28", 3);
    String strongestModel = officialModel("b40", 3);
    StringBuilder html =
        new StringBuilder()
            .append("<span>Strongest confidently-rated network:</span>")
            .append(officialLink(strongestModel))
            .append("<span>Latest network:</span>")
            .append(officialLink(latestModel))
            .append("<table class=\"table mt-3\">");
    for (String family : List.of("b6", "b10", "b15", "b18", "b20", "b28", "b40", "b60", "b80")) {
      for (int version = 1; version <= 3; version++) {
        String model = officialModel(family, version);
        html.append("<tr>")
            .append("<td>")
            .append(model)
            .append("</td>")
            .append("<td>2026-06-")
            .append(10 + version)
            .append("</td>")
            .append("<td>")
            .append(15000 + version)
            .append(" Elo</td>")
            .append("<td>")
            .append(officialLink(model))
            .append("</td>")
            .append("</tr>");
      }
    }
    html.append("</table>");

    List<KataGoAutoSetupHelper.RemoteWeightInfo> choices =
        KataGoAutoSetupHelper.parseOfficialWeights(html.toString());

    assertEquals(16, choices.size());
    assertEquals(
        List.of(
            "b28", "b28", "b40", "b40", "b60", "b60", "b20", "b20", "b18", "b18", "b15", "b15",
            "b10", "b10", "b6", "b6"),
        choices.stream().map(KataGoAutoSetupHelperTest::officialFamily).toList());
    assertTrue(
        choices.stream().anyMatch(info -> info.modelName.equals(latestModel) && info.latest));
    assertTrue(
        choices.stream()
            .anyMatch(info -> info.modelName.equals(strongestModel) && info.recommended));
    assertFalse(choices.stream().anyMatch(info -> officialFamily(info).equals("b80")));
  }

  @Test
  void officialWeightChoicesNoLongerReserveTheLegacyDefaultZhizi() throws Exception {
    String latestModel = officialModel("b28", 3);
    String olderModel = officialModel("b28", 2);
    String bundledModel = "kata1-zhizi-b28c512nbt-muonfd2";
    String html =
        new StringBuilder()
            .append("<span>Latest network:</span>")
            .append(officialLink(latestModel))
            .append("<table class=\"table mt-3\">")
            .append(officialRow(latestModel, "2026-06-28", "14180 Elo"))
            .append(officialRow(olderModel, "2026-06-20", "14130 Elo"))
            .append(officialRow(bundledModel, "2026-03-22", "14158 Elo"))
            .append("</table>")
            .toString();

    List<KataGoAutoSetupHelper.RemoteWeightInfo> choices =
        KataGoAutoSetupHelper.parseOfficialWeights(html);
    List<KataGoAutoSetupHelper.RemoteWeightInfo> family =
        choices.stream().filter(info -> officialFamily(info).equals("b28")).toList();

    assertEquals(2, family.size());
    assertTrue(family.stream().anyMatch(info -> info.modelName.equals(latestModel) && info.latest));
    assertTrue(family.stream().anyMatch(info -> info.modelName.equals(olderModel)));
    assertFalse(family.stream().anyMatch(info -> info.modelName.equals(bundledModel)));
  }

  @Test
  void transformerWeightDownloadResumesAndVerifiesChecksum() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-transformer-resume");
    byte[] modelBytes = repeatedBytes(32 * 1024, (byte) 23);
    int partialSize = 7 * 1024;
    try (RangeFixtureServer server = RangeFixtureServer.start(modelBytes)) {
      withUserDirAndConfig(
          tempRoot,
          () -> {
            Path weightsDir = Files.createDirectories(tempRoot.resolve("weights"));
            Files.write(
                weightsDir.resolve("model.bin.gz.part"),
                java.util.Arrays.copyOf(modelBytes, partialSize));
            KataGoAutoSetupHelper.RemoteWeightInfo info =
                new KataGoAutoSetupHelper.RemoteWeightInfo(
                    "Transformer",
                    "fixture-transformer",
                    server.url(),
                    "2026-07-29",
                    "",
                    true,
                    true,
                    sha256(modelBytes),
                    modelBytes.length,
                    "1.17.0",
                    KataGoAutoSetupHelper.TransformerTier.BALANCED);

            Path downloaded = KataGoAutoSetupHelper.downloadWeight(info, null);

            assertEquals("bytes=" + partialSize + "-", server.lastRangeHeader());
            assertArrayEquals(modelBytes, Files.readAllBytes(downloaded));
            assertFalse(Files.exists(weightsDir.resolve("model.bin.gz.part")));
          });
    }
  }

  @Test
  void cleanlyTruncatedTransformerDownloadKeepsPartialForNextResume() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-transformer-truncated");
    byte[] modelBytes = repeatedBytes(32 * 1024, (byte) 29);
    int partialSize = 7 * 1024;
    try (FixtureServer truncated =
            FixtureServer.start(java.util.Arrays.copyOf(modelBytes, partialSize));
        RangeFixtureServer resumed = RangeFixtureServer.start(modelBytes)) {
      withUserDirAndConfig(
          tempRoot,
          () -> {
            KataGoAutoSetupHelper.RemoteWeightInfo firstAttempt =
                transformerFixtureWeight(truncated.url(), modelBytes);

            assertThrows(
                IOException.class, () -> KataGoAutoSetupHelper.downloadWeight(firstAttempt, null));
            Path partial = tempRoot.resolve("weights").resolve("model.bin.gz.part");
            assertArrayEquals(
                java.util.Arrays.copyOf(modelBytes, partialSize), Files.readAllBytes(partial));

            Path downloaded =
                KataGoAutoSetupHelper.downloadWeight(
                    transformerFixtureWeight(resumed.url(), modelBytes), null);

            assertEquals("bytes=" + partialSize + "-", resumed.lastRangeHeader());
            assertArrayEquals(modelBytes, Files.readAllBytes(downloaded));
            assertFalse(Files.exists(partial));
          });
    }
  }

  @Test
  void transformerWeightChecksumFailureDeletesDamagedPartialFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-transformer-bad-sha");
    byte[] modelBytes = repeatedBytes(16 * 1024, (byte) 31);
    try (FixtureServer server = FixtureServer.start(modelBytes)) {
      withUserDirAndConfig(
          tempRoot,
          () -> {
            KataGoAutoSetupHelper.RemoteWeightInfo info =
                new KataGoAutoSetupHelper.RemoteWeightInfo(
                    "Transformer",
                    "fixture-transformer",
                    server.url(),
                    "2026-07-29",
                    "",
                    true,
                    true,
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    modelBytes.length,
                    "1.17.0",
                    KataGoAutoSetupHelper.TransformerTier.BALANCED);

            assertThrows(IOException.class, () -> KataGoAutoSetupHelper.downloadWeight(info, null));
            assertFalse(Files.exists(tempRoot.resolve("weights").resolve("model.bin.gz")));
            assertFalse(Files.exists(tempRoot.resolve("weights").resolve("model.bin.gz.part")));
          });
    }
  }

  @Test
  void existingVerifiedWeightEmitsSuccessfulExistingFileStage() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-weight-existing-diag");
    Path logDir = Files.createTempDirectory("katago-weight-existing-logs");
    byte[] modelBytes = repeatedBytes(16 * 1024, (byte) 19);
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(logDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    try {
      ListAppender<ILoggingEvent> events = attachDiagnostics();
      withUserDirAndConfig(
          tempRoot,
          () -> {
            Path weightsDir = Files.createDirectories(tempRoot.resolve("weights"));
            Files.write(weightsDir.resolve("model.bin.gz"), modelBytes);
            KataGoAutoSetupHelper.RemoteWeightInfo info =
                transformerFixtureWeight("http://127.0.0.1:1/model.bin.gz", modelBytes);

            Path downloaded = KataGoAutoSetupHelper.downloadWeight(info, null);

            assertEquals(
                weightsDir.resolve("model.bin.gz").toAbsolutePath().normalize(),
                downloaded.toAbsolutePath().normalize());
            assertArrayEquals(modelBytes, Files.readAllBytes(downloaded));
          });
      String logs = formattedDiagnostics(events);
      assertTrue(logs.contains("operation=weight-download"), logs);
      assertTrue(logs.contains("stage=existing-file"), logs);
      assertTrue(logs.contains("outcome=success"), logs);
      assertTrue(logs.contains("durationMs="), logs);
      assertFalse(logs.contains("outcome=failed"), logs);
      assertFalse(logs.contains("stage=http-download"), logs);
      assertFalse(logs.contains("stage=move"), logs);
    } finally {
      LoggingRuntime.resetForTests();
    }
  }

  @Test
  void weightChecksumFailureStopsAtVerifyStage() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-weight-verify-diag");
    Path logDir = Files.createTempDirectory("katago-weight-verify-logs");
    byte[] modelBytes = repeatedBytes(16 * 1024, (byte) 31);
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(logDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    try (FixtureServer server = FixtureServer.start(modelBytes)) {
      ListAppender<ILoggingEvent> events = attachDiagnostics();
      withUserDirAndConfig(
          tempRoot,
          () -> {
            KataGoAutoSetupHelper.RemoteWeightInfo info =
                new KataGoAutoSetupHelper.RemoteWeightInfo(
                    "Transformer",
                    "fixture-transformer",
                    server.url(),
                    "2026-07-29",
                    "",
                    true,
                    true,
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    modelBytes.length,
                    "1.17.0",
                    KataGoAutoSetupHelper.TransformerTier.BALANCED);

            assertThrows(IOException.class, () -> KataGoAutoSetupHelper.downloadWeight(info, null));
          });
      String logs = formattedDiagnostics(events);
      assertTrue(logs.contains("operation=weight-download"), logs);
      assertTrue(logs.contains("stage=http-download outcome=success"), logs);
      assertTrue(logs.contains("stage=verify outcome=failed"), logs);
      assertTrue(logs.contains("reason=checksum-mismatch"), logs);
      assertFalse(logs.contains("stage=move"), logs);
      assertFalse(logs.contains("0000000000000000"), logs);
      assertFalse(logs.contains(server.url()), logs);
    } finally {
      LoggingRuntime.resetForTests();
    }
  }

  @Test
  void quickAnalysisModelDownloadResumesIntoHiddenDirectoryAndEnablesIt() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-quick-model-resume");
    byte[] modelBytes = repeatedBytes(24 * 1024, (byte) 37);
    int partialSize = 6 * 1024;
    try (RangeFixtureServer server = RangeFixtureServer.start(modelBytes)) {
      withQuickAnalysisDownloadProperties(
          server.url(KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_FILE_NAME),
          sha256(modelBytes),
          modelBytes.length,
          () ->
              withUserDirAndConfig(
                  tempRoot,
                  () -> {
                    Path modelDir =
                        Files.createDirectories(tempRoot.resolve("quick-analysis-models"));
                    Path partial =
                        modelDir.resolve(
                            KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_FILE_NAME + ".part");
                    Files.write(partial, java.util.Arrays.copyOf(modelBytes, partialSize));

                    Path downloaded = KataGoAutoSetupHelper.downloadQuickAnalysisModel(null, null);
                    KataGoAutoSetupHelper.QuickAnalysisModelStatus status =
                        KataGoAutoSetupHelper.inspectQuickAnalysisModel();
                    KataGoAutoSetupHelper.SetupSnapshot snapshot =
                        KataGoAutoSetupHelper.inspectLocalSetup();

                    assertEquals("bytes=" + partialSize + "-", server.lastRangeHeader());
                    assertArrayEquals(modelBytes, Files.readAllBytes(downloaded));
                    assertTrue(downloaded.startsWith(modelDir));
                    assertTrue(status.isInstalled());
                    assertTrue(status.isEnabled());
                    assertFalse(snapshot.weightCandidates.contains(downloaded));
                    assertEquals(
                        downloaded.toString(),
                        Lizzie.config.uiConfig.optString("katago-quick-analysis-model-path"));
                    assertTrue(
                        Lizzie.config.uiConfig.getBoolean(
                            "quick-analysis-lightweight-model-enabled"));
                  }));
    }
  }

  @Test
  void quickAnalysisModelChecksumFailureDoesNotEnableOrExposePartialFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-quick-model-bad-sha");
    byte[] modelBytes = repeatedBytes(12 * 1024, (byte) 41);
    try (RangeFixtureServer server = RangeFixtureServer.start(modelBytes)) {
      withQuickAnalysisDownloadProperties(
          server.url(KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_FILE_NAME),
          "0000000000000000000000000000000000000000000000000000000000000000",
          modelBytes.length,
          () ->
              withUserDirAndConfig(
                  tempRoot,
                  () -> {
                    assertThrows(
                        IOException.class,
                        () -> KataGoAutoSetupHelper.downloadQuickAnalysisModel(null, null));
                    Path modelDir = tempRoot.resolve("quick-analysis-models");
                    assertFalse(
                        Files.exists(
                            modelDir.resolve(
                                KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_FILE_NAME)));
                    assertFalse(
                        Files.exists(
                            modelDir.resolve(
                                KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_FILE_NAME + ".part")));
                    assertFalse(Lizzie.config.quickAnalysisLightweightModelEnabled);
                  }));
    }
  }

  @Test
  void quickAnalysisCommandUsesHiddenModelWithoutEditingNormalEngineList() throws Exception {
    Path root = Files.createTempDirectory("katago-quick-model-command");
    byte[] modelBytes = repeatedBytes(16 * 1024, (byte) 43);
    Path engine = touch(root.resolve("engine").resolve(testKataGoBinaryName()));
    Path gtp = touch(root.resolve("engine").resolve("configs").resolve("gtp.cfg"));
    Path analysis = touch(root.resolve("engine").resolve("configs").resolve("analysis.cfg"));
    Path activeWeight = touch(root.resolve("weights").resolve("main.bin.gz"));
    Path quickModel =
        writeModel(
            root.resolve("quick-analysis-models")
                .resolve(KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_FILE_NAME),
            modelBytes);

    withQuickAnalysisDownloadProperties(
        "http://127.0.0.1/not-used/" + KataGoAutoSetupHelper.QUICK_ANALYSIS_MODEL_FILE_NAME,
        sha256(modelBytes),
        modelBytes.length,
        () ->
            withUserDirAndConfig(
                root,
                () -> {
                  EngineData configured =
                      engineData("Primary KataGo", engine, gtp, activeWeight, true);
                  Utils.saveEngineSettings(new ArrayList<>(List.of(configured)));
                  Lizzie.config.uiConfig.put("default-engine", 0);
                  Lizzie.config.uiConfig.put(
                      "katago-quick-analysis-model-path", quickModel.toString());
                  Lizzie.config.quickAnalysisLightweightModelEnabled = true;
                  Lizzie.config.analysisReuseCurrentEngine = false;

                  String command =
                      KataGoAutoSetupHelper.resolveQuickAnalysisEngineCommand().orElseThrow();

                  assertTrue(command.contains(" analysis "));
                  assertTrue(command.contains(quickModel.toString()));
                  assertTrue(command.contains(analysis.toString()));
                  assertEquals(1, Utils.getEngineData().size());
                  assertEquals(configured.commands, Utils.getEngineData().get(0).commands);

                  Lizzie.config.analysisReuseCurrentEngine = true;
                  assertTrue(KataGoAutoSetupHelper.resolveQuickAnalysisEngineCommand().isEmpty());
                }));
  }

  private static KataGoAutoSetupHelper.RemoteWeightInfo transformerFixtureWeight(
      String url, byte[] modelBytes) throws Exception {
    return new KataGoAutoSetupHelper.RemoteWeightInfo(
        "Transformer",
        "fixture-transformer",
        url,
        "2026-07-29",
        "",
        true,
        true,
        sha256(modelBytes),
        modelBytes.length,
        "1.17.0",
        KataGoAutoSetupHelper.TransformerTier.BALANCED);
  }

  @Test
  void importWeightCopiesToLocalWeightsWithoutChangingPreferredWeight() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-import-weight");
    Path source = Files.write(tempRoot.resolve("custom.bin.gz"), new byte[] {1, 2, 3, 4});

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("katago-preferred-weight-path", "old.bin.gz");

          Path imported = KataGoAutoSetupHelper.importWeight(source);

          assertTrue(imported.startsWith(tempRoot.resolve("weights")));
          assertTrue(Files.isRegularFile(imported));
          assertEquals(
              "old.bin.gz", Lizzie.config.uiConfig.optString("katago-preferred-weight-path"));
          assertFalse(imported.equals(source.toAbsolutePath().normalize()));
        });
  }

  @Test
  void importedWeightDoesNotReplaceWeightConfiguredByDefaultEngine() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-import-keeps-active");
    Path appRoot = Files.createDirectories(tempRoot.resolve("portable with spaces"));
    Path workDir = Files.createDirectories(appRoot.resolve("user-data"));
    Path engine =
        touch(
            appRoot
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Files.writeString(
        appRoot.resolve("engines").resolve("katago").resolve("VERSION.txt"),
        "Model source: kata1-zhizi-b28c512nbt-muonfd2.bin.gz\n");
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    Path bundledWeight = touch(appRoot.resolve("weights").resolve("default.bin.gz"));
    try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(bundledWeight))) {
      output.write(
          "kata1-zhizi-b28c512nbt-muonfd2\n15\n22\n19\n".getBytes(StandardCharsets.US_ASCII));
    }
    Path source = touch(tempRoot.resolve("incoming").resolve("default.bin.gz"));

    withProcessDirAndConfig(
        appRoot,
        workDir,
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          engines.add(engineData("KataGo Bundled", engine, gtpConfig, bundledWeight, true));
          Utils.saveEngineSettings(engines);
          Lizzie.config.uiConfig.put("default-engine", 0);

          assertEquals(bundledWeight, KataGoAutoSetupHelper.inspectLocalSetup().activeWeightPath);
          assertEquals(
              "zhizi 28B muonfd2", KataGoAutoSetupHelper.resolveWeightDisplayName(bundledWeight));

          Path imported = KataGoAutoSetupHelper.importWeight(source);
          KataGoAutoSetupHelper.SetupSnapshot refreshed = KataGoAutoSetupHelper.inspectLocalSetup();

          assertTrue(Files.isRegularFile(imported));
          assertTrue(refreshed.weightCandidates.contains(imported));
          assertEquals(bundledWeight, refreshed.activeWeightPath);
          assertFalse(imported.equals(refreshed.activeWeightPath));
          assertEquals("default.bin.gz", KataGoAutoSetupHelper.resolveWeightDisplayName(imported));
        });
  }

  @Test
  void importHumanSlModelCopiesToSeparateDirectoryAndDoesNotChangeActiveWeight() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-import-humansl");
    Path source = Files.write(tempRoot.resolve("custom-human.bin.gz"), new byte[2 * 1024 * 1024]);
    Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("katago-preferred-weight-path", weight.toString());

          Path imported = KataGoAutoSetupHelper.importHumanSlModel(source);
          KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
          KataGoAutoSetupHelper.HumanSlModelStatus status =
              KataGoAutoSetupHelper.inspectHumanSlModel();

          assertTrue(imported.startsWith(tempRoot.resolve("human-sl-models")));
          assertTrue(Files.isRegularFile(imported));
          assertEquals(weight, snapshot.activeWeightPath);
          assertEquals(
              weight.toString(), Lizzie.config.uiConfig.optString("katago-preferred-weight-path"));
          assertEquals(
              imported.toString(), Lizzie.config.uiConfig.optString("katago-human-sl-model-path"));
          assertTrue(status.isInstalled());
          assertEquals(imported, status.modelPath);
        });
  }

  @Test
  void inspectHumanSlModelUsesRememberedPathBeforeDirectoryScan() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-status");
    Path first = touchModel(tempRoot.resolve("human-sl-models").resolve("first-human.bin.gz"));
    Path remembered = touchModel(tempRoot.resolve("external").resolve("remembered-human.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("katago-human-sl-model-path", remembered.toString());

          KataGoAutoSetupHelper.HumanSlModelStatus status =
              KataGoAutoSetupHelper.inspectHumanSlModel();

          assertTrue(status.isInstalled());
          assertEquals(remembered, status.modelPath);
          assertEquals(remembered, status.candidates.get(0));
          assertTrue(status.candidates.contains(first));
        });
  }

  @Test
  void inspectHumanSlModelPrefersBundledDefaultFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-bundled");
    Path olderCustom =
        touchModel(tempRoot.resolve("human-sl-models").resolve("custom-human.bin.gz"));
    byte[] officialBytes = repeatedBytes(4096, (byte) 3);
    Path bundled =
        writeModel(
            tempRoot
                .resolve("human-sl-models")
                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME),
            officialBytes);

    withUserDirAndConfig(
        tempRoot,
        () ->
            withHumanSlDownloadProperties(
                "http://127.0.0.1/model.bin.gz",
                sha256(officialBytes),
                officialBytes.length,
                () -> {
                  KataGoAutoSetupHelper.HumanSlModelStatus status =
                      KataGoAutoSetupHelper.inspectHumanSlModel();

                  assertTrue(status.isInstalled());
                  assertEquals(bundled, status.modelPath);
                  assertEquals(bundled, status.candidates.get(0));
                  assertTrue(status.candidates.contains(olderCustom));
                }));
  }

  @Test
  void inspectHumanSlModelFindsBundledFileFromAppRootWithoutEngine() throws Exception {
    Path appRoot = Files.createTempDirectory("katago-humansl-app-root");
    Path workDir = Files.createDirectories(appRoot.resolve("user-data"));
    Path processDir = Files.createDirectories(workDir.resolve("cwd"));
    byte[] officialBytes = repeatedBytes(4096, (byte) 5);
    Path bundled =
        writeModel(
            appRoot
                .resolve("human-sl-models")
                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME),
            officialBytes);

    withProcessDirAndConfig(
        processDir,
        workDir,
        () ->
            withHumanSlDownloadProperties(
                "http://127.0.0.1/model.bin.gz",
                sha256(officialBytes),
                officialBytes.length,
                () -> {
                  KataGoAutoSetupHelper.HumanSlModelStatus status =
                      KataGoAutoSetupHelper.inspectHumanSlModel();

                  assertTrue(status.isInstalled());
                  assertEquals(bundled, status.modelPath);
                  assertEquals(bundled, status.candidates.get(0));
                }));
  }

  @Test
  void inspectLocalSetupKeepsAppRootWhenWorkingDirHasHumanSlModels() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-app-root-priority");
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path workDir = Files.createDirectories(appRoot.resolve("user-data"));
    Path processDir = Files.createDirectories(workDir.resolve("cwd"));
    Files.createDirectories(workDir.resolve("human-sl-models"));
    Path engine =
        touch(
            appRoot
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfig = touch(configDir.resolve("analysis.cfg"));
    touch(appRoot.resolve("weights").resolve("default.bin.gz"));
    Path customWeight = touch(workDir.resolve("weights").resolve("custom.bin.gz"));
    Path humanSlSource =
        Files.write(tempRoot.resolve("custom-human.bin.gz"), new byte[2 * 1024 * 1024]);

    withProcessDirAndConfig(
        processDir,
        workDir,
        () -> {
          Lizzie.config.uiConfig.put("katago-preferred-weight-path", customWeight.toString());

          Path importedModel = KataGoAutoSetupHelper.importHumanSlModel(humanSlSource);
          KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
          KataGoAutoSetupHelper.HumanSlModelStatus humanSlStatus =
              KataGoAutoSetupHelper.inspectHumanSlModel();

          assertEquals(workDir.toAbsolutePath().normalize(), snapshot.workingDir);
          assertEquals(appRoot.toAbsolutePath().normalize(), snapshot.appRoot);
          assertEquals(engine, snapshot.enginePath);
          assertEquals(gtpConfig, snapshot.gtpConfigPath);
          assertEquals(analysisConfig, snapshot.analysisConfigPath);
          assertEquals(customWeight, snapshot.activeWeightPath);
          assertTrue(importedModel.startsWith(workDir.resolve("human-sl-models")));
          assertEquals(
              importedModel.toString(),
              Lizzie.config.uiConfig.optString("katago-human-sl-model-path"));
          assertTrue(humanSlStatus.isInstalled());
          assertEquals(importedModel, humanSlStatus.modelPath);
        });
  }

  @Test
  void downloadingHumanSlAfterCustomWeightKeepsCompleteAppRoot() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-download-root-priority");
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path workDir = Files.createDirectories(appRoot.resolve("user-data"));
    Path processDir = Files.createDirectories(workDir.resolve("cwd"));
    Path engine =
        touch(
            appRoot
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    touch(appRoot.resolve("weights").resolve("default.bin.gz"));
    Path customWeight = touch(workDir.resolve("weights").resolve("custom.bin.gz"));
    byte[] modelBytes = repeatedBytes(4096, (byte) 11);

    try (FixtureServer server = FixtureServer.start(modelBytes)) {
      withProcessDirAndConfig(
          processDir,
          workDir,
          () ->
              withHumanSlDownloadProperties(
                  server.url(),
                  sha256(modelBytes),
                  modelBytes.length,
                  () -> {
                    Lizzie.config.uiConfig.put(
                        "katago-preferred-weight-path", customWeight.toString());

                    Path downloaded = KataGoAutoSetupHelper.downloadHumanSlModel(null);
                    KataGoAutoSetupHelper.SetupSnapshot snapshot =
                        KataGoAutoSetupHelper.inspectLocalSetup();

                    assertTrue(downloaded.startsWith(workDir.resolve("human-sl-models")));
                    assertEquals(appRoot.toAbsolutePath().normalize(), snapshot.appRoot);
                    assertEquals(engine, snapshot.enginePath);
                    assertEquals(gtpConfig, snapshot.gtpConfigPath);
                    assertEquals(customWeight, snapshot.activeWeightPath);
                  }));
    }
  }

  @Test
  void humanSlCandidatesPreferWorkingDirAncestorOverSeparateEngineRoot() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-candidate-roots");
    Path portableRoot = Files.createDirectories(tempRoot.resolve("portable"));
    Path workDir = Files.createDirectories(portableRoot.resolve("user-data"));
    Path selectedAppRoot = Files.createDirectories(tempRoot.resolve("engine-app"));
    Path portableModel =
        touchModel(
            portableRoot
                .resolve("human-sl-models")
                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME));
    Path selectedRootModel =
        touchModel(
            selectedAppRoot
                .resolve("human-sl-models")
                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME));

    List<Path> candidates =
        KataGoAutoSetupHelper.collectHumanSlModelCandidates(workDir, selectedAppRoot);

    assertEquals(portableModel, candidates.get(0));
    assertTrue(candidates.contains(selectedRootModel));
  }

  @Test
  void downloadHumanSlModelVerifiesChecksumAndRemembersPath() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-download");
    byte[] modelBytes = repeatedBytes(4096, (byte) 7);
    try (FixtureServer server = FixtureServer.start(modelBytes)) {
      withUserDirAndConfig(
          tempRoot,
          () ->
              withHumanSlDownloadProperties(
                  server.url(),
                  sha256(modelBytes),
                  modelBytes.length,
                  () -> {
                    Path downloaded = KataGoAutoSetupHelper.downloadHumanSlModel(null);
                    KataGoAutoSetupHelper.HumanSlModelStatus status =
                        KataGoAutoSetupHelper.inspectHumanSlModel();

                    assertEquals(modelBytes.length, Files.size(downloaded));
                    assertEquals(
                        downloaded.toString(),
                        Lizzie.config.uiConfig.optString("katago-human-sl-model-path"));
                    assertTrue(status.isInstalled());
                    assertEquals(downloaded, status.modelPath);
                  }));
    }
  }

  @Test
  void downloadHumanSlModelRejectsChecksumMismatchAndDeletesPartialFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-download-bad");
    byte[] modelBytes = repeatedBytes(4096, (byte) 9);
    try (FixtureServer server = FixtureServer.start(modelBytes)) {
      withUserDirAndConfig(
          tempRoot,
          () ->
              withHumanSlDownloadProperties(
                  server.url(),
                  "0000000000000000000000000000000000000000000000000000000000000000",
                  modelBytes.length,
                  () -> {
                    assertThrows(
                        IOException.class, () -> KataGoAutoSetupHelper.downloadHumanSlModel(null));

                    Path modelsDir = tempRoot.resolve("human-sl-models");
                    assertFalse(
                        Files.exists(
                            modelsDir.resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME)));
                    assertFalse(
                        Files.exists(
                            modelsDir.resolve(
                                KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME + ".part")));
                    assertFalse(KataGoAutoSetupHelper.inspectHumanSlModel().isInstalled());
                  }));
    }
  }

  @Test
  void humanSlDownloadDefaultsToMirrorAndKeepsExplicitOverrideIsolated() throws Exception {
    String previous = System.getProperty("lizzie.humansl.model.url");
    try {
      System.clearProperty("lizzie.humansl.model.url");
      assertEquals(
          java.util.Arrays.asList(
              "https://download.goagent.top/models/humansl/b18c384nbt-humanv0.bin.gz",
              KataGoAutoSetupHelper.HUMAN_SL_MODEL_ORIGIN_URL),
          KataGoAutoSetupHelper.humanSlModelDownloadUrls());
      System.setProperty("lizzie.humansl.model.url", "http://127.0.0.1/model");
      assertEquals(
          java.util.Collections.singletonList("http://127.0.0.1/model"),
          KataGoAutoSetupHelper.humanSlModelDownloadUrls());
    } finally {
      restoreProperty("lizzie.humansl.model.url", previous);
    }
  }

  @Test
  void humanSlFallsBackAfterHttpFailureOrCorruptMirrorAndReusesVerifiedModel() throws Exception {
    byte[] bytes = repeatedBytes(4096, (byte) 7);
    try (ErrorFixtureServer unavailable = ErrorFixtureServer.start();
        FixtureServer corrupt = FixtureServer.start(repeatedBytes(4096, (byte) 8));
        FixtureServer shortFile = FixtureServer.start(repeatedBytes(128, (byte) 7));
        FixtureServer origin = FixtureServer.start(bytes)) {
      for (String mirror :
          java.util.Arrays.asList(unavailable.url(), corrupt.url(), shortFile.url())) {
        Path root = Files.createTempDirectory("humansl-mirror-fallback");
        withUserDirAndConfig(
            root,
            () ->
                withHumanSlDownloadProperties(
                    origin.url(),
                    sha256(bytes),
                    bytes.length,
                    () -> {
                      Path downloaded =
                          KataGoAutoSetupHelper.downloadHumanSlModel(
                              null, null, java.util.Arrays.asList(mirror, origin.url()));
                      assertEquals(sha256(bytes), sha256(Files.readAllBytes(downloaded)));
                      assertFalse(
                          Files.exists(
                              downloaded.resolveSibling(downloaded.getFileName() + ".part")));
                      assertEquals(
                          downloaded.toString(),
                          Lizzie.config.uiConfig.optString("katago-human-sl-model-path"));
                      assertEquals(
                          downloaded,
                          KataGoAutoSetupHelper.downloadHumanSlModel(
                              null, null, java.util.Collections.singletonList(unavailable.url())));
                    }));
      }
    }
  }

  @Test
  void cancellingHumanSlDownloadDoesNotStartFallbackOrInstallPartialFile() throws Exception {
    byte[] bytes = repeatedBytes(32768, (byte) 7);
    Path root = Files.createTempDirectory("humansl-mirror-cancel");
    try (FixtureServer mirror = FixtureServer.start(bytes)) {
      withUserDirAndConfig(
          root,
          () ->
              withHumanSlDownloadProperties(
                  mirror.url(),
                  sha256(bytes),
                  bytes.length,
                  () -> {
                    KataGoAutoSetupHelper.DownloadSession session =
                        new KataGoAutoSetupHelper.DownloadSession();
                    assertThrows(
                        KataGoAutoSetupHelper.DownloadCancelledException.class,
                        () ->
                            KataGoAutoSetupHelper.downloadHumanSlModel(
                                (name, downloaded, total) -> session.cancel(),
                                session,
                                java.util.Arrays.asList(
                                    mirror.url(), "invalid fallback must not be accessed")));
                    assertFalse(
                        Files.exists(
                            root.resolve("human-sl-models")
                                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME)));
                    assertFalse(
                        Files.exists(
                            root.resolve("human-sl-models")
                                .resolve(
                                    KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME + ".part")));
                  }));
    }
  }

  @Test
  void inspectHumanSlModelRejectsTruncatedOfficialModel() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-truncated");
    Files.createDirectories(tempRoot.resolve("human-sl-models"));
    Files.write(
        tempRoot.resolve("human-sl-models").resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME),
        new byte[] {1, 2, 3, 4});

    withUserDirAndConfig(
        tempRoot, () -> assertFalse(KataGoAutoSetupHelper.inspectHumanSlModel().isInstalled()));
  }

  @Test
  void inspectLocalSetupUsesConfiguredWorkDirectoryInsteadOfProcessDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-configured-workdir");
    Path workDir = Files.createDirectories(tempRoot.resolve("portable").resolve("user-data"));
    Path processDir = Files.createDirectories(tempRoot.resolve("outside-process-dir"));
    Path engine =
        touch(
            workDir
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configs =
        Files.createDirectories(workDir.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configs.resolve("gtp.cfg"));
    Path weight = touch(workDir.resolve("weights").resolve("default.bin.gz"));

    withProcessDirAndConfig(
        processDir,
        workDir,
        () -> {
          KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();

          assertEquals(workDir.toAbsolutePath().normalize(), snapshot.workingDir);
          assertEquals(engine, snapshot.enginePath);
          assertEquals(gtpConfig, snapshot.gtpConfigPath);
          assertEquals(weight, snapshot.activeWeightPath);
        });
  }

  @Test
  void startupRepairDoesNotRewriteTensorRtProfileToCuda() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-tensorrt-repair");
    Path cudaEngine =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64")
                .resolve("katago.exe"));
    Path tensorRtEngine =
        touch(
            tempRoot
                .resolve("runtime")
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-nvidia-tensorrt")
                .resolve("katago.exe"));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfig = touch(configDir.resolve("analysis.cfg"));
    Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          engines.add(engineData("KataGo Bundled", cudaEngine, gtpConfig, weight, false));
          engines.add(engineData("KataGo TensorRT", tensorRtEngine, gtpConfig, weight, true));
          Utils.saveEngineSettings(engines);
          Lizzie.config.uiConfig.put("default-engine", 1);
          Lizzie.config.uiConfig.put(
              "analysis-engine-command",
              quote(tensorRtEngine)
                  + " analysis -model "
                  + quote(weight)
                  + " -config "
                  + quote(analysisConfig)
                  + " -quit-without-waiting");

          assertFalse(KataGoAutoSetupHelper.repairBrokenBundledCommandsIfNeeded());
          assertFalse(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());

          ArrayList<EngineData> repairedEngines = Utils.getEngineData();
          assertEquals("KataGo TensorRT", repairedEngines.get(1).name);
          assertTrue(repairedEngines.get(1).isDefault);
          assertTrue(repairedEngines.get(1).commands.contains("windows-x64-nvidia-tensorrt"));
          assertEquals(1, Lizzie.config.uiConfig.optInt("default-engine"));
          assertTrue(
              Lizzie.config
                  .uiConfig
                  .optString("analysis-engine-command")
                  .contains("windows-x64-nvidia-tensorrt"));
        });
  }

  @Test
  void startupRepairLeavesRemoteComputeEngineAlone() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-remote-compute-repair");

    withUserDirAndConfig(
        tempRoot,
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          EngineData remote = new EngineData();
          remote.index = 0;
          remote.name = "自建算力 · 127.0.0.1:8765";
          remote.commands = "remote-compute://custom-websocket";
          remote.isDefault = true;
          engines.add(remote);
          Utils.saveEngineSettings(engines);
          Lizzie.config.uiConfig.put("default-engine", 0);

          assertNull(Utils.resolveExistingExecutable(remote.commands));
          assertFalse(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());

          ArrayList<EngineData> repairedEngines = Utils.getEngineData();
          assertEquals("remote-compute://custom-websocket", repairedEngines.get(0).commands);
          assertTrue(repairedEngines.get(0).isDefault);
          assertEquals(0, Lizzie.config.uiConfig.optInt("default-engine"));
        });
  }

  @Test
  void autoSetupRefreshPreservesUserEngineKomiAndSettings() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-preserve-engine-settings");
    Path engine =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          EngineData autoSetupEngine =
              engineData(
                  KataGoAutoSetupHelper.getAutoSetupEngineName(), engine, gtpConfig, weight, false);
          autoSetupEngine.komi = 6.5F;
          autoSetupEngine.preload = true;
          autoSetupEngine.width = 13;
          autoSetupEngine.height = 13;
          autoSetupEngine.initialCommand = "kata-set-rules chinese";
          engines.add(autoSetupEngine);
          Utils.saveEngineSettings(engines);
          Lizzie.config.uiConfig.put("autoload-default", false);
          Lizzie.config.uiConfig.put("autoload-empty", false);
          Lizzie.config.uiConfig.put("autoload-last", false);

          KataGoAutoSetupHelper.applyAutoSetup(KataGoAutoSetupHelper.inspectLocalSetup(), false);

          ArrayList<EngineData> refreshedEngines = Utils.getEngineData();
          EngineData refreshed = refreshedEngines.get(0);
          assertEquals(6.5F, refreshed.komi);
          assertTrue(refreshed.preload);
          assertEquals(13, refreshed.width);
          assertEquals(13, refreshed.height);
          assertEquals("kata-set-rules chinese", refreshed.initialCommand);
          assertFalse(refreshed.isDefault);
        });
  }

  @Test
  void autoSetupReusesRenamedManagedEntryAndProtectsRepurposedCommand() throws Exception {
    Path root = Files.createTempDirectory("katago-renamed-profile");
    Path engine =
        touch(
            root.resolve("engines/katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path gtp = touch(root.resolve("engines/katago/configs/gtp.cfg"));
    touch(root.resolve("engines/katago/configs/analysis.cfg"));
    Path weight = touch(root.resolve("weights/default.bin.gz"));
    withUserDirAndConfig(
        root,
        () -> {
          Lizzie.config.uiConfig.put("autoload-empty", true);
          var snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
          KataGoAutoSetupHelper.applyAutoSetup(snapshot, false);
          ArrayList<EngineData> entries = Utils.getEngineData();
          EngineData managed = entries.get(0);
          String id = managed.id;
          managed.name = "Notebook KataGo";
          managed.komi = 6.5F;
          managed.preload = true;
          EngineData namesake = engineData("KataGo Auto Setup", engine, gtp, weight, false);
          namesake.commands += " -override-config numSearchThreads=2";
          entries.add(namesake);
          Utils.saveEngineSettings(entries);
          KataGoAutoSetupHelper.applyAutoSetup(snapshot, false);
          entries = Utils.getEngineData();
          assertEquals(2, entries.size());
          assertEquals(id, entries.get(0).id);
          assertEquals("Notebook KataGo", entries.get(0).name);
          assertEquals(6.5F, entries.get(0).komi);
          assertTrue(entries.get(0).preload);
          assertEquals(namesake.commands, entries.get(1).commands);

          entries.get(0).commands += " -override-config numSearchThreads=3";
          String customCommand = entries.get(0).commands;
          Utils.saveEngineSettings(entries);
          Lizzie.config.uiConfig.put("default-engine", 0);
          assertFalse(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());
          KataGoAutoSetupHelper.applyAutoSetup(snapshot, false);
          entries = Utils.getEngineData();
          assertEquals(3, entries.size());
          assertEquals(customCommand, entries.get(0).commands);
          assertEquals(id, entries.get(0).id);
          assertEquals(namesake.commands, entries.get(1).commands);
          assertTrue(BundledKataGoProfile.isManaged(entries.get(2)));
        });
  }

  @Test
  void startupFallbackDoesNotClaimUnownedMissingCommands() throws Exception {
    Path root = Files.createTempDirectory("katago-stale-custom-startup");
    Path engine =
        touch(
            root.resolve("engines/katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path gtp = touch(root.resolve("engines/katago/configs/gtp.cfg"));
    touch(root.resolve("engines/katago/configs/analysis.cfg"));
    Path weight = touch(root.resolve("weights/default.bin.gz"));
    withUserDirAndConfig(
        root,
        () -> {
          EngineData bundled = engineData("Notebook KataGo", engine, gtp, weight, false);
          BundledKataGoProfile.claim(bundled);
          Path staleEngine =
              root.resolve("removed/engines/katago")
                  .resolve(detectTestPlatformDir())
                  .resolve(testKataGoBinaryName());
          for (String customCommand :
              List.of(
                  engineData("", staleEngine, gtp, weight, false).commands
                      + " -override-config numSearchThreads=2",
                  "\"" + root.resolve("external/katago") + "\" gtp")) {
            EngineData custom = engineData("KataGo Auto Setup", engine, gtp, weight, true);
            custom.id = "custom-stale";
            custom.commands = customCommand;
            custom.komi = 6.5F;
            Utils.saveEngineSettings(new ArrayList<>(List.of(custom, bundled)));
            Lizzie.config.uiConfig.put("default-engine", 0);
            for (int restart = 0; restart < 2; restart++) {
              assertFalse(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());
              ArrayList<EngineData> saved = Utils.getEngineData();
              assertEquals(2, saved.size());
              assertEquals("custom-stale", saved.get(0).id);
              assertEquals("KataGo Auto Setup", saved.get(0).name);
              assertEquals(customCommand, saved.get(0).commands);
              assertEquals(6.5F, saved.get(0).komi);
              assertFalse(BundledKataGoProfile.isManaged(saved.get(0)));
              assertTrue(BundledKataGoProfile.isManaged(saved.get(1)));
            }
          }
          EngineData legacy = engineData("Legacy launcher", engine, gtp, weight, true);
          legacy.commands = "java -jar \"" + root.resolve("missing-launcher.jar") + "\"";
          Utils.saveEngineSettings(new ArrayList<>(List.of(legacy)));
          Lizzie.config.uiConfig.put("default-engine", 0);
          assertTrue(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());
          assertEquals(1, Utils.getEngineData().size());
          assertTrue(BundledKataGoProfile.isManaged(Utils.getEngineData().get(0)));
          assertEquals("Legacy launcher", Utils.getEngineData().get(0).name);
        });
  }

  @Test
  void implicitStartupModePersistsAUsableDefaultDuringTheFirstAutoSetup() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-first-auto-setup-default");
    Path engine =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          EngineData existing =
              engineData(
                  KataGoAutoSetupHelper.getAutoSetupEngineName(), engine, gtpConfig, weight, false);
          Utils.saveEngineSettings(new ArrayList<>(List.of(existing)));
          Lizzie.config.uiConfig.remove("autoload-default");
          Lizzie.config.uiConfig.remove("autoload-empty");
          Lizzie.config.uiConfig.remove("autoload-last");
          Lizzie.config.uiConfig.put("default-engine", -1);
          Lizzie.config
              .config
              .put("ui", Lizzie.config.uiConfig)
              .put("leelaz", Lizzie.config.leelazConfig);

          KataGoAutoSetupHelper.SetupResult result =
              KataGoAutoSetupHelper.applyAutoSetup(
                  KataGoAutoSetupHelper.inspectLocalSetup(), false);

          ArrayList<EngineData> saved = Utils.getEngineData();
          assertEquals(0, result.engineIndex);
          assertEquals(0, Lizzie.config.uiConfig.optInt("default-engine", -1));
          assertTrue(Lizzie.config.uiConfig.optBoolean("autoload-default"));
          assertFalse(Lizzie.config.uiConfig.optBoolean("autoload-empty"));
          assertFalse(Lizzie.config.uiConfig.optBoolean("autoload-last"));
          assertTrue(saved.get(0).isDefault);

          org.json.JSONObject persisted =
              new org.json.JSONObject(Files.readString(Path.of(Lizzie.config.getConfigFilePath())));
          assertEquals(0, persisted.getJSONObject("ui").getInt("default-engine"));
          assertTrue(persisted.getJSONObject("ui").getBoolean("autoload-default"));
          assertTrue(
              persisted
                  .getJSONObject("leelaz")
                  .getJSONArray("engine-settings-list")
                  .getJSONObject(0)
                  .getBoolean("isDefault"));
        });
  }

  @Test
  void explicitStartupModesRemainUntouchedByBackgroundAutoSetupRepair() throws Exception {
    for (boolean[] mode :
        List.of(
            new boolean[] {true, false, false},
            new boolean[] {false, true, false},
            new boolean[] {false, false, false},
            new boolean[] {false, false, true})) {
      Path tempRoot = Files.createTempDirectory("katago-explicit-startup-mode");
      Path engine =
          touch(
              tempRoot
                  .resolve("engines")
                  .resolve("katago")
                  .resolve(detectTestPlatformDir())
                  .resolve(testKataGoBinaryName()));
      Path configDir =
          Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
      Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
      touch(configDir.resolve("analysis.cfg"));
      Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

      withUserDirAndConfig(
          tempRoot,
          () -> {
            EngineData existing = engineData("Custom default", engine, gtpConfig, weight, true);
            Lizzie.config.uiConfig.put("autoload-default", mode[0]);
            Lizzie.config.uiConfig.put("autoload-empty", mode[1]);
            Lizzie.config.uiConfig.put("autoload-last", mode[2]);
            Lizzie.config.uiConfig.put("default-engine", 0);
            Utils.saveEngineSettings(new ArrayList<>(List.of(existing)));

            KataGoAutoSetupHelper.applyAutoSetup(KataGoAutoSetupHelper.inspectLocalSetup(), false);

            assertEquals(mode[0], Lizzie.config.uiConfig.optBoolean("autoload-default"));
            assertEquals(mode[1], Lizzie.config.uiConfig.optBoolean("autoload-empty"));
            assertEquals(mode[2], Lizzie.config.uiConfig.optBoolean("autoload-last"));
            assertEquals(0, Lizzie.config.uiConfig.optInt("default-engine"));
          });
    }
  }

  @Test
  void addingDownloadedWeightsKeepsIndependentReusableEngineProfiles() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-independent-weight-engines");
    touch(
        tempRoot
            .resolve("engines")
            .resolve("katago")
            .resolve(detectTestPlatformDir())
            .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    Path firstWeight = touch(tempRoot.resolve("weights").resolve("model-a.bin.gz"));
    Path secondWeight = touch(tempRoot.resolve("weights").resolve("model-b.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("autoload-default", false);
          Lizzie.config.uiConfig.put("autoload-empty", true);
          Lizzie.config.uiConfig.put("autoload-last", false);
          KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
          KataGoAutoSetupHelper.SetupResult initial =
              KataGoAutoSetupHelper.applyAutoSetup(snapshot.withActiveWeight(firstWeight));
          KataGoAutoSetupHelper.SetupResult first =
              KataGoAutoSetupHelper.addWeightEngineProfile(snapshot.withActiveWeight(firstWeight));
          KataGoAutoSetupHelper.SetupResult second =
              KataGoAutoSetupHelper.addWeightEngineProfile(snapshot.withActiveWeight(secondWeight));
          KataGoAutoSetupHelper.SetupResult repeated =
              KataGoAutoSetupHelper.addWeightEngineProfile(snapshot.withActiveWeight(secondWeight));

          ArrayList<EngineData> engines = Utils.getEngineData();
          assertEquals(initial.engineIndex, first.engineIndex);
          assertFalse(first.createdEngine);
          assertTrue(second.createdEngine);
          assertFalse(repeated.createdEngine);
          assertEquals(second.engineIndex, repeated.engineIndex);
          assertEquals(2, engines.size());
          assertEquals(KataGoAutoSetupHelper.getAutoSetupEngineName(),
              engines.get(first.engineIndex).name,
              "Selecting the same model must retain the existing owned profile's name");
          assertTrue(engines.get(first.engineIndex).commands.contains(firstWeight.toString()));
          assertTrue(engines.get(second.engineIndex).commands.contains(secondWeight.toString()));
          assertFalse(engines.get(first.engineIndex).isDefault);
          assertTrue(engines.get(second.engineIndex).isDefault);
          assertEquals(second.engineIndex, Lizzie.config.uiConfig.optInt("default-engine"));
          assertFalse(Lizzie.config.uiConfig.optBoolean("autoload-default"));
          assertTrue(Lizzie.config.uiConfig.optBoolean("autoload-empty"));
          assertFalse(Lizzie.config.uiConfig.optBoolean("autoload-last"));
          assertTrue(
              Lizzie.config
                  .uiConfig
                  .optString("analysis-engine-command")
                  .contains(secondWeight.toString()));

          KataGoAutoSetupHelper.SetupResult selectedAgain =
              KataGoAutoSetupHelper.applyAutoSetup(snapshot.withActiveWeight(firstWeight));
          ArrayList<EngineData> refreshed = Utils.getEngineData();
          assertEquals(first.engineIndex, selectedAgain.engineIndex);
          assertEquals(2, refreshed.size());
          assertTrue(refreshed.get(first.engineIndex).isDefault);
          assertTrue(refreshed.get(second.engineIndex).commands.contains(secondWeight.toString()));
        });
  }

  @Test
  void autoSetupDoesNotOverwriteUserManagedAnalysisCommand() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-user-analysis-command");
    touch(
        tempRoot
            .resolve("engines")
            .resolve("katago")
            .resolve(detectTestPlatformDir())
            .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    Path weight = touch(tempRoot.resolve("weights").resolve("model.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          String customCommand = "custom-katago analysis -model custom.bin.gz";
          Lizzie.config.analysisEngineCommand = customCommand;
          Lizzie.config.analysisEngineCommandCustomized = true;
          Lizzie.config.uiConfig.put("analysis-engine-command", customCommand);
          Lizzie.config.uiConfig.put("analysis-engine-command-customized", true);

          KataGoAutoSetupHelper.applyAutoSetup(
              KataGoAutoSetupHelper.inspectLocalSetup().withActiveWeight(weight));

          assertEquals(customCommand, Lizzie.config.analysisEngineCommand);
          assertEquals(customCommand, Lizzie.config.uiConfig.optString("analysis-engine-command"));
        });
  }

  private static EngineData engineData(
      String name, Path enginePath, Path configPath, Path weightPath, boolean isDefault) {
    EngineData data = new EngineData();
    data.name = name;
    data.commands =
        quote(enginePath) + " gtp -model " + quote(weightPath) + " -config " + quote(configPath);
    data.preload = false;
    data.komi = 7.5F;
    data.width = 19;
    data.height = 19;
    data.isDefault = isDefault;
    data.useJavaSSH = false;
    data.ip = "";
    data.port = "";
    data.userName = "";
    data.password = "";
    data.useKeyGen = false;
    data.keyGenPath = "";
    data.initialCommand = "";
    return data;
  }

  private static String officialModel(String family, int version) {
    return "kata1-" + family + "c512nbt-s" + version + "-d" + version;
  }

  private static String officialLink(String model) {
    return "<a href=\"https://example.com/" + model + ".bin.gz\">" + model + "</a>";
  }

  private static String officialRow(String model, String releaseDate, String elo) {
    return "<tr><td>"
        + model
        + "</td><td>"
        + releaseDate
        + "</td><td>"
        + elo
        + "</td><td>"
        + officialLink(model)
        + "</td></tr>";
  }

  private static String officialFamily(KataGoAutoSetupHelper.RemoteWeightInfo info) {
    int start = info.modelName.indexOf("-b");
    int end = info.modelName.indexOf('c', start + 2);
    return start >= 0 && end > start ? info.modelName.substring(start + 1, end) : "";
  }

  private static Path touch(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    return Files.write(path, new byte[0]).toAbsolutePath().normalize();
  }

  private static Path touchModel(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    return Files.write(path, new byte[2 * 1024 * 1024]).toAbsolutePath().normalize();
  }

  private static Path writeModel(Path path, byte[] bytes) throws Exception {
    Files.createDirectories(path.getParent());
    return Files.write(path, bytes).toAbsolutePath().normalize();
  }

  private static byte[] repeatedBytes(int size, byte value) {
    byte[] bytes = new byte[size];
    java.util.Arrays.fill(bytes, value);
    return bytes;
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath().normalize().toString() + "\"";
  }

  private static String quoteLiteral(Path path) {
    return "\"" + path.toString() + "\"";
  }

  private static String detectTestPlatformDir() {
    String osName = System.getProperty("os.name", "").toLowerCase();
    String arch = System.getProperty("os.arch", "").toLowerCase();
    boolean isArm = arch.contains("aarch64") || arch.contains("arm64");
    boolean is64 = arch.contains("64");
    if (osName.contains("win")) {
      return is64 ? "windows-x64" : "windows-x86";
    }
    if (osName.contains("mac") || osName.contains("darwin")) {
      return isArm ? "macos-arm64" : "macos-amd64";
    }
    return is64 ? "linux-x64" : "linux-x86";
  }

  private static String testKataGoBinaryName() {
    return System.getProperty("os.name", "").toLowerCase().contains("win")
        ? "katago.exe"
        : "katago";
  }

  private static void withUserDirAndConfig(Path userDir, ThrowingRunnable action) throws Exception {
    withProcessDirAndConfig(userDir, userDir, action);
  }

  private static void withProcessDirAndConfig(
      Path processDir, Path configDir, ThrowingRunnable action) throws Exception {
    String previousUserDir = System.getProperty("user.dir");
    Config previousConfig = Lizzie.config;
    try {
      System.setProperty("user.dir", processDir.toString());
      Lizzie.config = ConfigTestHelper.createForTests(configDir);
      Lizzie.config.config = new org.json.JSONObject();
      Lizzie.config.leelazConfig = new org.json.JSONObject();
      Lizzie.config.uiConfig = new org.json.JSONObject();
      action.run();
    } finally {
      if (previousUserDir == null) {
        System.clearProperty("user.dir");
      } else {
        System.setProperty("user.dir", previousUserDir);
      }
      Lizzie.config = previousConfig;
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void withHumanSlDownloadProperties(
      String url, String sha256, long size, ThrowingRunnable action) throws Exception {
    String previousUrl = System.getProperty("lizzie.humansl.model.url");
    String previousSha = System.getProperty("lizzie.humansl.model.sha256");
    String previousSize = System.getProperty("lizzie.humansl.model.size");
    try {
      System.setProperty("lizzie.humansl.model.url", url);
      System.setProperty("lizzie.humansl.model.sha256", sha256);
      System.setProperty("lizzie.humansl.model.size", Long.toString(size));
      action.run();
    } finally {
      restoreProperty("lizzie.humansl.model.url", previousUrl);
      restoreProperty("lizzie.humansl.model.sha256", previousSha);
      restoreProperty("lizzie.humansl.model.size", previousSize);
    }
  }

  private static void withQuickAnalysisDownloadProperties(
      String url, String sha256, long size, ThrowingRunnable action) throws Exception {
    String previousUrl = System.getProperty("lizzie.quick-analysis.model.url");
    String previousSha = System.getProperty("lizzie.quick-analysis.model.sha256");
    String previousSize = System.getProperty("lizzie.quick-analysis.model.size");
    try {
      System.setProperty("lizzie.quick-analysis.model.url", url);
      System.setProperty("lizzie.quick-analysis.model.sha256", sha256);
      System.setProperty("lizzie.quick-analysis.model.size", Long.toString(size));
      action.run();
    } finally {
      restoreProperty("lizzie.quick-analysis.model.url", previousUrl);
      restoreProperty("lizzie.quick-analysis.model.sha256", previousSha);
      restoreProperty("lizzie.quick-analysis.model.size", previousSize);
    }
  }

  private static ListAppender<ILoggingEvent> attachDiagnostics() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger logger = (Logger) LoggerFactory.getLogger(LogCategories.DIAGNOSTICS);
    logger.addAppender(appender);
    return appender;
  }

  private static String formattedDiagnostics(ListAppender<ILoggingEvent> events) {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : events.list) {
      text.append(event.getFormattedMessage()).append('\n');
    }
    return text.toString();
  }

  private static void restoreProperty(String key, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(key);
      return;
    }
    System.setProperty(key, previousValue);
  }

  private static String sha256(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(bytes);
    StringBuilder builder = new StringBuilder();
    for (byte value : hash) {
      builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return builder.toString();
  }

  private static final class FixtureServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    private FixtureServer(HttpServer server, ExecutorService executor) {
      this.server = server;
      this.executor = executor;
    }

    private static FixtureServer start(byte[] bytes) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      server.createContext(
          "/model.bin.gz",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
              body.write(bytes);
            }
          });
      server.setExecutor(executor);
      server.start();
      return new FixtureServer(server, executor);
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/model.bin.gz";
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private static final class RangeFixtureServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private volatile String lastRangeHeader = "";

    private RangeFixtureServer(HttpServer server, ExecutorService executor) {
      this.server = server;
      this.executor = executor;
    }

    private static RangeFixtureServer start(byte[] bytes) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RangeFixtureServer fixture = new RangeFixtureServer(server, executor);
      server.createContext(
          "/",
          exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            fixture.lastRangeHeader = range == null ? "" : range;
            int start = 0;
            int status = 200;
            if (range != null && range.startsWith("bytes=") && range.endsWith("-")) {
              start = Integer.parseInt(range.substring(6, range.length() - 1));
              status = 206;
              exchange
                  .getResponseHeaders()
                  .set(
                      "Content-Range",
                      "bytes " + start + "-" + (bytes.length - 1) + "/" + bytes.length);
            }
            int length = bytes.length - start;
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(status, length);
            try (OutputStream body = exchange.getResponseBody()) {
              body.write(bytes, start, length);
            }
          });
      server.setExecutor(executor);
      server.start();
      return fixture;
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/model.bin.gz";
    }

    private String url(String fileName) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + fileName;
    }

    private String lastRangeHeader() {
      return lastRangeHeader;
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private static final class RoutesFixtureServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    private RoutesFixtureServer(HttpServer server, ExecutorService executor) {
      this.server = server;
      this.executor = executor;
    }

    private static RoutesFixtureServer start(String... pathAndBody) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      for (int i = 0; i < pathAndBody.length; i += 2) {
        byte[] bytes = pathAndBody[i + 1].getBytes(StandardCharsets.UTF_8);
        server.createContext(
            pathAndBody[i],
            exchange -> {
              exchange.sendResponseHeaders(200, bytes.length);
              try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
              }
            });
      }
      server.setExecutor(executor);
      server.start();
      return new RoutesFixtureServer(server, executor);
    }

    private String url(String path) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void respondAt(String path, int status, String content) {
      server.removeContext(path);
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      server.createContext(path, exchange -> {
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) { body.write(bytes); }
      });
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private static final class ErrorFixtureServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    private ErrorFixtureServer(HttpServer server, ExecutorService executor) {
      this.server = server;
      this.executor = executor;
    }

    private static ErrorFixtureServer start() throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      server.createContext(
          "/networks/",
          exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
          });
      server.setExecutor(executor);
      server.start();
      return new ErrorFixtureServer(server, executor);
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/networks/";
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
