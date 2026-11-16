package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Compatibility at the SGF import boundary, including tolerated legacy analysis syntax. */
class SGFAnalysisParsingTest {
  private static final String MOVE = "move C3 visits 100 winrate 5500 pv C3 D4";

  @ParameterizedTest
  @ValueSource(strings = {"detached", "live", "edit", "branch"})
  void retainedTokensShareWithinOneImportWhileCandidatesAndListsStayWritable(String entry)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      String payload = "[KataGo 45 100 2.5 1.2 0\n"
          + "move C3 visits 100 pv C3 D4 C3 pvVisits 001 +1 001 info "
          + "move C3 visits 100 pv C3 D4 C3 pvVisits 001 +1 001]";
      String branch = ";B[aa]LZ" + payload + "LZ2" + payload
          + ";W[bb]LZOP" + payload + "LZOP2" + payload;
      BoardHistoryList history = importHistory(entry, branch);
      List<MoveData> candidates = new ArrayList<>();
      for (BoardHistoryNode node = history.getStart().next().orElseThrow();
          node != null; node = node.next().orElse(null)) {
        assertEquals(2, node.getData().bestMoves.size());
        assertEquals(2, node.getData().bestMoves2.size());
        candidates.addAll(node.getData().bestMoves);
        candidates.addAll(node.getData().bestMoves2);
      }
      assertEquals(8, candidates.size());
      MoveData first = candidates.get(0);
      MoveData separateImport = importHistory(entry, branch).getStart().next().orElseThrow()
          .getData().bestMoves.get(0);
      assertNotSame(first.coordinate, separateImport.coordinate,
          "the sharing table is not global or retained between imports");
      BoardHistorySnapshot snapshot = history.copyForRestore(null);
      for (int i = 0; i < candidates.size(); i++) {
        MoveData move = candidates.get(i);
        assertSame(first.coordinate, move.coordinate);
        assertSame(move.coordinate, move.variation.get(0));
        assertSame(move.coordinate, move.variation.get(2));
        assertSame(first.pvVisits.get(0), move.pvVisits.get(0));
        assertSame(move.pvVisits.get(0), move.pvVisits.get(2));
        if (i > 0) {
          assertNotSame(first, move);
          assertNotSame(first.variation, move.variation);
          assertNotSame(first.pvVisits, move.pvVisits);
        }
      }
      for (MoveData move : candidates) {
        assertEquals(List.of("C3", "D4", "C3"), move.variation);
        assertEquals(List.of("001", "+1", "001"), move.pvVisits);
        move.coordinate = "pass";
        move.variation.clear();
        move.pvVisits.set(0, "changed");
      }
      assertEquals(List.of("C3", "D4", "C3"), separateImport.variation);
      BoardHistoryList restored = snapshot.restore(
          false, Lizzie.config.matchAiMoves, Lizzie.config.matchAiPercentsPlayouts);
      for (BoardHistoryNode node = restored.getStart().next().orElseThrow();
          node != null; node = node.next().orElse(null)) {
        for (List<MoveData> moves : List.of(node.getData().bestMoves, node.getData().bestMoves2)) {
          for (MoveData move : moves) {
            assertEquals("C3", move.coordinate);
            assertEquals(List.of("C3", "D4", "C3"), move.variation);
            assertEquals(List.of("001", "+1", "001"), move.pvVisits);
          }
        }
      }
    }
  }

  @Test
  void tokenRegionsReuseExactUtf16TextAndKeepLongValuesOutsideTheTable() {
    BoardData.AnalysisStrings strings = new BoardData.AnalysisStrings();
    for (String value : List.of("C3", "C30", "c3", "한글", "한글\uD83C\uDF0F", "001", "+1", "-0",
        "polygenelubricants", "x".repeat(64), "\uD83C\uDF0F".repeat(32), "x".repeat(65))) {
      String source = "prefix:" + value + ":suffix";
      String retained = strings.substring(source, 7, 7 + value.length());
      String repeated = strings.substring("(" + value + ")", 1, 1 + value.length());
      assertEquals(value, retained);
      assertEquals(value, repeated);
      if (value.length() <= 64) {
        assertSame(retained, repeated);
        assertSame(retained, strings.reuse(new String(value)),
            "existing strings and source regions use the same exact-text table");
      } else {
        assertNotSame(retained, repeated);
      }
    }
    String existing = new String("Q16");
    assertSame(existing, strings.reuse(existing));
    assertSame(existing, strings.substring("pv Q16 tail", 3, 6));
  }

  @Test
  void tokenRegionCollisionsAndSaturationKeepTextWithoutEvictingEntries() {
    BoardData.AnalysisStrings strings = new BoardData.AnalysisStrings();
    List<String> values = new ArrayList<>(List.of("AaAaAa", "AaAaBB", "AaBBAa", "AaBBBB",
        "BBAaAa", "BBAaBB", "BBBBAa", "BBBBBB"));
    for (int i = 0; i < 4300; i++) values.add("token-" + i);
    List<String> retained = new ArrayList<>();
    for (String value : values) {
      retained.add(strings.substring("[" + value + "]", 1, 1 + value.length()));
    }
    assertEquals(values, retained);
    int reused = 0;
    for (int i = 0; i < values.size(); i++) {
      String value = values.get(i);
      String repeated = strings.substring("(" + value + ")", 1, 1 + value.length());
      assertEquals(value, repeated);
      if (repeated == retained.get(i)) reused++;
      if (i < 4) assertSame(retained.get(i), repeated, "earliest collisions are not evicted");
      if (i >= 4 && i < 8) assertNotSame(retained.get(i), repeated, "collision work stays bounded");
    }
    assertTrue(reused > 0 && reused <= 4096, "reuse must stay within the bounded table");
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void legacyNamesDecodeOnceWithoutInventingProvenance(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      // More than one literal slash may be intentional, or retained by an older importer.
      // Do not guess at the original name by repeatedly unescaping it.
      for (String name : List.of("KataGo", "엔진]", "path\\net\\n", "path\\\\net", "name\\]")) {
        String escaped = name.replace("\\", "\\\\").replace("]", "\\]");
        BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + tag + "["
            + escaped + " 45 100 2.5 1.2 0\n" + MOVE + " ownership 0.25])", true);
        for (int round = 0; round < 3; round++) {
          BoardData data = history.getStart().getData();
          assertEquals(name, tag.endsWith("2") ? data.engineName2 : data.engineName);
          assertEquals(List.of("C3", "D4"), moves(data, tag).get(0).variation);
          assertEquals(List.of(0.25), ownership(data, tag));
          assertNull(tag.endsWith("2")
              ? data.secondaryAnalysisProvenance() : data.primaryAnalysisProvenance());
          assertNull(tag.endsWith("2")
              ? data.secondaryOwnershipProvenance() : data.primaryOwnershipProvenance());
          if (round < 2) {
            Lizzie.board.setHistory(history);
            history = SGFParser.parseSgf(SGFParser.saveToString(false), true);
          }
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void literalSeparatorsAndWhitespaceKeepCandidatesAcrossParserEntryPoints(String tag)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      String detail = " info \t info \u0001move\tC3 visits 100 unknown ignored winrate 5500"
          + " pv C3\u0001D4 E5\u0002 info move D4 visits 50 pv D4"
          + " ownership\t0.25 -0.5 bad 1.0";
      String opaque = "raw\\]\\\\n";
      String branch = ";B[aa]" + tag + "[KataGo 45 100 2.5 1.2 0\n" + detail + "]"
          + "XX[" + opaque + "]KGP[" + opaque + "]C[comment\\]\\\\n]";
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
        assertEquals(opaque, data.getProperty("XX"));
        assertEquals(opaque, data.getProperty("KGP"));
        assertEquals("comment]\\n", data.comment);
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

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void candidateKeysKeepExactBoundariesAndLastValueWins(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      MoveData move = moves(parse(tag,
          "unknown visits xmove D4 move C3 xvisits 999 visits 100 order +7 edgeVisits 12"
              + " winrate 5500 prior 125 scoreMean -0.0 scoreStdev NaN visits 200"
              + " scoreMeanExtra 9 VISITS 888 pv C3 D4 pvVisits 200 150"), tag).get(0);
      assertEquals("C3", move.coordinate);
      assertEquals(200, move.playouts);
      assertEquals(7, move.order);
      assertEquals(12, move.edgeVisits);
      assertEquals(55.0, move.winrate);
      assertEquals(55.0, move.lcb);
      assertEquals(1.25, move.policy);
      assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(move.scoreMean));
      assertEquals(List.of("C3", "D4"), move.variation);
      assertEquals(List.of("200", "150"), move.pvVisits);
      assertEquals(List.of("C3"), moves(parse(tag, "move C3 visits 100 visits"), tag).get(0).variation);
    }
  }

  private static BoardData parse(String tag, String detail) {
    BoardHistoryList history = SGFParser.parseSgf(
        "(;SZ[5]" + tag + "[KataGo 45 100 2.5 1.2 0\n" + detail + "])", true);
    assertNotNull(history);
    return history.getStart().getData();
  }

  private static BoardHistoryList importHistory(String entry, String branch) {
    String sgf = "(;SZ[5]" + branch + ")";
    if (entry.equals("detached")) return SGFParser.parseSgf(sgf, true);
    if (entry.equals("branch")) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5])", true);
      SGFParser.parseBranch(history, branch);
      return history;
    }
    if (entry.equals("live")) assertTrue(SGFParser.loadFromString(sgf, false));
    else assertTrue(SGFParser.loadFromStringforedit(sgf));
    return Lizzie.board.getHistory();
  }

  private static List<MoveData> moves(BoardData data, String tag) {
    return tag.endsWith("2") ? data.bestMoves2 : data.bestMoves;
  }

  private static List<Double> ownership(BoardData data, String tag) {
    return tag.endsWith("2") ? data.estimateArray2 : data.estimateArray;
  }
}
