package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.enginegame.EngineGameCompletionFacts;
import featurecat.lizzie.enginegame.EngineGameParticipantDescriptor;
import featurecat.lizzie.enginegame.EngineGameRecord;
import featurecat.lizzie.enginegame.EngineGameRecordContext;
import featurecat.lizzie.enginegame.EngineGameSide;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.GameOutcome;
import featurecat.lizzie.enginegame.MatchRulesAdmission;
import featurecat.lizzie.enginegame.MatchRulesSnapshot;
import featurecat.lizzie.enginegame.MatchRulesTexts;
import featurecat.lizzie.gui.LizzieFrame;
import java.util.Enumeration;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SGFParserEngineGameRecordTest {
  @Test
  void appendCompletedTotalsTargetsTheSuppliedHistoryAfterReplacement() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      BoardHistoryList original = completedHistory();
      BoardHistoryList replacement =
          SGFParser.parseSgf("(;SZ[5]C[replacement root];B[cc])", true);
      Lizzie.board.setHistory(replacement);
      BoardHistoryNode replacementPosition = replacement.getCurrentHistoryNode();

      SGFParser.appendGameTimeAndPlayouts(original);

      String comment = original.getStart().getData().comment;
      assertTrue(comment.contains("finished root"), comment);
      assertTrue(comment.contains("1.5"), comment);
      assertTrue(comment.contains("2.5"), comment);
      assertTrue(comment.contains("11"), comment);
      assertTrue(comment.contains("22"), comment);
      assertSame(replacement, Lizzie.board.getHistory());
      assertSame(replacementPosition, replacement.getCurrentHistoryNode());
      assertEquals("replacement root", replacement.getStart().getData().comment);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitCompletedCaptureKeepsOriginalMetadataAndCommentsAfterReplacement(
      boolean replacementHasEngineContext) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Board board = Lizzie.board;
      BoardHistoryList original = completedHistory();
      board.setHistory(original);
      Lizzie.config.appendWinrateToComment = true;
      Lizzie.config.showKataGoScoreLeadWithKomi = true;
      BoardData originalRoot = original.getStart().getData();
      originalRoot.isKataData = true;
      originalRoot.engineName = "finished analysis engine";
      originalRoot.setPlayouts(23);
      originalRoot.scoreMean = 2.0;
      String expected = SGFParser.captureSave(board, false);
      BoardHistoryNode originalPosition = original.getCurrentHistoryNode();
      BoardHistoryList replacement =
          SGFParser.parseSgf(
              "(;SZ[9]KM[0.5]PB[replacement black]PW[replacement white]"
                  + "RE[B+0.5]DZ[KW]C[replacement root];B[cc];W[dd])", true);
      if (replacementHasEngineContext) {
        replacement.getGameInfo().attachEngineGameRecordContext(
            EngineGameRecordContext.saveFormattingMarker());
      }
      board.setHistory(replacement);
      BoardHistoryNode replacementPosition = replacement.getCurrentHistoryNode();
      board.isGameBoard = true;
      board.isLoadingFile = true;
      Lizzie.leelaz.isKatago = true;
      Lizzie.leelaz.rulesSummary = KataGoRules.Summary.JAPANESE;
      Lizzie.leelaz.oriEnginename = "replacement engine";

      ResourceBundle liveBundle = Lizzie.resourceBundle;
      Zobrist liveCoordinateHash = replacementCoordinateHash();
      int[] observations = {0};
      Lizzie.resourceBundle =
          new ResourceBundle() {
            @Override
            protected Object handleGetObject(String key) {
              // Comments are formatted inside serialization, after root metadata and before
              // the writer returns. An engine reader must keep seeing the replacement board.
              assertEquals(9, Board.boardWidth);
              assertEquals(9, Board.boardHeight);
              assertEquals(liveCoordinateHash, replacementCoordinateHash());
              observations[0]++;
              return liveBundle.getObject(key);
            }

            @Override
            public Enumeration<String> getKeys() {
              return liveBundle.getKeys();
            }
          };
      String captured;
      try {
        captured = SGFParser.captureSave(board, original, false);
      } finally {
        Lizzie.resourceBundle = liveBundle;
      }

      assertTrue(observations[0] > 0, "The live board must be observed during serialization.");
      assertEquals(expected, captured);
      assertTrue(captured.contains("SZ[19]"), captured);
      assertTrue(captured.contains("KM[7.5]"), captured);
      assertTrue(captured.contains("RE[W+R]"), captured);
      assertTrue(captured.contains("DZ[KB]"), captured);
      assertTrue(captured.contains("finished analysis engine"), captured);
      assertTrue(captured.contains(";B[aa]"), captured);
      assertTrue(captured.contains(";W[bb]"), captured);
      assertTrue(captured.contains("AB[se]"), captured);
      assertFalse(captured.contains("replacement"), captured);
      assertSame(originalPosition, original.getCurrentHistoryNode());
      assertEquals("finished root", originalRoot.comment);
      assertSame(replacement, board.getHistory());
      assertSame(replacementPosition, replacement.getCurrentHistoryNode());
      assertEquals("replacement root", replacement.getStart().getData().comment);
      assertEquals("B+0.5", replacement.getGameInfo().getResult());
      assertNull(replacement.getGameInfo().engineGameSaveSnapshot());
      assertTrue(board.isLoadingFile);
      assertEquals(9, Board.boardWidth);
      assertEquals(9, Board.boardHeight);
    }
  }

  private static Zobrist replacementCoordinateHash() {
    Zobrist hash = new Zobrist();
    hash.toggleStone(8, 8, Stone.BLACK);
    hash.toggleStone(3, 7, Stone.WHITE);
    return hash;
  }

  private static BoardHistoryList completedHistory() {
    BoardHistoryList history =
        SGFParser.parseSgf(
            "(;SZ[19]KM[7.5]PB[finished black]PW[finished white]RE[W+R]"
                + "AB[se]C[finished root];B[aa]C[finished first move];W[bb])", true);
    EngineGameRecordContext context =
        new EngineGameRecordContext(
            null,
            new EngineGameParticipantDescriptor(
                new EngineParticipantIdentity("finished-black", "finished black"),
                "finished black", true, false, KataGoRules.Summary.CHINESE),
            new EngineGameParticipantDescriptor(
                new EngineParticipantIdentity("finished-white", "finished white"),
                "finished white", false, false, null));
    history.getGameInfo().freezeEngineGameRecord(
        new EngineGameRecord(
            context,
            new EngineGameCompletionFacts(
                new GameOutcome.Resign(EngineGameSide.BLACK), 1, 7, true, 1500, 2500, 11, 22),
            "finished black", "finished white"));
    return history;
  }

  @Test
  void appendGameTimeAndPlayoutsUsesHistoryRecordNotCurrentGlobals() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.chkEngineSgfStart = true;
      if (LizzieFrame.toolbar != null) {
        LizzieFrame.toolbar.currentEnginePkSgfNum = 99;
      }

      GameInfo info = Lizzie.board.getHistory().getGameInfo();
      EngineGameRecordContext context = EngineGameRecordContext.saveFormattingMarker();
      info.attachEngineGameRecordContext(context);
      info.freezeEngineGameRecord(
          new EngineGameRecord(
              context,
              new EngineGameCompletionFacts(
                  new GameOutcome.Resign(EngineGameSide.BLACK), 1, 7, true, 1500, 2500, 11, 22),
              "BlackEngine",
              "WhiteEngine"));

      SGFParser.appendGameTimeAndPlayouts();
      String comment = Lizzie.board.getHistory().getStart().getData().comment;

      assertTrue(
          comment.contains(Lizzie.resourceBundle.getString("SGFParse.startGameSgf") + "7"),
          comment);
      assertFalse(comment.contains("99"), comment);
      assertTrue(comment.contains("1.5"), comment);
      assertTrue(comment.contains("2.5"), comment);
      assertTrue(comment.contains("11"), comment);
      assertTrue(comment.contains("22"), comment);
    }
  }

  @Test
  void saveUsesHistoryAttachmentWithoutCurrentEngineIndexes() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      GameInfo info = Lizzie.board.getHistory().getGameInfo();
      EngineParticipantIdentity black = new EngineParticipantIdentity("b", "BlackEngine");
      EngineParticipantIdentity white = new EngineParticipantIdentity("w", "WhiteEngine");
      info.attachEngineGameRecordContext(
          new EngineGameRecordContext(
              null,
              new EngineGameParticipantDescriptor(
                  black, "BlackEngine", true, false, KataGoRules.Summary.CHINESE),
              new EngineGameParticipantDescriptor(white, "WhiteEngine", false, false, null)));

      String sgf = SGFParser.saveToString(false);

      assertTrue(sgf.contains("DZ[KB]"), sgf);
    }
  }

  @Test
  void saveWritesFrozenMatchRulesNotLiveEngineClassification() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      ResourceBundle bundle = Lizzie.resourceBundle;
      KataGoRules chinese = KataGoRules.parse("chinese").orElseThrow();
      KataGoRules positional = KataGoRules.parse("chinese-ogs").orElseThrow();
      MatchRulesSnapshot snapshot =
          MatchRulesSnapshot.of(
              MatchRulesSnapshot.Phase.COMPLETED,
              chinese,
              side(new EngineParticipantIdentity("b", "BlackEngine"), chinese),
              side(new EngineParticipantIdentity("w", "WhiteEngine"), positional),
              MatchRulesAdmission.Outcome.REJECT);
      GameInfo info = Lizzie.board.getHistory().getGameInfo();
      EngineGameRecordContext context =
          new EngineGameRecordContext(
              null,
              new EngineGameParticipantDescriptor(
                  new EngineParticipantIdentity("b", "BlackEngine"),
                  "BlackEngine",
                  true,
                  false,
                  KataGoRules.Summary.CHINESE),
              new EngineGameParticipantDescriptor(
                  new EngineParticipantIdentity("w", "WhiteEngine"),
                  "WhiteEngine",
                  true,
                  false,
                  KataGoRules.Summary.CHINESE),
              snapshot);
      info.attachEngineGameRecordContext(context);
      info.freezeEngineGameRecord(
          new EngineGameRecord(
              context,
              new EngineGameCompletionFacts(
                  new GameOutcome.Resign(EngineGameSide.BLACK), 1, 1, true, 0, 0, 0, 0),
              "BlackEngine",
              "WhiteEngine",
              snapshot));
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.rulesSummary = KataGoRules.Summary.JAPANESE;
      }

      String first = SGFParser.saveToString(false);
      assertTrue(first.contains(bundle.getString("MatchRules.sgf.begin")), first);
      assertTrue(first.contains("ko=SIMPLE"), first);
      assertTrue(first.contains("ko=POSITIONAL"), first);
      assertFalse(first.contains(bundle.getString("LizzieFrame.currentRules.japanese")), first);

      String second = SGFParser.saveToString(false);
      int firstIndex = first.indexOf(bundle.getString("MatchRules.sgf.begin"));
      int secondBegin = second.indexOf(bundle.getString("MatchRules.sgf.begin"));
      long beginLines =
          Lizzie.board.getHistory().getStart().getData().comment.lines()
              .filter(bundle.getString("MatchRules.sgf.begin")::equals)
              .count();
      assertTrue(firstIndex >= 0, first);
      assertTrue(secondBegin >= 0, second);
      assertEquals(1, beginLines, second);
    }
  }

  private static MatchRulesAdmission.SideResult side(
      EngineParticipantIdentity identity, KataGoRules rules) {
    return new MatchRulesAdmission.SideResult(
        identity,
        true,
        true,
        rules,
        rules,
        EngineRulesResult.Status.CONFIRMED,
        EngineRulesResult.Reason.NONE,
        false);
  }
}
