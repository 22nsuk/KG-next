package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class RestrictionCoordinatesTest {
  @Test void removingMiddleVertexPreservesEveryOtherVertexInOrder() {
    assertEquals("D4,C3,R4", RestrictionCoordinates.remove("D4,Q16,C3,R4", "Q16"));
  }
  @Test void removingLastVertexProducesAnEmptyRestriction() {
    assertEquals("", RestrictionCoordinates.remove("D4", "D4"));
  }
  @Test void additionsDoNotDuplicateVertices() {
    assertEquals("D4,Q16", RestrictionCoordinates.add(" D4, Q16,D4", "D4"));
    assertEquals("D4,Q16,pass", RestrictionCoordinates.add("D4,Q16", "pass"));
  }
  @Test void missingVertexDoesNotChangeOthers() {
    assertEquals("D4,Q16", RestrictionCoordinates.remove("D4,Q16", "C3"));
  }
}
