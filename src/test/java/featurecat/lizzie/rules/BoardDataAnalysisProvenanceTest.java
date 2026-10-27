package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardDataAnalysisProvenanceTest {
  private static final AnalysisProvenance FIRST = source("first");
  private static final AnalysisProvenance SECOND = source("second");
  private static final AnalysisProvenance THIRD = source("third");

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void adoptionSeparatesEvaluationOwnershipAndRetainedOwnership(boolean secondary) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.enableLizzieCache = true;
      BoardData data = Lizzie.board.getData();
      KnownEngine engine = new KnownEngine(FIRST);
      Object stream = new Object();
      assertEquals(BoardData.AnalysisAdoption.FULL,
          data.adoptOrdinaryAnalysis(moves(500), "custom label", engine, 500, 500,
              List.of(0.1, -0.1), stream, secondary, false));
      assertSource(data, secondary, FIRST, FIRST);

      engine.provenance = SECOND;
      assertEquals(BoardData.AnalysisAdoption.OWNERSHIP_ONLY,
          data.adoptOrdinaryAnalysis(moves(50), "different label", engine, 50, 50,
              List.of(0.2, -0.2), new Object(), secondary, false));
      assertEquals(500, secondary ? data.getPlayouts2() : data.getPlayouts());
      assertSource(data, secondary, FIRST, SECOND);

      engine.provenance = THIRD;
      assertEquals(BoardData.AnalysisAdoption.REJECTED,
          data.adoptOrdinaryAnalysis(moves(40), "third label", engine, 40, 40,
              null, new Object(), secondary, false));
      assertSource(data, secondary, FIRST, SECOND);

      assertEquals(BoardData.AnalysisAdoption.FULL,
          data.adoptOrdinaryAnalysis(moves(600), "third label", engine, 600, 600,
              null, stream, secondary, false));
      assertSource(data, secondary, THIRD, SECOND);
      assertEquals(List.of(0.2, -0.2), secondary ? data.estimateArray2 : data.estimateArray);

      assertEquals(BoardData.AnalysisAdoption.FULL,
          data.adoptOrdinaryAnalysis(moves(700), "third label", engine, 700, 700,
              null, new Object(), secondary, false));
      assertSource(data, secondary, THIRD, null);
      assertNull(secondary ? data.estimateArray2 : data.estimateArray);
      assertSource(data, !secondary, null, null);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freshReplacementCannotRestoreHiddenCandidatesFromThePreviousSource(boolean secondary)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.enableLizzieCache = true;
      Lizzie.config.limitMaxSuggestion = 1;
      Lizzie.frame.priorityMoveCoords = new ArrayList<>();
      BoardData data = Lizzie.board.getData();
      KnownEngine engine = new KnownEngine(FIRST);
      List<MoveData> previous = new ArrayList<>(
          List.of(candidate("C3", 300, 0), candidate("D4", 200, 1)));
      assertEquals(BoardData.AnalysisAdoption.FULL,
          data.adoptOrdinaryAnalysis(previous, "previous", engine, 500, 500,
              null, new Object(), secondary, false));
      assertEquals("D4", (secondary ? data.bestMoves2OutOfRange : data.bestMovesOutOfRange)
          .get(0).coordinate);

      engine.provenance = SECOND;
      List<MoveData> fresh = new ArrayList<>(List.of(candidate("E5", 50, 0)));
      assertEquals(BoardData.AnalysisAdoption.REJECTED,
          data.adoptOrdinaryAnalysis(fresh, "current", engine, 50, 50,
              null, new Object(), secondary, false));
      assertSource(data, secondary, FIRST, null);
      assertEquals(BoardData.AnalysisAdoption.FULL,
          data.adoptOrdinaryAnalysis(fresh, "current", engine, 50, 50,
              null, new Object(), secondary, true));

      if (!secondary) {
        Lizzie.frame.priorityMoveCoords.add("D4");
        data.refreshFocusCandidateVisibility();
      }
      assertEquals(List.of("E5"), (secondary ? data.bestMoves2 : data.bestMoves)
          .stream().map(move -> move.coordinate).toList());
      assertTrue((secondary ? data.bestMoves2OutOfRange : data.bestMovesOutOfRange).isEmpty());
      assertSource(data, secondary, SECOND, null);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void genericImportsDoNotBorrowTheGloballySelectedEngine(boolean secondary) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.enableLizzieCache = true;
      BoardData data = Lizzie.board.getData();
      KnownEngine engine = new KnownEngine(FIRST);
      Lizzie.setPrimaryEngine(engine);
      if (secondary) {
        data.tryToSetBestMoves2FromEngine(moves(500), "label", engine, 500, List.of(0.1));
        data.tryToSetBestMoves2(moves(50), "import", false, 50, List.of(0.2));
      } else {
        data.tryToSetBestMovesFromEngine(moves(500), "label", engine, 500, List.of(0.1), true);
        data.tryToSetBestMoves(moves(50), "import", false, 50, List.of(0.2));
      }
      assertSource(data, secondary, FIRST, null);
      if (secondary) data.tryToSetBestMoves2(moves(600), "import", false, 600, List.of(0.3));
      else data.tryToSetBestMoves(moves(600), "import", false, 600, List.of(0.3));
      assertSource(data, secondary, null, null);
    }
  }

  @Test
  void copiesKeepDurableSourcesButNeverCarryReassessmentRunPermission() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      BoardData data = Lizzie.board.getData();
      data.tryToSetBestMovesFromEngine(moves(500), "label", new KnownEngine(FIRST),
          500, List.of(0.1), true);
      data.setPrimaryOwnershipProvenance(SECOND);
      data.setSecondaryAnalysisProvenance(THIRD);
      data.setSecondaryOwnershipProvenance(FIRST);
      data.addProperty("KGP", "opaque future metadata");
      Object run = new Object();
      data.markPrimaryAnalysisRun(run);
      data.setPlayouts(500);
      assertTrue(data.hasReusablePrimaryAnalysis(500, true, run));
      assertFalse(data.hasReusablePrimaryAnalysis(500, true, new Object()));

      BoardData clone = data.clone();
      BoardData sync = BoardData.empty(5, 5);
      sync.markPrimaryAnalysisRun(run);
      sync.sync(data);
      BoardData payload = BoardData.empty(5, 5);
      payload.markPrimaryAnalysisRun(run);
      payload.copyAnalysisPayloadFrom(data);
      for (BoardData copy : List.of(clone, sync, payload)) {
        assertSource(copy, false, FIRST, SECOND);
        assertSource(copy, true, THIRD, FIRST);
        assertEquals("opaque future metadata", copy.getProperty("KGP"));
        assertTrue(copy.hasReusablePrimaryAnalysis(500, true, null));
        assertFalse(copy.hasReusablePrimaryAnalysis(500, true, run));
      }
      data.tryToSetBestMovesFromEngine(moves(600), "label", new KnownEngine(THIRD),
          600, List.of(0.2), true);
      assertFalse(data.hasReusablePrimaryAnalysis(500, true, run));
      assertNull(data.getProperty("KGP"));
      data.markPrimaryAnalysisRun(run);
      data.clearAnalysisPayloadState();
      assertSource(data, false, null, null);
      assertSource(data, true, null, null);
      assertFalse(data.hasReusablePrimaryAnalysis(1, false, run));
      assertNull(data.getProperty("KGP"));
    }
  }

  @Test
  void invalidOptionalRuntimeMetadataBecomesUnknownWithoutBreakingAnalysis() {
    AnalysisProvenance source = new AnalysisProvenance(" ", "v1", "x".repeat(1025),
        "not-a-hash", "A".repeat(64), "japanese", Double.NaN, Double.POSITIVE_INFINITY,
        0.0, "rank_8d");
    assertEquals("", source.engineName());
    assertEquals("", source.modelName());
    assertEquals("", source.modelSha256());
    assertEquals("a".repeat(64), source.configSha256());
    assertNull(source.komi());
    assertNull(source.pda());
    assertEquals(0.0, source.wrn());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void informationClearActionsPreserveOwnershipSourceAndRemoveEvaluationSource(boolean after)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      BoardHistoryNode node = Lizzie.board.getHistory().getStart();
      BoardData data = node.getData();
      KnownEngine engine = new KnownEngine(FIRST);
      data.tryToSetBestMovesFromEngine(moves(500), "first", engine, 500, List.of(0.1), true);
      data.tryToSetBestMoves2FromEngine(moves(500), "second", engine, 500, List.of(0.2), true);
      data.setPrimaryOwnershipProvenance(SECOND);
      data.setSecondaryOwnershipProvenance(THIRD);
      Object run = new Object();
      data.markPrimaryAnalysisRun(run);
      if (after) Lizzie.board.clearBestMovesInfomationAfter(node);
      else Lizzie.board.clearBestMovesInfomation(node);
      assertSource(data, false, null, SECOND);
      assertSource(data, true, FIRST, THIRD);
      assertEquals(List.of(0.1), data.estimateArray);
      data.setPlayouts(500);
      data.bestMoves = moves(500);
      assertFalse(data.hasReusablePrimaryAnalysis(500, true, run));
      if (after) Lizzie.board.clearBestMovesInfomationAfter2(node);
      else Lizzie.board.clearbestmovesInfomation2(node);
      assertSource(data, true, null, THIRD);
      assertEquals(List.of(0.2), data.estimateArray2);
    }
  }

  private static AnalysisProvenance source(String name) {
    return new AnalysisProvenance("KataGo", "1.16", name, "a".repeat(64), "b".repeat(64),
        "japanese", 6.5, 0.0, 0.0, "");
  }

  private static void assertSource(BoardData data, boolean secondary,
      AnalysisProvenance analysis, AnalysisProvenance ownership) {
    assertEquals(analysis,
        secondary ? data.secondaryAnalysisProvenance() : data.primaryAnalysisProvenance());
    assertEquals(ownership,
        secondary ? data.secondaryOwnershipProvenance() : data.primaryOwnershipProvenance());
  }

  private static List<MoveData> moves(int visits) {
    MoveData move = new MoveData();
    move.coordinate = "C3";
    move.playouts = visits;
    move.winrate = 55;
    move.variation = new ArrayList<>(List.of("C3", "D4"));
    return new ArrayList<>(List.of(move));
  }

  private static MoveData candidate(String coordinate, int visits, int order) {
    MoveData move = new MoveData();
    move.coordinate = coordinate;
    move.playouts = visits;
    move.winrate = 55;
    move.order = order;
    move.variation = new ArrayList<>(List.of(coordinate));
    return move;
  }

  private static final class KnownEngine extends Leelaz {
    private AnalysisProvenance provenance;

    private KnownEngine(AnalysisProvenance provenance) throws IOException {
      super("");
      this.provenance = provenance;
    }

    @Override
    public AnalysisProvenance captureAnalysisProvenance() {
      return provenance;
    }
  }
}
