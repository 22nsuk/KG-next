package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PassVariationNumberingTest {
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
