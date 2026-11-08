package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardAnalysisTurnTest {
  @ParameterizedTest
  @ValueSource(strings = {"old 30 100", "old 30 100 4", "old 30 100 4 2"})
  void perspectiveExtensionDoesNotInventMissingScoreFields(String header) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromStringforedit("(;SZ[5]LZ[" + header + "])"));
      BoardData data = Lizzie.board.getData();
      int slots = data.analysisHeaderSlots;
      boolean kata = data.isKataData;
      data.togglePlayerToMove();
      BoardData saved = SGFParser.parseSgf(SGFParser.saveToString(false), true).getStart().getData();
      assertEquals(slots, saved.analysisHeaderSlots);
      assertEquals(kata, saved.isKataData);
      assertTrue(saved.analysisBlackToPlay(false));
      assertEquals(70, saved.winrate);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"(;SZ[5]PL[B])", "(;SZ[5]PL[W])", "(;SZ[5];B[aa])"})
  void turnEditPreservesBothSlotsThroughCopiesAndRepeatedSgfRoundTrips(String sgf) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromStringforedit(sgf));
      Lizzie.board.goToMoveNumber(1);
      BoardData data = Lizzie.board.getData();
      boolean originalTurn = data.blackToPlay;
      populate(data, false, 70);
      populate(data, true, 60);
      Lizzie.leelaz.canAddPlayer = true;
      String comment = SGFParser.formatComment(Lizzie.board.getHistory().getCurrentHistoryNode());
      long revision = data.primaryAnalysisRevision();
      Lizzie.board.changeNextTurn();
      assertEquals(comment, SGFParser.formatComment(Lizzie.board.getHistory().getCurrentHistoryNode()));
      assertEquals(!originalTurn, data.blackToPlay);
      assertTrue(data.primaryAnalysisRevision() > revision);
      assertTrue(data.isChanged);
      assertTrue(data.isChanged2, "Hidden secondary analysis is invalidated too");
      BoardData copy = BoardData.empty(5, 5);
      copy.copyAnalysisPayloadFrom(data);
      for (BoardData snapshot : List.of(data, data.clone(), copy)) {
        assertEquals(originalTurn, snapshot.analysisBlackToPlay(false));
        assertEquals(originalTurn, snapshot.analysisBlackToPlay(true));
        assertEquals(70, snapshot.winrate);
        assertEquals(60, snapshot.winrate2);
      }
      for (int round = 0; round < 2; round++) {
        String saved = SGFParser.saveToString(false);
        assertTrue(saved.contains("analysisTurn=" + (originalTurn ? "B" : "W")), saved);
        BoardHistoryList reopened = SGFParser.parseSgf(saved, true);
        BoardHistoryNode node = reopened.getStart();
        while (node.next().isPresent()) node = node.next().orElseThrow();
        data = node.getData();
        assertEquals(!originalTurn, data.blackToPlay);
        assertEquals(originalTurn, data.analysisBlackToPlay(false));
        assertEquals(originalTurn, data.analysisBlackToPlay(true));
        assertEquals(70, data.winrate);
        assertEquals(60, data.winrate2);
        assertEquals(70, data.bestMoves.get(0).winrate);
        assertEquals(60, data.bestMoves2.get(0).winrate);
        assertTrue(data.isChanged);
        assertTrue(data.isChanged2);
        Lizzie.board.setHistory(reopened);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void freshAnalysisChangesOnlyItsOwnSlotPerspective(boolean secondary) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      BoardData data = Lizzie.board.getData();
      populate(data, false, 70);
      populate(data, true, 60);
      data.togglePlayerToMove();
      populate(data, secondary, 80);
      assertFalse(data.analysisBlackToPlay(secondary));
      assertTrue(data.analysisBlackToPlay(!secondary));
      data.togglePlayerToMove();
      assertFalse(data.analysisBlackToPlay(secondary));
      assertTrue(data.analysisBlackToPlay(!secondary));
      data.clearAnalysisPayloadState();
      populate(data, false, 90);
      populate(data, true, 95);
      assertTrue(data.analysisBlackToPlay(false));
      assertTrue(data.analysisBlackToPlay(true));
    }
  }

  private static void populate(BoardData data, boolean secondary, double winrate) {
    MoveData move = new MoveData();
    move.coordinate = "C3";
    move.variation = List.of("C3");
    move.playouts = 100;
    move.winrate = winrate;
    move.isKataData = true;
    move.scoreMean = 4;
    ArrayList<MoveData> moves = new ArrayList<>(List.of(move));
    if (secondary) data.tryToSetBestMoves2FromEngine(moves, "test", null, 100, null, true);
    else data.tryToSetBestMovesFromEngine(moves, "test", null, 100, null, true);
  }
}
