package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.BundledKataGoProfile;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.Utils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConfigManagedProfilePreservationTest {
  @TempDir Path temp;

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void everySavedProfileSurvivesPackageCopyOrMoveAndMaintainedStartup(
      boolean portableLayout, boolean movePackage) throws Exception {
    Path previousPackage = temp.resolve("previous 한글 package");
    Path currentPackage = temp.resolve("current 한글 package");
    Path previousApp = createBundle(previousPackage, portableLayout);
    Path previousWork = workDirectory(previousPackage, portableLayout);
    Path currentApp = appDirectory(currentPackage, portableLayout);
    Path currentWork = workDirectory(currentPackage, portableLayout);
    Path legacyWeight =
        touch(previousApp.resolve("weights")
            .resolve(KataGoAutoSetupHelper.LEGACY_DEFAULT_WEIGHT_MODEL + ".bin.gz"));
    Path studyWeight = touch(previousWork.resolve("weights/my study.bin.gz"));
    Path studyConfig = touch(previousWork.resolve("engines/study.cfg"));
    Path studyAnalysis = touch(previousWork.resolve("engines/analysis.cfg"));

    withConfig(previousApp, previousWork, () -> {
      ArrayList<EngineData> original = new ArrayList<>();
      original.add(profile("Legacy selection", executable(previousApp), legacyWeight,
          previousApp.resolve("engines/katago/configs/gtp.cfg"), true));
      original.add(profile("Study selection", executable(previousApp), studyWeight,
          studyConfig, false));
      for (int i = 0; i < original.size(); i++) {
        EngineData entry = original.get(i);
        entry.id = "saved-weight-" + i;
        entry.name = "My selected model " + i;
        entry.preload = true;
        entry.komi = 6.5F;
        entry.width = 13;
        entry.height = 9;
      }
      EngineData edited = profile("Manual", executable(previousApp), legacyWeight,
          previousApp.resolve("engines/katago/configs/gtp.cfg"), false);
      edited.commands += " -override-config numSearchThreads=3";
      original.add(edited);
      EngineData ssh = profile("SSH", executable(previousApp), legacyWeight,
          previousApp.resolve("engines/katago/configs/gtp.cfg"), false);
      ssh.useJavaSSH = true;
      ssh.ip = "example.test";
      original.add(ssh);

      Lizzie.config.uiConfig.put("autoload-default", false)
          .put("autoload-empty", false).put("autoload-last", true)
          .put("default-engine", 0).put("last-engine", 1);
      String analysis = analysisCommand(executable(previousApp), studyWeight, studyAnalysis);
      Lizzie.config.uiConfig.put("analysis-engine-command", analysis)
          .put("analysis-engine-command-customized", false);
      Utils.saveEngineSettings(original);
      Lizzie.config.save();
      ArrayList<EngineData> saved = Utils.getEngineData();

      if (movePackage) {
        Files.move(previousPackage, currentPackage);
      } else {
        copyTree(previousPackage, currentPackage);
      }
      System.setProperty("user.dir", currentApp.toString());
      String expectedAnalysis = analysis.replace(previousPackage.toString(), currentPackage.toString());
      for (int restart = 0; restart < 3; restart++) {
        Lizzie.config = ConfigTestHelper.createBootstrapped(currentWork);
        for (int phase = 0; phase < 2; phase++) {
          if (phase == 1) Utils.applyMaintainedDefaultSettings();
          ArrayList<EngineData> actual = Utils.getEngineData();
          assertEquals(saved.size(), actual.size());
          for (int i = 0; i < saved.size(); i++) {
            EngineData before = saved.get(i);
            EngineData after = actual.get(i);
            assertEquals(before.id, after.id);
            assertEquals(before.name, after.name);
            assertEquals(before.isDefault, after.isDefault);
            assertEquals(before.preload, after.preload);
            assertEquals(before.komi, after.komi);
            assertEquals(before.width, after.width);
            assertEquals(before.height, after.height);
            assertEquals(before.useJavaSSH, after.useJavaSSH);
            assertEquals(before.ip, after.ip);
            assertEquals(
                i < 2
                    ? before.commands.replace(previousPackage.toString(), currentPackage.toString())
                    : before.commands,
                after.commands);
            assertEquals(i < 2, BundledKataGoProfile.isManaged(after));
          }
          assertEquals(0, Lizzie.config.uiConfig.getInt("default-engine"));
          assertEquals(1, Lizzie.config.uiConfig.getInt("last-engine"));
          assertTrue(Lizzie.config.uiConfig.getBoolean("autoload-last"));
          assertFalse(Lizzie.config.uiConfig.getBoolean("autoload-default"));
          assertFalse(Lizzie.config.uiConfig.getBoolean("autoload-empty"));
          assertEquals(expectedAnalysis, Lizzie.config.uiConfig.getString("analysis-engine-command"));
          assertEquals(expectedAnalysis, Lizzie.config.analysisEngineCommand);
          assertFalse(Lizzie.config.analysisEngineCommandCustomized);
        }
        Lizzie.config.save();
      }
      JSONObject persisted = new JSONObject(Files.readString(currentWork.resolve("config.txt")));
      assertEquals(expectedAnalysis,
          persisted.getJSONObject("ui").getString("analysis-engine-command"));
    });
  }

  @Test
  void remoteJavaStartupKeepsItsDefaultSelectionAcrossMaintainedRestarts() throws Exception {
    Path packageRoot = temp.resolve("remote Java startup");
    Path app = createBundle(packageRoot, true);
    Path work = workDirectory(packageRoot, true);
    withConfig(app, work, () -> {
      EngineData local = Utils.getEngineData().get(0);
      local.isDefault = false;
      EngineData remote = new EngineData();
      remote.id = "remote-java-engine";
      remote.name = "My remote Java engine";
      remote.commands = "java -jar remote/engine.jar";
      remote.isDefault = true;
      remote.useJavaSSH = true;
      remote.ip = "engine.example.test";
      remote.port = "2222";
      remote.userName = "fixture-user";
      remote.initialCommand = "cd my-engine";
      remote.width = 13;
      remote.height = 9;
      remote.komi = 6.5F;
      Lizzie.config.uiConfig.put("autoload-default", true)
          .put("autoload-last", false).put("autoload-empty", false)
          .put("default-engine", 0).put("last-engine", 1);
      Utils.saveEngineSettings(new ArrayList<>(List.of(remote, local)));
      Lizzie.config.save();
      JSONObject expectedRemote = new JSONObject(Lizzie.config.leelazConfig
          .getJSONArray("engine-settings-list").getJSONObject(0).toString());
      for (int restart = 0; restart < 3; restart++) {
        Lizzie.config = ConfigTestHelper.createBootstrapped(work);
        Utils.applyMaintainedDefaultSettings();
        ArrayList<EngineData> actual = Utils.getEngineData();
        assertEquals(2, actual.size());
        assertTrue(expectedRemote.similar(Lizzie.config.leelazConfig
            .getJSONArray("engine-settings-list").getJSONObject(0)));
        assertEquals(remote.commands, actual.get(0).commands);
        assertTrue(actual.get(0).useJavaSSH);
        assertTrue(actual.get(0).isDefault);
        assertFalse(actual.get(1).isDefault);
        assertEquals(0, Lizzie.config.uiConfig.getInt("default-engine"));
        assertEquals(1, Lizzie.config.uiConfig.getInt("last-engine"));
        assertTrue(Lizzie.config.uiConfig.getBoolean("autoload-default"));
        assertFalse(Lizzie.config.uiConfig.getBoolean("autoload-last"));
        Lizzie.config.save();
        JSONObject persisted = new JSONObject(Files.readString(work.resolve("config.txt")));
        assertTrue(expectedRemote.similar(persisted.getJSONObject("leelaz")
            .getJSONArray("engine-settings-list").getJSONObject(0)));
        assertEquals(0, persisted.getJSONObject("ui").getInt("default-engine"));
      }
    });
  }

  @ParameterizedTest
  @CsvSource({"manual,false", "manual,true", "ssh-bundled,false", "ssh-bundled,true",
      "ssh-java,false", "ssh-java,true"})
  void relocationRepairsOnlyMissingLocalCustomAnalysisAcrossRestarts(String kind, boolean movePackage)
      throws Exception {
    Path previousPackage = temp.resolve("analysis previous");
    Path currentPackage = temp.resolve("analysis current");
    Path previousApp = createBundle(previousPackage, true);
    Path previousWork = workDirectory(previousPackage, true);
    Path currentApp = appDirectory(currentPackage, true);
    Path currentWork = workDirectory(currentPackage, true);
    boolean javaSsh = kind.startsWith("ssh-");
    withConfig(previousApp, previousWork, () -> {
      String original = "ssh-java".equals(kind)
          ? "java -jar remote/analysis.jar" : Lizzie.config.analysisEngineCommand;
      if (!javaSsh) {
        original = analysisCommand(executable(previousApp),
            touch(previousWork.resolve("weights/my quick model.bin.gz")),
            touch(previousWork.resolve("engines/my quick analysis.cfg")))
            + " -override-config numSearchThreads=4,maxVisits=64";
      }
      Lizzie.config.uiConfig.put("analysis-engine-command", original)
          .put("analysis-engine-command-customized", !javaSsh);
      if (javaSsh) {
        Lizzie.config.leelazConfig.put("analysis-engine-ssh-info",
            new JSONObject().put("useJavaSSH", true).put("ip", "analysis.example.test"));
      }
      Lizzie.config.save();
      if (movePackage) Files.move(previousPackage, currentPackage);
      else copyTree(previousPackage, currentPackage);
      System.setProperty("user.dir", currentApp.toString());
      String expected = movePackage && !javaSsh
          ? original.replace(previousPackage.toString(), currentPackage.toString()) : original;
      for (int restart = 0; restart < 2; restart++) {
        Lizzie.config = ConfigTestHelper.createBootstrapped(currentWork);
        assertEquals(expected, Lizzie.config.analysisEngineCommand);
        Utils.applyMaintainedDefaultSettings();
        assertEquals(expected, Lizzie.config.uiConfig.getString("analysis-engine-command"));
        assertEquals(expected, Lizzie.config.analysisEngineCommand);
        assertEquals(!javaSsh, Lizzie.config.analysisEngineCommandCustomized);
        if (javaSsh) {
          JSONObject sshInfo = Lizzie.config.leelazConfig.getJSONObject("analysis-engine-ssh-info");
          assertTrue(sshInfo.getBoolean("useJavaSSH"));
          assertEquals("analysis.example.test", sshInfo.getString("ip"));
        }
      }
    });
  }

  @Test
  void missingDestinationAndExternalModelAndConfigKeepTheirSavedPaths() throws Exception {
    Path previousPackage = temp.resolve("missing previous");
    Path currentPackage = temp.resolve("missing current");
    Path previousApp = createBundle(previousPackage, false);
    Path previousWork = workDirectory(previousPackage, false);
    Path externalWeight = touch(temp.resolve("external/weights/selected.bin.gz"));
    Path externalConfig = touch(temp.resolve("external/configs/selected.cfg"));
    Path missingWeight = touch(previousApp.resolve("weights/not-copied.bin.gz"));
    Path missingConfig = touch(previousApp.resolve("engines/katago/configs/not-copied.cfg"));
    withConfig(previousApp, previousWork, () -> {
      ArrayList<EngineData> profiles = new ArrayList<>();
      profiles.add(profile("Missing destination", executable(previousApp), missingWeight,
          missingConfig, true));
      profiles.add(profile("External files", executable(previousApp), externalWeight,
          externalConfig, false));
      Utils.saveEngineSettings(profiles);
      Lizzie.config.save();
      copyTree(previousPackage, currentPackage);
      Files.delete(currentPackage.resolve("weights/not-copied.bin.gz"));
      Files.delete(currentPackage.resolve("engines/katago/configs/not-copied.cfg"));
      System.setProperty("user.dir", currentPackage.toString());
      Lizzie.config = ConfigTestHelper.createBootstrapped(currentPackage);
      ArrayList<EngineData> actual = Utils.getEngineData();
      assertEquals(2, actual.size());
      for (int i = 0; i < profiles.size(); i++) {
        assertEquals(profiles.get(i).commands.replace(
            executable(previousApp).toString(), executable(currentPackage).toString()),
            actual.get(i).commands);
        assertTrue(BundledKataGoProfile.isManaged(actual.get(i)));
      }
    });
  }

  @Test
  void siblingUserDataIsNotRelocatedWithoutThePortableMarker() throws Exception {
    Path previousPackage = temp.resolve("unmarked previous");
    Path currentPackage = temp.resolve("unmarked current");
    Path previousApp = createBundle(previousPackage, true);
    Path previousWork = workDirectory(previousPackage, true);
    Path currentApp = appDirectory(currentPackage, true);
    Path currentWork = workDirectory(currentPackage, true);
    Path selected = touch(previousWork.resolve("weights/external-to-app.bin.gz"));
    withConfig(previousApp, previousWork, () -> {
      EngineData entry = profile("External sibling", executable(previousApp), selected,
          previousApp.resolve("engines/katago/configs/gtp.cfg"), true);
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      Lizzie.config.save();
      copyTree(previousPackage, currentPackage);
      Files.delete(currentPackage.resolve(".lizzie-portable"));
      System.setProperty("user.dir", currentApp.toString());
      Lizzie.config = ConfigTestHelper.createBootstrapped(currentWork);
      EngineData saved = Utils.getEngineData().get(0);
      assertEquals(entry.commands.replace(previousApp.toString(), currentApp.toString()),
          saved.commands);
      assertTrue(saved.commands.contains(selected.toString()));
    });
  }

  private Path createBundle(Path packageRoot, boolean portableLayout) throws Exception {
    Path app = Files.createDirectories(appDirectory(packageRoot, portableLayout));
    touch(packageRoot.resolve(".lizzie-portable"));
    touch(app.resolve("PROJECT_INFO.txt"));
    touch(executable(app));
    touch(app.resolve("engines/katago/configs/gtp.cfg"));
    touch(app.resolve("engines/katago/configs/analysis.cfg"));
    touch(app.resolve("weights/default.bin.gz"));
    Files.createDirectories(workDirectory(packageRoot, portableLayout).resolve("save"));
    return app;
  }

  private static Path appDirectory(Path packageRoot, boolean portableLayout) {
    return portableLayout ? packageRoot.resolve("app") : packageRoot;
  }

  private static Path workDirectory(Path packageRoot, boolean portableLayout) {
    return portableLayout ? packageRoot.resolve("user-data") : packageRoot;
  }

  private static Path executable(Path app) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    String platform;
    if (os.contains("win")) platform = arch.contains("64") ? "windows-x64" : "windows-x86";
    else if (os.contains("mac") || os.contains("darwin"))
      platform = arch.contains("aarch64") || arch.contains("arm64") ? "macos-arm64" : "macos-amd64";
    else platform = arch.contains("64") ? "linux-x64" : "linux-x86";
    return app.resolve("engines/katago").resolve(platform)
        .resolve(os.contains("win") ? "katago.exe" : "katago");
  }

  private static EngineData profile(
      String name, Path engine, Path model, Path config, boolean selected) {
    EngineData entry = new EngineData();
    entry.name = name;
    entry.commands = quote(engine) + " gtp -model " + quote(model) + " -config " + quote(config);
    entry.width = 19;
    entry.height = 19;
    entry.komi = 7.5F;
    entry.isDefault = selected;
    BundledKataGoProfile.claim(entry);
    return entry;
  }

  private static String analysisCommand(Path engine, Path model, Path config) {
    return quote(engine) + " analysis -model " + quote(model) + " -config " + quote(config)
        + " -quit-without-waiting";
  }

  private static String quote(Path path) {
    return '"' + path.toAbsolutePath().normalize().toString() + '"';
  }

  private static Path touch(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "fixture");
    return path;
  }

  private static void copyTree(Path source, Path destination) throws Exception {
    try (var paths = Files.walk(source)) {
      for (Path path : paths.toList()) {
        Path target = destination.resolve(source.relativize(path));
        if (Files.isDirectory(path)) Files.createDirectories(target);
        else Files.copy(path, target);
      }
    }
  }

  private static void withConfig(Path app, Path work, ThrowingRunnable action) throws Exception {
    String previousDirectory = System.getProperty("user.dir");
    Config previousConfig = Lizzie.config;
    try {
      System.setProperty("user.dir", app.toString());
      Lizzie.config = ConfigTestHelper.createBootstrapped(work);
      action.run();
    } finally {
      if (previousDirectory == null) System.clearProperty("user.dir");
      else System.setProperty("user.dir", previousDirectory);
      Lizzie.config = previousConfig;
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
