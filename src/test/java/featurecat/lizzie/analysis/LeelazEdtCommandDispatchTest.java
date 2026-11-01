package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Stone;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LeelazEdtCommandDispatchTest {
  @TempDir Path tempDir;

  private Config previousConfig;
  private Leelaz previousPrimary;
  private Leelaz previousSecondary;
  private LizzieFrame previousFrame;

  @AfterEach
  void tearDown() {
    Lizzie.config = previousConfig;
    Lizzie.setPrimaryEngine(previousPrimary);
    Lizzie.leelaz2 = previousSecondary;
    Lizzie.frame = previousFrame;
  }

  @ParameterizedTest
  @ValueSource(strings = {"play", "pda"})
  void blockedEngineOutputNeverBlocksTheSwingEventThread(String command) throws Exception {
    installGlobals();
    BlockingOutput output = new BlockingOutput();
    Leelaz engine = new Leelaz("");
    engine.installCommandOutputForTest(output);
    engine.started = engine.isLoaded = true;
    Lizzie.setPrimaryEngine(engine);

    CountDownLatch sendReturned = new CountDownLatch(1);
    CountDownLatch nextUiEventRan = new CountDownLatch(1);
    try {
      SwingUtilities.invokeLater(
          () -> {
            if (command.equals("play")) engine.sendCommandNoLeelaz2("play B D4");
            else assertTrue(engine.sendConsolePda(0.25, false));
            sendReturned.countDown();
          });

      assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS), "engine write never started");
      SwingUtilities.invokeLater(nextUiEventRan::countDown);

      assertTrue(
          sendReturned.await(1, TimeUnit.SECONDS),
          "GTP output blocked the Swing event thread");
      assertTrue(
          nextUiEventRan.await(1, TimeUnit.SECONDS),
          "the next user input could not run while engine output was stalled");
      assertFalse(output.writeRanOnEventThread.get(), "physical GTP output ran on the Swing EDT");
    } finally {
      output.releaseWrite.countDown();
      output.writeCompleted.await(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void queuedLocalPlayRetiresOldAnalysisBeforePhysicalWriteCompletes() throws Exception {
    installGlobals();
    BlockingOutput output = new BlockingOutput();
    Leelaz engine = new Leelaz("");
    engine.installCommandOutputForTest(output);
    Lizzie.setPrimaryEngine(engine);
    MoveData staleMove = new MoveData();
    staleMove.coordinate = "D4";
    staleMove.playouts = 100;
    engine.setBestMovesForEngineGameTest(List.of(staleMove));

    try {
      SwingUtilities.invokeAndWait(() -> engine.playMove(Stone.BLACK, "D4"));

      assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS), "play write never started");
      assertTrue(
          engine.getBestMoves().isEmpty(),
          "the previous streaming payload remained visible while play was queued");
    } finally {
      output.releaseWrite.countDown();
      output.writeCompleted.await(2, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void consolePdaAdmissionDoesNotWaitForAPrecedingPhysicalWrite(boolean dynamic) throws Exception {
    installGlobals();
    BlockingOutput output = new BlockingOutput();
    Leelaz engine = new Leelaz("");
    engine.installCommandOutputForTest(output);
    engine.started = engine.isLoaded = true;
    Lizzie.setPrimaryEngine(engine);
    CountDownLatch submitted = new CountDownLatch(1);
    var admission = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>>();
    CountDownLatch nextEvent = new CountDownLatch(1);
    try {
      SwingUtilities.invokeLater(() -> engine.sendCommandNoLeelaz2("play B D4"));
      assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
      SwingUtilities.invokeLater(() -> {
        var target = engine.captureConsolePdaTarget(
            Lizzie.capturePrimaryEngineGenerationSnapshot(engine), engine.engineIncarnationSnapshot());
        admission.set(engine.sendConsolePdaAsync(0.25, dynamic, target));
        submitted.countDown();
      });
      SwingUtilities.invokeLater(nextEvent::countDown);
      assertTrue(submitted.await(1, TimeUnit.SECONDS), "PDA submission waited for earlier engine I/O");
      assertTrue(nextEvent.await(1, TimeUnit.SECONDS), "PDA blocked the next Swing event");
      assertFalse(admission.get().isDone(), "a waiting request is not an admitted command");
    } finally {
      output.releaseWrite.countDown();
      assertTrue(output.writeCompleted.await(2, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {});
    }
    assertTrue(admission.get().get(3, TimeUnit.SECONDS));
  }

  @Test
  void primaryRetryReleasesAnalysisFenceForRootReplay() throws Exception {
    installGlobals();
    Leelaz engine = new Leelaz("");
    engine.installCommandOutputForTest(new java.io.ByteArrayOutputStream());
    engine.started = engine.isLoaded = true;
    Lizzie.setPrimaryEngine(engine);
    long generation = Lizzie.capturePrimaryEngineGeneration(engine);
    Field selectionField = EngineManager.class.getDeclaredField("ENGINE_SELECTION_STATE_LOCK");
    selectionField.setAccessible(true);
    Object selection = selectionField.get(null);
    Field fenceField = EngineManager.class.getDeclaredField("ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK");
    fenceField.setAccessible(true);
    var fence = (java.util.concurrent.locks.ReentrantLock) fenceField.get(null);
    var admitted = new CompletableFuture<Boolean>();
    var replayed = new CompletableFuture<Boolean>();
    CountDownLatch primaryOwned = new CountDownLatch(1);
    CountDownLatch startReplay = new CountDownLatch(1);
    Thread sender = new Thread(() -> {
      try { admitted.complete(engine.sendConsolePda(0.25, false)); }
      catch (Throwable failure) { admitted.completeExceptionally(failure); }
    }, "pda-admission");
    Thread restore = new Thread(() -> {
      try {
        Lizzie.runIfPrimaryEngine(engine, generation, () -> {
          primaryOwned.countDown();
          try {
            if (!startReplay.await(3, TimeUnit.SECONDS)) throw new AssertionError("replay not released");
            // Bounded claim detects the former deadlock without leaving a deadlocked test JVM.
            boolean available = fence.tryLock(1, TimeUnit.SECONDS);
            if (available) {
              try { engine.sendCommandNoLeelaz2("play B D4"); }
              finally { fence.unlock(); }
            }
            replayed.complete(available);
          } catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
      } catch (Throwable failure) { replayed.completeExceptionally(failure); }
    }, "primary-root-replay");
    try {
      synchronized (selection) {
        sender.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (sender.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
          Thread.sleep(5);
        org.junit.jupiter.api.Assertions.assertEquals(Thread.State.BLOCKED, sender.getState());
        restore.start();
        assertTrue(primaryOwned.await(2, TimeUnit.SECONDS));
      }
      startReplay.countDown();
      assertTrue(replayed.get(3, TimeUnit.SECONDS), "PDA retained the analysis fence while waiting for PRIMARY");
      assertTrue(admitted.get(3, TimeUnit.SECONDS));
    } finally {
      startReplay.countDown();
      sender.join(3000);
      restore.join(3000);
      assertFalse(sender.isAlive());
      assertFalse(restore.isAlive());
    }
  }

  private void installGlobals() throws Exception {
    previousConfig = Lizzie.config;
    previousPrimary = Lizzie.leelaz;
    previousSecondary = Lizzie.leelaz2;
    previousFrame = Lizzie.frame;
    Lizzie.config = ConfigTestHelper.createForTests(tempDir);
    Lizzie.leelaz2 = null;
    Lizzie.frame = allocate(LizzieFrame.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void realConsoleReponderHandsOffBeforePhysicalOutputCompletes(boolean mirrored) throws Exception {
    installGlobals();
    var previousBoard = Lizzie.board;
    var previousMenu = LizzieFrame.menu;
    BlockingOutput output = new BlockingOutput();
    try {
      Lizzie.board = new featurecat.lizzie.rules.Board();
      Lizzie.frame = allocate(QuietFrame.class);
      LizzieFrame.menu = allocate(QuietMenu.class);
      Leelaz engine = new PonderingEngine();
      engine.started = engine.isLoaded = engine.isKatago = true;
      engine.installCommandOutputForTest(output);
      Lizzie.setPrimaryEngine(engine);
      if (mirrored) {
        Leelaz mirror = new Leelaz("");
        mirror.started = mirror.isLoaded = mirror.isKatago = true;
        mirror.installCommandOutputForTest(new java.io.ByteArrayOutputStream());
        Lizzie.leelaz2 = mirror;
        Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      }
      var target = engine.captureConsolePdaTarget(
          Lizzie.capturePrimaryEngineGenerationSnapshot(engine), engine.engineIncarnationSnapshot());
      var resumed = engine.resumeConsolePonderAsync(target);
      assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
      resumed.get(1, TimeUnit.SECONDS);
      assertFalse(output.writeRanOnEventThread.get());
    } finally {
      output.releaseWrite.countDown();
      output.writeCompleted.await(2, TimeUnit.SECONDS);
      Lizzie.board = previousBoard;
      LizzieFrame.menu = previousMenu;
    }
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, false", "false, true", "true, true"})
  void consoleReponderPreservesUserPauseInEitherOrder(boolean mirrored, boolean reponderFirst)
      throws Exception {
    installGlobals();
    var previousBoard = Lizzie.board;
    var previousMenu = LizzieFrame.menu;
    var output = new java.io.ByteArrayOutputStream();
    PauseRaceEngine engine = new PauseRaceEngine(reponderFirst);
    CompletableFuture<Void> resumed = null;
    CompletableFuture<Void> paused = new CompletableFuture<>();
    var pauseThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    CountDownLatch pauseStarted = new CountDownLatch(1);
    try {
      Lizzie.board = new featurecat.lizzie.rules.Board();
      Lizzie.frame = allocate(QuietFrame.class);
      LizzieFrame.menu = allocate(QuietMenu.class);
      engine.started = engine.isLoaded = engine.isKatago = true;
      engine.installCommandOutputForTest(output);
      engine.Pondering();
      Lizzie.setPrimaryEngine(engine);
      if (mirrored) {
        Leelaz mirror = new Leelaz("");
        mirror.started = mirror.isLoaded = mirror.isKatago = true;
        mirror.installCommandOutputForTest(new java.io.ByteArrayOutputStream());
        Lizzie.leelaz2 = mirror;
        Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      }
      var target = engine.captureConsolePdaTarget(
          Lizzie.capturePrimaryEngineGenerationSnapshot(engine), engine.engineIncarnationSnapshot());
      resumed = engine.resumeConsolePonderAsync(target);
      assertTrue(engine.resumeAtBoundary.await(2, TimeUnit.SECONDS));

      Field pauseFlag = LizzieFrame.class.getDeclaredField("userAnalysisPaused");
      pauseFlag.setAccessible(true);
      SwingUtilities.invokeLater(() -> {
        pauseThread.set(Thread.currentThread());
        pauseStarted.countDown();
        try {
          engine.pauseForAnalysisControl(() -> {
            try { pauseFlag.setBoolean(Lizzie.frame, true); }
            catch (IllegalAccessException failure) { throw new AssertionError(failure); }
          });
          paused.complete(null);
        } catch (Throwable failure) {
          paused.completeExceptionally(failure);
        }
      });
      assertTrue(pauseStarted.await(2, TimeUnit.SECONDS));
      if (reponderFirst) {
        // Let pause contend after the worker has passed its policy check but before real ponder.
        // With the fix it waits for admission; without it pause finishes and is then overwritten.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!paused.isDone() && pauseThread.get().getState() != Thread.State.BLOCKED
            && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(paused.isDone() || pauseThread.get().getState() == Thread.State.BLOCKED,
            "pause did not reach the reponder boundary");
      } else {
        // Complete pause after the worker captured true, then let it use that stale observation.
        paused.get(2, TimeUnit.SECONDS);
      }
      engine.continueResume.countDown();
      resumed.get(3, TimeUnit.SECONDS);
      paused.get(3, TimeUnit.SECONDS);
      assertTrue(Lizzie.frame.isUserAnalysisPaused());
      assertFalse(engine.isPondering(), "console handback restarted analysis after user pause");
      if (!reponderFirst) {
        assertFalse(engine.ponderEntered.get(), "a completed pause must reject reponder entirely");
        assertFalse(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("kata-analyze"));
      }
    } finally {
      engine.continueResume.countDown();
      try {
        if (resumed != null) resumed.get(3, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> {});
      } finally {
        Lizzie.board = previousBoard;
        LizzieFrame.menu = previousMenu;
      }
    }
  }

  /** Gates scheduling only: production pause, ponder, target validation and queue admission run. */
  private static final class PauseRaceEngine extends Leelaz {
    private final boolean reponderFirst;
    private final AtomicBoolean capturePondering = new AtomicBoolean(true);
    private final AtomicBoolean ponderEntered = new AtomicBoolean();
    private final CountDownLatch resumeAtBoundary = new CountDownLatch(1);
    private final CountDownLatch continueResume = new CountDownLatch(1);

    PauseRaceEngine(boolean reponderFirst) throws IOException {
      super("");
      this.reponderFirst = reponderFirst;
    }

    @Override public boolean isPondering() {
      boolean captured = super.isPondering();
      if (!reponderFirst && capturePondering.compareAndSet(true, false)) awaitPauseRace();
      return captured;
    }

    @Override public void ponder(boolean addPlayer, boolean blackToPlay) {
      ponderEntered.set(true);
      if (reponderFirst) awaitPauseRace();
      super.ponder(addPlayer, blackToPlay);
    }

    private void awaitPauseRace() {
      resumeAtBoundary.countDown();
      try {
        if (!continueResume.await(5, TimeUnit.SECONDS))
          throw new AssertionError("console reponder was not released");
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AssertionError(failure);
      }
    }
  }

  private static final class PonderingEngine extends Leelaz {
    PonderingEngine() throws IOException { super(""); }
    @Override public boolean isPondering() { return true; }
  }

  private static final class QuietFrame extends LizzieFrame {
    @Override public void onMainEnginePonder() {}
    @Override public void clearSelectImage() {}
  }

  private static final class QuietMenu extends featurecat.lizzie.gui.Menu {
    @Override public void toggleEngineMenuStatus(boolean pondering, boolean thinking) {}
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class BlockingOutput extends OutputStream {
    private final CountDownLatch writeEntered = new CountDownLatch(1);
    private final CountDownLatch releaseWrite = new CountDownLatch(1);
    private final CountDownLatch writeCompleted = new CountDownLatch(1);
    private final AtomicBoolean writeRanOnEventThread = new AtomicBoolean();

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      writeRanOnEventThread.set(SwingUtilities.isEventDispatchThread());
      writeEntered.countDown();
      try {
        if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
          throw new IOException("test output was not released");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("test output interrupted", interrupted);
      } finally {
        writeCompleted.countDown();
      }
    }
  }
}
