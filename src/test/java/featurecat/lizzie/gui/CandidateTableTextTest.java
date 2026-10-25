package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CandidateTableTextTest {
  @Test
  void playedMoveShowsItsValueThenTheGapToTheBestMove() {
    assertEquals("99.2 ↓0.1", CandidateTableText.valueWithDifference(99.2, -0.1));
    assertEquals("7.3 ↑0.4", CandidateTableText.valueWithDifference(7.3, 0.42));
    assertEquals("7.3", CandidateTableText.valueWithDifference(7.3, -0.04));
    assertEquals("0.0", CandidateTableText.valueWithDifference(-0.01, -0.04));
    assertEquals("", CandidateTableText.valueWithDifference(Double.NaN, 1));
    assertEquals("7.3", CandidateTableText.valueWithDifference(7.3, Double.NaN));
  }

  @Test
  void tablesReadTheGapBackToColourThePlayedMove() {
    assertEquals(
        -12.5,
        CandidateTableText.differenceIn(CandidateTableText.valueWithDifference(80.2, -12.5)),
        1e-9);
    assertEquals(
        0.4,
        CandidateTableText.differenceIn(CandidateTableText.valueWithDifference(7.3, 0.42)),
        1e-9);
    assertEquals(
        0,
        CandidateTableText.differenceIn(CandidateTableText.valueWithDifference(7.3, -0.04)),
        1e-9);
    assertEquals(0, CandidateTableText.differenceIn("Pass"), 1e-9);
    assertEquals(0, CandidateTableText.differenceIn(null), 1e-9);
  }
}
