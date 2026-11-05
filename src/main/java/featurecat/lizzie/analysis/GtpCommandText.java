package featurecat.lizzie.analysis;

/**
 * Parsed view only: preserve the original command, including its ID, on the wire.
 * GTP strips controls/comments and treats tabs as spaces before parsing the optional ID.
 * KataGo also discards non-ASCII input and accepts an ID directly adjacent to the verb
 * (e.g. 123kata-set-rules).
 */
public record GtpCommandText(String payload, int id, String wireId) {
  public static final int NO_ID = -1;

  /** KataGo's digit parser rejects more than nine digits, including leading zeros. */
  public boolean hasSupportedId() {
    return wireId.length() <= 9 && (wireId.isEmpty() || id != NO_ID);
  }

  public String verb() {
    return payload.split("\\s+", 2)[0];
  }

  // All analysis lifecycle decisions use the same parsed verb, never the wire prefix or a
  // second parse of payload ("123 456 kata-analyze" is not an analysis command).
  public boolean isOrdinaryAnalysis() {
    return switch (verb()) {
      case "kata-analyze", "lz-analyze", "analyze" -> true;
      default -> false;
    };
  }

  public boolean isAnalyze() {
    return isOrdinaryAnalysis() || switch (verb()) {
      case "kata-genmove_analyze", "lz-genmove_analyze", "genmove_analyze" -> true;
      default -> false;
    };
  }

  public boolean startsAnalysisPayload() {
    return isAnalyze() || verb().equals("genmove");
  }

  public String withPayload(String original, String replacement) {
    if (payload.equals(replacement)) return original;
    return wireId.isEmpty() ? replacement : wireId + " " + replacement;
  }

  public boolean mutatesRules() {
    return switch (verb()) {
      case "kata-set-rules", "kata-set-rule", "kgs-rules", "loadsgf" -> true;
      default -> false;
    };
  }

  public static GtpCommandText parse(String command) {
    if (command == null) return new GtpCommandText("", NO_ID, "");
    StringBuilder text = new StringBuilder(command.length());
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (c == '#' || c == '\n') break;
      if (c == '\t') text.append(' ');
      else if (c >= ' ' && c <= '~') text.append(c);
    }
    return parseEnvelope(text.toString().trim());
  }

  /** Retains literal local filenames; only adapters implementing local loadsgf use this view. */
  public static String literalPayload(String command) {
    return parseEnvelope(command == null ? "" : command.trim()).payload();
  }

  private static GtpCommandText parseEnvelope(String line) {
    int end = 0;
    while (end < line.length() && isAsciiDigit(line.charAt(end))) end++;
    int id = NO_ID;
    if (end > 0) {
      try {
        id = Integer.parseInt(line.substring(0, end));
      } catch (NumberFormatException invalidId) {
        // A rejected ID must not prevent conservative invalidation of a possible rules write.
      }
    }
    return new GtpCommandText(line.substring(end).trim(), id, line.substring(0, end));
  }

  private static boolean isAsciiDigit(char c) {
    return c >= '0' && c <= '9';
  }
}
