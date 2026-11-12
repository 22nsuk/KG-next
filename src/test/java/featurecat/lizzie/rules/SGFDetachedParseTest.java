package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GameInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SGFDetachedParseTest {
  private static final SGFParser.ParseOptions OPTIONS =
      new SGFParser.ParseOptions(true, false, false, true, 3, 2, 7.5);

  @ParameterizedTest
  @ValueSource(strings = {"C[note]]", "C[note]]]", "]C[note]"})
  void strayClosingBracketsRetainMovesThroughDetachedLiveAndSavePaths(String comment)
      throws Exception {
    String sgf = "(;SZ[19];B[aa]" + comment + ";W[bb])";
    try (var harness = RulesLayerTestHarness.open(19)) {
      assertTwoMovesAndComment(SGFParser.parseSgf(sgf, true, OPTIONS));
      assertTrue(SGFParser.loadFromString(sgf, false));
      assertTwoMovesAndComment(Lizzie.board.getHistory());
      String saved = SGFParser.saveToString(false);
      assertTwoMovesAndComment(SGFParser.parseSgf(saved, true, OPTIONS));
      assertTrue(SGFParser.loadFromStringforedit(saved));
      assertTwoMovesAndComment(Lizzie.board.getHistory());
    }
  }

  @Test
  void strayClosingBracketBranchImportStillSavesAndReopensBothMoves() throws Exception {
    try (var harness = RulesLayerTestHarness.open(19)) {
      var history = SGFParser.parseSgf("(;SZ[19])", true, OPTIONS);
      SGFParser.parseBranch(history, ";B[aa]C[note]];W[bb]");
      assertTwoMovesAndComment(history);
      Lizzie.board.setHistory(history);
      String saved = SGFParser.saveToString(false);
      assertTwoMovesAndComment(SGFParser.parseSgf(saved, true, OPTIONS));
      assertTrue(SGFParser.loadFromString(saved, false));
      assertTwoMovesAndComment(Lizzie.board.getHistory());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {";", "(", ")"})
  void incompletePropertyNamesStopAtStructuralBoundaries(String boundary) {
    var document = SgfDocument.parse("(;C[first]INCOMPLETE" + boundary + "C[second])");
    assertNotNull(document);
    assertEquals(
        List.of(
            new SgfDocument.Event('P', "C", "first"),
            new SgfDocument.Event('P', "C", "second")),
        document.events().stream().filter(event -> event.kind() == 'P').toList());
  }

  @Test
  void validEscapedRepeatedAndNumberedPropertiesKeepTheirSyntaxAndBranches() throws Exception {
    String sgf = "(;SZ[19]LZOP2[Sub 41.0 150 2.1 0.5 0.8]KGP[opaque\\]data]"
        + ";B[aa]C[note\\]]LB[aa:A][bb:B]LZ2[Sub 41.0 150 2.1 0.5 0.8]"
        + "(;W[bb])(;W[cc]))";
    var document = SgfDocument.parse(sgf);
    assertNotNull(document);
    assertEquals(
        List.of("SZ", "LZOP2", "KGP", "B", "C", "LB", "LB", "LZ2", "W", "W"),
        document.events().stream().filter(event -> event.kind() == 'P')
            .map(SgfDocument.Event::name).toList());
    assertEquals("opaque\\]data", document.events().get(4).rawValue());
    try (var harness = RulesLayerTestHarness.open(19)) {
      // Analysis payload semantics have separate coverage; these are the valid syntax controls.
      var history = SGFParser.parseSgf(sgf, true, OPTIONS);
      var move = history.getStart().next().orElseThrow();
      assertEquals("note]", move.getData().comment);
      assertEquals(List.of("aa:A", "bb:B"), move.getData().getProperties().get("LB"));
      assertEquals(2, move.numberOfChildren());
      assertArrayEquals(new int[] {1, 1}, move.getVariation(0).orElseThrow().getData().lastMove.orElseThrow());
      assertArrayEquals(new int[] {2, 2}, move.getVariation(1).orElseThrow().getData().lastMove.orElseThrow());
    }
  }

  private static void assertTwoMovesAndComment(BoardHistoryList history) {
    assertNotNull(history);
    var black = history.getStart().next().orElseThrow();
    assertArrayEquals(new int[] {0, 0}, black.getData().lastMove.orElseThrow());
    assertEquals("note", black.getData().comment);
    var white = black.next().orElseThrow();
    assertArrayEquals(new int[] {1, 1}, white.getData().lastMove.orElseThrow());
    assertEquals(Stone.WHITE, white.getData().stones[20]);
    assertTrue(white.next().isEmpty());
  }

  @Test
  void syntaxCanBeReusedWithCapturedPolicies() throws Exception {
    SgfDocument document = SgfDocument.parse("(;SZ[5:7]KM[7.5]C[a\\]b\\\\c]"
        + ";B[eg]LZ[KataGo 45 12 2.5 1.25 0\nmove E1 visits 12 winrate 5500 pv E1 D2 C3 B4])");
    assertEquals(5, document.width());
    assertEquals(7, document.height());
    assertThrows(UnsupportedOperationException.class, () -> document.events().clear());
    try (var harness = RulesLayerTestHarness.open(19)) {
      var captured = new SGFParser.ParseOptions(true, false, false, true, 2, 1, 7.5);
      Lizzie.config.limitBranchLength = 1;
      Lizzie.config.readKomi = false;
      var first = SGFParser.buildHistory(document, true, captured);
      var second = SGFParser.buildHistory(document, true, OPTIONS);
      assertEquals(7.5, first.getGameInfo().getKomi());
      assertEquals("a]b\\c", first.getStart().getData().comment);
      assertEquals(2, first.getStart().next().orElseThrow().getData().bestMoves.get(0).variation.size());
      assertEquals(3, second.getStart().next().orElseThrow().getData().bestMoves.get(0).variation.size());
      first.getStart().getData().comment = "changed";
      assertEquals("a]b\\c", second.getStart().getData().comment);
    }
  }

  @Test
  void missingKomiUsesTheCapturedDefault() throws Exception {
    double original = GameInfo.DEFAULT_KOMI;
    try {
      GameInfo.DEFAULT_KOMI = 6.5;
      var captured = SGFParser.ParseOptions.capture();
      GameInfo.DEFAULT_KOMI = 7.5;
      var history = SGFParser.buildHistory(SgfDocument.parse("(;SZ[5:7];B[eg])"), true, captured);
      assertEquals(6.5, history.getGameInfo().getKomi());
    } finally {
      GameInfo.DEFAULT_KOMI = original;
    }
  }

  @Test
  void moveNumberBeforeEditedSetupUsesDetachedCoordinates() throws Exception {
    try (var harness = RulesLayerTestHarness.open(19)) {
      var history = SGFParser.parseSgf("(;SZ[5:7];B[eg]MN[10]AB[aa])", true, OPTIONS);
      var move = history.getStart().next().orElseThrow();
      assertEquals(Stone.BLACK, move.getData().stones[34]);
      var setup = move.next().orElseThrow();
      assertEquals(10, setup.getData().moveMNNumber);
      assertEquals(Stone.BLACK, setup.getData().stones[0]);
      assertEquals(19, Board.boardWidth);
      assertEquals(19, Board.boardHeight);
    }
  }

  @Test
  void detachedCaptureAndKoRemainValidAfterLiveBoardResize() throws Exception {
    try (var harness = RulesLayerTestHarness.open(19)) {
      var tables = Zobrist.captureTables();
      try {
        var history = SGFParser.parseSgf(
            "(;SZ[5:7]AB[ab][ba][bc]AW[bb][ca][cc][db];B[cb])", true, OPTIONS);
        var captured = history.getStart().next().orElseThrow();
        history.setHead(captured);
        assertEquals(1, captured.getData().blackCaptures);
        assertEquals(Stone.EMPTY, captured.getData().stones[8]);
        Board.boardWidth = 13;
        Board.boardHeight = 9;
        Zobrist.init();
        history.getStart().getData().getProperties().remove("SZ");
        assertArrayEquals(new int[] {5, 7}, SGFParser.resolveHistoryBoardSize(history));
        BoardHistoryNode.runWithoutGlobalMoveSideEffects(true, () -> {
          history.place(1, 1, Stone.WHITE, false, false, false, false, false);
          assertSame(captured, history.getCurrentHistoryNode(), "Immediate ko recapture must be rejected");
          history.place(4, 6, Stone.WHITE, false, false, false, false, false);
        });
        assertNotSame(captured, history.getCurrentHistoryNode());
        assertEquals(Stone.WHITE, history.getStones()[34]);
        var emptyHash = history.getZobrist();
        for (int x = 0; x < 5; x++) {
          for (int y = 0; y < 7; y++) emptyHash.toggleStone(x, y, history.getStones()[x * 7 + y]);
        }
        assertEquals(new Zobrist(0), emptyHash);
      } finally {
        Board.boardWidth = 19;
        Board.boardHeight = 19;
        Zobrist.restoreTables(tables);
      }
    }
  }

  @Test
  void independentlyParsedHistoriesRetainKoAfterOnlineStyleSync() throws Exception {
    try (var harness = RulesLayerTestHarness.open(19)) {
      String setup = "(;SZ[5:7]AB[ab][ba][bc]AW[bb][ca][cc][db];B[cb]";
      var current = SGFParser.parseSgf(setup + ")", true, OPTIONS);
      var incoming = SGFParser.parseSgf(setup + "C[updated])", true, OPTIONS);
      assertEquals(current.getZobrist(), incoming.getZobrist());
      var captured = current.getStart().next().orElseThrow();
      BoardHistoryNode.runWithoutGlobalMoveSideEffects(true, () -> {
        current.sync(incoming);
        assertEquals("updated", captured.getData().comment);
        current.setHead(captured);
        current.place(1, 1, Stone.WHITE, false, false, false, false, false);
        assertSame(captured, current.getCurrentHistoryNode(), "Synced nodes must share the ko hash domain");
      });
      Lizzie.board.setHistory(current);
      var snapshotHash = new Zobrist();
      for (int x = 0; x < 5; x++) {
        for (int y = 0; y < 7; y++) snapshotHash.toggleStone(x, y, current.getStones()[x * 7 + y]);
      }
      assertEquals(current.getZobrist(), snapshotHash, "Installed history and new snapshots must agree");
    }
  }

  @Test
  void ttRemainsARealCoordinateOnLargerBoards() throws Exception {
    try (var harness = RulesLayerTestHarness.open(19)) {
      var history = SGFParser.parseSgf("(;SZ[20:21];B[tt];W[])", true, OPTIONS);
      var move = history.getStart().next().orElseThrow();
      assertArrayEquals(new int[] {19, 19}, move.getData().lastMove.orElseThrow());
      assertEquals(Stone.BLACK, move.getData().stones[19 * 21 + 19]);
      assertTrue(move.next().orElseThrow().getData().isPassNode());
    }
  }

  @Test
  void liveLoaderRetainsLeadingNodeFragmentTolerance() throws Exception {
    try (var harness = RulesLayerTestHarness.open(19)) {
      assertTrue(SGFParser.loadFromStringforedit(";SZ[19];B[eg])"));
      var history = Lizzie.board.getHistory();
      assertEquals(361, history.getStones().length);
      assertArrayEquals(new int[] {4, 6}, history.getStart().next().orElseThrow().getData().lastMove.orElseThrow());
    }
  }

  @Test
  void detachedImportNeedsNoLiveOwnersAndRetainsItsCoordinatesAfterResize() throws Exception {
    try (var harness = RulesLayerTestHarness.open(19)) {
      var board = Lizzie.board;
      var engine = Lizzie.leelaz;
      var config = Lizzie.config;
      var frame = Lizzie.frame;
      var tables = Zobrist.captureTables();
      try {
        Lizzie.board = null;
        Lizzie.setPrimaryEngine(null);
        Lizzie.config = null;
        Lizzie.frame = null;
        var history = SGFParser.parseSgf(
            "(;SZ[5:7]KM[6.5]PB[흑]AW[aa:bb]AE[aa]PL[B]"
                + ";B[eg]C[edge]LZ[KataGo 45 12 2.5 1.25 0\n"
                + "move E1 visits 12 winrate 5500 pv E1 D2 C3 B4]"
                + "(;W[bg])(;W[]C[pass]))", true, OPTIONS);
        assertNotNull(history);
        assertEquals(19, Board.boardWidth);
        assertEquals(19, Board.boardHeight);
        assertSame(tables, Zobrist.captureTables());
        assertEquals(6.5, history.getGameInfo().getKomi());
        assertEquals("흑", history.getGameInfo().getPlayerBlack());
        var root = history.getStart().getData();
        assertEquals(35, root.stones.length);
        assertEquals(Stone.EMPTY, root.stones[0]);
        assertEquals(Stone.WHITE, root.stones[8]);
        var first = history.getStart().next().orElseThrow();
        assertEquals(Stone.BLACK, first.getData().stones[34]);
        assertEquals(3, first.getData().bestMoves.get(0).variation.size());
        assertEquals(2, first.numberOfChildren());
        assertTrue(first.getVariation(1).orElseThrow().getData().isPassNode());
        history.setHead(first);
        var before = history.getZobrist().clone();
        history.addExtraStone(3, 6, Stone.WHITE);
        assertEquals(Stone.WHITE, history.getStones()[27]);
        var expected = before.clone();
        expected.toggleStone(3, 6, Stone.WHITE);
        assertEquals(expected, history.getZobrist());
        // A later live-board initialization must not rebind existing hashes or their clones.
        Zobrist.init();
        var emptyHash = history.getZobrist();
        for (int x = 0; x < 5; x++) {
          for (int y = 0; y < 7; y++) {
            emptyHash.toggleStone(x, y, history.getStones()[x * 7 + y]);
          }
        }
        assertEquals(new Zobrist(0), emptyHash);
      } finally {
        Zobrist.restoreTables(tables);
        Lizzie.board = board;
        Lizzie.setPrimaryEngine(engine);
        Lizzie.config = config;
        Lizzie.frame = frame;
      }
    }
  }

  @Test
  void differentBoardSizesCanBuildConcurrentlyWithoutChangingLiveGeometry() throws Exception {
    try (var harness = RulesLayerTestHarness.open(19)) {
      var tables = Zobrist.captureTables();
      var start = new CountDownLatch(1);
      var executor = Executors.newFixedThreadPool(4);
      try {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int width : List.of(5, 9, 13, 17)) {
          tasks.add(() -> {
            assertTrue(start.await(10, TimeUnit.SECONDS));
            String edge = "" + (char) ('a' + width - 1) + 'g';
            for (int repeat = 0; repeat < 30; repeat++) {
              var history = SGFParser.parseSgf(
                  "(;SZ[" + width + ":7]AB[aa:bb];B[" + edge + "](;W[ag])(;W[]))",
                  true, OPTIONS);
              assertEquals(width * 7, history.getStones().length);
              var move = history.getStart().next().orElseThrow();
              assertArrayEquals(new int[] {width - 1, 6}, move.getData().lastMove.orElseThrow());
              assertEquals(Stone.BLACK, move.getData().stones[width * 7 - 1]);
              assertEquals(2, move.numberOfChildren());
              assertEquals(19, Board.boardWidth);
              assertEquals(19, Board.boardHeight);
              assertSame(tables, Zobrist.captureTables());
            }
            return null;
          });
        }
        var futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        for (var future : futures) future.get(20, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
  }
}
