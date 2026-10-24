package featurecat.lizzie.gui;

import java.util.OptionalDouble;

/** Validation for the editable WRN/PDA values, independent of their applied settings. */
enum KataAnalysisParameter {
  WRN(0, 2, "txt-kata-engine-wrn"),
  PDA(-3, 3, "txt-kata-engine-pda");

  final String configKey;
  private final double minimum;
  private final double maximum;

  KataAnalysisParameter(double minimum, double maximum, String configKey) {
    this.minimum = minimum;
    this.maximum = maximum;
    this.configKey = configKey;
  }

  OptionalDouble parse(String draft) {
    if (draft == null) return OptionalDouble.empty();
    String text = draft.trim();
    // A trailing decimal point is still being edited, just like a sign or an exponent prefix.
    if (text.isEmpty() || text.endsWith(".")) return OptionalDouble.empty();
    try {
      double value = Double.parseDouble(text);
      return Double.isFinite(value) && value >= minimum && value <= maximum
          ? OptionalDouble.of(value)
          : OptionalDouble.empty();
    } catch (NumberFormatException invalid) {
      return OptionalDouble.empty();
    }
  }
}
