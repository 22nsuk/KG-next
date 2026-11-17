package featurecat.lizzie.teacher;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Narrow, geometric move names adapted from upstream #735; no engine or live-board reads. */
final class MoveShapes {
  enum Kind {
    DIAGONAL("diagonal move", "마늘모"),
    ONE_SPACE_JUMP("one-space jump", "한 칸 뜀"),
    KNIGHTS_MOVE("knight's move", "날일자"),
    HANE("hane", "젖힘"),
    CONNECTION("connection", "이음"),
    CUT("cut", "끊음"),
    THREE_THREE_INVASION("3-3 invasion", "삼삼 침입");

    final String english;
    final String korean;

    Kind(String english, String korean) {
      this.english = english;
      this.korean = korean;
    }
  }

  record Shape(Kind kind, boolean black, List<String> ownStones, List<String> opponentStones) {
    Shape {
      ownStones = List.copyOf(ownStones);
      opponentStones = List.copyOf(opponentStones);
    }

    String describe(Locale locale) {
      boolean korean = locale != null && "ko".equals(locale.getLanguage());
      String own = colored(ownStones, black, korean);
      String opponent = colored(opponentStones, !black, korean);
      String detail =
          switch (kind) {
            case CONNECTION -> (korean ? "서로 다른 연결군: " : "joining distinct groups: ") + own;
            case CUT ->
                (korean ? "서로 다른 상대 연결군: " : "between distinct opposing groups: ")
                    + opponent
                    + (korean ? "; 반대 대각 받침: " : "; opposite diagonal support: ")
                    + own;
            case HANE ->
                (korean ? "받침: " : "support: ")
                    + own
                    + (korean ? "; 상대: " : "; around: ")
                    + opponent;
            case THREE_THREE_INVASION ->
                (korean ? "상대 화점: " : "under opposing 4-4 stone: ") + opponent;
            default -> (korean ? "기준: " : "from: ") + own;
          };
      return (korean ? kind.korean : kind.english) + " (" + detail + ")";
    }

    private static String colored(List<String> stones, boolean black, boolean korean) {
      String color = korean ? (black ? "흑 " : "백 ") : (black ? "B " : "W ");
      return String.join(", ", stones.stream().map(point -> color + point).toList());
    }
  }

  private record Point(int x, int y) {}

  private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

  private MoveShapes() {}

  /** Only the first accepted replay move is classified; omitted/illegal suffixes stay unnamed. */
  static Optional<Shape> describe(TeacherBoardContext board, TeacherBoardContext.Line line) {
    if (line.frames().size() < 2) return Optional.empty();
    TeacherBoardContext.Frame first = line.frames().get(1);
    // Capturing moves have additional tactical meaning outside this deliberately narrow set.
    if (first.captures() != 0 || !(first.color().equals("B") || first.color().equals("W"))) {
      return Optional.empty();
    }
    String move = TeacherBoardContext.normalizeMove(first.move(), board.width, board.height);
    if (move.isEmpty() || move.equals("pass")) return Optional.empty();
    int[] xy = Board.asCoordinates(move, board.height).orElseThrow();
    Point point = new Point(xy[0], xy[1]);
    Grid grid = new Grid(board);
    if (grid.at(point.x, point.y) != Stone.EMPTY) return Optional.empty();
    boolean black = first.color().equals("B");
    Stone own = black ? Stone.BLACK : Stone.WHITE;
    Stone opponent = black ? Stone.WHITE : Stone.BLACK;
    List<Point> owns = grid.neighbors(point, own);
    List<Point> opponents = grid.neighbors(point, opponent);

    List<Point> distinctOwnGroups = new ArrayList<>();
    for (Point stone : owns) {
      if (distinctOwnGroups.stream().noneMatch(other -> grid.sameGroup(stone, other))) {
        distinctOwnGroups.add(stone);
      }
    }
    if (distinctOwnGroups.size() >= 2) {
      return Optional.of(grid.shape(Kind.CONNECTION, black, distinctOwnGroups, List.of()));
    }
    // A contact with our existing group is not a jump, hane or a new connection.
    if (!owns.isEmpty()) return Optional.empty();
    if (opponents.size() == 2) {
      Point a = opponents.get(0);
      Point b = opponents.get(1);
      Point across = new Point(a.x + b.x - point.x, a.y + b.y - point.y);
      if (a.x != b.x
          && a.y != b.y
          && !grid.sameGroup(a, b)
          && grid.at(across.x, across.y) == own) {
        return Optional.of(grid.shape(Kind.CUT, black, List.of(across), opponents));
      }
      return Optional.empty();
    }
    if (opponents.size() == 1) {
      Point touched = opponents.get(0);
      List<Point> supports = new ArrayList<>();
      for (int[] diagonal : DIAGONALS) {
        Point stone = new Point(point.x + diagonal[0], point.y + diagonal[1]);
        if (grid.at(stone.x, stone.y) == own
            && Math.abs(stone.x - touched.x) + Math.abs(stone.y - touched.y) == 1) {
          supports.add(stone);
        }
      }
      return supports.size() == 1
          ? Optional.of(grid.shape(Kind.HANE, black, supports, opponents))
          : Optional.empty();
    }
    if (!opponents.isEmpty()) return Optional.empty();

    Shape shape = cornerInvasion(grid, point, black, opponent);
    if (shape == null) shape = relation(grid, point, black, own, Kind.DIAGONAL, 1, 1);
    if (shape == null) shape = relation(grid, point, black, own, Kind.ONE_SPACE_JUMP, 2, 0);
    if (shape == null) shape = relation(grid, point, black, own, Kind.KNIGHTS_MOVE, 1, 2);
    return Optional.ofNullable(shape);
  }

