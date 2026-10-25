package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MainWindowTitleTest {
  private static final String APP = "KG-next next-dev";
  private static final String PLAYERS = "[黑] Yasuda Shusaku vs [白] Inoue Gennan Inseki";
  private static final String RESULT = "(结果:B+2)";

  @Test
  void analysedPositionShowsEvaluationEngineAndSpeedBeforeTheGame() {
    assertEquals(
        "KG-next next-dev - [0.8 -7.2 857] KataGo 98 搜索/秒 - [黑] Yasuda Shusaku vs [白]"
            + " Inoue Gennan Inseki (结果:B+2) - earred.sgf",
        MainWindowTitle.compose(
            APP, "[0.8 -7.2 857] ", "KataGo", "98 搜索/秒", PLAYERS, RESULT, "earred.sgf"));
  }

  @Test
  void positionWithoutAnalysisOrSpeedKeepsTheSeparators() {
    // While automatic analysis runs, the main engine is paused and the node has no playouts yet.
    assertEquals(
        "KG-next next-dev - KataGo - [黑] Yasuda Shusaku vs [白] Inoue Gennan Inseki"
            + " (结果:B+2) - earred.sgf",
        MainWindowTitle.compose(APP, "", "KataGo", "", PLAYERS, RESULT, "earred.sgf"));
  }

  @Test
  void missingPartsDropOutWithTheirSeparators() {
    assertEquals(APP, MainWindowTitle.compose(APP, "", "", "", "", "", ""));
    assertEquals(
        "KG-next next-dev - [黑] Yasuda Shusaku vs [白] Inoue Gennan Inseki - a.sgf",
        MainWindowTitle.compose(APP, null, "", null, PLAYERS, "", "a.sgf"));
  }
}
