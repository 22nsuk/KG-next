package featurecat.lizzie.rules;

import java.util.Arrays;
import java.util.Objects;

/** Independent position arrays for undo; they become playable only after restoration. */
final class BoardPositionSnapshot {
  private static final long[] EMPTY_CELLS = new long[0];
  private static final Stone[] STONE_KINDS = Stone.values();
  private static final int NULL_STONE = 7;
  private static final int MAX_PACKED_SIZE = 1 << 29;

  private final int size;
  // Null denotes dense storage. Each sparse cell is index:29 / stone:3 / exact number:32.
  // Indices are ascending; omit only EMPTY/0 cells. The eighth stone code preserves null.
  private final long[] cells;
  private final Stone[] stones;
  private final int[] moveNumbers;

  /** Scratch storage owned by one whole-history capture; never retained by a snapshot. */
  static final class CaptureBuffer {
    private long[] cells = new long[8];

    private void ensureCapacity(int needed) {
      if (needed <= cells.length) return;
      int capacity = Math.max(needed, cells.length + (cells.length >> 1));
      cells = Arrays.copyOf(cells, capacity);
    }
  }

  private BoardPositionSnapshot(int size, long[] cells, Stone[] stones, int[] moveNumbers) {
    this.size = size;
    this.cells = cells;
    this.stones = stones;
    this.moveNumbers = moveNumbers;
  }

  /** The caller freezes mutations to both source arrays while this capture runs. */
  static BoardPositionSnapshot capture(Stone[] stones, int[] moveNumbers) {
    return capture(stones, moveNumbers, new CaptureBuffer());
  }

  static BoardPositionSnapshot capture(
      Stone[] stones, int[] moveNumbers, CaptureBuffer buffer) {
    Objects.requireNonNull(stones, "stones");
    Objects.requireNonNull(moveNumbers, "moveNumbers");
    if (stones.length != moveNumbers.length) {
      throw new IllegalArgumentException("Stone and move-number arrays must have equal lengths");
    }
    int size = stones.length;
    if (size > MAX_PACKED_SIZE || STONE_KINDS.length > NULL_STONE) {
      return new BoardPositionSnapshot(size, null, stones.clone(), moveNumbers.clone());
    }
    int count = 0;
    for (int i = 0; i < size; i++) {
      if (stones[i] != Stone.EMPTY || moveNumbers[i] != 0) {
        count++;
        // Keep the existing dense cutoff: cloning dense arrays avoids packing every cell.
        if (3L * count + 4 >= 2L * size) {
          return new BoardPositionSnapshot(size, null, stones.clone(), moveNumbers.clone());
        }
        buffer.ensureCapacity(count);
        int stone = stones[i] == null ? NULL_STONE : stones[i].ordinal();
        buffer.cells[count - 1] = ((long) i << 35) | ((long) stone << 32)
            | Integer.toUnsignedLong(moveNumbers[i]);
      }
    }
    if (count == 0) {
      return new BoardPositionSnapshot(size, EMPTY_CELLS, null, null);
    }
    return new BoardPositionSnapshot(size, Arrays.copyOf(buffer.cells, count), null, null);
  }

  int size() {
    return size;
  }

  int moveNumberAt(int index) {
    Objects.checkIndex(index, size);
    if (cells == null) return moveNumbers[index];
    int low = 0;
    int high = cells.length - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      int cellIndex = (int) (cells[middle] >>> 35);
      if (cellIndex < index) low = middle + 1;
      else if (cellIndex > index) high = middle - 1;
      else return (int) cells[middle];
    }
    return 0;
  }

  /** Restores fresh mutable arrays; subsequent target edits cannot alter this snapshot. */
  void restoreInto(BoardData target) {
    Objects.requireNonNull(target, "target");
    Stone[] restoredStones;
    int[] restoredNumbers;
    if (cells == null) {
      restoredStones = stones.clone();
      restoredNumbers = moveNumbers.clone();
    } else {
      restoredStones = new Stone[size];
      Arrays.fill(restoredStones, Stone.EMPTY);
      restoredNumbers = new int[size];
      for (long cell : cells) {
        int index = (int) (cell >>> 35);
        int stone = (int) (cell >>> 32) & 7;
        restoredStones[index] = stone == NULL_STONE ? null : STONE_KINDS[stone];
        restoredNumbers[index] = (int) cell;
      }
    }
    target.stones = restoredStones;
    target.moveNumberList = restoredNumbers;
  }
}
