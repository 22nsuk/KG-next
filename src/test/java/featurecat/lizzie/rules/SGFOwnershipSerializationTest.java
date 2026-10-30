package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.analysis.MoveData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SGFOwnershipSerializationTest {
  private static final AnalysisProvenance ANALYSIS = new AnalysisProvenance(
      "분석 엔진", "1.0", "모델] 경로\\net.bin.gz", "a".repeat(64), "b".repeat(64),
      "chinese", 7.5, 0.0, null, "rank_8d");
  private static final AnalysisProvenance OWNER = new AnalysisProvenance(
      "Ownership", "2.0", "다른 모델", "", "", "japanese", 6.5, null, null, "");

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void ownershipValuesKeepLegacyDoubleTextThroughSave(String tag) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = historyFor(tag);
      List<Double> values = new ArrayList<>(List.of(
          -0.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
          Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, 1.0e-7, -1.0e20));
      Random random = new Random(0x4b47534f574eL);
      while (values.size() < 138) {
        double value = Double.longBitsToDouble(random.nextLong());
        if (Double.isFinite(value)) values.add(value);
      }
      BoardData data = target(history, tag);
      install(data, tag, "KataGo", values, true);
      Lizzie.board.setHistory(history);

      String saved = SGFParser.saveToString(false);
      assertEquals(payload(tag, "KataGo", values, true), property(saved, tag));
      assertEquals(values, ownership(target(SGFParser.parseSgf(saved, true), tag), tag));

      // Legacy save uses Object text for null; import intentionally stops at this invalid token.
      values.add(3, null);
      setOwnership(data, tag, values);
      assertEquals(payload(tag, "KataGo", values, true),
          property(SGFParser.saveToString(false), tag));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void escapedPayloadKeepsCanonicalDigestBeforeSgfEscaping(String tag) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = historyFor(tag);
      String engineName = "엔진]경로\\[v2," + "長".repeat(2048) + "😀";
      List<Double> values = List.of(-0.0, 0.25, -0.5, 1.0e-7);
      BoardData data = target(history, tag);
      install(data, tag, engineName, values, true);
      installProvenance(data, tag);
      String expected = payload(tag, engineName, values, true);
      Lizzie.board.setHistory(history);
      String saved = SGFParser.saveToString(false);
      assertEquals(expected, property(saved, tag));
      assertTrue(saved.contains(tag + "[" + escape(expected) + "]"));
      String digest = metadataDigest(saved, tag);
      assertEquals(digest(expected), digest, "KGP must describe the decoded legacy payload");
      assertNotEquals(digest(escape(expected)), digest, "SGF escaping is outside the digest");
      // Existing analysis import retains SGF escapes in engine names; this checks the save boundary.
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void longUnicodePayloadAndProvenanceRoundTrip(String tag) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = historyFor(tag);
      String engineName = "엔진v2," + "長".repeat(2048) + "😀";
      List<Double> values = List.of(-0.0, 0.25, -0.5, 1.0e-7);
      BoardData data = target(history, tag);
      install(data, tag, engineName, values, true);
      installProvenance(data, tag);
      String expected = payload(tag, engineName, values, true);
      for (int round = 0; round < 2; round++) {
        Lizzie.board.setHistory(history);
        String saved = SGFParser.saveToString(false);
        assertEquals(expected, property(saved, tag));
        assertEquals(digest(expected), metadataDigest(saved, tag));

        history = SGFParser.parseSgf(saved, true);
        data = target(history, tag);
        assertEquals(engineName, secondary(tag) ? data.engineName2 : data.engineName);
        assertEquals(values, ownership(data, tag));
        assertEquals(ANALYSIS,
            secondary(tag) ? data.secondaryAnalysisProvenance() : data.primaryAnalysisProvenance());
        assertEquals(OWNER,
            secondary(tag) ? data.secondaryOwnershipProvenance() : data.primaryOwnershipProvenance());
      }
    }
  }

  @Test
  void ownershipBeyondInitialCapacityReservationSavesWithoutTruncation() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      String tag = "LZOP";
      BoardHistoryList history = historyFor(tag);
      List<Double> values = new ArrayList<>(8193);
      for (int i = 0; i < 8193; i++) {
        values.add(i % 2 == 0 ? 0.12345678901234566 : -0.9876543210987654);
      }
      values.set(values.size() - 1, -0.0);
      install(target(history, tag), tag, "KataGo", values, true);
      Lizzie.board.setHistory(history);
      String expected = payload(tag, "KataGo", values, true);
      assertTrue(expected.length() > 64 * 1024, "payload must exceed speculative reservation");

      String saved = SGFParser.saveToString(false);
      assertEquals(expected, property(saved, tag));
      assertEquals(values, ownership(target(SGFParser.parseSgf(saved, true), tag), tag));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void emptyCandidatesKeepHeaderOnlyAndOwnershipOnlySpacing(String tag) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = historyFor(tag);
      BoardData data = target(history, tag);
      install(data, tag, "KataGo", null, false);
      Lizzie.board.setHistory(history);

      for (int shape = 0; shape < 3; shape++) {
        List<Double> values = shape == 0 ? null : shape == 1 ? List.of() : List.of(-0.0, 0.5);
        setOwnership(data, tag, values);
        String savedPayload = property(SGFParser.saveToString(false), tag);
        assertEquals(payload(tag, "KataGo", values, false), savedPayload);
        if (shape < 2) assertFalse(savedPayload.contains("\n"), "no empty detail line");
        else assertTrue(savedPayload.contains("\n ownership -0.0 0.5"));
      }
    }
  }

  private static BoardHistoryList historyFor(String tag) {
    return SGFParser.parseSgf(tag.startsWith("LZOP") ? "(;SZ[5])" : "(;SZ[5];B[aa])", true);
  }

  private static BoardData target(BoardHistoryList history, String tag) {
    return (tag.startsWith("LZOP") ? history.getStart() : history.getEnd()).getData();
  }

  private static boolean secondary(String tag) {
    return tag.endsWith("2");
  }

  private static void install(
      BoardData data, String tag, String engineName, List<Double> values, boolean candidates) {
    MoveData move = new MoveData();
    move.coordinate = "C3";
    move.playouts = 100;
    move.winrate = 55;
    move.policy = 12.5;
    move.scoreMean = 2.5;
    move.variation = List.of("C3", "D4");
    List<MoveData> moves = candidates ? List.of(move) : List.of();
    if (secondary(tag)) {
      data.engineName2 = engineName;
      data.winrate2 = 55;
      data.setPlayouts2(100);
      data.rootVisits2 = 100;
      data.isKataData2 = true;
      data.scoreMean2 = 2.5;
      data.scoreStdev2 = 1.25;
      data.pda2 = 0;
      data.bestMoves2 = moves;
    } else {
      data.engineName = engineName;
      data.winrate = 55;
      data.setPlayouts(100);
      data.rootVisits = 100;
      data.isKataData = true;
      data.scoreMean = 2.5;
      data.scoreStdev = 1.25;
      data.pda = 0;
      data.bestMoves = moves;
    }
    setOwnership(data, tag, values);
  }

  private static void setOwnership(BoardData data, String tag, List<Double> values) {
    if (secondary(tag)) data.estimateArray2 = values;
    else data.estimateArray = values;
  }

  private static List<Double> ownership(BoardData data, String tag) {
    return secondary(tag) ? data.estimateArray2 : data.estimateArray;
  }

  private static void installProvenance(BoardData data, String tag) {
    if (secondary(tag)) {
      data.setSecondaryAnalysisProvenance(ANALYSIS);
      data.setSecondaryOwnershipProvenance(OWNER);
    } else {
      data.setPrimaryAnalysisProvenance(ANALYSIS);
      data.setPrimaryOwnershipProvenance(OWNER);
    }
  }

  private static String payload(
      String tag, String engineName, List<Double> values, boolean candidates) {
    String header = engineName + " 45.0 100 2.5 1.25 0.0 rootVisits=100";
    String detail = candidates
        ? "move C3 visits 100 winrate 5500 prior 1250 scoreMean "
            + (secondary(tag) ? "2.5" : "2.50") + " order 0 pv C3 D4"
            + (secondary(tag) ? " info " : "")
        : "";
    if (values != null && !values.isEmpty()) {
      StringJoiner numbers = new StringJoiner(" ");
      for (Double value : values) numbers.add(value == null ? "null" : Double.toString(value));
      detail += " ownership " + numbers;
    }
    return detail.isEmpty() ? header : header + "\n" + detail;
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("]", "\\]");
  }

  private static String digest(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static String metadataDigest(String saved, String tag) {
    JSONObject metadata = new JSONObject(new String(
        Base64.getUrlDecoder().decode(property(saved, "KGP")), StandardCharsets.UTF_8));
    assertEquals(1, metadata.getInt("version"));
    return metadata.getJSONObject(secondary(tag) ? "secondary" : "primary")
        .getString("payloadSha256");
  }

  private static String property(String sgf, String tag) {
    Matcher matcher = Pattern.compile("(?<![A-Za-z])" + tag + "\\[").matcher(sgf);
    assertTrue(matcher.find(), "missing " + tag);
    StringBuilder value = new StringBuilder();
    for (int i = matcher.end(); i < sgf.length(); i++) {
      char ch = sgf.charAt(i);
      if (ch == ']') return value.toString();
      if (ch == '\\') {
        assertTrue(++i < sgf.length(), "incomplete escape in " + tag);
        ch = sgf.charAt(i);
      }
      value.append(ch);
    }
    throw new AssertionError("unterminated " + tag);
  }
}
