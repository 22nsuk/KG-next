package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.logging.LoggingSettings;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTransactionalSaveTest {

  @Test
  void wholeGameAnalysisDepthIsValidatedAndPersistedTransactionally() throws Exception {
    Path workDirectory = Files.createTempDirectory("lizzie-whole-game-analysis-depth");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfigSections(config);

    config.saveWholeGameAnalysisDeepVisits(3_000);

    JSONObject saved = new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
    assertEquals(3_000, config.wholeGameAnalysisDeepVisits);
    assertEquals(3_000, saved.getJSONObject("ui").getInt("whole-game-analysis-deep-visits"));

    assertThrows(
        IllegalArgumentException.class, () -> config.saveWholeGameAnalysisDeepVisits(499));
    assertEquals(3_000, config.wholeGameAnalysisDeepVisits);
  }

  @Test
  void readBoardWebSocketPonderingNoticeSuppressionDefaultsToFalseAndPersists()
      throws Exception {
    Path workDirectory = Files.createTempDirectory("lizzie-readboard-pondering-notice");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfigSections(config);

    assertFalse(config.suppressReadBoardWebSocketPonderingNotice);

    config.suppressReadBoardWebSocketPonderingNotice();
    JSONObject saved =
        new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));

    assertTrue(
        saved.getJSONObject("ui").getBoolean("suppress-readboard-websocket-pondering-notice"));
  }

  @Test
  void updateConfigSectionsPersistsEditsAndKeepsLiveSectionReferences() throws Exception {
    Path workDirectory = Files.createTempDirectory("lizzie-config-transaction-success");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfigSections(config);
    JSONObject previousRoot = config.config;
    JSONObject previousUi = config.uiConfig;
    JSONObject previousLeelaz = config.leelazConfig;
    config.updateConfigSections(
        (ui, leelaz) -> {
          ui.put("analysis-engine-command", "katago analysis -config test.cfg");
          ui.put("analysis-reuse-current-engine", true);
          leelaz.put(
              "analysis-engine-ssh-info",
              new JSONObject().put("useJavaSSH", true).put("ip", "example.test"));
        });

    assertSame(config.config.getJSONObject("ui"), config.uiConfig);
    assertSame(config.config.getJSONObject("leelaz"), config.leelazConfig);
    assertSame(previousRoot, config.config);
    assertSame(previousUi, config.uiConfig);
    assertSame(previousLeelaz, config.leelazConfig);
    JSONObject reloaded = new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
    assertEquals(
        "katago analysis -config test.cfg",
        reloaded.getJSONObject("ui").getString("analysis-engine-command"));
    assertTrue(reloaded.getJSONObject("ui").getBoolean("analysis-reuse-current-engine"));
    assertEquals(
        "example.test",
        reloaded
            .getJSONObject("leelaz")
            .getJSONObject("analysis-engine-ssh-info")
            .getString("ip"));
  }

  @Test
  void failedSaveKeepsCurrentRootAndSectionReferences() throws Exception {
    Path workDirectory = Files.createTempDirectory("lizzie-config-transaction-failure");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfigSections(config);
    config.updateConfigSections(
        (ui, leelaz) -> {
          ui.put("nested", new JSONObject().put("keep", "saved"));
          ui.put("removable", "saved");
        });
    JSONObject previousRoot = config.config;
    JSONObject previousUi = config.uiConfig;
    JSONObject previousLeelaz = config.leelazConfig;
    Path configFile = Path.of(config.getConfigFilePath());
    Path savedFile = workDirectory.resolve("previous-config.txt");
    Files.move(configFile, savedFile);
    Files.createDirectory(configFile);
    Files.writeString(configFile.resolve("keep"), "keep");

    assertThrows(
        IOException.class,
        () ->
            config.updateConfigSections(
                (ui, leelaz) -> {
                  ui.put("analysis-engine-command", "must-not-be-adopted");
                  ui.getJSONObject("nested").put("keep", "must-not-be-adopted");
                  ui.remove("removable");
                }));

    assertSame(previousRoot, config.config);
    assertSame(previousUi, config.uiConfig);
    assertSame(previousLeelaz, config.leelazConfig);
    assertEquals("saved", config.uiConfig.getJSONObject("nested").getString("keep"));
    assertEquals("saved", config.uiConfig.getString("removable"));
    assertTrue(new JSONObject(Files.readString(savedFile)).similar(config.config));
  }

  @Test
  void overlappingSectionEditsPreserveBothChangesAndTheLastCommittedValue() throws Exception {
    Path workDirectory = Files.createTempDirectory("lizzie-config-overlapping-edits");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfigSections(config);
    config.config.put("unrelated-root", new JSONObject().put("keep", 7));
    config.uiConfig.put("nested", new JSONObject().put("keep", 1).put("edit", 0));
    config.uiConfig.put("removable", true);
    config.save();
    CountDownLatch attemptingSave = new CountDownLatch(1);
    FutureTask<Void> secondSave =
        new FutureTask<>(
            () -> {
              attemptingSave.countDown();
              config.updateConfigSections(
                  (ui, leelaz) -> {
                    ui.put("second-only", 2);
                    ui.put("shared", "second");
                    leelaz.put("second-engine-setting", true);
                  });
              return null;
            });
    Thread writer = new Thread(secondSave, "config-second-section-save");
    try {
      config.updateConfigSections(
          (ui, leelaz) -> {
            ui.put("first-only", 1);
            ui.put("shared", "first");
            ui.getJSONObject("nested").put("edit", 3);
            ui.remove("removable");
            writer.start();
            awaitSaveTurn(config, writer, secondSave, attemptingSave);
          });
      secondSave.get(5, TimeUnit.SECONDS);

      JSONObject saved = new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
      assertTrue(saved.similar(config.config));
      assertEquals(1, config.uiConfig.getInt("first-only"));
      assertEquals(2, config.uiConfig.getInt("second-only"));
      assertEquals("second", config.uiConfig.getString("shared"));
      assertEquals(1, config.uiConfig.getJSONObject("nested").getInt("keep"));
      assertEquals(3, config.uiConfig.getJSONObject("nested").getInt("edit"));
      assertFalse(config.uiConfig.has("removable"));
      assertTrue(config.leelazConfig.getBoolean("second-engine-setting"));
      assertEquals(7, config.config.getJSONObject("unrelated-root").getInt("keep"));
    } finally {
      writer.join(5_000);
      assertFalse(writer.isAlive());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"logging", "legacy"})
  void otherConfigSavesCannotOverwriteAnOverlappingSectionCommit(String saveKind) throws Exception {
    Path workDirectory = Files.createTempDirectory("lizzie-config-overlapping-" + saveKind);
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfigSections(config);
    CountDownLatch attemptingSave = new CountDownLatch(1);
    FutureTask<Void> sectionSave =
        new FutureTask<>(
            () -> {
              attemptingSave.countDown();
              config.updateConfigSections((ui, leelaz) -> ui.put("new-setting", "saved"));
              return null;
            });
    Thread writer = new Thread(sectionSave, "config-overlapping-section-save");
    AtomicBoolean started = new AtomicBoolean();
    config.config =
        new JSONObject(config.config.toString()) {
          @Override
          public String toString(int indentFactor) {
            String snapshot = super.toString(indentFactor);
            if (started.compareAndSet(false, true)) {
              writer.start();
              awaitSaveTurn(config, writer, sectionSave, attemptingSave);
            }
            return snapshot;
          }
        };
    config.uiConfig = config.config.getJSONObject("ui");
    config.leelazConfig = config.config.getJSONObject("leelaz");
    LoggingSettings settings = LoggingSettings.defaults().withDiagnosticsEnabled(false);
    try {
      if ("logging".equals(saveKind)) config.saveLoggingSettings(settings);
      else config.save();
      assertTrue(started.get(), "the competing save must start after the first snapshot");
      sectionSave.get(5, TimeUnit.SECONDS);

      JSONObject saved = new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
      assertEquals("saved", config.uiConfig.getString("new-setting"));
      assertTrue(saved.similar(config.config));
      if ("logging".equals(saveKind)) {
        assertSame(settings, config.loggingSettings);
        assertTrue(saved.getJSONObject(LoggingSettings.CONFIG_KEY).similar(settings.toJson()));
      }
    } finally {
      writer.join(5_000);
      assertFalse(writer.isAlive());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"sections", "logging"})
  void aLegacyUiEditWaitingToSaveSurvivesTransactionalPublication(String saveKind) throws Exception {
    Path workDirectory = Files.createTempDirectory("lizzie-config-legacy-ui-edit");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfigSections(config);
    CountDownLatch attemptingSave = new CountDownLatch(1);
    FutureTask<Void> noticeSave =
        new FutureTask<>(
            () -> {
              attemptingSave.countDown();
              config.suppressReadBoardWebSocketPonderingNotice();
              return null;
            });
    Thread writer = new Thread(noticeSave, "config-legacy-ui-edit");
    AtomicBoolean started = new AtomicBoolean();
    config.config =
        new JSONObject(config.config.toString()) {
          @Override
          public String toString(int indentFactor) {
            String snapshot = super.toString(indentFactor);
            if (started.compareAndSet(false, true)) {
              writer.start();
              // The production caller edits live JSON before entering save(). Its change must
              // survive while that save waits for this transaction's disk replacement.
              awaitSaveTurn(config, writer, noticeSave, attemptingSave);
            }
            return snapshot;
          }
        };
    config.uiConfig = config.config.getJSONObject("ui");
    config.leelazConfig = config.config.getJSONObject("leelaz");
    try {
      if ("logging".equals(saveKind)) {
        config.saveLoggingSettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
      } else {
        config.updateConfigSections((ui, leelaz) -> ui.put("transaction-setting", "saved"));
      }
      assertTrue(started.get(), "the legacy edit must run after the transaction takes its snapshot");
      noticeSave.get(5, TimeUnit.SECONDS);

      JSONObject saved = new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
      assertTrue(config.suppressReadBoardWebSocketPonderingNotice);
      assertTrue(config.uiConfig.optBoolean("suppress-readboard-websocket-pondering-notice", false));
      if ("logging".equals(saveKind)) {
        assertFalse(config.loggingSettings.diagnosticsEnabled());
        assertFalse(saved.getJSONObject(LoggingSettings.CONFIG_KEY).getBoolean("diagnostics-enabled"));
      } else {
        assertEquals("saved", config.uiConfig.getString("transaction-setting"));
      }
      assertTrue(saved.similar(config.config));
    } finally {
      writer.join(5_000);
      assertFalse(writer.isAlive());
    }
  }

  private static void awaitSaveTurn(
      Config config, Thread writer, FutureTask<Void> save, CountDownLatch attemptingSave) {
    try {
      assertTrue(attemptingSave.await(5, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!save.isDone()) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(writer.getId());
        if (info != null
            && info.getThreadState() == Thread.State.BLOCKED
            && info.getLockInfo() != null
            && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(config)) {
          return;
        }
        assertTrue(System.nanoTime() < deadline, "the other save must finish or wait for Config");
        Thread.onSpinWait();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void initializeConfigSections(Config config) throws IOException {
    JSONObject root = new JSONObject();
    JSONObject ui = new JSONObject();
    JSONObject leelaz = new JSONObject();
    root.put("ui", ui);
    root.put("leelaz", leelaz);
    config.config = root;
    config.uiConfig = ui;
    config.leelazConfig = leelaz;
    config.save();
  }
}
