package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PassVariationNumberingTest {
  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,true", "false,false"})
  void localVariationsAtLiveEndKeepTheirNumbersAcrossSave(boolean urlGame, boolean restart)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = restart;
      Board board = env.board();
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[aa]MN[20])", true);
      history.setHead(history.getMainEnd());
      board.setHistory(history);
      LizzieFrame.urlSgf = urlGame;
      Lizzie.frame.syncBoard = !urlGame;

      BoardHistoryNode fork = env.current();
      board.place(1, 1, Stone.WHITE);
      board.pass(Stone.BLACK);
      board.place(3, 3, Stone.WHITE);
      history.setHead(fork);
      board.place(2, 2, Stone.WHITE);
      assertSame(fork, history.getMainEnd());

      BoardHistoryList reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      for (BoardHistoryList record : List.of(history, reopened)) {
        BoardHistoryNode parent = record.getStart().next().orElseThrow();
        BoardHistoryNode first = parent.getVariation(1).orElseThrow();
        BoardHistoryNode second = parent.getVariation(2).orElseThrow();
        assertEquals(restart ? 0 : 20, first.getData().moveNumberList[Board.getIndex(0, 0)]);
        assertEquals(restart ? 1 : 21, first.getData().moveNumberList[Board.getIndex(1, 1)]);
        assertEquals(restart ? 1 : 21, second.getData().moveNumberList[Board.getIndex(2, 2)]);
        BoardData afterPass = first.next().orElseThrow().next().orElseThrow().getData();
        assertEquals(restart ? 3 : 23, afterPass.moveNumberList[Board.getIndex(3, 3)]);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void promotingAVariationFromAnObservedRootKeepsItsAbsoluteNumber(boolean restart)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = restart;
      BoardHistoryList history = env.board().getHistory();
      BoardHistoryNode root = history.getStart();
      root.getData().moveNumber = 42;
      history.pass(Stone.BLACK);
      history.setHead(root);
      history.place(2, 2, Stone.BLACK, true);
      assertTrue(env.board().setAsMainBranch());
      assertEquals(43, history.getData().moveNumberList[Board.getIndex(2, 2)]);
      assertEquals(43, env.board().moveNumberByXY(2, 2));
      assertEquals(restart ? 1 : 43, root.getVariation(1).orElseThrow().getData().moveMNNumber);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"normal", "append", "engine-game"})
  void firstPassAfterObservedRootContinuesItsMoveCount(String entrypoint) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      BoardHistoryList history = env.board().getHistory();
      history.getData().moveNumber = 42;
      switch (entrypoint) {
        case "normal" -> history.pass(Stone.BLACK);
        case "append" -> {
          history.pass(Stone.BLACK, false, false, false, true);
          history.next().orElseThrow();
        }
        case "engine-game" -> history.passForEngineGame(Stone.BLACK, true);
        default -> throw new IllegalArgumentException(entrypoint);
      }
      history.place(1, 1, Stone.WHITE);
      assertEquals(44, history.getData().moveNumberList[Board.getIndex(1, 1)]);
      assertEquals(44, env.board().moveNumberByXY(1, 1));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"normal", "branch", "dummy", "append", "engine-game",
      "board", "board-branch", "board-dummy"})
  void newPassOwnsItsPositionState(String entrypoint) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      boolean branch = entrypoint.endsWith("branch");
      boolean dummy = entrypoint.endsWith("dummy");
      // Keep addOrGoto's separate branch-number reset from masking the pass input's reset.
      Lizzie.config.newMoveNumberInBranch = false;
      BoardHistoryList history = env.board().getHistory();
      history.place(0, 0, Stone.BLACK);
      history.place(1, 1, Stone.WHITE);
      BoardHistoryNode parent = env.current();
      BoardData source = parent.getData();
      source.blackCaptures = 3;
      source.whiteCaptures = 4;
      if (branch) {
        history.place(2, 2, Stone.BLACK);
        assertTrue(history.previous().isPresent());
      } else if (entrypoint.equals("append")) {
        assertTrue(history.previous().isPresent());
        history.place(3, 3, Stone.WHITE);
      }
      BoardHistoryNode cursor = env.current();
      Stone[] stones = source.stones.clone();
      int[] numbers = source.moveNumberList.clone();
      Zobrist hash = source.zobrist.clone();

      switch (entrypoint) {
        case "normal" -> history.pass(Stone.BLACK);
        case "branch" -> history.pass(Stone.BLACK, true);
        case "dummy" -> history.pass(Stone.BLACK, false, true);
        case "append" -> history.pass(Stone.BLACK, false, false, false, true);
        case "engine-game" -> history.passForEngineGame(Stone.BLACK, true);
        case "board" -> env.board().pass(Stone.BLACK);
        case "board-branch" -> env.board().pass(Stone.BLACK, true);
        case "board-dummy" -> env.board().pass(Stone.BLACK, false, true);
        default -> throw new IllegalArgumentException(entrypoint);
      }
      BoardData pass =
          entrypoint.equals("append")
              ? parent.next(true).orElseThrow().getData()
              : history.getData();
      if (entrypoint.equals("append")) assertSame(cursor, env.current());
      assertTrue(pass.isPassNode());
      assertEquals(dummy, pass.dummy);
      assertEquals(Stone.BLACK, pass.lastMoveColor);
      assertFalse(pass.blackToPlay);
      assertEquals(3, pass.moveNumber);
      assertEquals(3, pass.blackCaptures);
      assertEquals(4, pass.whiteCaptures);
      assertArrayEquals(stones, pass.stones);
      assertArrayEquals(
          branch ? new int[numbers.length] : numbers, pass.moveNumberList);
      assertEquals(hash, pass.zobrist);
      assertNotSame(source.stones, pass.stones);
      assertNotSame(source.moveNumberList, pass.moveNumberList);
      assertNotSame(source.zobrist, pass.zobrist);

      pass.stones[Board.getIndex(0, 0)] = Stone.WHITE;
      pass.moveNumberList[Board.getIndex(0, 0)] = 99;
      pass.zobrist.toggleStone(0, 0, Stone.BLACK);
      assertArrayEquals(stones, source.stones);
      assertArrayEquals(numbers, source.moveNumberList);
      assertEquals(hash, source.zobrist);
    }
  }

  @ParameterizedTest
  @CsvSource({"false,true,1", "true,true,1", "false,false,1", "true,false,1",
      "false,true,2", "true,true,2", "false,false,2", "true,false,2"})
  void passesContinueVariationNumbersAndCoordinateLookup(
      boolean historyOnly, boolean restart, int passes) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = restart;
      Board board = env.board();
      BoardHistoryList history = board.getHistory();
      place(board, historyOnly, 0, 0, Stone.BLACK);
      place(board, historyOnly, 1, 1, Stone.WHITE);
      assertTrue(history.previous().isPresent());
      place(board, historyOnly, 3, 3, Stone.WHITE);
      int offset = restart ? 0 : 1;
      assertEquals(1 + offset, history.getData().moveNumberList[Board.getIndex(3, 3)]);
      for (int i = 0; i < passes; i++) {
        pass(board, historyOnly, i % 2 == 0 ? Stone.BLACK : Stone.WHITE, false);
      }
      int passNumber = history.getData().moveMNNumber;
      place(board, historyOnly, 4, 4, passes == 1 ? Stone.WHITE : Stone.BLACK);
      place(board, historyOnly, 0, 4, passes == 1 ? Stone.BLACK : Stone.WHITE);
      assertAll(
          () -> assertEquals(1 + passes + offset, passNumber, "pass number"),
          () -> assertEquals(2 + passes + offset,
              history.getData().moveNumberList[Board.getIndex(4, 4)], "stone after pass"),
          () -> assertEquals(3 + passes + offset,
              history.getData().moveNumberList[Board.getIndex(0, 4)], "next stone"),
          () -> assertEquals(3 + passes, board.moveNumberByCoord(new int[] {4, 4}), "coordinate lookup"),
          () -> assertEquals(4 + passes, board.moveNumberByCoord(new int[] {0, 4})));
    }
  }

  @ParameterizedTest
  @CsvSource({"false,true", "true,true", "false,false", "true,false"})
  void firstPassMainlineAndExplicitPassBranchKeepDisplayedNumbers(
      boolean historyOnly, boolean restart) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = restart;
      Board board = env.board();
      BoardHistoryList history = board.getHistory();
      pass(board, historyOnly, Stone.BLACK, false);
      pass(board, historyOnly, Stone.WHITE, false);
      place(board, historyOnly, 2, 2, Stone.BLACK);
      assertEquals(3, history.getData().moveNumberList[Board.getIndex(2, 2)]);
      assertTrue(history.previous().isPresent());
      assertTrue(history.previous().isPresent());
      pass(board, historyOnly, Stone.WHITE, true);
      assertEquals(restart ? 1 : -1, history.getData().moveMNNumber);
      place(board, historyOnly, 4, 4, Stone.BLACK);
      assertEquals(restart ? 2 : 3, history.getData().moveNumberList[Board.getIndex(4, 4)]);
      if (restart) assertEquals(3, board.moveNumberByCoord(new int[] {4, 4}));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sgfParserPassContinuesTheParsedVariation(boolean restart) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = restart;
      BoardHistoryList parsed =
          SGFParser.parseSgf("(;SZ[5];B[aa](;W[bb])(;W[dd];B[];W[ee];B[ae]))", true);
      BoardHistoryNode variation =
          parsed.getStart().next().orElseThrow().getVariation(1).orElseThrow();
      BoardHistoryNode pass = variation.next().orElseThrow();
      BoardHistoryNode third = pass.next().orElseThrow();
      BoardHistoryNode fourth = third.next().orElseThrow();
      assertTrue(pass.getData().isPassNode());
      assertEquals(Stone.BLACK, pass.getData().lastMoveColor);
      assertEquals(restart ? 2 : 3, pass.getData().moveMNNumber);
      assertEquals(restart ? 3 : 4, fourth.getData().moveNumberList[Board.getIndex(4, 4)]);
      assertEquals(restart ? 4 : 5, fourth.getData().moveNumberList[Board.getIndex(0, 4)]);
      env.board().setHistory(parsed);
      env.board().moveToAnyPosition(fourth);
      assertEquals(4, env.board().moveNumberByCoord(new int[] {4, 4}));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void addLastPassInheritsTheTailNumberAndLeavesTheCursorAlone(boolean restart)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = restart;
      BoardHistoryList history = env.board().getHistory();
      history.place(0, 0, Stone.BLACK);
      history.place(1, 1, Stone.WHITE);
      BoardHistoryNode tail = env.current();
      assertTrue(history.previous().isPresent());
      history.place(3, 3, Stone.WHITE);
      BoardHistoryNode cursor = env.current();
      history.pass(Stone.BLACK, false, false, false, true);
      assertSame(cursor, env.current());
      assertEquals(3, tail.next().orElseThrow().getData().moveMNNumber);
      assertEquals(3, tail.next().orElseThrow().getData().moveNumber);
      assertEquals(0, cursor.numberOfChildren());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void engineGameUsesFrozenBranchSettingAndContinuesAfterPass(boolean frozenRestart)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      Lizzie.config.newMoveNumberInBranch = !frozenRestart;
      BoardHistoryList history = env.board().getHistory();
      history.place(0, 0, Stone.BLACK);
      history.place(1, 1, Stone.WHITE);
      assertTrue(history.previous().isPresent());
      history.placeForEngineGame(3, 3, Stone.WHITE, false, false, frozenRestart);
      history.passForEngineGame(Stone.BLACK, frozenRestart);
      assertEquals(frozenRestart ? 2 : 3, history.getData().moveMNNumber);
      history.placeForEngineGame(4, 4, Stone.WHITE, false, false, frozenRestart);
      assertEquals(frozenRestart ? 3 : 4, history.getData().moveNumberList[Board.getIndex(4, 4)]);
    }
  }

  private static void place(Board board, boolean historyOnly, int x, int y, Stone color) {
    if (historyOnly) board.getHistory().place(x, y, color);
    else board.place(x, y, color);
  }

  private static void pass(Board board, boolean historyOnly, Stone color, boolean newBranch) {
    if (historyOnly) board.getHistory().pass(color, newBranch);
    else board.pass(color, newBranch);
  }
}
