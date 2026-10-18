package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TeacherVerifierTest {
  @ParameterizedTest
  @ValueSource(strings = {"D40", "I9", "D0", "D-1", "D+4", "D04", "D4.5"})
  void rejectsMalformedCoordinateTokensRatherThanIgnoringThem(String coordinate) {
    TeacherEvidence.Position position = position("D4", "Q16", List.of("Q16", "D16"));

    TeacherVerifier.Result result = TeacherVerifier.verify("Play " + coordinate + ".", position);

    assertFalse(result.ok());
    assertTrue(result.violations.stream().anyMatch(message -> message.contains(coordinate)));
  }

  @Test
  void acceptsCoordinatesFromEveryPositionInRangeEvidence() {
    TeacherEvidence.Position first = position("D4", "Q16", List.of("Q16", "D16"));
    TeacherEvidence.Position second = position("C3", "R17", List.of("R17", "R16"));

    TeacherVerifier.Result result =
        TeacherVerifier.verify("D4之后可比较Q16，另一处C3可考虑R17。", List.of(first, second));

    assertTrue(result.violations.isEmpty());
  }

  @Test
  void acceptsPlayedContinuationButRejectsUnsupportedCoordinates() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(
            12,
            "B",
            1000,
            "D4",
            OptionalDouble.empty(),
            List.of(new TeacherEvidence.Candidate(1, "Q16", 55, 1.2, 800, List.of("Q16"))),
            List.of("C3"));

    assertTrue(TeacherVerifier.verify("实战随后走C3。", position).violations.isEmpty());
    assertFalse(TeacherVerifier.verify("还可以走T19。", position).violations.isEmpty());
  }

  @Test
  void missingPositionEvidenceDoesNotTurnEveryCoordinateIntoAFailure() {
    TeacherVerifier.Result result = TeacherVerifier.verify("棋盘示例坐标D4，胜率101%。", List.of());

    assertTrue(
        result.violations.stream()
            .noneMatch(message -> message.contains("Unsupported coordinate")));
    assertTrue(result.violations.stream().anyMatch(message -> message.contains("101.0%")));
  }

  @Test
  void numericWarningsKeepWinrateAndPointEvidenceSeparate() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(
            12,
            "B",
            1000,
            "D4",
            OptionalDouble.of(40),
            List.of(
                new TeacherEvidence.Candidate(1, "Q16", 60, 3, 800, List.of("Q16")),
                new TeacherEvidence.Candidate(4, "D4", 20, 2.5, 200, List.of("D4"))));

    TeacherVerifier.Result valid =
        TeacherVerifier.verify("Winrate 60%; winrate loss 40%. 집 손실 0.5집.", position);
    assertTrue(valid.ok());
    assertTrue(valid.warnings.isEmpty());

    TeacherVerifier.Result swapped =
        TeacherVerifier.verify("Winrate 3%. Score loss 40 points.", position);
    assertTrue(swapped.ok(), "Ambiguous natural-language number claims remain warnings.");
    assertTrue(swapped.warnings.stream().anyMatch(warning -> warning.contains("Percentage 3.0%")));
    assertTrue(swapped.warnings.stream().anyMatch(warning -> warning.contains("value 40.0")));
  }

  @Test
  void scoreWarningsAcceptRoundedNegativeLeadsAndPositiveBehindMagnitudes() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(
            12,
            "W",
            1000,
            "",
            OptionalDouble.empty(),
            List.of(new TeacherEvidence.Candidate(1, "Q16", 42.4, -3.2, 800, List.of("Q16"))));

    TeacherVerifier.Result result =
        TeacherVerifier.verify("Winrate 42%. ScoreLead -3.2; behind by 3 points.", position);

    assertTrue(result.ok());
    assertTrue(result.warnings.isEmpty());
  }

  @Test
  void numericWarningsUseAllFrozenRangePositionsWithoutInventingZeroForMissingLoss() {
    TeacherEvidence.Position first = position("D4", "Q16", List.of("Q16"));
    TeacherEvidence.Position second =
        new TeacherEvidence.Position(
            25,
            "W",
            1000,
            "C3",
            OptionalDouble.empty(),
            List.of(new TeacherEvidence.Candidate(1, "R17", 25, 8, 800, List.of("R17"))));

    assertTrue(TeacherVerifier.verify("Winrates 55% and 25%; score 8.", List.of(first, second))
        .warnings.isEmpty());
    assertTrue(TeacherVerifier.verify("Score loss 0 points.", List.of(first, second))
        .warnings.stream().anyMatch(warning -> warning.contains("value 0.0")));
  }

  @Test
  void absoluteWordingIsStillReportedAfterLineBreaks() {
    TeacherVerifier.Result result =
        TeacherVerifier.verify(
            "The reference starts at Q16.\n\nThis is the only move.",
            position("D4", "Q16", List.of("Q16")));

    assertTrue(result.ok());
    assertFalse(result.warnings.isEmpty());
  }

  private static TeacherEvidence.Position position(
      String actualMove, String candidate, List<String> variation) {
    return new TeacherEvidence.Position(
        10,
        "B",
        1000,
        actualMove,
        OptionalDouble.empty(),
        List.of(new TeacherEvidence.Candidate(1, candidate, 55, 1.2, 800, variation)));
  }
}
