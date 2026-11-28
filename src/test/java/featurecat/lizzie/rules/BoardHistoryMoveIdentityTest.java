package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardHistoryMoveIdentityTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @ParameterizedTest
  @CsvSource({
    "BLACK, WHITE, false",
    "WHITE, BLACK, false",
    "BLACK, WHITE, true",
    "WHITE, BLACK, true",
    "BLACK, BLACK, false",
    "WHITE, WHITE, false",
    "BLACK, BLACK, true",
    "WHITE, WHITE, true"
  })
  void historyReplayRequiresTheRequestedColor(
      Stone originalColor, Stone requestedColor, boolean pass) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(BOARD_SIZE)) {
      BoardHistoryList history = env.board().getHistory();
      BoardHistoryNode parent = history.getCurrentHistoryNode();
      if (pass) history.pass(originalColor, false);
      else history.place(1, 1, originalColor);
      BoardHistoryNode original = history.getCurrentHistoryNode();
      original.getData().comment = "original branch";
      history.place(0, 0, originalColor.opposite());
      BoardHistoryNode continuation = history.getCurrentHistoryNode();
      assertTrue(history.previous().isPresent());
      assertTrue(history.previous().isPresent());
      boolean parentTurn = parent.getData().blackToPlay;

      if (pass) history.pass(requestedColor, false);
      else history.place(1, 1, requestedColor);

      if (originalColor == requestedColor) assertSame(original, history.getCurrentHistoryNode());
      else assertNotSame(original, history.getCurrentHistoryNode());
      assertEquals(originalColor == requestedColor ? 1 : 2, parent.numberOfChildren());
      assertEquals(requestedColor, history.getData().lastMoveColor);
      assertEquals(requestedColor == Stone.WHITE, history.getData().blackToPlay);
      assertEquals(pass, history.getData().isPassNode());
      if (!pass) assertEquals(requestedColor, history.getStones()[Board.getIndex(1, 1)]);
      assertEquals(1, history.getData().moveNumber);
      assertEquals(parentTurn, parent.getData().blackToPlay);
      assertSame(original, parent.getVariation(0).orElseThrow());
      assertSame(continuation, original.next().orElseThrow());
      assertEquals("original branch", original.getData().comment);
    }
  }

  @Test
  void nextByMoveIdentityReusesExistingVariationWithDifferentComment() {
    try (BoardSizeScope ignored = BoardSizeScope.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode root = history.getCurrentHistoryNode();
      root.add(new BoardHistoryNode(moveData(1, 0, Stone.BLACK, false, 0, 0)));

      BoardData existingData = moveData(0, 0, Stone.BLACK, false, 0, 0);
      existingData.comment = "existing SGF variation comment";
      BoardHistoryNode existingVariation = new BoardHistoryNode(existingData);
      existingVariation.reparentAsLastVariationOf(root);

      BoardData clickedMove = moveData(0, 0, Stone.BLACK, false, 0, 0);

      assertTrue(history.nextByMoveIdentity(clickedMove, false).isPresent());
      assertSame(existingVariation, history.getCurrentHistoryNode());
      assertEquals(2, root.numberOfChildren(), "clicking an existing variation must not duplicate it.");
      assertEquals(
          "existing SGF variation comment",
          history.getData().comment,
          "entering the existing branch should preserve its original node data.");
    }
  }

  @ParameterizedTest
  @CsvSource({"true, false", "false, false", "true, true", "false, true"})
  void placingAMoveSelectsTheSiblingWithTheRequestedCaptureResult(
      boolean desktop, boolean noCapture) throws Exception {
    try (var env = RulesLayerTestHarness.open(5)) {
      String capture = "(;B[ab]C[capture])";
      String retained = "(;B[ab]KGNC[1]C[retain])";
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[5]AP[KG-next]KGCM[1]PL[B]AB[ba]AW[aa]"
              + (noCapture ? capture + retained : retained + capture) + ")", true);
      history.toStart();
      env.board().setHistory(history);
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode expected = root.getVariation(1).orElseThrow();
      Lizzie.config.noCapture = noCapture;
      Lizzie.leelaz = null;
      if (desktop) env.board().place(0, 1, Stone.BLACK);
      else history.place(0, 1, Stone.BLACK);
      assertSame(expected, history.getCurrentHistoryNode());
      assertEquals(2, root.numberOfChildren());
      assertEquals(noCapture ? "retain" : "capture", expected.getData().comment);
      assertEquals(noCapture ? Stone.WHITE : Stone.EMPTY,
          expected.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(noCapture ? 0 : 1, expected.getData().blackCaptures);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void newMoveRetainsItsCapturePolicyWhenASetupEditCanChangeItsResult(boolean desktop)
      throws Exception {
    try (var env = RulesLayerTestHarness.open(5)) {
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[5]AP[KG-next]KGCM[1]AW[aa][ba];B[ab]KGNC[1])", true);
      history.toStart();
      Board board = env.board();
      board.setHistory(history);
      if (desktop) board.place(0, 1, Stone.BLACK);
      else history.place(0, 1, Stone.BLACK);
      // The two actions look identical until ba is recolored and aa loses its last liberty.
      assertEquals(2, history.getStart().numberOfChildren());
      board.editMove(new int[] {1, 0}, true, false);
      BoardHistoryNode root = board.getHistory().getStart();
      assertEquals(Stone.WHITE, root.getVariation(0).orElseThrow().getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.EMPTY, root.getVariation(1).orElseThrow().getData().stones[Board.getIndex(0, 0)]);
      assertEquals(1, board.getData().blackCaptures);
    }
  }

  @Test
  void nextByMoveIdentityDoesNotMatchDifferentMoveIdentity() {
    try (BoardSizeScope ignored = BoardSizeScope.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode root = history.getCurrentHistoryNode();
      new BoardHistoryNode(moveData(0, 0, Stone.BLACK, false, 1, 0))
          .reparentAsLastVariationOf(root);

      assertFalse(
          history.nextByMoveIdentity(moveData(0, 0, Stone.BLACK, false, 0, 0), false).isPresent(),
          "same coordinate should not match when capture totals differ.");
      assertFalse(
          history.nextByMoveIdentity(moveData(0, 0, Stone.WHITE, true, 1, 0), false).isPresent(),
          "same coordinate should not match when the played color differs.");
      assertSame(root, history.getCurrentHistoryNode());
      assertEquals(1, root.numberOfChildren());
    }
  }

  @Test
  void directChildMoveIdentitySupportsPassNodes() {
    try (BoardSizeScope ignored = BoardSizeScope.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode root = history.getCurrentHistoryNode();
      BoardData existingPass = passData(Stone.BLACK, false, 0, 0);
      existingPass.comment = "existing pass variation";
      BoardHistoryNode passVariation = new BoardHistoryNode(existingPass);
      passVariation.reparentAsLastVariationOf(root);

      assertTrue(history.nextByMoveIdentity(passData(Stone.BLACK, false, 0, 0), false).isPresent());
      assertSame(passVariation, history.getCurrentHistoryNode());
      assertEquals("existing pass variation", history.getData().comment);
    }
  }

  private static BoardData moveData(
      int x, int y, Stone color, boolean blackToPlay, int blackCaptures, int whiteCaptures) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        zobrist(stones),
        1,
        new int[BOARD_AREA],
        blackCaptures,
        whiteCaptures,
        50,
        0);
  }

  private static BoardData passData(
      Stone color, boolean blackToPlay, int blackCaptures, int whiteCaptures) {
    Stone[] stones = emptyStones();
    return BoardData.pass(
        stones,
        color,
        blackToPlay,
        zobrist(stones),
        1,
        new int[BOARD_AREA],
        blackCaptures,
        whiteCaptures,
        50,
        0);
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static final class BoardSizeScope implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;

    private BoardSizeScope(int previousBoardWidth, int previousBoardHeight) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
    }

    private static BoardSizeScope open() {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      return new BoardSizeScope(previousBoardWidth, previousBoardHeight);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
    }
  }
}
