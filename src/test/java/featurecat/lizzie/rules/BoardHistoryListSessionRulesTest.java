package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.KataGoRules;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardHistoryListSessionRulesTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void offlineStartingEditAndReopenRetainSelfCaptureUnderRecordedRules(boolean adoptRules)
      throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      Lizzie.setPrimaryEngine(null);
      assertTrue(SGFParser.loadFromString(
          "(;SZ[5]PL[B]AB[aa][ee]AW[ba][bb][ac]RU[New Zealand];B[ab])", false));
      Board board = fixture.board();
      if (adoptRules) SGFParser.adoptCurrentHistoryExternalRules();
      assertBlackSelfCapture(board.getHistory());

      board.editMove(new int[] {4, 4}, false, true);

      assertEquals(
          Stone.EMPTY, board.getHistory().getStart().getData().stones[Board.getIndex(4, 4)]);
      assertBlackSelfCapture(board.getHistory());
      assertBlackSelfCapture(SGFParser.parseSgf(SGFParser.saveToString(false), true));
    }
  }

  @Test
  void importedContinuationInheritsTheEditedGamesRules() throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromStringforedit("(;SZ[5]PL[B]AB[aa][ee]AW[ba][bb][ac])"));
      Board board = fixture.board();
      board.getHistory().publishManualRules(KataGoRules.parse("New Zealand").orElseThrow());
      board.editMove(new int[] {4, 4}, false, true);
      Lizzie.leelaz.canSuicidal = false;
      SGFParser.parseBranch(board.getHistory(), ";B[ab]");
      assertBlackSelfCapture(board.getHistory());
    }
  }

  private static void assertBlackSelfCapture(BoardHistoryList history) {
    BoardData move = history.getStart().next().orElseThrow().getData();
    assertEquals(Stone.BLACK, move.lastMoveColor);
    assertTrue(RulesLayerTestHarness.sameLastMove(move, 0, 1));
    assertEquals(Stone.EMPTY, move.stones[Board.getIndex(0, 0)]);
    assertEquals(Stone.EMPTY, move.stones[Board.getIndex(0, 1)]);
  }

  @Test
  void manualRulesEditRoundTripsSelfCaptureAndCommentWithOriginalDeclaration() throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromString(
          "(;SZ[5]PL[B]RU[Japanese]AB[aa][ba]AW[bb][ac];B[ab]C[keep])", false));
      Board board = fixture.board();
      board.getHistory().publishManualRules(KataGoRules.parse("New Zealand").orElseThrow());
      board.editMove(new int[] {1, 0}, true, false);
      assertBlackSelfCapture(board.getHistory());

      String saved = SGFParser.saveToString(false);
      Lizzie.setPrimaryEngine(null);
      assertTrue(SGFParser.loadFromString(saved, false));
      for (var history : List.of(board.getHistory(), SGFParser.parseSgf(saved, true))) {
        assertEquals("Japanese", history.getStart().getData().getProperty("RU"));
        assertBlackSelfCapture(history);
        assertEquals("keep", history.getStart().next().orElseThrow().getData().comment);
      }
      assertEquals(KataGoRules.Summary.JAPANESE,
          SGFParser.adoptCurrentHistoryExternalRules().parsedRules().orElseThrow().summary());
    }
  }

  @Test
  void manualRulesOverrideBothRootDeclarationAndEngineDuringEdit() throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      assertTrue(SGFParser.loadFromString(
          "(;SZ[5]RU[NZ]AB[aa][ba]AW[bb][ac];B[ab])", false));
      Board board = fixture.board();
      BoardHistoryList before = board.getHistory();
      before.publishManualRules(KataGoRules.parse("Japanese").orElseThrow());
      Lizzie.leelaz.canSuicidal = true;

      // Recoloring ba closes the last liberty of the black group after B[ab].
      board.editMove(new int[] {1, 0}, true, false);

      assertSame(before, board.getHistory());
      assertEquals(Stone.BLACK, before.getEnd().getData().stones[Board.getIndex(0, 1)]);
      assertFalse(board.canUndoEdit());
    }
  }

  @ParameterizedTest
  @CsvSource({
      "RU[Japanese],true,false,false",
      "AP[KG-next-fork]RU[Japanese],true,false,false",
      "RU[unrecognized],true,false,true",
      "RU[unrecognized],false,false,false",
      "RU[Japanese],false,true,true"
  })
  void importUsesCapturedFallbackForUnknownRulesAndPreservesNoCapture(
      String rules, boolean engineSuicide, boolean noCapture, boolean accepted) throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      Lizzie.leelaz.canSuicidal = engineSuicide;
      Lizzie.config.noCapture = noCapture;
      String sgf = "(;SZ[5]PL[B]AB[aa]AW[ba][bb][ac]" + rules + ";B[ab]RU[NZ]AP[KG-next])";
      assertTrue(SGFParser.loadFromString(sgf, false));
      for (var history : List.of(fixture.board().getHistory(), SGFParser.parseSgf(sgf, true))) {
        var move = history.getStart().next();
        assertEquals(accepted, move.isPresent());
        if (accepted) {
          assertEquals(noCapture ? Stone.BLACK : Stone.EMPTY,
              move.orElseThrow().getData().stones[Board.getIndex(0, 1)]);
        }
      }
    }
  }

  @Test
  void publishesImmutableTargetsWithDistinctKindsAndMonotonicRevisions() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));

    BoardHistoryList.SessionRulesTarget initial = history.captureSessionRules();
    assertEquals(BoardHistoryList.SessionRulesKind.UNSPECIFIED, initial.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.NONE, initial.source());
    assertEquals(0L, initial.revision());
    assertFalse(initial.parsedRules().isPresent());
    assertNull(initial.rawDeclaration());

    BoardHistoryList.SessionRulesTarget missing = history.publishExternalRules(null);
    assertEquals(BoardHistoryList.SessionRulesKind.UNSPECIFIED, missing.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.EXTERNAL_IMPORT, missing.source());
    assertTrue(missing.revision() > initial.revision());
    assertFalse(missing.parsedRules().isPresent());
    assertNull(missing.rawDeclaration());

    BoardHistoryList.SessionRulesTarget invalid = history.publishExternalRules(" ");
    assertEquals(BoardHistoryList.SessionRulesKind.INVALID, invalid.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.EXTERNAL_IMPORT, invalid.source());
    assertEquals(" ", invalid.rawDeclaration());
    assertFalse(invalid.parsedRules().isPresent());
    assertTrue(invalid.revision() > missing.revision());

    BoardHistoryList.SessionRulesTarget valid = history.publishExternalRules("Japanese");
    assertEquals(BoardHistoryList.SessionRulesKind.VALID, valid.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.EXTERNAL_IMPORT, valid.source());
    assertEquals("Japanese", valid.rawDeclaration());
    assertTrue(valid.parsedRules().isPresent());
    assertEquals(KataGoRules.Summary.JAPANESE, valid.parsedRules().orElseThrow().summary());
    assertTrue(valid.revision() > invalid.revision());

    KataGoRules manualRules = KataGoRules.parse("Chinese").orElseThrow();
    BoardHistoryList.SessionRulesTarget manual = history.publishManualRules(manualRules);
    assertEquals(BoardHistoryList.SessionRulesKind.VALID, manual.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.MANUAL_SELECTION, manual.source());
    assertTrue(manual.parsedRules().isPresent());
    assertSame(manualRules, manual.parsedRules().orElseThrow());
    assertTrue(manual.revision() > valid.revision());

    history.add(BoardData.empty(9, 9));
    history.previous();
    history.clear();
    assertSame(manual, history.captureSessionRules());

    assertEquals(BoardHistoryList.SessionRulesKind.UNSPECIFIED, initial.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.NONE, initial.source());
    assertEquals(0L, initial.revision());
    assertFalse(initial.parsedRules().isPresent());
    assertNull(initial.rawDeclaration());
  }

  @Test
  void manualPublicationRequiresTheExactCapturedTarget() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));
    BoardHistoryList.SessionRulesTarget imported = history.publishExternalRules("Japanese");
    KataGoRules chinese = KataGoRules.parse("Chinese").orElseThrow();
    BoardHistoryList.ManualRulesIntent first = history.beginManualRulesIntent();

    BoardHistoryList.SessionRulesTarget manual =
        history.publishManualRulesIfCurrent(first, chinese).orElseThrow();

    assertEquals(imported.revision() + 1L, manual.revision());
    assertTrue(history.publishManualRulesIfCurrent(first, chinese).isEmpty());
    assertSame(manual, history.captureSessionRules());

    BoardHistoryList.ManualRulesIntent superseded = history.beginManualRulesIntent();
    BoardHistoryList.ManualRulesIntent latest = history.beginManualRulesIntent();
    assertTrue(history.publishManualRulesIfCurrent(superseded, chinese).isEmpty());
    assertTrue(history.isLatestManualRulesIntent(latest));
    assertSame(manual, history.captureSessionRules());
  }

  @Test
  void analysisOverrideIsBoundToTargetAndEngineSet() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));
    BoardHistoryList.SessionRulesTarget target = history.publishExternalRules("Japanese");
    Object primary = new Object();
    Object mirror = new Object();

    history.authorizeAnalysisWithCurrentRules(target, primary, 7L, mirror);
    assertTrue(history.permitsAnalysisWithCurrentRules(target, primary, 7L, mirror));
    assertFalse(history.permitsAnalysisWithCurrentRules(target, primary, 8L, mirror));
    assertFalse(history.permitsAnalysisWithCurrentRules(target, primary, 7L, new Object()));
    history.revokeAnalysisOverride(target);
    assertFalse(history.permitsAnalysisWithCurrentRules(target, primary, 7L, mirror));
    history.authorizeAnalysisWithCurrentRules(target, primary, 7L, mirror);

    BoardHistoryList.SessionRulesTarget replacement = history.publishExternalRules("Chinese");
    assertFalse(history.permitsAnalysisWithCurrentRules(target, primary, 7L, mirror));
    assertFalse(history.permitsAnalysisWithCurrentRules(replacement, primary, 7L, mirror));
  }
}