  private static Shape cornerInvasion(Grid grid, Point point, boolean black, Stone opponent) {
    // Conservative naming policy, not a complete definition: a >=13-line board, exact 3-3,
    // and no other stones in this 6x6 corner except the opponent's 4-4 stone.
    if (Math.min(grid.board.width, grid.board.height) < 13) return null;
    int cornerX = point.x == 2 ? 0 : point.x == grid.board.width - 3 ? 1 : -1;
    int cornerY = point.y == 2 ? 0 : point.y == grid.board.height - 3 ? 1 : -1;
    if (cornerX < 0 || cornerY < 0) return null;
    Point anchor = null;
    for (int dx = 0; dx < 6; dx++) {
      for (int dy = 0; dy < 6; dy++) {
        int x = cornerX == 0 ? dx : grid.board.width - 1 - dx;
        int y = cornerY == 0 ? dy : grid.board.height - 1 - dy;
        Stone stone = grid.at(x, y);
        if (stone == Stone.EMPTY) continue;
        if (dx != 3 || dy != 3 || stone != opponent) return null;
        anchor = new Point(x, y);
      }
    }
    return anchor == null
        ? null
        : grid.shape(Kind.THREE_THREE_INVASION, black, List.of(), List.of(anchor));
  }

  private static Shape relation(
      Grid grid, Point point, boolean black, Stone own, Kind kind, int a, int b) {
    List<Point> anchors = new ArrayList<>();
    for (int dx = -2; dx <= 2; dx++) {
      for (int dy = -2; dy <= 2; dy++) {
        if (!((Math.abs(dx) == a && Math.abs(dy) == b)
            || (Math.abs(dx) == b && Math.abs(dy) == a))) continue;
        Point anchor = new Point(point.x + dx, point.y + dy);
        if (grid.at(anchor.x, anchor.y) == own && grid.clearRectangle(point, anchor)) {
          anchors.add(anchor);
        }
      }
    }
    return anchors.isEmpty() ? null : grid.shape(kind, black, anchors, List.of());
  }

  private static final class Grid {
    final TeacherBoardContext board;

    Grid(TeacherBoardContext board) {
      this.board = board;
    }

    Stone at(int x, int y) {
      return x < 0 || y < 0 || x >= board.width || y >= board.height
          ? null
          : board.stones.get(x * board.height + y);
    }

    List<Point> neighbors(Point point, Stone color) {
      List<Point> neighbors = new ArrayList<>();
      for (int[] side : SIDES) {
        Point neighbor = new Point(point.x + side[0], point.y + side[1]);
        if (at(neighbor.x, neighbor.y) == color) neighbors.add(neighbor);
      }
      return neighbors;
    }

    boolean sameGroup(Point first, Point second) {
      String firstCoordinate = coordinate(first);
      String secondCoordinate = coordinate(second);
      // Reuse the full-board groups already frozen for evidence, including remote connections.
      return board.groups.stream()
          .anyMatch(
              group ->
                  group.stones().contains(firstCoordinate)
                      && group.stones().contains(secondCoordinate));
    }

    boolean clearRectangle(Point first, Point second) {
      // This intentionally omits crowded diagonal/knight shapes rather than guessing a name.
      for (int x = Math.min(first.x, second.x); x <= Math.max(first.x, second.x); x++) {
        for (int y = Math.min(first.y, second.y); y <= Math.max(first.y, second.y); y++) {
          if ((x == first.x && y == first.y) || (x == second.x && y == second.y)) continue;
          if (at(x, y) != Stone.EMPTY) return false;
        }
      }
      return true;
    }

    String coordinate(Point point) {
      return TeacherBoardContext.coordinate(point.x, point.y, board.width, board.height);
    }

    Shape shape(Kind kind, boolean black, List<Point> owns, List<Point> opponents) {
      return new Shape(
          kind,
          black,
          owns.stream().map(this::coordinate).toList(),
          opponents.stream().map(this::coordinate).toList());
    }
  }
}
