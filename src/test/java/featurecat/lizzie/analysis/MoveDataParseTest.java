package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the KataGo info-line parsing and the memory shape of its results: variation/pvVisits must
 * be compact ArrayLists, not subList views that pin the whole tokenized line (hundreds of tokens
 * with ownership data) for as long as the MoveData lives in the game tree.
 */
class MoveDataParseTest {
  private static Config previousConfig;

  @BeforeAll
  static void installMinimalConfig() throws Exception {
    previousConfig = Lizzie.config;
    Lizzie.config = allocate(Config.class); // limitBranchLength defaults to 0 (unlimited)
  }

  @AfterAll
  static void restoreConfig() {
    Lizzie.config = previousConfig;
  }

  @Test
  void parsesKataGoInfoLine() {
    MoveData move =
        MoveData.fromInfoKatago(
            "move Q16 visits 100 winrate 0.5123 scoreMean 1.5 prior 0.08 order 0"
                + " pv Q16 D4 Q4 pvVisits 60 25 15");
    assertEquals("Q16", move.coordinate);
    assertEquals(100, move.playouts);
    assertEquals(51.23, move.winrate, 1e-9);
    assertEquals(1.5, move.scoreMean, 1e-9);
    assertEquals(0, move.order);
    assertEquals(List.of("Q16", "D4", "Q4"), move.variation);
    assertEquals(List.of("60", "25", "15"), move.pvVisits);
    assertNull(move.movesEstimateArray);
    assertInstanceOf(ArrayList.class, move.variation, "variation must not be a subList view");
    assertInstanceOf(ArrayList.class, move.pvVisits, "pvVisits must not be a subList view");
  }

  @Test
  void distinguishesSaiAndSayuriScorePerspectives() {
    MoveData sai =
        MoveData.fromInfoSai("move D4 visits 1 winrate 5000 prior 1000 order 0 pv D4", false);
    MoveData sayuri =
        MoveData.fromInfoSai(
            "move D4 visits 1 winrate 5000 scoreLead 4.0 prior 1000 order 0 pv D4", true);

    assertTrue(sai.scoreMeanIsBlackPerspective);
    assertFalse(sayuri.scoreMeanIsBlackPerspective);
    assertEquals(4.0, sayuri.scoreMean, 1e-9);
  }

  @Test
  void movesOwnershipStopsTheVariation() {
    MoveData move =
        MoveData.fromInfoKatago(
            "move D4 visits 10 winrate 0.6 pv D4 Q16 pvVisits 6 4 movesOwnership 0.5 -0.5");
    assertEquals(List.of("D4", "Q16"), move.variation);
    assertEquals(List.of("6", "4"), move.pvVisits);
    assertEquals(List.of(0.5, -0.5), move.movesEstimateArray);
    assertInstanceOf(ArrayList.class, move.variation, "variation must not be a subList view");
    assertThrows(
        UnsupportedOperationException.class,
        () -> move.movesEstimateArray.set(0, 0.0),
        "ownership retained in a move should be immutable primitive storage");
  }

  @Test
  void parsesWhitespaceAndScientificOwnershipWithoutRetainingAllTokens() {
    MoveData move =
        MoveData.fromInfoKatago(
            "  move  D4  visits  12  winrate  0.625  scoreLead -2.5  pv D4 Q16"
                + " pvVisits 7 5 movesOwnership -1e-1 0.25 1E-2  ");

    assertEquals("D4", move.coordinate);
    assertEquals(12, move.playouts);
    assertEquals(62.5, move.winrate, 1e-9);
    assertEquals(-2.5, move.scoreMean, 1e-9);
    assertEquals(List.of("D4", "Q16"), move.variation);
    assertEquals(List.of("7", "5"), move.pvVisits);
    assertEquals(-0.1, move.movesEstimateArray.get(0), 1e-12);
    assertEquals(0.25, move.movesEstimateArray.get(1), 1e-12);
    assertEquals(0.01, move.movesEstimateArray.get(2), 1e-12);
  }

  @Test
  void acceptsRepresentativeOwnershipNumbersAndStopsAtMalformedExponent() {
    MoveData valid =
        MoveData.fromInfoKatago(
            "move D4 visits 12 pv D4 movesOwnership -1 +0.125 .5 5. 1e3 -2.5E-2");

    assertEquals(List.of(-1.0, 0.125, 0.5, 5.0, 1000.0, -0.025), valid.movesEstimateArray);

    MoveData malformed =
        MoveData.fromInfoKatago("move D4 visits 12 pv D4 movesOwnership 0.5 1e+ 0.25");
    assertEquals(
        List.of(0.5),
        malformed.movesEstimateArray,
        "malformed ownership must stop parsing instead of being silently accepted");
  }

