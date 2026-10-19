package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HumanSlStrength40Test {
  @TempDir Path cache;

  static JSONObject game() {
    JSONArray moves = new JSONArray();
    for (int i = 0; i < 12; i++) moves.put(new JSONArray().put(i % 2 == 0 ? "B" : "W").put("pass"));
    return new JSONObject()
        .put("moves", moves)
        .put("rules", "chinese")
        .put("komi", 7.5)
        .put("boardXSize", 19)
        .put("boardYSize", 19);
  }

  static JSONObject response(JSONObject query) {
    JSONArray policy = new JSONArray();
    for (int i = 0; i < 361; i++) policy.put(0.);
    policy.put(1.);
    return new JSONObject()
        .put("id", query.getString("id"))
        .put("isDuringSearch", false)
        .put("rootInfo", new JSONObject().put("scoreLead", 0).put("winrate", .5))
        .put(
            "moveInfos",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("move", "pass")
                        .put("order", 0)
                        .put("scoreMean", 0)
                        .put("winrate", .5)
                        .put("prior", 1)))
        .put("humanPolicy", policy);
  }

  @Test
  void everyPolicyProfileUsesOneVisitAndRetainsHistory() {
    JSONObject original = game();
    String before = original.toString();
    for (String profile : HumanSlStrength40.PROFILES) {
      JSONObject query = HumanSlStrengthReuseSession.request(original, 7, profile);
      assertEquals(7, query.getJSONArray("moves").length());
      assertEquals(1, query.getInt("maxVisits"));
      assertTrue(query.getBoolean("includePolicy"));
      assertFalse(query.getBoolean("includeOwnership"));
      assertFalse(query.has("analyzeTurns"));
      JSONObject settings = query.getJSONObject("overrideSettings");
      assertEquals(profile, settings.getString("humanSLProfile"));
      assertEquals("SIDETOMOVE", settings.getString("reportAnalysisWinratesAs"));
      assertFalse(settings.getBoolean("ignorePreRootHistory"));
      assertEquals(2, settings.getInt("rootNumSymmetriesToSample"));
      assertEquals(0, settings.getDouble("humanSLRootExploreProbWeightless"));
    }
    assertEquals(before, original.toString());
    assertThrows(
        IllegalArgumentException.class,
        () -> HumanSlStrengthReuseSession.request(original, 12, "rank_5d"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HumanSlStrengthReuseSession.request(original, 0, "rank_20k"));
  }

  @Test
  void exactFirstChoiceAndZeroLossProduceFortyFeaturesForBothSides() throws Exception {
    JSONObject game = game();
    JSONArray base = HumanSlStrengthReuseSessionTest.base(13);
    JSONObject policies =
        HumanSlStrengthReuseSession.policies(
            game, base, HumanSlStrength40Test::response, cache, "synthetic", () -> false, n -> {});
    List<HumanSlStrength40.Side> sides =
        HumanSlStrength40.evaluate(game.getJSONArray("moves"), base, policies, 19);
    assertEquals(2, sides.size());
    for (HumanSlStrength40.Side side : sides) {
      assertEquals(6, side.samples());
      double[] features = side.features();
      assertEquals(40, features.length);
      assertEquals(1, features[0]);
      assertEquals(0.5, features[3]);
      assertEquals(1, features[7]);
      assertEquals(0, features[20]);
      assertEquals(0, features[37]);
      assertEquals(1, features[38]);
      assertTrue(side.rank() >= 0 && side.rank() <= 12);
      features[0] = -99;
      assertEquals(1, side.features()[0]);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> HumanSlStrength40.evaluate(game.getJSONArray("moves"), base, policies, 13));
  }

  @Test
  void unsupportedSetupAndNonalternatingHistoriesAreRejectedBeforeCompute() {
    JSONObject setup =
        game().put("initialStones", new JSONArray().put(new JSONArray().put("B").put("D4")));
    JSONObject nonalternating = game();
    nonalternating.getJSONArray("moves").getJSONArray(1).put(0, "B");
    for (JSONObject invalid : List.of(setup, nonalternating)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              HumanSlStrengthReuseSession.policies(
                  invalid,
                  HumanSlStrengthReuseSessionTest.base(13),
                  q -> {
                    fail("Unsupported history must not be searched");
                    return null;
                  },
                  cache,
                  "synthetic",
                  () -> false,
                  n -> {}));
    }
  }

  @Test
  void insufficientCoverageIsNaNNotZero() {
    JSONArray policy = new JSONArray();
    for (int i = 0; i < 362; i++) policy.put(.001);
    List<JSONObject> candidates = List.of(new JSONObject().put("move", "pass").put("scoreMean", 0));
    double[] values = HumanSlStrength40.human(candidates, policy, "pass", 19);
    assertTrue(Double.isNaN(values[1]));
    assertEquals(.001, values[2]);
    assertEquals(0, values[3]);
    policy.put(361, .9);
    assertEquals(.05, HumanSlStrength40.human(candidates, policy, "pass", 19)[1], 1e-12);
  }
}
