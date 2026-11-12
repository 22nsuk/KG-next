package featurecat.lizzie.rules;

import java.util.Objects;
import java.util.Random;

/** A position hash and its immutable coordinate table, shared by descendants of that position. */
public class Zobrist {
  // Independent histories can be spliced by online sync. Their same-size hashes must agree,
  // including after init(), without retaining a global cache of every board size ever parsed.
  private static final long TABLE_SEED = new Random().nextLong();
  private static volatile TableSnapshot currentTables = createTables(Board.boardWidth, Board.boardHeight);

  /** Opaque identity-preserving snapshot used by live-board rollback. */
  static final class TableSnapshot {
    private final int width;
    private final int height;
    private final long[] black;
    private final long[] white;

    private TableSnapshot(int width, int height, long[] black, long[] white) {
      this.width = width;
      this.height = height;
      this.black = black;
      this.white = white;
    }
  }

  private final TableSnapshot tables;
  private long zhash;

  public Zobrist() {
    this(0, currentTables);
  }

  public Zobrist(long zhash) {
    this(zhash, currentTables);
  }

  Zobrist(int width, int height) {
    this(0, tablesFor(width, height));
  }

  private Zobrist(long zhash, TableSnapshot tables) {
    this.zhash = zhash;
    this.tables = tables;
  }

  private static TableSnapshot tablesFor(int width, int height) {
    TableSnapshot current = currentTables;
    return current.width == width && current.height == height
        ? current : createTables(width, height);
  }

  private static TableSnapshot createTables(int width, int height) {
    Random random = new Random(TABLE_SEED ^ ((long) width * 0x9e3779b97f4a7c15L) ^ height);
    long[] black = new long[Math.multiplyExact(width, height)];
    long[] white = new long[black.length];
    for (int i = 0; i < black.length; i++) {
      black[i] = random.nextLong();
      white[i] = random.nextLong();
    }
    return new TableSnapshot(width, height, black, white);
  }

  public Zobrist clone() {
    return new Zobrist(zhash, tables);
  }

  public static synchronized void init() {
    currentTables = createTables(Board.boardWidth, Board.boardHeight);
  }

  static TableSnapshot captureTables() {
    return currentTables;
  }

  static synchronized void restoreTables(TableSnapshot snapshot) {
    currentTables = Objects.requireNonNull(snapshot, "Missing Zobrist table snapshot.");
  }

  int width() { return tables.width; }

  int height() { return tables.height; }

  int index(int x, int y) { return x * tables.height + y; }

  boolean isValid(int x, int y) {
    return x >= 0 && x < tables.width && y >= 0 && y < tables.height;
  }

  /** Add or remove a stone using this position's table, even after another board is opened. */
  public void toggleStone(int x, int y, Stone color) {
    switch (color) {
      case BLACK:
        zhash ^= tables.black[index(x, y)];
        break;
      case WHITE:
        zhash ^= tables.white[index(x, y)];
        break;
      default:
    }
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Zobrist && (((Zobrist) o).zhash == zhash);
  }

  @Override
  public int hashCode() { return (int) zhash; }

  @Override
  public String toString() { return "" + zhash; }
}