  @Test
  void parsesKataGoNumericFieldsWithoutTemporaryTokenStrings() {
    MoveData move =
        MoveData.fromInfoKatago(
            "move Q16 visits 2147483647 order +2 winrate 5.123e-1 prior +8e-2"
                + " lcb 4.5e-1 scoreMean -2.5E+0 scoreStdev .25 pv Q16");

    assertEquals("Q16", move.coordinate);
    assertEquals(Integer.MAX_VALUE, move.playouts);
    assertEquals(2, move.order);
    assertEquals(51.23, move.winrate, 1e-9);
    assertEquals(8.0, move.policy, 1e-9);
    assertEquals(45.0, move.lcb, 1e-9);
    assertEquals(-2.5, move.scoreMean, 1e-9);
    assertEquals(0.25, move.scoreStdev, 1e-9);
  }

  @Test
  void numericFastPathPreservesOverflowAndExtremeExponentSemantics() {
    assertThrows(
        NumberFormatException.class,
        () ->
            MoveData.fromInfoKatago(
                "move Q16 visits 999999999999999999999999999999999999 pv Q16"));

    MoveData positive =
        MoveData.fromInfoKatago(
            "move Q16 visits 1 scoreMean 1e999999999999999999999999999999 pv Q16");
    MoveData negative =
        MoveData.fromInfoKatago(
            "move Q16 visits 1 scoreMean 1e-999999999999999999999999999999 pv Q16");
    assertEquals(Double.POSITIVE_INFINITY, positive.scoreMean);
    assertEquals(0.0, negative.scoreMean);
  }

  @Test
  void branchLimitKeepsVariationAndPvVisitsCompact() {
    Lizzie.config.limitBranchLength = 2;
    try {
      MoveData move =
          MoveData.fromInfoKatago(
              "move Q16 visits 100 winrate 0.5123 pv Q16 D4 Q4 C3 pvVisits 40 30 20 10");

      assertEquals(List.of("Q16", "D4"), move.variation);
      assertEquals(List.of("40", "30"), move.pvVisits);
      assertInstanceOf(ArrayList.class, move.variation, "variation must be a compact ArrayList");
      assertInstanceOf(ArrayList.class, move.pvVisits, "pvVisits must be a compact ArrayList");
    } finally {
      Lizzie.config.limitBranchLength = 0;
    }
  }

  @Test
  void missingEdgePreservesLegacySymmetricCandidatePercentages() {
    KataGoAnalysisPayload payload = KataGoAnalysisPayload.parse(
        "info move D4 visits 10 pv D4 info move F6 visits 10 isSymmetryOf D4 pv F6");
    assertEquals(10, payload.totalVisits());
    assertEquals(0.5,
        payload.moves.get(0).allocationRatio(MoveData.getAllocationVisits(payload.moves)));
  }

