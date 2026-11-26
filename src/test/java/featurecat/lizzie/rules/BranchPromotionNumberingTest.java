package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BranchPromotionNumberingTest {
  @ParameterizedTest
  @CsvSource({"setMain,true", "setMain,false", "up,true", "up,false", "down,true", "down,false"})
  void promotingEitherWayCountsMovesAndPassesAcrossSetup(String operation, boolean restart)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = restart;
      Board board = env.board();
      board.setHistory(SGFParser.parseSgf(
          "(;SZ[5];B[aa];W[bb](;B[ab];AE[bb];W[ed])"
              + "(;AE[aa]AB[cc]PL[B];AW[dd];B[];W[ee]))", true));
      BoardHistoryNode fork = board.getHistory().getStart().childAtDepth(2);
      BoardHistoryNode oldMain = fork.getVariation(0).orElseThrow();
      BoardHistoryNode setup = fork.getVariation(1).orElseThrow();
      BoardHistoryNode selected = operation.equals("down") ? oldMain : setup.childAtDepth(3);
      board.moveToAnyPosition(selected);
      switch (operation) {
        case "up" -> board.moveBranchUp();
        case "down" -> board.moveBranchDown();
        case "setMain" -> assertTrue(board.setAsMainBranch());
        default -> throw new AssertionError(operation);
      }
      assertSame(selected, board.getHistory().getCurrentHistoryNode());
      assertSame(setup, fork.next().orElseThrow());
      assertSame(oldMain, fork.getVariation(1).orElseThrow());
      for (int round = 0; round < 2; round++) {
        fork = board.getHistory().getStart().childAtDepth(2);
        setup = fork.next().orElseThrow();
        BoardHistoryNode secondSetup = setup.next().orElseThrow();
        BoardHistoryNode pass = secondSetup.next().orElseThrow();
        BoardHistoryNode last = pass.next().orElseThrow();
        assertTrue(setup.getData().isSnapshotNode());
        assertTrue(secondSetup.getData().isSnapshotNode());
        assertTrue(pass.getData().isPassNode());
        assertNumbers(setup, 2, 2);
        assertNumbers(secondSetup, 2, 2);
        assertNumbers(pass, 3, 3);
        assertNumbers(last, 4, 4);
        assertEquals(0, label(last, 0, 0), "removed stone");
        assertEquals(2, label(last, 1, 1), "surviving played stone");
        assertEquals(0, label(last, 2, 2), "black setup stone");
        assertEquals(0, label(last, 3, 3), "white setup stone");
        assertEquals(4, label(last, 4, 4));
        oldMain = fork.getVariation(1).orElseThrow();
        BoardHistoryNode demotedEnd = oldMain.childAtDepth(2);
        assertNumbers(oldMain, 3, restart ? 1 : 3);
        assertNumbers(oldMain.next().orElseThrow(), 3, restart ? 1 : 3);
        assertNumbers(demotedEnd, 4, restart ? 2 : 4);
        assertEquals(0, label(demotedEnd, 1, 1), "removed on demoted branch");
        assertEquals(restart ? 1 : 3, label(demotedEnd, 0, 1));
        assertEquals(4, demotedEnd.moveNumberOfNode());
        assertEquals(4, board.getHistory().mainTrunkLength());
        board.moveToAnyPosition(fork);
        assertEquals(4, board.getHistory().currentBranchLength());
        assertTrue(board.goToMoveNumber(3));
        assertSame(pass, env.current());
        assertTrue(board.getHistory().goToMoveNumber(1, false));
        assertSame(fork.previous().orElseThrow(), env.current());
        if (round == 0) {
          board.setHistory(SGFParser.parseSgf(SGFParser.saveToString(false), true));
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void promotingNestedSetupBeforeFirstMovePreservesExplicitDisplayNumber(boolean restart)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = restart;
      Board board = env.board();
      board.setHistory(SGFParser.parseSgf(
          "(;SZ[5](;B[ee])(;AB[aa](;W[dd])(;AW[bb]MN[17];W[];B[cc])))", true));
      BoardHistoryNode outer = board.getHistory().getStart().getVariation(1).orElseThrow();
      BoardHistoryNode inner = outer.getVariation(1).orElseThrow();
      board.moveToAnyPosition(inner.childAtDepth(2));
      Lizzie.frame.setAsMain();
      assertTrue(env.current().isMainTrunk());
      for (int round = 0; round < 2; round++) {
        outer = board.getHistory().getStart().next().orElseThrow();
        inner = outer.next().orElseThrow();
        assertNumbers(outer, 0, 0);
        assertNumbers(inner, 0, 17);
        assertNumbers(inner.next().orElseThrow(), 1, 18);
        BoardHistoryNode last = inner.childAtDepth(2);
        assertNumbers(last, 2, 19);
        assertEquals(19, label(last, 2, 2));
        board.moveToAnyPosition(last);
        assertEquals(2, board.moveNumberByCoord(new int[] {2, 2}));
        assertEquals(0, board.moveNumberByCoord(new int[] {1, 1}));
        if (round == 0) {
          board.setHistory(SGFParser.parseSgf(SGFParser.saveToString(false), true));
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void thirdVariationReplacesAPlayedStoneAndResetsMn(boolean restart) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = restart;
      Board board = env.board();
      board.setHistory(SGFParser.parseSgf(
          "(;SZ[5];B[aa];W[bb](;B[ab])(;B[ba])"
              + "(;B[cc];AE[aa]AW[aa]MN[1];W[dd];B[ee]MN[2]))", true));
      BoardHistoryNode fork = board.getHistory().getStart().childAtDepth(2);
      BoardHistoryNode middle = fork.getVariation(1).orElseThrow();
      board.moveToAnyPosition(fork.getVariation(2).orElseThrow().childAtDepth(3));
      assertTrue(board.setAsMainBranch());
      assertSame(middle, fork.getVariation(1).orElseThrow());
      for (int round = 0; round < 2; round++) {
        fork = board.getHistory().getStart().childAtDepth(2);
        BoardHistoryNode first = fork.next().orElseThrow();
        BoardHistoryNode setup = first.next().orElseThrow();
        assertNumbers(first, 3, 3);
        assertNumbers(setup, 3, 1);
        BoardHistoryNode white = setup.next().orElseThrow();
        assertNumbers(white, 4, 2);
        board.moveToAnyPosition(white);
        assertEquals(0, board.moveNumberByXY(0, 0), "replacement setup stone");
        assertEquals(2, board.moveNumberByXY(1, 1), "surviving move before MN");
        assertEquals(4, board.moveNumberByXY(3, 3), "new move after MN");
        BoardHistoryNode last = white.next().orElseThrow();
        assertNumbers(last, 5, 2);
        assertEquals(0, label(last, 0, 0));
        assertEquals(1, label(last, 3, 3), "MN on the last play relabels the previous play");
        assertEquals(2, label(last, 4, 4));
        BoardHistoryNode demoted = fork.getVariation(2).orElseThrow();
        assertNumbers(demoted, 3, restart ? 1 : 3);
        assertEquals(restart ? 0 : 2, label(demoted, 1, 1));
        if (round == 0) {
          board.setHistory(SGFParser.parseSgf(SGFParser.saveToString(false), true));
        }
      }
    }
  }

  @Test
  void captureAndSideVariationKeepTheirOwnLabelsWhenNumberingPreferenceChanges() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Board board = env.board();
      Lizzie.config.newMoveNumberInBranch = false;
      board.setHistory(SGFParser.parseSgf(
          "(;SZ[5];W[aa];B[ab](;W[cc])(;AW[ee]PL[B];B[ba](;W[];DD[true])(;W[dd])))", true));
      BoardHistoryNode fork = board.getHistory().getStart().childAtDepth(2);
      BoardHistoryNode capture = fork.getVariation(1).orElseThrow().next().orElseThrow();
      BoardHistoryNode pass = capture.next().orElseThrow();
      BoardHistoryNode dummy = pass.next(true).orElseThrow();
      int recordedDummyNumber = dummy.getData().moveNumber;
      BoardHistoryNode side = capture.getVariation(1).orElseThrow();
      int[] sideLabels = side.getData().moveNumberList.clone();
      assertEquals(4, label(side, 3, 3));

      Lizzie.config.newMoveNumberInBranch = true;
      board.moveToAnyPosition(pass);
      Lizzie.frame.setAsMain();
      assertSame(pass, env.current());
      assertSame(pass, board.getHistory().getMainEnd());
      assertEquals(4, board.getHistory().mainTrunkLength());
      assertEquals(Stone.EMPTY, capture.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(0, label(capture, 0, 0));
      assertNumbers(dummy, recordedDummyNumber, 4);
      assertTrue(dummy.getData().dummy);
      assertArrayEquals(sideLabels, side.getData().moveNumberList);

      BoardHistoryList reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      BoardHistoryNode reopenedDummy = reopened.getMainEnd().next(true).orElseThrow();
      assertTrue(reopenedDummy.getData().dummy);
      assertNumbers(reopenedDummy, recordedDummyNumber, 4);
    }
  }

  private static void assertNumbers(BoardHistoryNode node, int absolute, int display) {
    assertEquals(absolute, node.getData().moveNumber, "history number");
    assertEquals(display, node.getData().moveMNNumber, "display number");
  }

  private static int label(BoardHistoryNode node, int x, int y) {
    return node.getData().moveNumberList[node.getData().zobrist.index(x, y)];
  }
}
