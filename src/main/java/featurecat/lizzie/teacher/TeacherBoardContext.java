package featurecat.lizzie.teacher;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Frozen geometry and locally replayed reference lines; never mutates the user's history. */
final class TeacherBoardContext {
  record Group(String color, List<String> stones, List<String> liberties) {}

  record Frame(List<Stone> stones, String move, String color, int captures) {}

  record Line(String label, List<String> moves, List<Frame> frames, boolean truncated) {}

  final int width;
  final int height;
  final List<Stone> stones;
  final List<Group> groups;
  final List<Line> lines;

  private TeacherBoardContext(
      int width, int height, List<Stone> stones, List<Group> groups, List<Line> lines) {
    this.width = width;
    this.height = height;
    this.stones = List.copyOf(stones);
    this.groups = List.copyOf(groups);
    this.lines = List.copyOf(lines);
  }

  // Capture on the EDT before starting the request. Replay uses the active board dimensions;
  // every value read after capture is frozen, including coordinates on rectangular boards.
  static TeacherBoardContext capture(BoardHistoryNode node, TeacherEvidence.Position position) {
    int width = Board.boardWidth;
    int height = Board.boardHeight;
    BoardData data = node == null ? null : node.getData();
    if (data == null
        || data.stones == null
        || width <= 0
        || height <= 0
        || data.stones.length != width * height) {
      return null;
    }
    for (Stone stone : data.stones) {
      if (stone != Stone.BLACK && stone != Stone.WHITE && stone != Stone.EMPTY) {
        return null;
      }
    }
    BoardData frozen = data.clone();
    List<Stone> stones = List.copyOf(Arrays.asList(frozen.stones.clone()));
    List<Line> lines = new ArrayList<>();
    if (!position.playedContinuation.isEmpty()) {
      lines.add(replay(node, frozen, "recorded", position.playedContinuation, false));
    }
    for (TeacherEvidence.Candidate candidate : position.candidates) {
      List<String> moves = candidate.variation;
      boolean mismatchedRoot =
          !moves.isEmpty() && !candidate.coordinate.equalsIgnoreCase(moves.get(0));
      if (moves.isEmpty() || mismatchedRoot) {
        moves = List.of(candidate.coordinate);
      }
      lines.add(replay(node, frozen, candidate.coordinate, moves, mismatchedRoot));
    }
    return new TeacherBoardContext(width, height, stones, groups(stones, width, height), lines);
  }

  private static Line replay(
      BoardHistoryNode source,
      BoardData frozen,
      String label,
      List<String> moves,
      boolean truncated) {
    // The shared placement implementation owns capture, suicide and simple-ko rules. Replace
    // insertion only: normal addOrGoto can invoke foreground engine/UI hooks; add on this
    // detached tree has no such effects, for both MOVE and PASS.
    BoardHistoryList history =
        new BoardHistoryList(frozen.clone()) {
          @Override
          public void addOrGoto(BoardData data, boolean branch, boolean change) {
            add(data);
          }

          @Override
          public void addOrGoto(BoardData data, boolean branch) {
            add(data);
          }
        };
    source
        .previous()
        .filter(previous -> previous.getData().stones.length == frozen.stones.length)
        .ifPresent(
            previous ->
                new BoardHistoryNode(previous.getData().clone())
                    .setPreviousForChild(history.getCurrentHistoryNode()));
    List<Frame> frames = new ArrayList<>();
    frames.add(new Frame(List.copyOf(Arrays.asList(frozen.stones.clone())), "", "", 0));
    List<String> accepted = new ArrayList<>();
    for (String supplied : moves) {
      String move = normalizeMove(supplied, Board.boardWidth, Board.boardHeight);
      if (move.isEmpty()) {
        break;
      }
      BoardData before = history.getData();
      Stone color = before.blackToPlay ? Stone.BLACK : Stone.WHITE;
      if ("pass".equals(move)) {
        history.pass(color);
      } else {
        int[] xy = Board.asCoordinates(move).orElseThrow();
        history.place(xy[0], xy[1], color, false, false, false, false, false);
      }
      BoardData after = history.getData();
      if (after == before) {
        break;
      }
      accepted.add(move);
      int captures =
          color.isBlack()
              ? after.blackCaptures - before.blackCaptures
              : after.whiteCaptures - before.whiteCaptures;
      frames.add(
          new Frame(
              List.copyOf(Arrays.asList(after.stones.clone())),
              move,
              color.isBlack() ? "B" : "W",
              captures));
    }
    return new Line(
        label,
        List.copyOf(accepted),
        List.copyOf(frames),
        truncated || accepted.size() != moves.size());
  }

