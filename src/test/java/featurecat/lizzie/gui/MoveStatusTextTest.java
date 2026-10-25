package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.BoardData;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class MoveStatusTextTest {
  private static final ResourceBundle ZH =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
  private static final ResourceBundle EN =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);

  @Test
  void bothScoreModesRequireCurrentAnalysisAndAcceptMeasuredZero() {
    BoardData data = BoardData.empty(19, 19);
    data.komi = 6.5;
    data.analysisHeaderSlots = 3;
    data.isKataData = true;
    data.scoreMean = -7.3;
    for (boolean withKomi : new boolean[] {false, true}) {
      data.setPlayouts(12);
      assertTrue(!MoveStatusText.currentScore(EN, data, withKomi).isEmpty());
      data.setPlayouts(0);
      assertEquals("", MoveStatusText.currentScore(EN, data, withKomi));
      assertEquals("", MainWindowTitle.evaluation(data, withKomi));
    }
    data.setPlayouts(12);
    data.winrate = 100;
    data.scoreMean = 0;
    assertEquals("Even", MoveStatusText.currentScore(EN, data, false));
    assertEquals("[100.0 0.0 12]", MainWindowTitle.evaluation(data, false));
    data.scoreMean = Double.NaN;
    assertEquals("", MoveStatusText.currentScore(EN, data, false));
    assertEquals("[100.0 12]", MainWindowTitle.evaluation(data, false));
    data.winrate = Double.POSITIVE_INFINITY;
    assertEquals("", MainWindowTitle.evaluation(data, false));
  }

  @Test
  void visitOnlyPlaceholdersDoNotCreateEvaluationText() {
    BoardData data = BoardData.empty(19, 19);
    data.setPlayouts(20);
    data.isKataData = true;
    assertEquals("", MoveStatusText.currentScore(EN, data, false));
    assertEquals("", MainWindowTitle.evaluation(data, false));
  }

  @Test
  void previousMoveUsesAdjacentAnalysisAndItsOwnScorePerspective() {
    BoardData previous = BoardData.empty(19, 19);
    previous.analysisHeaderSlots = 3;
    previous.winrate = 60;
    previous.isKataData = true;
    previous.scoreMean = 2;
    BoardData current = BoardData.empty(19, 19);
    current.analysisHeaderSlots = 3;
    current.blackToPlay = false;
    current.winrate = 50;
    current.isKataData = true;
    current.scoreMean = 3;
    current.setPlayouts(10);
    assertEquals("", MoveStatusText.previousMove(EN, current, previous, true));
    previous.setPlayouts(10);
    assertEquals(
        "Prev. move -10.0% -5.0pts", MoveStatusText.previousMove(EN, current, previous, true));
    current.scoreMeanIsBlackPerspective = true;
    current.scoreMean = -3;
    assertEquals(
        "Prev. move -10.0% -5.0pts", MoveStatusText.previousMove(EN, current, previous, true));
    previous.isKataData = false;
    assertEquals("Prev. move -10.0%", MoveStatusText.previousMove(EN, current, previous, true));
  }

  @Test
  void leadNamesTheSideAheadInsteadOfANegativeBlackLead() {
    assertEquals("白领先 7.3 目", MoveStatusText.lead(ZH, -7.3));
    assertEquals("黑领先 2.0 目", MoveStatusText.lead(ZH, 2.04));
    assertEquals("均势", MoveStatusText.lead(ZH, -0.04));
    assertEquals("White leads by 7.3", MoveStatusText.lead(EN, -7.3));
  }

  @Test
  void previousMoveShowsTheCandidateRankOnlyWhenTheMoveWasACandidate() {
    assertEquals("上一手(#3) -0.3% -1.9目", MoveStatusText.previousMove(ZH, 3, -0.34, -1.88));
    assertEquals("上一手 +1.2%", MoveStatusText.previousMove(ZH, 0, 1.2, null));
    assertEquals("上一手(#1) 0.0% 0.0目", MoveStatusText.previousMove(ZH, 1, -0.01, 0.02));
  }
}
