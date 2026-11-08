package featurecat.lizzie.gui;

import featurecat.lizzie.rules.BoardData;
import java.text.MessageFormat;
import java.util.ResourceBundle;

/** A display snapshot, not a claim that the current engine produced or can reuse the result. */
record WinrateReading(double black, Source source, int moveNumber) {
  enum Source {
    POSITION,
    SAVED,
    RETAINED,
    PREVIOUS,
    NONE
  }

  static final WinrateReading UNKNOWN = new WinrateReading(0, Source.NONE, 0);

  static WinrateReading of(BoardData data) {
    return of(data, false);
  }

  static WinrateReading of(BoardData data, boolean secondary) {
    if (data == null) return UNKNOWN;
    synchronized (data) {
      if (!(secondary
          ? data.hasDisplayableSecondaryAnalysis()
          : data.hasDisplayablePrimaryAnalysis())) {
        return UNKNOWN;
      }
      boolean invalidated = secondary ? data.isChanged2 : data.isChanged;
      int headerSlots = secondary ? data.analysisHeaderSlots2 : data.analysisHeaderSlots;
      double winrate = secondary ? data.winrate2 : data.winrate;
      Source source =
          invalidated ? Source.RETAINED : headerSlots >= 3 ? Source.SAVED : Source.POSITION;
      return new WinrateReading(
          data.analysisBlackToPlay(secondary) ? winrate : 100 - winrate, source, data.moveNumber);
    }
  }

  /** Only the statistics panel opts into explicitly labelled, earlier-position fallback. */
  static WinrateReading forStatistics(BoardData current, BoardData previous, BoardData older) {
    WinrateReading reading = of(current);
    if (reading.known()) return reading;
    reading = of(previous);
    if (!reading.known()) reading = of(older);
    return reading.asPrevious();
  }

  WinrateReading asPrevious() {
    return known() ? new WinrateReading(black, Source.PREVIOUS, moveNumber) : this;
  }

  boolean known() {
    return source != Source.NONE;
  }

  String percent(boolean forBlack) {
    if (!known()) return "--";
    long blackTenths = Math.round(black * 10);
    long tenths = forBlack ? blackTenths : 1000 - blackTenths;
    return tenths / 10 + "." + tenths % 10 + "%";
  }

  String sourceText(ResourceBundle strings) {
    return switch (source) {
      case POSITION -> "";
      case SAVED -> strings.getString("AnalysisReadability.saved");
      case RETAINED -> strings.getString("AnalysisReadability.retained");
      case PREVIOUS ->
          MessageFormat.format(strings.getString("AnalysisReadability.previous"), moveNumber);
      case NONE -> strings.getString("AnalysisReadability.none");
    };
  }
}
