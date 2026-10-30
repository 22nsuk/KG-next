package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardDataNodeKindTest {
  private static final int BOARD_AREA = 9;

  @Test
  void snapshotFactoryKeepsExplicitSnapshotNodeKindEvenWithMarkerMetadata() {
    BoardData data = snapshotData(Optional.of(new int[] {1, 2}), Stone.BLACK, false, 59);

    assertEquals(BoardNodeKind.SNAPSHOT, data.getNodeKind());
    assertTrue(data.lastMove.isPresent());
    assertFalse(data.isMoveNode());
    assertFalse(data.isPassNode());
    assertTrue(data.isSnapshotNode());
    assertFalse(data.isHistoryActionNode());
  }

  @Test
  void moveFactoryCreatesHistoryMoveNode() {
    BoardData data = moveData(new int[] {1, 2}, Stone.BLACK, false, 42);

    assertEquals(BoardNodeKind.MOVE, data.getNodeKind());
    assertTrue(data.lastMove.isPresent());
    assertTrue(data.isMoveNode());
    assertFalse(data.isPassNode());
    assertFalse(data.isSnapshotNode());
    assertTrue(data.isHistoryActionNode());
  }

  @Test
  void passFactoryCreatesPassHistoryNode() {
    BoardData data = passData(Stone.WHITE, true, 60);

    assertEquals(BoardNodeKind.PASS, data.getNodeKind());
    assertFalse(data.lastMove.isPresent());
    assertFalse(data.isMoveNode());
    assertTrue(data.isPassNode());
    assertFalse(data.isSnapshotNode());
    assertTrue(data.isHistoryActionNode());
  }

  @Test
  void snapshotFactoryAndSyncPreserveSnapshotNodeKind() {
    BoardData source = snapshotData(Optional.empty(), Stone.EMPTY, false, 61);
    BoardData target = moveData(new int[] {0, 0}, Stone.BLACK, false, 62);

    target.sync(source);

    assertEquals(BoardNodeKind.SNAPSHOT, source.getNodeKind());
    assertEquals(BoardNodeKind.SNAPSHOT, target.getNodeKind());
    assertFalse(source.isHistoryActionNode());
    assertFalse(target.isHistoryActionNode());
    assertFalse(target.lastMove.isPresent());
    assertEquals(Stone.EMPTY, target.lastMoveColor);
  }

  @Test
  void clonePreservesSnapshotPropertiesAndCopiesMutableState() {
    BoardData source = snapshotData(Optional.of(new int[] {1, 2}), Stone.BLACK, false, 63);
    source.moveNumberList[0] = 7;
    source.addProperties("AB[aa]AW[bb]AE[cc]LB[aa:X]");
    source.comment = "setup";

    BoardData clone = source.clone();

    source.lastMove.get()[0] = 0;
    source.moveNumberList[0] = 3;
    source.stones[2 * 3 + 1] = Stone.WHITE;
    source.addProperty("TR", "bb");

    assertEquals(BoardNodeKind.SNAPSHOT, clone.getNodeKind());
    assertTrue(clone.lastMove.isPresent());
    assertEquals(1, clone.lastMove.get()[0]);
    assertEquals(2, clone.lastMove.get()[1]);
    assertEquals(7, clone.moveNumberList[0]);
    assertEquals(Stone.BLACK, clone.stones[2 * 3 + 1]);
    assertEquals("AB[aa]AW[bb]AE[cc]LB[aa:X]", clone.propertiesString());
    assertEquals("setup", clone.comment);
    assertFalse(clone.getProperties().containsKey("TR"));
  }

  @ParameterizedTest
  @EnumSource(BoardNodeKind.class)
  void cloneKeepsCoreMetadataAndDetachesArraysForEveryNodeKind(BoardNodeKind kind) {
    BoardData source = switch (kind) {
      case MOVE -> moveData(new int[] {1, 2}, Stone.BLACK, false, 63);
      case PASS -> passData(Stone.WHITE, true, 63);
      case SNAPSHOT -> snapshotData(Optional.of(new int[] {1, 2}), Stone.BLACK, false, 63);
    };
    source.moveMNNumber = 17;
    source.dummy = true;
    source.verify = true;
    source.blackCaptures = 3;
    source.whiteCaptures = 4;
    source.moveNumberList[0] = 7;
    source.zobrist = new Zobrist(12345);

    BoardData copy = source.clone();

    assertEquals(kind, copy.getNodeKind());
    assertEquals(63, copy.moveNumber);
    assertEquals(17, copy.moveMNNumber);
    assertTrue(copy.dummy);
    assertTrue(copy.verify);
    assertEquals(source.lastMoveColor, copy.lastMoveColor);
    assertEquals(source.blackToPlay, copy.blackToPlay);
    assertEquals(3, copy.blackCaptures);
    assertEquals(4, copy.whiteCaptures);
    assertEquals(new Zobrist(12345), copy.zobrist);
    assertNotSame(source.zobrist, copy.zobrist);
    assertNotSame(source.stones, copy.stones);
    assertNotSame(source.moveNumberList, copy.moveNumberList);
    source.stones[0] = Stone.WHITE;
    source.moveNumberList[0] = 99;
    assertEquals(Stone.EMPTY, copy.stones[0]);
    assertEquals(7, copy.moveNumberList[0]);
    if (source.lastMove.isPresent()) {
      source.lastMove.get()[0] = 0;
      assertEquals(1, copy.lastMove.orElseThrow()[0]);
    } else {
      assertTrue(copy.lastMove.isEmpty());
    }
  }

  private static BoardData moveData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[lastMove[1] * 3 + lastMove[0]] = lastMoveColor;
    return BoardData.move(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(),
        0,
        0,
        50,
        0);
  }

  private static BoardData passData(Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        emptyStones(),
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(),
        0,
        0,
        50,
        0);
  }

  private static BoardData snapshotData(
      Optional<int[]> lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    lastMove.ifPresent(coords -> stones[coords[1] * 3 + coords[0]] = lastMoveColor);
    return BoardData.snapshot(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(),
        0,
        0,
        50,
        0);
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static int[] moveList() {
    return new int[BOARD_AREA];
  }
}
