package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TeacherBoardContextTest {
  private int previousWidth;
  private int previousHeight;
  private Object previousZobristTables;

  @BeforeEach
  void setBoardSize() throws ReflectiveOperationException {
    previousWidth = Board.boardWidth;
    previousHeight = Board.boardHeight;
    Method captureTables = Zobrist.class.getDeclaredMethod("captureTables");
    captureTables.setAccessible(true);
    previousZobristTables = captureTables.invoke(null);
    Board.boardWidth = 19;
    Board.boardHeight = 19;
    Zobrist.init();
  }

  @AfterEach
  void restoreBoardSize() throws ReflectiveOperationException {
    Board.boardWidth = previousWidth;
    Board.boardHeight = previousHeight;
    Method restoreTables =
        Zobrist.class.getDeclaredMethod("restoreTables", previousZobristTables.getClass());
    restoreTables.setAccessible(true);
    restoreTables.invoke(null, previousZobristTables);
  }

  @Test
  void freezesColumnMajorCoordinatesAndCaptureWithoutMutatingTheSource() {
    BoardHistoryNode node = capturePosition();
    stone(node.getData(), 3, 15, Stone.BLACK); // D4, not Q16.
    Stone[] originalStones = node.getData().stones.clone();
    Zobrist originalHash = node.getData().zobrist.clone();

    TeacherEvidence.Position position = TeacherEvidence.current(node).orElseThrow();
    var query = TeacherEvidence.knowledgeQuery(node, position, position.board);

    assertTrue(position.board.facts().contains("W stones=[A19] liberties=[A18]"));
    assertTrue(position.board.facts().contains("B stones=[D4]"));
    assertTrue(
        query.boardSnapshot.stream()
            .anyMatch(snapshot -> snapshot.point.equals("D4") && snapshot.color.equals("B")));
    assertFalse(query.boardSnapshot.stream().anyMatch(snapshot -> snapshot.point.equals("Q16")));
    var capture = position.board.lines.get(0).frames().get(1);
    assertEquals(1, capture.captures());
    assertEquals(Stone.EMPTY, capture.stones().get(0));
    assertArrayEquals(originalStones, node.getData().stones);
    assertEquals(originalHash, node.getData().zobrist);
    assertEquals(0, node.getData().blackCaptures);
    assertTrue(node.variations.isEmpty());

    String frozenPrompt = TeacherPromptBuilder.formatPosition(position, Locale.ENGLISH);
    node.getData().stones[0] = Stone.EMPTY;
    node.getData().bestMoves.get(0).coordinate = "T1";
    node.getData().bestMoves.get(0).variation = List.of("T1");
    assertEquals(frozenPrompt, TeacherPromptBuilder.formatPosition(position, Locale.ENGLISH));
    assertTrue(TeacherVerifier.verify("The stone at A19 has a liberty at A18.", position).ok());
  }

  @ParameterizedTest
  @ValueSource(strings = {"A18", "D40", "I4", "Z1", "resign", ""})
  void illegalOrMalformedPvStopsWithoutReintroducingItsSuffix(String invalidMove) {
    BoardHistoryNode node = capturePosition();
    node.getData().bestMoves.get(0).variation = List.of("A18", invalidMove, "T1");

    TeacherEvidence.Position position = TeacherEvidence.current(node).orElseThrow();
    var line = position.board.lines.get(0);
    assertTrue(line.truncated());
    assertEquals(List.of("A18"), line.moves());
    assertEquals(3, position.candidates.get(0).variation.size());
    String prompt = TeacherPromptBuilder.formatPosition(position, Locale.ENGLISH);
    assertTrue(prompt.contains("captures=1"));
    assertTrue(prompt.contains("STOPPED"));
    assertFalse(prompt.contains("T1"));
    assertEquals(
        List.of("A18"),
        TeacherEvidence.knowledgeQuery(node, position, position.board).principalVariation);
    assertFalse(TeacherVerifier.verify("Play T1 next.", position).ok());
  }

  @Test
  void mismatchedPvRootDoesNotDescribeAnotherCandidatesLine() {
    BoardHistoryNode node = capturePosition();
    node.getData().bestMoves.get(0).variation = List.of("D4", "T1");
    TeacherEvidence.Position position = TeacherEvidence.current(node).orElseThrow();

    assertEquals(List.of("A18"), position.board.lines.get(0).moves());
    assertTrue(position.board.lines.get(0).truncated());
    assertFalse(TeacherPromptBuilder.formatPosition(position, Locale.ENGLISH).contains("T1"));
    assertFalse(TeacherVerifier.verify("Follow with D4.", position).ok());
  }

  @Test
  void replayedPassKeepsMoveColorsAndDoesNotTouchForegroundHistory() {
    BoardHistoryNode node = capturePosition();
    node.getData().bestMoves.get(0).variation = List.of("A18", "pass", "D4");

    TeacherEvidence.Position position = TeacherEvidence.current(node).orElseThrow();
    var frames = position.board.lines.get(0).frames();
    assertEquals(List.of("A18", "pass", "D4"), position.board.lines.get(0).moves());
    assertEquals("B", frames.get(1).color());
    assertEquals("W", frames.get(2).color());
    assertEquals("pass", frames.get(2).move());
    assertEquals("B", frames.get(3).color());
    assertEquals(frames.get(1).stones(), frames.get(2).stones());
    assertTrue(node.variations.isEmpty());
  }

  @Test
  void suicideStopsBeforeInventingCaptures() {
    BoardHistoryNode node = capturePosition();
    node.getData().blackToPlay = false;
    stone(node.getData(), 1, 1, Stone.BLACK);
    stone(node.getData(), 0, 2, Stone.BLACK); // A19, B18 and A17 surround A18.
    node.getData().stones[0] = Stone.BLACK;
    node.getData().zobrist = hash(node.getData().stones);

    TeacherEvidence.Position position = TeacherEvidence.current(node).orElseThrow();
    assertTrue(position.board.lines.get(0).truncated());
    assertTrue(position.board.lines.get(0).moves().isEmpty());
    assertEquals(1, position.board.lines.get(0).frames().size());
    assertEquals(0, node.getData().whiteCaptures);
  }

  @Test
  void firstReplayMoveRespectsSimpleKoFromThePreviousPosition() {
    BoardData before = BoardData.empty(19, 19);
    // B captures the single W stone at B17 by playing B18; W cannot immediately recapture.
    stone(before, 1, 2, Stone.WHITE);
    stone(before, 0, 2, Stone.BLACK);
    stone(before, 2, 2, Stone.BLACK);
    stone(before, 1, 3, Stone.BLACK);
    stone(before, 0, 1, Stone.WHITE);
    stone(before, 2, 1, Stone.WHITE);
    stone(before, 1, 0, Stone.WHITE);
    BoardHistoryNode previous = new BoardHistoryNode(before);
    BoardData after = before.clone();
    after.stones[Board.getIndex(1, 2)] = Stone.EMPTY;
    after.stones[Board.getIndex(1, 1)] = Stone.BLACK;
    after.zobrist = hash(after.stones);
    after.blackToPlay = false;
    BoardHistoryNode node = append(previous, after);
    node.getData().bestMoves = List.of(candidate("B17", List.of("B17", "T1")));

    TeacherEvidence.Position position = TeacherEvidence.current(node).orElseThrow();
    assertTrue(position.board.lines.get(0).truncated());
    assertTrue(position.board.lines.get(0).moves().isEmpty());
    assertFalse(TeacherPromptBuilder.formatPosition(position, Locale.ENGLISH).contains("T1"));
    assertEquals(Stone.BLACK, node.getData().stones[Board.getIndex(1, 1)]);
  }

  @Test
  void rectangularBoardAndLaterBoardSizeChangeDoNotAlterFrozenCoordinates() {
    Board.boardWidth = 13;
    Board.boardHeight = 9;
    Zobrist.init();
    BoardHistoryNode node = new BoardHistoryNode(BoardData.empty(13, 9));
    stone(node.getData(), 3, 5, Stone.BLACK); // D4 on 13x9.
    node.getData().bestMoves = List.of(candidate("M9", List.of("M9", "A1")));
    TeacherEvidence.Position position = TeacherEvidence.current(node).orElseThrow();
    String prompt = TeacherPromptBuilder.formatPosition(position, Locale.ENGLISH);
    assertTrue(prompt.contains("Board: 13x9"));
    assertTrue(prompt.contains("B stones=[D4]"));
    assertEquals("", position.knowledge); // The square-only matcher must not guess dimensions.

    Board.boardWidth = 19;
    Board.boardHeight = 19;
    Zobrist.init();
    assertEquals(prompt, TeacherPromptBuilder.formatPosition(position, Locale.ENGLISH));
    assertTrue(TeacherVerifier.verify("Compare the stone at D4 with M9.", position).ok());
  }

  @Test
  void rangeFreezesTheSelectedSourceWhenSetupNodesShareAMoveNumber() {
    BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(19, 19));
    BoardHistoryNode analyzed = capturePosition();
    root.variations.add(analyzed);
    root.setPreviousForChild(analyzed);
    BoardData after =
        BoardData.pass(
            analyzed.getData().stones.clone(),
            Stone.BLACK,
            false,
            analyzed.getData().zobrist.clone(),
            1,
            new int[361],
            0,
            0,
            50,
            0);
    append(analyzed, after);

    TeacherEvidence.Range range = TeacherEvidence.wholeGame(root);
    assertEquals(1, range.positions.size());
    assertEquals(Stone.WHITE, range.positions.get(0).board.stones.get(0));
    assertEquals(Stone.EMPTY, root.getData().stones[0]);
    assertEquals(List.of("pass"), range.positions.get(0).playedContinuation);
  }

  @Test
  void absentActualEvaluationRemainsUnknownAndSetupMarkersDoNotJoinKnowledgeSequences() {
    BoardHistoryNode node = capturePosition();
    BoardData source = node.getData();
    BoardHistoryNode snapshot =
        append(
            node,
            BoardData.snapshot(
                source.stones.clone(),
                Optional.of(new int[] {3, 15}),
                Stone.BLACK,
                false,
                source.zobrist.clone(),
                1,
                new int[361],
                0,
                0,
                50,
                0));
    snapshot.getData().bestMoves = List.of(candidate("Q16", List.of("Q16")));
    TeacherEvidence.Position position = TeacherEvidence.current(snapshot).orElseThrow();

    assertTrue(position.actualMove.isEmpty());
    assertTrue(position.actualWinrateLoss.isEmpty());
    assertTrue(position.actualScoreLoss().isEmpty());
    var query = TeacherEvidence.knowledgeQuery(snapshot, position, position.board);
    assertNull(query.lossScore);
    assertTrue(query.recentMoves.isEmpty());
  }

  private static BoardHistoryNode capturePosition() {
    BoardHistoryNode node = new BoardHistoryNode(BoardData.empty(19, 19));
    stone(node.getData(), 0, 0, Stone.WHITE);
    stone(node.getData(), 1, 0, Stone.BLACK);
    node.getData().bestMoves = List.of(candidate("A18", List.of("A18", "Q16", "D16")));
    return node;
  }

  private static MoveData candidate(String coordinate, List<String> variation) {
    MoveData candidate = new MoveData();
    candidate.coordinate = coordinate;
    candidate.playouts = 500;
    candidate.winrate = 60;
    candidate.scoreMean = 3;
    candidate.isKataData = true;
    candidate.variation = variation;
    return candidate;
  }

  private static void stone(BoardData data, int x, int y, Stone color) {
    data.stones[Board.getIndex(x, y)] = color;
    data.zobrist.toggleStone(x, y, color);
  }

  private static Zobrist hash(Stone[] stones) {
    Zobrist result = new Zobrist();
    for (int x = 0; x < Board.boardWidth; x++) {
      for (int y = 0; y < Board.boardHeight; y++) {
        result.toggleStone(x, y, stones[Board.getIndex(x, y)]);
      }
    }
    return result;
  }

  private static BoardHistoryNode append(BoardHistoryNode parent, BoardData data) {
    BoardHistoryNode child = new BoardHistoryNode(data);
    parent.variations.add(child);
    parent.setPreviousForChild(child);
    return child;
  }
}
