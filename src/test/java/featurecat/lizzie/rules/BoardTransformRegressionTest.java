package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardTransformRegressionTest {
  @Test
  void rotateAfterUndoAndRedoOfFlattenedStoneEdit() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Board board = env.board();
      assertTrue(SGFParser.loadFromStringforedit("(;SZ[5];B[aa];W[ee];B[cc])"));
      board.getHistory().setHead(board.getHistory().getEnd());
      board.hasStartStone = true;
      board.addStartListAll();
      board.flatten();
      board.editMove(new int[] {0, 0}, false, true);

      assertEquals(2, board.getMoveList().size());
      board.cleanedit();
      assertEquals(3, board.getMoveList().size());
      board.reedit();
      assertEquals(2, board.getMoveList().size());
      board.SpinAndMirror(1);

      assertEquals(Stone.BLACK, env.stoneAt(2, 2));
      assertEquals(Stone.WHITE, env.stoneAt(0, 4));
      assertEquals(2, Arrays.stream(board.getData().stones).filter(s -> s != Stone.EMPTY).count());
      assertEquals(0, board.getHistory().mainTrunkLength());
      assertFalse(board.getData().blackToPlay);
    }
  }

  @Test
  void mirrorAfterSetupEditKeepsRecordedSelfCaptureAndItsComment() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      featurecat.lizzie.Lizzie.setPrimaryEngine(null);
      assertTrue(SGFParser.loadFromString(
          "(;SZ[5]RU[New Zealand]PL[B]AB[aa][ee]AW[ba][bb][ac];B[ab]C[keep])", false));
      Board board = env.board();
      board.getHistory().setHead(board.getHistory().getEnd());
      board.editMove(new int[] {4, 4}, false, true);
      board.SpinAndMirror(3);
      BoardData move = board.getData();
      assertTrue(move.isMoveNode());
      assertArrayEquals(new int[] {4, 1}, move.lastMove.orElseThrow());
      assertEquals(Stone.EMPTY, move.stones[Board.getIndex(4, 0)]);
      assertEquals(Stone.EMPTY, move.stones[Board.getIndex(4, 1)]);
      assertEquals("keep", move.comment);
      BoardHistoryList reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      assertEquals("New Zealand", reopened.getStart().getData().getProperty("RU"));
      assertArrayEquals(move.stones, reopened.getEnd().getData().stones);
      assertEquals("keep", reopened.getEnd().getData().comment);
    }
  }

  @Test
  void mirrorKeepsEditedChildDiagramUnderAnEmptyRoot() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromStringforedit("(;SZ[5];AB[aa][bc]C[setup];W[ee]C[tail])"));
      Board board = env.board();
      board.getHistory().setHead(board.getHistory().getEnd());
      board.editMove(new int[] {0, 0}, false, true);
      board.SpinAndMirror(3);
      assertFalse(board.hasStartStone);
      assertEquals(Stone.BLACK, env.stoneAt(3, 2));
      assertEquals(Stone.WHITE, env.stoneAt(0, 4));
      assertEquals("tail", board.getData().comment);
      BoardHistoryList reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      BoardHistoryNode child = reopened.getStart().next().orElseThrow();
      assertTrue(child.getData().isSnapshotNode());
      assertEquals("setup", child.getData().comment);
      assertEquals(Stone.BLACK, child.getData().stones[Board.getIndex(3, 2)]);
      assertArrayEquals(board.getData().stones, reopened.getEnd().getData().stones);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 6})
  void transformKeepsSetupWithoutLibertiesAndSelectedVariation(int transform) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Board board = env.board();
      assertTrue(SGFParser.loadFromStringforedit(
          "(;SZ[5]KM[6.5]PB[Black player]RE[B+R]PL[W]AB[ab][ba][cb][bc][ee]AW[bb]"
              + "(;W[ad]C[first])(;W[dd]C[selected];B[]))"));
      BoardHistoryNode root = board.getHistory().getStart();
      board.getHistory().setHead(root.getVariation(1).orElseThrow());
      board.editMove(new int[] {4, 4}, false, true);
      if (transform == 6) board.exchangeBlackWhite();
      else board.SpinAndMirror(transform);

      String positions = switch (transform) {
        case 1 -> "PL[W]AB[da][eb][dc][cb]AW[db](;W[ba])(;W[bd];B[])";
        case 2 -> "PL[W]AB[be][ad][bc][cd]AW[bd](;W[de])(;W[db];B[])";
        case 3 -> "PL[W]AB[eb][da][cb][dc]AW[db](;W[ed])(;W[bd];B[])";
        case 4 -> "PL[W]AB[ad][be][cd][bc]AW[bd](;W[ab])(;W[db];B[])";
        default -> "PL[B]AW[ab][ba][cb][bc]AB[bb](;B[ad])(;B[dd];W[])";
      };
      BoardHistoryList expected = SGFParser.parseSgf("(;SZ[5]" + positions + ")", true);
      BoardHistoryNode actual = board.getHistory().getStart();
      assertArrayEquals(expected.getStart().getData().stones, actual.getData().stones);
      assertEquals(expected.getStart().getData().blackToPlay, actual.getData().blackToPlay);
      assertEquals(2, actual.numberOfChildren());
      assertEquals(actual.getVariation(1).orElseThrow(), board.getHistory().getCurrentHistoryNode());
      assertEquals("selected", board.getData().comment);
      assertArrayEquals(expected.getStart().getVariation(1).orElseThrow().getData().stones,
          board.getData().stones);
      assertEquals(6.5, board.getHistory().getGameInfo().getKomi());
      assertEquals(transform == 6 ? "" : "Black player", board.getHistory().getGameInfo().getPlayerBlack());
      assertEquals(transform == 6 ? "" : "B+R", board.getHistory().getGameInfo().getResult());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 6})
  void transformationRetainsFirstMoveBranchesAndCurrentPosition(int transform) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5]KM[6.5];B[aa](;W[bb])(;W[cc]))"));
      env.board().nextMove(false);
      AllMovelist moves = env.board().getAllMovelist(transform);
      env.board().clear(false);
      env.board().playAllMovelist(moves, 0);
      assertEquals(1, env.board().getHistory().getMoveNumber());
      assertEquals(transform == 6 ? Stone.WHITE : Stone.BLACK, env.board().getData().lastMoveColor);
      assertEquals(2, env.board().getHistory().mainTrunkLength());
      assertEquals(2, env.current().numberOfChildren());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 6})
  void transformationRetainsLeadingPassColor(int transform) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5];W[];B[aa])"));
      AllMovelist moves = env.board().getAllMovelist(transform);
      env.board().clear(false);
      env.board().playAllMovelist(moves, 0);
      BoardData first = env.board().getHistory().getStart().next().orElseThrow().getData();
      assertTrue(first.isPassNode());
      assertEquals(transform == 6 ? Stone.BLACK : Stone.WHITE, first.lastMoveColor);
      assertEquals(2, env.board().getHistory().mainTrunkLength());
    }
  }
}
