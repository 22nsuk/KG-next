package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisProvenance;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class AnalysisProvenanceSgfTest {
  private static final String PRIMARY = "KataGo 45.0 120 2.5 1.2 0 rootVisits=120\n"
      + "move C3 visits 120 winrate 5500 prior 1000 scoreMean 2.5 order 0 pv C3 D4"
      + " ownership 0.2 -0.2";
  private static final String SECONDARY = "Other 55.0 80 -2.5 1.3 0 rootVisits=80\n"
      + "move D4 visits 80 winrate 4500 prior 1000 scoreMean -2.5 order 0 pv D4 C3"
      + " ownership 0.3 -0.3";
  private static final AnalysisProvenance ANALYSIS = new AnalysisProvenance(
      "KataGo", "1.16.0", "모델 이름] \\ net.bin.gz", "a".repeat(64), "b".repeat(64),
      "{\"koRule\":\"SIMPLE\",\"scoringRule\":\"TERRITORY\"}", 6.5, 0.5, 0.75, "rank_8d");
  private static final AnalysisProvenance OWNERSHIP = new AnalysisProvenance(
      "Remote KataGo", "", "remote-model-id", "", "", "chinese", 7.5, null, null, "");

  @Test
  void rootMoveAndSetupRoundTripsKeepSeparateSourcesAndForgetRunTokens() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + legacyTags(true)
          + ";B[aa]" + legacyTags(false) + ";AB[bb]PL[W]" + legacyTags(false) + ")", true);
      Object run = new Object();
      for (BoardData data : mainLine(history)) {
        installSources(data);
        data.markPrimaryAnalysisRun(run);
        assertTrue(data.hasReusablePrimaryAnalysis(120, true, run));
      }
      for (int round = 0; round < 2; round++) {
        Lizzie.board.setHistory(history);
        String saved = SGFParser.saveToString(false);
        assertEquals(3, count(saved, "KGP["));
        assertTrue(saved.contains("LZOP["));
        assertTrue(saved.contains("LZ["));
        history = SGFParser.parseSgf(saved, true);
        List<BoardData> nodes = mainLine(history);
        assertEquals(3, nodes.size());
        for (BoardData data : nodes) {
          assertSources(data);
          assertEquals(120, data.getPlayouts());
          assertEquals(80, data.getPlayouts2());
          assertTrue(data.hasReusablePrimaryAnalysis(120, true));
          assertFalse(data.hasReusablePrimaryAnalysis(120, true, run));
        }
      }
    }
  }

  @Test
  void metadataOrderAndSetupTransfersWorkThroughAllParserEntrypoints() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      String kgp = "KGP[" + encode(metadata(PRIMARY, SECONDARY)) + "]";
      String analysis = legacyTags(false);
      for (String node : List.of(
          "B[aa]" + kgp + analysis + "AB[bb]PL[W]",
          "B[aa]" + analysis + "AB[bb]PL[W]" + kgp,
          "B[aa]AB[bb]PL[W]" + kgp + analysis,
          kgp + analysis + "B[aa]AB[bb]PL[W]")) {
        for (String entrypoint : List.of("detached", "edit", "branch")) {
          String context = entrypoint + ": " + node;
          BoardHistoryList history;
          if (entrypoint.equals("edit")) {
            assertTrue(SGFParser.loadFromStringforedit("(;SZ[5];" + node + ";W[cc])"));
            history = Lizzie.board.getHistory();
          } else if (entrypoint.equals("branch")) {
            history = SGFParser.parseSgf("(;SZ[5])", true);
            SGFParser.parseBranch(history, ";" + node + ";W[cc]");
          } else {
            history = SGFParser.parseSgf("(;SZ[5];" + node + ";W[cc])", true);
          }
          BoardData move = history.getStart().next().orElseThrow().getData();
          BoardData setup = history.getStart().next().orElseThrow().next().orElseThrow().getData();
          assertTrue(move.isMoveNode(), context);
          assertNull(move.primaryAnalysisProvenance(), context);
          assertNull(move.secondaryAnalysisProvenance(), context);
          assertTrue(setup.isSnapshotNode(), context);
          assertSources(setup);
          Lizzie.board.setHistory(history);
          BoardHistoryList reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true);
          assertSources(reopened.getStart().next().orElseThrow().next().orElseThrow().getData());
        }
      }
    }
  }

  @Test
  void legacyAndChangedPayloadsRemainVisibleWithoutInventingASource() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      BoardData legacy = SGFParser.parseSgf("(;SZ[5]" + legacyTags(true) + ")", true).getData();
      assertEquals(120, legacy.getPlayouts());
      assertNull(legacy.primaryAnalysisProvenance());
      assertNull(legacy.primaryOwnershipProvenance());

      String property = encode(metadata(PRIMARY, SECONDARY));
      String changed = PRIMARY.replace("rootVisits=120", "rootVisits=121");
      BoardData data = SGFParser.parseSgf("(;SZ[5]KGP[" + property + "]LZOP[" + changed
          + "]LZOP2[" + SECONDARY + "])", true).getData();
      assertEquals(121, data.getPlayouts());
      assertEquals("C3", data.bestMoves.get(0).coordinate);
      assertNull(data.primaryAnalysisProvenance());
      assertNull(data.primaryOwnershipProvenance());
      assertEquals(OWNERSHIP, data.secondaryAnalysisProvenance());
      assertEquals(ANALYSIS, data.secondaryOwnershipProvenance());
    }
  }

  @Test
  void importChecksOriginalPayloadBeforeSuggestionLimitsChangeIt() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.limitMaxSuggestion = 1;
      String primary = PRIMARY.replace(" ownership", " info move E5 visits 20 winrate 5000"
          + " prior 100 order 1 pv E5 D4 ownership");
      String property = encode(metadata(primary, SECONDARY));
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]KGP[" + property + "]LZOP["
          + primary + "]LZOP2[" + SECONDARY + "])", true);
      assertEquals(1, history.getData().bestMoves.size());
      assertSources(history.getData());
      Lizzie.board.setHistory(history);
      assertSources(SGFParser.parseSgf(SGFParser.saveToString(false), true).getData());
    }
  }

  @Test
  void allFourMaximumLengthSourceRecordsSurviveJsonEscapingAndRoundTrips() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      String escaped = "\u200b";
      AnalysisProvenance maximum = new AnalysisProvenance(
          escaped.repeat(1024), escaped.repeat(128), escaped.repeat(1024),
          "a".repeat(64), "b".repeat(64), escaped.repeat(8192),
          -Double.MAX_VALUE, -Double.MIN_NORMAL, Double.MIN_VALUE, escaped.repeat(256));
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + legacyTags(true) + ")", true);
      BoardData data = history.getData();
      data.setPrimaryAnalysisProvenance(maximum);
      data.setPrimaryOwnershipProvenance(maximum);
      data.setSecondaryAnalysisProvenance(maximum);
      data.setSecondaryOwnershipProvenance(maximum);

      for (int round = 0; round < 2; round++) {
        Lizzie.board.setHistory(history);
        String saved = SGFParser.saveToString(false);
        assertTrue(property(saved, "KGP").length() > 131072);
        history = SGFParser.parseSgf(saved, true);
        data = history.getData();
        assertEquals(maximum, data.primaryAnalysisProvenance());
        assertEquals(maximum, data.primaryOwnershipProvenance());
        assertEquals(maximum, data.secondaryAnalysisProvenance());
        assertEquals(maximum, data.secondaryOwnershipProvenance());
      }
    }
  }

  @Test
  void unsupportedOrMalformedMetadataPreservesLegacyAnalysisAndTheOpaqueProperty() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      List<String> properties = new ArrayList<>(List.of("not base64!", ""));
      String validJson = metadata(PRIMARY, SECONDARY).toString();
      String oversizedJson = validJson + " ".repeat(
          393216 * 3 / 4 + 1 - validJson.getBytes(StandardCharsets.UTF_8).length);
      properties.add(Base64.getUrlEncoder().withoutPadding()
          .encodeToString(oversizedJson.getBytes(StandardCharsets.UTF_8)));
      List<Consumer<JSONObject>> invalid = List.of(
          json -> json.put("version", 2),
          json -> json.put("app", "AnotherApp"),
          json -> json.put("futureField", true),
          json -> json.getJSONObject("primary").put("payloadSha256", "bad digest"),
          json -> json.getJSONObject("primary").getJSONObject("analysis").put("modelName", 42),
          json -> json.getJSONObject("primary").getJSONObject("analysis").put("pda", "0.5"),
          json -> json.getJSONObject("primary").getJSONObject("analysis")
              .put("rules", "x".repeat(8193)));
      for (Consumer<JSONObject> mutate : invalid) {
        JSONObject metadata = metadata(PRIMARY, SECONDARY);
        mutate.accept(metadata);
        properties.add(encode(metadata));
      }
      for (String property : properties) {
        BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + legacyTags(true)
            + "KGP[" + property + "])", true);
        BoardData data = history.getData();
        assertEquals(120, data.getPlayouts());
        assertEquals(80, data.getPlayouts2());
        assertNull(data.primaryAnalysisProvenance());
        assertNull(data.secondaryAnalysisProvenance());
        assertEquals(property, data.getProperty("KGP"));
        Lizzie.board.setHistory(history);
        String saved = SGFParser.saveToString(false);
        assertEquals(1, count(saved, "KGP["));
        assertEquals(property, SGFParser.parseSgf(saved, true).getData().getProperty("KGP"));
      }
    }
  }

  @Test
  void savedMetadataUsesTheExactSerializedLegacyPayloadDigest() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + legacyTags(true) + ")", true);
      installSources(history.getData());
      Lizzie.board.setHistory(history);
      String saved = SGFParser.saveToString(false);
      String rawProperty = property(saved, "KGP");
      JSONObject metadata = new JSONObject(new String(Base64.getUrlDecoder().decode(rawProperty),
          StandardCharsets.UTF_8));
      assertEquals("KG-next", metadata.getString("app"));
      assertEquals(1, metadata.getInt("version"));
      assertEquals(digest(property(saved, "LZOP")),
          metadata.getJSONObject("primary").getString("payloadSha256"));
      assertEquals(digest(property(saved, "LZOP2")),
          metadata.getJSONObject("secondary").getString("payloadSha256"));
    }
  }

  private static String legacyTags(boolean root) {
    String tag = root ? "LZOP" : "LZ";
    return tag + "[" + PRIMARY + "]" + tag + "2[" + SECONDARY + "]";
  }

  private static void installSources(BoardData data) {
    data.setPrimaryAnalysisProvenance(ANALYSIS);
    data.setPrimaryOwnershipProvenance(OWNERSHIP);
    data.setSecondaryAnalysisProvenance(OWNERSHIP);
    data.setSecondaryOwnershipProvenance(ANALYSIS);
  }

  private static void assertSources(BoardData data) {
    assertEquals(ANALYSIS, data.primaryAnalysisProvenance());
    assertEquals(OWNERSHIP, data.primaryOwnershipProvenance());
    assertEquals(OWNERSHIP, data.secondaryAnalysisProvenance());
    assertEquals(ANALYSIS, data.secondaryOwnershipProvenance());
  }

  private static List<BoardData> mainLine(BoardHistoryList history) {
    List<BoardData> data = new ArrayList<>();
    BoardHistoryNode node = history.getStart();
    while (node != null) {
      data.add(node.getData());
      node = node.next().orElse(null);
    }
    return data;
  }

  private static JSONObject metadata(String primary, String secondary) throws Exception {
    return new JSONObject().put("app", "KG-next").put("version", 1)
        .put("primary", new JSONObject().put("payloadSha256", digest(primary))
            .put("analysis", json(ANALYSIS)).put("ownership", json(OWNERSHIP)))
        .put("secondary", new JSONObject().put("payloadSha256", digest(secondary))
            .put("analysis", json(OWNERSHIP)).put("ownership", json(ANALYSIS)));
  }

  private static JSONObject json(AnalysisProvenance source) {
    return new JSONObject().put("engineName", source.engineName())
        .put("engineVersion", source.engineVersion()).put("modelName", source.modelName())
        .put("modelSha256", source.modelSha256()).put("configSha256", source.configSha256())
        .put("rules", source.rules()).put("komi", source.komi()).put("pda", source.pda())
        .put("wrn", source.wrn()).put("humanSLProfile", source.humanSLProfile());
  }

  private static String encode(JSONObject value) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String digest(String payload) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(StandardCharsets.UTF_8)));
  }

  private static String property(String sgf, String tag) {
    Matcher matcher = Pattern.compile(tag + "\\[([^]]*)]", Pattern.DOTALL).matcher(sgf);
    assertTrue(matcher.find(), tag);
    return matcher.group(1);
  }

  private static int count(String value, String target) {
    return (value.length() - value.replace(target, "").length()) / target.length();
  }
}
