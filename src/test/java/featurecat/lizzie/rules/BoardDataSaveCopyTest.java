package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardDataSaveCopyTest {
  @Test
  void saveSharesOnlyOwnedImmutableOwnershipWhilePlayableCloneRetainsItsCore() {
    BoardData source = BoardData.empty(19, 19);
    double[] ownership = {-0.0, 0.25, -0.5};
    source.estimateArray = BoardData.compactEstimateArray(ownership, ownership.length);
    source.estimateArray2 = BoardData.compactEstimateArray(Arrays.asList(null, 0.5));
    BoardData save = source.copyForSave();
    BoardData playable = source.clone();

    ownership[1] = 1.0;
    source.estimateArray = List.of(1.0);
    source.estimateArray2 = null;
    assertEquals(List.of(-0.0, 0.25, -0.5), save.estimateArray);
    assertEquals(Arrays.asList(null, 0.5), save.estimateArray2);
    assertThrows(UnsupportedOperationException.class, () -> save.estimateArray.set(0, 1.0));
    assertNotSame(playable.estimateArray, save.estimateArray);
    assertArrayEquals(source.moveNumberList, playable.moveNumberList);
    assertNotSame(source.moveNumberList, playable.moveNumberList);
    assertNotSame(source.zobrist, playable.zobrist);
    assertNotNull(playable.zobrist);
    source.moveNumberList[0] = 42;
    assertEquals(0, playable.moveNumberList[0]);
  }

  @Test
  void saveDetachesAnUnmodifiableViewOverMutableOwnership() {
    BoardData source = BoardData.empty(19, 19);
    var backing = new ArrayList<>(Arrays.asList(-0.0, null, 0.25));
    source.estimateArray = Collections.unmodifiableList(backing);
    source.estimateArray2 = backing;
    BoardData save = source.copyForSave();
    backing.set(0, 1.0);
    backing.clear();
    assertEquals(Arrays.asList(-0.0, null, 0.25), save.estimateArray);
    assertEquals(Arrays.asList(-0.0, null, 0.25), save.estimateArray2);
  }
}
