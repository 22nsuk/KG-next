package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisMoveRestrictions;
import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.analysis.WholeGameAnalysisPlan;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisRestrictionSgfTest {
  private static final String PAYLOAD = "KataGo 40.0 700 2.5 1.25 0 rootVisits=700\n"
      + "move C3 visits 700 winrate 6000 prior 1000 scoreMean 2.5 order 0 pv C3 D4"
      + " ownership 0.25 -0.25";
  private static final AnalysisProvenance ORDINARY = source(null);
  private static final AnalysisProvenance ALLOW = source("allow b C3,D4 2 allow w C3,D4 2");
  private static final AnalysisProvenance AVOID = source("avoid w PASS 999");

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void allFourSourcesRoundTripWithoutTurningVisibleRestrictionsIntoCompletedWork(boolean invalidated)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + tags(true)
          + ";B[aa]" + tags(false) + ";W[]" + tags(false) + ";AB[bb]PL[B]" + tags(false) + ")", true);
      for (BoardHistoryNode node = history.getStart(); node != null; node = node.next().orElse(null)) {
        BoardData data = node.getData();
        data.setPrimaryAnalysisProvenance(ALLOW);
        data.setPrimaryOwnershipProvenance(AVOID);
        data.setSecondaryAnalysisProvenance(AVOID);
        data.setSecondaryOwnershipProvenance(ALLOW);
        data.isChanged = invalidated;
      }
      for (int round = 0; round < 2; round++) {
        Lizzie.board.setHistory(history);
        FutureTask<String> capture = new FutureTask<>(
            () -> SGFParser.saveSnapshot(Lizzie.board, false, false, false));
        SwingUtilities.invokeAndWait(capture);
        history = SGFParser.parseSgf(capture.get(), true);
        for (BoardHistoryNode node = history.getStart(); node != null; node = node.next().orElse(null)) {
          BoardData data = node.getData();
          assertEquals(ALLOW, data.primaryAnalysisProvenance());
          assertEquals(AVOID, data.primaryOwnershipProvenance());
          assertEquals(AVOID, data.secondaryAnalysisProvenance());
          assertEquals(ALLOW, data.secondaryOwnershipProvenance());
          assertEquals(invalidated, data.isChanged);
          assertFalse(data.isChanged2);
          assertEquals(700, data.getPlayouts());
          assertEquals(List.of(0.25, -0.25), data.estimateArray);
          assertTrue(data.hasCompletePrimaryAnalysis(500, true));
          assertFalse(data.hasReusablePrimaryAnalysis(500, false));
          assertEquals(3, decode(data.getProperty("KGP")).getInt("version"));
        }
        WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(history.getStart(), 32, 500);
        assertEquals(0, plan.completedAtLeast(500, false));
        assertEquals(plan.positions(), plan.positionsMissingAnalysis(500, false));
      }
    }
  }

  @Test
  void ordinaryVersionsStayUnchangedAndRestrictedOwnershipIsIndependent() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + tags(true) + ")", true);
      Lizzie.board.setHistory(history);
      BoardData data = history.getData();
      data.setPrimaryAnalysisProvenance(ORDINARY);
      assertEquals(1, savedMetadata().getInt("version"));
      data.isChanged = true;
      assertEquals(2, savedMetadata().getInt("version"));
      data.isChanged = false;
      data.setPrimaryOwnershipProvenance(AVOID);
      BoardData reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true).getData();
      assertEquals(3, decode(reopened.getProperty("KGP")).getInt("version"));
      assertTrue(reopened.hasReusablePrimaryAnalysis(500, false));
      assertFalse(reopened.hasReusablePrimaryAnalysis(500, true));
      assertEquals(AVOID, reopened.primaryOwnershipProvenance());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void metadataOrderAndDigestBindingFollowTheOriginalSlot(boolean metadataFirst) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + tags(true) + ")", true);
      Lizzie.board.setHistory(history);
      history.getData().setPrimaryAnalysisProvenance(ALLOW);
      history.getData().setSecondaryAnalysisProvenance(AVOID);
      String kgp = "KGP[" + encode(savedMetadata()) + "]";
      String canonical = SGFParser.saveToString(false);
      BoardData saved = SGFParser.parseSgf(canonical, true).getData();
      String original = "LZOP[" + SGFParser.Escaping(savedPayload(canonical, "LZOP")) + "]"
          + "LZOP2[" + SGFParser.Escaping(savedPayload(canonical, "LZOP2")) + "]";
      BoardData data = SGFParser.parseSgf("(;SZ[5]"
          + (metadataFirst ? kgp + original : original + kgp) + ")", true).getData();
      assertEquals(saved.primaryAnalysisProvenance(), data.primaryAnalysisProvenance());
      assertEquals(AVOID, data.secondaryAnalysisProvenance());
      String changed = original.replaceFirst("rootVisits=700", "rootVisits=701");
      data = SGFParser.parseSgf("(;SZ[5]" + changed + kgp + ")", true).getData();
      assertHeaderWrnFallback(data);
      assertEquals(AVOID, data.secondaryAnalysisProvenance());
    }
  }

  @Test
  void unsupportedOrMalformedConditionsStayOpaqueAndNeverBecomePartialSourceRecords() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + tags(true) + ")", true);
      Lizzie.board.setHistory(history);
      history.getData().setPrimaryAnalysisProvenance(ALLOW);
      JSONObject valid = savedMetadata();
      String canonical = SGFParser.saveToString(false);
      String payload = savedPayload(canonical, "LZOP");
      List<Consumer<JSONObject>> mutations = List.of(
          metadata -> metadata.put("version", 2),
          metadata -> restriction(metadata).put("untilDepth", "2"),
          metadata -> restriction(metadata).put("untilDepth", -1),
          metadata -> restriction(metadata).put("player", "unknown"),
          metadata -> restriction(metadata).put("kind", "future"),
          metadata -> restriction(metadata).put("coordinates", "D4,,Q16"),
          metadata -> restriction(metadata).put("extra", true));
      for (Consumer<JSONObject> mutation : mutations) {
        JSONObject value = new JSONObject(valid.toString());
        mutation.accept(value);
        String raw = encode(value);
        BoardData data = SGFParser.parseSgf("(;SZ[5]LZOP[" + SGFParser.Escaping(payload)
            + "]KGP[" + raw + "])", true).getData();
        assertEquals(700, data.getPlayouts());
        assertHeaderWrnFallback(data);
        assertEquals(raw, data.getProperty("KGP"));
      }
      valid.getJSONObject("primary").getJSONObject("analysis").put("moveRestrictions", new JSONArray());
      BoardData unknown = SGFParser.parseSgf("(;SZ[5]LZOP[" + SGFParser.Escaping(payload)
          + "]KGP[" + encode(valid) + "])", true).getData();
      assertTrue(unknown.hasRestrictedPrimaryAnalysis());
      assertTrue(unknown.primaryAnalysisProvenance().moveRestrictions().clauses().isEmpty());
      assertFalse(unknown.hasReusablePrimaryAnalysis(1, false));
    }
  }

  private static JSONObject restriction(JSONObject metadata) {
    return metadata.getJSONObject("primary").getJSONObject("analysis")
        .getJSONArray("moveRestrictions").getJSONObject(0);
  }

  private static void assertHeaderWrnFallback(BoardData data) {
    assertEquals(new AnalysisProvenance("", "", "", "", "", "", null, null, 0.0, ""),
        data.primaryAnalysisProvenance());
  }

  @Test
  void metadataAloneCannotRemoveRestrictionsButReplacingThePayloadCan() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + tags(true) + ")", true);
      Lizzie.board.setHistory(history);
      history.getData().setPrimaryAnalysisProvenance(ALLOW);
      history.getData().setSecondaryAnalysisProvenance(AVOID);
      history = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      for (String replacement : List.of("", "not base64!")) {
        SGFParser.parseBranch(history, ";KGP[" + replacement + "]");
        assertEquals(ALLOW, history.getData().primaryAnalysisProvenance());
        assertEquals(AVOID, history.getData().secondaryAnalysisProvenance());
        assertFalse(history.getData().hasReusablePrimaryAnalysis(1, false));
      }
      SGFParser.parseBranch(history, ";LZ[" + PAYLOAD + "]");
      assertNull(history.getData().primaryAnalysisProvenance());
      assertEquals(AVOID, history.getData().secondaryAnalysisProvenance());
      assertTrue(history.getData().hasReusablePrimaryAnalysis(500, true));
    }
  }

  private static JSONObject savedMetadata() throws Exception {
    return decode(SGFParser.parseSgf(SGFParser.saveToString(false), true).getData().getProperty("KGP"));
  }

  private static String savedPayload(String sgf, String key) {
    int start = sgf.indexOf(key + "[") + key.length() + 1;
    return sgf.substring(start, sgf.indexOf(']', start));
  }

  private static JSONObject decode(String value) {
    return new JSONObject(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
  }

  private static String encode(JSONObject value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String tags(boolean root) {
    String key = root ? "LZOP" : "LZ";
    return key + "[" + PAYLOAD + "]" + key + "2[" + PAYLOAD + "]";
  }

  private static AnalysisProvenance source(String restriction) {
    return new AnalysisProvenance("KataGo", "1.16", "recorded-model", "", "", "chinese",
        7.5, 0.0, 0.0, "", restriction == null ? null
            : AnalysisMoveRestrictions.fromCommand("kata-analyze 10 " + restriction));
  }
}
