package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardHistoryMoveOwnershipTest {
  private static final int WIDTH = 5;
  private static final int HEIGHT = 7;

  @ParameterizedTest
  @EnumSource(BoardNodeKind.class)
  void publicFactoriesDetachMutablePositionInputs(BoardNodeKind kind) {
    Stone[] stones = new Stone[WIDTH * HEIGHT];
    Arrays.fill(stones, Stone.EMPTY);
    Zobrist hash = new Zobrist(WIDTH, HEIGHT);
    int index = hash.index(2, 3);
    stones[index] = Stone.BLACK;
    hash.toggleStone(2, 3, Stone.BLACK);
    int[] numbers = new int[stones.length];
    numbers[index] = 9;
    int[] lastMove = {2, 3};
    BoardData data = switch (kind) {
      case MOVE -> BoardData.move(
          stones, lastMove, Stone.BLACK, false, hash, 9, numbers, 2, 3, 47, 0);
      case PASS -> BoardData.pass(
          stones, Stone.BLACK, false, hash, 9, numbers, 2, 3, 47, 0);
      case SNAPSHOT -> BoardData.snapshot(
          stones, Optional.of(lastMove), Stone.BLACK, false, hash, 9, numbers, 2, 3, 47, 0);
    };
    Position before = Position.capture(data);
    assertNotSame(stones, data.stones);
    assertNotSame(numbers, data.moveNumberList);
    assertNotSame(hash, data.zobrist);
    data.lastMove.ifPresent(move -> assertNotSame(lastMove, move));

    stones[index] = Stone.WHITE;
    numbers[index] = 99;
    hash.toggleStone(2, 3, Stone.BLACK);
    lastMove[0] = 4;
    before.assertUnchanged(data);
    data.lastMove.ifPresent(move -> assertArrayEquals(new int[] {2, 3}, move));

    data.stones[index] = Stone.EMPTY;
    data.moveNumberList[index] = 0;
    data.zobrist.toggleStone(2, 3, Stone.WHITE);
    assertEquals(Stone.WHITE, stones[index]);
    assertEquals(99, numbers[index]);
    assertEquals(new Zobrist(WIDTH, HEIGHT), hash);
  }

  @ParameterizedTest
  @ValueSource(strings = {"normal", "branch", "change", "append", "engine-game"})
  void newMoveOwnsItsRectangularPositionAcrossHistoryEntrypoints(String entrypoint)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      BoardHistoryList history = rectangularHistory(env);
      Lizzie.config.newMoveNumberInBranch = false;
      history.place(0, 0, Stone.BLACK);
      history.place(1, 1, Stone.WHITE);
      BoardHistoryNode parent = env.current();
      BoardData source = parent.getData();
      source.moveMNNumber = 17;
      source.blackCaptures = 3;
      source.whiteCaptures = 4;
      BoardHistoryNode sibling = null;
      if (entrypoint.equals("branch") || entrypoint.equals("change")
          || entrypoint.equals("engine-game")) {
        history.place(2, 2, Stone.BLACK);
        sibling = env.current();
        assertTrue(history.previous().isPresent());
      } else if (entrypoint.equals("append")) {
        assertTrue(history.previous().isPresent());
        history.place(4, 4, Stone.WHITE);
        sibling = env.current();
      }
      BoardHistoryNode cursor = env.current();
      Position parentBefore = Position.capture(source);
      Position siblingBefore = sibling == null ? null : Position.capture(sibling.getData());

      switch (entrypoint) {
        case "normal" -> history.place(3, 6, Stone.BLACK);
        case "branch" -> history.place(3, 6, Stone.BLACK, true);
        case "change" -> history.place(3, 6, Stone.BLACK, false, true);
        case "append" -> history.place(3, 6, Stone.BLACK, false, false, true);
        case "engine-game" -> history.placeForEngineGame(3, 6, Stone.BLACK, false, false, false);
        default -> throw new IllegalArgumentException(entrypoint);
      }
      BoardData move = entrypoint.equals("append")
          ? parent.next(true).orElseThrow().getData() : history.getData();
      if (entrypoint.equals("append")) assertSame(cursor, env.current());
      assertTrue(move.isMoveNode());
      assertArrayEquals(new int[] {3, 6}, move.lastMove.orElseThrow());
      assertEquals(Stone.BLACK, move.lastMoveColor);
      assertEquals(false, move.blackToPlay);
      assertEquals(3, move.moveNumber);
      assertEquals(entrypoint.equals("branch") ? -1 : 18, move.moveMNNumber);
      assertEquals(3, move.blackCaptures);
      assertEquals(4, move.whiteCaptures);
      assertEquals(Stone.BLACK, move.stones[history.getIndex(3, 6)]);
      int[] expectedNumbers = entrypoint.equals("branch")
          ? new int[source.stones.length] : source.moveNumberList.clone();
      expectedNumbers[history.getIndex(3, 6)] = entrypoint.equals("branch") ? 3 : 18;
      assertArrayEquals(expectedNumbers, move.moveNumberList);
      assertPositionHash(move);
      assertNotSame(source.stones, move.stones);
      assertNotSame(source.moveNumberList, move.moveNumberList);
      assertNotSame(source.zobrist, move.zobrist);

      move.stones[history.getIndex(0, 0)] = Stone.EMPTY;
      move.moveNumberList[history.getIndex(0, 0)] = 99;
      move.zobrist.toggleStone(0, 0, Stone.BLACK);
      parentBefore.assertUnchanged(source);
      if (sibling != null) siblingBefore.assertUnchanged(sibling.getData());
    }
  }

  @Test
  void captureAndLaterHistoryEditsKeepParentAndSiblingPositionsIndependent() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      BoardHistoryList history = rectangularHistory(env);
      Board board = env.board();
      assertTrue(board.setupPlaceStone(1, 0, Stone.BLACK));
      assertTrue(board.setupPlaceStone(0, 0, Stone.WHITE));
      BoardHistoryNode parent = env.current();
      history.place(4, 6, Stone.BLACK);
      BoardHistoryNode sibling = env.current();
      assertTrue(history.previous().isPresent());
      Position parentBefore = Position.capture(parent.getData());
      Position siblingBefore = Position.capture(sibling.getData());

      history.place(0, 1, Stone.BLACK, true);
      BoardData capture = history.getData();
      assertEquals(Stone.EMPTY, capture.stones[history.getIndex(0, 0)]);
      assertEquals(0, capture.moveNumberList[history.getIndex(0, 0)]);
      assertEquals(1, capture.blackCaptures);
      assertEquals(0, capture.whiteCaptures);
      assertPositionHash(capture);
      history.addExtraStone(4, 6, Stone.WHITE);
      history.removeStone(1, 0, Stone.BLACK);
      history.moveNumber(77);
      assertPositionHash(capture);
      parentBefore.assertUnchanged(parent.getData());
      siblingBefore.assertUnchanged(sibling.getData());

      Position captureAfterEdits = Position.capture(capture);
      assertTrue(history.previous().isPresent());
      assertTrue(history.next().isPresent());
      assertSame(sibling, env.current());
      history.addExtraStone(2, 6, Stone.WHITE);
      history.moveNumber(88);
      captureAfterEdits.assertUnchanged(capture);
      parentBefore.assertUnchanged(parent.getData());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"suicide", "ko"})
  void rejectedCandidateDoesNotMutateParentAncestorOrExistingSibling(String rejection)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      BoardHistoryList history = rectangularHistory(env);
      Board board = env.board();
      boolean ko = rejection.equals("ko");
      if (ko) {
        board.setupPlaceStone(2, 1, Stone.BLACK);
        board.setupPlaceStone(1, 2, Stone.BLACK);
        board.setupPlaceStone(2, 3, Stone.BLACK);
        board.setupPlaceStone(3, 1, Stone.WHITE);
        board.setupPlaceStone(4, 2, Stone.WHITE);
        board.setupPlaceStone(3, 3, Stone.WHITE);
        board.setupPlaceStone(2, 2, Stone.WHITE);
        history.place(3, 2, Stone.BLACK);
        assertEquals(1, history.getData().blackCaptures);
      } else {
        board.setupPlaceStone(1, 0, Stone.WHITE);
        board.setupPlaceStone(0, 1, Stone.WHITE);
      }
      BoardHistoryNode parent = env.current();
      BoardData ancestor = history.root().getData();
      history.place(4, 6, ko ? Stone.WHITE : Stone.BLACK);
      BoardHistoryNode sibling = env.current();
      assertTrue(history.previous().isPresent());
      Position parentBefore = Position.capture(parent.getData());
      Position ancestorBefore = Position.capture(ancestor);
      Position siblingBefore = Position.capture(sibling.getData());

      history.place(ko ? 2 : 0, ko ? 2 : 0, ko ? Stone.WHITE : Stone.BLACK);

      assertSame(parent, env.current());
      assertEquals(1, parent.numberOfChildren());
      assertSame(sibling, parent.next(true).orElseThrow());
      parentBefore.assertUnchanged(parent.getData());
      ancestorBefore.assertUnchanged(ancestor);
      siblingBefore.assertUnchanged(sibling.getData());
      assertPositionHash(parent.getData());
    }
  }

  private static BoardHistoryList rectangularHistory(RulesLayerTestHarness env) {
    Board.boardWidth = WIDTH;
    Board.boardHeight = HEIGHT;
    Zobrist.init();
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(WIDTH, HEIGHT));
    env.board().setHistory(history);
    return history;
  }

  private static void assertPositionHash(BoardData data) {
    Zobrist expected = new Zobrist(WIDTH, HEIGHT);
    for (int x = 0; x < WIDTH; x++) {
      for (int y = 0; y < HEIGHT; y++) {
        expected.toggleStone(x, y, data.stones[expected.index(x, y)]);
      }
    }
    assertEquals(expected, data.zobrist);
  }

  private record Position(
      Stone[] stones, int[] numbers, Zobrist hash, int moveNumber, int blackCaptures,
      int whiteCaptures, boolean blackToPlay) {
    static Position capture(BoardData data) {
      return new Position(
          data.stones.clone(), data.moveNumberList.clone(), data.zobrist.clone(), data.moveNumber,
          data.blackCaptures, data.whiteCaptures, data.blackToPlay);
    }

    void assertUnchanged(BoardData data) {
      assertArrayEquals(stones, data.stones);
      assertArrayEquals(numbers, data.moveNumberList);
      assertEquals(hash, data.zobrist);
      assertEquals(moveNumber, data.moveNumber);
      assertEquals(blackCaptures, data.blackCaptures);
      assertEquals(whiteCaptures, data.whiteCaptures);
      assertEquals(blackToPlay, data.blackToPlay);
    }
  }
}
