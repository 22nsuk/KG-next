package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Move restrictions observed on one physically written analysis command. A null value means no
 * recorded restriction; an empty clause list means restrictions were present but not fully readable.
 * Neither form asserts that an engine applied an unsupported request.
 */
public record AnalysisMoveRestrictions(List<AnalysisMoveRestrictions.Clause> clauses) {
  public static final int MAX_CLAUSES = 64;
  public static final int MAX_TEXT_LENGTH = 65536;
  private static final AnalysisMoveRestrictions UNKNOWN = new AnalysisMoveRestrictions(List.of());

  public AnalysisMoveRestrictions {
    clauses = List.copyOf(clauses);
    int length = 0;
    for (Clause clause : clauses) length += clause.coordinates().length() + 32;
    if (clauses.size() > MAX_CLAUSES || length > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException("Oversized analysis restrictions");
    }
  }

  public record Clause(String kind, String player, String coordinates, int untilDepth) {
    public Clause {
      kind = kind.toLowerCase(Locale.ROOT);
      player = switch (player.toLowerCase(Locale.ROOT)) {
        case "b", "black" -> "B";
        case "w", "white" -> "W";
        default -> throw new IllegalArgumentException("Invalid restriction player");
      };
      coordinates = coordinates.toUpperCase(Locale.ROOT);
      if ((!kind.equals("allow") && !kind.equals("avoid"))
          || untilDepth < 0 || coordinates.length() > MAX_TEXT_LENGTH) {
        throw new IllegalArgumentException("Invalid analysis restriction");
      }
      for (String coordinate : coordinates.split(",", -1)) {
        if (!coordinate.matches("[A-HJ-Z]+[1-9][0-9]*|PASS")) {
          throw new IllegalArgumentException("Invalid restriction coordinate");
        }
      }
    }
  }

  /** Uses the same GTP envelope/comment parsing as physical analysis-output admission. */
  public static AnalysisMoveRestrictions fromCommand(String command) {
    GtpCommandText parsed = GtpCommandText.parse(command);
    if (!parsed.isOrdinaryAnalysis()) return null;
    String[] tokens = parsed.payload().split("\\s+");
    List<Clause> clauses = new ArrayList<>();
    for (int index = 1; index < tokens.length; index++) {
      if (!tokens[index].equalsIgnoreCase("allow") && !tokens[index].equalsIgnoreCase("avoid")) {
        continue;
      }
      if (index + 3 >= tokens.length) return UNKNOWN;
      try {
        clauses.add(new Clause(tokens[index], tokens[index + 1], tokens[index + 2],
            Integer.parseInt(tokens[index + 3])));
        if (clauses.size() > MAX_CLAUSES) return UNKNOWN;
      } catch (IllegalArgumentException invalid) {
        return UNKNOWN;
      }
      index += 3;
    }
    if (clauses.isEmpty()) return null;
    try {
      return new AnalysisMoveRestrictions(clauses);
    } catch (IllegalArgumentException oversized) {
      return UNKNOWN;
    }
  }
}
