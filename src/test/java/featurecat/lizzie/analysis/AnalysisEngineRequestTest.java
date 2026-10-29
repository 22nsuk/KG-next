package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.enginegame.EngineGamePlans;
import featurecat.lizzie.gui.EngineFailedMessage.DiagnosticActionResult;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.HumanSlGameController;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.WaitForAnalysis;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.GameEditSnapshot;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.OsPlatform;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisEngineRequestTest {
  private static final int BOARD_SIZE = 3;
  private final List<Leelaz> createdForegroundEngines = new ArrayList<>();

  @AfterEach
  void closeCreatedForegroundSessions() throws Exception {
    for (Leelaz engine : createdForegroundEngines) {
      closeExclusiveSessionForTest(engine);
    }
    createdForegroundEngines.clear();
  }

  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void sharedRestoreRemainsPartOfRequestLifecycleAfterLastResponse() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "sharedForegroundRestoreInProgress", true);
      assertFalse(engine.isAnalysisInProgress());
      assertTrue(engine.hasRequestLifecycleInProgress());
      setField(AnalysisEngine.class, engine, "sharedForegroundRestoreInProgress", false);
      assertFalse(engine.hasRequestLifecycleInProgress());
    }
  }

  @Test
  void localProcessKeepsDiagnosticFragmentsOutOfJsonResponses() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      try (LocalAnalysisSession session = LocalAnalysisSession.open()) {
        assertLocalResponse(session.engine, history.getStart());
        assertEquals(200, history.getStart().getData().getPlayouts());
        session.engine.normalQuit();
        session.assertStopped();
      }
    }
  }

  @Test
  void repeatedLocalAnalysisReleasesProcessAndBothReaders() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      for (int run = 0; run < 3; run++) {
        BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
        boardWithHistory(history);
        try (LocalAnalysisSession session = LocalAnalysisSession.open()) {
          assertLocalResponse(session.engine, history.getStart());
          session.engine.normalQuit();
          session.engine.normalQuit();
          session.assertStopped();
          assertFalse(session.engine.sendRequest(history.getStart()));
        }
      }
    }
  }

  @Test
  void localCancellationAndShutdownReleaseProcessAndBothReaders() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      for (boolean directShutdown : List.of(false, true)) {
        BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
        boardWithHistory(history);
        try (LocalAnalysisSession session = LocalAnalysisSession.open()) {
          assertLocalResponse(session.engine, history.getStart());
          if (directShutdown) {
            session.engine.shutdown();
          } else {
            session.engine.requestShutdown();
            assertFalse(session.engine.sendRequest(history.getStart()));
            session.engine.normalQuit();
          }
          session.assertStopped();
        }
      }
    }
  }

  @Test
  void unexpectedLocalOutputEndReleasesProcessAndBothReaders() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      for (String mode : List.of("stdout-eof", "crash")) {
        BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
        boardWithHistory(history);
        try (LocalAnalysisSession session = LocalAnalysisSession.open(true, mode)) {
          CountDownLatch aborted = new CountDownLatch(1);
          session.engine.setFailureCallback(result -> aborted.countDown());
          assertTrue(session.engine.sendRequest(history.getStart()));

          // No explicit quit: the stdout reader must own cleanup even if the child is still alive.
          session.assertStopped();
          assertTrue(aborted.await(5, TimeUnit.SECONDS), "aborted analysis was not reported");
          assertEquals(0, history.getStart().getData().getPlayouts());
        }
      }
    }
  }

  @Test
  void immediateLocalProcessExitDoesNotLeaveUnstartedReaders() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      for (int run = 0; run < 3; run++) {
        try (LocalAnalysisSession session = LocalAnalysisSession.open(true, "immediate-exit")) {
          session.assertStopped();
        }
      }
    }
  }

  @Test
  void localStartupEofRetainsLateStderrAndNaturalExitCode() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      try (LocalAnalysisSession session = LocalAnalysisSession.open(true, "late-stderr-exit")) {
        EngineStartupDiagnostics.Attempt attempt = session.engine.startupDiagnosticAttempt();
        CountDownLatch ownerClosed = new CountDownLatch(1);
        session.engine.setFailureCallback(
            result -> {
              // WholeGameAnalysisSession closes a failed worker again from its callback.
              session.engine.normalQuit();
              ownerClosed.countDown();
            });
        assertTrue(session.engine.sendRequest(history.getStart()));
        waitUntil(() -> attempt.snapshot() != null);
        assertTrue(ownerClosed.await(5, TimeUnit.SECONDS), "owner cleanup was not called");
        assertFalse(
            session.process.waitFor(100, TimeUnit.MILLISECONDS),
            "repeated cleanup cut off the pending startup diagnostic tail");
        assertTrue(attempt.collectingOutput(), "startup diagnostic should still be collecting");

        // Let the child finish only after production has observed its earlier stdout EOF.
        session.process
            .getOutputStream()
            .write("release late stderr\n".getBytes(StandardCharsets.UTF_8));
        session.process.getOutputStream().flush();

        session.assertStopped();
        waitUntil(() -> !attempt.collectingOutput());
        JSONObject diagnostic = attempt.snapshot().toJson();
        assertEquals("completed", diagnostic.getString("collectionState"));
        assertEquals(17, diagnostic.getInt("exitCode"));
        assertEquals("late analysis startup error", diagnostic.getString("stderr"));
      }
    }
  }

  @Test
  void stderrEndDoesNotStopLocalAnalysisResponses() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      try (LocalAnalysisSession session = LocalAnalysisSession.open(true, "stderr-eof")) {
        assertLocalResponse(session.engine, history.getStart());
        assertEquals(200, history.getStart().getData().getPlayouts());
        assertTrue(session.process.isAlive());
        assertTrue(session.engine.isRunning());

        session.engine.normalQuit();
        session.assertStopped();
      }
    }
  }

  @Test
  void readerCompletingLocalAnalysisCanAutoQuitItsOwnExecutor() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      Lizzie.config.analysisAutoQuit = true;
      try (LocalAnalysisSession session = LocalAnalysisSession.open(false, "gated-response")) {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        session.engine.setCompletionCallback(
            result -> {
              completions.incrementAndGet();
              completed.countDown();
            });
        session.engine.setFailureCallback(
            result -> {
              failures.incrementAndGet();
              completed.countDown();
            });

        session.engine.startRequest(1, 2, false);
        assertTrue(session.engine.isAnalysisInProgress(), "the real request must await its reader");
        // Release the result after dispatch returns, so the stdout reader performs auto-quit.
        session.process.getOutputStream().write('\n');
        session.process.getOutputStream().flush();

        assertTrue(completed.await(10, TimeUnit.SECONDS), "automatic quit did not complete");
        assertEquals(1, completions.get());
        assertEquals(0, failures.get());
        assertEquals(200, node.getData().getPlayouts());
        assertTrue(session.engine.isNormalEnd);
        session.assertStopped();
      }
    }
  }

  @Test
  void localQuitDoesNotWaitForBusyReaderOnEdt() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      try (LocalAnalysisSession session = LocalAnalysisSession.open()) {
        SilentGtpConsole console = (SilentGtpConsole) Lizzie.gtpConsole;
        console.errorEntered = new CountDownLatch(1);
        console.releaseError = new CountDownLatch(1);
        FutureTask<Void> quit =
            new FutureTask<>(
                () -> {
                  session.engine.normalQuit();
                  return null;
                });
        CountDownLatch nextEdtEvent = new CountDownLatch(1);
        try {
          assertLocalResponse(session.engine, history.getStart());
          assertTrue(console.errorEntered.await(5, TimeUnit.SECONDS), "stderr reader did not enter");

          javax.swing.SwingUtilities.invokeLater(quit);
          javax.swing.SwingUtilities.invokeLater(nextEdtEvent::countDown);

          quit.get(2, TimeUnit.SECONDS);
          assertTrue(nextEdtEvent.await(2, TimeUnit.SECONDS), "quit blocked the next EDT event");
          assertEquals(1, console.releaseError.getCount(), "reader must still be deliberately busy");
        } finally {
          console.releaseError.countDown();
        }
        session.assertStopped();
      }
    }
  }

  @Test
  void concurrentLocalQuitAndShutdownReleaseBothReaders() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      try (LocalAnalysisSession session = LocalAnalysisSession.open()) {
        assertLocalResponse(session.engine, history.getStart());
        ExecutorService callers = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
          List<Future<?>> quits = new ArrayList<>();
          for (int index = 0; index < 4; index++) {
            boolean normalQuit = index % 2 == 0;
            quits.add(
                callers.submit(
                    () -> {
                      ready.countDown();
                      assertTrue(start.await(5, TimeUnit.SECONDS));
                      for (int repeat = 0; repeat < 3; repeat++) {
                        if (normalQuit) session.engine.normalQuit();
                        else session.engine.shutdown();
                      }
                      return null;
                    }));
          }
          assertTrue(ready.await(5, TimeUnit.SECONDS));
          start.countDown();
          for (Future<?> quit : quits) quit.get(5, TimeUnit.SECONDS);
          session.assertStopped();
        } finally {
          start.countDown();
          callers.shutdownNow();
          assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS), "quit callers did not finish");
        }
      }
    }
  }

  private static void assertLocalResponse(AnalysisEngine engine, BoardHistoryNode node)
      throws Exception {
    CountDownLatch response = new CountDownLatch(1);
    engine.setProgressListener((completed, total) -> response.countDown());
    assertTrue(engine.sendRequest(node));
    assertTrue(response.await(10, TimeUnit.SECONDS), "local analysis response was not received");
    assertEquals(1, getField(AnalysisEngine.class, engine, "responseCount"));
  }

  private static final class LocalAnalysisSession implements AutoCloseable {
    private final AnalysisEngine engine;
    private final Object resources;
    private final Process process;
    private final List<ExecutorService> readers;
    private final GtpConsolePane previousConsole;

    private LocalAnalysisSession(AnalysisEngine engine, GtpConsolePane previousConsole)
        throws Exception {
      this.engine = engine;
      this.resources = getField(AnalysisEngine.class, engine, "readerResources");
      this.process = (Process) getField(resources.getClass(), resources, "process");
      this.previousConsole = previousConsole;
      this.readers =
          List.of(
              (ExecutorService) getField(resources.getClass(), resources, "executor"),
              (ExecutorService) getField(resources.getClass(), resources, "executorErr"));
      assertNotNull(process, "local analysis process did not start");
    }

    private static LocalAnalysisSession open() throws Exception {
      return open(true, "respond");
    }

    private static LocalAnalysisSession open(boolean preload, String mode) throws Exception {
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.analysisEngineCommand =
          "\""
              + java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
              + "\" -cp \""
              + System.getProperty("java.class.path")
              + "\" "
              + InterleavedAnalysisProcess.class.getName()
              + " "
              + mode;
      GtpConsolePane previousConsole = Lizzie.gtpConsole;
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      return new LocalAnalysisSession(new AnalysisEngine(preload), previousConsole);
    }

    private void assertStopped() throws Exception {
      assertTrue(process.waitFor(5, TimeUnit.SECONDS), "owned process was left alive");
      for (ExecutorService reader : readers) {
        assertTrue(
            reader.awaitTermination(5, TimeUnit.SECONDS), "owned reader executor did not terminate");
        assertTrue(reader.isTerminated());
        assertThrows(RejectedExecutionException.class, () -> reader.submit(() -> {}));
      }
      Thread cleanup = (Thread) getField(resources.getClass(), resources, "cleanup");
      assertNotNull(cleanup, "resource cleanup was not started");
      cleanup.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(cleanup.isAlive(), "resource cleanup was left running");
      assertFalse(engine.isRunning());
    }

    @Override
    public void close() throws Exception {
      try {
        // Assertions above observe production cleanup. Only a failed/unfinished test needs this.
        if (process.isAlive()) {
          process.destroyForcibly();
          assertTrue(process.waitFor(5, TimeUnit.SECONDS), "test process cleanup timed out");
        }
        for (ExecutorService reader : readers) {
          if (!reader.isTerminated()) {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS), "test reader cleanup timed out");
          }
        }
      } finally {
        try {
          Thread cleanup = (Thread) getField(resources.getClass(), resources, "cleanup");
          if (cleanup != null && cleanup.isAlive()) {
            cleanup.interrupt();
            cleanup.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(cleanup.isAlive(), "test resource cleanup timed out");
          }
        } finally {
          Lizzie.gtpConsole = previousConsole;
        }
      }
    }
  }

  public static final class InterleavedAnalysisProcess {
    public static void main(String[] args) throws Exception {
      var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
      String mode = args.length == 0 ? "respond" : args[0];
      if ("immediate-exit".equals(mode)) return;
      String request;
      while ((request = input.readLine()) != null) {
        if ("late-stderr-exit".equals(mode)) {
          System.out.close();
          if (input.readLine() != null) {
            System.err.println("late analysis startup error");
            System.err.flush();
            System.exit(17);
          }
          return;
        }
        if ("stdout-eof".equals(mode)) {
          System.out.close();
          // Leave stderr and stdin open: stdout EOF alone must terminate the owned child.
          while (input.readLine() != null) {}
          return;
        }
        if ("crash".equals(mode)) Runtime.getRuntime().halt(17);
        if ("stderr-eof".equals(mode)) System.err.close();
        if ("gated-response".equals(mode) && input.readLine() == null) return;
        int id = new JSONObject(request).getInt("id");
        System.err.print("diagnostic fragment without newline: ");
        System.err.flush();
        System.out.println(analysisResult(id, 200, 62.0));
        System.out.flush();
        System.err.println("finished");
      }
    }
  }

  @Test
  void automaticSilentAnalysisPausesForegroundAndLeavesResumeToItsCallback() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      PonderTrackingLeelaz foreground = allocate(PonderTrackingLeelaz.class);
      foreground.pondering = true;
      Lizzie.leelaz = foreground;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(
          AnalysisEngine.class,
          engine,
          "purpose",
          AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS);

      invokeAnalysisEnginePrepareRequestState(engine, false);

      assertFalse(foreground.pondering);
      assertEquals(1, foreground.notPonderingCalls);
      assertEquals(1, foreground.nameCommandCalls);
      assertFalse((boolean) getField(AnalysisEngine.class, engine, "shouldRePonder"));
      invokeAnalysisEngineResumeForeground(engine);
      assertEquals(0, foreground.ponderCalls);
    }
  }

  @Test
  void visibleUserAnalysisPausesAndRestoresForegroundExactlyOnce() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      PonderTrackingLeelaz foreground = allocate(PonderTrackingLeelaz.class);
      foreground.pondering = true;
      Lizzie.leelaz = foreground;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(
          AnalysisEngine.class,
          engine,
          "purpose",
          AnalysisResourceCoordinator.Purpose.USER_QUICK_ANALYSIS);

      invokeAnalysisEnginePrepareRequestState(engine, true);
      invokeAnalysisEngineResumeForeground(engine);
      invokeAnalysisEngineResumeForeground(engine);

      assertEquals(1, foreground.notPonderingCalls);
      assertEquals(1, foreground.nameCommandCalls);
      assertEquals(1, foreground.ponderCalls);
      assertTrue(foreground.pondering);
    }
  }

  @Test
  void reuseModeBindsCurrentForegroundKatagoWithoutStartingAProcess() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;

      AnalysisEngine engine = new AnalysisEngine(false);

      assertTrue(engine.isLoaded());
      assertNull(engine.process);
      assertTrue(engine.matchesCurrentAnalysisBackend());
    }
  }

  @Test
  void explicitReevaluationUsesTheSelectedCurrentEngineWithoutChangingSavedSettings()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = false;
      Lizzie.config.analysisEngineCommand = "a-separate-engine analysis -model separate.bin.gz";
      Lizzie.config.analysisUseCurrentRules = false;
      Lizzie.config.analysisSpecificRules = "{\"scoringRule\":\"TERRITORY\"}";

      AnalysisEngine engine = AnalysisEngine.createCurrentEngineWholeGame(500, foreground);

      assertTrue(engine.isLoaded());
      assertTrue(engine.usesSharedForegroundEngine());
      assertSame(foreground, getField(AnalysisEngine.class, engine, "sharedForegroundEngine"));
      assertNull(engine.process);
      assertFalse(Lizzie.config.analysisReuseCurrentEngine);
      assertEquals(
          "a-separate-engine analysis -model separate.bin.gz", Lizzie.config.analysisEngineCommand);
      assertEquals("{\"scoringRule\":\"TERRITORY\"}", Lizzie.config.analysisSpecificRules);
      engine.normalQuit();
    }
  }

  @Test
  void explicitReevaluationDoesNotFallbackWhenTheSelectedEngineIsMissingOrReplaced()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      Leelaz selected = reusableForegroundEngine(true);
      Leelaz replacement = reusableForegroundEngine(true);
      Lizzie.leelaz = replacement;
      Lizzie.config.analysisEngineCommand = "never-launch-a-dedicated-fallback";
      for (Leelaz expected : new Leelaz[] {null, selected}) {
        AnalysisEngine engine = AnalysisEngine.createCurrentEngineWholeGame(500, expected);
        assertFalse(engine.isLoaded());
        assertNull(engine.process);
        assertEquals(
            -1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false, new Object()));
        assertFalse(replacement.hasExclusiveGtpLease());
        engine.normalQuit();
      }
    }
  }

  @Test
  void automaticQuickAnalysisRecoversMissingLocalExecutableWithoutChangingSettings(
      @TempDir Path folder) throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = reusableForegroundEngine(true);
      Lizzie.config.leelazConfig = new JSONObject();
      String command = "\"" + folder.resolve("missing-katago") + "\" analysis -model custom.bin.gz";
      Lizzie.config.analysisEngineCommand = command;
      Lizzie.config.uiConfig =
          new JSONObject().put("analysis-engine-command-customized", true);

      AnalysisEngine engine = AnalysisEngine.createAutomaticQuickAnalysis();

      assertTrue(engine.isLoaded());
      assertTrue(engine.usesSharedForegroundEngine());
      assertTrue(engine.usesAutomaticPrimaryForegroundReuse());
      assertTrue(engine.usesMissingAnalysisEngineFallback());
      assertSame(Lizzie.leelaz, getField(AnalysisEngine.class, engine, "sharedForegroundEngine"));
      assertNull(engine.process, "the missing executable must never be launched");
      assertNull(engine.startupDiagnosticAttempt());
      assertEquals(command, Lizzie.config.analysisEngineCommand);
      assertTrue(Lizzie.config.uiConfig.getBoolean("analysis-engine-command-customized"));
      assertFalse(Lizzie.config.analysisReuseCurrentEngine);
      engine.shutdown();
    }
  }

  @Test
  void existingExeSuffixPreservesSeparateAutomaticAnalysisOnWindows(@TempDir Path folder)
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = reusableForegroundEngine(true);
      Lizzie.config.leelazConfig = new JSONObject();
      Path executable = folder.resolve("custom analysis engine.v1");
      Files.createFile(executable.resolveSibling(executable.getFileName() + ".exe"));
      String command = "\"" + executable + "\" analysis -model custom.bin.gz -config custom.cfg";
      Lizzie.config.analysisEngineCommand = command;

      assertEquals(
          !OsPlatform.isWindows(), AnalysisEngine.automaticallyReusesPrimaryForeground(false));
      assertEquals(command, Lizzie.config.analysisEngineCommand);
      assertFalse(Lizzie.config.analysisReuseCurrentEngine);
    }
  }

  @Test
  void missingExecutableFallbackDoesNotOverrideSshLightweightOrPathCommands(@TempDir Path folder)
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = reusableForegroundEngine(true);
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.analysisEngineCommand = "\"" + folder.resolve("missing-katago") + "\" analysis";
      assertTrue(AnalysisEngine.automaticallyReusesPrimaryForeground(false));
      assertFalse(AnalysisEngine.automaticallyReusesPrimaryForeground(true));
      Lizzie.config.leelazConfig.put(
          "analysis-engine-ssh-info", new JSONObject().put("useJavaSSH", true));
      assertFalse(AnalysisEngine.automaticallyReusesPrimaryForeground(false));
      Lizzie.config.leelazConfig.remove("analysis-engine-ssh-info");
      Lizzie.config.analysisEngineCommand = "katago analysis -model custom.bin.gz";
      assertFalse(AnalysisEngine.automaticallyReusesPrimaryForeground(false));
      Lizzie.config.analysisEngineCommand = RemoteComputeConfig.COMMAND_ZHIZI;
      assertFalse(AnalysisEngine.automaticallyReusesPrimaryForeground(false));
      Lizzie.config.analysisEngineCommand =
          "\"" + Files.createFile(folder.resolve("katago")) + "\" analysis";
      assertFalse(AnalysisEngine.automaticallyReusesPrimaryForeground(false));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void separateLightweightCommandKeepsItsOwnStartupFailure(
      boolean executableExists, @TempDir Path folder) throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = reusableForegroundEngine(true);
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.analysisEngineCommand = "\"" + folder.resolve("missing-default") + "\" analysis";
      Path lightweight = folder.resolve("lightweight-katago");
      if (executableExists) Files.createFile(lightweight);
      var constructor =
          AnalysisEngine.class.getDeclaredConstructor(
              boolean.class,
              AnalysisEngine.Workload.class,
              int.class,
              AnalysisResourceCoordinator.Purpose.class,
              boolean.class,
              String.class);
      constructor.setAccessible(true);
      AnalysisEngine engine =
          constructor.newInstance(
              true,
              AnalysisEngine.Workload.STANDARD,
              -1,
              AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS,
              false,
              "\"" + lightweight + "\" analysis -model lightweight.bin.gz");
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertFalse(engine.isLoaded());
      assertFalse(engine.usesSharedForegroundEngine());
      assertFalse(engine.usesMissingAnalysisEngineFallback());
      assertTrue(engine.usesDedicatedLightweightQuickModel());
      assertEquals(
          lightweight.toString(),
          ((List<?>) getField(AnalysisEngine.class, engine, "commands")).get(0));
      assertEquals(
          "process-create", engine.startupDiagnosticAttempt().snapshot().toJson().getString("phase"));
      engine.shutdown();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void manualAndPersistentPreloadKeepTheConfiguredExecutableFailure(
      boolean preload, @TempDir Path folder) throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = reusableForegroundEngine(true);
      Lizzie.config.leelazConfig = new JSONObject();
      String command = "\"" + folder.resolve("missing-katago") + "\" analysis -model custom.bin.gz";
      Lizzie.config.analysisEngineCommand = command;
      class DiagnosticEngine extends AnalysisEngine {
        String message;

        DiagnosticEngine() throws IOException {
          super(preload);
        }

        @Override
        public DiagnosticActionResult tryToDignostic(
            String message,
            KataGoRuntimeHelper.TensorRtRepairContext context,
            EngineStartupDiagnostics.Attempt attempt) {
          this.message = message;
          return DiagnosticActionResult.ofRepairChoice(true);
        }
      }
      DiagnosticEngine engine = new DiagnosticEngine();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertFalse(engine.isLoaded());
      assertFalse(engine.usesSharedForegroundEngine());
      assertFalse(engine.usesMissingAnalysisEngineFallback());
      assertEquals(
          "process-create", engine.startupDiagnosticAttempt().snapshot().toJson().getString("phase"));
      if (preload) assertNull(engine.message);
      else
        assertTrue(
            engine.message.contains("missing-katago"), "manual failure must reach its diagnostic");
      assertEquals(command, Lizzie.config.analysisEngineCommand);
      engine.shutdown();
    }
  }

  @Test
  void automaticQuickAnalysisReusesRemoteAndBundledNvidiaForegroundWhenAppropriate() {
    assertTrue(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, true, false, false, false));
    assertFalse(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, true, false, false, true));
    assertTrue(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, false, true, true, true));
    assertTrue(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, false, true, false, false));
    assertFalse(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, false, true, false, true));
    assertFalse(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.USER_QUICK_ANALYSIS, true, true, true, false));
    assertFalse(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.WHOLE_GAME_ANALYSIS, true, true, true, false));
    assertFalse(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, false, false, false, false));
  }

  @Test
  void automaticQuickAnalysisBindsTheActiveRemoteEngineWithoutChangingUserSettings()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(true);
      foreground.setEngineCommand(RemoteComputeConfig.COMMAND_ZHIZI);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = false;
      Lizzie.config.quickAnalysisLightweightModelEnabled = false;

      AnalysisEngine engine = AnalysisEngine.createAutomaticQuickAnalysis();

      assertTrue(engine.isLoaded());
      assertTrue(engine.usesSharedForegroundEngine());
      assertTrue(engine.usesAutomaticPrimaryForegroundReuse());
      assertTrue(engine.matchesCurrentAnalysisBackend());
      assertNull(engine.process);
      assertFalse(Lizzie.config.analysisReuseCurrentEngine);
    }
  }

  @Test
  void automaticPrimaryForegroundReuseInvalidatesWhenThePrimaryEngineChanges() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz firstForeground = reusableForegroundEngine(true);
      Leelaz secondForeground = reusableForegroundEngine(true);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      Lizzie.config.analysisReuseCurrentEngine = false;
      Lizzie.leelaz = firstForeground;
      setField(
          AnalysisEngine.class,
          engine,
          "purpose",
          AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS);
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", firstForeground);
      setField(AnalysisEngine.class, engine, "automaticPrimaryForegroundReuse", true);
      setField(
          AnalysisEngine.class,
          engine,
          "automaticPrimaryForegroundCommand",
          firstForeground.engineCommand());

      assertTrue(engine.matchesCurrentAnalysisBackend());
      assertFalse(Lizzie.config.analysisReuseCurrentEngine);

      Lizzie.leelaz = secondForeground;

      assertFalse(engine.matchesCurrentAnalysisBackend());
    }
  }

  @Test
  void automaticPrimaryForegroundReuseInvalidatesWhenTheSameEngineObjectChangesCommand()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(true);
      foreground.setEngineCommand("bundled-tensorrt-command");
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      Lizzie.config.analysisReuseCurrentEngine = false;
      Lizzie.leelaz = foreground;
      setField(
          AnalysisEngine.class,
          engine,
          "purpose",
          AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS);
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "automaticPrimaryForegroundReuse", true);
      setField(
          AnalysisEngine.class,
          engine,
          "automaticPrimaryForegroundCommand",
          foreground.engineCommand());

      assertTrue(engine.matchesCurrentAnalysisBackend());

      foreground.setEngineCommand("different-engine-command");

      assertFalse(engine.matchesCurrentAnalysisBackend());
    }
  }

  @Test
  void reuseModeRejectsNonKatagoWithoutFallingBackToDedicatedProcess() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(false);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;

      AnalysisEngine engine = new AnalysisEngine(false);

      assertFalse(engine.isLoaded());
      assertNull(engine.process);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.NOT_KATAGO, engine.getForegroundLeaseAvailability());
    }
  }

  @Test
  void reuseModeBindsTheForegroundEngineAgainForEachAnalysisTask() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Leelaz firstForeground = reusableForegroundEngine(true);
      Leelaz secondForeground = reusableForegroundEngine(true);
      Lizzie.leelaz = firstForeground;
      AnalysisEngine firstTask = new AnalysisEngine(false);

      Lizzie.leelaz = secondForeground;
      AnalysisEngine secondTask = new AnalysisEngine(false);

      assertFalse(firstTask.matchesCurrentAnalysisBackend());
      assertTrue(secondTask.matchesCurrentAnalysisBackend());
      assertNull(secondTask.process);
    }
  }

  @Test
  void reuseModeReportsMissingEngineReadinessCapabilitiesAndPlayMode() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.leelaz = null;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.NO_FOREGROUND_ENGINE,
          new AnalysisEngine(false).getForegroundLeaseAvailability());

      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      setField(Leelaz.class, foreground, "endGetCommandList", false);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY,
          new AnalysisEngine(false).getForegroundLeaseAvailability());

      setField(Leelaz.class, foreground, "endGetCommandList", true);
      foreground.commandLists.remove("kata-analyze");
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY,
          new AnalysisEngine(false).getForegroundLeaseAvailability());

      foreground.commandLists.add("kata-analyze");
      Lizzie.frame.isPlayingAgainstLeelaz = true;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.PLAY_MODE,
          new AnalysisEngine(false).getForegroundLeaseAvailability());
    }
  }

  @Test
  void reuseModeAllowsPonderingButRejectsGenmoveWithoutDedicatedFallback() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      foreground.Pondering();

      AnalysisEngine ponderingTask = new AnalysisEngine(false);
      assertTrue(ponderingTask.isLoaded());

      foreground.isThinking = true;
      AnalysisEngine genmoveTask = new AnalysisEngine(false);
      assertFalse(genmoveTask.isLoaded());
      assertNull(genmoveTask.process);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.GENMOVE,
          genmoveTask.getForegroundLeaseAvailability());
    }
  }

  @Test
  void reuseModeRejectsEveryReadBoardGmaBusyPhase() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      ReadBoard readBoard = allocate(ReadBoard.class);
      Lizzie.frame.readBoard = readBoard;

      for (String fieldName :
          List.of(
              "readBoardGmaPending",
              "readBoardGmaEngineRestorePending",
              "readBoardGmaEngineRestoreInProgress")) {
        setField(ReadBoard.class, readBoard, fieldName, true);

        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
            new AnalysisEngine(false).getForegroundLeaseAvailability(),
            fieldName);

        setField(ReadBoard.class, readBoard, fieldName, false);
      }
    }
  }

  @Test
  void reuseModeRejectsEngineGamePreparationAndExistingLease() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      EngineManager previousManager = Lizzie.engineManager;
      Leelaz dummy = new Leelaz("");
      EngineManager manager = new EngineManager(List.of(foreground, dummy));
      try {
        Lizzie.engineManager = manager;
        EngineManager.resetEngineGameTransactionStateForTest();
        EngineManager.beginEngineGameTransaction(
            manager, EngineGamePlans.harness(0, 1, false), null, true);
        AnalysisEngine gameTask = new AnalysisEngine(false);
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_GAME,
            gameTask.getForegroundLeaseAvailability());

        EngineManager.resetEngineGameTransactionStateForTest();
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
            foreground.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
        AnalysisEngine leasedTask = new AnalysisEngine(false);
        assertFalse(leasedTask.isLoaded());
        assertNull(leasedTask.process);
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.EXISTING_LEASE,
            leasedTask.getForegroundLeaseAvailability());
      } finally {
        foreground.endExclusiveGtpSession();
        EngineManager.resetEngineGameTransactionStateForTest();
        Lizzie.engineManager = previousManager;
      }
    }
  }

  @Test
  void terminatedSharedForegroundAnalysisEngineIsNotRunning() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.leelaz = reusableForegroundEngine(true);
      AnalysisEngine engine = new AnalysisEngine(false);

      engine.normalQuit();

      assertFalse(engine.isRunning());
    }
  }

  @Test
  void reuseModeRejectsHumanSlAndContributionModes() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.frame.humanSlGame = allocate(HumanSlGameController.class);

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.HUMAN_SL_GAME,
          new AnalysisEngine(false).getForegroundLeaseAvailability());

      Lizzie.frame.humanSlGame = null;
      Lizzie.frame.isContributing = true;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.APPLICATION_EXCLUSIVE_MODE,
          new AnalysisEngine(false).getForegroundLeaseAvailability());
    }
  }

  @Test
  void activeFlashLeaseRejectsContributionEntryWithoutSideEffects() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          foreground.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
      TrackingLizzieFrame frame = (TrackingLizzieFrame) Lizzie.frame;

      frame.startContributeEngine();

      assertEquals(1, frame.foregroundReservationConflictCount);
      assertFalse(frame.isContributing);
      assertNull(frame.contributeEngine);
      assertTrue(foreground.hasExclusiveGtpLease());
      foreground.endExclusiveGtpSession();
    }
  }

  @Test
  void benchmarkSuppressionRejectsLocalContributionBeforeReservedLifecycle() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      TrackingLizzieFrame frame = (TrackingLizzieFrame) Lizzie.frame;
      Lizzie.config.contributeUseCommand = false;
      Lizzie.leelaz = null;
      boolean pauseAccepted = false;
      try {
        KataGoRuntimeHelper.BenchmarkPauseResult pause =
            KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
        pauseAccepted = pause.accepted();

        frame.startContributeEngine();

        assertTrue(pause.accepted());
        assertEquals(1, frame.contributeBenchmarkConflictCount);
        assertEquals(0, frame.contributionStarts);
        assertFalse(frame.isContributing);
        assertNull(frame.contributeEngine);
      } finally {
        if (pauseAccepted) {
          KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
        }
      }
    }
  }

  @Test
  void rejectedInteractiveGenmoveDoesNotLeaveInputCommandStateBehind() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          foreground.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));

      Lizzie.frame.genmove();

      assertFalse(foreground.isInputCommand);
      foreground.endExclusiveGtpSession();
    }
  }

  @Test
  void sharedBatchCompletionWaitsForForegroundRestoreBeforeFinalProgressAndCallback()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      TrackingWaitForAnalysis waitFrame = allocate(TrackingWaitForAnalysis.class);
      AtomicInteger completions = new AtomicInteger();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      setField(AnalysisEngine.class, engine, "waitFrame", waitFrame);
      setIntField(AnalysisEngine.class, engine, "resultCount", 1);
      setIntField(AnalysisEngine.class, engine, "responseCount", 1);
      setField(
          AnalysisEngine.class,
          engine,
          "analyzeMap",
          new java.util.HashMap<Integer, BoardHistoryNode>(
              java.util.Map.of(1, Lizzie.board.getHistory().getCurrentHistoryNode())));
      List<ForegroundRestoreResult> restores = new CopyOnWriteArrayList<>();
      engine.setCompletionCallback(
          restore -> {
            restores.add(restore);
            completions.incrementAndGet();
          });

      invokeAnalysisEngineSetResult(engine);

      assertEquals(0, waitFrame.progressCalls);
      assertEquals(0, completions.get());
      assertTrue(engine.hasRequestLifecycleInProgress());

      foreground.completeRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, waitFrame.progressCalls);
      assertEquals(1, waitFrame.currentMove);
      assertEquals(1, waitFrame.totalMoves);
      assertEquals(1, completions.get());
      assertEquals(List.of(ForegroundRestoreResult.SUCCEEDED), restores);
      assertFalse(engine.hasRequestLifecycleInProgress());
    }
  }

  @Test
  void onlyAutomaticQuickAnalysisSuppressesForegroundRestoreFailurePopup() {
    assertFalse(
        AnalysisEngine.shouldReportSharedForegroundRestoreFailure(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS));
    assertTrue(
        AnalysisEngine.shouldReportSharedForegroundRestoreFailure(
            AnalysisResourceCoordinator.Purpose.USER_QUICK_ANALYSIS));
    assertTrue(
        AnalysisEngine.shouldReportSharedForegroundRestoreFailure(
            AnalysisResourceCoordinator.Purpose.WHOLE_GAME_ANALYSIS));
  }

  @Test
  void sharedBatchRestoreFailureDoesNotRunFinalProgressOrSuccessCallback() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      Lizzie.frame.analysisEngine = engine;
      setField(AnalysisEngine.class, engine, "silentProgress", true);
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      TrackingWaitForAnalysis waitFrame = allocate(TrackingWaitForAnalysis.class);
      AtomicInteger completions = new AtomicInteger();
      Lizzie.frame.isBatchAnalysisMode = true;
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      setField(AnalysisEngine.class, engine, "waitFrame", waitFrame);
      setIntField(AnalysisEngine.class, engine, "resultCount", 1);
      setField(
          AnalysisEngine.class,
          engine,
          "analyzeMap",
          new java.util.HashMap<Integer, BoardHistoryNode>(
              java.util.Map.of(1, Lizzie.board.getHistory().getCurrentHistoryNode())));
      engine.setCompletionCallback(restore -> completions.incrementAndGet());
      List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
      engine.setFailureCallback(failures::add);

      invokeAnalysisEngineSetResult(engine);
      foreground.failRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(0, waitFrame.progressCalls);
      assertEquals(0, completions.get());
      assertFalse(Lizzie.frame.isBatchAnalysisMode);
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
      AtomicInteger successfulQuits = new AtomicInteger();
      AtomicInteger failedQuits = new AtomicInteger();
      engine.normalQuit(successfulQuits::incrementAndGet, failedQuits::incrementAndGet);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(0, successfulQuits.get());
      assertEquals(1, failedQuits.get());
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
    }
  }

  @Test
  void sharedBatchFailureWaitsForForegroundRestoreBeforeFailureCallback() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      engine.setFailureCallback(failures::add);

      invokeAnalysisEngineFinishFailedRequestDispatch(engine);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertTrue(failures.isEmpty());
      assertTrue(engine.hasRequestLifecycleInProgress());

      foreground.completeRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(List.of(ForegroundRestoreResult.SUCCEEDED), failures);
      assertFalse(engine.hasRequestLifecycleInProgress());
    }
  }

  @Test
  void sharedBatchRestoreFailureDeliversFailureCallbackExactlyOnce() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      engine.setFailureCallback(failures::add);

      invokeAnalysisEngineFinishFailedRequestDispatch(engine);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertTrue(failures.isEmpty());

      foreground.failRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
      AtomicInteger successfulQuits = new AtomicInteger();
      AtomicInteger failedQuits = new AtomicInteger();
      engine.normalQuit(successfulQuits::incrementAndGet, failedQuits::incrementAndGet);
      invokeAnalysisEngineFinishFailedRequestDispatch(engine);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(0, successfulQuits.get());
      assertEquals(1, failedQuits.get());
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
    }
  }

  @Test
  void abortedSharedRequestWaitsForRestoreAndDeliversOnlyOnce() throws Exception {
    for (ForegroundRestoreResult restore :
        List.of(ForegroundRestoreResult.SUCCEEDED, ForegroundRestoreResult.FAILED)) {
      try (TestEnvironment env = TestEnvironment.open()) {
        boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
        TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
        DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
        List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
        setField(
            AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
        engine.setCompletionCallback(result -> completions.incrementAndGet());
        engine.setFailureCallback(failures::add);
        Method abort = AnalysisEngine.class.getDeclaredMethod("finishAbortedAnalysis");
        abort.setAccessible(true);

        abort.invoke(engine);
        abort.invoke(engine);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertTrue(failures.isEmpty());
        assertTrue(engine.hasRequestLifecycleInProgress());
        if (restore == ForegroundRestoreResult.SUCCEEDED) {
          foreground.completeRestore();
        } else {
          foreground.failRestore();
        }
        abort.invoke(engine);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertEquals(List.of(restore), failures);
        assertEquals(0, completions.get());
        assertFalse(engine.hasRequestLifecycleInProgress());
      }
    }
  }

  @Test
  void newRequestResetsPreviouslyFailedRestoreOutcome() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
      engine.setFailureCallback(failures::add);
      invokeAnalysisEngineFinishFailedRequestDispatch(engine);
      foreground.failRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);

      List<ForegroundRestoreResult> completions = new CopyOnWriteArrayList<>();
      engine.setCompletionCallback(completions::add);
      engine.startRequest(-1, -1, false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(List.of(ForegroundRestoreResult.NOT_REQUIRED), completions);
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
    }
  }

  @Test
  void normalQuitReportsActualRestoreResultAndRetainsItForLaterCleanup() throws Exception {
    for (ForegroundRestoreResult expected : ForegroundRestoreResult.values()) {
      try (TestEnvironment env = TestEnvironment.open()) {
        boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
        TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
        DeferredRestoreLeelaz foreground = null;
        if (expected != ForegroundRestoreResult.NOT_REQUIRED) {
          foreground = new DeferredRestoreLeelaz();
          setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
          setField(
              AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
        }
        List<ForegroundRestoreResult> results = new ArrayList<>();

        engine.normalQuitWithRestoreResult(results::add);

        if (foreground != null) {
          assertTrue(results.isEmpty());
          assertTrue(engine.hasRequestLifecycleInProgress());
          if (expected == ForegroundRestoreResult.SUCCEEDED) {
            foreground.completeRestore();
          } else {
            foreground.failRestore();
          }
        }
        assertEquals(List.of(expected), results);
        assertFalse(engine.hasRequestLifecycleInProgress());

        engine.normalQuitWithRestoreResult(results::add);

        assertEquals(List.of(expected, expected), results);
      }
    }
  }

  @Test
  void normalQuitContinuationJoinsAlreadyPendingSharedForegroundRestore() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      AtomicInteger continuations = new AtomicInteger();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      setField(
          AnalysisEngine.class,
          engine,
          "analyzeMap",
          new java.util.HashMap<Integer, BoardHistoryNode>(
              java.util.Map.of(1, Lizzie.board.getHistory().getCurrentHistoryNode())));

      invokeAnalysisEngineSetResult(engine);
      engine.normalQuit(continuations::incrementAndGet);

      assertEquals(0, continuations.get());
      foreground.completeRestore();
      assertEquals(1, continuations.get());
    }
  }

  @Test
  void normalQuitContinuationJoinsAlreadyPendingSharedForegroundRestoreFailure() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      AtomicInteger completions = new AtomicInteger();
      AtomicInteger failures = new AtomicInteger();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      setField(
          AnalysisEngine.class,
          engine,
          "analyzeMap",
          new java.util.HashMap<Integer, BoardHistoryNode>(
              java.util.Map.of(1, Lizzie.board.getHistory().getCurrentHistoryNode())));

      invokeAnalysisEngineSetResult(engine);
      engine.normalQuit(completions::incrementAndGet, failures::incrementAndGet);

      assertEquals(0, completions.get());
      assertEquals(0, failures.get());
      foreground.failRestore();
      assertEquals(0, completions.get());
      assertEquals(1, failures.get());
    }
  }

  @Test
  void normalQuitContinuationWaitsForSharedForegroundRestore() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      AtomicInteger continuations = new AtomicInteger();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));

      engine.normalQuit(continuations::incrementAndGet);

      assertEquals(0, continuations.get());
      foreground.completeRestore();
      assertEquals(1, continuations.get());
    }
  }

  @Test
  void normalQuitContinuationAlsoRunsOnceWhenSharedRestoreFails() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      AtomicInteger continuations = new AtomicInteger();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));

      engine.normalQuit(continuations::incrementAndGet);

      assertEquals(0, continuations.get());
      foreground.failRestore();
      assertEquals(1, continuations.get());
    }
  }

  @Test
  void normalQuitPermanentlyRejectsAWholeGameRestart() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;
      AnalysisEngine engine = new AnalysisEngine(false);

      engine.normalQuit();
      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);

      assertEquals(-1, requested);
      assertFalse(foreground.hasExclusiveGtpLease());
    }
  }

  @Test
  void foregroundLeaseStopFailureEndsRequestWithoutRunningSuccessContinuation() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.frame.isBatchAnalysisMode = true;
      AtomicInteger successfulContinuations = new AtomicInteger();
      AnalysisEngine engine = new AnalysisEngine(false);
      Lizzie.frame.analysisEngine = engine;
      setField(AnalysisEngine.class, engine, "silentProgress", true);
      engine.setCompletionCallback(restore -> successfulContinuations.incrementAndGet());

      engine.startRequest(1, -1, false);
      assertTrue(engine.isAnalysisInProgress());
      Lizzie.leelaz = null;
      int stopCommandId = getExclusiveStopCommandId(foreground);
      assertTrue(dispatchExclusiveLine(foreground, "?" + stopCommandId + " cannot stop"));

      assertFalse(engine.isAnalysisInProgress());
      assertEquals(0, successfulContinuations.get());
      assertFalse(Lizzie.frame.isBatchAnalysisMode);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, engine.getForegroundLeaseAvailability());
    }
  }

  @Test
  void foregroundLeaseSetupSendFailureEndsRequestWithoutRunningSuccessContinuation()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.frame.isBatchAnalysisMode = true;
      AtomicInteger successfulContinuations = new AtomicInteger();
      AnalysisEngine engine = new AnalysisEngine(false);
      Lizzie.frame.analysisEngine = engine;
      setField(AnalysisEngine.class, engine, "silentProgress", true);
      engine.setCompletionCallback(restore -> successfulContinuations.incrementAndGet());

      engine.startRequest(1, -1, false);
      int stopCommandId = getExclusiveStopCommandId(foreground);
      foreground.installCommandOutputForTest(
          new BufferedOutputStream(
              new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                  throw new IOException("simulated setup send failure");
                }
              }));
      Lizzie.leelaz = null;
      ResourceBundle previousResourceBundle = Lizzie.resourceBundle;
      Lizzie.resourceBundle = null;
      try {
        dispatchExclusiveLine(foreground, "=" + stopCommandId);
        assertTrue(dispatchExclusiveLine(foreground, ""));
      } finally {
        Lizzie.resourceBundle = previousResourceBundle;
      }

      assertFalse(engine.isAnalysisInProgress());
      assertEquals(0, successfulContinuations.get());
      assertFalse(Lizzie.frame.isBatchAnalysisMode);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, engine.getForegroundLeaseAvailability());
    }
  }

  @Test
  void foregroundReuseCapturesRulesAndAcknowledgesSetupCommandsInOrder() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      Leelaz foreground = reusableForegroundEngine(true);
      ByteArrayOutputStream output = installLeelazOutput(foreground);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.config.analysisUseCurrentRules = true;
      AnalysisEngine engine = new AnalysisEngine(false);

      engine.startRequest(1, -1, false);
      int leaseStopCommandId = getExclusiveStopCommandId(foreground);
      dispatchExclusiveLine(foreground, "=" + leaseStopCommandId);
      assertTrue(dispatchExclusiveLine(foreground, ""));
      assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("830000000 kata-get-rules\n"));

      String originalRules =
          "{\"koRule\":\"POSITIONAL\",\"scoringRule\":\"AREA\",\"taxRule\":\"NONE\"}";
      assertTrue(dispatchExclusiveLine(foreground, "=830000000 " + originalRules));
      assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("830000001 boardsize 3\n"));

      assertTrue(dispatchExclusiveLine(foreground, "=830000001"));
      assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("830000002 komi 7.5\n"));
      assertTrue(dispatchExclusiveLine(foreground, "=830000002"));
      assertTrue(
          output
              .toString(StandardCharsets.UTF_8)
              .endsWith("830000003 kata-set-rules " + originalRules + "\n"));
    }
  }

  @Test
  void currentEngineReevaluationKeepsOneLeaseAcrossStagesAndRestoresOnSessionClose()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 900, true);
      BoardHistoryList history = new BoardHistoryList(data);
      boardWithHistory(history);
      DeferredRestoreLeelaz foreground =
          reusableForegroundEngine(new DeferredRestoreLeelaz(), true);
      ByteArrayOutputStream output = installLeelazOutput(foreground);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = false;
      Lizzie.config.analysisUseCurrentRules = false;
      Lizzie.config.analysisSpecificRules = "{\"scoringRule\":\"TERRITORY\"}";
      AnalysisEngine engine = AnalysisEngine.createCurrentEngineWholeGame(500, foreground);
      Object run = new Object();
      AtomicInteger completions = new AtomicInteger();
      engine.setCompletionCallback(restore -> completions.incrementAndGet());
      engine.setKeepAliveAfterCurrentRequest(true);
      String actualRules =
          "{\"koRule\":\"POSITIONAL\",\"scoringRule\":\"AREA\",\"taxRule\":\"NONE\"}";
      try {
        assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 32, false, run));
        int leaseStopId = getExclusiveStopCommandId(foreground);
        assertTrue(dispatchExclusiveLine(foreground, "=" + leaseStopId));
        assertTrue(dispatchExclusiveLine(foreground, ""));
        int rulesId = (int) getField(AnalysisEngine.class, engine, "remoteGtpExpectedRulesCommandId");
        assertTrue(rulesId > 0);
        assertTrue(dispatchExclusiveLine(foreground, "=" + rulesId + " " + actualRules));
        acknowledgeSharedSetup(engine, foreground);
        completeSharedSearch(engine, foreground, 32, 32);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertEquals(1, completions.get());
        assertTrue(data.hasReusablePrimaryAnalysis(32, false, run));
        assertTrue(foreground.hasExclusiveGtpLease());
        assertNull(foreground.afterRestore, "ordinary pondering must not resume between stages");
        assertEquals(actualRules, data.primaryAnalysisProvenance().rules());
        assertNull(data.estimateArray);
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("TERRITORY"));

        engine.setCompletionCallback(restore -> completions.incrementAndGet());
        engine.setKeepAliveAfterCurrentRequest(true);
        assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false, run));
        assertEquals(
            0, getField(AnalysisEngine.class, engine, "remoteGtpExpectedRulesCommandId"));
        acknowledgeSharedSetup(engine, foreground);
        completeSharedSearch(engine, foreground, 120, 500);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertEquals(2, completions.get());
        assertTrue(data.hasReusablePrimaryAnalysis(500, false, run));
        assertEquals(500, data.rootVisits);
        assertEquals(actualRules, data.primaryAnalysisProvenance().rules());
        assertEquals(1, output.toString(StandardCharsets.UTF_8).split("kata-get-rules", -1).length - 1);
        assertNull(foreground.afterRestore);
        AtomicInteger handbacks = new AtomicInteger();
        engine.normalQuitWithRestoreResult(
            result -> {
              assertEquals(ForegroundRestoreResult.SUCCEEDED, result);
              handbacks.incrementAndGet();
            });
        assertNotNull(foreground.afterRestore);
        assertEquals(0, handbacks.get());
        foreground.completeRestore();
        assertEquals(1, handbacks.get());
        assertFalse(engine.hasRequestLifecycleInProgress());
      } finally {
        engine.shutdown();
      }
    }
  }

  @Test
  void currentEngineConditionChangeRejectsAReevaluationResponseWithoutChangingSavedSource()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 900, true);
      AnalysisProvenance saved = savedProvenance("saved engine");
      data.setPrimaryAnalysisProvenance(saved);
      BoardHistoryList history = new BoardHistoryList(data);
      boardWithHistory(history);
      DeferredRestoreLeelaz foreground =
          reusableForegroundEngine(new DeferredRestoreLeelaz(), true);
      Lizzie.leelaz = foreground;
      AnalysisEngine engine = AnalysisEngine.createCurrentEngineWholeGame(500, foreground);
      Object run = new Object();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());
      try {
        assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false, run));
        int leaseStopId = getExclusiveStopCommandId(foreground);
        dispatchExclusiveLine(foreground, "=" + leaseStopId);
        dispatchExclusiveLine(foreground, "");
        int rulesId = (int) getField(AnalysisEngine.class, engine, "remoteGtpExpectedRulesCommandId");
        dispatchExclusiveLine(foreground, "=" + rulesId + " {\"scoringRule\":\"AREA\"}");
        acknowledgeSharedSetup(engine, foreground);
        foreground.wrn = 0.8;

        completeSharedSearch(engine, foreground, 500, 500);

        assertEquals(900, data.getPlayouts());
        assertSame(saved, data.primaryAnalysisProvenance());
        assertFalse(data.hasReusablePrimaryAnalysis(500, false, run));
        assertNotNull(foreground.afterRestore);
        foreground.completeRestore();
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        assertEquals(1, failures.get());
      } finally {
        engine.shutdown();
      }
    }
  }

  @Test
  void sendRequestExpressesSnapshotRootPositionWithInitialStonesAndPlayer() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.empty(),
                  Stone.EMPTY,
                  false,
                  58));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertTrue(request.has("initialStones"), "snapshot-root request should send initial stones.");
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "snapshot-root request should serialize the current board as initial stones.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void foxHandicapSgfAnalysisRequestKeepsSetupStonesAndZeroKomi() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Board parseBoard =
          boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      parseBoard.isLoadingFile = true;
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3]KM[0]HA[2]AB[aa]AB[ca];W[ba])", true);
      boardWithHistory(history);
      history.next();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "B:C3"),
          stoneSet(request.getJSONArray("initialStones")),
          "Fox handicap imports must send root handicap stones to KataGo analysis.");
      assertEquals(List.of(List.of("W", "B3")), request.getJSONArray("moves").toList());
      assertEquals(0.0, request.getDouble("komi"), 0.0001);
      assertEquals(2, history.getGameInfo().getHandicap());
    }
  }

  @Test
  void visibleMainlineRequestWithNoPendingPositionsClosesAndRestoresForeground() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAutoQuit = false;
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      PonderTrackingLeelaz foreground = allocate(PonderTrackingLeelaz.class);
      foreground.pondering = true;
      Lizzie.leelaz = foreground;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(
          AnalysisEngine.class,
          engine,
          "purpose",
          AnalysisResourceCoordinator.Purpose.USER_QUICK_ANALYSIS);
      TrackingWaitForAnalysis waitFrame = allocate(TrackingWaitForAnalysis.class);
      waitFrame.setVisible(true);
      engine.waitFrame = waitFrame;
      AtomicInteger completions = new AtomicInteger();
      engine.setCompletionCallback(restore -> completions.incrementAndGet());

      engine.startRequest(-1, -1, true);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(0, engine.requestCount());
      assertEquals(2, waitFrame.visibilityCalls);
      assertFalse(waitFrame.lastVisible);
      assertEquals(1, completions.get());
      assertEquals(1, foreground.notPonderingCalls);
      assertEquals(1, foreground.ponderCalls);
      assertTrue(foreground.pondering);
    }
  }

  @Test
  void startRequestSkipsSnapshotRootOnlyHistory() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.empty(),
                  Stone.EMPTY,
                  false,
                  58));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(
          0,
          engine.requestCount(),
          "snapshot-root-only history should not emit batch requests from startRequest.");
    }
  }

  @Test
  void wholeGameRequestIncludesRootAndUsesExplicitTargetWithoutOwnership() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode firstMove = root.next().orElseThrow();

      int requested = engine.startWholeGameRequest(List.of(root, firstMove), 500, false);

      assertEquals(2, requested);
      JSONObject rootRequest = engine.requestAt(0);
      JSONObject moveRequest = engine.requestAt(1);
      assertEquals(500, rootRequest.getInt("maxVisits"));
      assertFalse(rootRequest.getBoolean("includeOwnership"));
      assertEquals(List.of(0), rootRequest.getJSONArray("analyzeTurns").toList());
      assertEquals(List.of(1), moveRequest.getJSONArray("analyzeTurns").toList());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wholeGameGeneratedCommentsPreserveEditIdentityWithoutHidingAuthoredChanges(boolean move)
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      if (move) {
        history.add(
            moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      }
      Board board = boardWithHistory(history);
      BoardHistoryNode node = history.getCurrentHistoryNode();
      BoardData data = node.getData();
      String originalComment = "reviewer's original annotation";
      data.comment = originalComment;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      FutureTask<GameEditSnapshot> capture = new FutureTask<>(() -> GameEditSnapshot.capture(board));
      javax.swing.SwingUtilities.invokeLater(capture);
      GameEditSnapshot beforeAnalysis = capture.get(5, TimeUnit.SECONDS);

      assertEquals(1, engine.startWholeGameRequest(List.of(node), 200, false));
      int firstId = Integer.parseInt(engine.requestAt(0).getString("id"));
      engine.parseResult(analysisResult(firstId, 200, 62.0));

      assertEquals(200, data.getPlayouts(), "the actual response must publish its analysis");
      assertFalse(originalComment.equals(data.comment), "publication must update the displayed comment");
      assertTrue(data.comment.contains(originalComment));
      FutureTask<Boolean> afterAnalysis = new FutureTask<>(() -> beforeAnalysis.isCurrent(board));
      javax.swing.SwingUtilities.invokeLater(afterAnalysis);
      assertTrue(afterAnalysis.get(5, TimeUnit.SECONDS),
          "whole-game analysis comments must not become authored edits at root or move nodes");

      String editedComment = "annotation edited while a save is pending";
      FutureTask<Void> edit = new FutureTask<>(() -> {
        data.comment = editedComment;
        return null;
      });
      javax.swing.SwingUtilities.invokeLater(edit);
      edit.get(5, TimeUnit.SECONDS);
      // Do not sample edit identity between the authored edit and another engine publication.
      assertEquals(1, engine.startWholeGameRequest(List.of(node), 400, false));
      int secondId = Integer.parseInt(engine.requestAt(1).getString("id"));
      engine.parseResult(analysisResult(secondId, 400, 68.0));

      assertEquals(400, data.getPlayouts());
      assertFalse(editedComment.equals(data.comment), "the later response must regenerate the comment");
      assertTrue(data.comment.contains(editedComment));
      FutureTask<Boolean> afterEditedAnalysis =
          new FutureTask<>(() -> beforeAnalysis.isCurrent(board));
      javax.swing.SwingUtilities.invokeLater(afterEditedAnalysis);
      assertFalse(afterEditedAnalysis.get(5, TimeUnit.SECONDS),
          "automatic publication must retain the authored change for the pending save guard");
      waitForMovelistRefreshThreads();
    }
  }

  @ParameterizedTest
  @CsvSource({"false, false", "false, true", "true, false", "true, true"})
  void progressRefreshesGraphWithAndWithoutWindow(boolean remote, boolean showProgressWindow)
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      for (int move = 1; move <= 17; move++) {
        boolean black = move % 2 == 1;
        history.add(passNode(emptyStones(), black ? Stone.BLACK : Stone.WHITE, !black, move));
      }
      boardWithHistory(history);
      SilentProgressTrackingFrame frame = allocate(SilentProgressTrackingFrame.class);
      Lizzie.frame = frame;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", remote);
      TrackingWaitForAnalysis window = allocate(TrackingWaitForAnalysis.class);
      if (showProgressWindow) engine.waitFrame = window;

      assertEquals(17, engine.startRequestMissingMainline(showProgressWindow));
      for (int completed = 1; completed <= 17; completed++) {
        if (remote) {
          completeRemoteGtpSearch(engine, 200, 62.0, 1.5);
        } else {
          engine.parseResult(analysisResult(completed, 200, 62.0));
        }

        int expectedRefreshes = completed <= 12 ? completed : completed < 16 ? 12 : completed - 3;
        assertEquals(
            expectedRefreshes,
            frame.silentProgressRefreshCalls,
            "graph refreshes are throttled during analysis and include the final result.");
        if (showProgressWindow) {
          assertEquals(completed, window.currentMove, "the progress window counts every result.");
          assertEquals(17, window.totalMoves);
        }
        assertEquals(
            completed == 17 ? 1 : 0,
            frame.fullRefreshCalls,
            "intermediate results must not rebuild the full board render surface.");
      }
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void graphProgressAlwaysRefreshesTheFinalResultBetweenThrottleIntervals() throws Exception {
    Method method =
        AnalysisEngine.class.getDeclaredMethod("shouldRefreshProgressGraph", int.class, int.class);
    method.setAccessible(true);

    assertTrue((boolean) method.invoke(null, 13, 13));
    assertTrue((boolean) method.invoke(null, 49, 49));
    assertFalse((boolean) method.invoke(null, 49, 50));
    assertTrue((boolean) method.invoke(null, 48, 50));
  }

  @Test
  void wholeGameRequestNeverDowngradesCachedResultsEvenWhenOverrideIsEnabled() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      rootData.setPlayouts(100);
      rootData.winrate = 41.0;
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger progress = new AtomicInteger();
      engine.setProgressListener((completed, total) -> progress.set(completed));

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      installCompleteAnalysis(rootData, 800, false);
      rootData.winrate = 44.0;
      engine.parseResult(analysisResult(1, 500, 70.0));

      assertEquals(800, rootData.getPlayouts());
      assertEquals(44.0, rootData.winrate, 0.0001);
      assertFalse(rootData.bestMoves.isEmpty());
      assertEquals(history.getStart(), history.getCurrentHistoryNode());
      assertEquals(
          0,
          progress.get(),
          "a response superseded by a complete higher-visit cache is consumed, not applied");
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void wholeGameResponseIsRejectedWhenKomiChangesDuringTheRequest() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      history.getGameInfo().setKomi(history.getGameInfo().getKomi() + 0.5);
      engine.parseResult(analysisResult(1, 500, 70.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void wholeGameResponseIsRejectedWhenAnalysisRulesChangeDuringTheRequest() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisUseCurrentRules = false;
      Lizzie.config.analysisSpecificRules = "";
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      assertEquals("tromp-taylor", engine.singleRequest().getString("rules"));
      Lizzie.config.analysisSpecificRules = "{\"scoringRule\":\"AREA\"}";
      engine.parseResult(analysisResult(1, 500, 70.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void wholeGameResponseIsRejectedWhenARequestedPositionMutatesInPlace() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      rootData.blackToPlay = !rootData.blackToPlay;
      engine.parseResult(analysisResult(1, 500, 70.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void wholeGameResponseIsRejectedAfterShutdownWasRequested() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      engine.requestShutdown();
      engine.parseResult(analysisResult(1, 500, 70.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void wholeGameRequestSkipsNodesAlreadyAtTheTargetRegardlessOfOverride() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(rootData, 500, false);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);

      assertEquals(0, requested);
      assertEquals(0, engine.requestCount());
    }
  }

  @Test
  void reevaluationReplacesDeeperSavedResultsAndCompletesOnlyForItsOwnRun() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 700, true);
      AnalysisProvenance saved = savedProvenance("saved engine");
      data.setPrimaryAnalysisProvenance(saved);
      data.setPrimaryOwnershipProvenance(saved);
      List<MoveData> savedMoves = data.bestMoves;
      BoardHistoryList history = new BoardHistoryList(data);
      boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      Object run = new Object();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      assertEquals(0, engine.startWholeGameRequest(List.of(root), 500, true));
      assertEquals(1, engine.startWholeGameRequest(List.of(root), 32, false, run));
      assertSame(savedMoves, data.bestMoves);
      assertSame(saved, data.primaryAnalysisProvenance());
      assertTrue(data.hasReusablePrimaryAnalysis(500, true));
      assertFalse(data.isChanged);

      engine.parseResult(analysisResult(1, 32, 65.0));

      assertEquals(32, data.getPlayouts());
      assertTrue(data.hasReusablePrimaryAnalysis(32, false, run));
      assertFalse(data.hasReusablePrimaryAnalysis(500, false, run));
      assertNull(data.estimateArray, "reevaluation must not borrow saved ownership from another run");
      assertNull(data.primaryOwnershipProvenance());
      assertNotNull(data.primaryAnalysisProvenance());
      assertEquals(1, engine.startWholeGameRequest(List.of(root), 500, false, run));

      engine.parseResult(analysisResultWithRootVisits(2, 120, 500, 70.0));

      assertEquals(500, data.getPlayouts());
      assertEquals(500, data.rootVisits);
      assertTrue(data.hasReusablePrimaryAnalysis(500, false, run));
      assertEquals(0, engine.startWholeGameRequest(List.of(root), 500, false, run));
      assertEquals(1, engine.startWholeGameRequest(List.of(root), 500, false, new Object()));
    }
  }

  @Test
  void cancellingReevaluationBeforeAResultPreservesReusableSavedDataAndItsSource() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 700, true);
      AnalysisProvenance saved = savedProvenance("saved engine");
      data.setPrimaryAnalysisProvenance(saved);
      data.setPrimaryOwnershipProvenance(saved);
      List<MoveData> savedMoves = data.bestMoves;
      List<Double> savedOwnership = data.estimateArray;
      BoardHistoryList history = new BoardHistoryList(data);
      boardWithHistory(history);
      Object run = new Object();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false, run));
      engine.requestShutdown();
      engine.parseResult(analysisResult(1, 500, 70.0));

      assertSame(savedMoves, data.bestMoves);
      assertSame(savedOwnership, data.estimateArray);
      assertSame(saved, data.primaryAnalysisProvenance());
      assertSame(saved, data.primaryOwnershipProvenance());
      assertTrue(data.hasReusablePrimaryAnalysis(500, true));
      assertFalse(data.hasReusablePrimaryAnalysis(500, true, run));
    }
  }

  @Test
  void malformedReevaluationResponseCannotClaimSavedPayloadForItsRun() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 700, true);
      AnalysisProvenance saved = savedProvenance("saved engine");
      data.setPrimaryAnalysisProvenance(saved);
      BoardHistoryList history = new BoardHistoryList(data);
      boardWithHistory(history);
      Object run = new Object();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false, run));

      engine.parseResult(emptyAnalysisResult(1));

      assertEquals(700, data.getPlayouts());
      assertSame(saved, data.primaryAnalysisProvenance());
      assertFalse(data.hasReusablePrimaryAnalysis(500, false, run));
      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false, run));
      engine.parseResult(analysisResult(2, 500, 70.0));
      assertTrue(data.hasReusablePrimaryAnalysis(500, false, run));
    }
  }

  @Test
  void dedicatedResultRecordsDispatchedConditionsWithoutBorrowingTheForegroundProducer()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      history.getGameInfo().setKomi(6.5);
      Lizzie.config.analysisSpecificRules = "{\"scoringRule\":\"TERRITORY\"}";
      Lizzie.leelaz.currentEnginename = "unrelated foreground";
      Lizzie.leelaz.pda = 1.5;
      Lizzie.leelaz.wrn = 0.6;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      assertTrue(engine.sendRequest(history.getStart()));
      history.getGameInfo().setKomi(7.5);
      Lizzie.config.analysisSpecificRules = "{\"scoringRule\":\"AREA\"}";

      engine.parseResult(analysisResultWithOwnership(1, 200, 70.0));

      AnalysisProvenance source = history.getStart().getData().primaryAnalysisProvenance();
      assertNotNull(source);
      assertEquals("TERRITORY", new JSONObject(source.rules()).getString("scoringRule"));
      assertEquals(6.5, source.komi());
      assertEquals("", source.engineName());
      assertEquals("", source.engineVersion());
      assertEquals("", source.modelName());
      assertNull(source.pda());
      assertNull(source.wrn());
      assertEquals(source, history.getStart().getData().primaryOwnershipProvenance());
    }
  }

  @Test
  void ordinaryDedicatedOwnershipBackfillPreservesTheDeeperEvaluationsSource() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.enableLizzieCache = true;
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 700, false);
      AnalysisProvenance saved = savedProvenance("deeper saved engine");
      data.setPrimaryAnalysisProvenance(saved);
      Object savedRun = new Object();
      data.markPrimaryAnalysisRun(savedRun);
      BoardHistoryList history = new BoardHistoryList(data);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      assertTrue(engine.sendRequest(history.getStart()));

      engine.parseResult(analysisResultWithOwnership(1, 200, 70.0));

      assertEquals(700, data.getPlayouts());
      assertSame(saved, data.primaryAnalysisProvenance());
      assertTrue(data.hasReusablePrimaryAnalysis(500, true, savedRun));
      assertNotNull(data.primaryOwnershipProvenance());
      assertEquals("", data.primaryOwnershipProvenance().engineName());
      assertEquals(BOARD_AREA, data.estimateArray.size());
    }
  }

  @Test
  void deeperReuseKeepsTheSourceOfOwnershipRetainedFromTheSavedResult() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 300, true);
      AnalysisProvenance saved = savedProvenance("saved ownership engine");
      data.setPrimaryAnalysisProvenance(saved);
      data.setPrimaryOwnershipProvenance(saved);
      BoardHistoryList history = new BoardHistoryList(data);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      engine.parseResult(analysisResult(1, 500, 70.0));

      assertEquals(500, data.getPlayouts());
      assertEquals("", data.primaryAnalysisProvenance().engineName());
      assertSame(saved, data.primaryOwnershipProvenance());
      assertEquals(List.of(0.25), data.estimateArray);
    }
  }

  @Test
  void invalidatedWholeGamePositionsRemainPendingWithTheirDisplayPayloadIntact() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      Board board = boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode firstMove = root.next().orElseThrow();
      List<BoardHistoryNode> positions = List.of(root, firstMove);
      for (BoardHistoryNode node : positions) {
        installCompleteAnalysis(node.getData(), 700, true);
      }
      List<MoveData> oldRootMoves = root.getData().bestMoves;
      List<MoveData> oldMoveMoves = firstMove.getData().bestMoves;
      WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(root, 32, 500);
      assertEquals(2, plan.completedAtLeast(500));
      assertTrue(plan.positionsMissingAnalysis(500, true).isEmpty());

      board.clearBestMovesAfterForFirstEngine(root);

      for (BoardHistoryNode node : positions) {
        assertTrue(node.getData().isChanged);
        assertEquals(700, node.getData().getPlayouts());
        assertTrue(node.getData().hasCompletePrimaryAnalysis(500, true));
      }
      assertSame(oldRootMoves, root.getData().bestMoves);
      assertSame(oldMoveMoves, firstMove.getData().bestMoves);
      assertEquals(positions, plan.positionsMissingAnalysis(500, true));
      assertEquals(0, plan.completedAtLeast(500));
    }
  }

  @ParameterizedTest
  @CsvSource({"500, false", "700, false", "900, false", "500, true", "700, true", "900, true"})
  void invalidatedWholeGameCacheIsReplacedByFreshAnalysis(int freshVisits, boolean ownership)
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      Board board = boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      List<BoardHistoryNode> positions = List.of(root, root.next().orElseThrow());
      for (BoardHistoryNode node : positions) {
        installCompleteAnalysis(node.getData(), 700, true);
        node.getData().bestMoves.get(0).coordinate = "A1";
        node.getData().estimateArray = List.of(0.9);
      }
      board.clearBestMovesAfterForFirstEngine(root);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      assertEquals(2, engine.startWholeGameRequest(positions, 500, ownership));

      for (int index = 0; index < positions.size(); index++) {
        BoardData data = positions.get(index).getData();
        assertEquals(700, data.getPlayouts());
        assertEquals("A1", data.bestMoves.get(0).coordinate);
        assertEquals(List.of(0.9), data.estimateArray);
        JSONObject request = engine.requestAt(index);
        assertEquals(500, request.getInt("maxVisits"));
        assertEquals(ownership, request.getBoolean("includeOwnership"));
        int requestId = Integer.parseInt(request.getString("id"));
        engine.parseResult(
            ownership
                ? analysisResultWithOwnership(requestId, freshVisits, 70.0)
                : analysisResult(requestId, freshVisits, 70.0));

        assertEquals(freshVisits, data.getPlayouts());
        assertEquals("B2", data.bestMoves.get(0).coordinate);
        assertEquals(70.0, data.winrate, 0.0001);
        assertFalse(data.isChanged, "accepted fresh analysis must become reusable at every depth");
        if (ownership) {
          assertEquals(BOARD_AREA, data.estimateArray.size());
          assertEquals(0.25, data.estimateArray.get(0), 0.0001);
        } else {
          assertNull(data.estimateArray, "ownership from invalidated analysis must not carry over");
        }
      }
      WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(root, 32, 500);
      assertTrue(plan.positionsMissingAnalysis(500, ownership).isEmpty());
      assertEquals(2, plan.completedAtLeast(500));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void invalidatedOwnershipCannotCompleteAFreshResponseWithoutRequestedOwnership() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 700, true);
      data.estimateArray = List.of(0.9);
      BoardHistoryList history = new BoardHistoryList(data);
      Board board = boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      board.clearBestMovesAfterForFirstEngine(root);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger progress = new AtomicInteger(-1);
      engine.setProgressListener((completed, total) -> progress.set(completed));

      assertEquals(1, engine.startWholeGameRequest(List.of(root), 500, true));
      engine.parseResult(analysisResult(1, 500, 70.0));

      assertEquals(500, data.getPlayouts());
      assertEquals(70.0, data.winrate, 0.0001);
      assertNull(data.estimateArray);
      assertFalse(data.isChanged);
      assertEquals(0, progress.get());
      WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(root, 32, 500);
      assertEquals(List.of(root), plan.positionsMissingAnalysis(500, true));
      assertTrue(plan.positionsMissingAnalysis(500, false).isEmpty());

      assertEquals(1, engine.startWholeGameRequest(List.of(root), 500, true));
      engine.parseResult(analysisResultWithOwnership(2, 500, 70.0));

      assertTrue(plan.positionsMissingAnalysis(500, true).isEmpty());
      assertEquals(BOARD_AREA, data.estimateArray.size());
      assertEquals(0.25, data.estimateArray.get(0), 0.0001);
    }
  }

  @Test
  void cancelledReplacementLeavesInvalidatedAnalysisAvailableForDisplay() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 700, true);
      BoardHistoryList history = new BoardHistoryList(data);
      Board board = boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      board.clearBestMovesAfterForFirstEngine(root);
      List<MoveData> oldMoves = data.bestMoves;
      List<Double> oldOwnership = data.estimateArray;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      assertEquals(1, engine.startWholeGameRequest(List.of(root), 500, false));
      engine.requestShutdown();
      engine.parseResult(analysisResultWithOwnership(1, 1000, 70.0));

      assertEquals(700, data.getPlayouts());
      assertSame(oldMoves, data.bestMoves);
      assertSame(oldOwnership, data.estimateArray);
      assertTrue(data.isChanged);
      assertTrue(data.hasCompletePrimaryAnalysis(500, true));
      assertEquals(
          List.of(root),
          WholeGameAnalysisPlan.create(root, 32, 500).positionsMissingAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void repeatedInvalidationRejectsAnInFlightReplacementWithoutClearingTheOldPayload()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 700, true);
      BoardHistoryList history = new BoardHistoryList(data);
      Board board = boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      board.clearBestMovesAfterForFirstEngine(root);
      List<MoveData> oldMoves = data.bestMoves;
      List<Double> oldOwnership = data.estimateArray;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());
      assertEquals(1, engine.startWholeGameRequest(List.of(root), 500, false));

      board.clearBestMovesAfterForFirstEngine(root);
      engine.parseResult(analysisResultWithOwnership(1, 500, 70.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertEquals(700, data.getPlayouts());
      assertSame(oldMoves, data.bestMoves);
      assertSame(oldOwnership, data.estimateArray);
      assertTrue(data.isChanged, "a response from before invalidation must not revalidate the cache");
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void invalidationBeforeTheFirstAnalysisResponseRejectsItsStalePayload() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(data);
      Board board = boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());
      assertEquals(1, engine.startWholeGameRequest(List.of(root), 500, false));

      board.clearBestMovesAfterForFirstEngine(root);
      engine.parseResult(analysisResult(1, 500, 70.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertEquals(0, data.getPlayouts());
      assertTrue(data.bestMoves.isEmpty());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void invalidationWhileAResponseWaitsForThePayloadLockRejectsThatResponse() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(data, 700, true);
      BoardHistoryList history = new BoardHistoryList(data);
      Board board = boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      board.clearBestMovesAfterForFirstEngine(root);
      List<MoveData> oldMoves = data.bestMoves;
      List<Double> oldOwnership = data.estimateArray;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      assertEquals(1, engine.startWholeGameRequest(List.of(root), 900, false));
      FutureTask<Void> parseResponse =
          new FutureTask<>(
              () -> {
                engine.parseResult(analysisResultWithOwnership(1, 1000, 70.0));
                return null;
              });
      Thread parser = new Thread(parseResponse, "analysis-payload-invalidation-test");

      try {
        synchronized (data) {
          parser.start();
          waitUntil(
              () -> {
                java.lang.management.ThreadInfo blocked =
                    java.lang.management.ManagementFactory.getThreadMXBean()
                        .getThreadInfo(parser.getId(), 32);
                return blocked != null
                    && blocked.getThreadState() == Thread.State.BLOCKED
                    && blocked.getLockInfo() != null
                    && blocked.getLockInfo().getIdentityHashCode() == System.identityHashCode(data)
                    && blocked.getLockOwnerId() == Thread.currentThread().getId()
                    && java.util.Arrays.stream(blocked.getStackTrace())
                        .anyMatch(
                            frame ->
                                frame.getClassName().equals(AnalysisEngine.class.getName())
                                    && frame.getMethodName().equals("parseResult"));
              });
          board.clearBestMovesAfterForFirstEngine(root);
        }
        parseResponse.get(2, TimeUnit.SECONDS);

        assertEquals(700, data.getPlayouts());
        assertSame(oldMoves, data.bestMoves);
        assertSame(oldOwnership, data.estimateArray);
        assertTrue(data.isChanged);
      } finally {
        parser.join(2000);
        assertFalse(parser.isAlive(), "response parsing must finish before restoring the fixture");
      }
    }
  }

  @Test
  void wholeGameRequestDoesNotMistakeVisitOnlyHeaderForDisplayableAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      rootData.setPlayouts(500);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);

      assertEquals(1, requested);
      assertEquals(1, engine.requestCount());
    }
  }

  @Test
  void localJsonRequestIsRegisteredBeforeAnImmediateResponseArrives() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger completions = new AtomicInteger();
      engine.synchronousResponse = analysisResult(1, 500, 70.0);
      engine.setCompletionCallback(restore -> completions.incrementAndGet());

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, requested);
      assertTrue(rootData.hasCompletePrimaryAnalysis(500, false));
      assertEquals(1, completions.get());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void localWholeGameUsesRootVisitsWithoutRequiringTopChildToReachTarget() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.synchronousResponse = analysisResultWithRootVisits(1, 300, 500, 70.0);

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);

      assertEquals(1, requested);
      assertEquals(500, rootData.getPlayouts());
      assertEquals(500, rootData.rootVisits);
      assertEquals(300, rootData.bestMoves.get(0).playouts);
      assertTrue(rootData.hasCompletePrimaryAnalysis(500, false));
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void childResearchCannotSatisfyWholeGameRootBudget() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.synchronousResponse = analysisResultWithRootVisits(1, 848, 100, 70.0);
      engine.startWholeGameRequest(List.of(history.getStart()), 500, false);
      assertEquals(0, rootData.getPlayouts());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void emptyJsonPayloadCompletesTheBatchWithoutReportingFalseSuccess() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger progress = new AtomicInteger(-1);
      AtomicInteger completions = new AtomicInteger();
      engine.synchronousResponse = emptyAnalysisResult(1);
      engine.setProgressListener((completed, total) -> progress.set(completed));
      engine.setCompletionCallback(restore -> completions.incrementAndGet());

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, requested);
      assertEquals(0, progress.get());
      assertEquals(1, completions.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void malformedImmediateResponseCannotStrandLaterWholeGameRequests() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode move = history.getCurrentHistoryNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.synchronousEngineLine = "{not-json";
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      int requested = engine.startWholeGameRequest(List.of(root, move), 500, false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(-1, requested);
      assertEquals(1, engine.requestCount());
      assertEquals(0, engine.pendingRequestCount());
      assertEquals(1, failures.get());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void malformedAnalysisResultDoesNotMarkStartupReadyBeforeExit() throws Exception {
    try (TestEnvironment env = TestEnvironment.open();
        EngineStartupDiagnostics diagnostics =
            new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      var attempt =
          diagnostics.begin("analysis-invalid", "WHOLE_GAME_ANALYSIS", List.of("engine"), true);
      setField(AnalysisEngine.class, engine, "startupDiagnosticAttempt", attempt);

      invokeAnalysisEngineParseLine(engine, "{not-json");

      assertEquals(
          "startup-exit",
          attempt.fail("startup-exit", "EOF before valid result").toJson().getString("phase"));
      assertEquals("analysis-invalid", attempt.snapshot().engineId());
    }
  }

  @Test
  void invalidMovePayloadDoesNotSuppressStartupExitDiagnostic() throws Exception {
    try (TestEnvironment env = TestEnvironment.open();
        EngineStartupDiagnostics diagnostics =
            new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      var attempt =
          diagnostics.begin("analysis-bad-moves", "WHOLE_GAME_ANALYSIS", List.of("engine"), true);
      setField(AnalysisEngine.class, engine, "startupDiagnosticAttempt", attempt);
      engine.synchronousEngineLine = "{\"id\":\"1\",\"moveInfos\":[{}]}";

      assertEquals(-1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      assertEquals(
          "startup-exit",
          attempt.fail("startup-exit", "EOF before valid moves").toJson().getString("phase"));
    }
  }

  @Test
  void requestedLocalOwnershipMustBePresentBeforeResultCountsAsComplete() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.synchronousResponse = analysisResultWithOwnership(1, 500, 70.0);

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, true);

      assertEquals(1, requested);
      assertTrue(engine.singleRequest().getBoolean("includeOwnership"));
      assertTrue(rootData.hasCompletePrimaryAnalysis(500, true));
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void missingRequestedOwnershipIsConsumedButNotReportedAsSuccess() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger progress = new AtomicInteger(-1);
      engine.synchronousResponse = analysisResult(1, 500, 70.0);
      engine.setProgressListener((completed, total) -> progress.set(completed));

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, true);

      assertEquals(1, requested);
      assertEquals(0, progress.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, true));
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void ownershipBackfillDoesNotDowngradeHigherVisitPrimaryAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(rootData, 10_000, false);
      rootData.bestMoves.get(0).coordinate = "A1";
      rootData.winrate = 44.0;
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.synchronousResponse = analysisResultWithOwnership(1, 500, 70.0);

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, true);

      assertEquals(1, requested);
      assertEquals(10_000, rootData.getPlayouts());
      assertEquals("A1", rootData.bestMoves.get(0).coordinate);
      assertEquals(44.0, rootData.winrate, 0.0001);
      assertTrue(rootData.hasCompletePrimaryAnalysis(500, true));
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteWholeGameUsesRootVisitsWithoutRequiringTopChildToReachTarget() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));

      completeRemoteGtpSearch(engine, 120, 500, 62.0, 1.5);

      assertEquals(500, rootData.getPlayouts());
      assertEquals(120, rootData.bestMoves.get(0).playouts);
      assertTrue(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteWholeGameResponseIsRejectedAfterShutdownDuringStopHandshake() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      int analyzeCommandId =
          numberedCommandIdStartingWith(lastCommand(engine.sentCommands), "kata-analyze ");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      String resultLine = remoteGtpInfoLine(120, 500, 62.0, 1.5);
      invokeAnalysisEngineParseLine(engine, resultLine);
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));

      engine.requestShutdown();
      invokeAnalysisEngineParseLine(engine, resultLine);
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void remoteWholeGameNormalizesMissingPvToTheCandidateMove() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 16, false));

      int analyzeCommandId =
          numberedCommandIdStartingWith(lastCommand(engine.sentCommands), "kata-analyze ");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      String resultLine = remoteGtpInfoLineWithoutPv(16, 62.0, 1.5);
      invokeAnalysisEngineParseLine(engine, resultLine);
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, resultLine);
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);

      assertEquals(List.of("B2"), rootData.bestMoves.get(0).variation);
      assertTrue(rootData.hasCompletePrimaryAnalysis(16, false));
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void startRequestMissingMainlineAnalyzesOnlyUnanalyzedMainlineMoves() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      analyzed.engineName = "cached-analysis";
      analyzed.analysisHeaderSlots = 3;
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(1, requested);
      assertEquals(1, engine.requestCount());
      JSONObject request = engine.singleRequest();
      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "B3")),
          request.getJSONArray("moves").toList(),
          "Yike curve completion should request only the missing current mainline node.");
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void missingMainlineCompletionOnPreloadedWorkerPreservesRoot() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode move = singleUnanalyzedMoveNode();
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      Lizzie.board.getHistory().toStart();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "isPreLoad", true);
      setField(AnalysisEngine.class, engine, "persistentPreload", true);
      AtomicInteger completed = new AtomicInteger();
      engine.setCompletionCallback(restore -> completed.incrementAndGet());

      assertEquals(1, engine.startRequestMissingMainline(false));
      engine.parseResult(analysisResult(1, 200, 62.0));
      waitForMovelistRefreshThreads();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, completed.get());
      assertEquals(200, move.getData().getPlayouts());
      assertSame(root, Lizzie.board.getHistory().getCurrentHistoryNode());
      assertEquals(0, engine.normalQuitCount);
    }
  }

  @Test
  void missingMainlineResponseIsRejectedAfterAnotherKifuLoads() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList firstHistory = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData firstMove =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      firstHistory.add(firstMove);
      boardWithHistory(firstHistory);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startRequestMissingMainline(false));

      BoardHistoryList secondHistory =
          new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData secondMove =
          moveNode(stones(placement(1, 0, Stone.BLACK)), new int[] {1, 0}, Stone.BLACK, false, 1);
      secondHistory.add(secondMove);
      boardWithHistory(secondHistory);

      engine.parseResult(analysisResult(1, 200, 62.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertEquals(0, firstMove.getPlayouts(), "a stale response must not mutate the old kifu");
      assertEquals(0, secondMove.getPlayouts(), "a stale response must not leak into the new kifu");
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void startRequestMissingMainlineStillSkipsExistingAnalysisWhenOverrideIsEnabled()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      analyzed.engineName = "cached-analysis";
      analyzed.analysisHeaderSlots = 3;
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(
          1,
          requested,
          "missing-mainline completion is a preservation path and should not reanalyze cached nodes.");
      assertEquals(1, engine.requestCount());
      assertEquals(List.of(2), engine.singleRequest().getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestMissingMainlineSkipsLowVisitExistingAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData lowVisits =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      lowVisits.setPlayouts(AnalysisEngine.targetAnalysisVisits() - 1);
      lowVisits.engineName = "partial-analysis";
      lowVisits.analysisHeaderSlots = 3;
      history.add(lowVisits);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(0, requested);
      assertEquals(0, engine.requestCount());
    }
  }

  @Test
  void startRequestReanalyzesExistingMovesWhenAlwaysOverrideIsEnabled() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(
          2,
          engine.requestCount(),
          "explicit override should still allow users to recalculate the whole selected range.");
      assertEquals(List.of(1), engine.requestAt(0).getJSONArray("analyzeTurns").toList());
      assertEquals(List.of(2), engine.requestAt(1).getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void parseResultHonorsAlwaysOverrideEvenWhenNewVisitsAreLower() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      Lizzie.config.enableLizzieCache = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      analyzed.winrate = 42.0;
      analyzed.engineName = "cached-analysis";
      history.add(analyzed);
      BoardHistoryNode analyzedNode = history.getCurrentHistoryNode();
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.trackPending(1, analyzedNode);
      engine.trackPending(2, analyzedNode);

      engine.parseResult(analysisResponse(1, 1, 0.77).toString());

      assertEquals(
          1,
          analyzed.getPlayouts(),
          "always override should bypass cache guards even when the new flash result has fewer visits.");
      assertEquals(77.0, analyzed.winrate, 0.0001);
      assertFalse("cached-analysis".equals(analyzed.engineName));
    }
  }

  @Test
  void completedManualAnalysisStillAutoQuitsWhenConfigured() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAutoQuit = true;
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.trackPending(1, node);

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(1, engine.normalQuitCount);
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void completedPreloadedAnalysisKeepsEngineWarm() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAutoQuit = true;
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "isPreLoad", true);
      setField(AnalysisEngine.class, engine, "persistentPreload", true);
      engine.trackPending(1, node);

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(0, engine.normalQuitCount);
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void completedAutoQuickAnalysisKeepsEngineWarmForNextKifu() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAutoQuit = true;
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.setKeepAliveAfterCurrentRequest(true);
      engine.trackPending(1, node);

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(0, engine.normalQuitCount);
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void startRequestMissingMainlineReportsDispatchFailure() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.failSends = true;
      engine.setCompletionCallback(restore -> {});

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(-1, requested);
      assertEquals(0, engine.pendingRequestCount());
      assertFalse(engine.isAnalysisInProgress());
      assertNull(engine.completionCallback());
    }
  }

  @Test
  void stoppedEngineRejectsLateMissingMainlineRequest() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.requestShutdown();

      assertEquals(-1, engine.startRequestMissingMainline(false));
      assertEquals(0, engine.pendingRequestCount());
      assertTrue(engine.sentCommands.isEmpty());
    }
  }

  @Test
  void failedSendRequestDoesNotLeavePendingAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.failSends = true;

      engine.sendRequest(history.getCurrentHistoryNode());

      assertEquals(0, engine.pendingRequestCount());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void failedBatchRequestDoesNotAdvanceToNextFile() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingLizzieFrame frame = (TrackingLizzieFrame) Lizzie.frame;
      frame.isBatchAnalysisMode = true;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.failSends = true;
      AtomicInteger completions = new AtomicInteger();
      engine.setCompletionCallback(restore -> completions.incrementAndGet());

      engine.startRequest(1, -1, false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(0, completions.get());
      assertEquals(0, engine.pendingRequestCount());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void emptyBatchRequestCompletesThroughItsCallbackOnTheEdt() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      Lizzie.frame.isBatchAna = true;
      Lizzie.frame.isBatchAnalysisMode = true;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger completions = new AtomicInteger();
      engine.setCompletionCallback(
          restore -> {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            assertEquals(ForegroundRestoreResult.NOT_REQUIRED, restore);
            completions.incrementAndGet();
          });

      engine.startRequest(-1, -1, false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, completions.get());
      assertTrue(engine.sentCommands.isEmpty());
      assertFalse(engine.hasRequestLifecycleInProgress());
      assertNull(engine.completionCallback());
    }
  }

  @Test
  void replacedWorkersFailureDoesNotStopTheCurrentBatch() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine oldEngine = TrackingAnalysisEngine.create();
      oldEngine.failSends = true;
      TrackingAnalysisEngine replacement = TrackingAnalysisEngine.create();
      Lizzie.frame.analysisEngine = replacement;
      Lizzie.frame.isBatchAnalysisMode = true;

      oldEngine.startRequest(1, -1, false);

      assertTrue(Lizzie.frame.isBatchAnalysisMode);
      assertSame(replacement, Lizzie.frame.analysisEngine);
      assertFalse(oldEngine.hasRequestLifecycleInProgress());
    }
  }

  @Test
  void emptyBatchRequestWaitsForForegroundRestoreBeforeCompleting() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      Lizzie.frame.isBatchAna = true;
      Lizzie.frame.isBatchAnalysisMode = true;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      List<ForegroundRestoreResult> completions = new CopyOnWriteArrayList<>();
      engine.setCompletionCallback(completions::add);

      engine.startRequestAllBranches(false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertTrue(completions.isEmpty());
      assertTrue(engine.hasRequestLifecycleInProgress());

      foreground.completeRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(List.of(ForegroundRestoreResult.SUCCEEDED), completions);
      assertFalse(engine.hasRequestLifecycleInProgress());
    }
  }

  @Test
  void remoteGtpAnalysisUsesKataAnalyzeCommandsForCurrentPosition() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);

      engine.sendRequest(node);

      assertTrue(engine.sentCommands.contains("boardsize 3"));
      assertTrue(engine.sentCommands.contains("komi 7.5"));
      assertTrue(engine.sentCommands.contains("kata-set-rules tromp-taylor"));
      assertTrue(engine.sentCommands.contains("set_position"));
      assertTrue(engine.sentCommands.contains("play B A3"));
      assertNumberedAnalyzeCommand(lastCommand(engine.sentCommands), "kata-analyze W 50");
      assertEquals(1, engine.pendingRequestCount());
    }
  }

  @Test
  void remoteGtpSnapshotUsesStaticSetPositionBeforeReplayingLaterActions() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK), placement(2, 0, Stone.WHITE));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 58));
      history.add(passNode(snapshotStones, Stone.WHITE, true, 59));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);

      engine.sendRequest(history.getCurrentHistoryNode());

      assertTrue(engine.sentCommands.contains("set_position B A3 W C3"));
      assertTrue(engine.sentCommands.contains("play W pass"));
      assertFalse(engine.sentCommands.contains("play B A3"));
      assertFalse(engine.sentCommands.contains("play W C3"));
    }
  }

  @Test
  void remoteGtpInfoLineCompletesAnalysisNodeAndWritesScoreLead() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);
      engine.sendRequest(node);
      int analyzeCommandId =
          assertNumberedAnalyzeCommand(lastCommand(engine.sentCommands), "kata-analyze W 50");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);

      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(33, 62.0, 1.5));
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      assertEquals(
          0,
          node.getData().getPlayouts(),
          "the first threshold frame must not be committed before KataGo finishes stopping.");

      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(48, 64.0, 1.7));
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(99, 12.0, -9.0));
      assertEquals(
          0,
          node.getData().getPlayouts(),
          "a late info frame after the numbered analyze boundary must be ignored.");
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);

      assertEquals(48, node.getData().getPlayouts());
      assertEquals(64.0, node.getData().winrate, 0.0001);
      assertEquals(1.7, node.getData().scoreMean, 0.0001);
      assertEquals(
          List.of("B2"),
          node.getData().bestMoves.get(0).variation,
          "rootInfo metadata must not leak into the principal variation.");
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpMainlineAnalysisAdvancesIncrementallyAfterFirstPosition() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      history.add(
          moveNode(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK)),
              new int[] {2, 0},
              Stone.BLACK,
              false,
              3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      assertEquals(1, countCommand(engine.sentCommands, "set_position"));
      assertTrue(engine.sentCommands.contains("play B A3"));
      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");

      completeRemoteGtpSearch(engine, 16, 62.0, 1.5);

      assertEquals(
          1,
          countCommand(engine.sentCommands, "set_position"),
          "remote mainline analysis should keep the board and avoid O(n^2) replay.");
      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze B 1 maxmoves 1 rootInfo true");
      assertTrue(engine.sentCommands.contains("play W B3"));

      completeRemoteGtpSearch(engine, 16, 58.0, 0.7);

      assertEquals(1, countCommand(engine.sentCommands, "set_position"));
      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");
      assertTrue(engine.sentCommands.contains("play B C3"));

      completeRemoteGtpSearch(engine, 16, 54.0, 0.3);

      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpMissingMainlineAnalysisAlsoAdvancesIncrementally() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.winrate = 51.0;
      analyzed.setPlayouts(100);
      MoveData existingMove = new MoveData();
      existingMove.coordinate = "B2";
      existingMove.playouts = 100;
      existingMove.winrate = 50.0;
      existingMove.variation = List.of("B2");
      analyzed.bestMoves = List.of(existingMove);
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      history.add(
          moveNode(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK)),
              new int[] {2, 0},
              Stone.BLACK,
              false,
              3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(2, requested);
      assertEquals(1, countCommand(engine.sentCommands, "set_position"));
      assertTrue(engine.sentCommands.contains("play B A3"));
      assertTrue(engine.sentCommands.contains("play W B3"));

      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze B 1 maxmoves 1 rootInfo true");
      completeRemoteGtpSearch(engine, 16, 58.0, 0.7);

      assertEquals(1, countCommand(engine.sentCommands, "set_position"));
      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");
      assertTrue(engine.sentCommands.contains("play B C3"));

      completeRemoteGtpSearch(engine, 16, 54.0, 0.3);

      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpSilentQuickCurveUsesStableSearchBudgetEvenWhenManualVisitsAreHigh()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisMaxVisits = 999;
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      String analyzeCommand = lastCommand(engine.sentCommands);
      int analyzeCommandId =
          assertNumberedAnalyzeCommand(analyzeCommand, "kata-analyze W 1 maxmoves 1 rootInfo true");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(15, 58.0, 0.9));
      assertEquals(
          analyzeCommand,
          lastCommand(engine.sentCommands),
          "remote quick curves should wait for the fixed search budget before stopping.");

      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(16, 62.0, 1.5));
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(24, 64.0, 1.7));
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);

      assertEquals(24, node.getData().getPlayouts());
      assertEquals(64.0, node.getData().winrate, 0.0001);
      assertEquals(1.7, node.getData().scoreMean, 0.0001);
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpStopErrorFailsWithoutCommittingResult() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      int analyzeCommandId =
          assertNumberedAnalyzeCommand(
              lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(16, 62.0, 1.5));
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, "?" + stopCommandId + " cannot stop");

      assertEquals(0, node.getData().getPlayouts());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void remoteGtpMissingStopAckFailsWithoutCommittingOrAdvancing() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      BoardHistoryNode firstNode = history.getCurrentHistoryNode();
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      int firstAnalyzeCommandId =
          assertNumberedAnalyzeCommand(
              lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");
      invokeAnalysisEngineParseLine(engine, "=" + firstAnalyzeCommandId);
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(16, 62.0, 1.5));
      int firstStopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, "");

      waitUntil(() -> !engine.isAnalysisInProgress());

      assertEquals(0, firstNode.getData().getPlayouts());
      assertEquals(1, countCommandContaining(engine.sentCommands, " kata-analyze "));
      assertEquals(firstStopCommandId, assertNumberedStopCommand(lastCommand(engine.sentCommands)));
    }
  }

  @Test
  void queuedRemoteGtpSetupSendFailureUsesDispatchFailureCleanup() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      Lizzie.frame.analysisEngine = engine;
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      Lizzie.frame.isBatchAnalysisMode = true;
      AtomicInteger successfulContinuations = new AtomicInteger();
      engine.setCompletionCallback(restore -> successfulContinuations.incrementAndGet());

      engine.startRequest(-1, -1, false);
      int analyzeCommandId =
          assertNumberedAnalyzeCommand(lastCommand(engine.sentCommands), "kata-analyze W 1");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(64, 62.0, 1.5));
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, "");
      engine.failSends = true;
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);

      assertFalse(engine.isAnalysisInProgress());
      assertEquals(0, successfulContinuations.get());
      assertFalse(Lizzie.frame.isBatchAnalysisMode);
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpBatchAnalysisKeepsConfiguredSearchBudget() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.batchAnalysisPlayouts = 64;
      ((TrackingLizzieFrame) Lizzie.frame).isBatchAnalysisMode = true;
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      int analyzeCommandId =
          assertNumberedAnalyzeCommand(lastCommand(engine.sentCommands), "kata-analyze W 1");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(64, 62.0, 1.5));
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);

      assertEquals(64, node.getData().getPlayouts());
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpSilentLongGamePrioritizesOverviewBeforeBackfill() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Stone[] empty = emptyStones();
      for (int moveNumber = 1; moveNumber <= 25; moveNumber++) {
        boolean blackMove = moveNumber % 2 == 1;
        history.add(passNode(empty, blackMove ? Stone.BLACK : Stone.WHITE, !blackMove, moveNumber));
      }
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");
      assertEquals(1, countCommandStartingWith(engine.sentCommands, "play "));

      completeRemoteGtpSearch(engine, 16, 62.0, 1.5);

      assertEquals(
          8,
          countCommandStartingWith(engine.sentCommands, "play "),
          "long remote quick curves should jump to sparse overview points first so users see a graph quickly.");
      assertTrue(lastCommand(engine.sentCommands).endsWith("maxmoves 1 rootInfo true"));
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void startRequestCountsOnlyMoveAndPassNodesWhenSelectingRange() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(
          snapshotNode(
              stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY, false, 1));
      history.add(passNode(stones(placement(0, 0, Stone.BLACK)), Stone.WHITE, true, 2));
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.BLACK)),
              new int[] {1, 0},
              Stone.BLACK,
              false,
              3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(2, 3, false);

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3"),
          stoneSet(request.getJSONArray("initialStones")),
          "range analysis should rebuild from the nearest SNAPSHOT anchor.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(
          List.of(List.of("W", "pass")),
          request.getJSONArray("moves").toList(),
          "range analysis should treat SNAPSHOT as a history boundary.");
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestIgnoresDummyPassWhenCollectingMoves() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK));
      Stone[] finalStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 1));
      BoardData dummyPass = passNode(snapshotStones, Stone.WHITE, true, 2);
      dummyPass.dummy = true;
      history.add(dummyPass);
      history.add(moveNode(finalStones, new int[] {1, 0}, Stone.BLACK, false, 3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(List.of(List.of("B", "B3")), request.getJSONArray("moves").toList());
      assertEquals(
          List.of(1),
          request.getJSONArray("analyzeTurns").toList(),
          "dummy PASS should stay out of request turn counting.");
    }
  }

  @Test
  void startRequestAllBranchesSkipsDummyPassNodesAndAnalyzesOnlyRealActions() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK));
      Stone[] finalStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 1));
      BoardData dummyPass = passNode(snapshotStones, Stone.WHITE, true, 2);
      dummyPass.dummy = true;
      history.add(dummyPass);
      history.add(moveNode(finalStones, new int[] {1, 0}, Stone.BLACK, false, 3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(1, engine.requestCount(), "branch analysis should skip dummy PASS nodes.");
      JSONObject request = engine.singleRequest();
      assertEquals(List.of(List.of("B", "B3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestAllBranchesSkipsSnapshotNodesAndAnalyzesOnlyRealActions() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY, false, 58));
      history.add(passNode(stones(placement(0, 0, Stone.BLACK)), Stone.WHITE, true, 59));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(1, engine.requestCount(), "all-branches scan should skip SNAPSHOT nodes.");
      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3"),
          stoneSet(request.getJSONArray("initialStones")),
          "all-branches scan should seed analysis from the snapshot board.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(List.of("W", "pass")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestSkipsMovesAtTargetVisitsButContinuesLowerVisits() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      int targetVisits = AnalysisEngine.targetAnalysisVisits();
      BoardData complete =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      complete.setPlayouts(targetVisits);
      history.add(complete);
      BoardData lowVisits =
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2);
      lowVisits.setPlayouts(targetVisits - 1);
      history.add(lowVisits);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(1, engine.requestCount(), "flash analysis should continue partial results.");
      JSONObject request = engine.singleRequest();
      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "B3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
      assertEquals(targetVisits, request.getInt("maxVisits"));
    }
  }

  @Test
  void startRequestOverrideReanalyzesMovesAtTargetVisits() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData complete =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      complete.setPlayouts(AnalysisEngine.targetAnalysisVisits());
      history.add(complete);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(1, engine.requestCount(), "explicit override should still reanalyze.");
      JSONObject request = engine.singleRequest();
      assertEquals(List.of(List.of("B", "A3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void silentStartRequestDoesNotPauseForegroundPonder() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.ponder();
      Lizzie.leelaz = leelaz;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(0, leelaz.togglePonderCount);
      assertTrue(leelaz.isPondering());
      assertEquals(1, engine.requestCount());
    }
  }

  @Test
  void startRequestAllBranchesSkipsOnlyBranchNodesAtTargetVisits() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      int targetVisits = AnalysisEngine.targetAnalysisVisits();
      BoardData complete =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      complete.setPlayouts(targetVisits);
      history.add(complete);
      BoardData lowVisits =
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2);
      lowVisits.setPlayouts(targetVisits - 1);
      history.add(lowVisits);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(
          1, engine.requestCount(), "branch flash analysis should continue partial nodes.");
      JSONObject request = engine.singleRequest();
      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "B3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestAllBranchesSkipsSnapshotRootOnlyHistory() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY, false, 58));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(
          0,
          engine.requestCount(),
          "snapshot-root-only history should not emit branch scan requests.");
    }
  }

  @Test
  void silentParseResultKeepsForegroundCurrentNodeAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData current =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      current.winrate = 41.0;
      current.setPlayouts(12);
      history.add(current);
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.ponder();
      Lizzie.leelaz = leelaz;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.startRequest(-1, -1, false);

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(12, current.getPlayouts());
      assertEquals(41.0, current.winrate, 0.0001);
      assertTrue(current.bestMoves.isEmpty());
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void silentParseResultUpdatesNonCurrentNodesWhileForegroundAnalyzes() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData current =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      history.add(current);
      BoardHistoryNode currentNode = history.getCurrentHistoryNode();
      BoardData next =
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2);
      next.winrate = 39.0;
      next.setPlayouts(8);
      history.add(next);
      history.setHead(currentNode);
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.ponder();
      Lizzie.leelaz = leelaz;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.sendRequest(history.getCurrentHistoryNode().next().get());

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(200, next.getPlayouts());
      assertEquals(62.0, next.winrate, 0.0001);
      assertFalse(next.bestMoves.isEmpty());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void sendRequestSetupOnlyStartStonePositionIncludesCurrentPlayer() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(0, 0, true));
      Lizzie.board.startStonelist.add(startStone(1, 0, false));
      history.getStart().getData().blackToPlay = false;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "setup-only analysis should send configured start stones.");
      assertEquals(
          "W",
          request.getString("initialPlayer"),
          "setup-only analysis should use the current position side to move.");
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestRootSnapshotPrefersSnapshotStonesOverConfiguredStartStones() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.empty(),
                  Stone.EMPTY,
                  false,
                  58));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(2, 2, true));
      Lizzie.board.startStonelist.add(startStone(1, 1, false));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "root SNAPSHOT should stay the request anchor even when start stones are configured.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestStartStonePositionWithMovesKeepsRootInitialPlayer() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Stone[] afterFirstMove =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(2, 0, Stone.WHITE));
      history.add(moveNode(afterFirstMove, new int[] {2, 0}, Stone.WHITE, true, 1));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(0, 0, true));
      Lizzie.board.startStonelist.add(startStone(1, 0, false));
      history.getStart().getData().blackToPlay = false;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "start-stone analysis with moves should still send configured start stones.");
      assertEquals(
          "W",
          request.getString("initialPlayer"),
          "start-stone analysis should keep root side-to-play even after real moves.");
      assertEquals(List.of(List.of("W", "C3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestWithStartStoneAndMidSnapshotUsesNearestSnapshotAsSharedAnchor() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Stone[] afterRootMove =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(2, 0, Stone.WHITE));
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK), placement(2, 0, Stone.WHITE));
      Stone[] finalStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(2, 0, Stone.WHITE));
      history.add(moveNode(afterRootMove, new int[] {2, 0}, Stone.WHITE, true, 1));
      history.add(snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, true, 1));
      history.add(passNode(snapshotStones, Stone.BLACK, false, 2));
      history.add(moveNode(finalStones, new int[] {1, 1}, Stone.WHITE, true, 3));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(0, 0, true));
      Lizzie.board.startStonelist.add(startStone(1, 0, false));
      history.getStart().getData().blackToPlay = false;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:C3"),
          stoneSet(request.getJSONArray("initialStones")),
          "start-stone history with a nearer snapshot should use that snapshot board as anchor.");
      assertEquals(
          "B",
          request.getString("initialPlayer"),
          "start-stone history with a nearer snapshot should use that snapshot side-to-play.");
      assertEquals(
          List.of(List.of("B", "pass"), List.of("W", "B2")),
          request.getJSONArray("moves").toList(),
          "analysis moves should stay scoped to actions after the same snapshot anchor.");
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void restoreClosedEngineBoardStateReplaysFromLatestSnapshotBoundary() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(snapshotNode(emptyStones(), Optional.empty(), Stone.EMPTY, false, 1));
      history.add(
          moveNode(stones(placement(1, 0, Stone.WHITE)), new int[] {1, 0}, Stone.WHITE, true, 2));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();

      leelaz.restoreClosedEngineBoardState(false);

      assertEquals("clear_board", leelaz.sentCommands.get(0));
      assertTrue(
          leelaz.sentCommands.get(1).startsWith("loadsgf "),
          "closed-engine restore should land the snapshot board exactly before later actions.");
      assertEquals(
          List.of("WHITE:B3"),
          leelaz.playedMoves,
          "closed-engine restore should replay only later real actions after the snapshot anchor.");
      assertArrayEquals(
          history.getCurrentHistoryNode().getData().stones,
          leelaz.copyStones(),
          "closed-engine restore should match the current board after the exact snapshot restore.");
    }
  }

  @Test
  void restoreClosedEngineBoardStateUsesLoadsgfForDeadSnapshotThenReplaysRealActions()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.BLACK),
              placement(0, 1, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(2, 1, Stone.BLACK),
              placement(0, 2, Stone.BLACK),
              placement(1, 2, Stone.BLACK));
      Stone[] finalStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.BLACK),
              placement(0, 1, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(2, 1, Stone.BLACK),
              placement(0, 2, Stone.BLACK),
              placement(1, 2, Stone.BLACK),
              placement(2, 2, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 58));
      history.add(passNode(snapshotStones, Stone.WHITE, true, 59));
      history.add(moveNode(finalStones, new int[] {2, 2}, Stone.BLACK, false, 60));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();

      leelaz.restoreClosedEngineBoardState(false);

      assertEquals(
          "clear_board",
          leelaz.sentCommands.get(0),
          "closed-engine restore should always reset the engine first.");
      assertTrue(
          leelaz.sentCommands.get(1).startsWith("loadsgf "),
          "dead snapshot anchors should restore through loadsgf before replaying actions.");
      assertEquals(
          List.of("WHITE:pass", "BLACK:C1"),
          leelaz.playedMoves,
          "after the static snapshot is restored, only real PASS/MOVE actions should be replayed.");
      assertArrayEquals(
          finalStones,
          leelaz.copyStones(),
          "loadsgf plus real actions should reproduce the final board exactly.");
      assertFalse(
          leelaz.isBlackToPlay(),
          "replaying the real actions after loadsgf should preserve the final side to play.");
    }
  }

  @Test
  void restoreClosedEngineBoardStateForRemovedStoneHistoryStillStartsFromSnapshotBoundary()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK), placement(2, 0, Stone.BLACK));
      Stone[] finalStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(2, 0, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 58));
      history.getCurrentHistoryNode().setRemovedStone();
      history.add(moveNode(finalStones, new int[] {1, 0}, Stone.WHITE, true, 59));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      Leelaz previousLeelaz = Lizzie.leelaz;
      Lizzie.leelaz = leelaz;
      try {
        leelaz.restoreClosedEngineBoardState(false);
      } finally {
        Lizzie.leelaz = previousLeelaz;
      }

      assertEquals(
          "clear_board",
          leelaz.sentCommands.get(0),
          "closed-engine restore should still clear the engine before restoring removed-stone history.");
      assertTrue(
          leelaz.sentCommands.get(1).startsWith("loadsgf "),
          "removed-stone history should restore from the latest snapshot boundary through loadsgf.");
      assertEquals(
          List.of("WHITE:B3"),
          leelaz.playedMoves,
          "removed-stone history should replay only the real actions after the snapshot boundary.");
      assertArrayEquals(
          finalStones,
          leelaz.copyStones(),
          "closed-engine restore should keep the final board exact after a removed-stone boundary.");
    }
  }

  private static Board boardWithHistory(BoardHistoryList history) throws Exception {
    waitForMovelistRefreshThreads();
    RequestBoard board = allocate(RequestBoard.class);
    board.movelistWorkers = new CopyOnWriteArrayList<>();
    board.movelistFailures = new CopyOnWriteArrayList<>();
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    Lizzie.board = board;
    return board;
  }

  private static BoardData moveNode(
      Stone[] stones, int[] lastMove, Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.move(
        stones,
        lastMove,
        color,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static BoardData passNode(
      Stone[] stones, Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        stones, color, blackToPlay, zobrist(stones), moveNumber, new int[BOARD_AREA], 0, 0, 50, 0);
  }

  private static BoardData snapshotNode(
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber) {
    return BoardData.snapshot(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static Stone[] stones(Placement... placements) {
    Stone[] stones = emptyStones();
    for (Placement placement : placements) {
      stones[Board.getIndex(placement.x, placement.y)] = placement.color;
    }
    return stones;
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static Set<String> stoneSet(JSONArray stones) {
    Set<String> result = new LinkedHashSet<>();
    for (Object entry : stones.toList()) {
      List<?> pair = (List<?>) entry;
      result.add(pair.get(0) + ":" + pair.get(1));
    }
    return result;
  }

  private static JSONObject analysisResponse(int id, int visits, double winrate) {
    JSONObject moveInfo = new JSONObject();
    moveInfo.put("order", 0);
    moveInfo.put("move", "C3");
    moveInfo.put("visits", visits);
    moveInfo.put("winrate", winrate);
    moveInfo.put("lcb", winrate);
    moveInfo.put("prior", 0.25);
    moveInfo.put("scoreLead", 1.5);
    moveInfo.put("scoreStdev", 0.3);
    moveInfo.put("pv", new JSONArray().put("C3"));

    JSONObject response = new JSONObject();
    response.put("id", String.valueOf(id));
    response.put("moveInfos", new JSONArray().put(moveInfo));
    return response;
  }

  private static String analysisResult(int id, int visits, double winrate) {
    JSONObject moveInfo = new JSONObject();
    moveInfo.put("move", "B2");
    moveInfo.put("visits", visits);
    moveInfo.put("winrate", winrate / 100.0);
    moveInfo.put("lcb", winrate / 100.0);
    moveInfo.put("prior", 0.25);
    moveInfo.put("scoreLead", 1.5);
    moveInfo.put("scoreStdev", 0.2);
    moveInfo.put("order", 0);
    moveInfo.put("pv", new JSONArray(List.of("B2")));
    JSONObject result = new JSONObject();
    result.put("id", String.valueOf(id));
    result.put("moveInfos", new JSONArray(List.of(moveInfo)));
    return result.toString();
  }

  private static String analysisResultWithOwnership(int id, int visits, double winrate) {
    JSONObject result = new JSONObject(analysisResult(id, visits, winrate));
    JSONArray ownership = new JSONArray();
    for (int index = 0; index < BOARD_AREA; index++) {
      ownership.put(index % 2 == 0 ? 0.25 : -0.25);
    }
    result.put("ownership", ownership);
    return result.toString();
  }

  private static String analysisResultWithRootVisits(
      int id, int moveVisits, int rootVisits, double winrate) {
    JSONObject result = new JSONObject(analysisResult(id, moveVisits, winrate));
    result.put("rootInfo", new JSONObject().put("visits", rootVisits));
    return result.toString();
  }

  private static String emptyAnalysisResult(int id) {
    return new JSONObject()
        .put("id", String.valueOf(id))
        .put("moveInfos", new JSONArray())
        .toString();
  }

  private static void installCompleteAnalysis(
      BoardData data, int visits, boolean includeOwnership) {
    MoveData move = new MoveData();
    move.coordinate = "B2";
    move.playouts = visits;
    move.winrate = 50.0;
    move.scoreMean = 1.5;
    move.scoreStdev = 0.2;
    move.isKataData = true;
    move.order = 0;
    move.variation = List.of("B2");
    data.setPlayouts(visits);
    data.bestMoves = new ArrayList<>(List.of(move));
    data.estimateArray = includeOwnership ? List.of(0.25) : null;
  }

  private static AnalysisProvenance savedProvenance(String engine) {
    return new AnalysisProvenance(
        engine, "1.16.4", "saved model", "a".repeat(64), "b".repeat(64), "chinese", 7.5, 0.0, 0.0, "");
  }

  private static BoardHistoryNode singleUnanalyzedMoveNode() throws Exception {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(
        moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
    boardWithHistory(history);
    return history.getCurrentHistoryNode();
  }

  private static void waitForMovelistRefreshThreads() throws InterruptedException {
    if (Lizzie.board instanceof RequestBoard) {
      ((RequestBoard) Lizzie.board).awaitMovelistRefresh();
    }
  }

  private static final class RequestBoard extends Board {
    private List<Thread> movelistWorkers;
    private List<Throwable> movelistFailures;
    private volatile CountDownLatch refreshStarted;
    private CountDownLatch awaitStarted;
    private Runnable beforeRefreshUpdate;

    @Override
    public synchronized void setMovelistAll(Runnable onComplete) {
      if (getHistory() == null) return;
      CountDownLatch started = new CountDownLatch(1);
      refreshStarted = started;
      super.setMovelistAll(onComplete);
      // Bind the actual worker before a later generation can replace its start signal.
      // updateMovelist records ownership before calling code that may need this monitor.
      try {
        assertTrue(started.await(5, TimeUnit.SECONDS), "move-list worker must start");
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted waiting for move-list worker", failure);
      }
    }

    @Override
    public void updateMovelist(BoardHistoryNode node) {
      Thread worker = Thread.currentThread();
      if (!"lizzie-movelist-refresh".equals(worker.getName())) {
        super.updateMovelist(node);
        return;
      }
      if (!movelistWorkers.contains(worker)) {
        worker.setUncaughtExceptionHandler((thread, failure) -> movelistFailures.add(failure));
        movelistWorkers.add(worker);
        refreshStarted.countDown();
      }
      if (beforeRefreshUpdate != null) beforeRefreshUpdate.run();
      super.updateMovelist(node);
    }

    private void awaitMovelistRefresh() throws InterruptedException {
      if (awaitStarted != null) awaitStarted.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      AssertionError failure = null;
      InterruptedException interrupted = null;
      for (int index = 0; index < movelistWorkers.size(); index++) {
        Thread worker = movelistWorkers.get(index);
        while (worker.isAlive()) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            AssertionError timeout = new AssertionError("owned move-list worker must exit");
            if (failure == null) failure = timeout;
            else failure.addSuppressed(timeout);
            break;
          }
          try {
            TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
          } catch (InterruptedException error) {
            if (interrupted == null) interrupted = error;
            else interrupted.addSuppressed(error);
          }
        }
      }
      if (!movelistFailures.isEmpty()) {
        AssertionError workerFailure =
            new AssertionError("owned move-list worker failed", movelistFailures.get(0));
        for (int index = 1; index < movelistFailures.size(); index++) {
          workerFailure.addSuppressed(movelistFailures.get(index));
        }
        if (failure == null) failure = workerFailure;
        else failure.addSuppressed(workerFailure);
      }
      if (interrupted != null) {
        if (failure != null) interrupted.addSuppressed(failure);
        throw interrupted;
      }
      if (failure != null) throw failure;
    }
  }

  @Test
  void alreadyBoundMovelistWorkerCannotReleaseTheNextStartGate() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      RequestBoard board = (RequestBoard) boardWithHistory(history);
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      AtomicInteger updates = new AtomicInteger();
      board.beforeRefreshUpdate =
          () -> {
            if (updates.getAndIncrement() != 0) return;
            entered.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS), "test must release first node");
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError(failure);
            }
          };
      try {
        board.setMovelistAll();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch nextStart = new CountDownLatch(1);
        board.refreshStarted = nextStart;
        release.countDown();
        board.awaitMovelistRefresh();
        assertEquals(2, updates.get(), "both real history nodes must be refreshed");
        assertEquals(1, nextStart.getCount(), "an old worker cannot bind a new generation");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void environmentCloseWaitsForOwnedMovelistWorkerBeforeRestoringGlobals() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Config scopedConfig = Lizzie.config;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      RequestBoard board = (RequestBoard) boardWithHistory(history);
      CountDownLatch release = new CountDownLatch(1);
      board.awaitStarted = new CountDownLatch(1);
      AtomicReference<Config> observedConfig = new AtomicReference<>();
      board.beforeRefreshUpdate =
          () -> {
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS), "test must release refresh");
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError(failure);
            }
            observedConfig.set(Lizzie.config);
          };
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        board.setMovelistAll();
        Future<?> closing =
            executor.submit(
                () -> {
                  env.close();
                  return null;
                });
        assertTrue(
            board.awaitStarted.await(5, TimeUnit.SECONDS), "close must join owned refreshes");
        assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
        assertSame(scopedConfig, Lizzie.config);
        release.countDown();
        closing.get(5, TimeUnit.SECONDS);
        assertSame(scopedConfig, observedConfig.get());
        assertTrue(board.movelistWorkers.stream().noneMatch(Thread::isAlive));
        assertSame(env.previousConfig, Lizzie.config);
      } finally {
        release.countDown();
        executor.shutdown();
        try {
          assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "fixture close must finish");
        } finally {
          executor.shutdownNow();
        }
      }
    }
  }

  @Test
  void interruptedCloseStillJoinsOwnedWorkersAndDrainsEdt() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Config scopedConfig = Lizzie.config;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      RequestBoard board = (RequestBoard) boardWithHistory(history);
      CountDownLatch entered = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      board.awaitStarted = new CountDownLatch(1);
      List<Config> workerConfigs = new CopyOnWriteArrayList<>();
      List<Config> callbackConfigs = new CopyOnWriteArrayList<>();
      AtomicReference<Thread> closer = new AtomicReference<>();
      AtomicBoolean interruptPreserved = new AtomicBoolean();
      board.beforeRefreshUpdate =
          () -> {
            entered.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS), "test must release both workers");
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError(failure);
            }
            workerConfigs.add(Lizzie.config);
            javax.swing.SwingUtilities.invokeLater(() -> callbackConfigs.add(Lizzie.config));
          };
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try (AutoCloseable probes =
          () -> {
            release.countDown();
            executor.shutdown();
            try {
              assertTrue(
                  executor.awaitTermination(5, TimeUnit.SECONDS), "fixture close must finish");
            } finally {
              executor.shutdownNow();
              board.awaitMovelistRefresh();
            }
          }) {
        board.setMovelistAll();
        board.setMovelistAll();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(2, board.movelistWorkers.size(), "both real worker generations must be owned");
        Future<?> closing =
            executor.submit(
                () -> {
                  closer.set(Thread.currentThread());
                  try {
                    env.close();
                    return null;
                  } finally {
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                  }
                });
        assertTrue(board.awaitStarted.await(5, TimeUnit.SECONDS));
        closer.get().interrupt();
        assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
        assertSame(scopedConfig, Lizzie.config);
        release.countDown();
        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> closing.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof InterruptedException);
        assertTrue(interruptPreserved.get(), "cleanup must restore the closer's interrupt flag");
        assertTrue(board.movelistWorkers.stream().noneMatch(Thread::isAlive));
        assertTrue(workerConfigs.size() >= 2, "both workers must finish against fixture globals");
        assertEquals(workerConfigs.size(), callbackConfigs.size());
        assertTrue(workerConfigs.stream().allMatch(config -> config == scopedConfig));
        assertTrue(callbackConfigs.stream().allMatch(config -> config == scopedConfig));
        assertSame(env.previousConfig, Lizzie.config);
      }
    }
  }

  @Test
  void workerFailureStillDrainsEdtAndRestoresGlobals() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Config scopedConfig = Lizzie.config;
      RequestBoard board =
          (RequestBoard)
              boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      IllegalStateException workerFailure = new IllegalStateException("move-list fixture failure");
      AtomicReference<Config> observedConfig = new AtomicReference<>();
      AtomicReference<Throwable> edtFailure = new AtomicReference<>();
      CountDownLatch edtEntered = new CountDownLatch(1);
      CountDownLatch releaseEdt = new CountDownLatch(1);
      board.awaitStarted = new CountDownLatch(1);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        javax.swing.SwingUtilities.invokeLater(
            () -> {
              edtEntered.countDown();
              try {
                if (!releaseEdt.await(5, TimeUnit.SECONDS)) {
                  edtFailure.set(new AssertionError("test must release EDT"));
                }
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                edtFailure.set(failure);
              }
            });
        assertTrue(edtEntered.await(5, TimeUnit.SECONDS));
        board.beforeRefreshUpdate =
            () -> {
              javax.swing.SwingUtilities.invokeLater(() -> observedConfig.set(Lizzie.config));
              throw workerFailure;
            };
        board.setMovelistAll();
        Future<?> closing =
            executor.submit(
                () -> {
                  env.close();
                  return null;
                });
        assertTrue(board.awaitStarted.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
        assertSame(scopedConfig, Lizzie.config);
        releaseEdt.countDown();
        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> closing.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof AssertionError);
        assertSame(workerFailure, failure.getCause().getCause());
        assertSame(
            scopedConfig, observedConfig.get(), "queued EDT work must retain fixture globals");
        assertNull(edtFailure.get());
        assertTrue(board.movelistWorkers.stream().noneMatch(Thread::isAlive));
        assertSame(env.previousConfig, Lizzie.config);
        assertSame(env.previousBoard, Lizzie.board);
        assertSame(env.previousFrame, Lizzie.frame);
        assertSame(env.previousLeelaz, Lizzie.leelaz);
      } finally {
        releaseEdt.countDown();
        executor.shutdown();
        try {
          assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "fixture close must finish");
        } finally {
          executor.shutdownNow();
        }
      }
    }
  }

  private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "condition was not met before timeout.");
  }

  private static Placement placement(int x, int y, Stone color) {
    return new Placement(x, y, color);
  }

  private static Movelist startStone(int x, int y, boolean isBlack) {
    Movelist move = new Movelist();
    move.x = x;
    move.y = y;
    move.isblack = isBlack;
    move.ispass = false;
    return move;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static void setField(Class<?> owner, Object target, String name, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Class<?> owner, Object target, String name) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Leelaz.ForegroundAnalysisLease foregroundLease(Leelaz engine) throws Exception {
    java.lang.reflect.Constructor<Leelaz.ForegroundAnalysisLease> constructor =
        Leelaz.ForegroundAnalysisLease.class.getDeclaredConstructor(Leelaz.class);
    constructor.setAccessible(true);
    return constructor.newInstance(engine);
  }

  private static boolean dispatchExclusiveLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  private static void setIntField(Class<?> owner, Object target, String name, int value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.setInt(target, value);
  }

  private static int getExclusiveStopCommandId(Leelaz engine) throws Exception {
    Field sessionField = Leelaz.class.getDeclaredField("exclusiveGtpSession");
    sessionField.setAccessible(true);
    Object session = sessionField.get(engine);
    Field stopCommandIdField = session.getClass().getDeclaredField("stopCommandId");
    stopCommandIdField.setAccessible(true);
    return stopCommandIdField.getInt(session);
  }

  private static String remoteGtpInfoLine(int visits, double winrate, double scoreLead) {
    return remoteGtpInfoLine(visits, visits, winrate, scoreLead);
  }

  private static String remoteGtpInfoLine(
      int moveVisits, int rootVisits, double winrate, double scoreLead) {
    double normalizedWinrate = winrate / 100.0;
    return "info move B2 visits "
        + moveVisits
        + " winrate "
        + normalizedWinrate
        + " scoreLead "
        + scoreLead
        + " order 0 pv B2 rootInfo visits "
        + rootVisits
        + " winrate "
        + normalizedWinrate
        + " scoreLead "
        + scoreLead;
  }

  private static String remoteGtpInfoLineWithoutPv(int visits, double winrate, double scoreLead) {
    double normalizedWinrate = winrate / 100.0;
    return "info move B2 visits "
        + visits
        + " winrate "
        + normalizedWinrate
        + " scoreLead "
        + scoreLead
        + " order 0 rootInfo visits "
        + visits
        + " winrate "
        + normalizedWinrate
        + " scoreLead "
        + scoreLead;
  }

  private static void completeRemoteGtpSearch(
      TrackingAnalysisEngine engine, int visits, double winrate, double scoreLead)
      throws Exception {
    completeRemoteGtpSearch(engine, visits, visits, winrate, scoreLead);
  }

  private static void completeRemoteGtpSearch(
      TrackingAnalysisEngine engine,
      int moveVisits,
      int rootVisits,
      double winrate,
      double scoreLead)
      throws Exception {
    int analyzeCommandId =
        numberedCommandIdStartingWith(lastCommand(engine.sentCommands), "kata-analyze ");
    invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
    invokeAnalysisEngineParseLine(
        engine, remoteGtpInfoLine(moveVisits, rootVisits, winrate, scoreLead));
    int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
    invokeAnalysisEngineParseLine(
        engine, remoteGtpInfoLine(moveVisits, rootVisits, winrate, scoreLead));
    invokeAnalysisEngineParseLine(engine, "");
    invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);
  }

  private static void acknowledgeSharedSetup(AnalysisEngine engine, Leelaz foreground)
      throws Exception {
    for (int count = 0; count < 12; count++) {
      int setupId = (int) getField(AnalysisEngine.class, engine, "remoteGtpExpectedSetupCommandId");
      if (setupId == 0) break;
      assertTrue(dispatchExclusiveLine(foreground, "=" + setupId));
    }
    assertEquals(0, getField(AnalysisEngine.class, engine, "remoteGtpExpectedSetupCommandId"));
    assertTrue((int) getField(AnalysisEngine.class, engine, "remoteGtpExpectedAnalyzeCommandId") > 0);
  }

  private static void completeSharedSearch(
      AnalysisEngine engine, Leelaz foreground, int moveVisits, int rootVisits) throws Exception {
    int analyzeId = (int) getField(AnalysisEngine.class, engine, "remoteGtpExpectedAnalyzeCommandId");
    assertTrue(dispatchExclusiveLine(foreground, "=" + analyzeId));
    assertTrue(dispatchExclusiveLine(foreground, remoteGtpInfoLine(moveVisits, rootVisits, 65.0, 1.5)));
    int stopId = (int) getField(AnalysisEngine.class, engine, "remoteGtpExpectedStopCommandId");
    assertTrue(stopId > 0);
    assertTrue(dispatchExclusiveLine(foreground, ""));
    assertTrue(dispatchExclusiveLine(foreground, "=" + stopId));
  }

  private static int assertNumberedStopCommand(String command) {
    return numberedCommandId(command, "stop");
  }

  private static int assertNumberedAnalyzeCommand(String command, String expectedCommand) {
    return numberedCommandId(command, expectedCommand);
  }

  private static int numberedCommandId(String command, String expectedCommand) {
    assertTrue(command != null, "remote GTP command must be present.");
    int separator = command.indexOf(' ');
    assertTrue(separator > 0, "remote GTP commands must use a numeric command id.");
    assertEquals(expectedCommand, command.substring(separator + 1));
    return Integer.parseInt(command.substring(0, separator));
  }

  private static int numberedCommandIdStartingWith(String command, String expectedPrefix) {
    assertTrue(command != null, "remote GTP command must be present.");
    int separator = command.indexOf(' ');
    assertTrue(separator > 0, "remote GTP commands must use a numeric command id.");
    assertTrue(command.substring(separator + 1).startsWith(expectedPrefix));
    return Integer.parseInt(command.substring(0, separator));
  }

  private static void invokeAnalysisEngineParseLine(AnalysisEngine engine, String line)
      throws Exception {
    Method method = AnalysisEngine.class.getDeclaredMethod("parseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void invokeAnalysisEngineSetResult(AnalysisEngine engine) throws Exception {
    Method method = AnalysisEngine.class.getDeclaredMethod("setResult");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static void invokeAnalysisEngineFinishFailedRequestDispatch(AnalysisEngine engine)
      throws Exception {
    Method method =
        AnalysisEngine.class.getDeclaredMethod("finishFailedRequestDispatch", boolean.class);
    method.setAccessible(true);
    method.invoke(engine, false);
  }

  private static void invokeAnalysisEnginePrepareRequestState(
      AnalysisEngine engine, boolean showProgressDialog) throws Exception {
    Method method = AnalysisEngine.class.getDeclaredMethod("prepareRequestState", boolean.class);
    method.setAccessible(true);
    method.invoke(engine, showProgressDialog);
  }

  private static void invokeAnalysisEngineResumeForeground(AnalysisEngine engine) throws Exception {
    Method method = AnalysisEngine.class.getDeclaredMethod("resumeForegroundAnalysisIfRequested");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static int countCommand(List<String> commands, String expected) {
    int count = 0;
    for (String command : commands) {
      if (expected.equals(command)) {
        count++;
      }
    }
    return count;
  }

  private static String lastCommand(List<String> commands) {
    return commands.get(commands.size() - 1);
  }

  private static int countCommandStartingWith(List<String> commands, String prefix) {
    int count = 0;
    for (String command : commands) {
      if (command.startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }

  private static int countCommandContaining(List<String> commands, String fragment) {
    int count = 0;
    for (String command : commands) {
      if (command.contains(fragment)) {
        count++;
      }
    }
    return count;
  }

  private static final class Placement {
    private final int x;
    private final int y;
    private final Stone color;

    private Placement(int x, int y, Stone color) {
      this.x = x;
      this.y = y;
      this.color = color;
    }
  }

  private Leelaz reusableForegroundEngine(boolean katago) throws Exception {
    return reusableForegroundEngine(new Leelaz(""), katago);
  }

  private <T extends Leelaz> T reusableForegroundEngine(T engine, boolean katago) throws Exception {
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = katago;
    engine.commandLists.addAll(
        List.of(
            "stop",
            "boardsize",
            "komi",
            "kata-get-rules",
            "kata-set-rules",
            "clear_board",
            "play",
            "set_position",
            "kata-analyze"));
    engine.installCommandOutputForTest(new ByteArrayOutputStream());
    Field capabilityDiscovery = Leelaz.class.getDeclaredField("endGetCommandList");
    capabilityDiscovery.setAccessible(true);
    capabilityDiscovery.set(engine, true);
    createdForegroundEngines.add(engine);
    return engine;
  }

  private static ByteArrayOutputStream installLeelazOutput(Leelaz engine) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    engine.installCommandOutputForTest(output);
    return output;
  }

  private static void closeExclusiveSessionForTest(Leelaz engine) throws Exception {
    if (engine == null) {
      return;
    }
    Field field = Leelaz.class.getDeclaredField("exclusiveGtpSession");
    field.setAccessible(true);
    Object session = field.get(engine);
    if (session == null) {
      return;
    }
    Method cancelInitial =
        Leelaz.class.getDeclaredMethod("cancelExclusiveGtpInitialStopTimeout", session.getClass());
    cancelInitial.setAccessible(true);
    cancelInitial.invoke(engine, session);
    Method cancelRelease =
        Leelaz.class.getDeclaredMethod("cancelExclusiveGtpReleaseStopTimeout", session.getClass());
    cancelRelease.setAccessible(true);
    cancelRelease.invoke(engine, session);
    Method method = Leelaz.class.getDeclaredMethod("closeExclusiveGtpSession", session.getClass());
    method.setAccessible(true);
    method.invoke(engine, session);
  }

  private static void completeForegroundRestore(Leelaz engine) throws Exception {
    Field sessionField = Leelaz.class.getDeclaredField("foregroundRestoreSession");
    sessionField.setAccessible(true);
    Object session = sessionField.get(engine);
    assertTrue(session != null, "foreground restore session should be active");
    Class<?> sessionType = session.getClass();
    Field restoreThreadField = sessionType.getDeclaredField("restoreThread");
    restoreThreadField.setAccessible(true);
    Field restoreCompletedField = sessionType.getDeclaredField("restoreCompleted");
    restoreCompletedField.setAccessible(true);
    Method completeMethod =
        Leelaz.class.getDeclaredMethod("completeForegroundRestore", sessionType);
    completeMethod.setAccessible(true);
    for (int attempt = 0; attempt < 5; attempt++) {
      Thread restoreThread = (Thread) restoreThreadField.get(session);
      if (restoreThread != null && restoreThread != Thread.currentThread()) {
        restoreThread.join(2000);
        assertFalse(restoreThread.isAlive(), "foreground restore attempt did not finish");
      }
      completeMethod.invoke(engine, session);
      if ((boolean) restoreCompletedField.get(session)) {
        return;
      }
    }
    assertTrue(false, "foreground restore retries did not converge");
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final LizzieFrame ownedFrame;
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Leelaz previousLeelaz;
    private boolean closed;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousLeelaz,
        LizzieFrame ownedFrame) {
      this.ownedFrame = ownedFrame;
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousLeelaz = previousLeelaz;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      Leelaz previousLeelaz = Lizzie.leelaz;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.analysisMaxVisits = 32;
      config.batchAnalysisPlayouts = 64;
      config.showPvVisits = false;
      config.showKataGoEstimate = false;
      config.useMovesOwnership = false;
      config.analysisUseCurrentRules = false;
      config.analysisSpecificRules = "";
      config.currentKataGoRules = "";
      config.autoLoadKataRules = false;
      config.kataRules = "";
      config.analysisEnginePreLoad = false;
      config.readKomi = true;
      Lizzie.config = config;

      TrackingLizzieFrame frame = allocate(TrackingLizzieFrame.class);
      frame.isBatchAnalysisMode = false;
      frame.priorityMoveCoords = new ArrayList<>();
      frame.clickOrder = -1;
      frame.selectedorder = -1;
      frame.currentRow = -1;
      frame.clickbadmove = LizzieFrame.outOfBoundCoordinate;
      frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
      featurecat.lizzie.gui.AnalysisUiRefreshTestSupport.install(frame);
      Lizzie.frame = frame;
      Lizzie.leelaz = allocate(Leelaz.class);

      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousConfig,
          previousBoard,
          previousFrame,
          previousLeelaz,
          frame);
    }

    @Override
    public synchronized void close() throws Exception {
      if (closed) return;
      closed = true;
      Throwable failure = null;
      boolean interrupted = Thread.interrupted();
      try {
        closeExclusiveSessionForTest(Lizzie.leelaz);
      } catch (Exception | Error error) {
        if (error instanceof InterruptedException) interrupted = true;
        failure = error;
      }
      try {
        waitForMovelistRefreshThreads();
      } catch (Exception | Error error) {
        if (error instanceof InterruptedException) interrupted = true;
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
      try {
        // Worker and engine completion can queue refreshes, even when their own work fails.
        FutureTask<Void> drained = new FutureTask<>(() -> null);
        javax.swing.SwingUtilities.invokeLater(drained);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0 && !drained.isDone()) {
            throw new TimeoutException("queued EDT work must finish before restoring globals");
          }
          try {
            drained.get(Math.max(0L, remaining), TimeUnit.NANOSECONDS);
            featurecat.lizzie.gui.AnalysisUiRefreshTestSupport.awaitIdle(ownedFrame);
            break;
          } catch (InterruptedException error) {
            interrupted = true;
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
          }
        }
      } catch (Exception | Error error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      } finally {
        Board.boardWidth = previousBoardWidth;
        Board.boardHeight = previousBoardHeight;
        Zobrist.init();
        Lizzie.config = previousConfig;
        Lizzie.board = previousBoard;
        Lizzie.frame = previousFrame;
        Lizzie.leelaz = previousLeelaz;
        if (interrupted) Thread.currentThread().interrupt();
      }
      if (failure instanceof Error) throw (Error) failure;
      if (failure != null) throw (Exception) failure;
    }
  }

  private static final class TrackingLizzieFrame extends LizzieFrame {
    private int foregroundReservationConflictCount;
    private int contributionStarts;
    private int contributeBenchmarkConflictCount;
    private int retainedModeConflictCount;

    private TrackingLizzieFrame() {}

    @Override
    public void updateTitle() {}

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void clearKataEstimate() {}

    @Override
    protected void showForegroundEngineModeReservationConflict() {
      foregroundReservationConflictCount++;
    }

    @Override
    protected void showForegroundEngineLeaseConflict() {
      retainedModeConflictCount++;
    }

    @Override
    protected void startContributeEngineReserved() {
      contributionStarts++;
    }

    @Override
    protected void showContributeBenchmarkConflict() {
      contributeBenchmarkConflictCount++;
    }
  }

  private static final class SilentProgressTrackingFrame extends LizzieFrame {
    private int silentProgressRefreshCalls;
    private int fullRefreshCalls;

    private SilentProgressTrackingFrame() {}

    @Override
    public void refreshSilentAnalysisProgress() {
      silentProgressRefreshCalls++;
    }

    @Override
    public void refresh() {
      fullRefreshCalls++;
    }

    @Override
    public void requestProblemListRefresh() {}
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private volatile CountDownLatch errorEntered;
    private volatile CountDownLatch releaseError;

    private SilentGtpConsole() {
      super(null);
    }

    @Override
    public void addCommand(String command, int commandNumber, String engineName) {}

    @Override
    public void addLine(String line) {}

    @Override
    public void addErrorLine(String line) {
      if (errorEntered == null) return;
      errorEntered.countDown();
      boolean interrupted = false;
      while (true) {
        try {
          releaseError.await();
          break;
        } catch (InterruptedException e) {
          // Keep the test reader busy until the test releases it, even if cleanup interrupts it.
          interrupted = true;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static final class PonderTrackingLeelaz extends Leelaz {
    private boolean pondering;
    private int notPonderingCalls;
    private int nameCommandCalls;
    private int ponderCalls;

    private PonderTrackingLeelaz() throws IOException {
      super("");
    }

    @Override
    public boolean isPondering() {
      return pondering;
    }

    @Override
    public void notPondering() {
      notPonderingCalls++;
      pondering = false;
    }

    @Override
    public void nameCmd() {
      nameCommandCalls++;
    }

    @Override
    public void ponder() {
      ponderCalls++;
      pondering = true;
    }
  }

  private static final class DeferredRestoreLeelaz extends Leelaz {
    private Runnable afterRestore;
    private Runnable afterRestoreFailure;

    private DeferredRestoreLeelaz() throws IOException {
      super("");
    }

    @Override
    public boolean endForegroundAnalysisLease(Object owner, Runnable completion, Runnable failure) {
      afterRestore = completion;
      afterRestoreFailure = failure;
      return true;
    }

    private void completeRestore() {
      Runnable completion = afterRestore;
      afterRestore = null;
      completion.run();
    }

    private void failRestore() {
      Runnable failure = afterRestoreFailure;
      afterRestore = null;
      afterRestoreFailure = null;
      failure.run();
    }
  }

  private static final class TrackingWaitForAnalysis extends WaitForAnalysis {
    private int progressCalls;
    private int currentMove;
    private int totalMoves;
    private int visibilityCalls;
    private boolean lastVisible;

    private TrackingWaitForAnalysis() {}

    @Override
    public void setProgress(int curMove, int allMove) {
      progressCalls++;
      currentMove = curMove;
      totalMoves = allMove;
    }

    @Override
    public void setVisible(boolean visible) {
      visibilityCalls++;
      lastVisible = visible;
    }

    @Override
    public boolean isVisible() {
      return lastVisible;
    }

    @Override
    public void setLocationRelativeTo(java.awt.Component component) {}
  }

  private static final class TrackingAnalysisEngine extends AnalysisEngine {
    private List<String> sentCommands;
    private boolean failSends;
    private String synchronousResponse;
    private String synchronousEngineLine;
    private int normalQuitCount;

    private TrackingAnalysisEngine() throws IOException {
      super(true);
    }

    private static TrackingAnalysisEngine create() throws Exception {
      TrackingAnalysisEngine engine = allocate(TrackingAnalysisEngine.class);
      engine.sentCommands = new CopyOnWriteArrayList<>();
      setField(
          AnalysisEngine.class, engine, "analyzeMap", new java.util.HashMap<Integer, Object>());
      setIntField(AnalysisEngine.class, engine, "globalID", 1);
      setField(AnalysisEngine.class, engine, "waitFrame", null);
      setField(AnalysisEngine.class, engine, "silentProgress", false);
      setField(AnalysisEngine.class, engine, "shouldRePonder", false);
      setField(AnalysisEngine.class, engine, "isLoaded", true);
      setField(
          AnalysisEngine.class,
          engine,
          "foregroundRestoreResult",
          ForegroundRestoreResult.NOT_REQUIRED);
      setField(AnalysisEngine.class, engine, "resourceBundle", Lizzie.resourceBundle);
      return engine;
    }

    @Override
    public boolean sendCommand(String command) {
      if (failSends) {
        return false;
      }
      sentCommands.add(command);
      String response = synchronousResponse;
      synchronousResponse = null;
      if (response != null) {
        parseResult(response);
      }
      String engineLine = synchronousEngineLine;
      synchronousEngineLine = null;
      if (engineLine != null) {
        try {
          invokeAnalysisEngineParseLine(this, engineLine);
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      }
      return true;
    }

    @Override
    public void normalQuit() {
      normalQuitCount++;
    }

    private JSONObject singleRequest() {
      assertEquals(1, sentCommands.size(), "test should capture exactly one analysis request.");
      return new JSONObject(sentCommands.get(0));
    }

    private int requestCount() {
      return sentCommands.size();
    }

    private JSONObject requestAt(int index) {
      return new JSONObject(sentCommands.get(index));
    }

    @SuppressWarnings("unchecked")
    private void trackPending(int id, BoardHistoryNode node) throws Exception {
      Field field = AnalysisEngine.class.getDeclaredField("analyzeMap");
      field.setAccessible(true);
      ((java.util.Map<Integer, BoardHistoryNode>) field.get(this)).put(id, node);
      setField(AnalysisEngine.class, this, "requestDispatchComplete", true);
    }

    private int pendingRequestCount() throws Exception {
      Field field = AnalysisEngine.class.getDeclaredField("analyzeMap");
      field.setAccessible(true);
      return ((java.util.Map<?, ?>) field.get(this)).size();
    }

    private Runnable completionCallback() throws Exception {
      Field field = AnalysisEngine.class.getDeclaredField("completionCallback");
      field.setAccessible(true);
      return (Runnable) field.get(this);
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE;

    static {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        UNSAFE = (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
