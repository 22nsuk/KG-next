package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.AnalysisEngineSSHController;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.EngineStartupDiagnostics;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
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
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.SgfSaveCoordinator;
import featurecat.lizzie.gui.web.WebBoardManager;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.DelayedTerminationProcess;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.MeasuredKataGoTuning;
import featurecat.lizzie.util.NvidiaGpuDiscoveryFixture;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.katago.tuning.KataGoMeasuredFingerprint;
import featurecat.lizzie.util.katago.tuning.KataGoMeasuredReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LizzieShutdownTest {
  private static final EngineParticipantIdentity FIRST =
      new EngineParticipantIdentity("shutdown-first", "");
  private static final EngineParticipantIdentity SECOND =
      new EngineParticipantIdentity("shutdown-second", "");

  @Test
  void exitDuringWholeGameProfileGpuDetectionWaitsForTheUnattachedConstructor(@TempDir Path root)
      throws Exception {
    NvidiaGpuDiscoveryFixture.runFork(getClass(), "whole-game-profile", root);
  }

  static void runGpuScenario(String scenario, Path root) throws Exception {
    assertEquals("whole-game-profile", scenario);
    try (Harness harness = Harness.open();
        NvidiaGpuDiscoveryFixture fixture = new NvidiaGpuDiscoveryFixture(root)) {
      Path executable = Files.writeString(root.resolve("katago"),
          "#!/bin/sh\n: > \"$(dirname \"$0\")/main.started\"\n");
      assertTrue(executable.toFile().setExecutable(true));
      Path model = Files.writeString(root.resolve("model.bin.gz"), "measured model");
      Path config = Files.writeString(root.resolve("analysis.cfg"), "numAnalysisThreads = 8\n");
      String command = "\"" + executable + "\" analysis -model \"" + model + "\" -config \"" + config + "\"";
      harness.config.config = new JSONObject();
      harness.config.leelazConfig = new JSONObject();
      harness.config.analysisReuseCurrentEngine = false;
      harness.config.analysisEngineCommand = command;
      EngineData entry = new EngineData();
      entry.id = "whole-game-gpu-shutdown";
      entry.name = "Measured local engine";
      entry.commands = command;
      JSONObject report = wholeGameReport(entry);
      assertTrue(KataGoMeasuredReport.parse(report).assess().eligible());
      entry.threadPolicy = new JSONObject().put(MeasuredKataGoTuning.KEY,
          new JSONObject().put("whole-game", new JSONObject().put("schemaVersion", 1)
              .put("command", command).put("report", report)));
      harness.config.leelazConfig.put("engine-settings-list",
          new JSONArray().put(new JSONObject().put("id", entry.id).put("name", entry.name)
              .put("command", entry.commands).put("threadPolicy", entry.threadPolicy)));
      JSONObject savedProfile = new JSONObject(entry.threadPolicy.toString());
      FutureTask<AnalysisEngine> startup = new FutureTask<>(
          () -> new AnalysisEngine(false, AnalysisEngine.Workload.WHOLE_GAME, 5000));
      Thread starter = new Thread(startup, "whole-game-profile-constructor");
      CompletableFuture<Integer> exited = new CompletableFuture<>();
      AtomicBoolean queryAliveAtExit = new AtomicBoolean(true);
      CountDownLatch nextEdtEvent = new CountDownLatch(1);
      try {
        starter.start();
        ProcessHandle query = fixture.awaitQuery();
        assertNull(harness.frame.analysisEngine, "constructor was already attached to the frame");
        SwingUtilities.invokeLater(() -> Lizzie.shutdown(code -> {
          queryAliveAtExit.set(query.isAlive());
          exited.complete(code);
        }));
        SwingUtilities.invokeLater(nextEdtEvent::countDown);
        assertTrue(nextEdtEvent.await(5, TimeUnit.SECONDS), "GPU cancellation blocked the EDT");
        assertEquals(0, exited.get(5, TimeUnit.SECONDS));
        assertFalse(queryAliveAtExit.get(), "app exit completed before the GPU query child exited");
        AnalysisEngine analysis = startup.get(5, TimeUnit.SECONDS);
        assertFalse(analysis.isLoaded());
        assertNull(analysis.process);
        assertNull(analysis.startupDiagnosticAttempt());
        assertFalse(Files.exists(root.resolve("main.started")));
        assertEquals(1, fixture.queryCount(), "shutdown started another GPU query");
        assertTrue(savedProfile.similar(Utils.getEngineData().get(0).threadPolicy));
      } finally {
        fixture.release();
        CompletableFuture<Void> cleanup = AnalysisEngine.shutdownAllResources();
        starter.interrupt();
        starter.join(5000);
        assertFalse(starter.isAlive());
        cleanup.get(5, TimeUnit.SECONDS);
      }
    }
  }

  private static JSONObject wholeGameReport(EngineData entry) throws Exception {
    JSONObject fingerprint = KataGoMeasuredFingerprint.capture(
        KataGoAutoSetupHelper.inspectSavedEngine(entry), NvidiaGpuDiscoveryFixture.gpu(),
        Utils.splitCommand(KataGoRuntimeHelper.optimizeAnalysisEngineCommand(
            entry.commands, 5000, false, false)));
    JSONArray runs = new JSONArray();
    for (int round = 1; round <= 3; round++) {
      for (int member = 0; member < 2; member++) {
        boolean baseline = (round + member) % 2 == 1;
        runs.put(new JSONObject().put("profile", baseline ? "baseline" : "candidate")
            .put("round", round).put("phase", "warm").put("seconds", baseline ? 10 : 8)
            .put("responseSeconds", .05).put("edtP95Seconds", .01)
            .put("maxMemoryMiB", 2000).put("maxEngineProcesses", 1)
            .put("rootVisitsByTurn", new JSONArray(List.of(5000, 5000))));
      }
    }
    return new JSONObject().put("schemaVersion", 1).put("scene", "whole-game")
        .put("measurementMode", "application").put("fingerprint", fingerprint)
        .put("fixtureSha256", "d".repeat(64)).put("budget", 5000).put("positions", 2)
        .put("metricScope", "totalGpu")
        .put("baselineParameters", new JSONObject().put("numAnalysisThreads", 8)
            .put("numSearchThreadsPerAnalysisThread", 2).put("nnMaxBatchSize", 16))
        .put("candidateParameters", new JSONObject().put("numAnalysisThreads", 12)
            .put("numSearchThreadsPerAnalysisThread", 2).put("nnMaxBatchSize", 32))
        .put("runs", runs);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void exitDuringRuntimePreflightSkipsMainProcessAndFailureDiagnostic(
      boolean cancelledProbe, @TempDir Path directory) throws Exception {
    try (Harness harness = Harness.open()) {
      AnalysisEngine analysis = analysisEngine();
      Path executable = directory.resolve("engines/katago/windows-x64-nvidia/katago.exe");
      Files.createDirectories(executable.getParent());
      Files.createFile(executable);
      CountDownLatch preflightEntered = new CountDownLatch(1);
      CountDownLatch releasePreflight = new CountDownLatch(1);
      AtomicInteger mainStarts = new AtomicInteger();
      FutureTask<Void> startup = new FutureTask<>(() -> {
        startAnalysis(analysis, "\"" + executable + "\" analysis", (proxy, method, arguments) -> {
          if (method.getName().equals("prepareRuntime")) {
            preflightEntered.countDown();
            awaitGate(releasePreflight);
            if (cancelledProbe)
              throw new InterruptedIOException("CUDA compatibility probe cancelled");
            return null;
          }
          mainStarts.incrementAndGet();
          throw new AssertionError("exit allowed the main engine to launch after preflight");
        });
        return null;
      });
      Thread starter = new Thread(startup, "analysis-preflight-test-starter");
      CompletableFuture<Integer> exited = new CompletableFuture<>();
      CountDownLatch nextEdtEvent = new CountDownLatch(1);
      try {
        starter.start();
        assertTrue(preflightEntered.await(5, TimeUnit.SECONDS));
        assertNull(harness.frame.analysisEngine);
        SwingUtilities.invokeLater(() -> Lizzie.shutdown(exited::complete));
        SwingUtilities.invokeLater(nextEdtEvent::countDown);
        assertTrue(nextEdtEvent.await(5, TimeUnit.SECONDS), "preflight cancellation blocked the EDT");
        assertFalse(exited.isDone(), "exit did not wait for preflight cleanup");
        releasePreflight.countDown();
        startup.get(5, TimeUnit.SECONDS);
        assertEquals(0, exited.get(5, TimeUnit.SECONDS));
        assertEquals(0, mainStarts.get());
        assertFalse(analysis.isLoaded());
        EngineStartupDiagnostics.Attempt diagnostic =
            (EngineStartupDiagnostics.Attempt) analysisField("startupDiagnosticAttempt").get(analysis);
        assertNull(diagnostic.snapshot(), "shutdown must not publish a runtime-preflight failure");
      } finally {
        releasePreflight.countDown();
        CompletableFuture<Void> cleanup = AnalysisEngine.shutdownAllResources();
        starter.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(starter.isAlive());
        cleanup.get(5, TimeUnit.SECONDS);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"handoff", "failed", "cancelled"})
  void exitWaitsForPreparationChildAfterTheStartupCallReturns(
      String outcome, @TempDir Path directory) throws Exception {
    try (Harness harness = Harness.open();
        DelayedTerminationProcess probe = new DelayedTerminationProcess(directory);
        GatedAnalysisProcess main = new GatedAnalysisProcess()) {
      AnalysisEngine analysis = analysisEngine();
      Path executable = directory.resolve("engines/katago/windows-x64-nvidia/katago.exe");
      Files.createDirectories(executable.getParent());
      Files.createFile(executable);
      CountDownLatch prepared = new CountDownLatch(1);
      CountDownLatch releasePreparation = new CountDownLatch(outcome.equals("cancelled") ? 1 : 0);
      AtomicInteger mainStarts = new AtomicInteger();
      FutureTask<Void> startup = new FutureTask<>(() -> {
        startAnalysis(analysis, "\"" + executable + "\" analysis", (proxy, method, arguments) -> {
          if (method.getName().equals("prepareRuntime")) {
            @SuppressWarnings("unchecked")
            Consumer<Process> started = (Consumer<Process>) arguments[2];
            Method waitForProbe = KataGoRuntimeHelper.class.getDeclaredMethod(
                "awaitCompatibilityProbe", Process.class, StringBuilder.class,
                BooleanSupplier.class, Consumer.class, long.class);
            waitForProbe.setAccessible(true);
            // Exercise the production timeout/registration boundary with a short time budget.
            assertFalse((boolean) waitForProbe.invoke(
                null, probe, new StringBuilder(), (BooleanSupplier) () -> false, started, 25L));
            prepared.countDown();
            awaitGate(releasePreparation);
            if (!outcome.equals("handoff")) throw new IOException("probe timed out");
            return null;
          }
          assertEquals("start", method.getName());
          mainStarts.incrementAndGet();
          return main;
        });
        return null;
      });
      Thread starter = new Thread(startup, "delayed-preparation-exit");
      CompletableFuture<Integer> exited = new CompletableFuture<>();
      AtomicBoolean childAliveAtExit = new AtomicBoolean(true);
      CountDownLatch nextEdtEvent = new CountDownLatch(1);
      try {
        main.allowDestroy.countDown();
        starter.start();
        assertTrue(prepared.await(5, TimeUnit.SECONDS));
        assertTrue(probe.awaitTerminationRequest(5, TimeUnit.SECONDS));
        assertTrue(probe.isAlive(), "the OS child must still be awaiting termination");
        if (!outcome.equals("cancelled")) startup.get(5, TimeUnit.SECONDS);
        assertNull(harness.frame.analysisEngine, "exit must not depend on frame attachment");
        SwingUtilities.invokeLater(() -> Lizzie.shutdown(code -> {
          childAliveAtExit.set(probe.isAlive());
          exited.complete(code);
        }));
        SwingUtilities.invokeLater(nextEdtEvent::countDown);
        assertTrue(nextEdtEvent.await(5, TimeUnit.SECONDS), "probe cleanup blocked the EDT");
        releasePreparation.countDown();
        startup.get(5, TimeUnit.SECONDS);
        assertEquals(outcome.equals("handoff") ? 1 : 0, mainStarts.get());
        assertFalse(exited.isDone(), "exit released a timed-out preparation child");

        probe.finishTermination();
        assertEquals(0, exited.get(5, TimeUnit.SECONDS));
        assertFalse(childAliveAtExit.get(), "exit callback ran before physical process exit");
        assertFalse(analysis.isLoaded());
      } finally {
        releasePreparation.countDown();
        probe.finishTermination();
        main.allowDestroy.countDown();
        starter.join(5000);
        assertFalse(starter.isAlive());
        AnalysisEngine.shutdownAllResources().get(5, TimeUnit.SECONDS);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"remote,false", "remote,true", "ssh,false", "ssh,true"})
  void exitCancelsConnectingAnalysisAndWaitsForItsFirstClose(
      String backend, boolean beforeCreation) throws Exception {
    try (Harness harness = Harness.open()) {
      AnalysisEngine analysis = analysisEngine();
      analysis.useJavaSSH = backend.equals("ssh");
      GatedAnalysisConnection connection = new GatedAnalysisConnection();
      CountDownLatch creating = new CountDownLatch(1);
      CountDownLatch releaseCreation = new CountDownLatch(beforeCreation ? 1 : 0);
      String command = analysis.useJavaSSH ? "test-analysis" : RemoteComputeConfig.COMMAND_ZHIZI;
      FutureTask<Void> startup = new FutureTask<>(() -> {
        startAnalysis(analysis, command, (proxy, method, arguments) -> {
          creating.countDown();
          awaitGate(releaseCreation);
          return switch (method.getName()) {
            case "createTransport" -> connection;
            case "createSsh" -> new AnalysisEngineSSHController(analysis, "127.0.0.1", "22", true) {
              @Override
              public Boolean login(String ignoredCommand, String user, String password) {
                try {
                  connection.start();
                  return Boolean.TRUE;
                } catch (IOException cancelled) {
                  return Boolean.FALSE;
                }
              }

              @Override
              public void close() {
                connection.close();
                super.close();
              }
            };
            default -> throw new AssertionError("unexpected local process launch");
          };
        });
        return null;
      });
      Thread starter = new Thread(startup, "analysis-connection-test-starter");
      CompletableFuture<Integer> exited = new CompletableFuture<>();
      FutureTask<Void> shutdown = new FutureTask<>(() -> {
        Lizzie.shutdown(exited::complete);
        return null;
      });
      CountDownLatch nextEdtEvent = new CountDownLatch(1);
      try {
        starter.start();
        assertTrue((beforeCreation ? creating : connection.startEntered).await(5, TimeUnit.SECONDS));
        assertNull(harness.frame.analysisEngine, "startup must not depend on frame registration");
        SwingUtilities.invokeLater(shutdown);
        SwingUtilities.invokeLater(nextEdtEvent::countDown);
        assertTrue(nextEdtEvent.await(5, TimeUnit.SECONDS), "connection close blocked the EDT");
        shutdown.get(5, TimeUnit.SECONDS);
        CompletableFuture<Void> cleanup = AnalysisEngine.shutdownAllResources();
        assertFalse(exited.isDone(), "exit ignored an in-flight startup");

        releaseCreation.countDown();
        assertTrue(connection.closeEntered.await(5, TimeUnit.SECONDS), "exit did not cancel connect");
        assertFalse(connection.closedOnEdt, "network cleanup must run off the EDT");
        startup.get(5, TimeUnit.SECONDS);
        assertTrue(connection.duplicateCloseReturned.await(5, TimeUnit.SECONDS));
        assertEquals(beforeCreation ? 0 : 1, connection.starts.get());
        assertEquals(backend.equals("remote") ? 1 : 0, connection.aborts.get(),
            "unfinished transports must use physical abort, not a close handshake");
        assertFalse(cleanup.isDone(), "a duplicate close returned before the first close finished");
        assertFalse(exited.isDone(), "JVM exit ran while connection cleanup was blocked");

        connection.allowClose.countDown();
        cleanup.get(5, TimeUnit.SECONDS);
        assertEquals(0, exited.get(5, TimeUnit.SECONDS));
        assertTrue(connection.closeReturned);
        assertFalse(analysis.isLoaded(), "cancelled startup published a loaded engine");
      } finally {
        releaseCreation.countDown();
        connection.allowClose.countDown();
        connection.close();
        starter.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(starter.isAlive(), "cancelled startup did not finish");
        analysis.shutdown();
        awaitAnalysisCleanup(analysis);
        exited.get(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void exitWaitsForPendingConnectionCleanupAfterStartAndAbortReturn() throws Exception {
    try (Harness harness = Harness.open()) {
      AnalysisEngine analysis = analysisEngine();
      CompletableFuture<Void> pendingConnection = new CompletableFuture<>();
      GatedAnalysisConnection connection = new GatedAnalysisConnection(pendingConnection);
      FutureTask<Void> startup = new FutureTask<>(() -> {
        startAnalysis(analysis, RemoteComputeConfig.COMMAND_ZHIZI, (proxy, method, arguments) -> {
          assertEquals("createTransport", method.getName());
          return connection;
        });
        return null;
      });
      Thread starter = new Thread(startup, "timed-out-analysis-connection");
      CompletableFuture<Integer> exited = new CompletableFuture<>();
      CountDownLatch nextEdtEvent = new CountDownLatch(1);
      Object registryLock = analysisField("ACTIVE_READER_RESOURCES").get(null);
      Set<?> pendingStartups = (Set<?>) analysisField("STARTING_ENGINES").get(null);
      try {
        starter.start();
        startup.get(5, TimeUnit.SECONDS);
        assertEquals(1, connection.starts.get());
        assertFalse(analysis.isLoaded());
        assertNull(harness.frame.analysisEngine, "startup was never attached to the frame");
        Object reservation;
        synchronized (registryLock) {
          assertEquals(1, pendingStartups.size(), "timed-out connect lost its cleanup reservation");
          reservation = pendingStartups.iterator().next();
        }

        SwingUtilities.invokeLater(() -> Lizzie.shutdown(exited::complete));
        SwingUtilities.invokeLater(nextEdtEvent::countDown);
        assertTrue(nextEdtEvent.await(5, TimeUnit.SECONDS), "pending connect blocked the EDT");
        assertTrue(connection.closeEntered.await(5, TimeUnit.SECONDS));
        assertFalse(connection.closedOnEdt);
        CompletableFuture<Void> cleanup = AnalysisEngine.shutdownAllResources();
        connection.close();
        assertTrue(connection.duplicateCloseReturned.await(5, TimeUnit.SECONDS));
        assertFalse(cleanup.isDone(), "a duplicate close released asynchronous connect cleanup");

        Field connectionClosed = reservation.getClass().getDeclaredField("connectionClosed");
        connectionClosed.setAccessible(true);
        CompletableFuture<?> firstAbort = (CompletableFuture<?>) connectionClosed.get(reservation);
        connection.allowClose.countDown();
        firstAbort.get(5, TimeUnit.SECONDS);
        assertTrue(connection.closeReturned);
        assertEquals(1, connection.aborts.get());
        assertFalse(pendingConnection.isDone());
        assertFalse(cleanup.isDone(), "abort return was mistaken for late connect cleanup");
        assertFalse(exited.isDone(), "exit ran while the transport still owned pending cleanup");
        synchronized (registryLock) {
          assertTrue(pendingStartups.contains(reservation));
        }

        pendingConnection.complete(null);
        cleanup.get(5, TimeUnit.SECONDS);
        assertEquals(0, exited.get(5, TimeUnit.SECONDS));
        synchronized (registryLock) {
          assertTrue(pendingStartups.isEmpty(), "completed cleanup retained its startup reservation");
        }
      } finally {
        pendingConnection.complete(null);
        connection.allowClose.countDown();
        connection.close();
        starter.interrupt();
        starter.join(5000);
        assertFalse(starter.isAlive());
        AnalysisEngine.shutdownAllResources().get(5, TimeUnit.SECONDS);
      }
    }
  }

  /** Mirrors the transports' idempotent close: it wakes start before physical cleanup returns. */
  private static final class GatedAnalysisConnection implements EngineTransport {
    private final CompletableFuture<Void> pendingStartup;
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger aborts = new AtomicInteger();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CountDownLatch startEntered = new CountDownLatch(1);
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final CountDownLatch duplicateCloseReturned = new CountDownLatch(1);
    private final CountDownLatch allowClose = new CountDownLatch(1);
    private volatile boolean closedOnEdt;
    private volatile boolean closeReturned;

    private GatedAnalysisConnection() {
      this(null);
    }

    private GatedAnalysisConnection(CompletableFuture<Void> pendingStartup) {
      this.pendingStartup = pendingStartup;
    }

    @Override
    public void start(Consumer<CompletableFuture<Void>> pendingCompletion) throws IOException {
      if (pendingStartup == null) {
        EngineTransport.super.start(pendingCompletion);
        return;
      }
      pendingCompletion.accept(pendingStartup);
      starts.incrementAndGet();
      startEntered.countDown();
      throw new IOException("connect result deadline elapsed before connection cleanup");
    }

    @Override
    public void start() throws IOException {
      starts.incrementAndGet();
      startEntered.countDown();
      try {
        assertTrue(closeEntered.await(15, TimeUnit.SECONDS), "connection was not cancelled");
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AssertionError(failure);
      }
      throw new IOException("connection cancelled");
    }

    @Override
    public void close() {
      if (!closing.compareAndSet(false, true)) {
        duplicateCloseReturned.countDown();
        return;
      }
      closedOnEdt = SwingUtilities.isEventDispatchThread();
      closeEntered.countDown();
      awaitGate(allowClose);
      closeReturned = true;
    }

    @Override public InputStream stdout() { throw new AssertionError("cancelled connection"); }
    @Override public OutputStream stdin() { throw new AssertionError("cancelled connection"); }
    @Override public InputStream stderr() { throw new AssertionError("cancelled connection"); }
    @Override public boolean isOpen() { return false; }
    @Override public String description() { return "controlled connection"; }
    @Override public void abort() { aborts.incrementAndGet(); close(); }
  }

  @ParameterizedTest
  @ValueSource(strings = {"attached", "detached", "starting", "initializing", "initialize-failure"})
  void exitWaitsForAnalysisCleanupWithoutBlockingEdt(String phase) throws Exception {
    try (Harness harness = Harness.open();
        GatedAnalysisProcess process = new GatedAnalysisProcess()) {
      AnalysisEngine analysis = analysisEngine();
      CountDownLatch startupEntered = new CountDownLatch(1);
      CountDownLatch releaseStartup = new CountDownLatch(1);
      Runnable pauseStartup = () -> {
        startupEntered.countDown();
        awaitGate(releaseStartup);
      };
      boolean paused = !phase.equals("attached") && !phase.equals("detached");
      boolean failedInitialization = phase.equals("initialize-failure");
      if (phase.equals("initializing") || failedInitialization) {
        process.beforeStdout = () -> {
          pauseStartup.run();
          if (failedInitialization) throw new IllegalStateException("controlled stream failure");
        };
      }
      FutureTask<Void> startup = new FutureTask<>(() -> {
        startAnalysis(analysis, () -> {
          if (phase.equals("starting")) pauseStartup.run();
          return process;
        });
        return null;
      });
      Thread starter = new Thread(startup, "analysis-shutdown-test-starter");
      CompletableFuture<Integer> exited = new CompletableFuture<>();
      FutureTask<Void> shutdown =
          new FutureTask<>(
              () -> {
                Lizzie.shutdown(exited::complete);
                return null;
              });
      CountDownLatch nextEdtEvent = new CountDownLatch(1);
      try {
        starter.start();
        if (paused) {
          assertTrue(startupEntered.await(5, TimeUnit.SECONDS), "startup did not reach its gate");
          assertNull(harness.frame.analysisEngine, "the starting engine must not be in the frame");
        } else {
          startup.get(5, TimeUnit.SECONDS);
          harness.frame.analysisEngine = analysis;
          if (phase.equals("detached")) {
            analysis.normalQuit();
            harness.frame.analysisEngine = null;
            // Exercise the shutdown path without an EngineManager as well.
            Lizzie.engineManager = null;
          }
        }
        SwingUtilities.invokeLater(shutdown);
        SwingUtilities.invokeLater(nextEdtEvent::countDown);

        assertTrue(nextEdtEvent.await(5, TimeUnit.SECONDS), "shutdown blocked the EDT");
        shutdown.get(5, TimeUnit.SECONDS);
        assertFalse(exited.isDone(), "JVM exit ran before the starting engine was accounted for");
        releaseStartup.countDown();
        if (failedInitialization) {
          ExecutionException failure =
              assertThrows(ExecutionException.class, () -> startup.get(5, TimeUnit.SECONDS));
          assertInstanceOf(IllegalStateException.class, failure.getCause());
        } else {
          startup.get(5, TimeUnit.SECONDS);
        }
        assertTrue(process.destroyEntered.await(5, TimeUnit.SECONDS));
        assertTrue(process.isAlive(), "the child must still be running behind the cleanup gate");
        assertFalse(exited.isDone(), "JVM exit ran before the analysis process was terminated");

        process.allowDestroy.countDown();
        assertEquals(0, exited.get(5, TimeUnit.SECONDS));
        assertFalse(process.isAlive(), "JVM exit ran while the analysis child was alive");
        assertFalse(analysis.isLoaded(), "cancelled startup published a loaded engine");
      } finally {
        releaseStartup.countDown();
        process.allowDestroy.countDown();
        starter.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(starter.isAlive(), "startup did not finish after releasing the gate");
        analysis.shutdown();
        awaitAnalysisCleanup(analysis);
      }
    }
  }

  @Test
  void unexpectedEofBeforeStartupHandoffRemainsAnAbnormalEnd() throws Exception {
    try (Harness harness = Harness.open();
        GatedAnalysisProcess process = new GatedAnalysisProcess()) {
      AnalysisEngine analysis = analysisEngine();
      CountDownLatch beforeReaders = new CountDownLatch(1);
      CountDownLatch releaseReaders = new CountDownLatch(1);
      CountDownLatch failed = new CountDownLatch(1);
      AtomicInteger pidReads = new AtomicInteger();
      analysis.setFailureCallback(result -> failed.countDown());
      process.stdoutEof = true;
      process.beforePid = () -> {
        // attachProcess reads the PID first; process accounting reads it after stream installation.
        if (pidReads.incrementAndGet() == 2) {
          beforeReaders.countDown();
          awaitGate(releaseReaders);
        }
      };
      FutureTask<Void> startup = new FutureTask<>(() -> {
        startAnalysis(analysis, () -> process);
        return null;
      });
      Thread starter = new Thread(startup, "analysis-eof-test-starter");
      try {
        starter.start();
        assertTrue(beforeReaders.await(5, TimeUnit.SECONDS));
        // Hold only the final ownership handoff; both production readers must remain runnable.
        synchronized (analysisField("ACTIVE_READER_RESOURCES").get(null)) {
          releaseReaders.countDown();
          assertTrue(failed.await(5, TimeUnit.SECONDS), "the reader did not report the EOF");
          assertFalse(startup.isDone(), "startup must still await its ownership handoff");
        }
        startup.get(5, TimeUnit.SECONDS);
        assertFalse(analysis.isNormalEnd, "startup cleanup suppressed the unexpected-EOF failure");
      } finally {
        releaseReaders.countDown();
        process.allowDestroy.countDown();
        starter.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(starter.isAlive());
        analysis.shutdown();
        awaitAnalysisCleanup(analysis);
      }
    }
  }

  @Test
  void failedAnalysisStartReleasesExitWaitAndShutdownRejectsLaterStarts(@TempDir Path directory)
      throws Exception {
    try (Harness harness = Harness.open()) {
      AnalysisEngine failed = analysisEngine();
      failed.startEngine("\"" + directory.resolve("missing-analysis-engine") + "\"");
      assertNull(failed.process);
      assertFalse(failed.isLoaded());

      CompletableFuture<Integer> exited = new CompletableFuture<>();
      SwingUtilities.invokeAndWait(() -> Lizzie.shutdown(exited::complete));
      assertEquals(0, exited.get(5, TimeUnit.SECONDS), "failed startup left an exit reservation");

      AnalysisEngine late = analysisEngine();
      startAnalysis(late, () -> {
        throw new AssertionError("shutdown allowed a new process launch");
      });
      assertNull(late.process);
      assertFalse(late.isLoaded());
    }
  }

  private static AnalysisEngine analysisEngine() throws Exception {
    AnalysisEngine engine = allocate(AnalysisEngine.class);
    analysisField("analyzeMap").set(engine, new ConcurrentHashMap<>());
    analysisField("resourceBundle").set(engine, Lizzie.resourceBundle);
    analysisField("isPreLoad").setBoolean(engine, true);
    return engine;
  }

  private static void awaitGate(CountDownLatch release) {
    try {
      assertTrue(release.await(5, TimeUnit.SECONDS), "startup gate was not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static void awaitAnalysisCleanup(AnalysisEngine analysis) throws Exception {
    Object resources = analysisField("readerResources").get(analysis);
    Field cleanupField = resources.getClass().getDeclaredField("cleanup");
    cleanupField.setAccessible(true);
    Thread cleanup = (Thread) cleanupField.get(resources);
    if (cleanup != null) {
      cleanup.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(cleanup.isAlive(), "cleanup did not finish after releasing the gate");
    }
  }

  private static Field analysisField(String name) throws NoSuchFieldException {
    Field field = AnalysisEngine.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void startAnalysis(AnalysisEngine engine, Callable<Process> processStarter)
      throws Exception {
    startAnalysis(
        engine, "\"" + Path.of(System.getProperty("java.home"), "bin", "java") + "\"",
        (proxy, method, arguments) -> processStarter.call());
  }

  private static void startAnalysis(
      AnalysisEngine engine, String command, InvocationHandler factoryMethods) throws Exception {
    Class<?> factory = Class.forName(AnalysisEngine.class.getName() + "$StartupFactory");
    Object starter = Proxy.newProxyInstance(
        factory.getClassLoader(), new Class<?>[] {factory}, factoryMethods);
    Method start = AnalysisEngine.class.getDeclaredMethod("startEngine", String.class, factory);
    start.setAccessible(true);
    try {
      start.invoke(engine, command, starter);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception exception) throw exception;
      if (failure.getCause() instanceof Error error) throw error;
      throw failure;
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
    private Runnable beforeStdout;
    private Runnable beforePid;
    private boolean stdoutEof;

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
      if (beforeStdout != null) beforeStdout.run();
      return stdoutEof ? InputStream.nullInputStream() : child.getInputStream();
    }

    @Override
    public long pid() {
      if (beforePid != null) beforePid.run();
      return child.pid();
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
  void repeatedShutdownJoinsOneAsynchronousExitAutosaveBeforeCleanup() throws Exception {
    try (Harness harness = Harness.open()) {
      CompletableFuture<Boolean> save = new CompletableFuture<>();
      harness.frame.autoSaveAttempt = save;
      AtomicInteger exits = new AtomicInteger();
      FutureTask<Void> shutdown = new FutureTask<>(() -> {
        Lizzie.shutdown(status -> exits.incrementAndGet());
        return null;
      });
      CountDownLatch nextEdtEvent = new CountDownLatch(1);
      try {
        SwingUtilities.invokeLater(shutdown);
        SwingUtilities.invokeLater(nextEdtEvent::countDown);
        assertTrue(nextEdtEvent.await(5, TimeUnit.SECONDS),
            "waiting for the final automatic save must not block the EDT");
        shutdown.get(5, TimeUnit.SECONDS);
        FutureTask<Void> repeated = new FutureTask<>(() -> {
          Lizzie.shutdown(status -> exits.incrementAndGet());
          return null;
        });
        SwingUtilities.invokeLater(repeated);
        repeated.get(5, TimeUnit.SECONDS);

        assertEquals(1, harness.frame.autoSaveCalls);
        assertEquals(0, exits.get());
        assertFalse(harness.config.persistCalled);
        assertFalse(harness.config.saveCalled);
        assertEquals(0, harness.frame.strengthShutdownCalls);
        assertEquals(0, harness.engine.forceQuitCount);

        SwingUtilities.invokeAndWait(() -> {
          save.complete(true);
          // The already queued completion still owns this close request.
          Lizzie.shutdown(status -> exits.incrementAndGet());
          assertEquals(1, harness.frame.autoSaveCalls);
          assertEquals(0, exits.get());
        });
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(1, harness.frame.autoSaveCalls,
            "resuming shutdown must not enqueue another final automatic save");
        assertTrue(harness.config.persistCalled);
        assertTrue(harness.config.saveCalled);
        assertEquals(1, harness.frame.strengthShutdownCalls);
        assertEquals(1, harness.engine.forceQuitCount);
        assertEquals(1, exits.get());
      } finally {
        save.complete(false);
        SwingUtilities.invokeAndWait(() -> {});
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void asynchronousExitAutosaveFailureLeavesExitRetryable(boolean exceptional) throws Exception {
    try (Harness harness = Harness.open()) {
      CompletableFuture<Boolean> save = new CompletableFuture<>();
      harness.frame.autoSaveAttempt = save;
      AtomicInteger exits = new AtomicInteger();
      try {
        FutureTask<Void> shutdown = new FutureTask<>(() -> {
          Lizzie.shutdown(status -> exits.incrementAndGet());
          return null;
        });
        SwingUtilities.invokeLater(shutdown);
        shutdown.get(5, TimeUnit.SECONDS);
        assertEquals(1, harness.frame.autoSaveCalls);
        assertFalse(harness.config.persistCalled);
        assertEquals(0, exits.get());

        SwingUtilities.invokeAndWait(() -> {
          if (exceptional) save.completeExceptionally(new IOException("automatic write failed"));
          else save.complete(false);
        });
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(1, harness.frame.autoSaveCalls);
        assertFalse(harness.config.persistCalled);
        assertFalse(harness.config.saveCalled);
        assertEquals(0, harness.frame.strengthShutdownCalls);
        assertEquals(0, harness.engine.forceQuitCount);
        assertEquals(0, exits.get());

        harness.frame.autoSaveAttempt = null;
        SwingUtilities.invokeAndWait(() -> Lizzie.shutdown(status -> exits.incrementAndGet()));
        assertEquals(2, harness.frame.autoSaveCalls);
        assertTrue(harness.config.persistCalled);
        assertTrue(harness.config.saveCalled);
        assertEquals(1, harness.frame.strengthShutdownCalls);
        assertEquals(1, harness.engine.forceQuitCount);
        assertEquals(1, exits.get());
      } finally {
        save.complete(false);
        SwingUtilities.invokeAndWait(() -> {});
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"true, false", "true, true", "false, false", "false, true"})
  void failedSettingsSavePreservesAppAndSuccessfulRetryCanExit(
      boolean failPersist, boolean runtimeFailure) throws Exception {
    try (Harness harness = Harness.open()) {
      EngineGameTransaction product = harness.startProduct();
      harness.config.uiConfig.put("shutdown-test-setting", "edited but not saved");
      harness.config.failPersist = failPersist;
      harness.config.failSave = !failPersist;
      harness.config.failWithRuntimeException = runtimeFailure;
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertTrue(harness.config.persistCalled);
      assertEquals(!failPersist, harness.config.saveCalled);
      assertSame(harness.config, Lizzie.config);
      assertEquals("edited but not saved", Lizzie.config.uiConfig.getString("shutdown-test-setting"));
      assertSame(product, Lizzie.engineGame.transaction());
      assertTrue(Lizzie.engineGame.current().playing());
      assertFalse(product.alreadyCompleted());
      assertEquals(0, harness.frame.strengthShutdownCalls);
      assertEquals(0, harness.engine.forceQuitCount);
      assertEquals(-1, exitCode.get());

      harness.config.failPersist = false;
      harness.config.failSave = false;
      harness.config.persistCalled = false;
      harness.config.saveCalled = false;
      Lizzie.shutdown(exitCode::set);

      assertTrue(harness.config.persistCalled);
      assertTrue(harness.config.saveCalled);
      assertEquals("edited but not saved", Lizzie.config.uiConfig.getString("shutdown-test-setting"));
      assertEquals(1, harness.frame.strengthShutdownCalls);
      assertEquals(1, harness.engine.forceQuitCount);
      assertEquals(0, exitCode.get());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedSettingsSaveDoesNotFenceRunningGameCompletion(boolean failPersist) throws Exception {
    try (Harness harness = Harness.open()) {
      EngineGameTransaction product = harness.startProduct();
      harness.config.failPersist = failPersist;
      harness.config.failSave = !failPersist;
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertEquals(-1, exitCode.get());
      assertTrue(
          Lizzie.engineGame.complete(
              new GameOutcome.DoublePass(), product.lifecycle().ownerToken(), 0));
      assertTrue(product.alreadyCompleted());
      assertEquals(1, Lizzie.engineGame.lastSummary().doublePassGames());
    }
  }

  @Test
  void gameCompletingDuringSettingsSaveStillRequiresItsSaveBeforeShutdown() throws Exception {
    try (Harness harness = Harness.open()) {
      EngineGameTransaction product = harness.startProduct();
      harness.config.onSave =
          () ->
              assertTrue(
                  Lizzie.engineGame.complete(
                      new GameOutcome.DoublePass(), product.lifecycle().ownerToken(), 0));
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertTrue(harness.config.persistCalled);
      assertTrue(harness.config.saveCalled);
      assertTrue(product.alreadyCompleted());
      assertEquals(1, Lizzie.engineGame.lastSummary().doublePassGames());
      assertEquals(0, harness.frame.strengthShutdownCalls);
      assertEquals(0, harness.engine.forceQuitCount);
      assertEquals(-1, exitCode.get());
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
  void repeatedShutdownJoinsOneAsynchronousEngineGameSaveBeforeCleanup() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.config.autoSaveOnExit = false;
      harness.manager.engineGameSavePending = true;
      CompletableFuture<Boolean> save = new CompletableFuture<>();
      harness.manager.saveAttempt = save;
      AtomicInteger exits = new AtomicInteger();
      try {
        SwingUtilities.invokeAndWait(() -> Lizzie.shutdown(status -> exits.incrementAndGet()));
        SwingUtilities.invokeAndWait(() -> Lizzie.shutdown(status -> exits.incrementAndGet()));
        assertEquals(1, harness.manager.retrySaveCalls);
        assertEquals(0, exits.get());
        assertFalse(harness.config.persistCalled);
        assertEquals(0, harness.frame.strengthShutdownCalls);
        assertEquals(0, harness.engine.forceQuitCount);

        // A completed disk write is not sufficient until the owner has published its receipt.
        SwingUtilities.invokeAndWait(() -> {
          harness.manager.engineGameSavePending = false;
          save.complete(true);
          // The queued resumption still owns the close request even though the future is done.
          Lizzie.shutdown(status -> exits.incrementAndGet());
          assertEquals(0, exits.get());
        });
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(1, exits.get());
        assertEquals(1, harness.frame.strengthShutdownCalls);
        assertEquals(1, harness.engine.forceQuitCount);
      } finally {
        save.complete(false);
        SwingUtilities.invokeAndWait(() -> {});
      }
    }
  }

  @Test
  void asynchronousEngineGameSaveFailureLeavesExitRetryable() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.manager.engineGameSavePending = true;
      CompletableFuture<Boolean> save = new CompletableFuture<>();
      harness.manager.saveAttempt = save;
      AtomicInteger exits = new AtomicInteger();
      try {
        SwingUtilities.invokeAndWait(() -> Lizzie.shutdown(status -> exits.incrementAndGet()));
        save.complete(false);
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, exits.get());
        assertTrue(harness.manager.engineGameSavePending);
        assertFalse(harness.config.persistCalled);
        assertEquals(0, harness.frame.strengthShutdownCalls);
        assertEquals(0, harness.engine.forceQuitCount);

        harness.manager.saveAttempt = null;
        harness.manager.retrySaveResult = true;
        SwingUtilities.invokeAndWait(() -> Lizzie.shutdown(status -> exits.incrementAndGet()));
        assertEquals(1, exits.get());
        assertEquals(2, harness.manager.retrySaveCalls);
      } finally {
        save.complete(false);
        SwingUtilities.invokeAndWait(() -> {});
      }
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
    public void close() throws Exception {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.engineManager = previousEngineManager;
      Lizzie.leelaz = previousEngine;
      Lizzie.webBoardManager = previousWebBoardManager;
      Lizzie.board = previousBoard;
      // The injected exit callback leaves this JVM alive; later tests need a fresh shutdown fence.
      Lizzie.engineGame.resetForTest();
      analysisField("resourcesShuttingDown").setBoolean(null, false);
    }
  }

  private static final class RecordingConfig extends Config {
    private RecordingConfig() throws IOException {}

    boolean persistCalled;
    boolean saveCalled;
    boolean failPersist;
    boolean failSave;
    boolean failWithRuntimeException;
    Runnable onSave;

    @Override
    public String getPersistFilePath() {
      return "persist";
    }

    @Override
    public String getConfigFilePath() {
      return "config.txt";
    }

    @Override
    public void persist() throws IOException {
      persistCalled = true;
      failIfRequested(failPersist);
    }

    @Override
    public void save() throws IOException {
      saveCalled = true;
      failIfRequested(failSave);
      if (onSave != null) onSave.run();
    }

    private void failIfRequested(boolean fail) throws IOException {
      if (!fail) return;
      if (failWithRuntimeException) throw new IllegalStateException("settings capture failed");
      throw new IOException("settings write failed");
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

    private CompletableFuture<Boolean> saveAttempt;

    @Override
    public CompletableFuture<Boolean> retryEngineGameSave() {
      retrySaveCalls++;
      if (saveAttempt != null) return saveAttempt;
      if (retrySaveFailure != null) {
        throw retrySaveFailure;
      }
      if (retrySaveResult) {
        engineGameSavePending = retirementPending;
      }
      return CompletableFuture.completedFuture(retrySaveResult);
    }
  }

  private static final class RecordingFrame extends LizzieFrame {
    int autoSaveCalls;
    int strengthShutdownCalls;
    boolean autoSaveResult;
    RuntimeException autoSaveFailure;
    CompletableFuture<Boolean> autoSaveAttempt;
    CompletableFuture<Void> strengthShutdown;

    @Override
    public CompletableFuture<Boolean> saveAutoGameAsync(int index) {
      autoSaveCalls++;
      assertEquals(1, index, "shutdown must save the exit recovery slot");
      if (autoSaveFailure != null) {
        throw autoSaveFailure;
      }
      return autoSaveAttempt == null
          ? CompletableFuture.completedFuture(autoSaveResult) : autoSaveAttempt;
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
