package featurecat.lizzie.gui;

import featurecat.lizzie.theme.Theme;
import java.awt.Font;
import java.util.List;

/**
 * Coordinate size and weight for main, comparison and independent main boards. Margins grow by
 * 60% of the labels' growth to leave room between the labels and stones on the edge lines.
 *
 * @param sizePercent the size in percent of the usual one; see {@link #SIZES}
 */
record CoordinateLabels(boolean bold, int sizePercent) {
  /** The sizes the theme settings offer, in percent: standard, large and extra large. */
  static final List<Integer> SIZES = List.of(100, 140, 200);
  static final CoordinateLabels STANDARD = new CoordinateLabels(false, 100);

  CoordinateLabels {
    sizePercent = Math.max(SIZES.get(0), Math.min(SIZES.get(SIZES.size() - 1), sizePercent));
  }

  static CoordinateLabels of(Theme theme) {
    return theme == null
        ? STANDARD
        : new CoordinateLabels(theme.coordinateBold(), theme.coordinateSize());
  }

  /** The size as a factor, kept within the offered sizes. */
  double scale() {
    return sizePercent / 100.0;
  }

  int fontStyle() {
    return bold ? Font.BOLD : Font.PLAIN;
  }

  /** The tallest a label may be next to stones of {@code stoneRadius}. */
  float maxHeight(int stoneRadius) {
    return (float) (stoneRadius * 4 / 5 * scale());
  }

  /** The widest a label may be next to stones of {@code stoneRadius}. */
  double maxWidth(int stoneRadius) {
    return stoneRadius * scale();
  }

  /** The board margin that leaves room for the labels, from the margin at the usual size. */
  double margin(double usualMargin) {
    return usualMargin * (1 + (scale() - 1) * MARGIN_GROWTH);
  }

  private static final double MARGIN_GROWTH = 0.6;

  /** The index in {@link #SIZES} nearest to {@code sizePercent}, for the settings' size list. */
  static int sizeIndex(int sizePercent) {
    int bounded = new CoordinateLabels(false, sizePercent).sizePercent();
    int best = 0;
    for (int i = 1; i < SIZES.size(); i++) {
      if (Math.abs(SIZES.get(i) - bounded) < Math.abs(SIZES.get(best) - bounded)) {
        best = i;
      }
    }
    return best;
  }
}
