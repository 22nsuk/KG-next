package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGamePlans;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EngineStartupDialogPolicyTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void headlessSecondaryDiagnosticPreservesStatusAndOnlyForegroundClearsPendingEngineGame(
      boolean backgroundPreload) throws Exception {
    assumeTrue(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    Leelaz previousPrimary = Lizzie.leelaz;
    EngineManager previousManager = Lizzie.engineManager;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousFirstLaunch = forceFirstLaunchSession(false);
    try {
      Lizzie.config =
          ConfigTestHelper.createForTests(tempDir.resolve("headless-secondary-diagnostic"));
      Lizzie.config.autoCheckEngineAlive = false;
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.setPrimaryEngine(primary);
      Lizzie.engineManager = new EngineManager(List.of(primary, secondary));
      Lizzie.board = new Board();
      Lizzie.board.isPkBoard = true;
      Lizzie.frame = null;
      EngineManager.beginEngineGameTransaction(
          Lizzie.engineManager, EngineGamePlans.harness(0, 1, false), null, true);
      Lizzie.engineStartupStatus.ready();

      if (backgroundPreload) {
        Object request = startupResources(secondary.beginPreloadStartAttempt());
        Method diagnostic =
            Leelaz.class.getDeclaredMethod(
                "showStartupDiagnostic", request.getClass(), String.class, boolean.class,
                boolean.class);
        diagnostic.setAccessible(true);
        diagnostic.invoke(secondary, request, "controlled background preload failure", true, true);
      } else {
        secondary.tryToDignostic("controlled headless secondary failure", false);
      }

      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(backgroundPreload, EngineManager.hasActiveEngineGameTransaction());
      assertEquals(backgroundPreload, Lizzie.board.isPkBoard);
    } finally {
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.config = previousConfig;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.engineManager = previousManager;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      forceFirstLaunchSession(previousFirstLaunch);
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void stalePrimaryDiagnosticCannotCancelReplacementEngineGame() throws Exception {
    assumeTrue(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    Leelaz previousPrimary = Lizzie.leelaz;
    EngineManager previousManager = Lizzie.engineManager;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousFirstLaunch = forceFirstLaunchSession(false);
    try {
      Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("stale-primary-diagnostic"));
      Lizzie.config.autoCheckEngineAlive = false;
      Leelaz oldPrimary = new Leelaz("");
      Leelaz replacement = new Leelaz("");
      Leelaz opponent = new Leelaz("");
      Lizzie.setPrimaryEngine(oldPrimary);
      long capturedGeneration = Lizzie.capturePrimaryEngineGeneration(oldPrimary);
      Field startupGeneration = Leelaz.class.getDeclaredField("startupPrimaryEngineGeneration");
      startupGeneration.setAccessible(true);
      startupGeneration.setLong(oldPrimary, capturedGeneration);
      Lizzie.setPrimaryEngine(replacement);
      Lizzie.engineManager = new EngineManager(List.of(replacement, opponent));
      Lizzie.board = new Board();
      Lizzie.board.isPkBoard = true;
      Lizzie.frame = null;
      EngineManager.beginEngineGameTransaction(
          Lizzie.engineManager, EngineGamePlans.harness(0, 1, false), null, true);
      Lizzie.engineStartupStatus.ready();

      oldPrimary.tryToDignostic("old startup failure", false);

      assertSame(replacement, Lizzie.leelaz);
      assertTrue(EngineManager.hasActiveEngineGameTransaction());
      assertTrue(Lizzie.board.isPkBoard);
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
    } finally {
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.config = previousConfig;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.engineManager = previousManager;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      forceFirstLaunchSession(previousFirstLaunch);
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void backgroundAnalysisPreloadDoesNotAnnounceGeneratedConfig() {
    assertFalse(AnalysisEngine.shouldShowGeneratedConfigNotice(true, true));
    assertFalse(AnalysisEngine.shouldShowGeneratedConfigNotice(false, false));
    assertTrue(AnalysisEngine.shouldShowGeneratedConfigNotice(false, true));
  }

  @Test
  void secondaryBundledStartupStageDoesNotPublishPrimaryStatus() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz primary = new Leelaz("");
    Leelaz secondary = new Leelaz("engines/katago/windows-x64-opencl/katago.exe");
    try {
      Lizzie.setPrimaryEngine(primary);
      Lizzie.engineStartupStatus.ready();

      invokeBundledStartupStage(secondary, reserveStartup(secondary));

      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
    } finally {
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void primaryBundledStartupStagePublishesCheckingStatus() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz primary = new Leelaz("engines/katago/windows-x64-opencl/katago.exe");
    try {
      Lizzie.setPrimaryEngine(primary);
      Lizzie.engineStartupStatus.ready();

      invokeBundledStartupStage(primary, reserveStartup(primary));

      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
    } finally {
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void secondaryOpenClCompatibilityStageDoesNotPublishPrimaryStatus() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz primary = new Leelaz("");
    Leelaz secondary = new Leelaz("engines/katago/windows-x64-opencl/katago.exe");
    try {
      Lizzie.setPrimaryEngine(primary);
      Lizzie.engineStartupStatus.ready();

      invokeBundledStartupStatus(
          secondary, reserveStartup(secondary),
          "BundledEngineStartup.status.openclCompatibility",
          "Using stable NVIDIA OpenCL compatibility mode...");

      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
    } finally {
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void stalePrimaryGenerationCannotPublishCheckingReadyOrPdaInitialization() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz engine = new Leelaz("engines/katago/windows-x64-opencl/katago.exe");
    try {
      Lizzie.setPrimaryEngine(engine);
      long staleGeneration = Lizzie.capturePrimaryEngineGeneration(engine);
      Object request = reserveStartup(engine);
      Lizzie.setPrimaryEngine(engine);
      Lizzie.engineStartupStatus.failed("failed", "Failed", "new owner failure");

      invokeBundledStartupStatus(
          engine, request,
          "BundledEngineStartup.status.checking",
          "stale startup must be ignored");
      Lizzie.initializeAfterVersionCheck(false, engine, false, staleGeneration);

      assertEquals(
          EngineStartupStatus.State.START_FAILED,
          Lizzie.engineStartupStatus.snapshot().state,
          "callbacks captured by the older generation must not publish CHECKING or READY");
    } finally {
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void missingExecutableWeightOrConfigUsesNotReadyRepairState() throws Exception {
    Path executable = tempDir.resolve("katago.exe");
    Path model = tempDir.resolve("model.bin.gz");
    Path config = tempDir.resolve("gtp.cfg");

    assertTrue(
        Leelaz.hasMissingLocalStartupAsset(
            List.of(executable.toString(), "gtp", "-model", model.toString()), false, false));

    Files.writeString(executable, "stub");
    Files.writeString(model, "stub");
    assertTrue(
        Leelaz.hasMissingLocalStartupAsset(
            List.of(
                executable.toString(),
                "gtp",
                "-model",
                model.toString(),
                "-config",
                config.toString()),
            false,
            false));

    Files.writeString(config, "stub");
    assertFalse(
        Leelaz.hasMissingLocalStartupAsset(
            List.of(
                executable.toString(),
                "gtp",
                "-model",
                model.toString(),
                "-config",
                config.toString()),
            false,
            false));
    assertFalse(
        Leelaz.hasMissingLocalStartupAsset(
            List.of(executable.toString(), "gtp"), true, false));
  }

  @Test
  void stoppedStartupCannotOverwriteStatusOnSamePrimary() throws Exception {
    Leelaz previous = Lizzie.leelaz;
    Leelaz primary = new Leelaz("engines/katago/windows-x64-opencl/katago.exe");
    try {
      Lizzie.setPrimaryEngine(primary);
      Object stopped = reserveStartup(primary);
      primary.normalQuit();
      Lizzie.engineStartupStatus.ready();
      invokeBundledStartupStage(primary, stopped);
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
    } finally {
      Lizzie.setPrimaryEngine(previous);
      Lizzie.engineStartupStatus.ready();
    }
  }

  private static Object reserveStartup(Leelaz engine) throws Exception {
    Object request = startupResources(engine.beginUpdateEngineStartAttempt());
    Field primaryGeneration = request.getClass().getDeclaredField("primaryGeneration");
    primaryGeneration.setAccessible(true);
    primaryGeneration.setLong(request, Lizzie.capturePrimaryEngineGeneration(engine));
    return request;
  }

  private static Object startupResources(Leelaz.UpdateEngineStartAttempt attempt) throws Exception {
    Field resources = attempt.getClass().getDeclaredField("resources");
    resources.setAccessible(true);
    return resources.get(attempt);
  }

  private static void invokeBundledStartupStage(Leelaz engine, Object request) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("updateBundledStartupStage",
        request.getClass(), Path.class, int.class, String.class, String.class);
    method.setAccessible(true);
    method.invoke(engine, request, Path.of("engines/katago/windows-x64-opencl/katago.exe"), 1,
        "BundledEngineStartup.status.checking", "Checking built-in engine files...");
  }

  private static void invokeBundledStartupStatus(
      Leelaz engine, Object request, String statusKey, String statusFallback) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("publishBundledStartupStatus",
        request.getClass(), String.class, String.class);
    method.setAccessible(true);
    method.invoke(engine, request, statusKey, statusFallback);
  }

  private static boolean forceFirstLaunchSession(boolean value) throws Exception {
    Field field = Lizzie.class.getDeclaredField("firstLaunchSession");
    field.setAccessible(true);
    boolean previous = field.getBoolean(null);
    field.setBoolean(null, value);
    return previous;
  }

}
