package featurecat.lizzie.rules;

import java.util.Arrays;
import java.util.Objects;

/** Independent position arrays for undo; they become playable only after restoration. */
final class BoardPositionSnapshot {
  private static final int[] EMPTY_NUMBERS = new int[0];
  private static final Stone[] EMPTY_STONES = new Stone[0];

  private final int size;
  // Null denotes dense storage. Sparse indices are ascending and omit only EMPTY/0 cells.
  private final int[] indices;
  private final Stone[] stones;
  private final int[] moveNumbers;

  /** Scratch storage owned by one whole-history capture; never retained by a snapshot. */
  static final class CaptureBuffer {
    private int[] indices = new int[8];
    private Stone[] stones = new Stone[8];
    private int[] numbers = new int[8];

    private void ensureCapacity(int needed) {
      if (needed <= indices.length) return;
      int capacity = Math.max(needed, indices.length + (indices.length >> 1));
      indices = Arrays.copyOf(indices, capacity);
      stones = Arrays.copyOf(stones, capacity);
      numbers = Arrays.copyOf(numbers, capacity);
    }
  }

  private BoardPositionSnapshot(int size, int[] indices, Stone[] stones, int[] moveNumbers) {
    this.size = size;
    this.indices = indices;
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
    int count = 0;
    for (int i = 0; i < size; i++) {
      if (stones[i] != Stone.EMPTY || moveNumbers[i] != 0) {
        count++;
        // Three sparse arrays must save enough entries to offset their extra array header.
        // Long arithmetic also handles board lengths outside the usual small-board range.
        if (3L * count + 4 >= 2L * size) {
          return new BoardPositionSnapshot(size, null, stones.clone(), moveNumbers.clone());
        }
        buffer.ensureCapacity(count);
        buffer.indices[count - 1] = i;
        buffer.stones[count - 1] = stones[i];
        buffer.numbers[count - 1] = moveNumbers[i];
      }
    }
    if (count == 0) {
      return new BoardPositionSnapshot(size, EMPTY_NUMBERS, EMPTY_STONES, EMPTY_NUMBERS);
    }
    return new BoardPositionSnapshot(size, Arrays.copyOf(buffer.indices, count),
        Arrays.copyOf(buffer.stones, count), Arrays.copyOf(buffer.numbers, count));
  }

  int size() {
    return size;
  }

  int moveNumberAt(int index) {
    Objects.checkIndex(index, size);
    if (indices == null) return moveNumbers[index];
    int entry = Arrays.binarySearch(indices, index);
    return entry < 0 ? 0 : moveNumbers[entry];
  }

  /** Restores fresh mutable arrays; subsequent target edits cannot alter this snapshot. */
  void restoreInto(BoardData target) {
    Objects.requireNonNull(target, "target");
    Stone[] restoredStones;
    int[] restoredNumbers;
    if (indices == null) {
      restoredStones = stones.clone();
      restoredNumbers = moveNumbers.clone();
    } else {
      restoredStones = new Stone[size];
      Arrays.fill(restoredStones, Stone.EMPTY);
      restoredNumbers = new int[size];
      for (int i = 0; i < indices.length; i++) {
        restoredStones[indices[i]] = stones[i];
        restoredNumbers[indices[i]] = moveNumbers[i];
      }
    }
    target.stones = restoredStones;
    target.moveNumberList = restoredNumbers;
  }
}
