package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.util.StoneSoundTestSupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Direct Board rules regression: play, capture, pass, ko, handicap/root setup, variation history,
 * and undo/redo / history-jump state. Complements existing history/sync/movelist/root-setup tests.
 */
class BoardPlayCaptureHistoryRegressionTest {
  private static final int SIZE = 5;

  @Test
  void ordinaryPlayPlacesStoneAndAdvancesHistory() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(2, 2, Stone.BLACK);
      board.place(3, 3, Stone.WHITE);

      assertEquals(Stone.BLACK, env.stoneAt(2, 2));
      assertEquals(Stone.WHITE, env.stoneAt(3, 3));
      assertEquals(2, board.getHistory().getMoveNumber());
      assertTrue(board.getHistory().isBlacksTurn());
      assertTrue(RulesLayerTestHarness.sameLastMove(env.current().getData(), 3, 3));
      assertEquals(Stone.WHITE, env.current().getData().lastMoveColor);
      assertEquals(0, env.current().getData().blackCaptures);
      assertEquals(0, env.current().getData().whiteCaptures);
      assertEquals(1, board.getHistory().getStart().numberOfChildren());
      assertEquals(1, board.getHistory().getStart().next().orElseThrow().numberOfChildren());
    }
  }

  @ParameterizedTest
  @CsvSource({"-1,0", "0,-1", "5,0", "0,5", "99,99"})
  void outOfBoundsPlaceIsIgnored(int x, int y) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      BoardHistoryNode root = board.getHistory().getStart();
      board.place(x, y, Stone.BLACK);

      assertSame(root, env.current());
      assertEquals(0, root.numberOfChildren());
      assertEquals(0, board.getHistory().getMoveNumber());
    }
  }

  @Test
  void occupyingAnExistingStoneIsIgnored() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(1, 1, Stone.BLACK);
      BoardHistoryNode afterFirst = env.current();
      int moveNumber = afterFirst.getData().moveNumber;

      board.place(1, 1, Stone.WHITE);

      assertSame(afterFirst, env.current());
      assertEquals(Stone.BLACK, env.stoneAt(1, 1));
      assertEquals(moveNumber, env.current().getData().moveNumber);
      assertEquals(0, afterFirst.numberOfChildren());
    }
  }

  @Test
  void singleStoneCaptureClearsThePointAndIncrementsCaptures() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(1, 0, Stone.BLACK);
      board.place(0, 0, Stone.WHITE);
      board.place(0, 1, Stone.BLACK);

      assertEquals(Stone.EMPTY, env.stoneAt(0, 0), "the corner white stone should be captured.");
      assertEquals(Stone.BLACK, env.stoneAt(1, 0));
      assertEquals(Stone.BLACK, env.stoneAt(0, 1));
      assertEquals(1, env.current().getData().blackCaptures);
      assertEquals(0, env.current().getData().whiteCaptures);
      assertEquals(3, env.current().getData().moveNumber);
      assertEquals(0, env.current().getData().moveNumberList[Board.getIndex(0, 0)]);
    }
  }

  @Test
  void multiStoneCaptureRemovesTheWholeChain() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      // Two adjacent white stones on the left edge, then black surrounds both.
      board.place(1, 0, Stone.BLACK);
      board.place(0, 0, Stone.WHITE);
      board.place(1, 1, Stone.BLACK);
      board.place(0, 1, Stone.WHITE);
      board.place(0, 2, Stone.BLACK);

      assertEquals(Stone.EMPTY, env.stoneAt(0, 0));
      assertEquals(Stone.EMPTY, env.stoneAt(0, 1));
      assertEquals(Stone.BLACK, env.stoneAt(1, 0));
      assertEquals(Stone.BLACK, env.stoneAt(1, 1));
      assertEquals(Stone.BLACK, env.stoneAt(0, 2));
      assertEquals(2, env.current().getData().blackCaptures);
      assertEquals(5, env.current().getData().moveNumber);
    }
  }

  @Test
  void suicidalFillIsRejected() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.setupPlaceStone(1, 0, Stone.WHITE);
      board.setupPlaceStone(0, 1, Stone.WHITE);
      BoardHistoryNode root = board.getHistory().getStart();

      board.place(0, 0, Stone.BLACK);

      assertSame(root, env.current(), "suicide should not create a history node.");
      assertEquals(Stone.EMPTY, env.stoneAt(0, 0));
      assertEquals(0, root.numberOfChildren());
    }
  }

  @Test
  void passAndConsecutivePassKeepStonesAndAdvanceMoveNumbers() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(2, 2, Stone.BLACK);
      Stone[] afterPlay = board.getHistory().getStones().clone();

      board.pass(Stone.WHITE);
      BoardHistoryNode firstPass = env.current();
      assertTrue(firstPass.getData().isPassNode());
      assertFalse(firstPass.getData().dummy);
      assertEquals(2, firstPass.getData().moveNumber);
      assertArrayEquals(afterPlay, board.getHistory().getStones());
      assertTrue(board.getHistory().isBlacksTurn());

      board.pass(Stone.BLACK);
      BoardHistoryNode secondPass = env.current();
      assertTrue(secondPass.getData().isPassNode());
      assertEquals(3, secondPass.getData().moveNumber);
      assertArrayEquals(afterPlay, board.getHistory().getStones());
      assertFalse(board.getHistory().isBlacksTurn());
      assertEquals(0, secondPass.getData().blackCaptures);
      assertNotSame(firstPass, secondPass);
      assertEquals(firstPass, secondPass.previous().orElseThrow());
    }
  }

  @Test
  void replayingTheNextPassWalksHistoryInsteadOfForking() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.pass(Stone.BLACK);
      BoardHistoryNode passNode = env.current();
      board.previousMove(false);
      board.pass(Stone.BLACK);

      assertSame(passNode, env.current());
      assertEquals(1, board.getHistory().getStart().numberOfChildren());
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "true,true"})
  void replayingAnnotatedVariationPreservesNodeAndAnalysis(boolean pass, boolean later)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(2, 2, Stone.BLACK);
      BoardHistoryNode parent = env.current();
      if (later) {
        board.place(3, 3, Stone.WHITE);
        board.previousMove(false);
      }
      if (pass) board.pass(Stone.WHITE);
      else board.place(3, 3, Stone.WHITE);
      BoardHistoryNode variation = env.current();
      BoardData data = variation.getData();
      data.comment = "analysed variation";
      data.winrate = 63.5;
      data.scoreMean = 2.25;
      data.setPlayouts(1234);
      data.rootVisits = 1500;
      MoveData best = new MoveData();
      best.coordinate = "A1";
      best.playouts = 1234;
      data.bestMoves.add(best);
      board.place(0, 0, Stone.BLACK);
      BoardHistoryNode continuation = env.current();
      board.previousMove(false);
      board.previousMove(false);

      if (pass) board.pass(Stone.WHITE);
      else board.place(3, 3, Stone.WHITE);

      assertSame(variation, env.current());
      assertEquals(later ? 2 : 1, parent.numberOfChildren());
      assertSame(data, env.current().getData());
      assertEquals("analysed variation", data.comment);
      assertEquals(63.5, data.winrate);
      assertEquals(2.25, data.scoreMean);
      assertEquals(1234, data.getPlayouts());
      assertEquals(1500, data.rootVisits);
      assertEquals(List.of(best), data.bestMoves);
      assertSame(continuation, variation.next().orElseThrow());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "true,BLACK,true,false,true,true,30",
    "true,WHITE,true,false,true,true,30",
    "true,BLACK,false,true,true,true,30",
    "true,WHITE,false,true,true,true,30",
    "true,BLACK,true,false,false,true,3",
    "true,WHITE,false,true,false,true,3",
    "true,WHITE,false,false,true,true,3",
    "true,WHITE,true,false,true,false,3",
    "false,BLACK,true,false,true,true,30",
    "false,WHITE,false,true,true,true,30"
  })
  void laterPassReplayPreservesHumanByoyomiAcrossTheNextEngineMove(
      boolean reuse,
      Stone color,
      boolean playing,
      boolean analysisPlaying,
      boolean humanTurn,
      boolean timerActive,
      int expectedSeconds)
      throws Exception {
    boolean previousShowingByoTime = LizzieFrame.isShowingByoTime;
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(2, 2, color.opposite());
      BoardHistoryNode parent = env.current();
      board.place(3, 3, color);
      board.previousMove(false);
      BoardHistoryNode existingPass = null;
      if (reuse) {
        board.pass(color);
        existingPass = env.current();
        existingPass.getData().comment = "keep this pass";
        board.previousMove(false);
      }

      // Exercise the real clock reset without starting a wall-clock scheduler or engine process.
      Lizzie.setPrimaryEngine(null);
      Lizzie.frame.isPlayingAgainstLeelaz = playing;
      Lizzie.frame.isAnaPlayingAgainstLeelaz = analysisPlaying;
      Lizzie.frame.playerIsBlack = humanTurn == parent.getData().blackToPlay;
      LizzieFrame.isShowingByoTime = timerActive;
      Lizzie.frame.maxByoTimes = 30;
      Lizzie.frame.byoSeconds = 3;
      Lizzie.frame.byoTimes = 5;

      board.pass(color);

      assertEquals(expectedSeconds, Lizzie.frame.byoSeconds);
      assertEquals(5, Lizzie.frame.byoTimes);
      assertEquals(2, parent.numberOfChildren());
      if (reuse) {
        assertSame(existingPass, env.current());
        assertEquals("keep this pass", env.current().getData().comment);
      }
      if (humanTurn) {
        board.place(0, 0, color.opposite());
        assertEquals(Lizzie.frame.playerIsBlack, board.getHistory().isBlacksTurn());
        assertEquals(expectedSeconds, Lizzie.frame.byoSeconds);
        assertEquals(5, Lizzie.frame.byoTimes);
      }
    } finally {
      LizzieFrame.isShowingByoTime = previousShowingByoTime;
    }
  }

  @ParameterizedTest
  @CsvSource({
    "true,false,WHITE,false", "false,true,WHITE,false",
    "false,false,BLACK,false", "false,false,WHITE,true"
  })
  void passReplayKeepsExplicitBranchesDummyAndColorDistinct(
      boolean newBranch, boolean dummy, Stone requestedColor, boolean existingDummy)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(2, 2, Stone.BLACK);
      BoardHistoryNode parent = env.current();
      board.place(3, 3, Stone.WHITE);
      board.previousMove(false);
      board.pass(Stone.WHITE, false, existingDummy);
      BoardHistoryNode variation = env.current();
      variation.getData().comment = "keep this branch";
      board.previousMove(false);

      board.pass(requestedColor, newBranch, dummy);

      assertNotSame(variation, env.current());
      assertEquals(3, parent.numberOfChildren());
      assertEquals(requestedColor, env.current().getData().lastMoveColor);
      assertEquals(dummy, env.current().getData().dummy);
      assertEquals("keep this branch", variation.getData().comment);
    }
  }

  @Test
  void firstPassChildOfAnotherColorIsNotReplayed() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.pass(Stone.BLACK);
      BoardHistoryNode blackPass = env.current();
      board.previousMove(false);
      board.pass(Stone.WHITE);
      assertNotSame(blackPass, env.current());
      assertEquals(Stone.WHITE, env.current().getData().lastMoveColor);
      assertEquals(2, board.getHistory().getStart().numberOfChildren());
    }
  }

  @Test
  void koRecaptureIsRejectedUntilThePositionChanges() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      placeClassicKoShape(board);
      board.setupSetSideToPlay(true);

      board.place(3, 2, Stone.BLACK);
      assertEquals(Stone.EMPTY, env.stoneAt(2, 2), "black should capture the ko stone.");
      assertEquals(Stone.BLACK, env.stoneAt(3, 2));
      assertEquals(1, env.current().getData().blackCaptures);
      BoardHistoryNode afterCapture = env.current();
      Stone[] afterCaptureStones = board.getHistory().getStones().clone();

      board.place(2, 2, Stone.WHITE);
      assertSame(afterCapture, env.current(), "immediate ko recapture should be rejected.");
      assertArrayEquals(afterCaptureStones, board.getHistory().getStones());
      assertEquals(Stone.EMPTY, env.stoneAt(2, 2));
      assertEquals(0, afterCapture.numberOfChildren());

      board.place(0, 0, Stone.WHITE);
      board.place(0, 4, Stone.BLACK);
      board.place(2, 2, Stone.WHITE);

      assertEquals(Stone.WHITE, env.stoneAt(2, 2), "recapture is allowed after a ko threat.");
      assertEquals(Stone.EMPTY, env.stoneAt(3, 2));
      assertEquals(1, env.current().getData().whiteCaptures);
      assertEquals(1, env.current().getData().blackCaptures);
    }
  }

  @Test
  void fixedHandicapReplacesRootWithSetupStonesAndWhiteToPlay() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      Board board = env.board();
      assertTrue(board.setupFixedHandicap(9));

      BoardHistoryNode root = board.getHistory().getStart();
      assertTrue(root.getData().isSnapshotNode());
      assertEquals(0, root.numberOfChildren());
      assertEquals(0, root.getData().moveNumber);
      assertFalse(root.getData().blackToPlay, "fixed handicap should leave White to play.");
      assertEquals(9, board.getHistory().getGameInfo().getHandicap());

      int[][] expected = {
        {3, 3}, {3, 15}, {15, 3}, {15, 15}, {9, 3}, {9, 15}, {3, 9}, {15, 9}, {9, 9}
      };
      for (int[] point : expected) {
        assertEquals(Stone.BLACK, env.stoneAt(point[0], point[1]), Arrays.toString(point));
      }
      int blackCount = 0;
      for (Stone stone : root.getData().stones) {
        if (stone == Stone.BLACK) {
          blackCount++;
        }
      }
      assertEquals(9, blackCount);
    }
  }

  @Test
  void unsupportedHandicapOnSmallBoardIsRejected() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      BoardHistoryNode root = board.getHistory().getStart();
      assertFalse(board.setupFixedHandicap(2));
      assertFalse(board.setupFixedHandicap(0));
      assertSame(root, board.getHistory().getStart());
      assertEquals(0, board.getHistory().getGameInfo().getHandicap());
    }
  }

  @Test
  void rootSetupThenOrdinaryPlayKeepsSetupOnRootAndMoveOnChild() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      assertTrue(board.setupPlaceStone(0, 0, Stone.BLACK));
      assertTrue(board.setupPlaceStone(4, 4, Stone.WHITE));
      assertTrue(board.setupSetSideToPlay(false));

      board.place(1, 1, Stone.WHITE);

      BoardHistoryNode root = board.getHistory().getStart();
      assertTrue(root.getData().isSnapshotNode());
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(4, 4)]);
      assertEquals(1, root.numberOfChildren());
      assertTrue(env.current().getData().isMoveNode());
      assertEquals(1, env.current().getData().moveNumber);
      assertEquals(Stone.WHITE, env.stoneAt(1, 1));
    }
  }

  @Test
  void playingADifferentMoveAfterUndoCreatesAVariationOffTheMainline() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(0, 0, Stone.BLACK);
      board.place(1, 1, Stone.WHITE);
      BoardHistoryNode blackNode = board.getHistory().getStart().next().orElseThrow();
      BoardHistoryNode mainlineWhite = env.current();

      assertTrue(board.previousMove(false));
      board.place(2, 2, Stone.WHITE);

      assertEquals(2, blackNode.numberOfChildren());
      assertSame(mainlineWhite, blackNode.getVariation(0).orElseThrow());
      BoardHistoryNode variation = blackNode.getVariation(1).orElseThrow();
      assertSame(variation, env.current());
      assertTrue(RulesLayerTestHarness.sameLastMove(variation.getData(), 2, 2));
      assertEquals(Stone.WHITE, env.stoneAt(2, 2));
      assertEquals(Stone.EMPTY, env.stoneAt(1, 1), "the variation must not keep the mainline stone.");
      assertEquals(Stone.WHITE, mainlineWhite.getData().stones[Board.getIndex(1, 1)]);
      assertTrue(blackNode.next().isPresent());
      assertSame(
          mainlineWhite,
          blackNode.next().orElseThrow(),
          "mainline next() should remain the first child.");
    }
  }

  @Test
  void explicitNewBranchKeepsExistingRedoAndAddsASibling() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(0, 0, Stone.BLACK);
      board.place(4, 4, Stone.WHITE);
      BoardHistoryNode blackNode = board.getHistory().getStart().next().orElseThrow();
      board.previousMove(false);
      board.place(1, 1, Stone.WHITE, true);

      assertEquals(2, blackNode.numberOfChildren());
      assertTrue(RulesLayerTestHarness.sameLastMove(blackNode.getVariation(0).orElseThrow().getData(), 4, 4));
      assertTrue(RulesLayerTestHarness.sameLastMove(blackNode.getVariation(1).orElseThrow().getData(), 1, 1));
    }
  }

  @Test
  void undoAndRedoRestoreStonesCapturesAndCurrentNode() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(1, 0, Stone.BLACK);
      board.place(0, 0, Stone.WHITE);
      board.place(0, 1, Stone.BLACK);
      BoardHistoryNode afterCapture = env.current();
      Stone[] capturedPosition = board.getHistory().getStones().clone();
      int blackCaptures = afterCapture.getData().blackCaptures;
      int moveNumber = afterCapture.getData().moveNumber;

      assertTrue(board.previousMove(false));
      assertEquals(Stone.WHITE, env.stoneAt(0, 0), "undo should restore the captured stone.");
      assertEquals(0, env.current().getData().blackCaptures);
      assertEquals(moveNumber - 1, env.current().getData().moveNumber);

      assertTrue(board.previousMove(false));
      assertTrue(board.previousMove(false));
      assertEquals(board.getHistory().getStart(), env.current());
      assertFalse(board.previousMove(false), "undo at root should be a no-op.");

      assertTrue(board.nextMove(false));
      assertTrue(board.nextMove(false));
      assertTrue(board.nextMove(false));
      assertSame(afterCapture, env.current());
      assertArrayEquals(capturedPosition, board.getHistory().getStones());
      assertEquals(blackCaptures, env.current().getData().blackCaptures);
      assertEquals(moveNumber, env.current().getData().moveNumber);
      assertFalse(board.nextMove(false), "redo past the end should be a no-op.");
    }
  }

  @Test
  void forwardNavigationSoundsEachDestinationMove() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(1, 0, Stone.BLACK);
      board.place(0, 0, Stone.WHITE);
      board.place(0, 1, Stone.BLACK);
      board.place(4, 4, Stone.WHITE);
      List<BoardHistoryNode> mainline = new ArrayList<>();
      BoardHistoryNode node = board.getHistory().getStart();
      while (node.next().isPresent()) {
        node = node.next().orElseThrow();
        mainline.add(node);
      }
      assertTrue(
          mainline.get(2).getData().blackCaptures > mainline.get(1).getData().blackCaptures,
          "the third move must capture so its sound differs from its neighbours.");
      while (board.previousMove(false)) {}
      List<BoardHistoryNode> sounded = new ArrayList<>();
      Lizzie.config.playSound = true;
      StoneSoundTestSupport.setStoneSound(sounded::add);
      try {
        while (board.nextMove(false)) {}
      } finally {
        StoneSoundTestSupport.setStoneSound(null);
      }

      assertEquals(mainline, sounded);
    }
  }

  @Test
  void goToMoveNumberKeepsCapturesAndNodeIdentity() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(1, 0, Stone.BLACK);
      board.place(0, 0, Stone.WHITE);
      board.place(0, 1, Stone.BLACK);
      board.pass(Stone.WHITE);
      BoardHistoryNode end = env.current();

      assertTrue(board.goToMoveNumber(0));
      assertSame(board.getHistory().getStart(), env.current());
      assertEquals(Stone.EMPTY, env.stoneAt(1, 0));
      assertEquals(0, env.current().getData().blackCaptures);

      assertTrue(board.goToMoveNumber(3));
      assertEquals(Stone.EMPTY, env.stoneAt(0, 0));
      assertEquals(1, env.current().getData().blackCaptures);
      assertEquals(3, env.current().getData().moveNumber);

      assertTrue(board.goToMoveNumber(4));
      assertSame(end, env.current());
      assertTrue(end.getData().isPassNode());
      assertEquals(1, end.getData().blackCaptures);
    }
  }

  @Test
  void nextVariationSwitchesBoardStateWithoutLosingTheOtherBranch() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(0, 0, Stone.BLACK);
      board.place(1, 1, Stone.WHITE);
      BoardHistoryNode blackNode = board.getHistory().getStart().next().orElseThrow();
      board.previousMove(false);
      board.place(2, 2, Stone.WHITE);
      BoardHistoryNode variation = env.current();

      assertTrue(board.previousMove(false));
      assertTrue(board.nextVariation(0));
      assertEquals(Stone.WHITE, env.stoneAt(1, 1));
      assertEquals(Stone.EMPTY, env.stoneAt(2, 2));

      assertTrue(board.previousMove(false));
      assertTrue(board.nextVariation(1));
      assertSame(variation, env.current());
      assertEquals(Stone.WHITE, env.stoneAt(2, 2));
      assertEquals(Stone.EMPTY, env.stoneAt(1, 1));
      assertEquals(2, blackNode.numberOfChildren());
    }
  }

  @Test
  void capturesAndCommentsSurviveJumpingAwayAndBack() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.place(1, 0, Stone.BLACK);
      board.place(0, 0, Stone.WHITE);
      board.place(0, 1, Stone.BLACK);
      env.current().getData().comment = "capture node";
      BoardHistoryNode captureNode = env.current();

      board.goToMoveNumber(0);
      board.goToMoveNumber(3);

      assertSame(captureNode, env.current());
      assertEquals("capture node", env.current().getData().comment);
      assertEquals(1, env.current().getData().blackCaptures);
      assertEquals(Stone.EMPTY, env.stoneAt(0, 0));
      assertEquals(3, env.current().getData().moveNumber);
    }
  }

  private static void placeClassicKoShape(Board board) {
    board.setupPlaceStone(2, 1, Stone.BLACK);
    board.setupPlaceStone(1, 2, Stone.BLACK);
    board.setupPlaceStone(2, 3, Stone.BLACK);
    board.setupPlaceStone(3, 1, Stone.WHITE);
    board.setupPlaceStone(4, 2, Stone.WHITE);
    board.setupPlaceStone(3, 3, Stone.WHITE);
    board.setupPlaceStone(2, 2, Stone.WHITE);
  }
}
