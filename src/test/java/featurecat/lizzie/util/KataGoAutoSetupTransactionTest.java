package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoRuntimeHelper.BenchmarkResult;
import featurecat.lizzie.util.KataGoRuntimeHelper.BenchmarkTarget;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KataGoAutoSetupTransactionTest {
  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(strings = {"auto", "weight", "named", "executable"})
  void setupDoesNotOverwriteAnOverlappingBenchmarkCommit(String entryPoint) throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Config config = isolatedConfig();
      Lizzie.config = config;
      SetupSnapshot snapshot = snapshot(true);
      KataGoAutoSetupHelper.applyAutoSetup(snapshot);
      String entryId = Utils.getEngineData().get(0).id;
      try (BenchmarkCommit fixture = new BenchmarkCommit(snapshot)) {
        AtomicBoolean started = new AtomicBoolean();
        CountDownLatch attemptingCommit = new CountDownLatch(1);
        FutureTask<Void> benchmarkCommit =
            new FutureTask<>(
                () -> {
                  attemptingCommit.countDown();
                  fixture.commit();
                  return null;
                });
        Thread benchmark = new Thread(benchmarkCommit, "setup-regression-benchmark-commit");
        config.uiConfig =
            new JSONObject(config.uiConfig.toString()) {
              @Override
              public boolean has(String key) {
                if ("autoload-default".equals(key) && started.compareAndSet(false, true)) {
                  // Setup has read its engine list. Give the competing commit the next turn:
                  // it either finishes now (old code), or blocks on the transaction owner.
                  benchmark.start();
                  awaitCommitTurn(benchmark, benchmarkCommit, attemptingCommit);
                }
                return super.has(key);
              }
            };
        try {
          switch (entryPoint) {
            case "auto" -> KataGoAutoSetupHelper.applyAutoSetup(snapshot, false);
            case "weight" -> KataGoAutoSetupHelper.addWeightEngineProfile(snapshot);
            case "named" ->
                KataGoAutoSetupHelper.applyEngineProfile(snapshot, "KataGo Auto Setup", false);
            case "executable" ->
                KataGoAutoSetupHelper.applyEngineProfile(
                    snapshot, "KataGo Auto Setup", false, snapshot.enginePath);
            default -> throw new AssertionError(entryPoint);
          }
        } finally {
          if (started.get()) benchmarkCommit.get(5, TimeUnit.SECONDS);
        }
        assertTrue(started.get(), "the competing commit ran after setup read its engine list");
        ArrayList<EngineData> engines = Utils.getEngineData();
        assertEquals(1, engines.size());
        assertEquals(entryId, engines.get(0).id);
        assertBenchmarkResult(engines.get(0).threadPolicy);
        JSONObject persisted =
            new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
        JSONObject savedEngine =
            persisted.getJSONObject("leelaz").getJSONArray("engine-settings-list").getJSONObject(0);
        assertBenchmarkResult(savedEngine.getJSONObject("threadPolicy"));
      }
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void missingAnalysisConfigUsesTheGtpFallbackInCommandAndRememberedPath() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Config config = isolatedConfig();
      Lizzie.config = config;
      SetupSnapshot snapshot = snapshot(false);

      KataGoAutoSetupHelper.SetupResult result = KataGoAutoSetupHelper.applyAutoSetup(snapshot);

      assertNotNull(result);
      assertEquals(1, Utils.getEngineData().size());
      assertFalse(config.analysisEngineCommand.isBlank());
      assertTrue(config.analysisEngineCommand.contains("gtp.cfg"));
      assertEquals(
          config.analysisEngineCommand, config.uiConfig.getString("analysis-engine-command"));
      String expectedPath = snapshot.gtpConfigPath.toAbsolutePath().normalize().toString();
      assertEquals(
          expectedPath, config.uiConfig.getString("katago-auto-setup-analysis-config-path"));
      JSONObject persisted = new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
      assertEquals(
          expectedPath,
          persisted.getJSONObject("ui").getString("katago-auto-setup-analysis-config-path"));
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedSaveDoesNotPublishCandidateConfigOrAnalysisCommand(boolean existingProfile)
      throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Config config = isolatedConfig();
      Lizzie.config = config;
      SetupSnapshot snapshot = snapshot(true);
      if (existingProfile) KataGoAutoSetupHelper.applyAutoSetup(snapshot);
      String analysis = "previous analysis command";
      config.analysisEngineCommand = analysis;
      config.uiConfig.put("analysis-engine-command", analysis);
      config.saveConfigSections(config.uiConfig, config.leelazConfig);
      JSONObject originalRoot = config.config;
      JSONObject originalUi = config.uiConfig;
      JSONObject originalLeelaz = config.leelazConfig;
      String originalContent = originalRoot.toString();
      Path configPath = Path.of(config.getConfigFilePath());
      Path previousFile = root.resolve("previous-config.txt");
      Files.move(configPath, previousFile);
      // A nonempty directory guarantees replacement fails on both Windows and Unix, even as root.
      Files.createDirectory(configPath);
      Path sentinel = Files.writeString(configPath.resolve("keep"), "keep");
      Path selectedWeight = Files.writeString(root.resolve("selected.bin.gz"), "");

      assertThrows(
          IOException.class,
          () -> KataGoAutoSetupHelper.applyAutoSetup(snapshot.withActiveWeight(selectedWeight)));

      assertSame(originalRoot, config.config);
      assertSame(originalUi, config.uiConfig);
      assertSame(originalLeelaz, config.leelazConfig);
      assertEquals(originalContent, config.config.toString());
      assertEquals(analysis, config.analysisEngineCommand);
      assertEquals("keep", Files.readString(sentinel));
      assertTrue(new JSONObject(Files.readString(previousFile)).similar(originalRoot));
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void interruptedSetupWaitingForPersistenceOwnerDoesNotCommit() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Config config = isolatedConfig();
      Lizzie.config = config;
      SetupSnapshot snapshot = snapshot(true);
      config.saveConfigSections(config.uiConfig, config.leelazConfig);
      String before = config.config.toString();
      String beforeDisk = Files.readString(Path.of(config.getConfigFilePath()));
      CountDownLatch started = new CountDownLatch(1);
      FutureTask<Void> setup =
          new FutureTask<>(
              () -> {
                started.countDown();
                KataGoAutoSetupHelper.applyAutoSetup(snapshot);
                return null;
              });
      Thread worker = new Thread(setup, "setup-regression-interrupted-owner");
      synchronized (Utils.class) {
        worker.start();
        awaitCommitTurn(worker, setup, started);
        worker.interrupt();
      }

      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> setup.get(5, TimeUnit.SECONDS));
      assertTrue(failure.getCause() instanceof KataGoAutoSetupHelper.DownloadCancelledException);
      assertEquals(before, config.config.toString());
      assertEquals(beforeDisk, Files.readString(Path.of(config.getConfigFilePath())));
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  private Config isolatedConfig() {
    Config config = ConfigTestHelper.createForTests(root);
    config.config = new JSONObject();
    config.uiConfig = new JSONObject();
    config.leelazConfig = new JSONObject().put("engine-settings-list", new org.json.JSONArray());
    return config;
  }

  private SetupSnapshot snapshot(boolean withAnalysisConfig) throws Exception {
    Path engine = Files.writeString(root.resolve("katago"), "");
    Path gtpConfig = Files.writeString(root.resolve("gtp.cfg"), "");
    Path analysisConfig =
        withAnalysisConfig ? Files.writeString(root.resolve("analysis.cfg"), "") : null;
    Path weight = Files.writeString(root.resolve("default.bin.gz"), "");
    Constructor<SetupSnapshot> constructor =
        SetupSnapshot.class.getDeclaredConstructor(
            Path.class, Path.class, Path.class, Path.class, Path.class, Path.class, List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        root, root, engine, gtpConfig, analysisConfig, weight, List.of(weight));
  }

  private static void assertBenchmarkResult(JSONObject policy) {
    assertEquals(12, policy.getInt("katago-benchmark-threads"));
    assertEquals(0.0, policy.getDouble("katago-benchmark-visits-per-second"));
    assertEquals(12345L, policy.getLong("katago-benchmark-updated-at"));
  }

  /** Runs the production result commit without launching a benchmark process. */
  private static final class BenchmarkCommit implements AutoCloseable {
    private final AtomicReference<BenchmarkTarget> owner;
    private final BenchmarkTarget previous;
    private final BenchmarkTarget target;
    private final BenchmarkResult result;
    private final Method commit;

    @SuppressWarnings("unchecked")
    BenchmarkCommit(SetupSnapshot snapshot) throws Exception {
      target = KataGoRuntimeHelper.captureBenchmarkTarget(snapshot, true);
      Constructor<BenchmarkResult> constructor =
          BenchmarkResult.class.getDeclaredConstructor(
              int.class, int.class, String.class, String.class, long.class);
      constructor.setAccessible(true);
      result = constructor.newInstance(12, 4, "test backend", "completed benchmark", 12345L);
      commit =
          KataGoRuntimeHelper.class.getDeclaredMethod(
              "commitBenchmarkResult",
              BenchmarkTarget.class,
              BenchmarkResult.class,
              DownloadSession.class);
      commit.setAccessible(true);
      Field field = KataGoRuntimeHelper.class.getDeclaredField("activeEntryBenchmark");
      field.setAccessible(true);
      owner = (AtomicReference<BenchmarkTarget>) field.get(null);
      previous = owner.getAndSet(target);
    }

    void commit() throws Exception {
      commit.invoke(null, target, result, new DownloadSession());
    }

    @Override
    public void close() {
      owner.set(previous);
    }
  }

  private static void awaitCommitTurn(
      Thread benchmark, FutureTask<Void> commit, CountDownLatch attemptingCommit) {
    try {
      assertTrue(attemptingCommit.await(5, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!commit.isDone()) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(benchmark.getId());
        if (info != null
            && info.getThreadState() == Thread.State.BLOCKED
            && info.getLockInfo() != null
            && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(Utils.class)) {
          return;
        }
        assertTrue(
            System.nanoTime() < deadline, "benchmark must commit or wait for setup's transaction");
        Thread.onSpinWait();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
