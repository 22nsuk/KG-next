package featurecat.lizzie.rules;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Setup points a snapshot owns, including explicit declarations that match its parent. */
final class SnapshotSetup {
  private SnapshotSetup() {}

  static BitSet points(BoardData data, BoardData parent) {
    return points(data, parent, data.zobrist.width(), data.zobrist.height());
  }

  static BitSet points(BoardData data, BoardData parent, int width, int height) {
    BitSet points = parent == null ? new BitSet() : declaredPoints(data, width, height);
    for (int i = 0; i < data.stones.length; i++) {
      Stone inherited = parent == null ? Stone.EMPTY : parent.stones[i];
      if (data.stones[i] != inherited) points.set(i);
    }
    return points;
  }

  static BitSet declaredPoints(BoardData data, int width, int height) {
    BitSet points = new BitSet();
    for (String key : List.of("AB", "AW", "AE")) {
      List<String> values = data.getPropertyValues(key);
      if (values == null) continue;
      for (String value : values) {
        addMatchingPoints(points, data, key, value.trim(), width, height);
      }
    }
    return points;
  }

  private static void addMatchingPoints(
      BitSet points, BoardData data, String key, String value, int width, int height) {
    String[] ends = value.split(":", -1);
    if (ends.length > 2) return;
    int[] first = SgfDocument.parseBoardPoint(ends[0], width, height);
    int[] last = ends.length == 1 ? first : SgfDocument.parseBoardPoint(ends[1], width, height);
    if (first == null || last == null) return;
    for (int x = first[0]; x <= last[0]; x++) {
      for (int y = first[1]; y <= last[1]; y++) {
        int index = x * height + y;
        if (key.equals(property(data.stones[index]))) points.set(index);
      }
    }
  }

  private static String property(Stone stone) {
    if (stone.isEmpty()) return "AE";
    return stone.isBlack() ? "AB" : "AW";
  }

  static Map<String, List<String>> properties(
      BoardData data, BitSet points, int width, int height) {
    Map<String, List<String>> properties = new LinkedHashMap<>();
    List<String> removals = new ArrayList<>();
    properties.put("AE", removals);
    for (int i = points.nextSetBit(0); i >= 0; i = points.nextSetBit(i + 1)) {
      String key = property(data.stones[i]);
      properties.computeIfAbsent(key, ignored -> new ArrayList<>())
          .add(SgfDocument.asCoord(i / height, i % height, width, height));
    }
    if (removals.isEmpty()) properties.remove("AE");
    properties.replaceAll((key, values) -> List.copyOf(values));
    return properties;
  }
}
