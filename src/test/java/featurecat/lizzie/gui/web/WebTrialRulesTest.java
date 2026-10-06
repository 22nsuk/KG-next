package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebTrialRulesTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void replayMatchesColorAndPreservesOriginalContinuation(boolean sameColor) throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      BoardHistoryNode existing = child(f.anchor, 3, 3, sameColor ? Stone.BLACK : Stone.WHITE);
      existing.getData().comment = "original analysis and comment";
      BoardHistoryNode continuation = child(existing, 4, 4, Stone.BLACK);
      f.send(f.owner, "trial_move", "x", 3, "y", 3);
      BoardHistoryNode actual = f.display.get();
      assertEquals(Stone.BLACK, actual.getData().lastMoveColor);
      assertEquals(Stone.BLACK, actual.getData().stones[Board.getIndex(3, 3)]);
      assertFalse(actual.getData().blackToPlay);
      if (sameColor) assertSame(existing, actual);
      else assertNotSame(existing, actual);
      assertSame(continuation, existing.variations.get(0));
      assertEquals("original analysis and comment", existing.getData().comment);
      assertSame(f.anchor, f.board.getHistory().getCurrentHistoryNode());
      assertEquals(sameColor ? 2 : 3, f.anchor.variations.size());
    }
  }

  @Test
  void nonMoveNodeWithStaleLastMoveCannotBeReused() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      BoardData setup = BoardData.empty(Board.boardWidth, Board.boardHeight);
      setup.lastMove = java.util.Optional.of(new int[] {3, 3});
      setup.lastMoveColor = Stone.BLACK;
      BoardHistoryNode existing = new BoardHistoryNode(setup);
      f.anchor.variations.add(existing);
      f.anchor.setPreviousForChild(existing);
      f.send(f.owner, "trial_move", "x", 3, "y", 3);
      assertNotSame(existing, f.display.get());
      assertTrue(f.display.get().getData().isMoveNode());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void immediateKoRecaptureIsRejectedIncludingAnExistingIllegalVariation(boolean existing)
      throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      put(f.anchor, Stone.WHITE, 2, 2, 1, 1, 3, 1, 2, 0);
      put(f.anchor, Stone.BLACK, 1, 2, 3, 2, 2, 3);
      Stone[] before = f.anchor.getData().stones.clone();
      f.send(f.owner, "trial_move", "x", 2, "y", 1);
      BoardHistoryNode capture = f.display.get();
      assertEquals(1, capture.getData().blackCaptures);
      assertEquals(Stone.EMPTY, capture.getData().stones[Board.getIndex(2, 2)]);
      BoardData recapture =
          BoardData.move(
              before,
              new int[] {2, 2},
              Stone.WHITE,
              true,
              f.anchor.getData().zobrist.clone(),
              2,
              f.anchor.getData().moveNumberList.clone(),
              1,
              1,
              0,
              0);
      assertTrue(BoardHistoryList.violatesKoRule(capture, recapture));
      BoardHistoryList cursor = f.board.getHistory().shallowCopy();
      cursor.setHead(capture);
      assertTrue(cursor.violatesKoRule(recapture));
      if (existing) {
        BoardHistoryNode illegal = new BoardHistoryNode(recapture);
        capture.variations.add(illegal);
        capture.setPreviousForChild(illegal);
      }
      f.events.publications.clear();
      f.send(f.owner, "trial_move", "x", 2, "y", 2);
      assertSame(capture, f.display.get());
      assertEquals(existing ? 1 : 0, capture.variations.size());
      assertTrue(f.events.publications.isEmpty());
      assertEquals(1, capture.getData().blackCaptures);
      assertEquals(0, capture.getData().whiteCaptures);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void multiStoneSuicideUsesTheDesktopEngineSetting(boolean allowed) throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      put(f.anchor, Stone.BLACK, 1, 1);
      put(f.anchor, Stone.WHITE, 0, 1, 1, 0, 1, 2, 2, 0, 2, 2, 3, 1);
      Lizzie.leelaz.canSuicidal = allowed;
      f.send(f.owner, "trial_move", "x", 2, "y", 1);
      if (allowed) {
        assertNotSame(f.anchor, f.display.get());
        assertEquals(Stone.EMPTY, f.display.get().getData().stones[Board.getIndex(1, 1)]);
        assertEquals(Stone.EMPTY, f.display.get().getData().stones[Board.getIndex(2, 1)]);
      } else {
        assertSame(f.anchor, f.display.get());
        assertTrue(f.events.publications.isEmpty());
      }
      assertEquals(Stone.BLACK, f.anchor.getData().stones[Board.getIndex(1, 1)]);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void singleStoneSuicideStaysRejectedEvenWhenMultiStoneSuicideIsEnabled(boolean allowed)
      throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      put(f.anchor, Stone.WHITE, 1, 2, 2, 1, 3, 2, 2, 3);
      Lizzie.leelaz.canSuicidal = allowed;
      f.send(f.owner, "trial_move", "x", 2, "y", 2);
      assertSame(f.anchor, f.display.get());
      assertTrue(f.events.publications.isEmpty());
    }
  }

  private static void put(BoardHistoryNode node, Stone color, int... xy) {
    for (int i = 0; i < xy.length; i += 2) {
      node.getData().stones[Board.getIndex(xy[i], xy[i + 1])] = color;
      node.getData().zobrist.toggleStone(xy[i], xy[i + 1], color);
    }
  }

  private static BoardHistoryNode child(BoardHistoryNode parent, int x, int y, Stone color) {
    BoardData original = parent.getData();
    Stone[] stones = original.stones.clone();
    stones[Board.getIndex(x, y)] = color;
    var hash = original.zobrist.clone();
    hash.toggleStone(x, y, color);
    BoardData data =
        BoardData.move(
            stones,
            new int[] {x, y},
            color,
            color == Stone.WHITE,
            hash,
            original.moveNumber + 1,
            original.moveNumberList.clone(),
            0,
            0,
            0,
            0);
    BoardHistoryNode node = new BoardHistoryNode(data);
    parent.variations.add(node);
    parent.setPreviousForChild(node);
    return node;
  }
}
