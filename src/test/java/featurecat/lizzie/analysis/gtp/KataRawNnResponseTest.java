package featurecat.lizzie.analysis.gtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class KataRawNnResponseTest {
  private final List<Double> policy = new ArrayList<>();
  private final List<Double> ownership = new ArrayList<>();

  /** A 2x2 response as KataGo prints it, with every scalar line it emits. */
  private static final List<String> RESPONSE =
      List.of(
          "whiteWin 0.412",
          "whiteLoss 0.588",
          "noResult 0",
          "whiteLead -0.7",
          "whiteScoreSelfplay -0.9",
          "whiteScoreSelfplaySq 12.5",
          "varTimeLeft 0.2",
          "shorttermWinlossError 0.1",
          "shorttermScoreError 2.3",
          "policy",
          "0.25 0.125",
          "NAN 0.5",
          "policyPass 0.125",
          "whiteOwnership",
          "0.5 -0.25",
          "-1 1");

  @Test
  void parsesHeaderAndScalars() {
    assertEquals(OptionalInt.of(4), KataRawNnResponse.headerSymmetry("= symmetry 4"));
    assertEquals(OptionalInt.empty(), KataRawNnResponse.headerSymmetry("= "));
    assertEquals(OptionalInt.empty(), KataRawNnResponse.headerSymmetry("whiteWin 0.4"));
    assertEquals(OptionalDouble.of(0.412), KataRawNnResponse.scalar("whiteWin 0.412", "whiteWin"));
    assertEquals(OptionalDouble.of(-0.7), KataRawNnResponse.scalar("whiteLead -0.7", "whiteLead"));
    assertEquals(OptionalDouble.empty(), KataRawNnResponse.scalar("whiteLead x", "whiteLead"));
    assertEquals(OptionalDouble.empty(), KataRawNnResponse.scalar("policy", "whiteWin"));
  }

  @Test
  void collectsPolicyInPermilleAndOwnershipForBlackToPlay() {
    KataRawNnResponse response = new KataRawNnResponse(2, 2);
    int completions = 0;
    for (String line : RESPONSE) {
      completions += response.accept(line, () -> true, policy, ownership) ? 1 : 0;
    }

    assertEquals(List.of(250.0, 125.0, 0.0, 500.0), policy);
    assertEquals(List.of(-0.5, 0.25, 1.0, -1.0), ownership);
    assertEquals(1, completions);
    assertFalse(response.accept("whiteOwnership", () -> true, policy, ownership));
    assertFalse(
        response.accept("0.1 0.1", () -> true, policy, ownership), "completion is reported once");
    assertEquals(4, ownership.size(), "rows after completion are ignored");
  }

  @Test
  void ownershipSignFollowsTheSideToPlayPerRow() {
    KataRawNnResponse response = new KataRawNnResponse(2, 2);
    AtomicBoolean blackToPlay = new AtomicBoolean(false);
    response.accept("whiteOwnership", blackToPlay::get, policy, ownership);
    response.accept("0.5 0.5", blackToPlay::get, policy, ownership);
    blackToPlay.set(true);
    assertTrue(response.accept("0.5 0.5", blackToPlay::get, policy, ownership));
    assertEquals(List.of(0.5, 0.5, -0.5, -0.5), ownership);
  }

  @Test
  void ignoresRowsOfTheWrongWidthAndLinesOutsideTheBlocks() {
    KataRawNnResponse response = new KataRawNnResponse(3, 1);
    response.accept("0.1 0.2 0.3", () -> true, policy, ownership);
    response.accept("policy", () -> true, policy, ownership);
    response.accept("0.1 0.2", () -> true, policy, ownership);
    response.accept("0.1 0.2 0.3 0.4", () -> true, policy, ownership);
    response.accept("0.1 0.2 0.3", () -> true, policy, ownership);
    response.accept("0.4 0.5 0.6", () -> true, policy, ownership);

    assertEquals(List.of(100.0, 200.0, 300.0), policy);
    assertTrue(ownership.isEmpty());
  }

  @Test
  void toleratesWhitespaceButRejectsMalformedScalarValuesAndPrefixMatches() {
    assertEquals(OptionalInt.of(3), KataRawNnResponse.headerSymmetry("=  symmetry\t3"));
    assertEquals(OptionalInt.empty(), KataRawNnResponse.headerSymmetry("= symmetry invalid"));
    assertEquals(OptionalInt.empty(), KataRawNnResponse.headerSymmetry("= symmetryExtra 1"));
    assertEquals(OptionalDouble.of(0.25), KataRawNnResponse.scalar("whiteWin\t 0.25", "whiteWin"));
    for (String line :
        List.of(
            "whiteWin",
            "whiteWinExtra 0.1",
            "whiteWin NaN",
            "whiteWin Infinity",
            "whiteWin 0.1 extra")) {
      assertEquals(OptionalDouble.empty(), KataRawNnResponse.scalar(line, "whiteWin"), line);
    }
  }

  @Test
  void nonFiniteAndMalformedCellsUseTheExistingZeroFallback() {
    KataRawNnResponse response = new KataRawNnResponse(3, 1);
    response.accept("policy", () -> false, policy, ownership);
    response.accept("NaN Infinity invalid", () -> false, policy, ownership);
    response.accept("whiteOwnership", () -> false, policy, ownership);
    assertTrue(response.accept("NaN -Infinity NAN", () -> false, policy, ownership));
    assertEquals(List.of(0.0, 0.0, 0.0), policy);
    assertEquals(List.of(0.0, 0.0, 0.0), ownership);
  }

  @Test
  void sectionMarkersAreNeverCellsOnOneWideBoards() {
    KataRawNnResponse response = new KataRawNnResponse(1, 1);
    response.accept("policy", () -> false, policy, ownership);
    response.accept("0.25", () -> false, policy, ownership);
    assertFalse(response.accept("whiteOwnership", () -> false, policy, ownership));
    assertTrue(response.accept("0.5", () -> false, policy, ownership));
    assertEquals(List.of(250.0), policy);
    assertEquals(List.of(0.5), ownership);
  }
}