  static String normalizeMove(String move, int width, int height) {
    if (move == null) {
      return "";
    }
    String normalized = move.trim().toUpperCase(Locale.ROOT);
    if ("PASS".equals(normalized)) {
      return "pass";
    }
    var parsed = Board.asCoordinates(normalized, height);
    if (parsed.isEmpty()) {
      return "";
    }
    int[] xy = parsed.get();
    if (xy[0] < 0 || xy[0] >= width || xy[1] < 0 || xy[1] >= height) {
      return "";
    }
    // The general parser accepts display suffixes, e.g. D40 -> D4. Evidence must retain the
    // exact supplied point, so accept only a canonical round trip.
    return coordinate(xy[0], xy[1], width, height).equals(normalized) ? normalized : "";
  }

  Line candidateLine(TeacherEvidence.Candidate candidate) {
    for (Line line : lines) {
      if (line.label().equals(candidate.coordinate)) {
        return line;
      }
    }
    return null;
  }

  static List<Group> groups(List<Stone> stones, int width, int height) {
    boolean[] visited = new boolean[stones.size()];
    List<Group> groups = new ArrayList<>();
    for (int origin = 0; origin < stones.size(); origin++) {
      Stone color = stones.get(origin);
      if (visited[origin] || color.isEmpty()) {
        continue;
      }
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      List<String> members = new ArrayList<>();
      Set<String> liberties = new LinkedHashSet<>();
      queue.add(origin);
      visited[origin] = true;
      while (!queue.isEmpty()) {
        int index = queue.removeFirst();
        // Board stores columns contiguously: getIndex(x, y) = x * height + y.
        int x = index / height;
        int y = index % height;
        members.add(coordinate(x, y, width, height));
        for (int[] xy : new int[][] {{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1}}) {
          if (xy[0] < 0 || xy[0] >= width || xy[1] < 0 || xy[1] >= height) {
            continue;
          }
          int neighbor = xy[0] * height + xy[1];
          if (stones.get(neighbor).isEmpty()) {
            liberties.add(coordinate(xy[0], xy[1], width, height));
          } else if (stones.get(neighbor) == color && !visited[neighbor]) {
            visited[neighbor] = true;
            queue.add(neighbor);
          }
        }
      }
      groups.add(
          new Group(color.isBlack() ? "B" : "W", List.copyOf(members), List.copyOf(liberties)));
    }
    return List.copyOf(groups);
  }

  private static String coordinate(int x, int y, int width, int height) {
    return width > 25 || height > 25
        ? "(" + x + "," + y + ")"
        : String.valueOf((char) ('A' + x + (x >= 8 ? 1 : 0))) + (height - y);
  }

  String facts() {
    StringBuilder text = new StringBuilder("Board: ").append(width).append('x').append(height);
    text.append(
        "\nConnected groups (liberties are geometry, NOT proof of life/death or sente):\n");
    for (Group group : groups) {
      text.append(group.color())
          .append(" stones=")
          .append(group.stones())
          .append(" liberties=")
          .append(group.liberties())
          .append('\n');
    }
    if (groups.isEmpty()) {
      text.append("Empty board.\n");
    }
    text.append(
        "Locally replayed lines (simple ko and no suicide; NOT proof of best replies, superko or forced play):\n");
    for (Line line : lines) {
      text.append(
              line.label().equals("recorded")
                  ? "Recorded continuation"
                  : "Candidate " + line.label())
          .append(": ");
      for (int index = 1; index < line.frames().size(); index++) {
        Frame frame = line.frames().get(index);
        text.append(index).append('.').append(frame.color()).append(' ').append(frame.move());
        if (frame.captures() > 0) {
          text.append(" captures=").append(frame.captures());
        }
        text.append("; ");
      }
      if (line.truncated()) {
        text.append("STOPPED: omitted moves were not replayable. Do not explain the omitted suffix.");
      }
      text.append('\n');
      if (line.frames().size() > 1) {
        Frame last = line.frames().get(line.frames().size() - 1);
        for (Group group : groups(last.stones(), width, height)) {
          if (group.liberties().size() <= 2) {
            text.append("End of line: ")
                .append(group.color())
                .append(' ')
                .append(group.stones())
                .append(" has ")
                .append(group.liberties().size())
                .append(" liberties ")
                .append(group.liberties())
                .append(" (not a death verdict).\n");
          }
        }
      }
    }
    return text.toString();
  }
}
