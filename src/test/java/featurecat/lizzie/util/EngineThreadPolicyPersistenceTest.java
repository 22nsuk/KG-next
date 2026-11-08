package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.katago.tuning.KataGoBenchmarkSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EngineThreadPolicyPersistenceTest {
  @TempDir Path root;

  @Test
  void failedCatalogSavePreservesPublishedConfiguration() throws Exception {
    Config previous = Lizzie.config;
    try {
      Lizzie.config = ConfigTestHelper.createForTests(root);
      Lizzie.config.config = new JSONObject();
      Lizzie.config.uiConfig = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      EngineData entry = new EngineData();
      entry.commands = "katago gtp";
      entry.name = "original";
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      String stored = Files.readString(root.resolve("config.txt"));
      var edited = Utils.getEngineData();
      edited.get(0).name = "unsaved";
      Files.move(root.resolve("config.txt"), root.resolve("saved-config"));
      Files.createDirectory(root.resolve("config.txt"));
      Files.writeString(root.resolve("config.txt/blocker"), "occupied");

      assertThrows(java.io.UncheckedIOException.class, () -> Utils.saveEngineSettings(edited));
      assertEquals("original", Utils.getEngineData().get(0).name);
      assertEquals(stored, Files.readString(root.resolve("saved-config")));
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void identicalCommandsKeepIndependentIdentityAcrossReorderAndReload() throws Exception {
    Config previous = Lizzie.config;
    try {
      Lizzie.config = ConfigTestHelper.createForTests(root);
      Lizzie.config.config = new JSONObject();
      Lizzie.config.uiConfig = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      EngineData first = new EngineData();
      first.commands = "katago gtp";
      first.name = "A";
      EngineData second = new EngineData();
      second.commands = first.commands;
      second.name = "B";
      Utils.saveEngineSettings(new ArrayList<>(List.of(first, second)));
      var initial =
          new JSONObject(Files.readString(root.resolve("config.txt")))
              .getJSONObject("leelaz")
              .getJSONArray("engine-settings-list");
      String firstId = initial.getJSONObject(0).getString("id");
      String secondId = initial.getJSONObject(1).getString("id");
      assertNotEquals(firstId, secondId);
      var entries = Utils.getEngineData();
      var moved = entries.remove(0);
      moved.name = "renamed A";
      moved.commands = "katago gtp -config changed.cfg";
      entries.add(moved);
      Utils.saveEngineSettings(entries);
      JSONObject disk = new JSONObject(Files.readString(root.resolve("config.txt")));
      Lizzie.config.leelazConfig = disk.getJSONObject("leelaz");
      var reloaded = Utils.getEngineData();
      assertEquals(secondId, Utils.engineDataToJson(reloaded.get(0)).getString("id"));
      assertEquals(firstId, Utils.engineDataToJson(reloaded.get(1)).getString("id"));
      assertEquals("renamed A", reloaded.get(1).name);
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void measuredSpeedsKeepTheirTrialSettingsAcrossSaveReloadAndSourceChanges() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      try (var input =
          getClass().getResourceAsStream("/featurecat/lizzie/util/benchmark-extra-settings.txt")) {
        Files.write(root.resolve("benchmark-output"), input.readAllBytes());
      }
      EngineData entry = env.entry("Measured settings");
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      var result = KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(entry), null, null);
      assertEquals(420.0, result.visitsPerSecond);
      assertEquals(350.0, result.nnEvalsPerSecond);
      assertEquals(10, result.measuredSettings.searchThreads());
      assertEquals(2, result.measuredSettings.nnServerThreadsPerModel());
      assertEquals(5, result.measuredSettings.maxBatchSize());
      assertTrue(result.hasMeasuredRecommendation());
      JSONObject disk = new JSONObject(Files.readString(root.resolve("config.txt")));
      Lizzie.config.leelazConfig = disk.getJSONObject("leelaz");
      var saved = EngineThreadPolicy.findSavedEntry(entry.id);
      var restored = KataGoRuntimeHelper.getStoredBenchmarkResult(saved);
      assertEquals(result.measuredSettings, restored.measuredSettings);
      assertEquals(result.visitsPerSecond, restored.visitsPerSecond);
      var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(saved);
      assertEquals(
          0.0,
          KataGoRuntimeHelper.getMeasuredBenchmarkSpeed(snapshot),
          "CFG still selects its own thread count, not the recommendation");
      EngineThreadPolicy.saveSource(entry.id, EngineThreadPolicy.Source.BENCHMARK);
      assertEquals(420.0, KataGoRuntimeHelper.getMeasuredBenchmarkSpeed(snapshot));
      assertTrue(KataGoRuntimeHelper.formatBenchmarkResult(restored).contains("nnMaxBatchSize=5"));
      assertTrue(KataGoRuntimeHelper.formatBenchmarkResult(restored).contains("420.0 visits/s"));

      Files.writeString(root.resolve("gtp.cfg"), "numSearchThreads=7\nnnMaxBatchSize=32\n");
      assertEquals(0.0, KataGoRuntimeHelper.getMeasuredBenchmarkSpeed(snapshot));
      assertEquals(
          result.measuredSettings,
          KataGoRuntimeHelper.getStoredBenchmarkResult(EngineThreadPolicy.findSavedEntry(entry.id))
              .measuredSettings);

      Files.delete(root.resolve("benchmark-output"));
      KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(entry), null, null);
      assertFalse(
          EngineThreadPolicy.findSavedEntry(entry.id)
              .threadPolicy
              .has("katago-benchmark-measured-settings"),
          "A new result without a detailed row cannot inherit the preceding measurement"
              + " conditions");
    }
  }

  @Test
  void oldSpeedsKeepTheirRecommendationsWithoutInventingMeasurementSettings() {
    EngineData entry = new EngineData();
    entry.threadPolicy =
        new JSONObject()
            .put("katago-benchmark-threads", 10)
            .put("katago-benchmark-nn-server-threads", 2)
            .put("katago-benchmark-batch-size", 5)
            .put("katago-benchmark-visits-per-second", 386.0);
    var result = KataGoRuntimeHelper.getStoredBenchmarkResult(entry);
    assertNotNull(result);
    assertEquals(10, result.recommendedThreads);
    assertEquals(386.0, result.visitsPerSecond);
    assertNull(result.measuredSettings);
    assertFalse(result.hasMeasuredRecommendation());
    entry.threadPolicy.put(
        "katago-benchmark-measured-settings", new JSONObject().put("source", "UNKNOWN"));
    assertNull(KataGoRuntimeHelper.getStoredBenchmarkResult(entry).measuredSettings);
    JSONObject incomplete = KataGoBenchmarkSettings.threadSweep(10).toJson();
    incomplete.remove("requiresDeviceMapping");
    assertNull(KataGoBenchmarkSettings.fromJson(incomplete));
    incomplete.put("requiresDeviceMapping", "false");
    assertNull(KataGoBenchmarkSettings.fromJson(incomplete));
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4})
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void perThreadGpuAdviceCannotSupplyAnEstimateFromScalarAgreementAlone(int servers)
      throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      String output;
      try (var input =
          getClass().getResourceAsStream("/featurecat/lizzie/util/benchmark-extra-settings.txt")) {
        output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
      StringBuilder deviceAdvice =
          new StringBuilder(
                  "To use this, set the following in your config:\n  numNNServerThreadsPerModel = ")
              .append(servers)
              .append('\n');
      for (int i = 0; i < servers; i++)
        deviceAdvice
            .append("  cudaDeviceToUseThread")
            .append(i)
            .append(" = ")
            .append(1 + i / 2)
            .append('\n');
      output =
          output.replace(
              "To use this, set numNNServerThreadsPerModel = 2 in your config.", deviceAdvice);
      Files.writeString(root.resolve("benchmark-output"), output);
      EngineData entry = env.entry("Explicit GPU device advice");
      entry.threadPolicy = new JSONObject().put("source", "BENCHMARK");
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      var result = KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(entry), null, null);
      assertEquals(420.0, result.visitsPerSecond);
      assertTrue(result.measuredSettings.requiresDeviceMapping());
      Lizzie.config.leelazConfig =
          new JSONObject(Files.readString(root.resolve("config.txt"))).getJSONObject("leelaz");
      var saved = EngineThreadPolicy.findSavedEntry(entry.id);
      assertEquals(
          result.measuredSettings,
          KataGoRuntimeHelper.getStoredBenchmarkResult(saved).measuredSettings);
      var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(saved);
      var launch =
          featurecat.lizzie.util.katago.tuning.KataGoCommandSpec.parse(
              KataGoRuntimeHelper.applyEntryLaunchPolicy(
                  snapshot.sourceArguments, snapshot.enginePath, saved));
      assertEquals("10", launch.overrideValue("numSearchThreads").orElseThrow());
      assertEquals(
          String.valueOf(servers),
          launch.overrideValue("numNNServerThreadsPerModel").orElseThrow());
      assertEquals("5", launch.overrideValue("nnMaxBatchSize").orElseThrow());
      assertEquals(0.0, KataGoRuntimeHelper.getMeasuredBenchmarkSpeed(snapshot));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"numSearchThreads=7", "nnMaxBatchSize=32", "numNNServerThreadsPerModel=4"})
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void explicitSettingsRemainSeparateFromTheMeasuredRecommendation(String override)
      throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      try (var input =
          getClass().getResourceAsStream("/featurecat/lizzie/util/benchmark-extra-settings.txt")) {
        Files.write(root.resolve("benchmark-output"), input.readAllBytes());
      }
      EngineData entry = env.entry("Explicit setting");
      entry.commands += " -override-config " + override;
      entry.threadPolicy = new JSONObject().put("source", "BENCHMARK");
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      var result = KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(entry), null, null);
      var saved = EngineThreadPolicy.findSavedEntry(entry.id);
      assertTrue(result.hasMeasuredRecommendation());
      assertEquals("", EngineThreadPolicy.environmentStatus(saved));
      assertTrue(saved.commands.contains(override));
      assertEquals(
          0.0,
          KataGoRuntimeHelper.getMeasuredBenchmarkSpeed(
              KataGoAutoSetupHelper.inspectSavedEngine(saved)));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"complete", "partial", "nonfinite"})
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void officialAppleWrapperRetainsOnlyCompleteOriginalSearchMeasurements(String row)
      throws Exception {
    var hardwareCache = KataGoRuntimeHelper.class.getDeclaredField("cachedAppleHardwareProfile");
    hardwareCache.setAccessible(true);
    Object previousHardware = hardwareCache.get(null);
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      String output;
      try (var input =
          getClass().getResourceAsStream("/featurecat/lizzie/util/benchmark-extra-settings.txt")) {
        output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
      if (row.equals("partial"))
        output =
            output.replace("6 / 6 positions, visits/s = 386", "3 / 6 positions, visits/s = 386");
      if (row.equals("nonfinite")) output = output.replace("nnEvals/s = 320.0", "nnEvals/s = NaN");
      Files.writeString(root.resolve("benchmark-output"), output);
      EngineData entry = env.entry("Official wrapper");
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      var snapshot =
          KataGoAutoSetupHelper.inspectSavedEngine(EngineThreadPolicy.findSavedEntry(entry.id));
      var method =
          KataGoRuntimeHelper.class.getDeclaredMethod(
              "runOfficialAppleSiliconBenchmark",
              KataGoAutoSetupHelper.SetupSnapshot.class,
              KataGoAutoSetupHelper.ProgressListener.class,
              KataGoAutoSetupHelper.DownloadSession.class);
      method.setAccessible(true);
      var result = (KataGoRuntimeHelper.BenchmarkResult) method.invoke(null, snapshot, null, null);
      assertEquals(row.equals("complete") ? 386.0 : 0.0, result.visitsPerSecond);
      assertFalse(
          result.hasMeasuredRecommendation(),
          "The extra NN/batch recommendation is not the original search measurement");
      if (row.equals("complete")) {
        assertEquals(KataGoBenchmarkSettings.threadSweep(10), result.measuredSettings);
      } else {
        assertNull(result.measuredSettings);
      }
    } finally {
      hardwareCache.set(null, previousHardware);
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void scalarAgreementCannotReuseAGpuMeasurementForAnExplicitAneDevice() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      EngineData entry = env.entry("GPU measurement, explicit ANE launch");
      entry.commands +=
          " -override-config numSearchThreads=10,nnMaxBatchSize=5,"
              + "numNNServerThreadsPerModel=1,metalDeviceToUseModel0Thread0=100";
      var settings =
          new KataGoBenchmarkSettings(
              10, 1, 5, KataGoBenchmarkSettings.Source.EXPERIMENTAL_PROFILE);
      entry.threadPolicy =
          new JSONObject()
              .put("source", "BENCHMARK")
              .put("katago-benchmark-threads", 10)
              .put("katago-benchmark-batch-size", 5)
              .put("katago-benchmark-topology", "GPU 0")
              .put("katago-benchmark-visits-per-second", 450.0)
              .put("katago-benchmark-measured-settings", settings.toJson());
      entry.threadPolicy.put(
          "environment",
          EngineThreadPolicy.environment(KataGoAutoSetupHelper.inspectSavedEngine(entry)));
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      assertTrue(KataGoRuntimeHelper.getStoredBenchmarkResult(entry).hasMeasuredRecommendation());
      assertEquals("", EngineThreadPolicy.environmentStatus(entry));
      assertEquals(
          0.0,
          KataGoRuntimeHelper.getMeasuredBenchmarkSpeed(
              KataGoAutoSetupHelper.inspectSavedEngine(entry)));
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void benchmarkMergesOnlyFrozenEntryAndPreservesLatestChoice() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      EngineData a = env.entry("A");
      EngineData b = env.entry("B");
      Utils.saveEngineSettings(new ArrayList<>(List.of(a, b)));
      var target =
          KataGoRuntimeHelper.captureBenchmarkTarget(
              KataGoAutoSetupHelper.inspectSavedEngine(a), false);
      var result =
          KataGoRuntimeHelper.runBenchmarkAndApply(
              target,
              (status, count, total) -> {
                if (count == 90) {
                  var entries = Utils.getEngineData();
                  var moved = entries.remove(0);
                  moved.name = "renamed A";
                  entries.add(moved);
                  Utils.saveEngineSettings(entries);
                }
              },
              null);
      assertEquals(8, result.recommendedThreads);
      assertEquals(
          EngineThreadPolicy.Source.CFG,
          EngineThreadPolicy.source(EngineThreadPolicy.findSavedEntry(a.id)));
      assertEquals(
          8, EngineThreadPolicy.recommendedThreads(EngineThreadPolicy.findSavedEntry(a.id)));
      assertEquals(
          0, EngineThreadPolicy.recommendedThreads(EngineThreadPolicy.findSavedEntry(b.id)));
      assertFalse(Lizzie.config.uiConfig.has("katago-benchmark-threads"));
      EngineThreadPolicy.saveSource(a.id, EngineThreadPolicy.Source.BENCHMARK);
      var retry =
          KataGoRuntimeHelper.captureBenchmarkTarget(
              KataGoAutoSetupHelper.inspectSavedEngine(EngineThreadPolicy.findSavedEntry(a.id)),
              false);
      KataGoRuntimeHelper.runBenchmarkAndApply(
          retry,
          (status, count, total) -> {
            if (count == 90) {
              try {
                EngineThreadPolicy.saveSource(a.id, EngineThreadPolicy.Source.CFG);
              } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
              }
            }
          },
          null);
      assertEquals(
          EngineThreadPolicy.Source.CFG,
          EngineThreadPolicy.source(EngineThreadPolicy.findSavedEntry(a.id)));
      assertThrows(
          java.io.IOException.class,
          () -> KataGoRuntimeHelper.runBenchmarkAndApply(target, null, null));
      JSONObject disk = new JSONObject(Files.readString(root.resolve("config.txt")));
      Lizzie.config.leelazConfig = disk.getJSONObject("leelaz");
      assertEquals(
          8, EngineThreadPolicy.recommendedThreads(EngineThreadPolicy.findSavedEntry(a.id)));
      assertEquals("renamed A", EngineThreadPolicy.findSavedEntry(a.id).name);
      assertTrue(Files.readString(root.resolve("argv")).contains("benchmark"));
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void failedAndCancelledRerunsKeepRecommendationAndDeletedTargetsStayDeleted() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      EngineData a = env.entry("A");
      Utils.saveEngineSettings(new ArrayList<>(List.of(a)));
      KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(a), null, null);
      EngineThreadPolicy.saveSource(a.id, EngineThreadPolicy.Source.BENCHMARK);
      Files.writeString(root.resolve("fail"), "yes");
      var failure =
          assertThrows(
              java.io.IOException.class,
              () -> KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(a), null, null));
      assertTrue(failure.getMessage().contains("controlled failure"), failure.getMessage());
      assertEquals(
          8, EngineThreadPolicy.recommendedThreads(EngineThreadPolicy.findSavedEntry(a.id)));
      assertEquals(
          EngineThreadPolicy.Source.BENCHMARK,
          EngineThreadPolicy.source(EngineThreadPolicy.findSavedEntry(a.id)));
      Files.delete(root.resolve("fail"));
      var session = new KataGoAutoSetupHelper.DownloadSession();
      assertThrows(
          KataGoAutoSetupHelper.DownloadCancelledException.class,
          () ->
              KataGoRuntimeHelper.runBenchmarkAndApply(
                  env.capture(a),
                  (status, count, total) -> {
                    if (count == 90) session.cancel();
                  },
                  session));
      assertEquals(
          8, EngineThreadPolicy.recommendedThreads(EngineThreadPolicy.findSavedEntry(a.id)));
      var target = env.capture(a);
      assertThrows(
          java.io.IOException.class,
          () ->
              KataGoRuntimeHelper.runBenchmarkAndApply(
                  target,
                  (status, count, total) -> {
                    if (count == 90) Utils.saveEngineSettings(new ArrayList<>());
                  },
                  null));
      assertTrue(Utils.getEngineData().isEmpty());
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void resultSaveFailureLeavesPreviousRecordAndSourceIntact() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      EngineData a = env.entry("A");
      Utils.saveEngineSettings(new ArrayList<>(List.of(a)));
      KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(a), null, null);
      EngineThreadPolicy.saveSource(a.id, EngineThreadPolicy.Source.BENCHMARK);
      var target = env.capture(a);
      String before = EngineThreadPolicy.findSavedEntry(a.id).threadPolicy.toString();
      Files.move(root.resolve("config.txt"), root.resolve("valid-config"));
      Files.createDirectory(root.resolve("config.txt"));
      Files.writeString(root.resolve("config.txt/occupied"), "occupied");
      assertThrows(
          java.io.IOException.class,
          () -> KataGoRuntimeHelper.runBenchmarkAndApply(target, null, null));
      assertEquals(before, EngineThreadPolicy.findSavedEntry(a.id).threadPolicy.toString());
      assertEquals(
          before,
          new JSONObject(Files.readString(root.resolve("valid-config")))
              .getJSONObject("leelaz")
              .getJSONArray("engine-settings-list")
              .getJSONObject(0)
              .getJSONObject("threadPolicy")
              .toString());
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void changedEnvironmentRetainsRecommendationWhileNewSetupHonorsSourceRevision() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      EngineData a = env.entry("A");
      a.threadPolicy =
          new JSONObject().put("source", "CFG").put("sourceRevision", 0).put("initialSetup", true);
      Utils.saveEngineSettings(new ArrayList<>(List.of(a)));
      var target =
          KataGoRuntimeHelper.captureBenchmarkTarget(
              KataGoAutoSetupHelper.inspectSavedEngine(a), true);
      EngineThreadPolicy.saveSource(a.id, EngineThreadPolicy.Source.CFG);
      KataGoRuntimeHelper.runBenchmarkAndApply(target, null, null);
      assertEquals(
          EngineThreadPolicy.Source.CFG,
          EngineThreadPolicy.source(EngineThreadPolicy.findSavedEntry(a.id)));
      Files.writeString(root.resolve("gtp.cfg"), "numSearchThreads=3\n# changed environment");
      EngineData stored = EngineThreadPolicy.findSavedEntry(a.id);
      assertEquals(
          EngineThreadPolicy.message("environmentChanged"),
          EngineThreadPolicy.environmentStatus(stored));
      stored = EngineThreadPolicy.saveSource(a.id, EngineThreadPolicy.Source.BENCHMARK);
      var launch =
          KataGoRuntimeHelper.applyEntryLaunchPolicy(
              Utils.splitCommand(stored.commands), env.engine, stored);
      assertTrue(String.join(" ", launch).contains("numSearchThreads=8"));
      Files.delete(root.resolve("model.bin.gz"));
      assertEquals(
          EngineThreadPolicy.message("environmentUnknown"),
          EngineThreadPolicy.environmentStatus(stored));
      assertEquals(8, EngineThreadPolicy.recommendedThreads(stored));
    }
  }

  @Test
  void attributableLegacyResultMigratesOnceAndKeepsExistingPolicy() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      Files.writeString(root.resolve("analysis.cfg"), "numSearchThreads=3");
      EngineData a = env.entry("A");
      var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(a);
      StringBuilder legacySignature = new StringBuilder();
      for (Path path :
          new Path[] {
            snapshot.enginePath,
            snapshot.gtpConfigPath,
            snapshot.analysisConfigPath,
            snapshot.activeWeightPath
          }) {
        if (path == null) legacySignature.append("|missing");
        else {
          legacySignature.append('|').append(path.toAbsolutePath().normalize());
          if (Files.exists(path))
            legacySignature
                .append(':')
                .append(Files.size(path))
                .append(':')
                .append(Files.getLastModifiedTime(path).toMillis());
          else legacySignature.append(":0:0");
        }
      }
      Lizzie.config
          .uiConfig
          .put("katago-benchmark-threads", 6)
          .put("katago-benchmark-signature", legacySignature.toString())
          .put("autoload-kata-engine-threads", true);
      Lizzie.config.leelazConfig.put(
          "engine-settings-list", new org.json.JSONArray().put(Utils.engineDataToJson(a)));
      EngineData migrated = Utils.normalizeEngineSettings().get(0);
      assertEquals(6, EngineThreadPolicy.recommendedThreads(migrated));
      assertEquals(EngineThreadPolicy.Source.BENCHMARK, EngineThreadPolicy.source(migrated));
      EngineThreadPolicy.saveSource(migrated.id, EngineThreadPolicy.Source.CFG);
      Lizzie.config.uiConfig.put("katago-benchmark-threads", 12);
      var reloaded = Utils.getEngineData().get(0);
      assertEquals(migrated.id, reloaded.id);
      assertEquals(6, EngineThreadPolicy.recommendedThreads(reloaded));
      assertEquals(EngineThreadPolicy.Source.CFG, EngineThreadPolicy.source(reloaded));
    }
  }

  @Test
  void unownedLegacyManualValueDoesNotBecomeBenchmarkHistory() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      EngineData a = env.entry("A");
      EngineData b = env.entry("B");
      Lizzie.config
          .uiConfig
          .put("txt-kata-engine-threads", "12")
          .put("autoload-kata-engine-threads", true);
      Lizzie.config.leelazConfig.put(
          "engine-settings-list",
          new org.json.JSONArray().put(Utils.engineDataToJson(a)).put(Utils.engineDataToJson(b)));
      for (EngineData migrated : Utils.normalizeEngineSettings()) {
        assertEquals(EngineThreadPolicy.Source.CFG, EngineThreadPolicy.source(migrated));
        assertEquals(0, EngineThreadPolicy.recommendedThreads(migrated));
      }
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void explicitNewSetupEnablesOnlyItsFirstSuccessfulRecommendation() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      var snapshot =
          KataGoAutoSetupHelper.inspectSelectedLocalKataGo(
                  env.engine, root.resolve("gtp.cfg"), root.resolve("model.bin.gz"))
              .toSnapshot();
      Files.writeString(root.resolve("analysis.cfg"), "numSearchThreads=3");
      snapshot =
          KataGoAutoSetupHelper.inspectSelectedLocalKataGo(
                  env.engine, root.resolve("gtp.cfg"), root.resolve("model.bin.gz"))
              .toSnapshot();
      var setup = KataGoAutoSetupHelper.applyAutoSetup(snapshot, false);
      assertTrue(setup.createdEngine);
      var target = KataGoRuntimeHelper.captureBenchmarkTarget(setup.snapshot, true);
      KataGoRuntimeHelper.runBenchmarkAndApply(target, null, null);
      assertEquals(
          EngineThreadPolicy.Source.BENCHMARK,
          EngineThreadPolicy.source(EngineThreadPolicy.findSavedEntry(target.entryId)));
      EngineThreadPolicy.saveSource(target.entryId, EngineThreadPolicy.Source.CFG);
      KataGoRuntimeHelper.runBenchmarkAndApply(
          KataGoRuntimeHelper.captureBenchmarkTarget(setup.snapshot, true), null, null);
      assertEquals(
          EngineThreadPolicy.Source.CFG,
          EngineThreadPolicy.source(EngineThreadPolicy.findSavedEntry(target.entryId)));
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void missingExplicitRelativeConfigDoesNotUseAnEngineDirectorySubstitute() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      Path engineDir = Files.createDirectories(root.resolve("other engine"));
      Path executable = Files.copy(env.engine, engineDir.resolve("katago"));
      assertTrue(executable.toFile().setExecutable(true));
      Files.writeString(engineDir.resolve("missing.cfg"), "numSearchThreads=3");
      EngineData entry = env.entry("Explicit missing cfg");
      entry.commands =
          "\""
              + executable
              + "\" gtp -config missing.cfg -model \""
              + root.resolve("model.bin.gz")
              + "\"";
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      java.io.IOException failure =
          assertThrows(
              java.io.IOException.class,
              () -> {
                var target = env.capture(entry);
                KataGoRuntimeHelper.runBenchmarkAndApply(target, null, null);
              });
      assertTrue(failure.getMessage().contains("missing.cfg"), failure.getMessage());
      assertEquals(
          0, EngineThreadPolicy.recommendedThreads(EngineThreadPolicy.findSavedEntry(entry.id)));
      assertFalse(Files.exists(engineDir.resolve("argv")));
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void encryptedCustomCommandKeepsConfigLayersOverridesAndWorkingDirectory() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      Path config = Files.writeString(root.resolve("custom settings.cfg"), "numSearchThreads=2");
      Path layer = Files.writeString(root.resolve("device settings.cfg"), "nnMaxBatchSize=16");
      Path model = Files.writeString(root.resolve("custom model.bin.gz"), "fixture model");
      EngineData entry = env.entry("Custom target");
      String raw =
          "\""
              + env.engine
              + "\" gtp --config=\""
              + config
              + "\" -config \""
              + layer
              + "\" --model=\""
              + model
              + "\" --override-config=\"numSearchThreads=12,nnMaxBatchSize=24,homeDataDir=custom-home\"";
      entry.commands = "encryption||" + Utils.doEncrypt2(raw);
      String savedCommand = entry.commands;
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(entry), null, null);
      List<String> actual = Files.readAllLines(root.resolve("argv"));
      assertEquals("benchmark", actual.get(0));
      assertEquals(
          List.of(config.toString(), layer.toString()),
          java.util.stream.IntStream.range(0, actual.size() - 1)
              .filter(i -> actual.get(i).equals("-config"))
              .mapToObj(i -> actual.get(i + 1))
              .toList());
      assertEquals(model.toString(), actual.get(actual.indexOf("-model") + 1));
      var overrides =
          featurecat.lizzie.util.katago.tuning.KataGoCommandSpec.parse(actual).effectiveOverrides();
      assertFalse(overrides.containsKey("numSearchThreads"));
      assertEquals("24", overrides.get("nnMaxBatchSize"));
      assertEquals("custom-home", overrides.get("homeDataDir"));
      assertEquals(root.toString(), Files.readString(root.resolve("cwd")));
      EngineData saved = EngineThreadPolicy.findSavedEntry(entry.id);
      assertEquals(savedCommand, saved.commands);
      assertEquals(EngineThreadPolicy.Source.CFG, EngineThreadPolicy.source(saved));
      assertEquals(8, EngineThreadPolicy.recommendedThreads(saved));
      assertEquals("numSearchThreads=2", Files.readString(config));
      assertFalse(Files.exists(root.resolve("analysis.cfg")));
      Files.writeString(layer, "nnMaxBatchSize=32\n# changed environment");
      assertEquals(
          EngineThreadPolicy.message("environmentChanged"),
          EngineThreadPolicy.environmentStatus(saved));
      assertEquals(8, EngineThreadPolicy.recommendedThreads(saved));
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void unsuccessfulBenchmarkOutputRetainsItsActualFailureReason() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      EngineData entry = env.entry("Failed output target");
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(entry), null, null);
      Files.writeString(
          env.engine,
          "#!/usr/bin/python3\nprint('ERROR: failed to load model: fixture-device-error')\n");
      java.io.IOException failure =
          assertThrows(
              java.io.IOException.class,
              () -> KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(entry), null, null));
      assertTrue(failure.getMessage().contains("fixture-device-error"), failure.getMessage());
      assertEquals(
          8, EngineThreadPolicy.recommendedThreads(EngineThreadPolicy.findSavedEntry(entry.id)));
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void completedOpenClTuningMayRejectCandidatesButUnfinishedOrFatalRunsCannotCommit()
      throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      EngineData entry = env.entry("First OpenCL benchmark");
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      String tuning =
          "Beginning GPU tuning for NVIDIA GeForce RTX 5070 Ti Laptop GPU modelVersion 15 channels"
              + " 512\n"
              + "ERROR: Could not find any configuration that worked\n"
              + "FP16 tensor core tuning failed for 1x1 convs\n";
      String recommendation = "numSearchThreads = 12: +82 Elo (recommended)\n";
      Files.writeString(
          env.engine,
          "#!/usr/bin/python3\nprint("
              + JSONObject.quote(tuning + "Done tuning\n" + recommendation)
              + ")\n");
      var result = KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(entry), null, null);
      assertEquals(12, result.recommendedThreads);
      for (String failed :
          List.of(
              tuning + recommendation,
              tuning + "ERROR: fatal error\nDone tuning\n" + recommendation,
              tuning + "Done tuning\nFATAL: uncaught exception\n" + recommendation)) {
        Files.writeString(
            env.engine, "#!/usr/bin/python3\nprint(" + JSONObject.quote(failed) + ")\n");
        assertThrows(
            java.io.IOException.class,
            () -> KataGoRuntimeHelper.runBenchmarkAndApply(env.capture(entry), null, null));
        assertEquals(
            12, EngineThreadPolicy.recommendedThreads(EngineThreadPolicy.findSavedEntry(entry.id)));
      }
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void discoveredRelativeCommandRemainsAvailableAndRunsItsSavedInputs() throws Exception {
    try (BenchmarkEnvironment env = new BenchmarkEnvironment()) {
      Files.writeString(root.resolve("analysis.cfg"), "numAnalysisThreads=1");
      EngineData entry = env.entry("Existing relative command");
      entry.commands =
          "\""
              + env.engine
              + "\" gtp -config gtp.cfg -model \""
              + root.resolve("model.bin.gz")
              + "\"";
      entry.isDefault = true;
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      var discovered = KataGoAutoSetupHelper.inspectLocalSetup();
      assertEquals("", KataGoRuntimeHelper.benchmarkUnavailableReason(discovered));
      var target = KataGoRuntimeHelper.captureBenchmarkTarget(discovered, false);
      KataGoRuntimeHelper.runBenchmarkAndApply(target, null, null);
      assertEquals(
          8, EngineThreadPolicy.recommendedThreads(EngineThreadPolicy.findSavedEntry(entry.id)));
      List<String> arguments = Files.readAllLines(root.resolve("argv"));
      assertEquals(
          root.resolve("gtp.cfg").toString(), arguments.get(arguments.indexOf("-config") + 1));
    }
  }

  private final class BenchmarkEnvironment implements AutoCloseable {
    private final Config previous = Lizzie.config;
    private final featurecat.lizzie.analysis.EngineManager previousManager = Lizzie.engineManager;
    private final Path engine;

    private BenchmarkEnvironment() throws Exception {
      Lizzie.config = ConfigTestHelper.createForTests(root);
      Lizzie.config.config = new JSONObject();
      Lizzie.config.uiConfig = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.engineManager = null;
      engine =
          Files.writeString(
              root.resolve("katago"),
              """
              #!/usr/bin/python3
              import pathlib,sys
              root=pathlib.Path(__file__).parent
              (root/'argv').write_text('\\n'.join(sys.argv[1:]))
              (root/'cwd').write_text(str(pathlib.Path.cwd()))
              if (root/'fail').exists():
                  print('controlled failure',flush=True)
                  sys.exit(7)
              if (root/'benchmark-output').exists():
                  print((root/'benchmark-output').read_text(),flush=True)
              else:
                  print('numSearchThreads = 8: (baseline) (recommended)',flush=True)
              """);
      assertTrue(engine.toFile().setExecutable(true));
      Files.writeString(root.resolve("gtp.cfg"), "numSearchThreads=3");
      Files.writeString(root.resolve("model.bin.gz"), "fixture model");
    }

    private EngineData entry(String name) {
      EngineData entry = new EngineData();
      entry.commands =
          "\""
              + engine
              + "\" gtp -config \""
              + root.resolve("gtp.cfg")
              + "\" -model \""
              + root.resolve("model.bin.gz")
              + "\"";
      entry.name = name;
      entry.width = entry.height = 19;
      return entry;
    }

    private KataGoRuntimeHelper.BenchmarkTarget capture(EngineData entry) throws Exception {
      return KataGoRuntimeHelper.captureBenchmarkTarget(
          KataGoAutoSetupHelper.inspectSavedEngine(EngineThreadPolicy.findSavedEntry(entry.id)),
          false);
    }

    @Override
    public void close() {
      Lizzie.config = previous;
      Lizzie.engineManager = previousManager;
    }
  }
}
