package featurecat.lizzie.gui;

import featurecat.lizzie.rules.BoardData;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The status line under the win-rate bar, on one line: who leads by how much ("白领先 7.3 目" rather
 * than "黑领先:-7.3"), then what the previous move changed ("上一手(#3) -0.3% -1.9目").
 */
final class MoveStatusText {
  private MoveStatusText() {}

  static boolean hasWinrate(BoardData data) {
    return data != null && data.hasDisplayablePrimaryAnalysis();
  }

  static boolean hasScore(BoardData data) {
    return hasWinrate(data)
        && (data.isKataData || data.isSaiData)
        && Double.isFinite(data.scoreMean);
  }

  static double blackScore(BoardData data) {
    return data.scoreMeanIsBlackPerspective || data.blackToPlay ? data.scoreMean : -data.scoreMean;
  }

  static String currentScore(ResourceBundle strings, BoardData data, boolean withKomi) {
    if (!hasScore(data)) return "";
    double score = blackScore(data);
    if (!withKomi) return lead(strings, score);
    if (!Double.isFinite(data.getKomi())) return "";
    return strings.getString("LizzieFrame.scoreLeadWithKomi") + decimal(score + data.getKomi());
  }

  /** Only adjacent, measured positions establish the change caused by the previous move. */
  static String previousMove(
      ResourceBundle strings, BoardData current, BoardData previous, boolean withScore) {
    if (!hasWinrate(current) || !hasWinrate(previous)) return "";
    double currentWR =
        current.blackToPlay == previous.blackToPlay ? current.winrate : 100 - current.winrate;
    Double change =
        withScore && hasScore(current) && hasScore(previous)
            ? (blackScore(current) - blackScore(previous)) * (previous.blackToPlay ? 1 : -1)
            : null;
    return previousMove(
        strings, current.lastMoveMatchCandidteNo, currentWR - previous.winrate, change);
  }

  static String decimal(double value) {
    String digits = String.format(Locale.ENGLISH, "%.1f", value);
    return "-0.0".equals(digits) ? "0.0" : digits;
  }

  /** Names the side that leads; a lead that rounds to 0.0 reads as even. */
  static String lead(ResourceBundle strings, double blackLead) {
    String points = String.format(Locale.ENGLISH, "%.1f", Math.abs(blackLead));
    if ("0.0".equals(points)) {
      return strings.getString("LizzieFrame.status.even");
    }
    return MessageFormat.format(
        strings.getString(
            blackLead > 0 ? "LizzieFrame.status.blackLeads" : "LizzieFrame.status.whiteLeads"),
        points);
  }

  /**
   * What the previous move changed for the side that played it: its rank among the candidates when
   * it was one ({@code candidateRank > 0}), then the winrate and, when known, the score.
   */
  static String previousMove(
      ResourceBundle strings, int candidateRank, double winrateChange, Double scoreChange) {
    StringBuilder text = new StringBuilder(strings.getString("LizzieFrame.status.previousMove"));
    if (candidateRank > 0) {
      text.append("(#").append(candidateRank).append(')');
    }
    text.append(' ').append(signed(winrateChange)).append('%');
    if (scoreChange != null) {
      text.append(' ').append(signed(scoreChange)).append(strings.getString("LizzieFrame.pts"));
    }
    return text.toString();
  }

  private static String signed(double value) {
    String digits = String.format(Locale.ENGLISH, "%.1f", Math.abs(value));
    return ("0.0".equals(digits) ? "" : value > 0 ? "+" : "-") + digits;
  }
}
