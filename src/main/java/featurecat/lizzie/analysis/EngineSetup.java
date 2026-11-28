package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.Stone;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Validates simultaneous stone placements before handing them to a Go engine. */
public final class EngineSetup {
  private record Point(int x, int y) {}
  private static final int[][] NEIGHBORS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
  private final int width;
  private final int height;
  // A remote SGF may declare a large rectangle with only a few stones.
  private final Map<Point, Boolean> stones = new HashMap<>();

  public EngineSetup(int width, int height) {
    this.width = width;
    this.height = height;
  }

  public boolean add(int x, int y, boolean black) {
    return onBoard(x, y) && stones.putIfAbsent(new Point(x, y), black) == null;
  }

  private boolean onBoard(int x, int y) {
    return x >= 0 && x < width && y >= 0 && y < height;
  }

  public boolean hasGroupWithoutLiberty() {
    Set<Point> visited = new HashSet<>();
    ArrayDeque<Point> pending = new ArrayDeque<>();
    for (var entry : stones.entrySet()) {
      if (!visited.add(entry.getKey())) continue;
      pending.add(entry.getKey());
      boolean hasLiberty = false;
      while (!pending.isEmpty()) {
        Point point = pending.removeFirst();
        for (int[] offset : NEIGHBORS) {
          int x = point.x + offset[0];
          int y = point.y + offset[1];
          if (!onBoard(x, y)) continue;
          Point next = new Point(x, y);
          Boolean color = stones.get(next);
          if (color == null) hasLiberty = true;
          else if (color.equals(entry.getValue()) && visited.add(next)) pending.addLast(next);
        }
      }
      if (!hasLiberty) return true;
    }
    return false;
  }

  static boolean hasGroupWithoutLiberty(Stone[] board, int width, int height) {
    EngineSetup setup = new EngineSetup(width, height);
    for (int i = 0; i < board.length; i++) {
      Stone stone = board[i];
      if (stone.isBlack() || stone.isWhite()) setup.add(i / height, i % height, stone.isBlack());
    }
    return setup.hasGroupWithoutLiberty();
  }
}
