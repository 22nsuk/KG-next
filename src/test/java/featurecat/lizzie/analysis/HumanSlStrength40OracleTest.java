package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HumanSlStrength40OracleTest {
  private static final String FIXTURE = "/models/strength/humansl40-oracle/predictions.json";

  @Test
  void bundledModelAndFeatureOrderMatchIndependentOracle() throws Exception {
    JSONObject oracle = oracle();
    assertEquals(1, oracle.getInt("schema_version"));
    assertArrayEquals(featureNames(oracle), HumanSlStrength40.featureNames());
    try (InputStream model = HumanSlStrength40.class.getResourceAsStream(HumanSlStrength40.RESOURCE)) {
      assertNotNull(model, "The oracle must verify the actual bundled model");
      assertEquals(
          oracle.getString("model_sha256"),
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(model.readAllBytes())));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("oracleCases")
  void predictionMatchesOfficialXgboost(
      String name, JSONArray values, double expected, double tolerance) {
    double[] features = new double[values.length()];
    for (int i = 0; i < features.length; i++) {
      features[i] = values.isNull(i) ? Double.NaN : values.getDouble(i);
    }
    assertEquals(
        expected,
        XGBoostStrengthModel.predictUncalibrated(
            HumanSlStrength40.RESOURCE, features, HumanSlStrength40.featureNames()),
        tolerance,
        name);
  }

  private static Stream<Arguments> oracleCases() throws Exception {
    JSONObject oracle = oracle();
    JSONArray cases = oracle.getJSONArray("cases");
    double tolerance = oracle.getDouble("absolute_tolerance");
    return IntStream.range(0, cases.length())
        .mapToObj(cases::getJSONObject)
        .map(
            example ->
                Arguments.of(
                    example.getString("id"),
                    example.getJSONArray("features"),
                    example.getDouble("expected_rank"),
                    tolerance));
  }

  private static String[] featureNames(JSONObject oracle) {
    JSONArray names = oracle.getJSONArray("feature_names");
    return IntStream.range(0, names.length()).mapToObj(names::getString).toArray(String[]::new);
  }

  private static JSONObject oracle() throws Exception {
    try (InputStream stream = HumanSlStrength40OracleTest.class.getResourceAsStream(FIXTURE)) {
      assertNotNull(stream, "Independent XGBoost oracle fixture is required");
      return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
