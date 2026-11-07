package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.BoardData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WinrateReadingTest {
  private static final ResourceBundle KO =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.KOREAN);

  @Test
  void absentAndVisitOnlyAnalysisNeverInventFiftyPercent() {
    BoardData data = BoardData.empty(19, 19);
    assertEquals("--", NameWinrates.value(WinrateReading.of(data), true, KO));
    data.setPlayouts(100);
    data.winrate = 56.25;
    assertFalse(WinrateReading.of(data).known());
    data.analysisHeaderSlots = 2;
    assertFalse(WinrateReading.of(data).known());
    data.bestMoves = new ArrayList<>(List.of(new MoveData()));
    assertEquals(WinrateReading.Source.POSITION, WinrateReading.of(data).source());
    assertEquals("56.3%", WinrateReading.of(data).percent(true));
    assertEquals("43.7%", WinrateReading.of(data).percent(false));
  }

  @Test
  void savedAndRetainedResultsStayVisibleWithDistinctLabels() {
    BoardData data = saved(56.25, false, 23);
    WinrateReading saved = WinrateReading.of(data);
    assertEquals("43.8%", saved.percent(true));
    assertEquals("56.2%", saved.percent(false));
    assertEquals("43.8% (저장 분석)", NameWinrates.value(saved, true, KO));
    data.isChanged = true;
    assertEquals(WinrateReading.Source.RETAINED, WinrateReading.of(data).source());
    assertEquals("43.8% (변경 전 분석)", NameWinrates.value(WinrateReading.of(data), true, KO));
    assertEquals(WinrateReading.Source.SAVED, saved.source(), "A reading is a stable snapshot");
  }

  @Test
  void onlyTheStatisticsPanelBorrowsEarlierPositionsAndLabelsTheirMove() {
    BoardData current = BoardData.empty(19, 19);
    BoardData previous = BoardData.empty(19, 19);
    BoardData older = saved(70, false, 21);
    WinrateReading fallback = WinrateReading.forStatistics(current, previous, older);
    assertEquals("30.0%", fallback.percent(true));
    assertEquals("이전 국면(21수)", fallback.sourceText(KO));
    assertEquals("--", NameWinrates.value(WinrateReading.of(current), true, KO));
    assertEquals("", MoveStatusText.previousMove(KO, current, previous, true));
    previous = saved(60, true, 22);
    assertEquals(22, WinrateReading.forStatistics(current, previous, older).moveNumber());
    current = saved(55, false, 23);
    assertEquals(
        WinrateReading.Source.SAVED,
        WinrateReading.forStatistics(current, previous, older).source());
  }

  @Test
  void comparisonBoardRequiresItsOwnAnalysisPayloadAndPerspective() {
    BoardData data = saved(60, false, 10);
    data.setPlayouts2(30);
    data.winrate2 = 25;
    assertFalse(WinrateReading.of(data, true).known());
    data.analysisHeaderSlots2 = 3;
    assertEquals("75.0%", WinrateReading.of(data, true).percent(true));
    assertEquals("40.0%", WinrateReading.of(data).percent(true));
    data.isChanged2 = true;
    assertEquals(WinrateReading.Source.RETAINED, WinrateReading.of(data, true).source());
    assertEquals(WinrateReading.Source.SAVED, WinrateReading.of(data).source());
    for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 101}) {
      data.winrate2 = invalid;
      assertFalse(WinrateReading.of(data, true).known());
    }
  }

  @Test
  void turnEditsKeepBothReadingsScoresAndMoveChangesInTheirMeasuredPerspective() {
    for (boolean black : new boolean[] {true, false}) {
      BoardData previous = saved(55, !black, 3);
      BoardData data = saved(70, black, 4);
      data.isKataData = true;
      data.scoreMean = 4;
      previous.isKataData = true;
      previous.scoreMean = 2;
      data.analysisHeaderSlots2 = 3;
      data.setPlayouts2(80);
      data.winrate2 = 60;
      var change = MoveStatusText.previousMoveStatus(KO, data, previous, true);
      double engineChange = MoveStatusText.engineGameWinrateChange(data, previous);
      String title = MainWindowTitle.evaluation(data, false);
      for (int toggle = 0; toggle < 3; toggle++) {
        data.togglePlayerToMove();
        assertEquals(black ? "70.0%" : "30.0%", WinrateReading.of(data).percent(true));
        assertEquals(black ? "60.0%" : "40.0%", WinrateReading.of(data, true).percent(true));
        assertEquals(WinrateReading.Source.RETAINED, WinrateReading.of(data).source());
        assertEquals(WinrateReading.Source.RETAINED, WinrateReading.of(data, true).source());
        assertEquals(black ? 4 : -4, MoveStatusText.blackScore(data));
        assertEquals(black ? 4 : -4, WinrateGraph.blackPerspectiveScoreMean(data));
        assertEquals(title, MainWindowTitle.evaluation(data, false));
        assertEquals(change, MoveStatusText.previousMoveStatus(KO, data, previous, true));
        assertEquals(engineChange, MoveStatusText.engineGameWinrateChange(data, previous));
        assertEquals(70, data.winrate, "The stored payload itself must not be rewritten");
      }
    }
  }

  @Test
  void togglePersistsOnlyItsOwnChoiceAndFailedSaveKeepsThePublishedState(@TempDir Path work)
      throws Exception {
    Config config = ConfigTestHelper.createForTests(work);
    config.uiConfig = new JSONObject().put("coordinate-size", 140).put("coordinate-bold", true);
    config.leelazConfig = new JSONObject().put("keep", "engine-setting");
    config.config = new JSONObject().put("ui", config.uiConfig).put("leelaz", config.leelazConfig);
    assertFalse(NameWinrates.shown(config.uiConfig));
    JSONObject ui = config.uiConfig;
    NameWinrates.toggle(config);
    Path path = Path.of(config.getConfigFilePath());
    JSONObject reloaded = new JSONObject(Files.readString(path));
    assertTrue(NameWinrates.shown(reloaded.getJSONObject("ui")));
    assertSame(ui, config.uiConfig);
    assertEquals(140, reloaded.getJSONObject("ui").getInt("coordinate-size"));
    assertEquals("engine-setting", reloaded.getJSONObject("leelaz").getString("keep"));
    Path saved = work.resolve("saved-config.txt");
    Files.move(path, saved);
    Files.createDirectory(path);
    Files.writeString(path.resolve("keep"), "occupied");
    assertThrows(IOException.class, () -> NameWinrates.toggle(config));
    assertTrue(NameWinrates.shown(ui));
    assertTrue(new JSONObject(Files.readString(saved)).similar(config.config));
  }

  private static BoardData saved(double winrate, boolean blackToPlay, int move) {
    BoardData data = BoardData.empty(19, 19);
    data.setPlayouts(100);
    data.analysisHeaderSlots = 3;
    data.winrate = winrate;
    data.blackToPlay = blackToPlay;
    data.moveNumber = move;
    return data;
  }
}
