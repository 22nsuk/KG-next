package featurecat.lizzie.rules;

import static featurecat.lizzie.rules.SGFParserSemanticRoundTripTest.assertTreeSemanticsEqual;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SGFParserCompressedSetupTest {
  private static final String NINE_BLACK = "AB[aa][ab][ac][ba][bb][bc][ca][cb][cc]";
  private static final String GAME =
      "(;FF[4]GM[1]SZ[5]KM[6.5]RU[Japanese]AB[aa:cc]PL[W]C[root];W[ee]"
          + "(;AE[aa:bb]AW[dd:de]PL[B]C[setup];B[])"
          + "(;AB[da:db]PL[B];B[dc]))";
  private static final String EXPANDED_GAME =
      GAME.replace("AB[aa:cc]", NINE_BLACK)
          .replace("AE[aa:bb]", "AE[aa][ab][ba][bb]")
          .replace("AW[dd:de]", "AW[dd][de]")
          .replace("AB[da:db]", "AB[da][db]");

  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"detached", "string", "edit", "file"})
  void setupRangesAgreeAcrossImportAndRepeatedSave(String entrypoint) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(5)) {
      BoardHistoryList expected = SGFParser.parseSgf(EXPANDED_GAME, true);
      BoardHistoryList original = Lizzie.board.getHistory();
      BoardHistoryList actual;
      switch (entrypoint) {
        case "detached" -> {
          actual = SGFParser.parseSgf(GAME, true);
          assertSame(original, Lizzie.board.getHistory());
        }
        case "string" -> {
          assertTrue(SGFParser.loadFromString(GAME, false));
          actual = Lizzie.board.getHistory();
        }
        case "edit" -> {
          assertTrue(SGFParser.loadFromStringforedit(GAME));
          actual = Lizzie.board.getHistory();
        }
        default -> {
          Path input = directory.resolve("compressed.sgf");
          Files.writeString(input, GAME);
          assertTrue(SGFParser.load(input.toString(), false, false));
          SwingUtilities.invokeAndWait(() -> {});
          actual = Lizzie.board.getHistory();
        }
      }
      assertNotNull(actual);
      assertEquals(9, count(actual.getStart(), Stone.BLACK));
      BoardHistoryNode setup = actual.getStart().next().orElseThrow().next().orElseThrow();
      assertEquals(5, count(setup, Stone.BLACK));
      assertEquals(3, count(setup, Stone.WHITE));
      assertTrue(setup.getData().isSnapshotNode());
      assertEquals(1, setup.getData().moveNumber);
      assertTrue(setup.next().orElseThrow().getData().isPassNode());
      assertTreeSemanticsEqual(expected, actual);
      for (int round = 0; round < 2; round++) {
        Lizzie.board.setHistory(actual);
        actual = SGFParser.parseSgf(SGFParser.saveToString(false), true);
        assertTreeSemanticsEqual(expected, actual);
      }
    }
  }

  @Test
  void whiteRectangleAndSequentialRemovalKeepSetupSemantics() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(5)) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]AW[aa:cc];AE[aa:cc])", true);
      BoardHistoryNode root = history.getStart();
      assertEquals(9, count(root, Stone.WHITE));
      assertEquals(0, count(root.next().orElseThrow(), Stone.WHITE));
      assertEquals(0, root.next().orElseThrow().getData().moveNumber);
      assertEquals(0, root.next().orElseThrow().getData().blackCaptures);
      assertEquals(0, root.next().orElseThrow().getData().whiteCaptures);
      assertEquals(
          0, count(SGFParser.parseSgf("(;SZ[5]AB[aa:cc]AE[aa:cc])", true).getStart(), Stone.BLACK));
    }
  }

  @Test
  void branchImportExpandsBothSetupAndRemovalWithoutChangingExistingBranch() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(5)) {
      BoardHistoryList expected = SGFParser.parseSgf("(;SZ[5];B[ee])", true);
      SGFParser.parseBranch(expected, ";" + NINE_BLACK + "PL[W];W[ee];AE[aa][ab]AW[dd][de];B[]");
      BoardHistoryList actual = SGFParser.parseSgf("(;SZ[5];B[ee])", true);
      BoardHistoryNode existing = actual.getStart().next().orElseThrow();
      int branch = SGFParser.parseBranch(actual, ";AB[aa:cc]PL[W];W[ee];AE[aa:ab]AW[dd:de];B[]");
      assertTrue(branch >= 0);
      assertSame(existing, actual.getStart().getVariation(0).orElseThrow());
      assertEquals(9, count(actual.getStart().getVariation(1).orElseThrow(), Stone.BLACK));
      assertTreeSemanticsEqual(expected, actual);
      Lizzie.board.setHistory(actual);
      assertTreeSemanticsEqual(expected, SGFParser.parseSgf(SGFParser.saveToString(false), true));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"AB", "AW", "AE"})
  void editingInsideImportedRangeCannotBeUndoneByStaleSetupMetadata(String property)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(5)) {
      String initial = property.equals("AE") ? NINE_BLACK : "";
      assertTrue(
          SGFParser.loadFromStringforedit(
              "(;SZ[5]" + initial + ";W[ee];" + property + "[aa:cc]PL[B])"));
      BoardHistoryNode setup =
          Lizzie.board.getHistory().getStart().next().orElseThrow().next().orElseThrow();
      assertEquals(property.equals("AB") ? 9 : 0, count(setup, Stone.BLACK));
      assertEquals(property.equals("AW") ? 10 : 1, count(setup, Stone.WHITE));
      Lizzie.board.moveToAnyPosition(setup);
      BoardHistoryList history = Lizzie.board.getHistory();
      history.removeStone(1, 1, Stone.EMPTY);
      history.addExtraStone(1, 1, property.equals("AW") ? Stone.BLACK : Stone.WHITE);
      history.removeStone(2, 2, Stone.EMPTY);
      Stone[] edited = setup.getData().stones.clone();
      for (int round = 0; round < 2; round++) {
        BoardHistoryList reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true);
        BoardHistoryNode savedSetup = reopened.getStart().next().orElseThrow().next().orElseThrow();
        assertArrayEquals(edited, savedSetup.getData().stones);
        assertEquals(Stone.EMPTY, savedSetup.getData().stones[Board.getIndex(2, 2)]);
        Lizzie.board.setHistory(reopened);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ";"})
  void compressedHandicapKeepsItsTurnAndEngineRestoreEligibility(String separator)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(5)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5]HA[4]" + separator + "AB[aa:bb])", true);
      BoardHistoryNode setup = history.getStart();
      if (!separator.isEmpty()) setup = setup.next().orElseThrow();
      assertEquals(4, count(setup, Stone.BLACK));
      assertFalse(setup.getData().blackToPlay);
      assertTrue(InitialHandicapSetup.isInitialHandicap(setup, 4, 5, 5));
      Lizzie.board.setHistory(history);
      assertTreeSemanticsEqual(history, SGFParser.parseSgf(SGFParser.saveToString(false), true));
    }
  }

  @Test
  void propertyBoundariesSmartGoRepeatedValuesAndSurroundingTextStayCompatible() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(5)) {
      String game =
          "(;SZ[5:7]AddBlack[aa:ab][ea:eb]note[not:a-range]PL[W]"
              + "C[AB[zz:aa\\] is text]XX[AW[bad:bad\\]]LB[aa:A:B];W[])";
      BoardHistoryList expected =
          SGFParser.parseSgf(game.replace("AddBlack[aa:ab][ea:eb]", "AB[aa][ab][ea][eb]"), true);
      BoardHistoryList actual =
          SGFParser.parseSgf("SZ[52] AB[zz:aa] before " + game + " after AW[cc:aa]", true);
      assertNotNull(actual);
      assertEquals(4, count(actual.getStart(), Stone.BLACK));
      assertEquals(35, actual.getStart().getData().stones.length);
      assertEquals("AB[zz:aa] is text", actual.getStart().getData().comment);
      assertEquals("AW[bad:bad\\]", actual.getStart().getData().getProperty("XX"));
      assertEquals("aa:A:B", actual.getStart().getData().getProperty("LB"));
      assertTreeSemanticsEqual(expected, actual);
    }
  }

  @Test
  void extendedCoordinatesRoundTripAndDetachedParsingRestoresActiveBoardSize() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(5)) {
      BoardHistoryList expected =
          SGFParser.parseSgf("(;SZ[52:3]AB[0_0][0_1][1_0][1_1]AW[51_2]PL[W])", true);
      BoardHistoryList actual = SGFParser.parseSgf("(;SZ[52:3]AB[0_0:1_1]AW[51_2]PL[W])", true);
      assertEquals(4, count(actual.getStart(), Stone.BLACK));
      assertEquals(5, Board.boardWidth);
      assertEquals(5, Board.boardHeight);
      assertTreeSemanticsEqual(expected, actual);
      Lizzie.board.setHistory(actual);
      assertTreeSemanticsEqual(expected, SGFParser.parseSgf(SGFParser.saveToString(false), true));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"[cc:aa]", "[aa:fa]", "[aa:]", "[a:bb]", "[aa:bb:cc]", "[aa:ab"})
  void invalidLateRangeCannotReplaceOrPartiallyExtendTheCurrentGame(String setupValue)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(5)) {
      assertTrue(SGFParser.loadFromString("(;SZ[5];B[cc])", false));
      BoardHistoryList original = Lizzie.board.getHistory();
      BoardHistoryNode current = original.getCurrentHistoryNode();
      BoardHistoryNode existing = original.getStart().next().orElseThrow();
      String invalid = "(;SZ[5];B[bb];AB[aa:ab];AW" + setupValue + ")";
      assertNull(SGFParser.parseSgf(invalid, true));
      assertFalse(SGFParser.loadFromString(invalid, false));
      assertSame(original, Lizzie.board.getHistory());
      assertFalse(SGFParser.loadFromStringforedit(invalid));
      assertSame(original, Lizzie.board.getHistory());
      Path file = directory.resolve("invalid-range.sgf");
      Files.writeString(file, invalid);
      assertFalse(SGFParser.load(file.toString(), false, false));
      assertSame(original, Lizzie.board.getHistory());
      assertEquals(-1, SGFParser.parseBranch(original, ";AB[aa:ab];AE" + setupValue));
      assertSame(current, original.getCurrentHistoryNode());
      assertEquals(1, original.getStart().numberOfChildren());
      assertSame(existing, original.getStart().next().orElseThrow());
      assertEquals(0, count(original.getStart(), Stone.BLACK));
    }
  }

  private static long count(BoardHistoryNode node, Stone color) {
    return Arrays.stream(node.getData().stones).filter(stone -> stone == color).count();
  }
}
