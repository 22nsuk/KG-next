package featurecat.lizzie.gui;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Edits the comma-separated GTP vertex list used by the existing selection tools. */
final class RestrictionCoordinates {
  private RestrictionCoordinates() {}

  static String add(String coordinates, String vertex) {
    Set<String> result = parse(coordinates);
    result.add(vertex);
    return String.join(",", result);
  }

  static String remove(String coordinates, String vertex) {
    Set<String> result = parse(coordinates);
    result.remove(vertex);
    return String.join(",", result);
  }

  private static Set<String> parse(String coordinates) {
    Set<String> result = new LinkedHashSet<>();
    if (coordinates != null) {
      Arrays.stream(coordinates.split(","))
          .map(String::trim)
          .filter(value -> !value.isEmpty())
          .forEach(result::add);
    }
    return result;
  }
}