  @Test
  void upstreamRootCountIsIndependentOfCandidateResearch() throws Exception {
    String line;
    try (var input = getClass().getResourceAsStream("/analysis/katago-root-focus-info.txt")) {
      line = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    KataGoAnalysisPayload payload = KataGoAnalysisPayload.parse(line);
    assertEquals(100, payload.rootVisits);
    assertEquals(100, payload.totalVisits());
    assertEquals(848, MoveData.getPlayouts(payload.moves));
    MoveData c3 = payload.moves.stream().filter(move -> move.coordinate.equals("C3")).findFirst().orElseThrow();
    assertEquals(368, c3.playouts);
    assertEquals(0, c3.edgeVisits);
    assertEquals(10, c3.order);
    assertFalse(c3.variation.contains("rootInfo"));
    assertEquals(0.0, c3.allocationRatio(MoveData.getAllocationVisits(payload.moves)));
    assertEquals(0.0, c3.allocationRatio(0));
    KataGoAnalysisPayload legacy = KataGoAnalysisPayload.parse("info move D4 visits 40 pv D4");
    assertEquals(-1, legacy.rootVisits);
    assertEquals(40, legacy.totalVisits());
    assertEquals(1.0, legacy.moves.get(0).allocationRatio(40));
  }

  @Test
  void frameSeparatesOwnershipAndPvArrays() {
    KataGoAnalysisPayload payload = KataGoAnalysisPayload.parse(
        "info move C3 visits 8 pv C3 D4 pvVisits 5 3 pvEdgeVisits 2 1"
            + " movesOwnership 0.5 -0.5 rootInfo visits 4 winrate 0.6"
            + " ownership 0.25 -0.25 ownershipStdev 0.1 0.2");
    assertEquals(4, payload.totalVisits());
    assertEquals(List.of(0.25, -0.25), payload.ownership);
    assertEquals(List.of(0.5, -0.5), payload.moves.get(0).movesEstimateArray);
    assertEquals(List.of("2", "1"), payload.moves.get(0).pvEdgeVisits);
  }

  @Test
  void rootOwnershipPreservesLargeArraysAndFollowingFrameFields() {
    StringBuilder line = new StringBuilder("info move C3 visits 8 pv C3 ownership");
    List<Double> expected = new ArrayList<>();
    for (int index = 0; index < 361; index++) {
      double value = (index % 9 - 4) / 8.0;
      expected.add(value);
      line.append(' ').append(value);
    }
    line.append(" ownershipStdev ignored-token info move D4 visits 4 pv D4"
        + " rootInfo visits 12 winrate 0.6");

    KataGoAnalysisPayload payload = KataGoAnalysisPayload.parse(line.toString());

    assertEquals(expected.size(), payload.ownership.size());
    for (int index = 0; index < expected.size(); index++) {
      assertEquals(expected.get(index), payload.ownership.get(index), 1e-12);
    }
    assertEquals(2, payload.moves.size());
    assertEquals("D4", payload.moves.get(1).coordinate);
    assertEquals(12, payload.totalVisits());
    assertEquals(60.0, payload.rootWinrate, 1e-9);
    assertThrows(UnsupportedOperationException.class, () -> payload.ownership.set(0, 0.0));
    List<Double> retained = List.copyOf(payload.ownership);

    KataGoAnalysisPayload next = KataGoAnalysisPayload.parse("ownership 0.75 -0.75");
    assertEquals(2, next.ownership.size());
    assertEquals(0.75, next.ownership.get(0), 1e-12);
    assertEquals(-0.75, next.ownership.get(1), 1e-12);
    assertEquals(retained, payload.ownership, "later frames must not change a retained snapshot");
  }

  @Test
  void rootOwnershipKeepsLastBlockAndDistinguishesAbsentFromEmpty() {
    assertNull(KataGoAnalysisPayload.parse("ownershipStdev ignored-token").ownership);
    assertEquals(List.of(), KataGoAnalysisPayload.parse("ownership").ownership);
    assertEquals(List.of(),
        KataGoAnalysisPayload.parse("ownership 0.25 -0.25 ownership ownershipStdev 0.1").ownership);

    KataGoAnalysisPayload payload = KataGoAnalysisPayload.parse(
        "ownership 0.25 -0.25 ownershipStdev ignored-token ownership -0.0 -1e-1 1E-2 NaN");
    assertEquals(4, payload.ownership.size());
    assertEquals(Double.doubleToRawLongBits(-0.0),
        Double.doubleToRawLongBits(payload.ownership.get(0)));
    assertEquals(-0.1, payload.ownership.get(1), 1e-12);
    assertEquals(0.01, payload.ownership.get(2), 1e-12);
    assertTrue(Double.isNaN(payload.ownership.get(3)));
  }

  @Test
  void malformedRootOwnershipRejectsTheFrame() {
    assertThrows(NumberFormatException.class, () -> KataGoAnalysisPayload.parse(
        "info move C3 visits 8 pv C3 rootInfo visits 8 ownership 0.5 1e+ -0.5"));
  }

  @Test
  void preservesZeroEdgeAndSeparatePvStatistics() {
    MoveData move =
        MoveData.fromInfoKatago(
            "move C3 visits 368 edgeVisits 0 order 10 pv C3 D4"
                + " pvVisits 300 20 pvEdgeVisits 0 10 movesOwnership 0.5 -0.5"
                + " rootInfo visits 100 ownership 0.25 -0.25");
    assertEquals(368, move.playouts);
    assertEquals(0, move.edgeVisits);
    assertEquals(10, move.order);
    assertEquals(List.of("C3", "D4"), move.variation);
    assertEquals(List.of("300", "20"), move.pvVisits);
    assertEquals(List.of("0", "10"), move.pvEdgeVisits);
    assertEquals(List.of(0.5, -0.5), move.movesEstimateArray);
    assertEquals(-1, MoveData.fromInfoKatago("move D4 visits 1 pv D4").edgeVisits);
  }

  @Test
  void rootAndOwnershipNeverBecomeCandidateVariation() {
    MoveData move =
        MoveData.fromInfoKatago(
            "move C3 visits 368 order 10 pv C3 D4"
                + " rootInfo visits 100 winrate 0.6 ownership 0.25 -0.25");
    assertEquals(List.of("C3", "D4"), move.variation);
  }

  @Test
  void fromInfoParsesLeelaZeroLine() {
    MoveData move =
        MoveData.fromInfo("move Q16 visits 50 winrate 5123 prior 800 order 0 pv Q16 D4");
    assertEquals("Q16", move.coordinate);
    assertEquals(50, move.playouts);
    assertEquals(List.of("Q16", "D4"), move.variation);
    assertInstanceOf(ArrayList.class, move.variation, "variation must not be a subList view");
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    f.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
    return type.cast(unsafe.allocateInstance(type));
  }
}
