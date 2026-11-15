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

  @ParameterizedTest
  @ValueSource(ints = {1, 239, 240, 241, 300, 323, 324})
  void positionsBelowNinetyPercentOccupancyArePackedAndRestoreExactly(int occupied) {
    Stone[] stones = new Stone[361];
    int[] numbers = new int[361];
    fillOccupied(stones, numbers, occupied);
    assertFalse(BoardPositionSnapshot.capture(stones.clone(), numbers.clone()).isDense());
    assertIndependentRoundTrip(stones, numbers, 19, 19);
  }

  @ParameterizedTest
  @ValueSource(ints = {325, 326, 360, 361})
  void positionsAtLeastNinetyPercentOccupiedKeepDenseArraysAndRestoreExactly(int occupied) {
    Stone[] stones = new Stone[361];
    int[] numbers = new int[361];
    fillOccupied(stones, numbers, occupied);
    assertTrue(BoardPositionSnapshot.capture(stones.clone(), numbers.clone()).isDense());
    assertIndependentRoundTrip(stones, numbers, 19, 19);
  }

  @Test
  void numberOnlyCellsCountTowardTheDenseBound() {
    // 324 stones stay packed; one EMPTY cell with a nonzero number makes 325 occupied cells.
    Stone[] stones = new Stone[361];
    int[] numbers = new int[361];
    fillOccupied(stones, numbers, 324);
    assertFalse(BoardPositionSnapshot.capture(stones, numbers).isDense());
    int free = 0;
    while (stones[free] != Stone.EMPTY || numbers[free] != 0) free++;
    numbers[free] = -5;
    assertTrue(BoardPositionSnapshot.capture(stones, numbers).isDense());
    assertIndependentRoundTrip(stones, numbers, 19, 19);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 9, 10, 11, 19})
  void denseBoundRoundsNinetyPercentUpForSmallBoards(int size) {
    int denseFrom = (9 * size + 9) / 10;
    for (int occupied = 1; occupied <= size; occupied++) {
      Stone[] stones = new Stone[size];
      int[] numbers = new int[size];
      fillOccupied(stones, numbers, occupied);
      BoardPositionSnapshot snapshot = BoardPositionSnapshot.capture(stones, numbers);
      assertEquals(occupied >= denseFrom, snapshot.isDense(), size + "/" + occupied);
      BoardData target = BoardData.empty(size, 1);
      snapshot.restoreInto(target);
      assertArrayEquals(stones, target.stones);
      assertArrayEquals(numbers, target.moveNumberList);
    }
  }

  @Test
  void aBufferFilledByAFullBoardCannotLeakIntoLaterSparseSnapshots() {
    BoardPositionSnapshot.CaptureBuffer buffer = new BoardPositionSnapshot.CaptureBuffer();
    Stone[] full = new Stone[361];
    int[] fullNumbers = new int[361];
    fillOccupied(full, fullNumbers, 361);
    BoardPositionSnapshot dense = BoardPositionSnapshot.capture(full, fullNumbers, buffer);
    Stone[] sparse = new Stone[361];
    int[] sparseNumbers = new int[361];
    fillOccupied(sparse, sparseNumbers, 240);
    BoardPositionSnapshot packed = BoardPositionSnapshot.capture(sparse, sparseNumbers, buffer);
    assertTrue(dense.isDense());
    assertFalse(packed.isDense());
    BoardData target = BoardData.empty(19, 19);
    packed.restoreInto(target);
    assertArrayEquals(sparse, target.stones);
    assertArrayEquals(sparseNumbers, target.moveNumberList);
    dense.restoreInto(target);
    assertArrayEquals(full, target.stones);
    assertArrayEquals(fullNumbers, target.moveNumberList);
  }

  /** Occupies cells spread over the board with every stone kind, null and extreme numbers. */
  private static void fillOccupied(Stone[] stones, int[] numbers, int occupied) {
    Arrays.fill(stones, Stone.EMPTY);
    Arrays.fill(numbers, 0);
    Stone[] kinds = Stone.values();
    int size = stones.length;
    for (int k = 0; k < occupied; k++) {
      int i = (int) ((long) k * 7 % size);
      if (size % 7 == 0) i = k; // keep the stride a permutation
      int kind = k % (kinds.length + 1);
      stones[i] = kind == kinds.length ? null : kinds[kind];
      if (stones[i] == Stone.EMPTY && k % 2 == 0) stones[i] = Stone.WHITE;
      numbers[i] = switch (k % 5) {
        case 0 -> Integer.MIN_VALUE + k;
        case 1 -> Integer.MAX_VALUE - k;
        case 2 -> -k;
        case 3 -> k;
        default -> k % 3 == 0 ? 0 : k + 1;
      };
      if (stones[i] == Stone.EMPTY && numbers[i] == 0) numbers[i] = -1 - k;
    }
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
