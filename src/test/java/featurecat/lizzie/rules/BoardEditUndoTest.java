package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.MoveData;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

      BoardHistoryList restored = snapshot.restore(
          false, Lizzie.config.matchAiMoves, Lizzie.config.matchAiPercentsPlayouts);
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
  void historyRestoreIsExactOnBothSidesOfTheDenseBoundAndForAFullBoard() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      // A 5x5 position is stored dense from 23 occupied cells (90% rounded up).
      int[] occupancy = {0, 22, 23, 24, 25};
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[];W[];B[];W[])", true);
      List<BoardData> originals = new ArrayList<>();
      BoardHistoryNode node = history.getStart();
      for (int level : occupancy) {
        BoardData data = node.getData();
        data.stones = new Stone[25];
        data.moveNumberList = new int[25];
        Arrays.fill(data.stones, Stone.EMPTY);
        for (int i = 0; i < level; i++) {
          data.stones[i] = i % 3 == 0 ? Stone.WHITE : i % 7 == 0 ? Stone.EMPTY : Stone.BLACK;
          data.moveNumberList[i] = i % 4 == 0 ? 0 : i % 5 == 0 ? Integer.MIN_VALUE : i + 1;
          if (data.stones[i] == Stone.EMPTY && data.moveNumberList[i] == 0) {
            data.moveNumberList[i] = -i;
          }
        }
        originals.add(data);
        node = node.next().orElse(null);
      }
      assertNull(node, "one history node per occupancy level");
      List<Stone[]> expectedStones = new ArrayList<>();
      List<int[]> expectedNumbers = new ArrayList<>();
      for (BoardData data : originals) {
        expectedStones.add(data.stones.clone());
        expectedNumbers.add(data.moveNumberList.clone());
      }
      BoardHistorySnapshot snapshot = history.copyForRestore(null);
      for (BoardData data : originals) {
        Arrays.fill(data.stones, Stone.BLACK_RECURSED);
        Arrays.fill(data.moveNumberList, 77);
      }
      BoardHistoryList restored = snapshot.restore(
          false, Lizzie.config.matchAiMoves, Lizzie.config.matchAiPercentsPlayouts);
      BoardHistoryNode current = restored.getStart();
      for (int index = 0; index < occupancy.length; index++) {
        BoardData data = current.getData();
        assertArrayEquals(expectedStones.get(index), data.stones, "stones at " + occupancy[index]);
        assertArrayEquals(expectedNumbers.get(index), data.moveNumberList,
            "numbers at " + occupancy[index]);
        assertNotSame(originals.get(index).stones, data.stones);
        assertNotSame(originals.get(index).moveNumberList, data.moveNumberList);
        current = current.next().orElse(null);
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
    BoardData.AnalysisStrings strings = new BoardData.AnalysisStrings();
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

    BoardData.AnalysisStrings nextCapture = new BoardData.AnalysisStrings();
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
  void insertionReplaysContinuationBranchesAndUndoRedoRestoreTheSelectedPosition()
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Board board = env.board();
      board.setHistory(SGFParser.parseSgf(
          "(;SZ[5]KM[6.5]C[root];B[aa]C[first]"
              + "(;W[bb]C[selected](;B[];W[cc])(;B[dd];W[dc])"
              + "(;B[cd];W[ed])(;B[dc];W[de]))"
              + "(;W[ab]C[other];B[ac]))", true));
      BoardHistoryNode parent = board.getHistory().getStart().next().orElseThrow();
      BoardHistoryNode selected = parent.getVariation(0).orElseThrow();
      BoardHistoryNode other = parent.getVariation(1).orElseThrow();
      board.getHistory().setHead(selected);
      String before = SGFParser.saveToString(false);
      var gameInfo = board.getHistory().getGameInfo();
      Lizzie.config.playSound = true;

      Lizzie.frame.insertMove(new int[] {4, 4}, true);

      BoardHistoryNode inserted = env.current();
      assertTrue(RulesLayerTestHarness.sameLastMove(inserted.getData(), 4, 4));
      assertEquals(Stone.BLACK, inserted.getData().lastMoveColor);
      assertEquals(3, inserted.getData().moveNumber);
      BoardHistoryNode replayedSelection = inserted.previous().orElseThrow();
      assertTrue(RulesLayerTestHarness.sameLastMove(replayedSelection.getData(), 1, 1));
      assertSame(parent, replayedSelection.previous().orElseThrow());
      assertTrue(parent.getVariations().contains(other));
      assertEquals("other", other.getData().comment);
      assertEquals(4, inserted.numberOfChildren());
      BoardHistoryNode pass = inserted.getVariation(0).orElseThrow();
      assertTrue(pass.getData().isPassNode());
      assertEquals(Stone.BLACK, pass.getData().lastMoveColor);
      assertEquals(4, pass.getData().moveNumber);
      BoardHistoryNode mainEnd = pass.next().orElseThrow();
      assertTrue(RulesLayerTestHarness.sameLastMove(mainEnd.getData(), 2, 2));
      assertEquals(Stone.WHITE, mainEnd.getData().lastMoveColor);
      assertEquals(Stone.BLACK, mainEnd.getData().stones[Board.getIndex(4, 4)]);
      BoardHistoryNode branch = inserted.getVariation(1).orElseThrow();
      assertTrue(RulesLayerTestHarness.sameLastMove(branch.getData(), 3, 3));
      assertEquals(Stone.BLACK, branch.getData().lastMoveColor);
      assertTrue(RulesLayerTestHarness.sameLastMove(
          branch.next().orElseThrow().getData(), 3, 2));
      BoardHistoryNode third = inserted.getVariation(2).orElseThrow();
      assertTrue(RulesLayerTestHarness.sameLastMove(third.getData(), 2, 3));
      assertTrue(RulesLayerTestHarness.sameLastMove(
          third.next().orElseThrow().getData(), 4, 3));
      BoardHistoryNode fourth = inserted.getVariation(3).orElseThrow();
      assertTrue(RulesLayerTestHarness.sameLastMove(fourth.getData(), 3, 2));
      assertTrue(RulesLayerTestHarness.sameLastMove(
          fourth.next().orElseThrow().getData(), 3, 4));
      assertSame(gameInfo, board.getHistory().getGameInfo());
      assertTrue(Lizzie.config.playSound);
      String after = SGFParser.saveToString(false);

      board.cleanedit();
      assertEquals(before, SGFParser.saveToString(false));
      assertTrue(RulesLayerTestHarness.sameLastMove(env.current().getData(), 1, 1));
      assertEquals("selected", env.current().getData().comment);
      assertSame(gameInfo, board.getHistory().getGameInfo());
      board.reedit();
      assertEquals(after, SGFParser.saveToString(false));
      assertTrue(RulesLayerTestHarness.sameLastMove(env.current().getData(), 4, 4));
      assertEquals(4, env.current().numberOfChildren());
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
  @CsvSource({"false, false", "false, true", "true, false", "true, true"})
  void undoRedoRestoresSetupModeOnlyWhileOffline(boolean engineStarted, boolean redo)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Board board = Lizzie.board;
      board.setHistory(SGFParser.parseSgf("(;SZ[5]AB[aa])", true));
      board.setSetupMode(true);
      board.saveListForEdit();
      assertTrue(board.setupPlaceStone(1, 1, Stone.WHITE));

      if (redo) {
        board.cleanedit();
        assertTrue(board.isSetupMode());
        assertEquals(Stone.EMPTY, board.getData().stones[Board.getIndex(1, 1)]);
      }

      board.setSetupMode(false);
      EngineManager.isEmpty = !engineStarted;
      if (redo) board.reedit();
      else board.cleanedit();
      assertEquals(!engineStarted, board.isSetupMode(),
          "undo/redo must not restore offline setup input routing over an active engine");
      assertEquals(redo ? Stone.WHITE : Stone.EMPTY,
          board.getData().stones[Board.getIndex(1, 1)]);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void trialRestoreRenumbersBothEnginesWithoutReplacingBranchCaches(boolean alreadyOrdinary)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Board board = Lizzie.board;
      BoardHistoryList history = board.getHistory();
      BoardHistoryNode root = history.getStart();
      root.getData().moveNumber = 30;
      BoardHistoryList.TrialNumbering numbering = new BoardHistoryList.TrialNumbering(history);
      root.getData().moveNumber = 0;
      root.getData().setPlayouts(100);
      root.getData().setPlayouts2(200);
      root.getData().winrate = 50;
      root.getData().winrate2 = 50;
      Lizzie.config.matchAiMoves = 2;
      Lizzie.config.matchAiPercentsPlayouts = 50;
      root.getData().bestMoves = new ArrayList<>(List.of(
          matchCandidate(2, 2, 100), matchCandidate(1, 1, 60)));
      root.getData().bestMoves2 = new ArrayList<>(List.of(
          matchCandidate(1, 1, 100), matchCandidate(2, 2, 60)));
      history.place(1, 1, Stone.BLACK, true);
      BoardHistoryNode main = history.getCurrentHistoryNode();
      main.getData().setPlayouts(110);
      main.getData().setPlayouts2(210);
      board.updateMovelist(main);
      board.updateMovelist2(main);
      history.setHead(root);
      history.place(2, 2, Stone.BLACK, true);
      BoardHistoryNode branch = history.getCurrentHistoryNode();
      branch.getData().setPlayouts(120);
      branch.getData().setPlayouts2(220);
      board.updateMovelist(branch);
      board.updateMovelist2(branch);
      List<NodeInfo> originals = List.of(
          root.nodeInfo, root.nodeInfoMain, root.nodeInfo2, root.nodeInfoMain2);
      for (NodeInfo info : originals) {
        assertEquals(1, info.moveNum);
        if (alreadyOrdinary) info.moveNum = 31;
      }

      BoardHistorySnapshot snapshot = history.copyForRestore(numbering);
      Lizzie.config.matchAiPercentsPlayouts = 70;
      BoardHistoryList restored = snapshot.restore(false, 2, 70);
      BoardHistoryNode restoredRoot = restored.getStart();
      List<NodeInfo> copies = List.of(restoredRoot.nodeInfo, restoredRoot.nodeInfoMain,
          restoredRoot.nodeInfo2, restoredRoot.nodeInfoMain2);
      for (int i = 0; i < copies.size(); i++) {
        NodeInfo original = originals.get(i);
        NodeInfo copy = copies.get(i);
        assertEquals(31, copy.moveNum);
        assertEquals(alreadyOrdinary ? 31 : 1, original.moveNum, "capture leaves live caches alone");
        assertNotSame(original, copy);
        assertArrayEquals(original.coords, copy.coords);
        assertEquals(original.playouts, copy.playouts);
        assertEquals(original.previousPlayouts, copy.previousPlayouts);
        assertEquals(original.analyzed, copy.analyzed);
        assertEquals(original.winrate, copy.winrate);
        assertEquals(original.diffWinrate, copy.diffWinrate);
      }
      assertArrayEquals(new int[] {2, 2}, restoredRoot.nodeInfo.coords);
      assertArrayEquals(new int[] {1, 1}, restoredRoot.nodeInfoMain.coords);
      assertArrayEquals(new int[] {2, 2}, restoredRoot.nodeInfo2.coords);
      assertArrayEquals(new int[] {1, 1}, restoredRoot.nodeInfoMain2.coords);
      assertFalse(restoredRoot.nodeInfoMain.isMatchAi);
      assertFalse(restoredRoot.nodeInfo2.isMatchAi);
      assertTrue(restoredRoot.nodeInfo.isMatchAi);
      assertTrue(restoredRoot.nodeInfoMain2.isMatchAi);
      assertSame(restoredRoot.getVariation(1).orElseThrow(), restored.getCurrentHistoryNode());
    }
  }

  @ParameterizedTest
  @CsvSource({
      "undo, 2, 70, 50", "redo, 2, 70, 50", "trial, 2, 70, 50",
      "undo, 1, 50, 50", "redo, 1, 50, 50", "trial, 1, 50, 50",
      "undo, 2, 50, 50", "redo, 2, 50, 50", "trial, 2, 50, 50",
      "undo, 2, 50, 70", "redo, 2, 50, 70", "trial, 2, 50, 70"
  })
  void restoresMatchJudgmentsUsingCurrentSettings(
      String action, int candidates, double percent, double initialPercent)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Board board = Lizzie.board;
      BoardHistoryList history = board.getHistory();
      BoardHistoryNode root = history.getStart();
      Lizzie.config.matchAiMoves = 2;
      Lizzie.config.matchAiPercentsPlayouts = initialPercent;
      root.getData().winrate = root.getData().winrate2 = 50;
      root.getData().setPlayouts(160);
      root.getData().setPlayouts2(160);
      history.place(1, 1, Stone.BLACK, true);
      BoardHistoryNode main = history.getCurrentHistoryNode();
      main.getData().setPlayouts(100);
      main.getData().setPlayouts2(100);
      history.setHead(root);
      history.place(2, 2, Stone.BLACK, true);
      BoardHistoryNode branch = history.getCurrentHistoryNode();
      branch.getData().setPlayouts(100);
      branch.getData().setPlayouts2(100);
      root.getData().bestMoves = new ArrayList<>(List.of(
          matchCandidate(2, 2, 100), matchCandidate(1, 1, 60)));
      root.getData().bestMoves2 = new ArrayList<>(List.of(
          matchCandidate(1, 1, 100), matchCandidate(2, 2, 60)));
      populateBranchMatchCaches(board, root, main, branch);
      assertEquals(initialPercent <= 60, root.nodeInfoMain.isMatchAi);
      assertEquals(initialPercent <= 60, root.nodeInfo2.isMatchAi);

      Board.GameStateSnapshot trial = null;
      if (action.equals("trial")) trial = board.captureGameState();
      else {
        board.saveListForEdit();
        branch.getData().comment = "edited";
        if (action.equals("redo")) board.cleanedit();
      }

      // The settings dialog invalidates only the active tree; saved undo/trial trees still exist.
      board.clearNodeInfo(board.getHistory().getStart());
      Lizzie.config.matchAiMoves = candidates;
      Lizzie.config.matchAiPercentsPlayouts = percent;
      if (action.equals("trial")) {
        // Materialization precedes adoption of the saved geometry; candidate names use that
        // geometry even if the user changed board size while the snapshot was held.
        Board.boardWidth = Board.boardHeight = 19;
        board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
      }
      if (action.equals("undo")) board.cleanedit();
      else if (action.equals("redo")) board.reedit();
      else board.restoreGameState(trial);

      BoardHistoryNode restored = board.getHistory().getStart();
      boolean secondMatches = candidates >= 2 && percent <= 60;
      assertEquals(secondMatches, restored.nodeInfoMain.isMatchAi, "primary mainline judgment");
      assertEquals(secondMatches, restored.nodeInfo2.isMatchAi, "secondary branch judgment");
      assertTrue(restored.nodeInfo.isMatchAi, "primary branch keeps its own candidate");
      assertTrue(restored.nodeInfoMain2.isMatchAi, "secondary mainline keeps its own candidate");
      assertTrue(restored.nodeInfo.isBest);
      assertFalse(restored.nodeInfoMain.isBest);
      assertFalse(restored.nodeInfo2.isBest);
      assertTrue(restored.nodeInfoMain2.isBest);
      assertEquals(0.6, restored.nodeInfoMain.percentsMatch);
      assertEquals(0.6, restored.nodeInfo2.percentsMatch);
      assertArrayEquals(new int[] {2, 2}, restored.nodeInfo.coords);
      assertArrayEquals(new int[] {1, 1}, restored.nodeInfoMain.coords);
      assertEquals(160, restored.getData().getPlayouts());
      assertEquals(160, restored.getData().getPlayouts2());
      assertEquals(60, restored.getData().bestMoves.get(1).playouts);
      assertEquals(60, restored.getData().bestMoves2.get(1).playouts);
      // No new visits or engine switch may be needed to make a restored judgment correct.
      board.updateMovelist(restored.getVariation(1).orElseThrow());
      board.updateMovelist2(restored.getVariation(1).orElseThrow());
      assertEquals(secondMatches, restored.nodeInfoMain.isMatchAi);
      assertEquals(secondMatches, restored.nodeInfo2.isMatchAi);
    }
  }

  private static MoveData matchCandidate(int x, int y, int visits) {
    MoveData move = new MoveData();
    move.coordinate = Board.convertCoordinatesToName(x, y);
    move.playouts = visits;
    return move;
  }

  private static void populateBranchMatchCaches(Board board, BoardHistoryNode root,
      BoardHistoryNode main, BoardHistoryNode branch) {
    root.getData().playoutsChanged = true;
    board.updateMovelist(main);
    board.updateMovelist2(main);
    root.getData().playoutsChanged = true;
    // Equal visits must not accidentally make the fixture reuse the mainline's branch cache.
    root.nodeInfo2.playouts = -1;
    board.updateMovelist(branch);
    board.updateMovelist2(branch);
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

      BoardHistoryList restored = history.copyForRestore(numbering)
          .restore(false, Lizzie.config.matchAiMoves, Lizzie.config.matchAiPercentsPlayouts);
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
      BoardHistoryNode restored = history.copyForRestore(numbering)
          .restore(false, Lizzie.config.matchAiMoves, Lizzie.config.matchAiPercentsPlayouts).getStart();
      assertEquals(40, restored.getData().moveNumber);
      assertEquals(List.of(41, 40, 41, 40), restored.getVariations().stream()
          .map(node -> node.getData().moveNumber).toList());
      assertEquals(0, history.copyForRestore(null)
          .restore(false, Lizzie.config.matchAiMoves, Lizzie.config.matchAiPercentsPlayouts).getData().moveNumber);
      BoardHistoryList replacement = new BoardHistoryList(BoardData.empty(5, 5));
      replacement.getData().moveNumber = 99;
      assertEquals(99, replacement.copyForRestore(numbering)
          .restore(false, Lizzie.config.matchAiMoves, Lizzie.config.matchAiPercentsPlayouts).getData().moveNumber,
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
