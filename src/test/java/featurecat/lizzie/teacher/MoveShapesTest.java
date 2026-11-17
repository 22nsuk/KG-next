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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoveShapesTest {
  private record Point(int x, int y) {}

  private record Fixture(
      MoveShapes.Kind kind,
      String korean,
      int width,
      int height,
      Point move,
      List<Point> own,
      List<Point> opponents) {}

  private int previousWidth;
  private int previousHeight;
  private Object previousTables;

  @BeforeEach
  void preserveBoardGeometry() throws ReflectiveOperationException {
    previousWidth = Board.boardWidth;
    previousHeight = Board.boardHeight;
    Method capture = Zobrist.class.getDeclaredMethod("captureTables");
    capture.setAccessible(true);
    previousTables = capture.invoke(null);
  }

  @AfterEach
  void restoreBoardGeometry() throws ReflectiveOperationException {
    Board.boardWidth = previousWidth;
    Board.boardHeight = previousHeight;
    Method restore = Zobrist.class.getDeclaredMethod("restoreTables", previousTables.getClass());
    restore.setAccessible(true);
    restore.invoke(null, previousTables);
  }

  static Stream<Arguments> namedShapes() {
    List<Fixture> examples =
        List.of(
            fixture(MoveShapes.Kind.DIAGONAL, "마늘모", p(5, 4), List.of(p(4, 3)), List.of()),
            fixture(MoveShapes.Kind.ONE_SPACE_JUMP, "한 칸 뜀", p(5, 4), List.of(p(5, 2)), List.of()),
            fixture(MoveShapes.Kind.KNIGHTS_MOVE, "날일자", p(5, 4), List.of(p(4, 2)), List.of()),
            fixture(MoveShapes.Kind.HANE, "젖힘", p(5, 4), List.of(p(4, 3)), List.of(p(5, 3))),
            fixture(MoveShapes.Kind.CONNECTION, "이음", p(5, 4), List.of(p(4, 4), p(6, 4)), List.of()),
            fixture(MoveShapes.Kind.CUT, "끊음", p(5, 4), List.of(p(4, 3)), List.of(p(4, 4), p(5, 3))),
            new Fixture(MoveShapes.Kind.THREE_THREE_INVASION, "삼삼 침입", 19, 13,
                p(2, 2), List.of(), List.of(p(3, 3))),
            // Edge/corner examples catch indexing wraparound as well as color/orientation errors.
            fixture(MoveShapes.Kind.ONE_SPACE_JUMP, "한 칸 뜀", p(0, 0), List.of(p(0, 2)), List.of()),
            fixture(MoveShapes.Kind.KNIGHTS_MOVE, "날일자", p(0, 0), List.of(p(1, 2)), List.of()),
            fixture(MoveShapes.Kind.HANE, "젖힘", p(0, 2), List.of(p(1, 1)), List.of(p(0, 1))),
            fixture(MoveShapes.Kind.CONNECTION, "이음", p(0, 3), List.of(p(0, 2), p(1, 3)), List.of()),
            fixture(MoveShapes.Kind.CUT, "끊음", p(1, 3), List.of(p(0, 2)), List.of(p(0, 3), p(1, 2))));
    List<Arguments> cases = new ArrayList<>();
    for (Fixture example : examples) {
      for (int symmetry = 0; symmetry < 8; symmetry++) {
        for (boolean black : new boolean[] {false, true}) {
          cases.add(Arguments.of(example, symmetry, black));
        }
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}, symmetry={1}, black={2}")
  @MethodSource("namedShapes")
  void namesShapesAndTheirActualSupportingStonesUnderEverySymmetryAndColor(
      Fixture original, int symmetry, boolean black) {
    Fixture fixture = transform(original, symmetry);
    TeacherEvidence.Position position = capture(fixture, black);
    MoveShapes.Shape shape = shape(position).orElseThrow();

    assertEquals(fixture.kind, shape.kind());
    assertEquals(black, shape.black());
    assertEquals(coordinates(fixture.own, fixture.height), new HashSet<>(shape.ownStones()));
    assertEquals(coordinates(fixture.opponents, fixture.height), new HashSet<>(shape.opponentStones()));
    assertTrue(shape.describe(Locale.KOREAN).startsWith(fixture.korean + " ("));
    assertFalse(shape.describe(Locale.ENGLISH).contains(fixture.korean));
    String facts = position.board.facts(Locale.KOREAN);
    assertTrue(facts.contains("Candidate " + coordinate(fixture.move, fixture.height) + ": " + fixture.korean));
    assertTrue(TeacherVerifier.verify(shape.describe(Locale.KOREAN), position).ok(),
        "Supporting stones use existing coordinate evidence.");
    assertFalse(TeacherVerifier.verify("Invented Z99.", position).ok());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void alreadyConnectedGroupsAreNotNewConnectionsOrCuts(boolean black) {
    Fixture connection = fixture(MoveShapes.Kind.CONNECTION, "이음", p(5, 4),
        List.of(p(4, 4), p(4, 3), p(4, 2), p(5, 2), p(6, 2), p(6, 3), p(6, 4)), List.of());
    Fixture cut = fixture(MoveShapes.Kind.CUT, "끊음", p(5, 4), List.of(p(4, 3)),
        List.of(p(4, 4), p(3, 4), p(3, 3), p(3, 2), p(4, 2), p(5, 2), p(5, 3)));
    for (int symmetry = 0; symmetry < 8; symmetry++) {
      assertTrue(shape(capture(transform(connection, symmetry), black)).isEmpty());
      assertTrue(shape(capture(transform(cut, symmetry), black)).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void contactWithoutItsSupportingStoneAndOppositeWedgesStayUnnamed(boolean black) {
    for (List<Point> opponents : List.of(
        List.of(p(5, 3)), List.of(p(4, 4), p(5, 3)), List.of(p(4, 4), p(6, 4)))) {
      Fixture missing = fixture(MoveShapes.Kind.HANE, "젖힘", p(5, 4), List.of(), opponents);
      for (int symmetry = 0; symmetry < 8; symmetry++) {
        assertTrue(shape(capture(transform(missing, symmetry), black)).isEmpty());
      }
    }
    Fixture ambiguousHane = fixture(MoveShapes.Kind.HANE, "젖힘", p(5, 4),
        List.of(p(4, 3), p(6, 3)), List.of(p(5, 3)));
    assertTrue(shape(capture(ambiguousHane, black)).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void interveningStonesDoNotKeepTheDistantShapeName(boolean friendlyBlocker) {
    List<Fixture> relations = List.of(
        fixture(MoveShapes.Kind.DIAGONAL, "마늘모", p(5, 4), List.of(p(4, 3)), List.of()),
        fixture(MoveShapes.Kind.ONE_SPACE_JUMP, "한 칸 뜀", p(5, 4), List.of(p(5, 2)), List.of()),
        fixture(MoveShapes.Kind.KNIGHTS_MOVE, "날일자", p(5, 4), List.of(p(4, 2)), List.of()));
    for (Fixture relation : relations) {
      Point anchor = relation.own.get(0);
      for (int x = Math.min(anchor.x, relation.move.x); x <= Math.max(anchor.x, relation.move.x); x++) {
        for (int y = Math.min(anchor.y, relation.move.y); y <= Math.max(anchor.y, relation.move.y); y++) {
          Point blocker = p(x, y);
          if (blocker.equals(anchor) || blocker.equals(relation.move)) continue;
          List<Point> owns = new ArrayList<>(relation.own);
          if (friendlyBlocker) owns.add(blocker);
          Fixture blocked = fixture(relation.kind, relation.korean, relation.move, owns,
              friendlyBlocker ? List.of() : List.of(blocker));
          Optional<MoveShapes.Shape> result = shape(capture(blocked, true));
          // A blocker can create a different supported contact shape, such as a hane or cut.
          assertTrue(result.isEmpty() || result.get().kind() != relation.kind, blocker.toString());
        }
      }
    }
  }

  @Test
  void ambiguousCornersDoNotClaimAnInvasion() {
    List<Fixture> corners = List.of(
        new Fixture(MoveShapes.Kind.THREE_THREE_INVASION, "삼삼 침입", 19, 13, p(2, 2), List.of(), List.of()),
        new Fixture(MoveShapes.Kind.THREE_THREE_INVASION, "삼삼 침입", 19, 13, p(2, 2), List.of(p(3, 3)), List.of()),
        new Fixture(MoveShapes.Kind.THREE_THREE_INVASION, "삼삼 침입", 19, 13, p(2, 2), List.of(p(5, 5)), List.of(p(3, 3))),
        new Fixture(MoveShapes.Kind.THREE_THREE_INVASION, "삼삼 침입", 19, 13, p(2, 2), List.of(), List.of(p(3, 3), p(5, 5))),
        new Fixture(MoveShapes.Kind.THREE_THREE_INVASION, "삼삼 침입", 19, 13, p(1, 1), List.of(), List.of(p(3, 3))),
        new Fixture(MoveShapes.Kind.THREE_THREE_INVASION, "삼삼 침입", 9, 9, p(2, 2), List.of(), List.of(p(3, 3))));
    for (Fixture corner : corners) {
      for (int symmetry = 0; symmetry < 8; symmetry++) {
        Optional<MoveShapes.Shape> result = shape(capture(transform(corner, symmetry), true));
        assertTrue(result.isEmpty() || result.get().kind() != MoveShapes.Kind.THREE_THREE_INVASION);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"D04", "D+4", "D40", "I4", "Z1", "D0", "D20", "(3,15)", "resign", ""})
  void invalidCoordinatesCannotEnterTheShapeEvidence(String coordinate) {
    Fixture fixture = new Fixture(MoveShapes.Kind.ONE_SPACE_JUMP, "한 칸 뜀", 19, 19,
        p(3, 15), List.of(p(3, 13)), List.of());
    assertTrue(TeacherEvidence.current(node(fixture, true, coordinate)).isEmpty());
  }

  @Test
  void passOccupiedPointsAndUnrelatedEdgeStonesStayUnnamed() {
    Fixture fixture = fixture(MoveShapes.Kind.ONE_SPACE_JUMP, "한 칸 뜀", p(0, 3),
        List.of(p(12, 2)), List.of());
    assertTrue(shape(capture(fixture, true)).isEmpty());
    TeacherEvidence.Position pass = TeacherEvidence.current(node(fixture, true, "pass")).orElseThrow();
    assertTrue(shape(pass).isEmpty());
    TeacherEvidence.Position occupied = TeacherEvidence.current(
        node(fixture, true, coordinate(p(12, 2), fixture.height))).orElseThrow();
    assertTrue(occupied.board.lines.get(0).moves().isEmpty());
    assertTrue(shape(occupied).isEmpty());
  }

  private static Fixture fixture(MoveShapes.Kind kind, String korean, Point move,
      List<Point> own, List<Point> opponents) {
    return new Fixture(kind, korean, 13, 9, move, own, opponents);
  }

  private static Point p(int x, int y) {
    return new Point(x, y);
  }

  private static Fixture transform(Fixture fixture, int symmetry) {
    int turns = symmetry % 4;
    return new Fixture(fixture.kind, fixture.korean,
        turns % 2 == 0 ? fixture.width : fixture.height,
        turns % 2 == 0 ? fixture.height : fixture.width,
        transform(fixture.move, fixture.width, fixture.height, symmetry),
        fixture.own.stream().map(p -> transform(p, fixture.width, fixture.height, symmetry)).toList(),
        fixture.opponents.stream().map(p -> transform(p, fixture.width, fixture.height, symmetry)).toList());
  }

  private static Point transform(Point point, int width, int height, int symmetry) {
    int x = symmetry >= 4 ? width - 1 - point.x : point.x;
    int y = point.y;
    for (int turn = 0; turn < symmetry % 4; turn++) {
      int rotatedX = height - 1 - y;
      y = x;
      x = rotatedX;
      int previousWidth = width;
      width = height;
      height = previousWidth;
    }
    return p(x, y);
  }

  private static String coordinate(Point point, int height) {
    return String.valueOf((char) ('A' + point.x + (point.x >= 8 ? 1 : 0))) + (height - point.y);
  }

  private static HashSet<String> coordinates(List<Point> points, int height) {
    HashSet<String> result = new HashSet<>();
    for (Point point : points) result.add(coordinate(point, height));
    return result;
  }

  private static BoardHistoryNode node(Fixture fixture, boolean black, String coordinate) {
    Board.boardWidth = fixture.width;
    Board.boardHeight = fixture.height;
    Zobrist.init();
    BoardData data = BoardData.empty(fixture.width, fixture.height);
    data.blackToPlay = black;
    for (Point point : fixture.own) put(data, point, black ? Stone.BLACK : Stone.WHITE);
    for (Point point : fixture.opponents) put(data, point, black ? Stone.WHITE : Stone.BLACK);
    MoveData candidate = new MoveData();
    candidate.coordinate = coordinate;
    candidate.playouts = 100;
    candidate.winrate = 50;
    candidate.variation = List.of(coordinate);
    data.bestMoves = List.of(candidate);
    return new BoardHistoryNode(data);
  }

  private static void put(BoardData data, Point point, Stone color) {
    data.stones[Board.getIndex(point.x, point.y)] = color;
    data.zobrist.toggleStone(point.x, point.y, color);
  }

  private static TeacherEvidence.Position capture(Fixture fixture, boolean black) {
    return TeacherEvidence.current(node(fixture, black, coordinate(fixture.move, fixture.height))).orElseThrow();
  }

  private static Optional<MoveShapes.Shape> shape(TeacherEvidence.Position position) {
    return MoveShapes.describe(position.board, position.board.lines.get(0));
  }
}
