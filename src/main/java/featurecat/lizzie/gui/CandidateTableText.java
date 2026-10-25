package featurecat.lizzie.gui;

import java.util.Locale;

/** Text of the candidate tables' winrate and score cells for the move actually played. */
final class CandidateTableText {
  private CandidateTableText() {}

  /**
   * "99.2 ↓0.1": the value, then how far the played move is from the best one. The arrow carries
   * the sign, so the cell no longer reads "↓-0.1(99.2)" and is cut to "↓-0.1(99..." in narrow
   * columns. A difference that rounds to 0.0 is left out.
   */
  static String valueWithDifference(double value, double difference) {
    if (!Double.isFinite(value)) return "";
    String shown = MoveStatusText.decimal(value);
    if (!Double.isFinite(difference)) return shown;
    String gap = String.format(Locale.ENGLISH, "%.1f", Math.abs(difference));
    return "0.0".equals(gap) ? shown : shown + " " + (difference > 0 ? "↑" : "↓") + gap;
  }

  /**
   * The difference written by {@link #valueWithDifference}: negative after "↓", positive after "↑",
   * 0 without an arrow. The tables colour the played move's row by it.
   */
  static double differenceIn(String cell) {
    if (cell == null) return 0;
    int down = cell.indexOf('↓');
    int up = cell.indexOf('↑');
    int arrow = Math.max(down, up);
    if (arrow < 0) return 0;
    try {
      double gap = Double.parseDouble(cell.substring(arrow + 1).trim());
      return arrow == down ? -gap : gap;
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
