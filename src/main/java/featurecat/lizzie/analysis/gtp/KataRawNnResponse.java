package featurecat.lizzie.analysis.gtp;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;

/**
 * Incremental reader for one KataGo {@code kata-raw-nn} response, fed one output line at a time.
 *
 * <p>The response is a {@code = symmetry N} header, scalar lines such as {@code whiteWin 0.42},
 * then a {@code policy} block and a {@code whiteOwnership} block of one line per board row. The
 * caller owns the output lists and may replace them between lines; this parser retains only the
 * response's parsing phase, with no engine or UI dependencies.
 */
public final class KataRawNnResponse {
  private final int width;
  private final int height;
  private boolean readingPolicy;
  private boolean readingOwnership;
  private boolean completeReported;

  public KataRawNnResponse(int width, int height) {
    this.width = width;
    this.height = height;
  }

  public boolean matchesBoardSize(int width, int height) {
    return this.width == width && this.height == height;
  }

  /** The symmetry announced by a {@code = symmetry N} response header, if this is one. */
  public static OptionalInt headerSymmetry(String line) {
    if (line == null || !line.startsWith("=")) {
      return OptionalInt.empty();
    }
    String[] params = line.trim().split("\\s+");
    if (params.length == 3 && params[1].equals("symmetry")) {
      try {
        return OptionalInt.of(Integer.parseInt(params[2]));
      } catch (NumberFormatException malformed) {
        return OptionalInt.empty();
      }
    }
    return OptionalInt.empty();
  }

  /** The value of a scalar line such as {@code whiteWin 0.42}, if {@code line} is that line. */
  public static OptionalDouble scalar(String line, String name) {
    if (line == null || !line.startsWith(name)) {
      return OptionalDouble.empty();
    }
    String[] params = line.trim().split("\\s+");
    if (params.length != 2 || !params[0].equals(name)) {
      return OptionalDouble.empty();
    }
    try {
      double value = Double.parseDouble(params[1]);
      return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    } catch (NumberFormatException malformed) {
      return OptionalDouble.empty();
    }
  }

  /**
   * Feeds one body line. Policy rows are stored in permille, ownership rows for the side to play
   * (white ownership negated when {@code blackToPlay} is true, evaluated per row). Lines whose
   * value count is not the board width are ignored; unparsable or non-finite values count as 0.
   * Output lists are used only for this call, never retained across the caller's display resets.
   *
   * @return true exactly once, when the last ownership row arrives
   */
  public boolean accept(
      String line,
      BooleanSupplier blackToPlay,
      List<Double> policyPermille,
      List<Double> ownership) {
    if (line == null || completeReported) {
      return false;
    }
    String trimmed = line.trim();
    if (trimmed.startsWith("policyPass")) {
      // The pass probability is not a board row (on a 2-wide board it used to be read as one).
      return false;
    }
    if (trimmed.equals("policy")) {
      readingPolicy = true;
      readingOwnership = false;
      return false;
    }
    if (trimmed.equals("whiteOwnership")) {
      readingPolicy = false;
      readingOwnership = true;
      return false;
    }
    if (readingPolicy) {
      String[] values = line.trim().split("\\s+");
      if (values.length == width && policyPermille.size() < width * height) {
        for (String value : values) {
          policyPermille.add(parseOrZero(value) * 1000.0);
        }
      }
    }
    if (readingOwnership) {
      String[] values = line.trim().split("\\s+");
      if (values.length == width) {
        boolean negate = blackToPlay.getAsBoolean();
        for (String value : values) {
          double whiteOwnership = parseOrZero(value);
          ownership.add(negate ? -whiteOwnership : whiteOwnership);
        }
      }
      if (ownership.size() == width * height) {
        completeReported = true;
        readingOwnership = false;
        return true;
      }
    }
    return false;
  }

  private static double parseOrZero(String value) {
    try {
      double parsed = Double.parseDouble(value);
      return Double.isFinite(parsed) ? parsed : 0.0;
    } catch (NumberFormatException malformed) {
      return 0.0;
    }
  }
}
