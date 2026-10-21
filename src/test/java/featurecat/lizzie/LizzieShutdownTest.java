package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.Acceptance;
import featurecat.lizzie.enginegame.EngineGameBatchSpecFactory;
import featurecat.lizzie.enginegame.EngineGameParsedStart;
import featurecat.lizzie.enginegame.EngineGamePlan;
import featurecat.lizzie.enginegame.EngineGameSide;
import featurecat.lizzie.enginegame.EngineGameTransaction;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.GameOutcome;
import featurecat.lizzie.enginegame.LifecycleBinding;
import featurecat.lizzie.enginegame.ParticipantBinding;
import featurecat.lizzie.enginegame.StartFailure;
import featurecat.lizzie.enginegame.StartObserver;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.SgfSaveCoordinator;
import featurecat.lizzie.gui.web.WebBoardManager;
import featurecat.lizzie.rules.Board;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LizzieShutdownTest {
  private static final EngineParticipantIdentity FIRST =
      new EngineParticipantIdentity("shutdown-first", "");
  private static final EngineParticipantIdentity SECOND =
      new EngineParticipantIdentity("shutdown-second", "");

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void exitWaitsForAnalysisCleanupWithoutBlockingEdt(boolean alreadyDetached) throws Exception {
    try (Harness harness = Harness.open();
        GatedAnalysisProcess process = new GatedAnalysisProcess()) {
      AnalysisEngine analysis = allocate(AnalysisEngine.class);
      analysis.process = process;
      Method initialize = AnalysisEngine.class.getDeclaredMethod("initializeStreams");
      initialize.setAccessible(true);
      initialize.invoke(analysis);
      harness.frame.analysisEngine = analysis;
      if (alreadyDetached) {
        analysis.normalQuit();
        harness.frame.analysisEngine = null;
        // Exercise the shutdown path without an EngineManager as well.
        Lizzie.engineManager = null;
      }
      CompletableFuture<Integer> exited = new CompletableFuture<>();
      FutureTask<Void> shutdown =
          new FutureTask<>(
              () -> {
                Lizzie.shutdown(exited::complete);
                return null;
              });
      CountDownLatch nextEdtEvent = new CountDownLatch(1);
      try {
        SwingUtilities.invokeLater(shutdown);
        SwingUtilities.invokeLater(nextEdtEvent::countDown);

        assertTrue(nextEdtEvent.await(5, TimeUnit.SECONDS), "shutdown blocked the EDT");
        shutdown.get(5, TimeUnit.SECONDS);
        assertTrue(process.destroyEntered.await(5, TimeUnit.SECONDS));
        assertTrue(process.isAlive(), "the child must still be running behind the cleanup gate");
        assertFalse(exited.isDone(), "JVM exit ran before the analysis process was terminated");

        process.allowDestroy.countDown();
        assertEquals(0, exited.get(5, TimeUnit.SECONDS));
        assertFalse(process.isAlive(), "JVM exit ran while the analysis child was alive");
      } finally {
        process.allowDestroy.countDown();
        Field resourcesField = AnalysisEngine.class.getDeclaredField("readerResources");
        resourcesField.setAccessible(true);
        Object resources = resourcesField.get(analysis);
        Field cleanupField = resources.getClass().getDeclaredField("cleanup");
        cleanupField.setAccessible(true);
        Thread cleanup = (Thread) cleanupField.get(resources);
        if (cleanup != null) {
          cleanup.join(TimeUnit.SECONDS.toMillis(5));
          assertFalse(cleanup.isAlive(), "cleanup did not finish after releasing the gate");
        }
      }
    }
  }

  /** The child deliberately keeps working after stdin EOF, like a draining analysis engine. */
  public static final class AnalysisChild {
    public static void main(String[] args) throws Exception {
      new CountDownLatch(1).await();
    }
  }

  private static final class GatedAnalysisProcess extends Process implements AutoCloseable {
    private final Process child;
    private final CountDownLatch destroyEntered = new CountDownLatch(1);
    private final CountDownLatch allowDestroy = new CountDownLatch(1);

    private GatedAnalysisProcess() throws IOException {
      child =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  AnalysisChild.class.getName())
              .start();
    }

    @Override
    public OutputStream getOutputStream() {
      return child.getOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return child.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return child.getErrorStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
      return child.waitFor();
    }

    @Override
    public int exitValue() {
      return child.exitValue();
    }

    @Override
    public boolean isAlive() {
      return child.isAlive();
    }

    @Override
    public void destroy() {
      destroyForcibly();
    }

    @Override
    public Process destroyForcibly() {
      destroyEntered.countDown();
      try {
        if (!allowDestroy.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("analysis cleanup gate was not released");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
      child.destroyForcibly();
      return this;
    }

    @Override
    public void close() throws InterruptedException {
      allowDestroy.countDown();
      child.destroyForcibly();
      assertTrue(child.waitFor(5, TimeUnit.SECONDS), "test child cleanup timed out");
    }
  }

  @Test
  void forceKillAllowsAnAlreadyMissingPrimaryEngine() throws Exception {
    try (Harness harness = Harness.open()) {
      assertDoesNotThrow(() -> Lizzie.engineManager.forceKillAllEngines());
      assertEquals(1, harness.engine.forceQuitCount);
    }
  }

  @Test
  void autosaveRuntimeFailureCancelsShutdownBeforeStoppingAnyResources() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.autoSaveFailure = new IllegalStateException("autosave failed");
      harness.config.autoSaveOnExit = true;
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertEquals(1, harness.frame.autoSaveCalls);
      assertFalse(harness.config.persistCalled);
      assertFalse(harness.config.saveCalled);
      assertEquals(0, harness.frame.strengthShutdownCalls);
      assertEquals(0, harness.engine.forceQuitCount);
      assertEquals(-1, exitCode.get());
    }
  }

  @Test
  void failedAutosaveCancelsShutdownAndSuccessfulRetryCanExit() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.autoSaveResult = false;
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertEquals(1, harness.frame.autoSaveCalls);
      assertFalse(harness.config.persistCalled);
      assertFalse(harness.config.saveCalled);
      assertEquals(0, harness.frame.strengthShutdownCalls);
      assertEquals(0, harness.engine.forceQuitCount);
      assertEquals(-1, exitCode.get());

      harness.frame.autoSaveResult = true;
      Lizzie.shutdown(exitCode::set);

      assertEquals(2, harness.frame.autoSaveCalls);
      assertTrue(harness.config.persistCalled);
      assertTrue(harness.config.saveCalled);
      assertEquals(1, harness.frame.strengthShutdownCalls);
      assertEquals(1, harness.engine.forceQuitCount);
      assertEquals(0, exitCode.get());
    }
  }

  @Test
  void pendingEngineGameSaveCancelsExitUntilItsFrozenPayloadIsSaved() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.manager.engineGameSavePending = true;
      harness.manager.retrySaveResult = false;
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertEquals(1, harness.manager.retrySaveCalls);
      assertTrue(harness.manager.engineGameSavePending);
      assertEquals(0, harness.frame.autoSaveCalls);
      assertFalse(harness.config.persistCalled);
      assertFalse(harness.config.saveCalled);
      assertEquals(0, harness.frame.strengthShutdownCalls);
      assertEquals(0, harness.engine.forceQuitCount);
      assertEquals(-1, exitCode.get());

      harness.manager.retrySaveResult = true;
      Lizzie.shutdown(exitCode::set);

      assertEquals(2, harness.manager.retrySaveCalls);
      assertFalse(harness.manager.engineGameSavePending);
      assertEquals(1, harness.frame.autoSaveCalls);
      assertEquals(1, harness.engine.forceQuitCount);
      assertEquals(0, exitCode.get());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void requiredEngineGameCommitAllowsExitWithAutosaveDisabled(boolean retirementPending)
      throws Exception {
    try (Harness harness = Harness.open()) {
      harness.config.autoSaveOnExit = false;
      harness.manager.engineGameSavePending = true;
      harness.manager.retirementPending = retirementPending;
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertEquals(1, harness.manager.retrySaveCalls);
      assertEquals(retirementPending, harness.manager.engineGameSavePending);
      assertEquals(0, harness.frame.autoSaveCalls);
      assertEquals(1, harness.engine.forceQuitCount);
      assertEquals(0, exitCode.get());
    }
  }

  @Test
  void engineGameRetryRuntimeFailurePreservesPendingSaveAndCancelsExit() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.manager.engineGameSavePending = true;
      harness.manager.retrySaveFailure = new IllegalStateException("engine SGF write failed");
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertEquals(1, harness.manager.retrySaveCalls);
      assertTrue(harness.manager.engineGameSavePending);
      assertEquals(0, harness.frame.autoSaveCalls);
      assertEquals(0, harness.frame.strengthShutdownCalls);
      assertEquals(0, harness.engine.forceQuitCount);
      assertFalse(harness.config.persistCalled);
      assertEquals(-1, exitCode.get());
    }
  }

  @Test
  void failedExitAutosavePreservesRunningProductAndDoesNotFenceItsCompletion() throws Exception {
    try (Harness harness = Harness.open()) {
      EngineGameTransaction product = harness.startProduct();
      harness.frame.autoSaveResult = false;
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertSame(product, Lizzie.engineGame.transaction());
      assertTrue(Lizzie.engineGame.current().playing());
      assertFalse(product.alreadyCompleted());
      assertEquals(0, harness.engine.forceQuitCount);
      assertEquals(0, harness.frame.strengthShutdownCalls);
      assertEquals(-1, exitCode.get());
      assertTrue(
          Lizzie.engineGame.complete(
              new GameOutcome.DoublePass(), product.lifecycle().ownerToken(), 0));
      assertTrue(product.alreadyCompleted());
      assertEquals(1, Lizzie.engineGame.lastSummary().doublePassGames());
    }
  }

  @Test
  void shutdownApprovalRejectsLateCompletionWhileResourceCleanupIsStillPending() throws Exception {
    try (Harness harness = Harness.open()) {
      EngineGameTransaction product = harness.startProduct();
      harness.config.autoSaveOnExit = false;
      CompletableFuture<Void> resourceCleanup = new CompletableFuture<>();
      harness.frame.strengthShutdown = resourceCleanup;
      CompletableFuture<Integer> exited = new CompletableFuture<>();
      try {
        SwingUtilities.invokeAndWait(() -> Lizzie.shutdown(exited::complete));

        assertEquals(1, harness.frame.strengthShutdownCalls);
        assertFalse(exited.isDone());
        assertFalse(
            Lizzie.engineGame.complete(
                new GameOutcome.DoublePass(), product.lifecycle().ownerToken(), 0));
        assertFalse(product.alreadyCompleted());
        assertEquals(0, Lizzie.engineGame.lastSummary().doublePassGames());
        assertFalse(harness.manager.hasPendingEngineGameSave());

        resourceCleanup.complete(null);
        assertEquals(0, exited.get(5, TimeUnit.SECONDS));
      } finally {
        resourceCleanup.complete(null);
      }
    }
  }

  @Test
  void queuedSaveFailureCancelsThisExitAndDoesNotPoisonTheNextExit(@TempDir Path directory)
      throws Exception {
    try (Harness harness = Harness.open()) {
      Path blocked = Files.createDirectory(directory.resolve("blocked.sgf"));
      Files.writeString(blocked.resolve("keep"), "not a replaceable SGF");
      AtomicInteger exitCode = new AtomicInteger(-1);
      AtomicReference<CompletableFuture<Void>> save = new AtomicReference<>();
      Field queueField = SgfSaveCoordinator.class.getDeclaredField("SAVES");
      queueField.setAccessible(true);
      Object queue = queueField.get(null);
      Method submit =
          queue.getClass().getDeclaredMethod(
              "submit", Path.class, String.class, BooleanSupplier.class, Runnable.class,
              Consumer.class);
      submit.setAccessible(true);
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              @SuppressWarnings("unchecked")
              CompletableFuture<Void> submitted =
                  (CompletableFuture<Void>)
                      submit.invoke(
                          queue, blocked, "(;SZ[5];B[aa])", (BooleanSupplier) () -> true,
                          (Runnable) () -> {}, (Consumer<Throwable>) failure -> {});
              save.set(submitted);
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
            // The real queue's UI completion cannot run until this EDT action returns.
            Lizzie.shutdown(exitCode::set);
            assertEquals(0, harness.frame.autoSaveCalls);
            assertEquals(0, harness.frame.strengthShutdownCalls);
            assertEquals(-1, exitCode.get());
          });
      assertThrows(ExecutionException.class, () -> save.get().get(5, TimeUnit.SECONDS));
      // Drain the shutdown continuation queued by the owner's completion, including the
      // race where this thread observes future completion before that continuation is queued.
      CountDownLatch drained = new CountDownLatch(1);
      SwingUtilities.invokeAndWait(() -> SwingUtilities.invokeLater(drained::countDown));
      assertTrue(drained.await(5, TimeUnit.SECONDS));

      assertEquals(0, harness.frame.autoSaveCalls);
      assertEquals(0, harness.frame.strengthShutdownCalls);
      assertFalse(harness.config.persistCalled);
      assertFalse(harness.config.saveCalled);
      assertEquals(0, harness.engine.forceQuitCount);
      assertEquals(-1, exitCode.get());

      Lizzie.shutdown(exitCode::set);
      assertEquals(1, harness.frame.autoSaveCalls);
      assertEquals(1, harness.engine.forceQuitCount);
      assertEquals(0, exitCode.get());
    }
  }

  @Test
  void closingWithoutExitAutosaveStillPersistsConfigKillsEnginesAndExits() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.config.autoSaveOnExit = false;
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertEquals(0, harness.frame.autoSaveCalls);
      assertTrue(harness.config.persistCalled);
      assertTrue(harness.config.saveCalled);
      assertEquals(1, harness.engine.forceQuitCount);
      assertEquals(0, exitCode.get());
    }
  }

  private static final class Harness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final EngineManager previousEngineManager;
    private final Leelaz previousEngine;
    private final WebBoardManager previousWebBoardManager;
    private final Board previousBoard;
    private final RecordingConfig config;
    private final RecordingFrame frame;
    private final RecordingLeelaz engine;
    private final RecordingEngineManager manager;

    private Harness(
        Config previousConfig,
        LizzieFrame previousFrame,
        EngineManager previousEngineManager,
        Leelaz previousEngine,
        WebBoardManager previousWebBoardManager,
        Board previousBoard,
        RecordingConfig config,
        RecordingFrame frame,
        RecordingLeelaz engine,
        RecordingEngineManager manager) {
      this.previousConfig = previousConfig;
      this.previousFrame = previousFrame;
      this.previousEngineManager = previousEngineManager;
      this.previousEngine = previousEngine;
      this.previousWebBoardManager = previousWebBoardManager;
      this.previousBoard = previousBoard;
      this.config = config;
      this.frame = frame;
      this.engine = engine;
      this.manager = manager;
    }

    private static Harness open() throws Exception {
      RecordingConfig config = allocate(RecordingConfig.class);
      config.autoSaveOnExit = true;
      config.uiConfig = new JSONObject();
      RecordingFrame frame = allocate(RecordingFrame.class);
      frame.autoSaveResult = true;
      RecordingLeelaz engine = new RecordingLeelaz();
      RecordingEngineManager engineManager = new RecordingEngineManager(engine);
      Harness harness =
          new Harness(
              Lizzie.config,
              Lizzie.frame,
              Lizzie.engineManager,
              Lizzie.leelaz,
              Lizzie.webBoardManager,
              Lizzie.board,
              config,
              frame,
              engine,
              engineManager);
      Lizzie.config = config;
      Lizzie.frame = frame;
      Lizzie.engineManager = engineManager;
      Lizzie.leelaz = null;
      Lizzie.webBoardManager = null;
      Lizzie.board = null;
      Lizzie.engineGame.resetForTest();
      Lizzie.engineGame.replaceChromeForTest(transition -> {});
      return harness;
    }

    private EngineGameTransaction startProduct() throws IOException {
      manager.engineList.add(new RecordingLeelaz());
      assertInstanceOf(
          Acceptance.Accepted.class,
          Lizzie.engineGame.accept(
              EngineGameBatchSpecFactory.from(
                  EngineGameParsedStart.builder()
                      .first(FIRST)
                      .second(SECOND)
                      .genmove(true)
                      .timeLimitEnabled(true)
                      .firstTimeSeconds(1)
                      .secondTimeSeconds(1)
                      .autosave(false)
                      .build()),
              new StartObserver() {
                @Override
                public void playing() {}

                @Override
                public void startFailed(StartFailure failure) {
                  throw new AssertionError(failure);
                }
              }));
      Lizzie.engineGame.onOwnerPlaying();
      return Lizzie.engineGame.transaction();
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.engineManager = previousEngineManager;
      Lizzie.leelaz = previousEngine;
      Lizzie.webBoardManager = previousWebBoardManager;
      Lizzie.board = previousBoard;
      // The injected exit callback leaves this JVM alive; later tests need a fresh shutdown fence.
      Lizzie.engineGame.resetForTest();
    }
  }

  private static final class RecordingConfig extends Config {
    private RecordingConfig() throws IOException {}

    boolean persistCalled;
    boolean saveCalled;

    @Override
    public void persist() {
      persistCalled = true;
    }

    @Override
    public void save() {
      saveCalled = true;
    }
  }

  private static final class RecordingEngineManager extends EngineManager {
    boolean engineGameSavePending;
    boolean retirementPending;
    boolean retrySaveResult = true;
    int retrySaveCalls;
    RuntimeException retrySaveFailure;
    final Object productOwner = new Object();

    private RecordingEngineManager(Leelaz engine) {
      super(new ArrayList<>(java.util.List.of(engine)));
    }

    @Override
    public int resolveEngineGameParticipant(EngineParticipantIdentity identity) {
      if (FIRST.equals(identity)) return 0;
      if (SECOND.equals(identity)) return 1;
      return super.resolveEngineGameParticipant(identity);
    }

    @Override
    public boolean startEngineGame(EngineGamePlan plan, boolean firstGame) {
      return Lizzie.engineGame.publishOwnerIfStarting(
          plan,
          product ->
              product.attach(
                  LifecycleBinding.ofOwner(productOwner),
                  ParticipantBinding.of(
                      product, EngineGameSide.BLACK, plan.blackIndex(), plan.blackLimits(),
                      plan.playMode(), 0),
                  ParticipantBinding.of(
                      product, EngineGameSide.WHITE, plan.whiteIndex(), plan.whiteLimits(),
                      plan.playMode(), 0)));
    }

    @Override
    public boolean hasPendingEngineGameSave() {
      return engineGameSavePending;
    }

    @Override
    public boolean retryEngineGameSave() {
      retrySaveCalls++;
      if (retrySaveFailure != null) {
        throw retrySaveFailure;
      }
      if (retrySaveResult) {
        engineGameSavePending = retirementPending;
      }
      return retrySaveResult;
    }
  }

  private static final class RecordingFrame extends LizzieFrame {
    int autoSaveCalls;
    int strengthShutdownCalls;
    boolean autoSaveResult;
    RuntimeException autoSaveFailure;
    CompletableFuture<Void> strengthShutdown;

    @Override
    public boolean saveAutoGame(int index) {
      autoSaveCalls++;
      if (autoSaveFailure != null) {
        throw autoSaveFailure;
      }
      return autoSaveResult;
    }

    @Override
    public CompletableFuture<Void> shutdownHumanSlPreparation() {
      strengthShutdownCalls++;
      return strengthShutdown == null ? CompletableFuture.completedFuture(null) : strengthShutdown;
    }

    @Override
    public void closeContributeEngine() {}

    @Override
    public void shutdownClockHelper() {}

    @Override
    public void requestProblemListRefresh() {}
  }

  private static final class RecordingLeelaz extends Leelaz {
    int forceQuitCount;

    private RecordingLeelaz() throws IOException {
      super("");
    }

    @Override
    public boolean isStarted() {
      return true;
    }

    @Override
    public void forceQuit() {
      forceQuitCount++;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
