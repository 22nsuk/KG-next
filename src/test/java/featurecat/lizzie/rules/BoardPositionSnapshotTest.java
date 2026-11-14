package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardPositionSnapshotTest {
  @Test
  void sparseRectangularPositionRetainsEveryStoneKindAndNumberWithoutAliases() {
    Stone[] stones = new Stone[7 * 11];
    Arrays.fill(stones, Stone.EMPTY);
    int[] numbers = new int[stones.length];
    Stone[] kinds = Stone.values();
    for (int i = 0; i < kinds.length; i++) {
      stones[i * 3] = kinds[i];
      numbers[i * 3] = i % 2 == 0 ? 20 + i : -20 - i;
    }
    numbers[0] = 0; // An unnumbered black stone at index zero is still an occupied cell.
    stones[70] = null;
    numbers[70] = Integer.MIN_VALUE;
    numbers[76] = Integer.MAX_VALUE;
    assertIndependentRoundTrip(stones, numbers, 7, 11);
  }

  @Test
  void densePositionRetainsRecursedCapturedNullAndEmptyStonesWithoutAliases() {
    Stone[] stones = new Stone[8 * 5];
    int[] numbers = new int[stones.length];
    Stone[] kinds = Stone.values();
    for (int i = 0; i < stones.length; i++) {
      stones[i] = i % 5 == 0 ? null : kinds[i % kinds.length];
      numbers[i] = i % 2 == 0 ? Integer.MAX_VALUE - i : Integer.MIN_VALUE + i;
    }
    assertIndependentRoundTrip(stones, numbers, 8, 5);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 361})
  void emptyPositionsRestoreDefaultCellsAndEnforceIndexBounds(int size) {
    Stone[] stones = new Stone[size];
    Arrays.fill(stones, Stone.EMPTY);
    int[] numbers = new int[size];
    BoardPositionSnapshot snapshot = BoardPositionSnapshot.capture(stones, numbers);
    BoardData target = BoardData.empty(19, 19);
    snapshot.restoreInto(target);
    assertEquals(size, snapshot.size());
    assertArrayEquals(stones, target.stones);
    assertArrayEquals(numbers, target.moveNumberList);
    if (size > 0) assertEquals(0, snapshot.moveNumberAt(size - 1));
    assertThrows(IndexOutOfBoundsException.class, () -> snapshot.moveNumberAt(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> snapshot.moveNumberAt(size));
  }

  @Test
  void mismatchedSourceArraysAreRejectedBeforeCapture() {
    assertThrows(IllegalArgumentException.class,
        () -> BoardPositionSnapshot.capture(new Stone[2], new int[3]));
    assertThrows(NullPointerException.class,
        () -> BoardPositionSnapshot.capture(null, new int[0]));
    assertThrows(NullPointerException.class,
        () -> BoardPositionSnapshot.capture(new Stone[0], null));
  }

  @Test
  void aReusedCaptureBufferCannotMutateEarlierSparseSnapshots() {
    BoardPositionSnapshot.CaptureBuffer buffer = new BoardPositionSnapshot.CaptureBuffer();
    Stone[] stones = new Stone[361];
    Arrays.fill(stones, Stone.EMPTY);
    int[] numbers = new int[361];
    stones[300] = Stone.BLACK;
    numbers[300] = -7;
    BoardPositionSnapshot first = BoardPositionSnapshot.capture(stones, numbers, buffer);
    Arrays.fill(stones, 0, 200, Stone.WHITE);
    Arrays.fill(numbers, 0, 200, 123);
    BoardPositionSnapshot.capture(stones, numbers, buffer);
    BoardData restored = BoardData.empty(19, 19);
    first.restoreInto(restored);
    assertEquals(Stone.EMPTY, restored.stones[0]);
    assertEquals(0, restored.moveNumberList[0]);
    assertEquals(Stone.BLACK, restored.stones[300]);
    assertEquals(-7, restored.moveNumberList[300]);
  }

  private static void assertIndependentRoundTrip(
      Stone[] stones, int[] numbers, int width, int height) {
    Stone[] expectedStones = stones.clone();
    int[] expectedNumbers = numbers.clone();
    BoardPositionSnapshot snapshot = BoardPositionSnapshot.capture(stones, numbers);
    assertEquals(stones.length, snapshot.size());
    Arrays.fill(stones, Stone.BLACK);
    Arrays.fill(numbers, 999);

    BoardData target = BoardData.empty(width, height);
    target.moveNumber = 123;
    target.comment = "metadata stays owned by the history snapshot";
    Zobrist hash = target.zobrist;
    snapshot.restoreInto(target);
    assertArrayEquals(expectedStones, target.stones);
    assertArrayEquals(expectedNumbers, target.moveNumberList);
    for (int i = 0; i < expectedNumbers.length; i++) {
      assertEquals(expectedNumbers[i], snapshot.moveNumberAt(i), "index " + i);
    }
    assertEquals(123, target.moveNumber);
    assertEquals("metadata stays owned by the history snapshot", target.comment);
    assertSame(hash, target.zobrist);

    Stone[] firstRestoration = target.stones;
    int[] firstNumbers = target.moveNumberList;
    Arrays.fill(firstRestoration, Stone.WHITE);
    Arrays.fill(firstNumbers, -999);
    snapshot.restoreInto(target);
    assertNotSame(firstRestoration, target.stones);
    assertNotSame(firstNumbers, target.moveNumberList);
    assertArrayEquals(expectedStones, target.stones);
    assertArrayEquals(expectedNumbers, target.moveNumberList);
    for (int i = 0; i < expectedNumbers.length; i++) {
      assertEquals(expectedNumbers[i], snapshot.moveNumberAt(i), "after target edit " + i);
    }
  }
}
