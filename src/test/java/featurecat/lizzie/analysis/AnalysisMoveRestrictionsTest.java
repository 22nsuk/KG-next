package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisMoveRestrictionsTest {
  @ParameterizedTest
  @ValueSource(strings = {
      "KATA-ANALYZE", "LZ-ANALYZE", "ANALYZE",
      "KATA-GENMOVE_ANALYZE", "LZ-GENMOVE_ANALYZE", "GENMOVE_ANALYZE"
  })
  void recordsEveryWrittenClauseWithoutAssumingUiSettingsOrOnePlayer(String verb) {
    var restrictions = AnalysisMoveRestrictions.fromCommand(
        "124" + verb + "\tW 10 allow black d4,q16 2 avoid w pass 999 rootInfo true # allow b A1 1");
    assertNotNull(restrictions);
    assertEquals(List.of(
        new AnalysisMoveRestrictions.Clause("allow", "B", "D4,Q16", 2),
        new AnalysisMoveRestrictions.Clause("avoid", "W", "PASS", 999)), restrictions.clauses());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "kata-analyze 10", "124 kata-analyze 10 # allow b D4 1",
      "124 kata-analyze-file allow b D4 1", "124 456 kata-analyze 10 allow b D4 1",
      "name allow b D4 1", "genmove b", "genmove b allow b D4 1",
      "kata-genmove_analyze b 10", "lz-genmove_analyze w 10", "genmove_analyze b 10",
      "124 kata-genmove_analyze b 10 # allow b D4 1",
      "124 kata-genmove_analyze-file b 10 allow b D4 1",
      "124 456 kata-genmove_analyze b 10 allow b D4 1"
  })
  void nonRestrictionsAndOtherCommandsDoNotInventConditions(String command) {
    assertNull(AnalysisMoveRestrictions.fromCommand(command));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "kata-analyze 10 allow", "kata-analyze 10 allow b D4",
      "lz-analyze 10 avoid b D4 unknown", "analyze 10 allow wrong D4 1",
      "kata-analyze 10 allow b D4 1 avoid w A0 2",
      "kata-genmove_analyze b 10 allow", "lz-genmove_analyze w 10 avoid b D4 unknown",
      "genmove_analyze b 10 allow b D4 1 avoid w A0 2"
  })
  void unreadableRestrictionsStayRestrictedWithoutPublishingPartialDetails(String command) {
    var restrictions = AnalysisMoveRestrictions.fromCommand(command);
    assertNotNull(restrictions);
    assertTrue(restrictions.clauses().isEmpty());
  }

  @Test
  void snapshotDoesNotFollowCallerMutationsAndLargeCoordinateListsAreBounded() {
    var clauses = new ArrayList<>(List.of(new AnalysisMoveRestrictions.Clause("allow", "B", "D4", 1)));
    var restrictions = new AnalysisMoveRestrictions(clauses);
    clauses.clear();
    assertEquals(1, restrictions.clauses().size());
    assertThrows(UnsupportedOperationException.class, () -> restrictions.clauses().clear());
    assertTrue(AnalysisMoveRestrictions.fromCommand(
        "kata-analyze 10 allow b " + "D4,".repeat(22000) + "Q16 2").clauses().isEmpty());
    assertEquals(1, AnalysisMoveRestrictions.fromCommand(
        "kata-analyze 10 allow b " + "D4,".repeat(3000) + "Q16 2").clauses().size());
  }
}
