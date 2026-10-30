package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Compatibility at the SGF import boundary, including tolerated legacy analysis syntax. */
class SGFAnalysisParsingTest {
  private static final String MOVE = "move C3 visits 100 winrate 5500 pv C3 D4";

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void literalSeparatorsAndWhitespaceKeepCandidatesAcrossParserEntryPoints(String tag)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      String detail = " info \t info \u0001move\tC3 visits 100 unknown ignored winrate 5500"
          + " pv C3\u0001D4 E5\u0002 info move D4 visits 50 pv D4"
          + " ownership\t0.25 -0.5 bad 1.0";
      String branch = ";B[aa]" + tag + "[KataGo 45 100 2.5 1.2 0\n" + detail + "]";
      String sgf = "(;SZ[5]" + branch + ")";
      for (String entry : List.of("detached", "live", "edit", "branch")) {
        BoardHistoryList history;
        if (entry.equals("detached")) {
          history = SGFParser.parseSgf(sgf, true);
        } else if (entry.equals("branch")) {
          history = SGFParser.parseSgf("(;SZ[5])", true);
          SGFParser.parseBranch(history, branch);
        } else {
          if (entry.equals("live")) assertTrue(SGFParser.loadFromString(sgf, false));
          else assertTrue(SGFParser.loadFromStringforedit(sgf));
          history = Lizzie.board.getHistory();
        }
        BoardData data = history.getStart().next().orElseThrow().getData();
        List<MoveData> moves = moves(data, tag);
        assertEquals(2, moves.size());
        assertEquals("C3", moves.get(0).coordinate);
        assertEquals(55, moves.get(0).winrate);
        assertEquals(1.2, moves.get(0).scoreStdev);
        assertEquals(List.of("C3\u0001D4", "E5"), moves.get(0).variation);
        assertEquals("D4", moves.get(1).coordinate);
        assertEquals(1, moves.get(1).order);
        assertEquals(List.of(0.25, -0.5), ownership(data, tag));
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"LZ,false,1", "LZOP,false,1", "LZ2,false,3", "LZOP2,false,3",
      "LZ,true,3", "LZOP,true,3", "LZ2,true,3", "LZOP2,true,3"})
  void suggestionLimitRetainsOwnershipAndSecondaryEnginePolicy(
      String tag, boolean showNoSuggestionCircle, int count) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.limitMaxSuggestion = 1;
      Lizzie.config.showNoSuggCircle = showNoSuggestionCircle;
      BoardData data = parse(tag, MOVE + " info " + MOVE + " info " + MOVE + " ownership 0.5");
      assertEquals(count, moves(data, tag).size());
      assertEquals(List.of(0.5), ownership(data, tag));
      if (count == 1) {
        data = parse(tag, MOVE + " info move D4 order invalid ownership -0.5");
        assertEquals(1, moves(data, tag).size(), "discarded candidates are not interpreted");
        assertEquals(List.of(-0.5), ownership(data, tag));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void defaultSuggestionCapStillReadsOwnershipAfterIgnoredCandidates(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      BoardData data = parse(tag, (MOVE + " info ").repeat(361)
          + "move D4 order invalid ownership 0.25");
      assertEquals(361, moves(data, tag).size());
      assertEquals(List.of(0.25), ownership(data, tag));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void branchLimitDoesNotTruncateVisitsAfterLongPv(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.limitBranchLength = 2;
      MoveData move = moves(parse(tag, "move C3 visits 100 pv " + "C3 D4 ".repeat(512)
          + "pvVisits 100 90 80 pvVisits tail ownership 1.0"), tag).get(0);
      assertEquals(List.of("C3", "D4"), move.variation);
      assertEquals(List.of("100", "90", "80", "pvVisits", "tail"), move.pvVisits);
      assertEquals(List.of("C3"), moves(parse(tag, "move C3 visits 100 pv"), tag).get(0).variation);
      move = moves(parse(tag, "move C3 visits 100 pv pvVisits"), tag).get(0);
      assertEquals(List.of("C3"), move.variation);
      assertEquals(List.of(), move.pvVisits);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void ownershipRetainsNumericFormsAndStopsAtFirstInvalidToken(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      BoardData data = parse(tag, MOVE
          + " ownership\u0001-0.0 1e-3 0x1.0p-1 NaN Infinity -Infinity invalid 0.75");
      List<Double> values = ownership(data, tag);
      assertEquals(List.of(-0.0, 0.001, 0.5, Double.NaN,
          Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY), values);
      assertThrows(UnsupportedOperationException.class, () -> values.set(0, 1.0));
      assertNull(ownership(parse(tag, MOVE), tag));
      assertEquals(List.of(), ownership(parse(tag, MOVE + " ownership"), tag));
      assertEquals(List.of(), ownership(parse(tag, MOVE + " ownership invalid 1.0"), tag));
      assertEquals(List.of(0.5), ownership(parse(tag, MOVE + "ownership 0.5"), tag),
          "legacy ownership matching is not restricted to token boundaries");
      data = parse(tag, MOVE + " ownership " + "0.25 ".repeat(1025));
      assertEquals(java.util.Collections.nCopies(1025, 0.25), ownership(data, tag),
          "legacy imports do not truncate ownership to the current board area");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void blankSegmentsTrailingKeysAndNonAsciiSpacesKeepLegacyMeaning(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      assertEquals(0, moves(parse(tag, " info \t info "), tag).size());
      assertEquals(0, moves(parse(tag, "\u0001\t\u0002"), tag).size());
      BoardData data = parse(tag, "move C3 visits 100 trailing");
      assertEquals(List.of("C3"), moves(data, tag).get(0).variation);
      data = parse(tag, "move C3 visits 100 pv C3\u00a0D4\tE5 info ");
      assertEquals(List.of("C3\u00a0D4", "E5"), moves(data, tag).get(0).variation);
      assertEquals(1, moves(data, tag).size());
    }
  }

  private static BoardData parse(String tag, String detail) {
    BoardHistoryList history = SGFParser.parseSgf(
        "(;SZ[5]" + tag + "[KataGo 45 100 2.5 1.2 0\n" + detail + "])", true);
    assertNotNull(history);
    return history.getStart().getData();
  }

  private static List<MoveData> moves(BoardData data, String tag) {
    return tag.endsWith("2") ? data.bestMoves2 : data.bestMoves;
  }

  private static List<Double> ownership(BoardData data, String tag) {
    return tag.endsWith("2") ? data.estimateArray2 : data.estimateArray;
  }
}
