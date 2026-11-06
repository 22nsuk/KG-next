package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.KataGoAnalysisWebSocketTransportTest.TransportHarness;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ManualRulesSelectionTest {
  @TempDir Path directory;
  private static final KataGoRules CHINESE = KataGoRules.parse("chinese").orElseThrow();
  private static final KataGoRules JAPANESE = KataGoRules.parse("japanese").orElseThrow();

  @Test
  void identicalPresetDoesNotWriteCommandsPreferencesOrInvalidateAnalysis() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.history.getGameInfo().setKomiNoMenu(7.5);
      Lizzie.config.autoLoadKataRules = true;
      Lizzie.config.kataRules = JAPANESE.toGtpArgument();
      Path config = Path.of(Lizzie.config.getConfigFilePath());
      Files.writeString(config,
          "{\"ui\":{\"auto-load-kata-rules\":true,\"kata-rules\":\"japanese\"}}");
      String saved = Files.readString(config);
      var target = f.history.captureSessionRules();
      var commands = f.transport.commands();

      ManualRulesSelection selection = f.apply(RulePreset.CHINESE, 1_000L);

      assertTrue(selection.unchanged());
      assertTrue(selection.applied());
      assertSame(target, f.history.captureSessionRules());
      assertEquals(commands, f.transport.commands());
      assertEquals(saved, Files.readString(config));
      assertEquals(JAPANESE.toGtpArgument(), Lizzie.config.kataRules);
      f.assertCaches(false, false);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void komiOnlyPresetWaitsForRulesThenRestoresBothEnginesBeforeAllowingAnalysis(boolean comparison)
      throws Exception {
    try (Fixture f = new Fixture()) {
      if (comparison) f.addMirror(CHINESE);
      f.history.getGameInfo().setKomiNoMenu(7.5);
      for (BoardData data : f.cached) data.komi = 7.5;
      ManualRulesSelection selection = f.apply(RulePreset.CHINESE_7, 1_000L);
      assertFalse(selection.unchanged());
      assertEquals(7.5, f.history.getGameInfo().getKomi());
      f.assertCaches(false, false);
      assertEquals(0, count(f.transport, "komi"));

      f.confirmChange(CHINESE);
      await(selection::isDone);

      assertTrue(selection.applied());
      assertEquals(7.0, f.history.getGameInfo().getKomi());
      assertTrue(f.history.getGameInfo().changedKomi);
      assertTrue(Lizzie.board.isEngineAlignmentPending());
      assertFalse(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
      f.primary.sendCommand("kata-analyze 10");
      assertEquals(0, count(f.transport, "kata-analyze"));
      if (comparison) assertEquals(0, count(f.mirrorTransport, "kata-analyze"));
      f.assertCaches(true, true);
      for (BoardData data : f.cached) assertEquals(7.5, data.komi);

      f.restoreResponses = true;
      var frozen = Lizzie.board.freezeCurrentPositionForPrimaryEngineExactRestore().orElseThrow();
      assertTrue(frozen.execute());
      assertTrue(frozen.completeEngineAlignment());
      assertEquals(comparison ? 2 : 1, f.restoredSgfs.size());
      for (String sgf : f.restoredSgfs) assertTrue(sgf.contains("KM[7.0]"), sgf);
      assertFalse(Lizzie.board.isEngineAlignmentPending());
      assertTrue(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
      f.primary.sendCommand("kata-analyze 10");
      assertEquals(1, count(f.transport, "kata-analyze"));
      if (comparison) assertEquals(1, count(f.mirrorTransport, "kata-analyze"));
      f.assertCaches(true, true);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"rejected", "recovery-failed", "mismatch", "timeout", "query-failed"})
  void failedPresetNeverChangesKomiAndKeepsAnalysisPayload(String failure) throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.history.getGameInfo().setKomiNoMenu(7.5);
      var target = f.history.captureSessionRules();
      ManualRulesSelection selection = f.apply(
          RulePreset.JAPANESE, failure.equals("timeout") ? 40L : 1_000L);
      switch (failure) {
        case "rejected", "recovery-failed" -> {
          f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
          f.awaitRecovery();
          f.reply(f.primary, f.transport, "kata-get-rules",
              failure.equals("rejected") ? CHINESE.toGtpArgument() : "rejected",
              failure.equals("rejected"));
        }
        case "mismatch" -> f.confirmChange(CHINESE);
        case "query-failed" -> {
          f.reply(f.primary, f.transport, "kata-set-rules", "", true);
          f.reply(f.primary, f.transport, "kata-get-rules", "rejected", false);
        }
        default -> { }
      }
      await(selection::isDone);
      assertFalse(selection.applied());
      assertEquals(7.5, f.history.getGameInfo().getKomi());
      assertFalse(f.history.getGameInfo().changedKomi);
      assertSame(target, f.history.captureSessionRules());
      assertEquals(0, f.frame.synchronizedRules);
      assertFalse(Lizzie.board.isEngineAlignmentPending());
      f.assertCaches(failure.equals("timeout") || failure.equals("query-failed"), false);
    }
  }

  @Test
  void handicapPresetChangesRulesWithoutChangingKomiOrDefaults() throws Exception {
    try (Fixture f = new Fixture()) {
      f.history.getGameInfo().setHandicap(3);
      f.history.getGameInfo().setKomiNoMenu(0.5);
      double defaultKomi = GameInfo.DEFAULT_KOMI;
      ManualRulesSelection selection = f.apply(RulePreset.JAPANESE, 1_000L);
      f.confirmChange(JAPANESE);
      await(selection::isDone);
      assertTrue(selection.applied());
      assertEquals(0.5, f.history.getGameInfo().getKomi());
      assertEquals(defaultKomi, GameInfo.DEFAULT_KOMI);
      assertFalse(f.history.getGameInfo().changedKomi);
      assertEquals(0, count(f.transport, "komi"));
      f.assertCaches(true, false);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"komi", "komi-aba", "handicap", "handicap-aba", "game-info", "history", "engine"})
  void pendingPresetCannotOverwriteAnotherEditOrReplacement(String change) throws Exception {
    try (Fixture f = new Fixture()) {
      f.history.getGameInfo().setKomiNoMenu(7.5);
      ManualRulesSelection selection = f.apply(RulePreset.JAPANESE, 1_000L);
      switch (change) {
        case "komi" -> f.history.getGameInfo().setKomiNoMenu(5.5);
        case "komi-aba" -> {
          f.history.getGameInfo().setKomiNoMenu(7.0);
          f.history.getGameInfo().setKomiNoMenu(7.5);
        }
        case "handicap" -> f.history.getGameInfo().setHandicap(2);
        case "handicap-aba" -> {
          f.history.getGameInfo().setHandicap(2);
          f.history.getGameInfo().setHandicap(0);
        }
        case "game-info" -> f.history.setGameInfo(new GameInfo());
        case "history" -> Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
        case "engine" -> Lizzie.setPrimaryEngine(new Leelaz(""));
        default -> throw new AssertionError(change);
      }
      if (!change.equals("history") && !change.equals("engine")) {
        assertFalse(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary),
            "Metadata edits cannot release the pending rules output fence early");
      }
      f.confirmChange(JAPANESE);
      await(selection::isDone);
      assertFalse(selection.applied());
      assertFalse(selection.isLatestSelection());
      assertEquals(change.equals("komi") ? 5.5 : 7.5, f.history.getGameInfo().getKomi());
      assertEquals(0, f.frame.synchronizedRules);
      assertFalse(Lizzie.board.isEngineAlignmentPending());
    }
  }

  @Test
  void presetSettlementReleasesBoardWhileAnOutputOwnerNeedsIt() throws Exception {
    try (Fixture f = new Fixture()) {
      f.history.getGameInfo().setKomiNoMenu(7.5);
      ManualRulesSelection selection = f.apply(RulePreset.CHINESE_7, 1_000L);
      CountDownLatch outputHeld = new CountDownLatch(1);
      CountDownLatch acquireBoard = new CountDownLatch(1);
      FutureTask<Boolean> owner = new FutureTask<>(() ->
          EngineManager.runIfNoActiveEngineGameAnalysisOutput(() -> {
            outputHeld.countDown();
            try {
              assertTrue(acquireBoard.await(3, TimeUnit.SECONDS));
              synchronized (Lizzie.board) {
                assertEquals(7.5, f.history.getGameInfo().getKomi());
              }
            } catch (InterruptedException interrupted) {
              throw new AssertionError(interrupted);
            }
          }));
      Thread output = new Thread(owner, "preset-output-owner");
      output.start();
      try {
        assertTrue(outputHeld.await(3, TimeUnit.SECONDS));
        f.confirmChange(CHINESE);
        assertTrue(selection.operation().isDone());
        assertFalse(selection.isDone());
        acquireBoard.countDown();
        assertTrue(owner.get(3, TimeUnit.SECONDS));
        await(selection::isDone);
        assertTrue(selection.applied());
        assertEquals(7.0, f.history.getGameInfo().getKomi());
      } finally {
        acquireBoard.countDown();
        output.join(3_000L);
        assertFalse(output.isAlive());
      }
    }
  }

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

  @ParameterizedTest
  @CsvSource({"false,kata-set-rules japanese", "true,kata-set-rules japanese",
      "false,kata-set-rule ko SIMPLE", "true,kata-set-rule ko SIMPLE",
      "false,kgs-rules japanese", "true,kgs-rules japanese",
      "false,loadsgf external.sgf", "true,loadsgf external.sgf"})
  void rawRulesWritesInvalidateCachesBeforeTheFirstManualSelection(boolean mirrored, String command)
      throws Exception {
    try (Fixture f = new Fixture()) {
      if (mirrored) f.addMirror(CHINESE);
      assertTrue(f.primary.sendRawConsoleCommand(command));
      f.reply(f.primary, f.transport, command, "", true);
      if (mirrored) f.reply(f.mirror, f.mirrorTransport, command, "", true);
      int primaryQueries = count(f.transport, "kata-get-rules");
      var query = f.primary.queryEngineRulesOperation(1_000L);
      await(() -> count(f.transport, "kata-get-rules") == primaryQueries + 1);
      f.reply(f.primary, f.transport, "kata-get-rules", JAPANESE.toGtpArgument(), true);
      assertTrue(query.await(1_000L).isConfirmed());
      if (mirrored) {
        int mirrorQueries = count(f.mirrorTransport, "kata-get-rules");
        var mirrorQuery = f.mirror.queryEngineRulesOperation(1_000L);
        await(() -> count(f.mirrorTransport, "kata-get-rules") == mirrorQueries + 1);
        f.reply(f.mirror, f.mirrorTransport, "kata-get-rules", JAPANESE.toGtpArgument(), true);
        assertTrue(mirrorQuery.await(1_000L).isConfirmed());
      }
      assertTrue(f.apply(JAPANESE).unchanged());
      f.assertCaches(true, mirrored);

      // The actual ordinary-analysis consumer must replace a deeper result from the old rules.
      f.primary.sendCommandNoLeelaz2("kata-analyze 10");
      f.reply(f.primary, f.transport, "kata-analyze", "", true);
      f.primary.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      assertEquals(10, f.history.getData().getPlayouts());
      assertEquals("Q16", f.history.getData().bestMoves.get(0).coordinate);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "123 kata-set-rules japanese", "123 kata-set-rule ko SIMPLE",
      "123 kgs-rules japanese", "123 loadsgf external.sgf",
      "  00123\tkata-set-rules\tjapanese # comment", "123kata-set-rules japanese",
      "123 ka\rta-set-rules japanese", "123 kata-set-rules# missing argument",
      "123 ka\u0001ta-set-rules japanese", "123 ka\u007fta-set-rules japanese",
      "123 한kata-set-rules japanese"
  })
  void numberedRawRulesWritesInvalidateBothSlotsAndFenceReadbacks(String command) throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      var query = f.primary.queryEngineRulesOperation(30_000L);
      Object settings = f.primary.analysisSettingsToken();
      assertTrue(f.primary.sendRawConsoleCommand(command));
      assertTrue(f.transport.rawCommands().contains(command.trim()), "Preserve the wire command");
      assertTrue(f.mirrorTransport.rawCommands().contains(command.trim()));
      f.assertCaches(true, true);
      assertNull(f.primary.currentConfirmedRules());
      assertNull(f.mirror.currentConfirmedRules());
      assertEquals("", f.primary.captureAnalysisProvenance().rules());
      assertNotSame(settings, f.primary.analysisSettingsToken());
      // This query was written before the raw mutation, so its late result cannot confirm rules.
      f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
      assertFalse(query.await(1_000L).isConfirmed());
      f.primary.processCommandResponseLineForTest("?123 rejected");
      f.mirror.processCommandResponseLineForTest("?123 rejected");
      f.assertCaches(true, true);
      var freshPrimary = f.primary.queryEngineRulesOperation(1_000L);
      f.reply(f.primary, f.transport, "kata-get-rules", JAPANESE.toGtpArgument(), true);
      assertTrue(freshPrimary.await(1_000L).isConfirmed());
      var freshMirror = f.mirror.queryEngineRulesOperation(1_000L);
      f.reply(f.mirror, f.mirrorTransport, "kata-get-rules", JAPANESE.toGtpArgument(), true);
      assertTrue(freshMirror.await(1_000L).isConfirmed());
      assertTrue(f.apply(JAPANESE).unchanged());
      f.assertCaches(true, true);
    }
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {"true|123 name # kata-set-rules japanese",
      "false|# 123 kata-set-rules japanese", "true|123 kata-get-rules",
      "true|123 kata-set-rules-extra japanese", "true|123 456 kata-set-rules japanese"})
  void nonMutatingRawCommandsDoNotInvalidateRules(boolean accepted, String command) throws Exception {
    try (Fixture f = new Fixture()) {
      Object settings = f.primary.analysisSettingsToken();
      assertEquals(accepted, f.primary.sendRawConsoleCommand(command));
      f.assertCaches(false, false);
      assertNotNull(f.primary.currentConfirmedRules());
      assertSame(settings, f.primary.analysisSettingsToken());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"kata-set-rules japanese", "123 kata-set-rules japanese"})
  void rawRulesBoundaryCoversQueuedNodesAndRetiresOnlyThePreviousStream(String command) throws Exception {
    try (Fixture f = new Fixture()) {
      assertTrue(f.apply(CHINESE).unchanged());
      f.primary.sendCommandNoLeelaz2("kata-analyze 10");
      f.reply(f.primary, f.transport, "kata-analyze", "", true);
      f.primary.requireResponseBeforeSend = true;
      f.primary.sendCommandNoLeelaz2("name");
      assertTrue(f.primary.sendRawConsoleCommand(command));
      f.primary.sendCommandNoLeelaz2("kata-analyze 10");
      assertTrue(f.primary.hasPendingRulesWrite());
      f.assertCaches(false, false);
      BoardData added = BoardData.empty(19, 19);
      added.bestMoves = f.history.getData().bestMoves;
      added.setPlayouts(100);
      added.isChanged = false;
      synchronized (f.history) { f.history.getStart().addAtLast(added); }

      f.reply(f.primary, f.transport, "name", "KataGo", true);
      assertTrue(added.isChanged, "Capture at enqueue would miss this node");
      f.assertCaches(true, false);
      f.primary.parseAnalysisLineForTest("info move Q16 visits 1000 winrate 0.6 pv Q16");
      f.assertCaches(true, false);
      f.primary.processCommandResponseLineForTest(command.startsWith("123") ? "=123" : "=");
      assertEquals(2, count(f.transport, "kata-analyze"), "Post-write analysis must still dispatch");
      f.reply(f.primary, f.transport, "kata-analyze", "", true);
      f.primary.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      assertEquals(10, f.history.getData().getPlayouts());
      assertFalse(f.history.getData().isChanged);

      // A later raw write must invalidate refilled data even after a one-shot manual guard fired.
      assertTrue(f.primary.sendRawConsoleCommand("kgs-rules chinese"));
      assertTrue(f.history.getData().isChanged);
      assertFalse(f.history.getData().hasReusablePrimaryAnalysis(10, false));
      assertEquals(2, f.history.getData().primaryAnalysisRevision());
    }
  }

  @Test
  void historyReplacementWhileRawWriterWaitsInvalidatesTheLiveTree() throws Exception {
    try (Fixture f = new Fixture()) {
      BoardData replacementData = BoardData.empty(19, 19);
      replacementData.bestMoves = f.history.getData().bestMoves;
      replacementData.setPlayouts(100);
      replacementData.isChanged = false;
      BoardHistoryList replacement = new BoardHistoryList(replacementData);
      FutureTask<Boolean> write = new FutureTask<>(() ->
          f.primary.sendRawConsoleCommand("kata-set-rules japanese"));
      Thread writer = new Thread(write, "raw-rules-history-replacement");
      try {
        synchronized (f.history) {
          writer.start();
          await(() -> {
            var info = ManagementFactory.getThreadMXBean().getThreadInfo(writer.getId());
            return info != null && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockInfo() != null
                && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(f.history);
          });
          Lizzie.board.setHistory(replacement);
        }
        assertTrue(write.get(3, TimeUnit.SECONDS));
        assertTrue(replacementData.isChanged);
        f.assertCaches(false, false);
      } finally {
        writer.join(3_000L);
        assertFalse(writer.isAlive());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
      "false,kata-set-rules japanese,=", "false,123 kata-set-rules japanese,=123",
      "false,kata-set-rule ko SIMPLE,=", "false,123 kata-set-rule ko SIMPLE,=123",
      "false,kgs-rules japanese,=", "false,123 kgs-rules japanese,=123",
      "false,loadsgf external.sgf,=", "false,123 loadsgf external.sgf,=123",
      "true,kata-set-rules japanese,=", "true,123 kata-set-rule ko SIMPLE,=123",
      "true,kgs-rules japanese,=", "true,123 loadsgf external.sgf,=123",
      "false,kata-set-rules japanese,? rejected", "true,123 kata-set-rule ko SIMPLE,?123 rejected",
      "false,kgs-rules japanese,? rejected"
  })
  void asynchronousRawRulesWaitForTheirResponseBeforeAcceptingSuccessorAnalysis(
      boolean mirrored, String command, String response) throws Exception {
    try (Fixture f = new Fixture()) {
      if (mirrored) f.addMirror(CHINESE);
      Leelaz engine = mirrored ? f.mirror : f.primary;
      var transport = mirrored ? f.mirrorTransport : f.transport;
      assertFalse(engine.requireResponseBeforeSend, "Exercise the default KataGo dispatch mode");
      assertTrue(SessionRulesSynchronizer.permitsOrdinaryAnalysis(engine));
      engine.sendCommandNoLeelaz2("kata-analyze 10");
      f.reply(engine, transport, "kata-analyze", "", true);
      if (mirrored) engine.sendCommandNoLeelaz2(command);
      else assertTrue(engine.sendRawConsoleCommand(command));
      engine.sendCommandNoLeelaz2("kata-analyze 10");

      // Output already in the pipe belongs to the retired analysis until the rules reply.
      engine.parseAnalysisLineForTest("info move Q16 visits 1000 winrate 0.6 pv Q16");
      f.assertCaches(!mirrored, mirrored);
      assertEquals(1, count(transport, "kata-analyze"));
      engine.processCommandResponseLineForTest("=9999 unrelated response");
      assertEquals(1, count(transport, "kata-analyze"), "Only the matching reply drains the boundary");

      if (command.startsWith("loadsgf ")) {
        // Position synchronization assigns its own response ID to an unnumbered loadsgf.
        f.reply(engine, transport, command, "", true);
      } else {
        engine.processCommandResponseLineForTest(response);
      }
      assertEquals(2, count(transport, "kata-analyze"), "Success and rejection both drain old output");
      // KataGo can stream before completing the new analyze command's response.
      engine.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      BoardData data = f.history.getData();
      assertEquals(10, mirrored ? data.getPlayouts2() : data.getPlayouts());
      assertEquals("Q16", (mirrored ? data.bestMoves2 : data.bestMoves).get(0).coordinate);
      assertFalse(mirrored ? data.isChanged2 : data.isChanged);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"123", "123 # comment", "00123\t\r# comment"})
  void emptyNumberedConsoleInputCannotPoisonLaterRulesResponses(String command) throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      if (f.primary.sendRawConsoleCommand(command)) {
        // KataGo's empty-command error has no ID, even when the input has one.
        f.primary.processCommandResponseLineForTest("? empty command");
        f.mirror.processCommandResponseLineForTest("? empty command");
      }
      assertTrue(f.primary.sendRawConsoleCommand("kata-set-rules japanese"));
      assertTrue(f.primary.sendRawConsoleCommand("kata-analyze 10"));
      f.reply(f.primary, f.transport, "kata-set-rules", "", true);
      f.reply(f.mirror, f.mirrorTransport, "kata-set-rules", "", true);
      assertEquals(1, count(f.transport, "kata-analyze"));
      assertEquals(1, count(f.mirrorTransport, "kata-analyze"));
      f.primary.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      f.mirror.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      assertEquals(10, f.history.getData().getPlayouts());
      assertEquals(10, f.history.getData().getPlayouts2());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"123kata-set-rules japanese", "123\tkata-set-rules japanese",
      "00123\tkata-set-rules\tjapanese # ignored", "123 ka\u0001ta-set-rules japanese",
      " 123 kata-set-rules japanese", "123kata-set-rules invalid",
      "123\tkgs-rules japanese"})
  void websocketRulesReplyCannotStrandSuccessorAnalysis(String command) throws Exception {
    try (Fixture f = new Fixture(); TransportHarness remote = TransportHarness.open()) {
      var transport = remote.transport();
      f.primary.installCommandOutputForTest(transport.stdin());
      assertTrue(f.primary.sendRawConsoleCommand(command));
      String firstReply = new String(transport.stdout().readNBytes(transport.stdout().available()),
          java.nio.charset.StandardCharsets.UTF_8);
      firstReply.lines().forEach(f.primary::processCommandResponseLineForTest);
      assertTrue(f.primary.sendRawConsoleCommand("kata-set-rules chinese"));
      String rulesReply = new String(transport.stdout().readNBytes(transport.stdout().available()),
          java.nio.charset.StandardCharsets.UTF_8);
      rulesReply.lines().forEach(f.primary::processCommandResponseLineForTest);
      assertTrue(f.primary.sendRawConsoleCommand("124kata-analyze B 10"));
      String analysisReply = new String(transport.stdout().readNBytes(transport.stdout().available()),
          java.nio.charset.StandardCharsets.UTF_8);
      assertEquals("=124\n", analysisReply, "Rules success or rejection must release analysis");
      assertTrue(firstReply.startsWith("=123") || firstReply.startsWith("?123"), firstReply);
      analysisReply.lines().forEach(f.primary::processCommandResponseLineForTest);
      f.primary.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      assertEquals(10, f.history.getData().getPlayouts());
    }
  }

  @ParameterizedTest
  @CsvSource({"kata-set-rules japanese,kata-analyze,true",
      "KATA-SET-RULES japanese,KATA-ANALYZE,false",
      "kata-set-rules japanese,KaTa-AnAlYzE,false",
      "kata-set-rules invalid,KATA-ANALYZE,true",
      "kgs-rules japanese,kata-analyze,false",
      "kata-set-rule ko SIMPLE,KATA-ANALYZE,true"})
  void websocketRulesWaitForOldQueryBeforeReleasingSuccessor(
      String rulesCommand, String analyzeCommand, boolean ackFirst) throws Exception {
    try (Fixture f = new Fixture(); TransportHarness remote = TransportHarness.open()) {
      f.primary.installCommandOutputForTest(remote.transport().stdin());
      assertTrue(f.primary.sendRawConsoleCommand("120 kata-analyze B 10"));
      pumpRemote(f, remote);
      remote.emitAnalysis(0, true, 200);
      pumpRemote(f, remote);
      assertEquals(200, f.history.getData().getPlayouts());
      assertTrue(f.primary.sendRawConsoleCommand("121" + rulesCommand));
      String early = pumpRemote(f, remote);
      assertFalse(early.contains("=121") || early.contains("?121"), early);
      assertTrue(f.history.getData().isChanged);
      assertTrue(f.primary.sendRawConsoleCommand("122\t" + analyzeCommand + " B 10 # comment"));
      remote.emitAnalysis(0, true, 777);
      pumpRemote(f, remote);
      assertEquals(200, f.history.getData().getPlayouts(), "Old partial must not repopulate cache");
      var terminate = remote.sentQuery(1);
      assertEquals("terminate", terminate.getString("action"));
      if (ackFirst) remote.emit(terminate);
      else remote.emitAnalysis(0, false, 888);
      assertFalse(pumpRemote(f, remote).contains("121"));
      if (ackFirst) remote.emitAnalysis(0, false, 888);
      else remote.emit(terminate);
      pumpRemote(f, remote);
      pumpRemote(f, remote); // successor response produced while consuming the rules response
      assertEquals(200, f.history.getData().getPlayouts());
      var successor = remote.sentQuery(2);
      assertEquals(rulesCommand.toLowerCase(java.util.Locale.ROOT).equals("kata-set-rules japanese")
          ? "TERRITORY" : "AREA", successor.getJSONObject("rules").getString("scoring"));
      remote.emitAnalysis(0, true, 999); // late/duplicate packets remain retired
      remote.emit(terminate);
      assertEquals("", pumpRemote(f, remote));
      remote.emitAnalysis(2, true, 20);
      pumpRemote(f, remote);
      assertEquals(20, f.history.getData().getPlayouts(), "Only successor may publish fresh results");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"KATA-ANALYZE", "KaTa-AnAlYzE"})
  void websocketUppercaseAnalysisAfterStoppedRulesChangePublishesResults(String verb)
      throws Exception {
    try (Fixture f = new Fixture(); TransportHarness remote = TransportHarness.open()) {
      f.primary.installCommandOutputForTest(remote.transport().stdin());
      assertTrue(f.primary.sendRawConsoleCommand("123 kata-set-rules japanese"));
      pumpRemote(f, remote);
      assertTrue(f.primary.sendRawConsoleCommand("124" + verb + " B 10"));
      pumpRemote(f, remote);
      remote.emitAnalysis(0, true, 20);
      pumpRemote(f, remote);
      assertEquals(20, f.history.getData().getPlayouts());
    }
  }

  private static String pumpRemote(Fixture f, TransportHarness remote) throws Exception {
    var input = remote.transport().stdout();
    String output = new String(input.readNBytes(input.available()),
        java.nio.charset.StandardCharsets.UTF_8);
    output.lines().forEach(line -> {
      if (line.startsWith("info ")) f.primary.parseAnalysisLineForTest(line);
      else f.primary.processCommandResponseLineForTest(line);
    });
    return output;
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "# comment", "\t\r\u0001", "한글", "0", "123 # comment",
      "1000000000 name", "2147483647 name", "0000000001 name", "0000000000 name",
      "2147483648 name", "999999999999999999999 kata-set-rules japanese",
      "name\nkata-set-rules japanese", "123 # comment\nname"})
  void malformedConsoleEnvelopeIsRejectedBeforeEitherEngineIsTouched(String command)
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      assertTrue(f.primary.sendRawConsoleCommand("123 name"));
      var primaryBefore = f.transport.rawCommands();
      var mirrorBefore = f.mirrorTransport.rawCommands();
      assertFalse(f.primary.sendRawConsoleCommand(command));
      assertEquals(primaryBefore, f.transport.rawCommands());
      assertEquals(mirrorBefore, f.mirrorTransport.rawCommands());
      f.assertCaches(false, false);
      // Rejection cannot consume or relax an existing numbered response owner.
      f.primary.processCommandResponseLineForTest("=123 KataGo");
      f.mirror.processCommandResponseLineForTest("=123 KataGo");
      assertTrue(f.primary.sendRawConsoleCommand("kata-set-rules japanese"));
      f.reply(f.primary, f.transport, "kata-set-rules", "", true);
      f.reply(f.mirror, f.mirrorTransport, "kata-set-rules", "", true);
      assertTrue(f.primary.sendRawConsoleCommand("kata-analyze 10"));
      assertEquals(1, count(f.transport, "kata-analyze"));
      assertEquals(1, count(f.mirrorTransport, "kata-analyze"));
    }
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {"unknown-command|? unknown command",
      "0 unknown-command|?0 unknown command", "999999999 unknown-command|?999999999 unknown command",
      "000123unknown-command # comment|?123 unknown command"})
  void engineRejectionOfAValidEnvelopeStillAllowsRulesAndAnalysis(String command, String error)
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.primary.requireResponseBeforeSend = true;
      f.mirror.requireResponseBeforeSend = true;
      assertTrue(f.primary.sendRawConsoleCommand(command));
      assertTrue(f.transport.rawCommands().contains(command));
      assertTrue(f.mirrorTransport.rawCommands().contains(command));
      assertTrue(f.primary.sendRawConsoleCommand("kata-set-rules japanese"));
      assertTrue(f.primary.sendRawConsoleCommand("kata-analyze 10"));
      assertEquals(0, count(f.transport, "kata-set-rules"));
      f.primary.processCommandResponseLineForTest(error);
      f.mirror.processCommandResponseLineForTest(error);
      f.reply(f.primary, f.transport, "kata-set-rules", "", true);
      f.reply(f.mirror, f.mirrorTransport, "kata-set-rules", "", true);
      assertEquals(1, count(f.transport, "kata-analyze"));
      assertEquals(1, count(f.mirrorTransport, "kata-analyze"));
    }
  }

  @Test
  void asynchronousAnalysisWaitsForEveryRulesWriteWithoutBlockingOtherCommands() throws Exception {
    try (Fixture f = new Fixture()) {
      f.primary.sendCommandNoLeelaz2("kata-analyze 10");
      f.reply(f.primary, f.transport, "kata-analyze", "", true);
      assertTrue(f.primary.sendRawConsoleCommand("123 kata-set-rules japanese"));
      f.primary.sendCommandNoLeelaz2("789 name");
      assertTrue(f.primary.sendRawConsoleCommand("124 kgs-rules chinese"));
      assertTrue(f.transport.rawCommands().contains("789 name"), "Unrelated commands stay asynchronous");
      assertTrue(f.transport.rawCommands().contains("124 kgs-rules chinese"));
      f.primary.sendCommandNoLeelaz2("kata-analyze 10");
      f.primary.processCommandResponseLineForTest("=123");
      f.primary.processCommandResponseLineForTest("=789 KataGo");
      f.primary.parseAnalysisLineForTest("info move Q16 visits 1000 winrate 0.6 pv Q16");
      f.assertCaches(true, false, 2L);
      assertEquals(1, count(f.transport, "kata-analyze"));
      f.primary.processCommandResponseLineForTest("?124 rejected");
      assertEquals(2, count(f.transport, "kata-analyze"));
      f.primary.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      assertEquals(10, f.history.getData().getPlayouts());
      assertFalse(f.history.getData().isChanged);
    }
  }

  @Test
  void replacingTheReaderRetiresTheAsynchronousRulesBoundaryAndQueuedAnalysis() throws Exception {
    try (Fixture f = new Fixture()) {
      assertTrue(f.primary.sendRawConsoleCommand("123 kata-set-rules japanese"));
      f.primary.sendCommandNoLeelaz2("kata-analyze 10");
      assertEquals(0, count(f.transport, "kata-analyze"));
      f.primary.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      var freshTransport = ExactSnapshotRestoreProtocolFixture.install(f.primary, command -> null);
      f.primary.processCommandResponseLineForTest("=123");
      assertEquals(0, count(freshTransport, "kata-analyze"), "Old queued work must not survive rebind");
      f.primary.sendCommandNoLeelaz2("kata-analyze 10");
      assertEquals(1, count(freshTransport, "kata-analyze"), "Old replies must not gate the new reader");
      f.primary.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      assertEquals(10, f.history.getData().getPlayouts());
    }
  }

  @Test
  void rawRulesFromAHistoryLockedProducerUseTheCleanDispatcher() throws Exception {
    try (Fixture f = new Fixture()) {
      synchronized (f.history) {
        assertTrue(f.primary.sendRawConsoleCommand("kata-set-rules japanese"));
        assertEquals(0, count(f.transport, "kata-set-rules"));
        f.assertCaches(false, false);
      }
      await(() -> count(f.transport, "kata-set-rules") == 1);
      f.assertCaches(true, false);
    }
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "true|124 kata-analyze 10", "false|124 kata-analyze 10",
      "true|124kata-analyze 10", "false|124kata-analyze 10",
      "true|00124\tkata-analyze\t10 # allow B D4 1",
      "true|124 ka\rta-analyze 10", "true|124 lz-analyze 10", "true|124 analyze 10"
  })
  void numberedSuccessorAnalysisUsesTheSameRulesBoundaryAndOutputOwner(
      boolean queuedBeforeReply, String analysis) throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      assertTrue(f.primary.sendRawConsoleCommand("kata-analyze 10"));
      f.reply(f.primary, f.transport, "kata-analyze", "", true);
      f.reply(f.mirror, f.mirrorTransport, "kata-analyze", "", true);
      assertTrue(f.primary.sendRawConsoleCommand("123 kata-set-rules japanese"));
      if (queuedBeforeReply) {
        assertTrue(f.primary.sendRawConsoleCommand(analysis));
        assertFalse(f.transport.rawCommands().contains(analysis));
        assertFalse(f.mirrorTransport.rawCommands().contains(analysis));
        f.primary.parseAnalysisLineForTest("info move D4 visits 1000 winrate 0.6 pv D4");
        f.mirror.parseAnalysisLineForTest("info move D4 visits 1000 winrate 0.6 pv D4");
        f.assertCaches(true, true);
      }
      f.primary.processCommandResponseLineForTest("=123");
      f.mirror.processCommandResponseLineForTest("=123");
      if (!queuedBeforeReply) assertTrue(f.primary.sendRawConsoleCommand(analysis));
      assertTrue(f.transport.rawCommands().contains(analysis));
      assertTrue(f.mirrorTransport.rawCommands().contains(analysis));
      f.primary.processCommandResponseLineForTest("=124");
      f.mirror.processCommandResponseLineForTest("=124");
      f.primary.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      f.mirror.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      assertEquals(10, f.history.getData().getPlayouts());
      assertEquals(10, f.history.getData().getPlayouts2());
      assertEquals("Q16", f.history.getData().bestMoves.get(0).coordinate);
      assertEquals("Q16", f.history.getData().bestMoves2.get(0).coordinate);
    }
  }

  @Test
  void numberedAnalysisIsCoalescedAndCannotBypassInitialSynchronization() throws Exception {
    try (Fixture f = new Fixture()) {
      f.primary.beginInitialBoardSynchronization();
      try {
        assertFalse(f.primary.sendRawConsoleCommand("124kata-analyze 10"));
      } finally {
        f.primary.endInitialBoardSynchronization();
      }
      assertTrue(f.primary.sendRawConsoleCommand("123 kata-set-rules japanese"));
      assertTrue(f.primary.sendRawConsoleCommand("124kata-analyze 10"));
      assertTrue(f.primary.sendRawConsoleCommand("125 analyze 10"));
      f.primary.processCommandResponseLineForTest("=123");
      assertFalse(f.transport.rawCommands().contains("124kata-analyze 10"));
      assertTrue(f.transport.rawCommands().contains("125 analyze 10"));
      f.primary.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      assertEquals(10, f.history.getData().getPlayouts());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void numberedAnalysisIsTranslatedForTheReceivingLegacyEngine(boolean direct) throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.mirror.isKatago = false;
      String command = "00124kata-analyze 10 rootInfo true reuseRootTree true ownership true # comment";
      if (direct) f.mirror.sendCommandNoLeelaz2(command);
      else assertTrue(f.primary.sendRawConsoleCommand(command));
      assertTrue(f.mirrorTransport.rawCommands().contains("00124 lz-analyze 10"));
    }
  }

  @Test
  void rawRulesWriteInvalidatesOnlyTheMirrorWhenItActuallyDrains() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.mirror.requireResponseBeforeSend = true;
      f.mirror.sendCommandNoLeelaz2("name");
      f.mirror.sendCommandNoLeelaz2("kata-set-rules japanese");
      f.assertCaches(false, false);
      f.reply(f.mirror, f.mirrorTransport, "name", "KataGo", true);
      f.assertCaches(false, true);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"kata-set-rules japanese", "123 kata-set-rules japanese"})
  void rawRulesCancelledByReaderReplacementDoNotInvalidateAnalysis(String command) throws Exception {
    try (Fixture f = new Fixture()) {
      f.primary.requireResponseBeforeSend = true;
      f.primary.sendCommandNoLeelaz2("name");
      assertTrue(f.primary.sendRawConsoleCommand(command));
      f.primary.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      assertFalse(f.transport.rawCommands().stream().anyMatch(line -> line.contains("kata-set-rules")));
      f.assertCaches(false, false);
    }
  }

  @ParameterizedTest
  @CsvSource({"primary,kata-set-rules japanese", "mirror,kata-set-rules japanese",
      "console,kata-set-rules japanese", "primary,kata-set-rule ko SIMPLE",
      "mirror,kata-set-rule ko SIMPLE", "console,kata-set-rule ko SIMPLE",
      "primary,kgs-rules japanese", "mirror,kgs-rules japanese", "console,kgs-rules japanese",
      "primary,loadsgf queued.sgf", "mirror,loadsgf queued.sgf", "console,loadsgf queued.sgf"})
  void queuedRulesWritersCannotMakeTheLastManualSelectionANoOp(String route, String command)
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.primary.requireResponseBeforeSend = true;
      f.mirror.requireResponseBeforeSend = true;
      f.primary.sendCommandNoLeelaz2("name");
      f.mirror.sendCommandNoLeelaz2("name");
      if (route.equals("console")) assertTrue(f.primary.sendRawConsoleCommand(command));
      else (route.equals("primary") ? f.primary : f.mirror).sendCommandNoLeelaz2(command);
      assertFalse(f.transport.commands().contains(command));
      assertFalse(f.mirrorTransport.commands().contains(command));
      f.assertCaches(false, false);

      ManualRulesSelection selection = f.apply(CHINESE);
      assertFalse(selection.unchanged(), "An earlier queued writer can still change the rules");
      f.reply(f.primary, f.transport, "name", "KataGo", true);
      if (!route.equals("mirror")) {
        await(() -> f.transport.commands().contains(command));
        f.reply(f.primary, f.transport, command, "", true);
      }
      await(() -> f.transport.commands().contains("kata-set-rules " + CHINESE.toGtpArgument()));
      List<String> primaryCommands = f.transport.commands();
      assertEquals("kata-set-rules " + CHINESE.toGtpArgument(),
          primaryCommands.get(primaryCommands.size() - 1));
      f.confirmChange(CHINESE);
      await(selection::isDone);
      assertTrue(f.history.captureSessionRules().parsedRules().orElseThrow()
          .semanticallyEquals(CHINESE));

      // Exercise the real post-selection synchronizer while the mirror still has queued work.
      FutureTask<SessionRulesSynchronizer.Result> sync = new FutureTask<>(() ->
          SessionRulesSynchronizer.synchronize(f.history.captureSessionRules(), f.mirror));
      Thread worker = new Thread(sync, "queued-rules-mirror-sync");
      worker.start();
      try {
        if (!route.equals("primary")) {
          await(() -> sync.isDone() || !f.mirror.engineRulesOperation().isDone());
          assertFalse(sync.isDone(), "Mirror convergence must not reuse a superseded observation");
        }
        synchronized (f.history) {
          // Force the supported deferred-send path; releasing this response is not a transport
          // completion signal for the raw write that follows it (the Linux CI race).
          f.reply(f.mirror, f.mirrorTransport, "name", "KataGo", true);
          if (!route.equals("primary")) {
            assertFalse(f.mirrorTransport.commands().contains(command));
          }
        }
        if (!route.equals("primary")) {
          await(() -> f.mirrorTransport.commands().contains(command));
          f.reply(f.mirror, f.mirrorTransport, command, "", true);
          // Creating the asynchronous operation precedes enqueue/dispatch. Wait for its actual
          // corrective write, not just the pending operation observed above.
          await(() -> f.mirrorTransport.commands().contains(
              "kata-set-rules " + CHINESE.toGtpArgument()));
          List<String> mirrorCommands = f.mirrorTransport.commands();
          assertEquals("kata-set-rules " + CHINESE.toGtpArgument(),
              mirrorCommands.get(mirrorCommands.size() - 1));
          f.reply(f.mirror, f.mirrorTransport, "kata-set-rules", "", true);
          f.reply(f.mirror, f.mirrorTransport, "kata-get-rules", CHINESE.toGtpArgument(), true);
        }
        assertTrue(sync.get(3, TimeUnit.SECONDS).satisfied());
        assertTrue(f.mirror.currentConfirmedRules().semanticallyEquals(CHINESE));
      } finally {
        worker.interrupt();
        worker.join(3_000L);
        assertFalse(worker.isAlive());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"kata-set-rules japanese", "kata-set-rule ko SIMPLE",
      "kgs-rules japanese", "loadsgf external.sgf"})
  void synchronizationCannotSkipAnAlreadyWrittenExternalRulesChange(String command)
      throws Exception {
    try (Fixture f = new Fixture()) {
      var target = f.history.publishExternalRules(CHINESE.toGtpArgument());
      f.primary.sendCommandNoLeelaz2(command);
      f.reply(f.primary, f.transport, command, "", true);
      assertTrue(f.primary.engineRulesResult().isConfirmed(), "UI result is historical");
      FutureTask<SessionRulesSynchronizer.Result> sync = new FutureTask<>(() ->
          SessionRulesSynchronizer.synchronize(target, f.primary));
      Thread worker = new Thread(sync, "written-rules-sync");
      worker.start();
      try {
        await(() -> sync.isDone() || !f.primary.engineRulesOperation().isDone());
        assertFalse(sync.isDone(), "A prior confirmation cannot cover an external write");
        await(() -> f.transport.commands().contains("kata-set-rules " + CHINESE.toGtpArgument()));
        f.confirmChange(CHINESE);
        assertTrue(sync.get(3, TimeUnit.SECONDS).satisfied());
        assertTrue(f.primary.currentConfirmedRules().semanticallyEquals(CHINESE));
      } finally {
        worker.interrupt();
        worker.join(3_000L);
        assertFalse(worker.isAlive());
      }
    }
  }

  @Test
  void dispatchedRulesWriterStillPreventsNoOpBeforeItsFirstPhysicalByte() throws Exception {
    try (Fixture f = new Fixture()) {
      Field lockField = Leelaz.class.getDeclaredField("rulesObservationLock");
      lockField.setAccessible(true);
      int lockId = System.identityHashCode(lockField.get(f.primary));
      FutureTask<Void> write = new FutureTask<>(() -> {
        f.primary.sendRawConsoleCommand("kgs-rules japanese");
        return null;
      });
      Thread writer = new Thread(write, "dequeued-rules-writer");
      ManualRulesSelection[] selected = {null};
      try {
        f.primary.withManualRulesCache(() -> {
          writer.start();
          try {
            await(() -> {
              var info = ManagementFactory.getThreadMXBean().getThreadInfo(writer.getId());
              return info != null && info.getThreadState() == Thread.State.BLOCKED
                  && info.getLockInfo() != null
                  && info.getLockInfo().getIdentityHashCode() == lockId;
            });
          } catch (Exception failure) { throw new AssertionError(failure); }
          assertEquals(0, count(f.transport, "kgs-rules"));
          assertTrue(f.primary.hasPendingRulesWrite(), "Dequeue must not hide the selected writer");
        });
        write.get(3, TimeUnit.SECONDS);
        selected[0] = f.apply(CHINESE);
        assertFalse(selected[0].unchanged());
        f.reply(f.primary, f.transport, "kgs-rules", "", true);
        f.confirmChange(CHINESE);
        await(selected[0]::isDone);
        assertTrue(f.primary.currentConfirmedRules().semanticallyEquals(CHINESE));
      } finally {
        writer.join(3_000L);
        assertFalse(writer.isAlive());
      }
    }
  }

  @Test
  void unrelatedQueuedWorkDoesNotDisableTheManualNoOp() throws Exception {
    try (Fixture f = new Fixture()) {
      f.primary.requireResponseBeforeSend = true;
      f.primary.sendCommandNoLeelaz2("name");
      f.primary.sendCommandNoLeelaz2("version");
      assertTrue(f.apply(CHINESE).unchanged());
      f.assertCaches(false, false);
      f.reply(f.primary, f.transport, "name", "KataGo", true);
      f.reply(f.primary, f.transport, "version", "test", true);
      assertEquals(0, count(f.transport, "kata-set-rules"));
    }
  }

  @Test
  void cancelledQueuedRulesWriterRestoresNoOpWithoutDiscardingCache() throws Exception {
    try (Fixture f = new Fixture()) {
      f.primary.requireResponseBeforeSend = true;
      f.primary.sendCommandNoLeelaz2("name");
      var cancelled = f.primary.applyEngineRulesOperation(JAPANESE, 30_000L);
      var replacement = f.primary.queryEngineRulesOperation(30_000L);
      assertEquals(EngineRulesResult.Reason.REPLACED, cancelled.await(1_000L).reason());
      f.reply(f.primary, f.transport, "name", "KataGo", true);
      f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
      assertTrue(replacement.await(1_000L).isConfirmed());
      assertTrue(f.apply(CHINESE).unchanged());
      assertEquals(0, count(f.transport, "kata-set-rules"));
      f.assertCaches(false, false);
    }
  }

  @ParameterizedTest
  @CsvSource({"unchanged,false", "unchanged,true", "rejected,false", "rejected,true",
      "recovering,false", "recovering,true"})
  void exactPositionRestorePreservesRetainedAnalysisUntilAnExternalWrite(
      String selectionState, boolean comparison) throws Exception {
    try (Fixture f = new Fixture()) {
      if (comparison) f.addMirror(CHINESE);
      ManualRulesSelection selection = f.apply(
          selectionState.equals("unchanged") ? CHINESE : JAPANESE);
      if (!selectionState.equals("unchanged")) {
        f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
        f.awaitRecovery();
        if (selectionState.equals("rejected")) {
          f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
          await(selection::isDone);
        }
      }
      f.assertCaches(false, false);
      f.restoreResponses = true;
      ExactSnapshotEngineRestore.prepareCurrentPosition(
          f.primary.captureHistoryNavigationExactSnapshotRestoreAdmission(f.mirror),
          f.cached.get(0)).execute();
      assertEquals(comparison ? 2 : 1, f.restoredSgfs.size());
      for (String sgf : f.restoredSgfs) assertFalse(sgf.contains("RU["));
      if (selectionState.equals("recovering")) {
        // The physical snapshot fence rejects this older readback, but cannot revoke cache
        // provenance: the rejected setter and the admitted restore did not change the rules.
        f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
        await(selection::isDone);
      }
      f.assertCaches(false, false);
      if (!selectionState.equals("recovering")) {
        var target = f.history.captureSessionRules();
        var commands = f.transport.commands();
        assertTrue(f.apply(CHINESE).unchanged(),
            "An admitted rules-preserving restore must not make the next identical choice a change");
        assertSame(target, f.history.captureSessionRules());
        assertEquals(commands, f.transport.commands());
        f.assertCaches(false, false);
      }

      // A harmless restore must not consume the guard protecting against a later real writer.
      f.restoreResponses = false;
      assertTrue(f.primary.sendRawConsoleCommand("loadsgf external.sgf"));
      f.reply(f.primary, f.transport, "loadsgf external.sgf", "", true);
      if (comparison) f.reply(f.mirror, f.mirrorTransport, "loadsgf external.sgf", "", true);
      f.assertCaches(true, comparison);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"confirmed", "rejected", "query-failed", "timeout", "replaced",
      "send-failed", "unconfirmed"})
  void terminalRulesOperationsReleaseTheirIntent(String outcome) throws Exception {
    try (Fixture f = new Fixture()) {
      if (outcome.equals("send-failed")) f.failSetWrite = true;
      if (outcome.equals("unconfirmed")) f.primary.advertiseCommandsForTest(List.of("kata-set-rules"));
      BooleanSupplier intent = () -> Lizzie.board.getHistory() == f.history;
      var operation = f.primary.applyManualRulesOperation(JAPANESE,
          outcome.equals("timeout") ? 40L : 30_000L, intent);
      Field callback = Leelaz.EngineRulesOperation.class.getDeclaredField("currentIntent");
      callback.setAccessible(true);
      if (!outcome.equals("timeout") && !outcome.equals("send-failed")) {
        assertSame(intent, callback.get(operation));
      }
      switch (outcome) {
        case "confirmed" -> f.confirmChange(JAPANESE);
        case "rejected" -> f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
        case "query-failed" -> {
          f.reply(f.primary, f.transport, "kata-set-rules", "", true);
          f.reply(f.primary, f.transport, "kata-get-rules", "query failed", false);
        }
        case "unconfirmed" -> f.reply(f.primary, f.transport, "kata-set-rules", "", true);
        case "replaced" -> {
          f.primary.queryEngineRulesOperation(1_000L);
          f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
        }
        default -> { }
      }
      assertNotNull(operation.await(2_000L));
      var result = operation.result();
      assertEquals(switch (outcome) {
        case "confirmed" -> EngineRulesResult.Reason.NONE;
        case "rejected" -> EngineRulesResult.Reason.SET_REJECTED;
        case "query-failed" -> EngineRulesResult.Reason.QUERY_REJECTED;
        case "timeout" -> EngineRulesResult.Reason.SET_TIMEOUT;
        case "replaced" -> EngineRulesResult.Reason.REPLACED;
        case "send-failed" -> EngineRulesResult.Reason.SEND_FAILED;
        case "unconfirmed" -> EngineRulesResult.Reason.QUERY_UNSUPPORTED;
        default -> throw new AssertionError(outcome);
      }, result.reason());
      assertNull(callback.get(operation), "Terminal protocol work must release its caller's history");
      Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
      assertSame(result, operation.result(), "Releasing caller ownership must retain the result");
    }
  }

  @Test
  void rejectedManualSelectionReleasesBothSetterAndRecoveryIntents() throws Exception {
    try (Fixture f = new Fixture()) {
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      f.awaitRecovery();
      var recovery = f.primary.engineRulesOperation();
      f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
      await(selection::isDone);
      Field callback = Leelaz.EngineRulesOperation.class.getDeclaredField("currentIntent");
      callback.setAccessible(true);
      assertNull(callback.get(selection.operation()));
      assertNull(callback.get(recovery));
      assertSame(recovery, f.primary.engineRulesOperation());
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

  @ParameterizedTest
  @CsvSource({"kata-set-rule ko POSITIONAL,false", "kata-set-rules aga,false", "kgs-rules aga,false",
      "kata-set-rule ko POSITIONAL,true", "kata-set-rules aga,true", "kgs-rules aga,true"})
  void externalRulesWriteInvalidatesRejectedSelectionWithoutRetry(
      String command, boolean delayedReadback) throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      var target = f.history.publishExternalRules("unrecognized-rules");
      long generation = Lizzie.capturePrimaryEngineGeneration(f.primary);
      f.history.authorizeAnalysisWithCurrentRules(target, f.primary, generation, f.mirror);
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      f.awaitRecovery();

      f.primary.sendCommandNoLeelaz2(command);
      f.reply(f.primary, f.transport, command, "", true);
      f.reply(f.primary, f.transport, "kata-get-rules",
          delayedReadback ? CHINESE.toGtpArgument() : "read failed", delayedReadback);
      await(selection::isDone);

      assertSame(target, f.history.captureSessionRules());
      assertFalse(f.history.permitsAnalysisWithCurrentRules(target, f.primary, generation, f.mirror));
      assertEquals(0, f.frame.synchronizedRules);
      f.assertCaches(true, false);
    }
  }

  @Test
  void physicalRulesWriteCannotCrossTheCacheDecisionFence() throws Exception {
    try (Fixture f = new Fixture()) {
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      f.awaitRecovery();
      Field lockField = Leelaz.class.getDeclaredField("rulesObservationLock");
      lockField.setAccessible(true);
      int lockId = System.identityHashCode(lockField.get(f.primary));
      FutureTask<Void> write = new FutureTask<>(() -> {
        f.primary.sendCommandNoLeelaz2("kgs-rules aga");
        return null;
      });
      Thread writer = new Thread(write, "manual-rules-cache-fenced-writer");
      try {
        f.primary.withManualRulesCache(() -> {
          writer.start();
          try {
            await(() -> {
              var info = ManagementFactory.getThreadMXBean().getThreadInfo(writer.getId());
              return info != null && info.getThreadState() == Thread.State.BLOCKED
                  && info.getLockInfo() != null
                  && info.getLockInfo().getIdentityHashCode() == lockId;
            });
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
          assertEquals(0, count(f.transport, "kgs-rules"));
          assertSame(selection, f.primary.manualRulesCacheOwner.get());
        });
        write.get(3, TimeUnit.SECONDS);
        f.reply(f.primary, f.transport, "kgs-rules", "", true);
        assertNull(f.primary.manualRulesCacheOwner.get());
        f.reply(f.primary, f.transport, "kata-get-rules", "read failed", false);
        await(selection::isDone);
        f.assertCaches(true, false);
      } finally {
        writer.join(3_000L);
        assertFalse(writer.isAlive());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"console,kata-set-rules aga", "mirror,kata-set-rules aga",
      "console,kata-set-rule ko POSITIONAL", "mirror,kata-set-rule ko POSITIONAL",
      "console,kgs-rules aga", "mirror,kgs-rules aga"})
  void externalRulesWritesInvalidateTheCacheOfEachReceivingEngine(String route, String command)
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      f.awaitRecovery();
      if (route.equals("console")) assertTrue(f.primary.sendRawConsoleCommand(command));
      else f.mirror.sendCommandNoLeelaz2(command);
      assertTrue(f.mirrorTransport.commands().contains(command));
      if (route.equals("console")) f.reply(f.primary, f.transport, command, "", true);
      f.reply(f.mirror, f.mirrorTransport, command, "", true);
      f.reply(f.primary, f.transport, "kata-get-rules", "read failed", false);
      await(selection::isDone);

      f.assertCaches(route.equals("console"), true);
      assertTrue(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.mirror));
      f.mirror.sendCommandNoLeelaz2("kata-analyze 10");
      f.reply(f.mirror, f.mirrorTransport, "kata-analyze", "", true);
      f.mirror.parseAnalysisLineForTest("info move Q16 visits 10 winrate 0.6 pv Q16");
      assertEquals(10, f.history.getData().getPlayouts2());
      assertEquals("Q16", f.history.getData().bestMoves2.get(0).coordinate);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void queuedMirroredRulesWriteCannotRetainCacheAfterManualSelectionFinishes(boolean retry)
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.mirror.requireResponseBeforeSend = true;
      f.mirror.sendCommandNoLeelaz2("name");
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      f.awaitRecovery();
      assertTrue(f.primary.sendRawConsoleCommand("kata-set-rules aga"));
      assertEquals(0, count(f.mirrorTransport, "kata-set-rules"));
      f.reply(f.primary, f.transport, "kata-set-rules", "", true);
      if (retry) {
        int expectedQueries = count(f.transport, "kata-get-rules") + 1;
        selection = f.apply(JAPANESE);
        f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
        await(() -> count(f.transport, "kata-get-rules") == expectedQueries);
      }
      f.reply(f.primary, f.transport, "kata-get-rules", "read failed", false);
      await(selection::isDone);
      f.reply(f.mirror, f.mirrorTransport, "name", "KataGo", true);
      assertEquals(1, count(f.mirrorTransport, "kata-set-rules"));
      f.reply(f.mirror, f.mirrorTransport, "kata-set-rules", "", true);
      f.assertCaches(true, true);
    }
  }

  @Test
  void cancelledBeforeWriteDoesNotInvalidateTheRetainedMirrorCache() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      f.mirror.requireResponseBeforeSend = true;
      f.mirror.sendCommandNoLeelaz2("name");
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      f.awaitRecovery();
      var cancelled = f.mirror.applyEngineRulesOperation(JAPANESE, 30_000L);
      var replacement = f.mirror.queryEngineRulesOperation(30_000L);
      assertEquals(EngineRulesResult.Reason.REPLACED, cancelled.await(1_000L).reason());
      f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
      await(selection::isDone);
      f.reply(f.mirror, f.mirrorTransport, "name", "KataGo", true);
      assertEquals(0, count(f.mirrorTransport, "kata-set-rules"));
      f.reply(f.mirror, f.mirrorTransport, "kata-get-rules", CHINESE.toGtpArgument(), true);
      assertTrue(replacement.await(1_000L).isConfirmed());
      f.assertCaches(false, false);
    }
  }

  @Test
  void unchangedSelectionAlsoProtectsItsRetainedCachesUntilTheNextExternalWrite() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      assertTrue(f.apply(CHINESE).unchanged());
      f.assertCaches(false, false);
      assertTrue(f.primary.sendRawConsoleCommand("kata-set-rules aga"));
      f.reply(f.primary, f.transport, "kata-set-rules", "", true);
      f.reply(f.mirror, f.mirrorTransport, "kata-set-rules", "", true);
      f.assertCaches(true, true);
    }
  }

  @Test
  void successfulSettlementAlsoInvalidatesCachedNodesAddedWhilePending() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      ManualRulesSelection selection = f.apply(JAPANESE);
      BoardData added = BoardData.empty(19, 19);
      added.bestMoves = f.history.getData().bestMoves;
      added.bestMoves2 = f.history.getData().bestMoves2;
      added.setPlayouts(100);
      added.setPlayouts2(100);
      added.isChanged = false;
      added.isChanged2 = false;
      synchronized (f.history) {
        f.history.getStart().addAtLast(added);
      }
      f.confirmChange(JAPANESE);
      await(selection::isDone);
      assertTrue(added.isChanged);
      assertTrue(added.isChanged2);
      assertEquals(100, added.getPlayouts());
      assertEquals(100, added.getPlayouts2());
      f.assertCaches(true, true);
    }
  }

  @Test
  void successfulSelectionStillInvalidatesTheInactiveComparisonSlot() throws Exception {
    try (Fixture f = new Fixture()) {
      f.addMirror(CHINESE);
      Lizzie.leelaz2 = null;
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.confirmChange(JAPANESE);
      await(selection::isDone);
      f.assertCaches(true, true);
    }
  }

  @Test
  void externalRulesWriteAfterRecoveryPublicationInvalidatesTheCurrentSelection() throws Exception {
    try (Fixture f = new Fixture()) {
      var target = f.history.publishExternalRules("unrecognized-rules");
      long generation = Lizzie.capturePrimaryEngineGeneration(f.primary);
      f.history.authorizeAnalysisWithCurrentRules(target, f.primary, generation, null);
      ManualRulesSelection selection = f.apply(JAPANESE);
      f.reply(f.primary, f.transport, "kata-set-rules", "rejected", false);
      f.awaitRecovery();
      f.frame.refreshEntered = new CountDownLatch(1);
      f.frame.releaseRefresh = new CountDownLatch(1);
      f.frame.gateNextRefresh = true;
      FutureTask<Void> readback = new FutureTask<>(() -> {
        f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
        return null;
      });
      Thread reader = new Thread(readback, "manual-rules-external-write-after-readback");
      reader.start();
      try {
        assertTrue(f.frame.refreshEntered.await(3, TimeUnit.SECONDS));
        assertFalse(selection.isDone());
        f.primary.sendCommandNoLeelaz2("kata-set-rules aga");
        f.reply(f.primary, f.transport, "kata-set-rules aga", "", true);
        f.frame.releaseRefresh.countDown();
        readback.get(3, TimeUnit.SECONDS);
        await(selection::isDone);

        assertSame(target, f.history.captureSessionRules());
        assertFalse(f.history.permitsAnalysisWithCurrentRules(target, f.primary, generation, null));
        assertEquals(0, f.frame.synchronizedRules);
        f.assertCaches(true, false);
      } finally {
        f.frame.releaseRefresh.countDown();
        reader.join(3_000L);
        assertFalse(reader.isAlive());
      }
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
      f.assertCaches(true, false, 2L);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"kata-set-rules japanese", "kata-set-rule ko POSITIONAL",
      "kgs-rules japanese", "loadsgf changed-rules.sgf"})
  void delayedReadbackBeforeARulesWriteCannotMakeManualSelectionANoOp(String command)
      throws Exception {
    try (Fixture f = new Fixture()) {
      var target = f.history.publishExternalRules(CHINESE.toGtpArgument());
      var query = f.primary.queryEngineRulesOperation(30_000L);
      f.primary.sendCommand(command);
      assertTrue(f.transport.commands().contains(command), "The later write must reach the engine");
      // The engine answered this query before processing the later write; delivery is delayed.
      f.reply(f.primary, f.transport, "kata-get-rules", CHINESE.toGtpArgument(), true);
      f.reply(f.primary, f.transport, command, "", true);

      assertFalse(query.await(1_000L).isConfirmed());
      assertNull(f.primary.currentConfirmedRules());
      assertFalse(SessionRulesSynchronizer.permitsOrdinaryAnalysis(f.primary));
      ManualRulesSelection selection = f.apply(CHINESE);
      assertFalse(selection.unchanged());
      f.confirmChange(CHINESE);
      await(selection::isDone);
      assertEquals(target.revision() + 1, f.history.captureSessionRules().revision());
      assertTrue(f.primary.currentConfirmedRules().semanticallyEquals(CHINESE));
      f.assertCaches(true, false, 2L);
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
    boolean restoreResponses;
    final List<String> restoredSgfs = new ArrayList<>();

    ExactSnapshotRestoreProtocolFixture.Response restoreResponse(String command) throws Exception {
      if (!restoreResponses) return null;
      if (command.startsWith("loadsgf ")) {
        restoredSgfs.add(Files.readString(Path.of(command.substring("loadsgf ".length()))));
      }
      return command.equals("clear_board") || command.equals("name") || command.startsWith("loadsgf ")
          ? ExactSnapshotRestoreProtocolFixture.Response.success() : null;
    }

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
        return restoreResponse(command);
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
      mirrorTransport = ExactSnapshotRestoreProtocolFixture.install(mirror, this::restoreResponse);
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

    ManualRulesSelection apply(RulePreset preset, long timeout) {
      ManualRulesSelection selection = ManualRulesSelection.apply(primary, preset, timeout);
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
      assertCaches(primaryChanged, secondaryChanged, primaryChanged ? 1L : 0L);
    }

    void assertCaches(boolean primaryChanged, boolean secondaryChanged, long primaryRevision) {
      for (BoardData data : cached) {
        assertEquals(100, data.getPlayouts());
        assertEquals(100, data.getPlayouts2());
        assertEquals("D4", data.bestMoves.get(0).coordinate);
        assertEquals("D4", data.bestMoves2.get(0).coordinate);
        assertEquals(primaryChanged, data.isChanged);
        assertEquals(secondaryChanged, data.isChanged2);
        assertEquals(!primaryChanged, data.hasReusablePrimaryAnalysis(100, false));
        assertEquals(primaryRevision, data.primaryAnalysisRevision());
      }
    }

    @Override public void close() throws Exception {
      try {
        for (ManualRulesSelection selection : selections) await(selection::isDone);
        assertNull(primary.manualRulesCacheOwner.get());
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
