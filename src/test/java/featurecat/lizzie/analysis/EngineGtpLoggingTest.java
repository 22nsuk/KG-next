package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.EngineObservation;
import featurecat.lizzie.logging.LogStream;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.TraceScope;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EngineGtpLoggingTest {
  private static final String RAW_MOVE = "T03_RAW_MOVE";
  private static final String INFO_CANARY = "T03_INFO_CANARY";

  @TempDir Path tempDir;
  private Config previousConfig;
  private Leelaz previousLeelaz;
  private GtpConsolePane previousConsole;
  private Board previousBoard;
  private LizzieFrame previousFrame;

  @AfterEach
  void tearDown() {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    Lizzie.config = previousConfig;
    Lizzie.setPrimaryEngine(previousLeelaz);
    Lizzie.gtpConsole = previousConsole;
    Lizzie.board = previousBoard;
    Lizzie.frame = previousFrame;
  }

  @Test
  void engineLifecycleUsesNewIdentitiesAndReadyWithoutRawProtocol() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.isLoaded = false;
    Method markLoaded = Leelaz.class.getDeclaredMethod("markEngineLoaded");
    markLoaded.setAccessible(true);
    markLoaded.invoke(engine);
    String first = EngineObservation.identityFor(engine);
    EngineObservation.restartInstance(engine, "MAIN_BOARD");
    String second = EngineObservation.identityFor(engine);
    awaitLogs(runtime);

    assertNotEquals(first, second);
    String app = readApp();
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engine event=started"), app);
    assertTrue(app.contains("engine event=ready"), app);
    assertTrue(app.contains("engine=" + first), app);
    assertTrue(app.contains("engine=" + second), app);
    assertTrue(app.contains("purpose=MAIN_BOARD"), app);
    assertFalse(app.contains("gtp raw"), app);
  }

  @Test
  void startupFailureAllocatesEngineIdentityWithoutStartedEvent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    engine.setEngineCommand(
        "\"C:\\\\Users\\\\Player\\\\secret\\\\katago.exe\" gtp "
            + "-model \"C:\\\\Users\\\\Player\\\\secret\\\\model.bin.gz\"");
    reportEngineFailure(engine, "ssh login failed");
    awaitLogs(runtime);
    String app = readApp();
    assertTrue(app.contains("engine event=failed reason=ssh login failed"), app);
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engineType=katago"), app);
    assertTrue(app.contains("source=user-configured"), app);
    assertTrue(app.contains("purpose=MAIN_BOARD"), app);
    assertTrue(app.contains("model=model.bin.gz"), app);
    assertFalse(app.contains("engine event=started"), app);
    assertFalse(app.contains("Player"), app);
    assertFalse(app.contains("secret"), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=failed");
    assertTrue(app.contains("engine=eng-"), app);
  }

  @Test
  void processStartFailureKeepsBootstrapIdentityWithoutStartedEvent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    reportEngineFailure(engine, "process start failed");
    awaitLogs(runtime);
    String app = readApp();
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engine event=failed reason=process start failed"), app);
    assertTrue(app.contains("engineType=unknown"), app);
    assertTrue(app.contains("backend=unknown"), app);
    assertTrue(app.contains("onnxProvider=unknown"), app);
    assertFalse(app.contains("engine event=started"), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=failed");
  }

  @Test
  void exitBeforeReadyReusesBootstrapIdentity() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    String identity = EngineObservation.identityFor(engine);
    reportEngineFailure(engine, "exit-before-ready");
    awaitLogs(runtime);
    String app = readApp();
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("engine event=started"), app);
    assertTrue(app.contains("engine event=failed reason=exit-before-ready"), app);
    assertTrue(identity != null && app.contains("engine=" + identity), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=failed");
    assertSameEngineIdentity(app, "event=started", "event=failed");
  }

  @Test
  void bundledLaunchBootstrapRecordsSourceAndAvailableBackend() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    Path enginePath =
        tempDir
            .resolve("engines")
            .resolve("katago")
            .resolve("windows-x64-nvidia")
            .resolve("katago.exe");
    Files.createDirectories(enginePath.getParent());
    Files.writeString(enginePath, "", StandardCharsets.UTF_8);
    engine.setEngineCommand(
        "\""
            + enginePath.toAbsolutePath().normalize()
            + "\" gtp -model kata1.bin.gz -config gtp.cfg");
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    Method loaded = Leelaz.class.getDeclaredMethod("markEngineLoaded");
    loaded.setAccessible(true);
    engine.isLoaded = false;
    loaded.invoke(engine);
    awaitLogs(runtime);
    String app = readApp();
    String identity = EngineObservation.identityFor(engine);
    assertTrue(app.contains("engine event=bootstrap"), app);
    assertTrue(app.contains("source=bundled"), app);
    assertTrue(app.contains("backend=nvidia"), app);
    assertTrue(app.contains("engineType=katago"), app);
    assertEquals(
        KataGoRuntimeHelper.resolveNvidiaBackend(enginePath),
        EngineStartupBootstrap.factsFor(engine.engineCommand, "MAIN_BOARD").backend());
    assertEquals(
        "unknown",
        EngineStartupBootstrap.factsFor(engine.engineCommand, "MAIN_BOARD").onnxProvider());
    assertTrue(app.contains("engine event=ready"), app);
    assertTrue(identity != null && app.contains("engine=" + identity), app);
    assertSameEngineIdentity(app, "event=bootstrap", "event=ready");
    String bootstrapLine = eventLine(app, "event=bootstrap");
    assertTrue(bootstrapLine != null && !bootstrapLine.contains(tempDir.toString()), app);
  }

  @Test
  void consecutiveFailuresAndRetryMintDistinctIdentities() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    reportEngineFailure(engine, "ssh login failed");
    reportEngineFailure(engine, "ssh login failed");
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    awaitLogs(runtime);
    String app = readApp();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("engine=(eng-[0-9a-f-]+)").matcher(app);
    java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    assertTrue(ids.size() >= 3, app);
    assertTrue(app.contains("engine event=failed"), app);
    assertTrue(app.contains("engine event=started"), app);
    String successId = EngineObservation.identityFor(engine);
    assertTrue(successId != null && ids.contains(successId), app);
  }

  @Test
  void gtpSummaryOmitsRawArgumentsAndTraceCapturesThem() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    Leelaz engine = prepareEngine();
    ExactSnapshotRestoreProtocolFixture.install(
        engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommand("play B " + RAW_MOVE);
    awaitLogs(runtime);
    String appBeforeTrace = readApp();
    assertTrue(appBeforeTrace.contains("gtp command=play outcome=sent"), appBeforeTrace);
    assertTrue(appBeforeTrace.contains("gtp command=play outcome=ok"), appBeforeTrace);
    assertFalse(appBeforeTrace.contains(RAW_MOVE), appBeforeTrace);
    assertFalse(Files.exists(tempDir.resolve("logs/engine-trace.log")));

    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    engine.sendCommand("play B " + RAW_MOVE);
    // Raw-stream persistence is a logging contract, independent of analysis ownership. The
    // parser now deliberately quarantines an unowned info line, so exercise the observation seam
    // directly instead of manufacturing an invalid analysis owner in this logging-only test.
    EngineObservation.traceRawStream(
        EngineObservation.identityFor(engine), null, "info move D4 visits 1 " + INFO_CANARY);
    awaitLogs(runtime);
    runtime.stopFullTrace();
    awaitLogs(runtime);

    String app = readApp();
    assertFalse(app.contains(RAW_MOVE), app);
    assertFalse(app.contains(INFO_CANARY), app);
    String trace = Files.readString(tempDir.resolve("logs/engine-trace.log"));
    assertTrue(trace.contains("gtp raw command="), trace);
    assertTrue(trace.contains("play B " + RAW_MOVE), trace);
    assertTrue(trace.contains("gtp raw stream="), trace);
    assertTrue(trace.contains(INFO_CANARY), trace);
    assertTrue(trace.contains("Full Trace session started"), trace);
  }

  @Test
  void engineAndGtpModulesAreIndependent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommand("name");
    awaitLogs(runtime);
    String engineOnly = readApp();
    assertTrue(engineOnly.contains("engine event=queue"), engineOnly);
    assertFalse(engineOnly.contains("gtp command=name"), engineOnly);

    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    engine.sendCommand("version");
    awaitLogs(runtime);
    String gtpOnly = readApp();
    assertTrue(gtpOnly.contains("gtp command=version outcome=sent"), gtpOnly);
  }

  @Test
  void staleResponseKeepsOriginalEngineIdentity() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    String first = EngineObservation.identityFor(engine);
    engine.sendCommand("name");
    String second = EngineObservation.restartInstance(engine, "MAIN_BOARD");
    engine.processCommandResponseLineForTest("=");
    awaitLogs(runtime);

    String app = readApp();
    int sent = app.indexOf("gtp command=name outcome=sent");
    int ok = app.indexOf("gtp command=name outcome=ok");
    assertTrue(sent >= 0, app);
    assertTrue(ok >= 0, app);
    String sentLine = app.substring(Math.max(0, sent - 120), sent + 40);
    String okLine = app.substring(Math.max(0, ok - 120), ok + 40);
    assertTrue(sentLine.contains("engine=" + first), sentLine);
    assertTrue(okLine.contains("engine=" + first), okLine);
    assertFalse(okLine.contains("engine=" + second), okLine);
  }

  @Test
  void protocolCommandIdentityIsReusedWhenPresent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.GTP_SUMMARY)));
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommandWithResponseForTest("kata-set-param numSearchThreads 12", () -> {});
    engine.processCommandResponseLineForTest("=700000000");
    awaitLogs(runtime);

    String app = readApp();
    assertTrue(app.contains("command=700000000"), app);
    assertTrue(app.contains("gtp command=kata-set-param"), app);
    assertFalse(app.contains("numSearchThreads 12"), app);
  }

  @Test
  void stalledTraceQueueDoesNotBlockCommandSend() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(32, 1, 1, 1, 7, 10_000, 1_000));
    installGlobals();
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(true));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    CountDownLatch gate = new CountDownLatch(1);
    Method block =
        LoggingRuntime.class.getDeclaredMethod(
            "blockPersistenceForTests", LogStream.class, CountDownLatch.class);
    block.setAccessible(true);
    block.invoke(runtime, LogStream.ENGINE_TRACE, gate);
    org.slf4j.LoggerFactory.getLogger("lizzie.engine.trace").info("block-one");
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    long began = System.nanoTime();
    engine.sendCommand("play B " + RAW_MOVE);
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
    gate.countDown();
    assertTrue(elapsed < 500, "send blocked for " + elapsed + "ms");
  }

  @Test
  void disabledTraceDoesNotFormatRawCommand() throws Exception {
    LoggingRuntime runtime = startRuntime();
    Leelaz engine = prepareEngine();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.sendCommand("play B " + RAW_MOVE);
    awaitLogs(runtime);
    assertFalse(readApp().contains(RAW_MOVE), readApp());
    assertFalse(Files.exists(tempDir.resolve("logs/engine-trace.log")));
  }

  @Test
  void coalescedAnalysisIsRecordedOnlyWhenTheLastRequestIsWritten() throws Exception {
    LoggingRuntime runtime = startEngineDiagnostics();
    Leelaz engine = prepareEngine();
    Lizzie.board = new Board();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    ExactSnapshotRestoreProtocolFixture.Transport transport =
        ExactSnapshotRestoreProtocolFixture.install(engine, command -> null);
    engine.sendCommand("undo");
    for (int i = 0; i < 128; i++) {
      engine.sendCommand("kata-analyze B " + (10 + i));
    }
    assertEquals(List.of("undo"), transport.commands());
    awaitLogs(runtime);
    assertFalse(readApp().contains("engine event=analysis-started"), readApp());

    String undo = transport.rawCommands().get(0);
    engine.processCommandResponseLineForTest("=" + undo.substring(0, undo.indexOf(' ')));
    assertEquals(List.of("undo", "kata-analyze B 137"), transport.commands());
    awaitLogs(runtime);
    assertEquals(1L, eventCount(readApp(), "analysis-started"), readApp());
  }

  @ParameterizedTest
  @CsvSource({
    "kata-analyze B 10, analysis-started, kata-analyze",
    "kata-set-param numSearchThreads 12, dynamic-parameter, numSearchThreads=12"
  })
  void commandDiagnosticsWaitForFlushAndKeepTheWrittenInstance(
      String command, String event, String detail) throws Exception {
    LoggingRuntime runtime = startEngineDiagnostics();
    Leelaz engine = prepareEngine();
    Lizzie.board = new Board();
    String first = EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    Leelaz replacement = new Leelaz("");
    ExactSnapshotRestoreProtocolFixture.install(
        engine,
        written -> {
          assertEquals(command, written);
          awaitLogs(runtime);
          assertEquals(0L, eventCount(readApp(), event), readApp());
          EngineObservation.restartInstance(engine, "MAIN_BOARD");
          Lizzie.setPrimaryEngine(replacement);
          return null;
        });

    // The response callback adds a protocol ID on the wire; diagnostics still classify payloads.
    engine.sendCommandWithResponseForTest(command, () -> {});
    awaitLogs(runtime);
    String app = readApp();
    assertEquals(1L, eventCount(app, event), app);
    assertNotEquals(first, EngineObservation.identityFor(engine));
    assertEquals(first, engineIdOnEventLine(app, "engine event=" + event), app);
    assertTrue(eventLine(app, "engine event=" + event).contains("command=" + detail), app);
    assertTrue(eventLine(app, "engine event=" + event).contains("purpose=MAIN_BOARD"), app);
    assertSameEngineIdentity(app, "engine event=" + event, "outcome=sent");
  }

  @ParameterizedTest
  @CsvSource({
    "kata-analyze B 10, analysis-started, write",
    "kata-analyze B 10, analysis-started, flush",
    "kata-set-param numSearchThreads 12, dynamic-parameter, write",
    "kata-set-param numSearchThreads 12, dynamic-parameter, flush"
  })
  void failedWritesNeverRecordSuccessfulCommandDiagnostics(
      String command, String event, String failureStage) throws Exception {
    LoggingRuntime runtime = startEngineDiagnostics();
    Leelaz engine = prepareEngine();
    Lizzie.board = new Board();
    EngineObservation.ensureStarted(engine, "MAIN_BOARD");
    engine.installCommandOutputForTest(failingOutput(failureStage));

    engine.sendCommand(command);
    awaitLogs(runtime);
    String app = readApp();
    assertEquals(0L, eventCount(app, event), app);
    assertTrue(app.contains("outcome=failed"), app);
  }

  @ParameterizedTest
  @CsvSource({
    "123 kata-analyze B 10, analysis-started, kata-analyze, false, true",
    "123 kata-analyze B 10, analysis-started, kata-analyze, true, true",
    "kata-set-param numSearchThreads 12, dynamic-parameter, numSearchThreads=12, false, true",
    "kata-set-param numSearchThreads 12, dynamic-parameter, numSearchThreads=12, true, true",
    "123 kata-analyze B 10, analysis-started, kata-analyze, false, false"
  })
  void auxiliaryCommandDiagnosticsFollowTheTransportResult(
      String command, String event, String detail, boolean failFlush, boolean enableAtFlush)
      throws Exception {
    LoggingRuntime runtime = startRuntime();
    if (enableAtFlush) runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    else enableEngineDiagnostics(runtime);
    assertEquals(!enableAtFlush, EngineObservation.engineDiagnosticsEnabled());
    AnalysisEngine engine = allocate(AnalysisEngine.class);
    setField(AnalysisEngine.class, engine, "purpose",
        AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS);
    String first = EngineObservation.ensureStarted(engine, "AUTO_QUICK_ANALYSIS");
    ByteArrayOutputStream output =
        new ByteArrayOutputStream() {
          private boolean commandFlushed;

          @Override
          public void flush() throws IOException {
            // Closing a BufferedOutputStream flushes it again without delivering a new command.
            if (commandFlushed) return;
            commandFlushed = true;
            try {
              awaitLogs(runtime);
              assertEquals(0L, eventCount(readApp(), event), readApp());
              if (enableAtFlush) enableEngineDiagnostics(runtime);
              else runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
              EngineObservation.restartInstance(engine, "AUTO_QUICK_ANALYSIS");
            } catch (Exception error) {
              throw new IOException(error);
            }
            if (failFlush) throw new IOException("controlled auxiliary flush failure");
          }
        };
    Method bind = AnalysisEngine.class.getDeclaredMethod(
        "bindStartedCarrier", Process.class, AnalysisEngineSSHController.class, EngineTransport.class, String.class);
    bind.setAccessible(true);
    bind.invoke(engine, null, null, new QuietTransport(false, output), first);

    try {
      assertEquals(!failFlush, engine.sendCommand(command));
      assertEquals(command + "\n", output.toString(StandardCharsets.UTF_8));
      awaitLogs(runtime);
      String app = readApp();
      assertEquals(!failFlush && enableAtFlush ? 1L : 0L, eventCount(app, event), app);
      if (!failFlush && enableAtFlush) {
        assertEquals(first, engineIdOnEventLine(app, "engine event=" + event), app);
        assertTrue(app.contains("purpose=AUTO_QUICK_ANALYSIS pid=-1 command=" + detail), app);
      }
    } finally {
      engine.shutdown();
      AnalysisEngine.shutdownAllResources().get(5, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "234 kata-analyze B 10, analysis-started, false",
    "234 kata-analyze B 10, analysis-started, true",
    "kata-set-param numSearchThreads 12, dynamic-parameter, false",
    "kata-set-param numSearchThreads 12, dynamic-parameter, true"
  })
  void sharedCommandDiagnosticsFollowAdmissionAndFlush(
      String command, String event, boolean failFlush) throws Exception {
    LoggingRuntime runtime = startEngineDiagnostics();
    AnalysisEngine engine = allocate(AnalysisEngine.class);
    setField(AnalysisEngine.class, engine, "purpose",
        AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS);
    Leelaz foreground = prepareEngine();
    foreground.commandLists.addAll(
        List.of("boardsize", "komi", "clear_board", "play", "set_position",
            "kata-get-rules", "kata-set-rules"));
    setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
    String carrierId = EngineObservation.ensureStarted(foreground, "MAIN_BOARD");
    ByteArrayOutputStream output =
        new ByteArrayOutputStream() {
          @Override
          public void flush() throws IOException {
            if (toString(StandardCharsets.UTF_8).endsWith(command + "\n")) {
              EngineObservation.restartInstance(foreground, "MAIN_BOARD");
              if (failFlush) throw new IOException("controlled shared flush failure");
            }
          }
        };
    foreground.installCommandOutputForTest(output);

    assertFalse(engine.sendCommand(command));
    awaitLogs(runtime);
    assertEquals(0L, eventCount(readApp(), event), readApp());
    try {
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          foreground.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
      String stop = output.toString(StandardCharsets.UTF_8);
      Method dispatch = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
      dispatch.setAccessible(true);
      assertTrue((Boolean) dispatch.invoke(foreground, "=" + stop.substring(0, stop.indexOf(' '))));
      assertTrue((Boolean) dispatch.invoke(foreground, ""));

      assertEquals(!failFlush, engine.sendCommand(command));
      assertTrue(output.toString(StandardCharsets.UTF_8).endsWith(command + "\n"));
      awaitLogs(runtime);
      String app = readApp();
      assertEquals(failFlush ? 0L : 1L, eventCount(app, event), app);
      if (!failFlush) {
        String token = "engine event=" + event;
        assertEquals(carrierId, engineIdOnEventLine(app, token), app);
        assertTrue(eventLine(app, token).contains("purpose=AUTO_QUICK_ANALYSIS "), app);

        ByteArrayOutputStream replacement = new ByteArrayOutputStream();
        foreground.installFreshCommandOutputForTest(replacement);
        assertFalse(engine.sendCommand(command), "The old lease cannot write to a new carrier");
        assertEquals(0, replacement.size());
        awaitLogs(runtime);
        assertEquals(1L, eventCount(readApp(), event), readApp());
      }
    } finally {
      foreground.endExclusiveGtpSession();
    }
  }

  @ParameterizedTest
  @CsvSource({"MAIN_BOARD, false", "PRELOAD, false", "COMPARE, true"})
  void remoteConnectionKeepsItsLaunchPurposeWhenSelectedForPondering(String purpose, boolean forceQuit)
      throws Exception {
    LoggingRuntime runtime = startEngineDiagnostics();
    Leelaz previousCompare = Lizzie.leelaz2;
    Menu previousMenu = LizzieFrame.menu;
    EngineManager previousManager = Lizzie.engineManager;
    RemoteLoggingEngine engine = new RemoteLoggingEngine(false);
    try {
      QuietFrame frame = allocate(QuietFrame.class);
      Lizzie.frame = frame;
      LizzieFrame.menu = allocate(QuietMenu.class);
      Lizzie.engineManager = null;
      engine.preload = purpose.equals("PRELOAD");
      Lizzie.leelaz2 = purpose.equals("COMPARE") ? engine : null;
      Lizzie.setPrimaryEngine(purpose.equals("MAIN_BOARD") ? engine : new Leelaz(""));
      Lizzie.board = new Board();
      engine.startEngine(-1);
      assertTrue(engine.bootstrapFinished.await(5, TimeUnit.SECONDS), "startup command worker");
      awaitLogs(runtime);
      assertEquals(1L, eventCount(readApp(), "process-started"), readApp());
      assertSameEngineIdentity(readApp(), "event=bootstrap", "event=process-started");

      String started = eventLine(readApp(), "engine event=process-started");
      assertTrue(started.contains("purpose=" + purpose + " "), started);
      Lizzie.setPrimaryEngine(engine);
      engine.isLoaded = true;
      engine.isKatago = true;
      setField(engine, "endGetCommandList", true);
      for (int i = 0; i < 3; i++) engine.ponder();
      awaitLogs(runtime);
      assertEquals(3, frame.ponderNotifications, "Foreground resource coordination must still run");
      assertEquals(1L, eventCount(readApp(), "process-started"), readApp());

      // A new launch can publish its observation before replacing the old transport.
      String successor = purpose.equals("PRELOAD")
          ? EngineObservation.restartInstance(engine, "MAIN_BOARD") : null;
      if (forceQuit) {
        assertTrue(engine.forceQuitIfCurrentIncarnation(engine.captureEngineIncarnationFence()));
      } else {
        engine.shutdown();
      }
      awaitLogs(runtime);
      String app = readApp();
      assertEquals(1L, eventCount(app, "process-stopped"), app);
      assertSameEngineIdentity(app, "event=process-started", "event=process-stopped");
      if (successor != null) assertEquals(successor, EngineObservation.identityFor(engine));
      assertTrue(eventLine(app, "event=process-stopped").contains("purpose=" + purpose + " "), app);
    } finally {
      try {
        closeRemoteReaders(engine);
      } finally {
        Lizzie.leelaz2 = previousCompare;
        LizzieFrame.menu = previousMenu;
        Lizzie.engineManager = previousManager;
      }
    }
  }

  @Test
  void eofBeforeReadyRetiresTheBoundLaunchAndPreservesTheNewObservation() throws Exception {
    LoggingRuntime runtime = startEngineDiagnostics();
    RemoteLoggingEngine engine = new RemoteLoggingEngine(false);
    EngineManager previousManager = Lizzie.engineManager;
    try {
      Lizzie.engineManager = null;
      Lizzie.frame = allocate(QuietFrame.class);
      Lizzie.board = new Board();
      Lizzie.setPrimaryEngine(engine);
      engine.startEngine(-1);
      assertTrue(engine.bootstrapFinished.await(5, TimeUnit.SECONDS));
      String first = EngineObservation.identityFor(engine);
      String successor = EngineObservation.restartInstance(engine, "COMPARE");

      engine.transport.close();
      awaitRemoteReaders(engine);
      engine.shutdown();
      awaitLogs(runtime);

      String app = readApp();
      assertEquals(1L, eventCount(app, "process-stopped"), app);
      assertEquals(first, engineIdOnEventLine(app, "event=process-stopped"), app);
      assertEquals(first, engineIdOnEventLine(app, "event=failed reason=exit-before-ready"), app);
      assertEquals(successor, EngineObservation.identityFor(engine));
    } finally {
      try {
        closeRemoteReaders(engine);
      } finally {
        Lizzie.engineManager = previousManager;
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preloadFailCloseOnlyRecordsACarrierThatConnected(boolean connected) throws Exception {
    LoggingRuntime runtime = startEngineDiagnostics();
    RemoteLoggingEngine engine = new RemoteLoggingEngine(!connected);
    engine.transport.failStreams = connected;
    try {
      Lizzie.setPrimaryEngine(engine);
      Leelaz.UpdateEngineStartAttempt attempt = engine.beginUpdateEngineStartAttempt();
      Throwable failure = connected
          ? assertThrows(IllegalStateException.class, () -> attempt.startEngine(-1))
          : assertThrows(IOException.class, () -> attempt.startEngine(-1));
      attempt.failClose(failure);
      assertEquals(0L, engine.transport.closed.getCount(), "failed transport was closed");
      awaitLogs(runtime);
      String app = readApp();
      assertEquals(connected ? 1L : 0L, eventCount(app, "process-started"), app);
      assertEquals(connected ? 1L : 0L, eventCount(app, "process-stopped"), app);
      if (connected) {
        assertSameEngineIdentity(app, "event=process-started", "event=process-stopped");
      } else {
        assertTrue(app.contains("engine event=failed"), app);
      }
    } finally {
      closeRemoteReaders(engine);
    }
  }

  private static void reportEngineFailure(Leelaz engine, String reason) throws Exception {
    Method fail = Leelaz.class.getDeclaredMethod(
        "noteEngineFailed", AnalysisResourceCoordinator.EngineLaunch.class, String.class);
    fail.setAccessible(true);
    fail.invoke(engine, new AnalysisResourceCoordinator.EngineLaunch(
        AnalysisResourceCoordinator.Purpose.MAIN_BOARD, EngineObservation.identityFor(engine)), reason);
  }

  private LoggingRuntime startEngineDiagnostics() throws Exception {
    LoggingRuntime runtime = startRuntime();
    enableEngineDiagnostics(runtime);
    return runtime;
  }

  private static void enableEngineDiagnostics(LoggingRuntime runtime) {
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE, DiagnosticModule.GTP_SUMMARY)));
  }

  private static long eventCount(String app, String event) {
    return app.lines().filter(line -> line.contains("engine event=" + event + " ")).count();
  }

  private static OutputStream failingOutput(String stage) {
    return new OutputStream() {
      @Override
      public void write(int value) throws IOException {
        if (stage.equals("write")) throw new IOException("controlled write failure");
      }

      @Override
      public void flush() throws IOException {
        if (stage.equals("flush")) throw new IOException("controlled flush failure");
      }
    };
  }

  private static void closeRemoteReaders(RemoteLoggingEngine engine) throws Exception {
    try {
      if (engine.started) {
        assertTrue(engine.bootstrapFinished.await(5, TimeUnit.SECONDS), "startup command worker");
      }
    } finally {
      engine.shutdown();
      engine.transport.close();
    }
    awaitRemoteReaders(engine);
  }

  private static void awaitRemoteReaders(RemoteLoggingEngine engine) throws Exception {
    for (String name : List.of("executor", "executorErr")) {
      Field field = Leelaz.class.getDeclaredField(name);
      field.setAccessible(true);
      ScheduledExecutorService executor = (ScheduledExecutorService) field.get(engine);
      if (executor != null) assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), name);
    }
  }

  private static final class RemoteLoggingEngine extends Leelaz {
    private final QuietTransport transport;
    private final CountDownLatch bootstrapFinished = new CountDownLatch(1);

    private RemoteLoggingEngine(boolean failStart) throws IOException {
      super(RemoteComputeConfig.COMMAND_ZHIZI);
      transport = new QuietTransport(failStart);
    }

    @Override
    protected EngineTransport createRemoteTransport() {
      return transport;
    }

    @Override
    void afterEngineGameBootstrapWorkerForTest(
        EngineManager.EngineGameOwnerTransaction transaction) {
      bootstrapFinished.countDown();
    }

    @Override
    public void tryToDignostic(String message, boolean isModal) {}
  }

  private static final class QuietTransport implements EngineTransport {
    private final boolean failStart;
    private boolean failStreams;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final OutputStream output;

    private QuietTransport(boolean failStart) {
      this(failStart, new ByteArrayOutputStream());
    }

    private QuietTransport(boolean failStart, OutputStream output) {
      this.failStart = failStart;
      this.output = output;
    }

    @Override
    public void start() throws IOException {
      if (failStart) throw new IOException("controlled remote connection failure");
    }

    @Override
    public InputStream stdout() {
      if (failStreams) throw new IllegalStateException("controlled stream binding failure");
      return new InputStream() {
        @Override
        public int read() throws IOException {
          try {
            closed.await();
            return -1;
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
          }
        }
      };
    }

    @Override
    public InputStream stderr() {
      return stdout();
    }

    @Override
    public OutputStream stdin() {
      return output;
    }

    @Override
    public boolean isOpen() {
      return !failStart && closed.getCount() != 0;
    }

    @Override
    public String description() {
      return "controlled diagnostic transport";
    }

    @Override
    public void abort() {
      close();
    }

    @Override
    public void close() {
      closed.countDown();
    }
  }

  private static final class QuietFrame extends LizzieFrame {
    private int ponderNotifications;

    @Override
    public void onMainEnginePonder() {
      ponderNotifications++;
    }

    @Override
    public void clearSelectImage() {}
  }

  private static final class QuietMenu extends Menu {
    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {}
  }

  private LoggingRuntime startRuntime() throws Exception {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    installGlobals();
    return runtime;
  }

  private void installGlobals() throws Exception {
    previousConfig = Lizzie.config;
    previousLeelaz = Lizzie.leelaz;
    previousConsole = Lizzie.gtpConsole;
    previousBoard = Lizzie.board;
    previousFrame = Lizzie.frame;
    Lizzie.config = ConfigTestHelper.createForTests(tempDir);
    Lizzie.gtpConsole = null;
    Lizzie.frame = allocate(LizzieFrame.class);
  }

  private Leelaz prepareEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = true;
    engine.isCheckingName = true;
    engine.commandLists.addAll(List.of("stop", "kata-analyze", "name", "version"));
    setField(engine, "endGetCommandList", true);
    engine.installCommandOutputForTest(new ByteArrayOutputStream());
    Lizzie.setPrimaryEngine(engine);
    return engine;
  }

  private String readApp() throws Exception {
    Path file = tempDir.resolve("logs/app.log");
    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  private static void assertSameEngineIdentity(String app, String firstEvent, String secondEvent) {
    String first = engineIdOnEventLine(app, firstEvent);
    String second = engineIdOnEventLine(app, secondEvent);
    assertTrue(
        first != null && first.equals(second),
        firstEvent + "=" + first + " " + secondEvent + "=" + second + "\n" + app);
  }

  private static String eventLine(String app, String eventToken) {
    for (String line : app.split("\n")) {
      if (line.contains(eventToken)) {
        return line;
      }
    }
    return null;
  }

  private static String engineIdOnEventLine(String app, String eventToken) {
    String line = eventLine(app, eventToken);
    if (line == null) {
      return null;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("engine=(eng-[0-9a-f-]+)").matcher(line);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static void awaitLogs(LoggingRuntime runtime) throws Exception {
    Method method =
        LoggingRuntime.class.getDeclaredMethod("awaitIdle", long.class, TimeUnit.class);
    method.setAccessible(true);
    assertTrue(
        (Boolean) method.invoke(runtime, 10L, TimeUnit.SECONDS),
        "All submitted log events must reach disk before asserting engine identities");
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    setField(Leelaz.class, target, name, value);
  }

  private static void setField(Class<?> owner, Object target, String name, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super(null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addLine(String line) {}

    @Override
    public void addErrorLine(String line) {}
  }
}
