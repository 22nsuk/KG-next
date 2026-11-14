package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.MoveData;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardEditUndoTest {
  @Test
  void restoreSharesRepeatedAnalysisStringsWhileKeepingCandidatesAndListsIndependent()
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[])", true);
      BoardData root = history.getStart().getData();
      BoardData child = history.getStart().next().orElseThrow().getData();
      List<String> expected = Arrays.asList("C3", "Q16", null, "", " D4", "d4", "한글");
      List<List<MoveData>> originals = new ArrayList<>();
      for (BoardData data : List.of(root, child)) {
        data.bestMoves = new ArrayList<>();
        data.bestMoves2 = new ArrayList<>();
        data.bestMovesOutOfRange = new ArrayList<>();
        data.bestMoves2OutOfRange = new ArrayList<>();
        for (List<MoveData> moves : List.of(data.bestMoves, data.bestMoves2,
            data.bestMovesOutOfRange, data.bestMoves2OutOfRange)) {
          MoveData move = candidate(1);
          move.coordinate = new String("C3");
          move.variation = new ArrayList<>();
          for (String value : expected) {
            move.variation.add(value == null ? null : new String(value));
          }
          move.pvVisits = new ArrayList<>(Arrays.asList(new String("001"), "1", null, "+1"));
          move.pvEdgeVisits = new ArrayList<>(Arrays.asList(new String("001"), " 1 ", "1.0"));
          moves.add(move);
          moves.add(null);
          MoveData empty = new MoveData();
          empty.pvVisits = new ArrayList<>();
          moves.add(empty);
          originals.add(moves);
        }
      }
      BoardHistorySnapshot snapshot = history.copyForRestore(null);
      for (List<MoveData> moves : originals) {
        moves.get(0).coordinate = "pass";
        moves.get(0).variation.clear();
        moves.get(0).pvVisits.set(0, "changed");
        moves.get(0).pvEdgeVisits.clear();
      }

      BoardHistoryList restored = snapshot.restore();
      List<MoveData> copies = new ArrayList<>();
      for (BoardData data : List.of(restored.getStart().getData(),
          restored.getStart().next().orElseThrow().getData())) {
        for (List<MoveData> moves : List.of(data.bestMoves, data.bestMoves2,
            data.bestMovesOutOfRange, data.bestMoves2OutOfRange)) {
          MoveData move = moves.get(0);
          assertEquals("C3", move.coordinate);
          assertEquals(expected, move.variation);
          assertEquals(Arrays.asList("001", "1", null, "+1"), move.pvVisits);
          assertEquals(List.of("001", " 1 ", "1.0"), move.pvEdgeVisits);
          assertSame(copies.isEmpty() ? move.coordinate : copies.get(0).coordinate,
              move.variation.get(0), "repeated immutable tokens do not need separate objects");
          assertSame(move.pvVisits.get(0), move.pvEdgeVisits.get(0));
          assertNull(moves.get(1));
          assertNull(moves.get(2).variation);
          assertNull(moves.get(2).pvEdgeVisits);
          moves.get(2).pvVisits.add("writable");
          copies.add(move);
        }
      }
      for (int i = 0; i < copies.size(); i++) {
        MoveData move = copies.get(i);
        assertNotSame(originals.get(i).get(0), move);
        assertEquals(expected, move.variation, "editing another restored candidate stays local");
        assertEquals("001", move.pvVisits.get(0));
        assertEquals("001", move.pvEdgeVisits.get(0));
        move.variation.set(0, "pass");
        move.pvVisits.clear();
        move.pvEdgeVisits.add("new");
        assertEquals("changed", originals.get(i).get(0).pvVisits.get(0));
      }
    }
  }

  @Test
  void analysisStringReuseIsBoundedAndPreservesUnusualValuesAfterTheLimit() {
    BoardData source = BoardData.empty(5, 5);
    MoveData move = new MoveData();
    move.variation = new ArrayList<>();
    // These eight distinct strings have the same hash; a cache miss must keep the original text.
    move.variation.addAll(List.of("AaAaAa", "AaAaBB", "AaBBAa", "AaBBBB",
        "BBAaAa", "BBAaBB", "BBBBAa", "BBBBBB"));
    int tokensStart = move.variation.size();
    for (int repeat = 0; repeat < 2; repeat++) {
      for (int i = 0; i < 4300; i++) move.variation.add("token-" + i);
    }
    move.variation.addAll(Arrays.asList(null, "x".repeat(64), "x".repeat(65),
        "9223372036854775808", "-0", "0", "polygenelubricants"));
    int expectedSize = move.variation.size();
    source.bestMoves.add(move);
    BoardData.RestoreStrings strings = new BoardData.RestoreStrings();
    BoardData copy = source.copyRestoreMetadata(strings);
    assertEquals(move.variation, copy.bestMoves.get(0).variation);
    int reused = 0;
    for (int i = 0; i < 4300; i++) {
      if (copy.bestMoves.get(0).variation.get(tokensStart + i)
          == copy.bestMoves.get(0).variation.get(tokensStart + i + 4300)) {
        reused++;
      }
    }
    assertTrue(reused > 0 && reused <= 4096, "reuse must stay within the bounded cache");
    assertSame(copy.bestMoves.get(0).variation.get(tokensStart),
        copy.bestMoves.get(0).variation.get(tokensStart + 4300),
        "existing cache entries are not evicted");
    move.variation.clear();
    assertEquals(expectedSize, copy.bestMoves.get(0).variation.size());

    BoardData.RestoreStrings nextCapture = new BoardData.RestoreStrings();
    move.variation.addAll(List.of(new String("x".repeat(64)), new String("x".repeat(64)),
        new String("x".repeat(65)), new String("x".repeat(65))));
    BoardData next = source.copyRestoreMetadata(nextCapture);
    assertEquals(move.variation, next.bestMoves.get(0).variation);
    assertSame(next.bestMoves.get(0).variation.get(0), next.bestMoves.get(0).variation.get(1));
    assertNotSame(next.bestMoves.get(0).variation.get(2), next.bestMoves.get(0).variation.get(3),
        "long tokens are retained without hashing/interning");
  }

  @Test
  void undoRestoresIndependentPlayableAnalysisAndRawProperties() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Board board = Lizzie.board;
      board.setHistory(SGFParser.parseSgf(
          "(;SZ[5:7]AB[aa:ba]AW[eg]XX[first,raw][second]"
              + "(;W[cc]TR[bb]LB[cc:label,one];B[])"
              + "(;W[dd]C[original]YY[a][b];AE[aa]AB[ee]PL[W];W[]))", true));
      BoardHistoryNode selected = board.getHistory().getStart().getVariation(1).orElseThrow();
      board.getHistory().setHead(selected);
      BoardData original = selected.getData();
      AnalysisProvenance source = new AnalysisProvenance(
          "KataGo", "test", "model", "", "", "Japanese", 6.5, 0.5, 0.25, "rank_5d");
      original.setPrimaryAnalysisProvenance(source);
      original.setSecondaryAnalysisProvenance(source);
      original.setPrimaryOwnershipProvenance(source);
      original.setSecondaryOwnershipProvenance(source);
      original.bestMoves = new ArrayList<>(List.of(candidate(1)));
      original.bestMoves2 = new ArrayList<>(List.of(candidate(2)));
      original.bestMovesOutOfRange = new ArrayList<>(List.of(candidate(3)));
      original.bestMoves2OutOfRange = new ArrayList<>(List.of(candidate(4)));
      original.setPlayouts(123);
      original.setPlayouts2(87);
      original.rootVisits = 120;
      original.rootVisits2 = 80;
      original.estimateArray = new ArrayList<>(List.of(0.2, -0.2));
      original.estimateArray2 = new ArrayList<>(List.of(0.4, -0.4));
      original.isChanged = true;
      original.isChanged2 = false;
      original.setAnalysisBlackToPlay(false, !original.blackToPlay);
      Object run = new Object();
      original.markPrimaryAnalysisRun(run);
      BoardData rootData = board.getHistory().getStart().getData();
      rootData.setPlayouts(456);
      rootData.bestMoves = new ArrayList<>(List.of(candidate(5)));
      rootData.markPrimaryAnalysisRun(run);
      assertTrue(rootData.hasReusablePrimaryAnalysis(400, false, run));
      Stone[] stones = original.stones.clone();
      int[] grid = original.moveNumberList.clone();
      Zobrist hash = original.zobrist.clone();
      selected.addExtraStones(4, 6, false);
      selected.nodeInfo.coords = new int[] {4, 6};
      selected.nodeInfo.winrate = 64;
      selected.nodeInfo.analyzed = true;
      selected.nodeInfoMain2.scoreLead = -0.0;

      board.saveListForEdit();
      for (List<MoveData> moves : List.of(original.bestMoves, original.bestMoves2,
          original.bestMovesOutOfRange, original.bestMoves2OutOfRange)) {
        MoveData move = moves.get(0);
        move.playouts = 999;
        move.variation.set(0, "pass");
        move.pvVisits.clear();
        move.pvEdgeVisits.clear();
        move.movesEstimateArray.set(0, 1.0);
      }
      original.estimateArray.set(0, 1.0);
      original.estimateArray2.clear();
      original.stones[0] = Stone.EMPTY;
      original.moveNumberList[0] = 999;
      original.zobrist.toggleStone(0, 0, Stone.BLACK);
      original.setProperty("YY", "changed");
      original.comment = "edited";
      original.isChanged = false;
      selected.extraStones.get(0).x = 1;
      selected.nodeInfo.coords[0] = 0;
      selected.nodeInfo.winrate = 99;
      selected.clear();
      board.cleanedit();

      BoardData restored = board.getData();
      assertNotSame(original, restored);
      assertArrayEquals(stones, restored.stones);
      assertArrayEquals(grid, restored.moveNumberList);
      assertEquals(hash, restored.zobrist);
      assertEquals("original", restored.comment);
      assertEquals(List.of("a", "b"), restored.getPropertyValues("YY"));
      assertEquals(List.of("first,raw", "second"),
          board.getHistory().getStart().getData().getPropertyValues("XX"));
      BoardHistoryNode main = board.getHistory().getStart().getVariation(0).orElseThrow();
      assertEquals(List.of("bb"), main.getData().getPropertyValues("TR"));
      assertEquals(List.of("cc:label,one"), main.getData().getPropertyValues("LB"));
      assertSame(board.getHistory().getStart().getVariation(1).orElseThrow(),
          board.getHistory().getCurrentHistoryNode());
      assertTrue(board.getHistory().getCurrentHistoryNode().next().orElseThrow()
          .getData().isSnapshotNode());
      assertNotNull(board.getHistory().getCurrentHistoryNode().extraStones,
          "undo must retain extra-stone state that has no SGF representation");
      assertEquals(4, board.getHistory().getCurrentHistoryNode().extraStones.get(0).x);
      BoardHistoryNode restoredNode = board.getHistory().getCurrentHistoryNode();
      assertArrayEquals(new int[] {4, 6}, restoredNode.nodeInfo.coords);
      assertEquals(64, restoredNode.nodeInfo.winrate);
      assertTrue(restoredNode.nodeInfo.analyzed);
      assertEquals(Double.doubleToRawLongBits(-0.0),
          Double.doubleToRawLongBits(restoredNode.nodeInfoMain2.scoreLead));
      assertNotNull(restoredNode.nodeInfoMain);
      assertNotNull(restoredNode.nodeInfo2);
      assertCandidateEquals(candidate(1), restored.bestMoves.get(0));
      assertCandidateEquals(candidate(2), restored.bestMoves2.get(0));
      assertCandidateEquals(candidate(3), restored.bestMovesOutOfRange.get(0));
      assertCandidateEquals(candidate(4), restored.bestMoves2OutOfRange.get(0));
      assertEquals(List.of(0.2, -0.2), restored.estimateArray);
      assertEquals(List.of(0.4, -0.4), restored.estimateArray2);
      assertEquals(source, restored.primaryAnalysisProvenance());
      assertEquals(source, restored.secondaryAnalysisProvenance());
      assertEquals(source, restored.primaryOwnershipProvenance());
      assertEquals(source, restored.secondaryOwnershipProvenance());
      assertEquals(123, restored.getPlayouts());
      assertEquals(87, restored.getPlayouts2());
      assertEquals(120, restored.rootVisits);
      assertEquals(80, restored.rootVisits2);
      assertTrue(restored.isChanged);
      assertFalse(restored.isChanged2);
      assertEquals(!restored.blackToPlay, restored.analysisBlackToPlay(false));
      assertFalse(restored.hasReusablePrimaryAnalysis(1, false, run));
      BoardData restoredRoot = board.getHistory().getStart().getData();
      assertTrue(restoredRoot.hasReusablePrimaryAnalysis(400, false));
      assertFalse(restoredRoot.hasReusablePrimaryAnalysis(400, false, run),
          "saved results must not authorize the previous live reassessment run");
      board.getHistory().place(4, 4, Stone.BLACK, true);
      assertEquals(Stone.BLACK, board.getData().stones[Board.getIndex(4, 4)],
          "restored hash/grid/position must still support normal play");
      assertTrue(RulesLayerTestHarness.sameLastMove(board.getData(), 4, 4));
    }
  }

  @Test
  void oneStepUndoRedoKeepsCurrentGameInfoAndRulesAndClearRollbackKeepsThePendingEdit()
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Board board = Lizzie.board;
      assertTrue(SGFParser.loadFromStringforedit("(;SZ[5]KM[6.5]RU[Japanese]C[before];B[aa])"));
      board.saveListForEdit();
      board.getData().comment = "after";
      var info = board.getHistory().getGameInfo();
      info.setKomiNoMenu(8.5);
      var rules = board.getHistory().publishManualRules(KataGoRules.parse("Chinese").orElseThrow());
      Board.ClearStateSnapshot rollback = board.captureClearState();
      board.cleanedittemp();
      assertFalse(board.canUndoEdit());
      board.restoreClearState(rollback);
      assertTrue(board.canUndoEdit());

      board.cleanedit();
      assertEquals("before", board.getData().comment);
      assertSame(info, board.getHistory().getGameInfo());
      assertSame(rules, board.getHistory().captureSessionRules());
      assertEquals(8.5, board.getHistory().getGameInfo().getKomi());
      assertFalse(board.canUndoEdit());
      assertTrue(board.canRedoEdit());
      BoardHistoryList undone = board.getHistory();
      board.cleanedit();
      assertSame(undone, board.getHistory(), "undo stays a single step");
      board.reedit();
      assertEquals("after", board.getData().comment);
      assertSame(info, board.getHistory().getGameInfo());
      assertSame(rules, board.getHistory().captureSessionRules());
      assertFalse(board.canRedoEdit());
      board.cleanedit();
      board.saveListForEdit();
      board.getData().comment = "replacement";
      assertFalse(board.canRedoEdit(), "a new edit replaces redo");
      board.cleanedit();
      assertEquals("before", board.getData().comment);
      board.cleanedittemp();
      assertFalse(board.canUndoEdit());
      assertFalse(board.canRedoEdit());
    }
  }

  @Test
  void restoreTransfersPlayableGeometryAndSetupOnce() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Board board = Lizzie.board;
      board.setHistory(SGFParser.parseSgf("(;SZ[5:7]AB[aa][bb]KM[6.5];W[cc])", true));
      board.getHistory().setHead(board.getHistory().getStart().next().orElseThrow());
      board.hasStartStone = true;
      Movelist stone = new Movelist();
      stone.x = 1;
      stone.y = 1;
      stone.isblack = true;
      board.startStonelist = new ArrayList<>(List.of(stone));
      board.isKataBoard = true;
      Board.GameStateSnapshot saved = board.captureGameState();
      stone.x = 4;
      board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
      board.hasStartStone = false;
      board.restoreGameState(saved);
      assertEquals(5, Board.boardWidth);
      assertEquals(7, Board.boardHeight);
      assertEquals(35, board.getData().moveNumberList.length);
      assertEquals(1, board.getData().moveNumber);
      assertTrue(board.hasStartStone);
      assertEquals(1, board.startStonelist.get(0).x);
      assertTrue(board.isKataBoard);
      assertEquals(6.5, board.getHistory().getGameInfo().getKomi());
      assertTrue(board.isEngineAlignmentPending());
      assertThrows(IllegalArgumentException.class, () -> board.restoreGameState(saved));
      board.getData().setProperty("TR", "aa");
      board.getData().bestMoves.add(candidate(1));
      board.getData().bestMoves2.add(candidate(2));
      assertEquals("aa", board.getData().getProperty("TR"));
      assertEquals(1, board.getData().bestMoves.size());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void trialNumberingKeepsMnBranchesPassesAndSetupNodes(boolean branchNumbers) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = branchNumbers;
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[5];B[aa]MN[7];W[];AE[aa]AB[bb]PL[B];B[cc](;W[dd])(;W[ee]))", true);
      BoardHistoryNode anchor = history.getStart().next().orElseThrow().next().orElseThrow()
          .next().orElseThrow().next().orElseThrow();
      history.setHead(anchor);
      BoardHistoryList.TrialNumbering numbering = new BoardHistoryList.TrialNumbering(history);
      anchor.getData().moveNumber = 0;
      history.place(0, 4, Stone.WHITE, true);
      // Actual Board.place's trial marker; its production path is covered by the protocol test.
      history.getData().moveNumberList[Board.getIndex(0, 4)] = -1;
      history.pass(Stone.BLACK);
      BoardData pass = history.getData();
      history.addOrGoto(BoardData.snapshot(pass.stones, java.util.Optional.empty(), Stone.EMPTY,
          pass.blackToPlay, pass.zobrist, pass.moveNumber, pass.moveNumberList,
          pass.blackCaptures, pass.whiteCaptures, 0, 0));

      BoardHistoryList restored = history.copyForRestore(numbering).restore();
      assertTrue(restored.getData().isSnapshotNode());
      assertEquals(5, restored.getData().moveNumber);
      assertEquals(branchNumbers ? 1 : 4,
          restored.getData().moveNumberList[Board.getIndex(0, 4)]);
      assertEquals(0, restored.getData().moveNumberList[Board.getIndex(0, 0)],
          "setup removal and branch hiding must remain hidden");
      BoardHistoryNode first = restored.getStart().next().orElseThrow();
      assertEquals("7", first.getData().getProperty("MN"));
      assertEquals(7, first.getData().moveMNNumber);
      BoardHistoryNode restoredAnchor = first.next().orElseThrow().next().orElseThrow()
          .next().orElseThrow();
      assertEquals(3, restoredAnchor.getData().moveNumber);
      assertEquals(3, restoredAnchor.numberOfChildren());
      assertEquals(4, restoredAnchor.getVariation(1).orElseThrow().getData().moveNumber);
      assertEquals(0, anchor.getData().moveNumber, "normalization only changes the private copy");
      assertEquals(-1, history.getData().moveNumberList[Board.getIndex(0, 4)]);
    }
  }

  @Test
  void trialNumberingKeepsSynchronizedRootAndBothDummyConventions() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = Lizzie.board.getHistory();
      BoardHistoryNode root = history.getStart();
      root.getData().moveNumber = 40;
      addDummy(root, 41);
      addDummy(root, 40);
      BoardHistoryList.TrialNumbering numbering = new BoardHistoryList.TrialNumbering(history);
      root.getData().moveNumber = 0;
      addDummy(root, 1);
      addDummy(root, 0);
      BoardHistoryNode restored = history.copyForRestore(numbering).restore().getStart();
      assertEquals(40, restored.getData().moveNumber);
      assertEquals(List.of(41, 40, 41, 40), restored.getVariations().stream()
          .map(node -> node.getData().moveNumber).toList());
      assertEquals(0, history.copyForRestore(null).restore().getData().moveNumber);
      BoardHistoryList replacement = new BoardHistoryList(BoardData.empty(5, 5));
      replacement.getData().moveNumber = 99;
      assertEquals(99, replacement.copyForRestore(numbering).restore().getData().moveNumber,
          "a trial cannot renumber a different document");
    }
  }

  private static void addDummy(BoardHistoryNode parent, int moveNumber) {
    BoardData source = parent.getData();
    BoardData dummy = BoardData.pass(source.stones, Stone.WHITE, true, source.zobrist, moveNumber,
        source.moveNumberList, source.blackCaptures, source.whiteCaptures, 0, 0);
    dummy.dummy = true;
    parent.addOrGoto(dummy, true);
  }

  private static MoveData candidate(int value) {
    MoveData move = new MoveData();
    move.coordinate = "C3";
    move.playouts = 100 + value;
    move.edgeVisits = 90 + value;
    move.winrate = 51.25 + value;
    move.variation = new ArrayList<>(List.of("C3", "D4"));
    move.pvVisits = new ArrayList<>(List.of("100", "80"));
    move.pvEdgeVisits = new ArrayList<>(List.of("90", "70"));
    move.lcb = 49.25;
    move.policy = 0.125;
    move.scoreMean = 2.25;
    move.scoreStdev = 1.75;
    move.isKataData = true;
    move.isSaiData = true;
    move.scoreMeanIsBlackPerspective = true;
    move.order = value;
    move.isNextMove = true;
    move.bestWinrate = 55.25;
    move.bestScoreMean = 3.25;
    move.lastTimeUnlimited = true;
    move.lastTimeUnlimitedTime = 123456L;
    move.isSymmetry = true;
    move.movesEstimateArray = new ArrayList<>(List.of(0.5, -0.5));
    return move;
  }

  private static void assertCandidateEquals(MoveData expected, MoveData actual) throws Exception {
    for (var field : MoveData.class.getFields()) {
      if (!Modifier.isStatic(field.getModifiers()))
        assertEquals(field.get(expected), field.get(actual), field.getName());
    }
  }
}
