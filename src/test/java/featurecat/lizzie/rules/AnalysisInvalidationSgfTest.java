package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.analysis.KataGoAnalysisPayload;
import featurecat.lizzie.analysis.WholeGameAnalysisPlan;
import featurecat.lizzie.util.AtomicSgfFileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnalysisInvalidationSgfTest {
  private static final String PRIMARY = "KataGo 40.0 700 2.5 1.25 0 rootVisits=700\n"
      + "move C3 visits 700 winrate 6000 prior 1000 scoreMean 2.5 order 0 pv C3 D4"
      + " ownership 0.25 -0.25";
  private static final String SECONDARY = "Other 55.0 800 -1.5 1.5 0 rootVisits=800\n"
      + "move D4 visits 800 winrate 4500 prior 1000 scoreMean -1.5 order 0 pv D4 C3"
      + " ownership 0.5 -0.5";
  private static final AnalysisProvenance SOURCE = new AnalysisProvenance(
      "KataGo", "1.16", "recorded-model", "a".repeat(64), "b".repeat(64),
      "chinese", 7.5, 0.0, 0.0, "");

  @TempDir Path tempDirectory;

  @ParameterizedTest
  @CsvSource({
      "false,false,false", "true,false,false", "false,true,false",
      "true,false,true", "false,true,true", "true,true,true"
  })
  void slotValiditySurvivesRepeatedReopenIndependentlyOfRecordedSources(
      boolean primaryInvalidated, boolean secondaryInvalidated, boolean recordedSource)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + analysisTags(true) + ")", true);
      BoardData original = history.getStart().getData();
      if (recordedSource) recordSources(original);
      Object oldRun = new Object();
      original.markPrimaryAnalysisRun(oldRun);
      Lizzie.board.setHistory(history);
      assertFalse(Lizzie.config.isDoubleEngineMode());
      if (primaryInvalidated) Lizzie.board.clearbestmoves();
      if (secondaryInvalidated) Lizzie.board.clearbestmoves2();
      assertValidity(history.getStart(), primaryInvalidated, secondaryInvalidated);

      for (int round = 0; round < 2; round++) {
        Lizzie.board.setHistory(history);
        history = SGFParser.parseSgf(SGFParser.saveToString(false), true);
        BoardHistoryNode root = history.getStart();
        BoardData data = root.getData();
        assertVisiblePayload(data);
        assertEquals(recordedSource ? SOURCE : null, data.primaryAnalysisProvenance());
        assertEquals(recordedSource ? SOURCE : null, data.primaryOwnershipProvenance());
        assertEquals(recordedSource ? SOURCE : null, data.secondaryAnalysisProvenance());
        assertEquals(recordedSource ? SOURCE : null, data.secondaryOwnershipProvenance());
        assertFalse(data.hasReusablePrimaryAnalysis(500, true, oldRun),
            "SGF must not grant an earlier live run's completion permission");
        assertValidity(root, primaryInvalidated, secondaryInvalidated);
      }
    }
  }

  @Test
  void invalidatedSubtreeKeepsMovePassSetupAndVariationPayloadsButRemainsPending()
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + analysisTags(true)
          + "(;B[aa]" + analysisTags(false)
          + "(;W[]" + analysisTags(false) + ";AB[bb]PL[B]" + analysisTags(false) + ")"
          + "(;W[ee]" + analysisTags(false) + "))"
          + "(;B[dd]" + analysisTags(false) + "))", true);
      for (BoardHistoryNode node : allNodes(history.getStart())) recordSources(node.getData());
      Lizzie.board.setHistory(history);
      BoardHistoryNode invalidated = history.getStart().getVariation(0).orElseThrow();
      Lizzie.board.clearBestMovesAfterForFirstEngine(invalidated);
      Lizzie.board.clearBestMovesAfterForSecondEngine(invalidated);
      assertSubtreeState(history);

      for (int round = 0; round < 2; round++) {
        Lizzie.board.setHistory(history);
        history = SGFParser.parseSgf(SGFParser.saveToString(false), true);
        assertSubtreeState(history);
      }
    }
  }

  @Test
  void uiSaveSnapshotAndFileLoadKeepInvalidatedResultsVisibleAndNonReusable() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + analysisTags(true) + ")", true);
      Lizzie.board.setHistory(history);
      recordSources(history.getStart().getData());
      Lizzie.board.clearbestmoves();
      Lizzie.board.clearbestmoves2();
      assertValidity(history.getStart(), true, true);

      Path file = tempDirectory.resolve("무효화된-분석.sgf");
      for (int round = 0; round < 2; round++) {
        FutureTask<String> capture = new FutureTask<>(
            () -> SGFParser.saveSnapshot(Lizzie.board, false, false, false));
        SwingUtilities.invokeAndWait(capture);
        AtomicSgfFileWriter.write(file, capture.get());
        assertTrue(SGFParser.load(file.toString(), false, false));
        SwingUtilities.invokeAndWait(() -> {});
        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertVisiblePayload(root.getData());
        assertEquals(SOURCE, root.getData().primaryAnalysisProvenance());
        assertEquals(SOURCE, root.getData().secondaryAnalysisProvenance());
        assertValidity(root, true, true);
      }
    }
  }

  @Test
  void freshLowerVisitReplacementValidatesOnlyItsSlotAcrossFurtherReopens() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + analysisTags(true) + ")", true);
      recordSources(history.getStart().getData());
      Lizzie.board.setHistory(history);
      Lizzie.board.clearbestmoves();
      Lizzie.board.clearbestmoves2();
      history = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      Lizzie.board.setHistory(history);
      Lizzie.config.enableLizzieCache = true;
      BoardData reopened = history.getStart().getData();
      KataGoAnalysisPayload incoming = KataGoAnalysisPayload.parse(
          "info move B2 visits 600 winrate 0.65 scoreMean 1.0 scoreStdev 0.5 pv B2 C3"
              + " rootInfo visits 600 ownership 0.75 -0.75");
      assertEquals(BoardData.AnalysisAdoption.FULL,
          reopened.adoptOrdinaryAnalysis(
              incoming.moves, "KataGo", Lizzie.leelaz, incoming.totalVisits(),
              incoming.rootVisits, incoming.ownership, new Object(), false, false),
          "fresh analysis must replace an invalidated result even at fewer visits");

      for (int round = 0; round < 2; round++) {
        Lizzie.board.setHistory(history);
        history = SGFParser.parseSgf(SGFParser.saveToString(false), true);
        BoardData data = history.getStart().getData();
        assertEquals(600, data.getPlayouts());
        assertEquals("B2", data.bestMoves.get(0).coordinate);
        assertEquals(65.0, data.winrate);
        assertEquals(2, data.estimateArray.size());
        assertEquals(0.75, data.estimateArray.get(0), 1e-12);
        assertEquals(-0.75, data.estimateArray.get(1), 1e-12);
        assertEquals(800, data.getPlayouts2());
        assertEquals("D4", data.bestMoves2.get(0).coordinate);
        assertEquals(List.of(0.5, -0.5), data.estimateArray2);
        assertEquals(SOURCE, data.secondaryAnalysisProvenance());
        assertEquals(SOURCE, data.secondaryOwnershipProvenance());
        assertValidity(history.getStart(), false, true);
      }
    }
  }

  private static void assertValidity(
      BoardHistoryNode root, boolean primaryInvalidated, boolean secondaryInvalidated) {
    BoardData data = root.getData();
    WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(root, 32, 500);
    assertAll(
        () -> assertEquals(primaryInvalidated, data.isChanged, "primary invalidation"),
        () -> assertEquals(secondaryInvalidated, data.isChanged2, "secondary invalidation"),
        () -> assertTrue(data.hasCompletePrimaryAnalysis(500, true),
            "invalidation must retain the complete displayed result"),
        () -> assertEquals(!primaryInvalidated, data.hasReusablePrimaryAnalysis(500, true),
            "a complete invalidated result cannot satisfy new analysis work"),
        () -> assertEquals(primaryInvalidated ? List.of(root) : List.of(),
            plan.positionsMissingAnalysis(500, true), "actual mainline work still required"),
        () -> assertEquals(primaryInvalidated ? 0 : 1, plan.completedAtLeast(500, true)));
  }

  private static void assertSubtreeState(BoardHistoryList history) {
    BoardHistoryNode root = history.getStart();
    BoardHistoryNode move = root.getVariation(0).orElseThrow();
    BoardHistoryNode pass = move.getVariation(0).orElseThrow();
    BoardHistoryNode setup = pass.next().orElseThrow();
    BoardHistoryNode variation = move.getVariation(1).orElseThrow();
    BoardHistoryNode validVariation = root.getVariation(1).orElseThrow();
    assertTrue(move.getData().isMoveNode());
    assertTrue(pass.getData().isPassNode());
    assertTrue(setup.getData().isSnapshotNode());
    assertEquals(6, allNodes(root).size());
    for (BoardHistoryNode node : allNodes(root)) {
      assertVisiblePayload(node.getData());
      assertEquals(SOURCE, node.getData().primaryAnalysisProvenance());
      assertEquals(SOURCE, node.getData().secondaryAnalysisProvenance());
    }
    WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(root, 32, 500);
    assertEquals(List.of(root, move, pass), plan.positions(), "setup is not a played move");
    assertAll(
        () -> assertTrue(root.getData().hasReusablePrimaryAnalysis(500, true)),
        () -> assertTrue(validVariation.getData().hasReusablePrimaryAnalysis(500, true)),
        () -> assertFalse(root.getData().isChanged2),
        () -> assertFalse(validVariation.getData().isChanged2),
        () -> {
          for (BoardHistoryNode node : List.of(move, pass, setup, variation)) {
            assertTrue(node.getData().isChanged,
                "invalidated primary node " + node.getData().moveNumber);
            assertTrue(node.getData().isChanged2,
                "invalidated secondary node " + node.getData().moveNumber);
            assertFalse(node.getData().hasReusablePrimaryAnalysis(500, true));
          }
        },
        () -> assertEquals(List.of(move, pass), plan.positionsMissingAnalysis(32, false)),
        () -> assertEquals(List.of(move, pass), plan.positionsMissingAnalysis(500, true)),
        () -> assertEquals(1, plan.completedAtLeast(500, true)));
  }

  private static String analysisTags(boolean root) {
    String tag = root ? "LZOP" : "LZ";
    return tag + "[" + PRIMARY + "]" + tag + "2[" + SECONDARY + "]";
  }

  private static void recordSources(BoardData data) {
    data.setPrimaryAnalysisProvenance(SOURCE);
    data.setPrimaryOwnershipProvenance(SOURCE);
    data.setSecondaryAnalysisProvenance(SOURCE);
    data.setSecondaryOwnershipProvenance(SOURCE);
  }

  private static void assertVisiblePayload(BoardData data) {
    assertEquals(700, data.getPlayouts());
    assertEquals(800, data.getPlayouts2());
    assertEquals(60.0, data.winrate);
    assertEquals(45.0, data.winrate2);
    assertEquals(2.5, data.scoreMean);
    assertEquals(-1.5, data.scoreMean2);
    assertEquals("C3", data.bestMoves.get(0).coordinate);
    assertEquals("D4", data.bestMoves2.get(0).coordinate);
    assertEquals(List.of("C3", "D4"), data.bestMoves.get(0).variation);
    assertEquals(List.of("D4", "C3"), data.bestMoves2.get(0).variation);
    assertEquals(List.of(0.25, -0.25), data.estimateArray);
    assertEquals(List.of(0.5, -0.5), data.estimateArray2);
  }

  private static List<BoardHistoryNode> allNodes(BoardHistoryNode root) {
    List<BoardHistoryNode> result = new ArrayList<>();
    result.add(root);
    for (BoardHistoryNode child : root.getVariations()) result.addAll(allNodes(child));
    return result;
  }
}
