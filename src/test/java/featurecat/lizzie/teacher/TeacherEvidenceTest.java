package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TeacherEvidenceTest {
  @Test
  void copiesTopThreeCandidatesActualMoveAndBoundedPv() {
    BoardHistoryNode parent = new BoardHistoryNode(BoardData.empty(19, 19));
    String actual = Board.convertCoordinatesToName(3, 3).toUpperCase();
    parent.getData().bestMoves =
        List.of(
            move("Q16", 61.5, 3.2, 1200, 20),
            move(actual, 55.0, 1.1, 900, 4),
            move("D4", 52.0, 0.4, 600, 3),
            move("Q4", 51.0, 0.1, 400, 2));
    append(parent, moveData(1, 3, 3, false));

    TeacherEvidence.Position evidence = TeacherEvidence.current(parent).orElseThrow();

    assertEquals(actual, evidence.actualMove);
    assertEquals(3, evidence.candidates.size());
    assertEquals(TeacherEvidence.MAX_PV_MOVES, evidence.candidates.get(0).variation.size());
    assertTrue(evidence.actualWinrateLoss.isPresent());
    assertEquals(6.5, evidence.actualWinrateLoss.getAsDouble(), 0.0001);
  }

  @Test
  void wholeGameSelectsAtMostFortyKeyPositionsInChronologicalOrder() {
    BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(19, 19));
    BoardHistoryNode parent = root;
    for (int moveNumber = 1; moveNumber <= 45; moveNumber++) {
      int x = moveNumber % 19;
      int y = (moveNumber * 3) % 19;
      String actual = Board.convertCoordinatesToName(x, y).toUpperCase();
      parent.getData().bestMoves =
          List.of(
              move("Q16", 70.0, 5.0, 1000 + moveNumber, 3),
              move(actual, 70.0 - moveNumber / 2.0, 3.0, 800, 3));
      parent = append(parent, moveData(moveNumber, x, y, moveNumber % 2 == 0));
    }

    TeacherEvidence.Range range = TeacherEvidence.wholeGame(root);

    assertEquals(45, range.analyzedPositions);
    assertEquals(40, range.positions.size());
    assertEquals(5, range.omittedPositions);
    assertEquals(0, range.positions.get(0).moveNumber);
    assertEquals(44, range.positions.get(range.positions.size() - 1).moveNumber);
    for (int index = 1; index < range.positions.size(); index++) {
      assertTrue(range.positions.get(index - 1).moveNumber < range.positions.get(index).moveNumber);
    }
  }

  @Test
  void genericCornerMoveIsNotInjectedAsAHighConfidenceNamedOpening() {
    BoardHistoryNode parent = new BoardHistoryNode(BoardData.empty(19, 19));
    parent.getData().bestMoves = List.of(move("Q16", 60, 3, 1000, 0));
    append(parent, moveData(1, 3, 15, false));

    String prompt =
        TeacherPromptBuilder.formatPosition(
            TeacherEvidence.current(parent).orElseThrow(), Locale.ENGLISH);

    assertFalse(prompt.contains("Position-specific knowledge:"));
  }

  @Test
  void knowledgeRequiresMultipleIndependentSignalsOrStrongGeometry() {
    featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch weaklyGrounded =
        knowledgeMatch("strong", List.of("phase:opening", "sequence-overlap:1"));
    featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch sequenceGrounded =
        knowledgeMatch("strong", List.of("sequence-overlap:3"));
    featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch signalGrounded =
        knowledgeMatch("strong", List.of("candidate:corner", "pv:approach"));

    assertFalse(TeacherEvidence.hasGroundedKnowledgeEvidence(weaklyGrounded));
    assertTrue(TeacherEvidence.hasGroundedKnowledgeEvidence(sequenceGrounded));
    assertTrue(TeacherEvidence.hasGroundedKnowledgeEvidence(signalGrounded));
  }

  @ParameterizedTest
  @CsvSource({"true, false", "false, false", "true, true", "false, true"})
  void actualMoveOutsideTopThreeKeepsItsOwnEvaluationAndPv(
      boolean blackToPlay, boolean blackScorePerspective) {
    BoardHistoryNode parent = new BoardHistoryNode(BoardData.empty(19, 19));
    parent.getData().blackToPlay = blackToPlay;
    MoveData best = move("Q16", 60, 3.0, 1200, 0);
    best.variation = List.of("Q16", "D16");
    MoveData played = move("D4", 20, 2.5, 400, 0);
    played.variation = List.of("D4", "Q4");
    parent.getData().bestMoves =
        List.of(best, move("D16", 58, 2.9, 900, 0), move("Q4", 55, 2.7, 700, 0), played);
    for (MoveData candidate : parent.getData().bestMoves) {
      candidate.scoreMeanIsBlackPerspective = blackScorePerspective;
      if (blackScorePerspective && !blackToPlay) {
        candidate.scoreMean = -candidate.scoreMean;
      }
    }
    append(parent, moveData(1, 3, 15, !blackToPlay));

    TeacherEvidence.Position evidence = TeacherEvidence.current(parent).orElseThrow();

    assertEquals(4, evidence.candidates.size());
    assertEquals(4, evidence.candidates.get(3).rank);
    assertEquals("D4", evidence.candidates.get(3).coordinate);
    assertEquals(List.of("D4", "Q4"), evidence.candidates.get(3).variation);
    assertEquals(2.5, evidence.candidates.get(3).scoreLead);
    assertEquals(40, evidence.actualWinrateLoss.orElseThrow());
    String prompt = TeacherPromptBuilder.formatPosition(evidence, Locale.ENGLISH);
    assertTrue(prompt.contains("40.0 percentage points"));
    assertTrue(prompt.contains("score loss (points, NOT percentage points): 0.5"));
    assertTrue(prompt.contains("Candidate D4: 1." + (blackToPlay ? "B" : "W") + " D4"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"snapshot", "dummy", "wrong-color", "wrong-next-turn", "jump-number", "removed-stone"})
  void invalidRecordedTransitionIsNotDescribedAsAnActualMove(String invalidKind) {
    BoardHistoryNode parent = new BoardHistoryNode(BoardData.empty(19, 19));
    parent.getData().bestMoves = List.of(move("Q16", 60, 3, 1000, 0));
    BoardData after = moveData(1, 3, 15, false);
    if (invalidKind.equals("snapshot")) {
      after =
          BoardData.snapshot(
              after.stones,
              Optional.of(new int[] {3, 15}),
              Stone.BLACK,
              false,
              after.zobrist,
              1,
              after.moveNumberList,
              0,
              0,
              50,
              0);
    } else if (invalidKind.equals("dummy")) {
      after.dummy = true;
    } else if (invalidKind.equals("wrong-color")) {
      after.lastMoveColor = Stone.WHITE;
    } else if (invalidKind.equals("wrong-next-turn")) {
      after.blackToPlay = true;
    } else if (invalidKind.equals("jump-number")) {
      after.moveNumber = 2;
    }
    BoardHistoryNode child = append(parent, after);
    if (invalidKind.equals("removed-stone")) {
      child.setRemovedStone();
    }

    TeacherEvidence.Position evidence = TeacherEvidence.current(parent).orElseThrow();

    assertEquals("", evidence.actualMove);
    assertTrue(evidence.playedContinuation.isEmpty());
    assertTrue(evidence.actualWinrateLoss.isEmpty());
    assertEquals(0, TeacherEvidence.wholeGame(parent).analyzedPositions);
  }

  @Test
  void recordedPassKeepsParityAndTheNextInvalidNodeStopsTheContinuation() {
    BoardHistoryNode parent = new BoardHistoryNode(BoardData.empty(19, 19));
    parent.getData().bestMoves = List.of(move("Q16", 60, 3, 1000, 0));
    BoardData pass =
        BoardData.pass(
            parent.getData().stones.clone(), Stone.BLACK, false, new Zobrist(), 1,
            new int[361], 0, 0, 50, 0);
    BoardHistoryNode passed = append(parent, pass);
    BoardHistoryNode reply = append(passed, moveData(2, 15, 3, true));
    append(reply, moveData(4, 3, 15, false)); // A missing move must not be silently skipped.

    TeacherEvidence.Position evidence = TeacherEvidence.current(parent).orElseThrow();
    assertEquals("pass", evidence.actualMove);
    assertEquals(List.of("pass", "Q16"), evidence.playedContinuation);
    String prompt = TeacherPromptBuilder.formatPosition(evidence, Locale.ENGLISH);
    assertTrue(prompt.contains("Recorded continuation: 1.B pass; 2.W Q16;"));
    assertFalse(prompt.contains("D4"));
  }

  @Test
  void promptAndVerifierExcludeIllegalPvSuffixOnTheProductionCapturePath() {
    BoardHistoryNode parent = new BoardHistoryNode(BoardData.empty(19, 19));
    MoveData candidate = move("D4", 60, 3, 1000, 0);
    candidate.variation = List.of("D4", "D4", "T1");
    parent.getData().bestMoves = List.of(candidate);

    TeacherEvidence.Position evidence = TeacherEvidence.current(parent).orElseThrow();
    String prompt = TeacherPromptBuilder.formatPosition(evidence, Locale.ENGLISH);

    assertTrue(prompt.contains("STOPPED"));
    assertFalse(prompt.contains("T1"));
    assertFalse(TeacherVerifier.verify("The continuation ends at T1.", evidence).ok());
  }

  @Test
  void actualMoveWithoutCandidateEvaluationHasUnknownLossDespiteChildDefaults() {
    BoardHistoryNode parent = new BoardHistoryNode(BoardData.empty(19, 19));
    parent.getData().bestMoves = List.of(move("Q16", 60, 3, 1000, 0));
    BoardHistoryNode child = append(parent, moveData(1, 3, 15, false));
    child.getData().winrate = 0;
    child.getData().scoreMean = 0;

    TeacherEvidence.Position evidence = TeacherEvidence.current(parent).orElseThrow();
    String prompt = TeacherPromptBuilder.formatPosition(evidence, Locale.ENGLISH);

    assertEquals("D4", evidence.actualMove);
    assertTrue(evidence.actualWinrateLoss.isEmpty());
    assertFalse(prompt.contains("Actual move score loss"));
    assertFalse(prompt.contains("Actual move winrate loss"));
  }

  private static featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch knowledgeMatch(
      String confidence, List<String> reasons) {
    featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch match =
        new featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch();
    match.confidence = confidence;
    match.reason = new ArrayList<>(reasons);
    return match;
  }

  private static MoveData move(
      String coordinate, double winrate, double scoreMean, int visits, int variationLength) {
    MoveData move = new MoveData();
    move.coordinate = coordinate;
    move.winrate = winrate;
    move.scoreMean = scoreMean;
    move.playouts = visits;
    move.variation = new ArrayList<>();
    for (int index = 0; index < variationLength; index++) {
      move.variation.add(index % 2 == 0 ? "D4" : "Q16");
    }
    return move;
  }

  private static BoardData moveData(int moveNumber, int x, int y, boolean blackToPlayAfterMove) {
    Stone[] stones = new Stone[19 * 19];
    Arrays.fill(stones, Stone.EMPTY);
    return BoardData.move(
        stones,
        new int[] {x, y},
        blackToPlayAfterMove ? Stone.WHITE : Stone.BLACK,
        blackToPlayAfterMove,
        new Zobrist(),
        moveNumber,
        new int[19 * 19],
        0,
        0,
        50.0,
        0);
  }

  private static BoardHistoryNode append(BoardHistoryNode parent, BoardData data) {
    BoardHistoryNode child = new BoardHistoryNode(data);
    parent.variations.add(child);
    parent.setPreviousForChild(child);
    return child;
  }
}
