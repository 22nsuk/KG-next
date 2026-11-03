package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ManualRulesSelectionTest {
  @TempDir Path directory;
  private static final KataGoRules CHINESE = KataGoRules.parse("chinese").orElseThrow();
  private static final KataGoRules JAPANESE = KataGoRules.parse("japanese").orElseThrow();

  @Test
  void identicalConfirmedRulesPreserveTargetBranchesAndBothCachesWithoutCommands() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      var target = f.history.publishExternalRules(CHINESE.toGtpArgument());
      var commands = f.transport.commands();
      var mirrorCommands = f.mirrorTransport.commands();

      ManualRulesSelection selection = f.apply(CHINESE);

      assertTrue(selection.unchanged());
      assertTrue(selection.isDone());
      assertSame(target, f.history.captureSessionRules());
      assertEquals(commands, f.transport.commands());
      assertEquals(mirrorCommands, f.mirrorTransport.commands());
      assertEquals(0, f.frame.retiredImports);
      f.assertCaches(false, false);
    }
  }

  @Test
  void rejectionPreservesCachesAndResumesWithFreshAnalysisAfterRecovery() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      var target = f.history.publishExternalRules(CHINESE.toGtpArgument());
      f.primary.sendCommand("kata-analyze 10");
      f.reply(f.primary, f.transport, "kata-analyze", "", true);
      set(f.primary, "isPondering", true);
      ManualRulesSelection selection = f.apply(JAPANESE);
      int analysisCount = count(f.transport, "kata-analyze");
      assertFalse(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
      f.primary.parseAnalysisLineForTest("info move Q16 visits 999 winrate 0.6 pv Q16");
      f.primary.sendCommand("kata-analyze 10");
      assertEquals(analysisCount, count(f.transport, "kata-analyze"));
      f.assertCaches(false, false);

      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      f.awaitRecovery();
      assertFalse(selection.isDone());
      f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
      await(selection::isDone);

      assertEquals(EngineRulesResult.Reason.SET_REJECTED, selection.operation().result().reason());
      assertSame(target, f.history.captureSessionRules());
      assertTrue(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
      assertEquals(1, f.primary.resumed);
      assertEquals(analysisCount + 1, count(f.transport, "kata-analyze"));
      f.assertCaches(false, false);
    }
  }

  @Test
  void failedRecoveryReadDoesNotInvalidateAnExplicitlyRejectedChange() throws Exception {
    try (Fixture f = new Fixture()) {
      var target = f.history.publishExternalRules(CHINESE.toGtpArgument());
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      f.awaitRecovery();
      f.reply(f.primary, f.transport, "kata-get-rules", "read failed", false);
      await(selection::isDone);

      assertSame(target, f.history.captureSessionRules());
      assertFalse(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
      f.assertCaches(false, false);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"chinese", "aga", "read-failed", "raw-write", "other-setter"})
  void rejectedRetriesRetainOriginalCacheProvenanceAndContinueDecision(String recovery)
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      var target = f.history.publishExternalRules("unrecognized-rules");
      long generation = Lizzie.capturePrimaryEngineGeneration(f.primary);
      f.history.authorizeAnalysisWithCurrentRules(target, f.primary, generation, f.mirror);
      set(f.primary, "isPondering", true);

      for (int attempt = 0; attempt < 3; attempt++) {
        boolean injectWrite = attempt == 1;
        int[] expectedQueries = {0};
        ManualRulesSelection[] next = {null};
        // Hold settlement until the successor is admitted, preserving the overlap under test.
        assertTrue(EngineManager.runIfNoActiveEngineGameAnalysisOutput(() -> {
          if (injectWrite && recovery.equals("raw-write")) {
            f.primary.sendCommandNoLeelaz2("kata-set-rule ko POSITIONAL");
            f.reply(f.primary, f.transport, "kata-set-rule", "", true);
            assertTrue(f.history.hasPendingManualRulesIntent());
          } else if (injectWrite && recovery.equals("other-setter")) {
            f.primary.applyEngineRulesOperation(KataGoRules.parse("aga").orElseThrow(), 1_000L);
            f.reply(f.primary, f.transport, "kata-set-rules", "", true);
            assertTrue(f.history.hasPendingManualRulesIntent());
          }
          expectedQueries[0] = count(f.transport, "kata-get-rules") + 1;
          next[0] = f.apply(JAPANESE);
        }));
        ManualRulesSelection selection = next[0];
        assertFalse(selection.unchanged());
        assertFalse(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
        f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
        await(() -> count(f.transport, "kata-get-rules") == expectedQueries[0]);
        assertFalse(selection.isDone(), "Retry must overlap the pending recovery query");
      }
      boolean externalWrite = recovery.equals("raw-write") || recovery.equals("other-setter");
      boolean readFailed = recovery.equals("read-failed") || externalWrite;
      f.reply(f.primary, f.transport, "kata-get-rules",
          readFailed ? "read failed" : KataGoRules.parse(recovery).orElseThrow().toGtpArgument(),
          !readFailed);
      for (ManualRulesSelection selection : f.selections) await(selection::isDone);

      boolean unchangedRules = recovery.equals("chinese");
      assertSame(target, f.history.captureSessionRules());
      assertFalse(f.history.hasPendingManualRulesIntent());
      assertEquals(unchangedRules, f.history.permitsAnalysisWithCurrentRules(
          target, f.primary, generation, f.mirror));
      assertEquals(unchangedRules, SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
      assertEquals(unchangedRules ? 1 : 0, count(f.transport, "kata-analyze"));
      f.assertCaches(recovery.equals("aga") || externalWrite, false);
    }
  }

  @Test
  void confirmedChangeInvalidatesBranchesAndConvergesTheCurrentPairOnce() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      var original = f.history.captureSessionRules();
      ManualRulesSelection selection = f.apply(JAPANESE);
      assertFalse(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary),
          "Even an unspecified session target must block analysis during a manual change");
      f.assertCaches(false, false);
      f.confirmChange(JAPANESE);
      await(selection::isDone);

      assertEquals(original.revision() + 1, f.history.captureSessionRules().revision());
      assertEquals(BoardHistoryList.SessionRulesSource.MANUAL_SELECTION,
          f.history.captureSessionRules().source());
      assertEquals(1, f.frame.synchronizedRules);
      assertFalse(f.history.hasPendingManualRulesIntent());
      f.assertCaches(true, true);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"chinese", "aga"})
  void mismatchedReadbackPreservesOnlyAnUnchangedActualRuleset(String actual) throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      var original = f.history.captureSessionRules();
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.confirmChange(KataGoRules.parse(actual).orElseThrow());
      await(selection::isDone);

      assertSame(original, f.history.captureSessionRules());
      assertEquals(0, f.frame.synchronizedRules);
      f.assertCaches(!actual.equals("chinese"), false);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"timeout", "query-failed", "partial-send"})
  void uncertainMutationInvalidatesReuseButKeepsPayloadAndSecondaryCache(String failure)
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.failSetWrite = failure.equals("partial-send");
      ManualRulesSelection selection = f.apply(JAPANESE, failure.equals("timeout") ? 40L : 1_000L);
      if (failure.equals("query-failed")) {
        f.reply(f.primary, f.transport, "kata-set-rules", "", true);
        f.reply(f.primary, f.transport, "kata-get-rules", "query rejected", false);
      }
      await(selection::isDone);

      assertFalse(selection.operation().result().isConfirmed());
      assertEquals(0, f.frame.synchronizedRules);
      assertFalse(f.history.hasPendingManualRulesIntent());
      f.assertCaches(true, false);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"japanese", "unrecognized-rules"})
  void matchingEngineStillAdoptsAnIncompatibleSessionTarget(String declaration) throws Exception {
    try (Fixture f = new Fixture()) {
      var target = f.history.publishExternalRules(declaration);
      ManualRulesSelection selection = f.apply(CHINESE);
      assertFalse(selection.unchanged());
      f.confirmChange(CHINESE);
      await(selection::isDone);

      assertEquals(target.revision() + 1, f.history.captureSessionRules().revision());
      assertTrue(f.history.captureSessionRules().parsedRules().orElseThrow()
          .semanticallyEquals(CHINESE));
      f.assertCaches(true, false);
    }
  }

  @Test
  void occupiedMirrorRejectsBeforeRetiringThePrimaryStream() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.primary.sendCommand("kata-analyze 10");
      f.reply(f.primary, f.transport, "kata-analyze", "", true);
      f.mirror.beginForegroundRestoreForTest();

      assertNull(ManualRulesSelection.apply(f.primary, JAPANESE, 1_000L));
      assertFalse(f.history.hasPendingManualRulesIntent());
      assertEquals(0, count(f.transport, "kata-set-rules"));
      f.primary.parseAnalysisLineForTest("info move Q16 visits 200 winrate 0.6 pv Q16");
      assertEquals(200, f.history.getData().getPlayouts(), "The original stream is still admitted");
      assertFalse(f.history.getData().isChanged);
    }
  }

  @Test
  void newlySelectedReservedMirrorCannotResumeItsOldOrdinaryOutput() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.mirror.sendCommandNoLeelaz2("kata-analyze 10");
      f.reply(f.mirror, f.mirrorTransport, "kata-analyze", "", true);
      f.mirror.parseAnalysisLineForTest("info move D4 visits 150 winrate 0.6 pv D4");
      assertEquals(150, f.history.getData().getPlayouts2());
      Lizzie.config.extraMode = ExtraMode.Normal;
      Lizzie.leelaz2 = null;
      ManualRulesSelection selection = f.apply(JAPANESE);

      try (var reservation = f.mirror.beginEngineModeReservation()) {
        assertNotNull(reservation);
        Lizzie.config.extraMode = ExtraMode.Double_Engine;
        Lizzie.leelaz2 = f.mirror;
        f.confirmChange(JAPANESE);
        await(selection::isDone);
        f.mirror.parseAnalysisLineForTest("info move Q16 visits 999 winrate 0.6 pv Q16");

        assertEquals(150, f.history.getData().getPlayouts2());
        assertEquals("D4", f.history.getData().bestMoves2.get(0).coordinate);
        assertTrue(f.history.getData().isChanged2);
        assertEquals(0, count(f.mirrorTransport, "kata-set-rules"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"history", "primary", "incarnation", "intent"})
  void staleCompletionCannotChangeTheSuccessor(String change) throws Exception {
    try (Fixture f = new Fixture()) {
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", "", true);
      var original = f.history.captureSessionRules();
      BoardHistoryList.ManualRulesIntent successor = null;
      switch (change) {
        case "history" -> Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
        case "primary" -> {
          Leelaz successorPrimary = new Leelaz("");
          Lizzie.setPrimaryEngine(successorPrimary);
          assertTrue(SessionRulesSynchronizer.permitsOrdinaryAnalysis(successorPrimary),
              "An obsolete pending selection must not block its successor");
          Lizzie.setPrimaryEngine(f.primary);
          assertTrue(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
        }
        case "incarnation" -> f.primary.installFreshCommandOutputForTest(new ByteArrayOutputStream());
        case "intent" -> successor = f.history.beginManualRulesIntent();
        default -> throw new AssertionError(change);
      }
      f.reply(f.primary, f.transport, "kata-get-rules", JAPANESE.toGtpArgument(), true);
      await(selection::isDone);
      assertSame(original, f.history.captureSessionRules());
      assertEquals(0, f.frame.synchronizedRules);
      f.assertCaches(false, false);
      if (successor != null) {
        assertTrue(f.history.ownsPendingManualRulesIntent(successor));
        f.history.finishManualRulesIntent(successor);
      }
    }
  }

  @Test
  void supersededIntentCannotWriteASetterQueuedBehindAnotherCommand() throws Exception {
    try (Fixture f = new Fixture()) {
      f.primary.requireResponseBeforeSend = true;
      f.primary.sendCommand("name");
      ManualRulesSelection selection = f.apply(JAPANESE);
      assertEquals(0, count(f.transport, "kata-set-rules"));
      var newerTarget = f.history.publishExternalRules("chinese");

      f.reply(f.primary, f.transport, "name", "KataGo", true);
      await(selection::isDone);

      assertEquals(0, count(f.transport, "kata-set-rules"));
      assertSame(newerTarget, f.history.captureSessionRules());
      f.assertCaches(false, false);
    }
  }

  @Test
  void recoveryQueryCannotReplaceANewerOperation() throws Exception {
    try (Fixture f = new Fixture()) {
      var rejected = f.primary.applyEngineRulesOperation(JAPANESE, 1_000L);
      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      var newer = f.primary.queryEngineRulesOperation(1_000L);
      int queries = count(f.transport, "kata-get-rules");

      assertNull(f.primary.queryEngineRulesAfter(rejected, 1_000L, () -> true));
      assertSame(newer, f.primary.engineRulesOperation());
      assertEquals(queries, count(f.transport, "kata-get-rules"));
      f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void supersededCompletionCannotPublishRestoreConsentOrRetireANewMirror(boolean recovery)
      throws Exception {
    try (Fixture f = new Fixture()) {
      var target = f.history.publishExternalRules("unrecognized-rules");
      f.history.authorizeAnalysisWithCurrentRules(
          target, f.primary, Lizzie.capturePrimaryEngineGeneration(f.primary), null);
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", recovery ? "rejected" : "", !recovery);
      if (recovery) f.awaitRecovery();
      f.frame.refreshEntered = new CountDownLatch(1);
      f.frame.releaseRefresh = new CountDownLatch(1);
      f.frame.gateNextRefresh = true;
      FutureTask<Void> readback = new FutureTask<>(() -> {
        f.reply(f.primary, f.transport, "kata-get-rules",
            (recovery ? CHINESE : JAPANESE).toGtpArgument(), true);
        return null;
      });
      Thread reader = new Thread(readback, "manual-rules-gated-readback");
      reader.start();
      try {
        assertTrue(f.frame.refreshEntered.await(3, TimeUnit.SECONDS));
        assertTrue(f.history.hasPendingManualRulesIntent());
        assertFalse(selection.isDone());
        f.assertCaches(false, false);
        f.addMirror(CHINESE);
        long mirrorGeneration = outputGeneration(f.mirror);
        var newer = f.primary.queryEngineRulesOperation(1_000L);
        f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
        f.frame.releaseRefresh.countDown();
        readback.get(3, TimeUnit.SECONDS);
        await(selection::isDone);

        assertSame(newer, f.primary.engineRulesOperation());
        assertSame(target, f.history.captureSessionRules());
        assertFalse(f.history.permitsAnalysisWithCurrentRules(
            target, f.primary, Lizzie.capturePrimaryEngineGeneration(f.primary), null));
        assertEquals(0, f.frame.synchronizedRules);
        assertEquals(mirrorGeneration, outputGeneration(f.mirror));
        f.assertCaches(!recovery, false);
      } finally {
        f.frame.releaseRefresh.countDown();
        reader.join(3_000L);
        assertFalse(reader.isAlive());
      }
    }
  }

  @Test
  void rejectionRestoresAnExistingContinueDecisionOnlyAfterMatchingReadback() throws Exception {
    try (Fixture f = new Fixture()) {
      var target = f.history.publishExternalRules("unrecognized-rules");
      long generation = Lizzie.capturePrimaryEngineGeneration(f.primary);
      f.history.authorizeAnalysisWithCurrentRules(target, f.primary, generation, null);
      ManualRulesSelection selection = f.apply(JAPANESE);
      assertFalse(f.history.permitsAnalysisWithCurrentRules(target, f.primary, generation, null));
      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      f.awaitRecovery();
      assertFalse(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
      f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
      await(selection::isDone);

      assertTrue(f.history.permitsAnalysisWithCurrentRules(target, f.primary, generation, null));
      assertTrue(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
      f.assertCaches(false, false);
    }
  }

  private static long outputGeneration(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("analysisOutputGeneration");
    field.setAccessible(true);
    return ((java.util.concurrent.atomic.AtomicLong) field.get(engine)).get();
  }

  @ParameterizedTest
  @CsvSource({"false,confirmed", "false,rejected", "false,rejected-retry",
      "true,rejected", "true,rejected-retry"})
  void overlappingApplyCannotMistakeAPreviousConfirmationForCacheProvenance(
      boolean rejectFirst, String outcome)
      throws Exception {
    try (Fixture f = new Fixture()) {
      var original = f.history.captureSessionRules();
      ManualRulesSelection first = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", rejectFirst ? "rejected" : "", !rejectFirst);
      if (rejectFirst) f.awaitRecovery();
      f.frame.refreshEntered = new CountDownLatch(1);
      f.frame.releaseRefresh = new CountDownLatch(1);
      f.frame.gateNextRefresh = true;
      FutureTask<Void> readback = new FutureTask<>(() -> {
        f.reply(f.primary, f.transport, "kata-get-rules", JAPANESE.toGtpArgument(), true);
        return null;
      });
      Thread reader = new Thread(readback, "manual-rules-overlapping-confirmation");
      reader.start();
      try {
        assertTrue(f.frame.refreshEntered.await(3, TimeUnit.SECONDS));
        assertEquals(!rejectFirst, first.operation().snapshot().isConfirmed());
        assertFalse(first.isDone());
        f.assertCaches(false, false);

        ManualRulesSelection second = f.apply(JAPANESE);
        assertFalse(second.unchanged());
        boolean rejectSecond = !outcome.equals("confirmed");
        if (rejectSecond) {
          f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
          await(() -> count(f.transport, "kata-get-rules") == 3);
          if (outcome.equals("rejected-retry")) {
            second = f.apply(JAPANESE);
            f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
            await(() -> count(f.transport, "kata-get-rules") == 4);
          }
          f.reply(f.primary, f.transport, "kata-get-rules", "read failed", false);
        } else {
          f.confirmChange(JAPANESE);
        }
        await(second::isDone);
        f.frame.releaseRefresh.countDown();
        readback.get(3, TimeUnit.SECONDS);
        await(first::isDone);

        f.assertCaches(true, false);
        assertEquals(rejectSecond ? 0 : 1, f.frame.synchronizedRules);
        assertEquals(original.revision() + (rejectSecond ? 0 : 1),
            f.history.captureSessionRules().revision());
      } finally {
        f.frame.releaseRefresh.countDown();
        reader.join(3_000L);
        assertFalse(reader.isAlive());
      }
    }
  }

  @Test
  void rawRulesWriteInvalidatesTheObservationUsedForNoOpDetection() throws Exception {
    try (Fixture f = new Fixture()) {
      assertNotNull(f.primary.currentConfirmedRules());
      f.primary.sendCommand("kata-set-rule ko POSITIONAL");
      assertNull(f.primary.currentConfirmedRules());
      f.reply(f.primary, f.transport, "kata-set-rule", "", true);
      ManualRulesSelection selection = f.apply(CHINESE);
      assertFalse(selection.unchanged());
      f.confirmChange(CHINESE);
      await(selection::isDone);
      f.assertCaches(true, false);
    }
  }

  private static int count(ExactSnapshotRestoreProtocolFixture.Transport transport, String command) {
    return (int) transport.commands().stream().filter(line -> line.startsWith(command)).count();
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5L);
    assertTrue(condition.getAsBoolean(), "Timed out waiting for rules completion");
  }

  private final class Fixture implements AutoCloseable {
    final Config previousConfig = Lizzie.config;
    final Leelaz previousPrimary = Lizzie.leelaz;
    final Leelaz previousSecondary = Lizzie.leelaz2;
    final Board previousBoard = Lizzie.board;
    final LizzieFrame previousFrame = Lizzie.frame;
    final GtpConsolePane previousConsole = Lizzie.gtpConsole;
    final BottomToolbar previousToolbar = LizzieFrame.toolbar;
    final EngineManager previousManager = Lizzie.engineManager;
    final TestEngine primary;
    Leelaz mirror;
    final HeadlessFrame frame;
    final BoardHistoryList history;
    final List<BoardData> cached = new ArrayList<>();
    final List<ManualRulesSelection> selections = new ArrayList<>();
    final ExactSnapshotRestoreProtocolFixture.Transport transport;
    ExactSnapshotRestoreProtocolFixture.Transport mirrorTransport;
    boolean failSetWrite;

    Fixture() throws Exception {
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.config = ConfigTestHelper.createForTests(directory);
      Lizzie.config.enableLizzieCache = true;
      Lizzie.config.analyzeUpdateIntervalCentisec = 10;
      Lizzie.gtpConsole = null;
      frame = allocate(HeadlessFrame.class);
      frame.priorityMoveCoords = new ArrayList<>();
      Lizzie.frame = frame;
      LizzieFrame.toolbar = allocate(BottomToolbar.class);
      primary = new TestEngine();
      initialize(primary);
      transport = ExactSnapshotRestoreProtocolFixture.install(primary, command -> {
        if (failSetWrite && command.startsWith("kata-set-rules")) {
          throw new IOException("Failure after the command bytes reached the transport");
        }
        return null;
      });
      Lizzie.setPrimaryEngine(primary);
      Lizzie.leelaz2 = null;
      Lizzie.board = new Board();
      history = Lizzie.board.getHistory();
      cached.add(history.getData());
      cached.add(history.getStart().addAtLast(BoardData.empty(19, 19)).getData());
      cached.add(history.getStart().addAtLast(BoardData.empty(19, 19)).getData());
      for (BoardData data : cached) {
        MoveData move = new MoveData();
        move.coordinate = "D4";
        move.playouts = 100;
        move.winrate = 55;
        move.variation = List.of("D4");
        data.bestMoves = List.of(move);
        data.bestMoves2 = List.of(move);
        data.setPlayouts(100);
        data.setPlayouts2(100);
        data.isChanged = false;
        data.isChanged2 = false;
      }
      primary.queryEngineRulesOperation(1_000L);
      reply(primary, transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
      assertNotNull(primary.currentConfirmedRules());
    }

    void addMirror(KataGoRules rules) throws Exception {
      mirror = new Leelaz("");
      initialize(mirror);
      mirrorTransport = ExactSnapshotRestoreProtocolFixture.install(mirror, command -> null);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      Lizzie.leelaz2 = mirror;
      mirror.queryEngineRulesOperation(1_000L);
      reply(mirror, mirrorTransport, "kata-get-rules", rules.toGtpArgument(), true);
    }

    ManualRulesSelection apply(KataGoRules rules) {
      return apply(rules, 1_000L);
    }

    ManualRulesSelection apply(KataGoRules rules, long timeout) {
      ManualRulesSelection selection = ManualRulesSelection.apply(primary, rules, timeout);
      assertNotNull(selection);
      selections.add(selection);
      return selection;
    }

    void confirmChange(KataGoRules rules) {
      reply(primary, transport, "kata-set-rules", "", true);
      reply(primary, transport, "kata-get-rules", rules.toGtpArgument(), true);
    }

    void awaitRecovery() throws Exception {
      await(() -> count(transport, "kata-get-rules") == 2);
    }

    void reply(Leelaz engine, ExactSnapshotRestoreProtocolFixture.Transport output,
        String command, String payload, boolean success) {
      List<String> lines = output.rawCommands();
      for (int index = lines.size() - 1; index >= 0; index--) {
        String line = lines.get(index);
        int split = line.indexOf(' ');
        boolean numbered = split > 0 && Character.isDigit(line.charAt(0));
        if ((numbered ? line.substring(split + 1) : line).startsWith(command)) {
          engine.processCommandResponseLineForTest((success ? "=" : "?")
              + (numbered ? line.substring(0, split) : "") + " " + payload);
          return;
        }
      }
      throw new AssertionError("Missing " + command + " in " + lines);
    }

    void assertCaches(boolean primaryChanged, boolean secondaryChanged) {
      for (BoardData data : cached) {
        assertEquals(100, data.getPlayouts());
        assertEquals(100, data.getPlayouts2());
        assertEquals("D4", data.bestMoves.get(0).coordinate);
        assertEquals("D4", data.bestMoves2.get(0).coordinate);
        assertEquals(primaryChanged, data.isChanged);
        assertEquals(secondaryChanged, data.isChanged2);
        assertEquals(!primaryChanged, data.hasReusablePrimaryAnalysis(100, false));
        assertEquals(primaryChanged ? 1L : 0L, data.primaryAnalysisRevision());
      }
    }

    @Override public void close() throws Exception {
      try {
        for (ManualRulesSelection selection : selections) await(selection::isDone);
        assertNull(primary.pendingManualRulesSelection.get());
      } finally {
        EngineManager.resetEngineGameTransactionStateForTest();
        Lizzie.config = previousConfig;
        Lizzie.setPrimaryEngine(previousPrimary);
        Lizzie.leelaz2 = previousSecondary;
        Lizzie.board = previousBoard;
        Lizzie.frame = previousFrame;
        Lizzie.gtpConsole = previousConsole;
        LizzieFrame.toolbar = previousToolbar;
        Lizzie.engineManager = previousManager;
      }
    }
  }

  private static void initialize(Leelaz engine) {
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    engine.started = true;
    engine.isLoaded = true;
    engine.isKatago = true;
    engine.advertiseCommandsForTest(List.of("kata-set-rules", "kata-get-rules", "kata-analyze"));
  }

  private static final class TestEngine extends Leelaz {
    int resumed;
    TestEngine() throws IOException { super(""); }
    @Override public void ponder() {
      resumed++;
      sendCommand("kata-analyze 10");
    }
  }

  private static final class HeadlessFrame extends LizzieFrame {
    int retiredImports;
    int synchronizedRules;
    volatile boolean gateNextRefresh;
    CountDownLatch refreshEntered;
    CountDownLatch releaseRefresh;
    @Override public void refresh() {
      if (!gateNextRefresh) return;
      gateNextRefresh = false;
      refreshEntered.countDown();
      try {
        if (!releaseRefresh.await(4, TimeUnit.SECONDS)) throw new AssertionError("Readback gate timed out");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    @Override public void requestAnalysisRefresh() {}
    @Override public void requestAnalysisTitleUpdate() {}
    @Override public BoardHistoryNode getDisplayNode() {
      return Lizzie.board.getHistory().getCurrentHistoryNode();
    }
    @Override public void retireImportedRulesForManualSelection(
        BoardHistoryList history, BoardHistoryList.SessionRulesTarget target) {
      retiredImports++;
      super.retireImportedRulesForManualSelection(history, target);
    }
    @Override public void synchronizeManualRulesAfterSelection(
        BoardHistoryList history, BoardHistoryList.SessionRulesTarget target,
        BoardHistoryList.ManualRulesIntent intent, Leelaz engine, long generation) {
      assertFalse(history.hasPendingManualRulesIntent());
      assertTrue(history.getData().isChanged || engine.activeComparisonEngine() != null);
      synchronizedRules++;
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }
}
