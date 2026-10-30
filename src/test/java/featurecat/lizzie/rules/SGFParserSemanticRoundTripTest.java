package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SGFParser model-semantic tests and parse → write → parse round-trip. Complements existing
 * root-setup / node-kind SGF coverage; asserts board/history meaning rather than SGF bytes.
 */
class SGFParserSemanticRoundTripTest {
  private static final int SIZE = 5;
  @TempDir Path tempDirectory;

  @ParameterizedTest
  @ValueSource(strings = {
      "(;SZ[5]AB[aa]AW[ee](;W[cc];B[])(;B[bb];W[]))",
      "(;SZ[5]PL[W];AB[aa]AW[ee](;W[cc];B[])(;B[bb];W[]))"
  })
  void branchEditsAndDirectTurnChangePersistCurrentInitialState(String sgf) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      assertTrue(SGFParser.loadFromStringforedit(sgf));
      Board board = Lizzie.board;
      BoardHistoryNode initial = board.getHistory().getStart();
      if (initial.next().orElseThrow().getData().isSnapshotNode()) {
        initial = initial.next().orElseThrow();
      }
      assertFalse(initial.getData().blackToPlay);
      board.moveToAnyPosition(initial.getVariation(1).orElseThrow());
      assertFalse(initial.getData().blackToPlay, "browsing does not infer from the side branch");
      board.moveBranchUp();
      assertFalse(initial.getData().blackToPlay, "promotion does not reinterpret the initial state");
      assertTreeSemanticsEqual(board.getHistory(),
          SGFParser.parseSgf(SGFParser.saveToString(false), true));
      board.deleteMoveNoHint();
      assertFalse(initial.getData().blackToPlay, "deleting the first action preserves the turn");
      board.moveToAnyPosition(initial);
      Lizzie.leelaz.canAddPlayer = true;
      int children = initial.numberOfChildren();
      board.changeNextTurn();
      assertTrue(initial.getData().blackToPlay);
      assertEquals(children, initial.numberOfChildren());
      assertEquals(0, initial.getData().moveNumber);
      BoardHistoryList expected = board.getHistory();
      for (int round = 0; round < 2; round++) {
        BoardHistoryList reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true);
        assertTreeSemanticsEqual(expected, reopened);
        board.setHistory(reopened);
        expected = reopened;
      }
    }
  }

  @Test
  void deletingWhiteFirstActionReplaysBlackPassWithoutChangingInitialTurn() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      assertTrue(SGFParser.loadFromStringforedit("(;SZ[5];AB[aa]AW[ee];W[cc];B[])"));
      Board board = Lizzie.board;
      board.nextMove(false);
      board.nextMove(false);
      board.nextMove(false);
      board.editMove(new int[] {2, 2}, false, true);
      BoardHistoryNode setup = board.getHistory().getStart().next().orElseThrow();
      assertTrue(setup.getData().isSnapshotNode());
      assertFalse(setup.getData().blackToPlay);
      assertEquals(0, setup.getData().moveNumber);
      BoardData pass = setup.next().orElseThrow().getData();
      assertTrue(pass.isPassNode());
      assertEquals(Stone.BLACK, pass.lastMoveColor);
      assertEquals(1, pass.moveNumber);
      assertEquals(Stone.EMPTY, pass.stones[Board.getIndex(2, 2)]);
      for (int round = 0; round < 2; round++) {
        BoardHistoryList expected = board.getHistory();
        BoardHistoryList reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true);
        assertTreeSemanticsEqual(expected, reopened);
        board.setHistory(reopened);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "PL[B]", "PL[W]"})
  void editUndoRedoPreservesIndependentInitialSetupAndResolvedTurn(String player) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      assertTrue(SGFParser.loadFromStringforedit(
          "(;SZ[5]" + player + ";AB[aa]AW[ee];W[cc];B[])"));
      Board board = Lizzie.board;
      board.nextMove(false);
      board.nextMove(false);
      board.nextMove(false);
      BoardHistoryList expected = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      board.saveListForEdit();
      board.cleanedit();
      assertTreeSemanticsEqual(expected, board.getHistory());
      assertEquals(BoardNodeKind.PASS, board.getData().getNodeKind());
      board.reedit();
      assertTreeSemanticsEqual(expected, board.getHistory());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void dummyFirstBranchRetainsSelectedNodeAcrossDeleteUndoRedo(boolean deletePass)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      Board board = Lizzie.board;
      assertTrue(SGFParser.loadFromStringforedit("(;SZ[5];AB[aa]AW[ee]PL[W])"));
      board.nextMove(false);
      board.pass(Stone.BLACK, false, true);
      board.previousMove(false);
      board.getHistory().place(2, 2, Stone.WHITE, true);
      BoardHistoryNode white = board.getHistory().getCurrentHistoryNode();
      white.getData().comment = "real white move";
      white.getData().engineName = "KataGo";
      white.getData().setPlayouts(123);
      white.getData().analysisHeaderSlots = 3;
      board.getHistory().pass(Stone.BLACK);
      BoardHistoryNode pass = board.getHistory().getCurrentHistoryNode();
      assertFalse(pass.getData().dummy);
      assertEquals(BoardNodeKind.PASS, pass.getData().getNodeKind());
      assertEquals(2, pass.getData().moveNumber);
      board.moveToAnyPosition(deletePass ? pass : white);
      BoardHistoryList before = SGFParser.parseSgf(SGFParser.saveToString(false), true);

      board.deleteMoveNoHint();
      BoardHistoryList after = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      board.cleanedit();
      assertTreeSemanticsEqual(before, board.getHistory());
      assertDummySiblingAnalysis(board.getHistory(), true);
      BoardHistoryNode setup = board.getHistory().getStart().getVariation(0).orElseThrow();
      assertFalse(setup.getData().blackToPlay);
      assertTrue(setup.getVariation(0).orElseThrow().getData().dummy);
      BoardHistoryNode restoredWhite = setup.getVariation(1).orElseThrow();
      assertSame(deletePass ? restoredWhite.getVariation(0).orElseThrow() : restoredWhite,
          board.getHistory().getCurrentHistoryNode());

      board.reedit();
      assertTreeSemanticsEqual(after, board.getHistory());
      setup = board.getHistory().getStart().getVariation(0).orElseThrow();
      assertFalse(setup.getData().blackToPlay);
      assertSame(deletePass ? setup.getVariation(1).orElseThrow() : setup,
          board.getHistory().getCurrentHistoryNode());
      if (deletePass) assertDummySiblingAnalysis(board.getHistory(), true);
    }
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "(;SZ[5];W[cc])|false", "(;SZ[5];B[cc])|true",
      "(;SZ[5];W[])|false", "(;SZ[5];B[])|true",
      "(;SZ[5]AB[aa][ee];W[cc])|false",
      "(;SZ[5]AB[aa]AW[ee];W[cc])|false",
      "(;SZ[5]PL[B]AB[aa];W[cc])|true",
      "(;SZ[5]HA[2]AB[aa][ee];B[cc])|true",
      "(;SZ[5]HA[3]AB[aa][ee];W[cc])|false",
      "(;SZ[5]HA[2]AB[aa][ee])|false",
      "(;SZ[5]HA[3]AB[aa][ee])|true", "(;SZ[5]HA[2])|true",
      "(;SZ[5]AB[aa][ee])|true",
      "(;SZ[5](;W[cc])(;B[bb]))|false",
      "(;SZ[5](;B[cc])(;W[bb]))|true",
      "(;SZ[5];C[note]TR[aa];W[cc])|false",
      "(;SZ[5];AB[aa];W[cc])|true",
      "(;SZ[5]AB[aa];AE[aa];W[cc])|true",
      "(;SZ[5];PL[W];W[cc])|true"
  })
  void initialTurnUsesLocalFirstActionAfterExplicitPlayer(String sgf, boolean black)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList parsed = SGFParser.parseSgf(sgf, true);
      assertEquals(black, parsed.getStart().getData().blackToPlay, sgf);
      Lizzie.board.setHistory(parsed);
      BoardHistoryList first = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      assertTreeSemanticsEqual(parsed, first);
      assertEquals(black ? "B" : "W", first.getStart().getData().getProperty("PL"));
      Lizzie.board.setHistory(first);
      assertTreeSemanticsEqual(first, SGFParser.parseSgf(SGFParser.saveToString(false), true));
    }
  }

  @Test
  void explicitPlayerPersistsAcrossSetupUntilReplaced() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[5]PL[B];AB[aa][ee];PL[W];W[cc])", true);
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode setup = root.next().orElseThrow();
      BoardHistoryNode player = setup.next().orElseThrow();
      assertTrue(root.getData().blackToPlay);
      assertTrue(setup.getData().blackToPlay);
      assertFalse(player.getData().blackToPlay);
      assertEquals(0, setup.getData().moveNumber);
      assertEquals(Stone.WHITE, player.next().orElseThrow().getData().lastMoveColor);
    }
  }

  @Test
  void initialSetupEntrypointsAgreeAndDetachedDoesNotTouchLiveHistory() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String sgf = "(;SZ[5]HA[3];C[setup]AB[aa]AW[ee];W[];B[cc])";
      BoardHistoryList live = Lizzie.board.getHistory();
      BoardHistoryList expected = SGFParser.parseSgf(sgf, true);
      assertSame(live, Lizzie.board.getHistory());
      assertTrue(expected.getStart().getData().blackToPlay);
      assertFalse(expected.getStart().next().orElseThrow().getData().blackToPlay);
      assertTrue(SGFParser.loadFromString(sgf, false));
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      assertTrue(SGFParser.loadFromStringforedit(sgf));
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      Path file = tempDirectory.resolve("initial-turn.sgf");
      Files.writeString(file, sgf);
      assertTrue(SGFParser.load(file.toString(), false, false));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
    }
  }

  @Test
  void importedInitialBranchesResolveLocallyWithoutRejudgingExistingPosition() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[bb])", true);
      SGFParser.parseBranch(history, ";AB[aa];W[cc]");
      BoardHistoryNode setup = history.getStart().getVariation(1).orElseThrow();
      assertTrue(history.getStart().getData().blackToPlay);
      assertFalse(setup.getData().blackToPlay);
      assertEquals(Stone.WHITE, setup.next().orElseThrow().getData().lastMoveColor);
      BoardHistoryList inherited = SGFParser.parseSgf("(;SZ[5]PL[B])", true);
      SGFParser.parseBranch(inherited, ";AB[aa];W[cc]");
      assertTrue(inherited.getStart().next().orElseThrow().getData().blackToPlay);
      BoardHistoryList midgame = SGFParser.parseSgf("(;SZ[5];B[bb];W[dd])", true);
      midgame.next();
      midgame.next();
      SGFParser.parseBranch(midgame, ";AB[aa];W[cc]");
      BoardHistoryNode midSetup = midgame.getStart().next().orElseThrow()
          .next().orElseThrow().next().orElseThrow();
      assertTrue(midSetup.getData().blackToPlay);
    }
  }

  @Test
  void setupBoundariesResolveIndependentlyInEveryInitialVariation() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[5](;AB[aa];C[transparent];W[cc])(;AW[ee];B[bb]))", true);
      BoardHistoryNode root = history.getStart();
      assertTrue(root.getData().blackToPlay);
      assertFalse(root.getVariation(0).orElseThrow().getData().blackToPlay);
      assertTrue(root.getVariation(1).orElseThrow().getData().blackToPlay);
      BoardHistoryList removed = SGFParser.parseSgf(
          "(;SZ[5]AB[aa];AE[aa];W[cc])", true);
      assertTrue(removed.getStart().getData().blackToPlay);
      assertFalse(removed.getStart().next().orElseThrow().getData().blackToPlay);
    }
  }

  @Test
  void legacyEmptyNodeBeforeDummyRemainsReadable() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String sgf = "(;SZ[5](;;DD[true])(;W[]C[real]))";
      BoardHistoryList expected = SGFParser.parseSgf("(;SZ[5](;DD[true])(;W[]C[real]))", true);
      assertTreeSemanticsEqual(expected, SGFParser.parseSgf(sgf, true));
      assertTrue(SGFParser.loadFromString(sgf, false));
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      assertTrue(SGFParser.loadFromStringforedit(sgf));
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      BoardHistoryList branch = SGFParser.parseSgf("(;SZ[5])", true);
      SGFParser.parseBranch(branch, sgf.substring(sgf.indexOf('(', 1)));
      assertTreeSemanticsEqual(expected, branch);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ";AB[aa]AW[ee]"})
  void serializedDummySiblingPreservesRealMoveAndPassAcrossParsers(String setup) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      Board board = Lizzie.board;
      assertTrue(SGFParser.loadFromStringforedit("(;SZ[5]" + setup + ")"));
      if (!setup.isEmpty()) board.nextMove(false);
      board.pass(Stone.BLACK, false, true);
      board.previousMove(false);
      board.getHistory().place(2, 2, Stone.WHITE, true);
      board.getHistory().getData().comment = "real white move";
      board.getHistory().getData().engineName = "KataGo";
      board.getHistory().getData().setPlayouts(123);
      board.getHistory().getData().analysisHeaderSlots = 3;
      board.getHistory().pass(Stone.BLACK);
      BoardHistoryList expected = board.getHistory();
      String saved = SGFParser.saveToString(false);

      for (int round = 0; round < 2; round++) {
        BoardHistoryList detached = SGFParser.parseSgf(saved, true);
        assertSame(expected, board.getHistory(), "detached import leaves the live tree alone");
        assertTreeSemanticsEqual(expected, detached);
        assertDummySiblingAnalysis(detached, !setup.isEmpty());
        assertTrue(detached.getStart().getData().blackToPlay);
        assertTrue(SGFParser.loadFromString(saved, false));
        assertTreeSemanticsEqual(expected, board.getHistory());
        assertDummySiblingAnalysis(board.getHistory(), !setup.isEmpty());
        assertTrue(SGFParser.loadFromStringforedit(saved));
        assertTreeSemanticsEqual(expected, board.getHistory());
        assertDummySiblingAnalysis(board.getHistory(), !setup.isEmpty());
        expected = board.getHistory();
        saved = SGFParser.saveToString(false);
      }

      BoardHistoryList branch = SGFParser.parseSgf("(;SZ[5]" + setup + ")", true);
      if (!setup.isEmpty()) branch.next();
      SGFParser.parseBranch(branch, saved.substring(saved.indexOf('(', 1)));
      assertTreeSemanticsEqual(expected, branch);
    }
  }

  private static void assertDummySiblingAnalysis(BoardHistoryList history, boolean independentSetup) {
    BoardHistoryNode parent = history.getStart();
    if (independentSetup) parent = parent.getVariation(0).orElseThrow();
    BoardData white = parent.getVariation(1).orElseThrow().getData();
    assertEquals("KataGo", white.engineName);
    assertEquals(123, white.getPlayouts());
  }

  @Test
  void atomicAutoSavePreservesDummySiblingsSetupTurnsAndAnalysis() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Lizzie.config.enableLizzieCache = true;
      String sgf =
          "(;SZ[5]HA[2]AB[aa][ee]PL[W]KM[0.5]C[root]"
              + "LZOP[KataGo 40 10 2 1 0 rootVisits=10\n"
              + "move C3 visits 10 winrate 6000 pv C3]"
              + "(;DD[true]C[placeholder]LZ[KataGo 45 123 2 1 0 rootVisits=123])"
              + "(;W[bb]C[selected]LZ[KataGo 30 100 3 1 0 rootVisits=100\n"
              + "move D4 visits 50 winrate 7000 pv D4 C4]"
              + ";AE[aa]AB[cc]PL[B]C[setup]LZ[KataGo 35 200 4 1 0 rootVisits=200]"
              + ";B[dd]C[end]LZ[KataGo 20 300 5 1 0 rootVisits=300])"
              + "(;W[]C[pass sibling]LZ[KataGo 25 250 6 1 0 rootVisits=250]))";
      assertTrue(SGFParser.loadFromString(sgf, false));
      BoardHistoryList expected = env.board().getHistory();
      Path saved = tempDirectory.resolve("combined-auto-save.sgf");
      Files.writeString(saved, "(;SZ[5]C[previous complete record])");

      for (int round = 0; round < 2; round++) {
        BoardHistoryList live = env.board().getHistory();
        BoardHistoryNode selected =
            live.getStart().getVariation(1).orElseThrow().next().orElseThrow()
                .next().orElseThrow();
        live.setHead(selected);
        SGFParser.save(env.board(), saved.toString(), true);
        assertSame(live, env.board().getHistory());
        assertSame(selected, live.getCurrentHistoryNode(), "detached save preserves the live cursor");
        assertTrue(Files.readString(saved).contains("AP[KG-next:"));
        assertTrue(SGFParser.load(saved.toString(), false, false));
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        BoardHistoryList reopened = env.board().getHistory();
        assertTreeSemanticsEqual(expected, reopened);
        BoardHistoryNode root = reopened.getStart();
        assertEquals(0.5, reopened.getGameInfo().getKomi(), 0.0001);
        assertEquals(3, root.numberOfChildren());
        assertFalse(root.getData().blackToPlay);
        assertEquals(10, root.getData().getPlayouts());
        BoardData dummy = root.getVariation(0).orElseThrow().getData();
        assertTrue(dummy.dummy);
        assertEquals("placeholder", dummy.comment);
        assertEquals(123, dummy.getPlayouts());
        BoardHistoryNode white = root.getVariation(1).orElseThrow();
        assertEquals(Stone.WHITE, white.getData().lastMoveColor);
        assertFalse(white.getData().dummy);
        assertEquals(100, white.getData().rootVisits);
        assertEquals(List.of("D4", "C4"), white.getData().bestMoves.get(0).variation);
        BoardHistoryNode setup = white.next().orElseThrow();
        assertTrue(setup.getData().isSnapshotNode());
        assertTrue(setup.getData().blackToPlay);
        assertEquals("B", setup.getData().getProperty("PL"));
        assertEquals(Stone.EMPTY, setup.getData().stones[Board.getIndex(0, 0)]);
        assertEquals(Stone.BLACK, setup.getData().stones[Board.getIndex(2, 2)]);
        assertEquals(200, setup.getData().getPlayouts());
        BoardData end = setup.next().orElseThrow().getData();
        assertEquals(Stone.BLACK, end.lastMoveColor);
        assertFalse(end.blackToPlay);
        assertEquals(300, end.getPlayouts());
        BoardData pass = root.getVariation(2).orElseThrow().getData();
        assertTrue(pass.isPassNode());
        assertFalse(pass.dummy);
        assertEquals(Stone.WHITE, pass.lastMoveColor);
        assertEquals(250, pass.getPlayouts());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"W[]", "W[cc]", "B[]", "B[cc]"})
  void dummyPassCannotSupplyInitialTurnEvidence(String action) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String sgf = "(;SZ[5];DD[true]" + action + ")";
      BoardHistoryList detached = SGFParser.parseSgf(sgf, true);
      assertTrue(detached.getStart().getData().blackToPlay);
      assertTrue(detached.getStart().getVariation(0).orElseThrow().getData().dummy);
      assertEquals(2, detached.getStart().numberOfChildren());
      BoardData real = detached.getStart().getVariation(1).orElseThrow().getData();
      assertFalse(real.dummy);
      assertEquals(action.startsWith("B") ? Stone.BLACK : Stone.WHITE, real.lastMoveColor);
      assertEquals(action.endsWith("[]") ? BoardNodeKind.PASS : BoardNodeKind.MOVE,
          real.getNodeKind());
      assertEquals(1, real.moveNumber);
      assertTrue(SGFParser.loadFromString(sgf, false));
      assertTreeSemanticsEqual(detached, Lizzie.board.getHistory());
      assertTrue(SGFParser.loadFromStringforedit(sgf));
      assertTreeSemanticsEqual(detached, Lizzie.board.getHistory());
      BoardHistoryList branch = SGFParser.parseSgf("(;SZ[5])", true);
      SGFParser.parseBranch(branch, ";DD[true]" + action);
      assertTreeSemanticsEqual(detached, branch);
      for (int round = 0; round < 2; round++) {
        BoardHistoryList reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true);
        assertTreeSemanticsEqual(detached, reopened);
        Lizzie.board.setHistory(reopened);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"W[cc], LZ, false", "W[], LZ, false", "B[cc], LZOP, false",
      "B[], LZOP, false", "W[cc], LZ, true", "W[], LZ, true",
      "B[cc], LZOP, true", "B[], LZOP, true"})
  void sameNodeDummyPayloadBelongsToRealActionRegardlessOfPropertyOrder(
      String action, String analysisTag, boolean rootNode) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      String payload = "C[note]LB[aa:A]" + analysisTag
          + "[KataGo 45 12.3k 2.5 1.2 0 rootVisits=12345\n"
          + "move D4 visits 321 winrate 5500 pv D4]" + analysisTag + "2"
          + "[Second 55 23.5k -2.5 1.3 0 rootVisits=23456\n"
          + "move E5 visits 432 winrate 4500 pv E5]";
      for (String node : List.of("DD[true]" + payload + action,
          payload + "DD[true]" + action, "DD[true]" + action + payload)) {
        String sgf = "(;SZ[5]" + (rootNode ? "" : ";") + node + ")";
        for (String entry : List.of("detached", "live", "edit", "branch", "file")) {
          BoardHistoryList history;
          if (entry.equals("detached")) {
            history = SGFParser.parseSgf(sgf, true);
          } else if (entry.equals("branch")) {
            history = SGFParser.parseSgf("(;SZ[5])", true);
            SGFParser.parseBranch(history, ";" + node);
          } else {
            if (entry.equals("live")) assertTrue(SGFParser.loadFromString(sgf, false));
            else if (entry.equals("edit")) assertTrue(SGFParser.loadFromStringforedit(sgf));
            else {
              Path input = tempDirectory.resolve("dummy-payload.sgf");
              Files.writeString(input, sgf);
              assertTrue(SGFParser.load(input.toString(), false, false));
              javax.swing.SwingUtilities.invokeAndWait(() -> {});
            }
            history = Lizzie.board.getHistory();
          }
          assertSameNodeDummyPayload(history, action, entry + ": " + node);
          Lizzie.board.setHistory(history);
          for (int round = 0; round < 2; round++) {
            Path saved = tempDirectory.resolve("dummy-payload-saved.sgf");
            Files.writeString(saved, SGFParser.saveToString(false));
            assertTrue(SGFParser.load(saved.toString(), false, false));
            javax.swing.SwingUtilities.invokeAndWait(() -> {});
            assertSameNodeDummyPayload(Lizzie.board.getHistory(), action,
                entry + " reopen " + round + ": " + node);
          }
        }
      }
    }
  }

  private static void assertSameNodeDummyPayload(
      BoardHistoryList history, String action, String context) {
    BoardHistoryNode root = history.getStart();
    assertEquals("", root.getData().comment, context);
    assertNull(root.getData().getProperty("LB"), context);
    assertEquals(0, root.getData().getPlayouts(), context);
    assertEquals(0, root.getData().getPlayouts2(), context);
    assertEquals(2, root.numberOfChildren(), context);
    BoardHistoryNode dummy = root.getVariation(0).orElseThrow();
    assertTrue(dummy.getData().dummy, context);
    assertEquals(0, dummy.numberOfChildren(), context);
    assertEquals("", dummy.getData().comment, context);
    assertNull(dummy.getData().getProperty("LB"), context);
    assertEquals(0, dummy.getData().getPlayouts(), context);
    assertEquals(0, dummy.getData().getPlayouts2(), context);
    assertTrue(dummy.getData().bestMoves.isEmpty(), context);
    assertTrue(dummy.getData().bestMoves2.isEmpty(), context);
    BoardData real = root.getVariation(1).orElseThrow().getData();
    assertFalse(real.dummy, context);
    assertEquals(action.endsWith("[]") ? BoardNodeKind.PASS : BoardNodeKind.MOVE,
        real.getNodeKind(), context);
    assertEquals(action.startsWith("B") ? Stone.BLACK : Stone.WHITE, real.lastMoveColor, context);
    assertEquals(1, real.moveNumber, context);
    assertEquals("note", real.comment, context);
    assertEquals("aa:A", real.getProperty("LB"), context);
    assertEquals(12345, real.getPlayouts(), context);
    assertEquals(23456, real.getPlayouts2(), context);
    assertEquals("KataGo", real.engineName, context);
    assertEquals("Second", real.engineName2, context);
    assertEquals(321, real.bestMoves.get(0).playouts, context);
    assertEquals(List.of("D4"), real.bestMoves.get(0).variation, context);
    assertEquals(432, real.bestMoves2.get(0).playouts, context);
    assertEquals(List.of("E5"), real.bestMoves2.get(0).variation, context);
  }

  @ParameterizedTest
  @CsvSource({"true, false", "true, true", "false, false", "false, true"})
  void rootPayloadScanRespectsEscapesSetupAndNodeBoundaries(
      boolean sameNodeDummy, boolean setup) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      String comment = "note];(;DD[true]W[cc])\\tail";
      String sgf = "(;SZ[5]" + (setup ? "AB[ee]PL[B]" : "")
          + "C[note\\];(;DD[true\\]W[cc\\])\\\\tail]LB[aa:A]"
          + "LZ[KataGo 45 123]LZ2[Second 55 234]"
          + (sameNodeDummy ? "" : ";") + "DD[true]W[cc])";
      for (String entry : List.of("detached", "live", "edit", "file")) {
        if (entry.equals("detached")) Lizzie.board.setHistory(SGFParser.parseSgf(sgf, true));
        else if (entry.equals("live")) assertTrue(SGFParser.loadFromString(sgf, false));
        else if (entry.equals("edit")) assertTrue(SGFParser.loadFromStringforedit(sgf));
        else {
          Path input = tempDirectory.resolve("escaped-root.sgf");
          Files.writeString(input, sgf);
          assertTrue(SGFParser.load(input.toString(), false, false));
          javax.swing.SwingUtilities.invokeAndWait(() -> {});
        }
        for (int round = 0; round < 3; round++) {
          BoardHistoryNode root = Lizzie.board.getHistory().getStart();
          assertEquals(2, root.numberOfChildren(), entry);
          assertTrue(root.getData().isSnapshotNode());
          assertEquals(0, root.getData().moveNumber);
          assertTrue(root.getData().blackToPlay);
          assertEquals(setup ? Stone.BLACK : Stone.EMPTY,
              root.getData().stones[Board.getIndex(4, 4)]);
          BoardData dummy = root.getVariation(0).orElseThrow().getData();
          assertTrue(dummy.dummy);
          BoardData real = root.getVariation(1).orElseThrow().getData();
          assertEquals(Stone.WHITE, real.lastMoveColor);
          assertTrue(real.isMoveNode());
          assertEquals(1, real.moveNumber);
          BoardData owner = sameNodeDummy ? real : root.getData();
          BoardData other = sameNodeDummy ? root.getData() : real;
          assertEquals(comment, owner.comment, entry);
          assertEquals("aa:A", owner.getProperty("LB"), entry);
          assertEquals(123, owner.getPlayouts(), entry);
          assertEquals(234, owner.getPlayouts2(), entry);
          for (BoardData empty : List.of(dummy, other)) {
            assertEquals("", empty.comment, entry);
            assertNull(empty.getProperty("LB"), entry);
            assertEquals(0, empty.getPlayouts(), entry);
            assertEquals(0, empty.getPlayouts2(), entry);
          }
          if (round < 2) {
            assertTrue(SGFParser.loadFromString(SGFParser.saveToString(false), false));
          }
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP"})
  void standaloneDummyKeepsPayloadAcrossTwoSavedFileReopens(String analysisTag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      String sgf = "(;SZ[5]C[parent]LB[bb:P](;DD[true]C[placeholder]LB[aa:A]"
          + analysisTag + "[KataGo 45 123]" + analysisTag + "2[Second 55 234])"
          + "(;W[]C[sibling]LB[cc:S]))";
      assertTrue(SGFParser.loadFromString(sgf, false));
      for (int round = 0; round < 3; round++) {
        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertEquals("parent", root.getData().comment);
        assertEquals("bb:P", root.getData().getProperty("LB"));
        assertEquals(0, root.getData().getPlayouts());
        assertEquals(0, root.getData().getPlayouts2());
        assertEquals(2, root.numberOfChildren());
        BoardData dummy = root.getVariation(0).orElseThrow().getData();
        assertTrue(dummy.dummy);
        assertEquals("placeholder", dummy.comment);
        assertEquals("aa:A", dummy.getProperty("LB"));
        assertEquals(123, dummy.getPlayouts());
        assertEquals(234, dummy.getPlayouts2());
        assertEquals("KataGo", dummy.engineName);
        assertEquals("Second", dummy.engineName2);
        BoardData sibling = root.getVariation(1).orElseThrow().getData();
        assertFalse(sibling.dummy);
        assertTrue(sibling.isPassNode());
        assertEquals(Stone.WHITE, sibling.lastMoveColor);
        assertEquals(1, sibling.moveNumber);
        assertEquals("sibling", sibling.comment);
        assertEquals("cc:S", sibling.getProperty("LB"));
        assertEquals(0, sibling.getPlayouts());
        assertEquals(0, sibling.getPlayouts2());
        if (round < 2) {
          Path saved = tempDirectory.resolve("standalone-dummy.sgf");
          Files.writeString(saved, SGFParser.saveToString(false));
          assertTrue(SGFParser.load(saved.toString(), false, false));
          javax.swing.SwingUtilities.invokeAndWait(() -> {});
        }
      }
    }
  }

  @Test
  void dummyPayloadBufferStopsAtNodeAndVariationBoundaries() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String branches = "(;DD[true]C[placeholder]LZ[KataGo 45 123])"
          + "(;DD[true]C[real]LZ[KataGo 55 345]W[cc];B[aa]C[next])"
          + "(;W[dd]C[side])";
      String sgf = "(;SZ[5]" + branches + ")";
      for (String entry : List.of("detached", "live", "edit", "branch")) {
        BoardHistoryList history;
        if (entry.equals("detached")) history = SGFParser.parseSgf(sgf, true);
        else if (entry.equals("branch")) {
          history = SGFParser.parseSgf("(;SZ[5])", true);
          SGFParser.parseBranch(history, sgf.substring(sgf.indexOf("(;DD")));
        } else {
          if (entry.equals("live")) assertTrue(SGFParser.loadFromString(sgf, false));
          else assertTrue(SGFParser.loadFromStringforedit(sgf));
          history = Lizzie.board.getHistory();
        }
        BoardHistoryNode root = history.getStart();
        assertEquals(4, root.numberOfChildren(), entry);
        BoardData standaloneDummy = root.getVariation(0).orElseThrow().getData();
        assertTrue(standaloneDummy.dummy);
        assertEquals("placeholder", standaloneDummy.comment);
        assertEquals(123, standaloneDummy.getPlayouts());
        BoardData splitDummy = root.getVariation(1).orElseThrow().getData();
        assertTrue(splitDummy.dummy);
        assertEquals("", splitDummy.comment);
        assertEquals(0, splitDummy.getPlayouts());
        BoardHistoryNode real = root.getVariation(2).orElseThrow();
        assertEquals("real", real.getData().comment);
        assertEquals(345, real.getData().getPlayouts());
        assertEquals("next", real.next().orElseThrow().getData().comment);
        assertEquals(0, real.next().orElseThrow().getData().getPlayouts());
        BoardData side = root.getVariation(3).orElseThrow().getData();
        assertFalse(side.dummy);
        assertEquals("side", side.comment);
        assertEquals(0, side.getPlayouts());
      }
    }
  }

  @Test
  void completedHandicapDefaultDoesNotRequireEngineRestoreEligibility() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList staged = SGFParser.parseSgf("(;SZ[5]HA[2];AB[aa];AB[ee])", true);
      BoardHistoryNode partial = staged.getStart().next().orElseThrow();
      BoardHistoryNode complete = partial.next().orElseThrow();
      assertTrue(partial.getData().blackToPlay);
      assertFalse(complete.getData().blackToPlay);
      assertFalse(InitialHandicapSetup.isInitialHandicap(complete, 2, SIZE, SIZE));
      BoardHistoryList invalidPl = SGFParser.parseSgf(
          "(;SZ[5]HA[2]PL[invalid]AB[aa][ee])", true);
      assertFalse(invalidPl.getStart().getData().blackToPlay);
    }
  }

  @ParameterizedTest
  @CsvSource({"file, true", "file, false", "string, true", "string, false"})
  void handicapImportPreservesSavedAnalysisUntilNewStreamExceedsItsVisits(
      String entrypoint, boolean readKomi) throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Lizzie.config.readKomi = readKomi;
      Lizzie.config.enableLizzieCache = true;
      String sgf =
          "(;SZ[5]HA[2]AB[aa][ee]PL[W]KM[0.5]"
              + "LZOP[b11 99.8 392 -18.1 13.6 0 rootVisits=392\n"
              + "move C3 visits 392 winrate 20 scoreMean -18.1 pv C3]"
              + ";W[bb];B[cc]LZ[b11 99.8 102k -18.1 13.9 0 rootVisits=101709\n"
              + "move D4 visits 2638 winrate 20 scoreMean -18.1 pv D4 C4])";
      if (entrypoint.equals("file")) {
        Path input = tempDirectory.resolve("handicap-analysis.sgf");
        Files.writeString(input, sgf);
        assertTrue(SGFParser.load(input.toString(), false, false));
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
      } else {
        assertTrue(SGFParser.loadFromString(sgf, false));
      }
      BoardHistoryList history = env.board().getHistory();
      assertEquals(0.5, history.getGameInfo().getKomi(), 0.0001);
      assertEquals(392, history.getStart().getData().getPlayouts());
      assertTrue(env.board().nextMove(false));
      assertTrue(env.board().nextMove(false));
      BoardData data = env.board().getData();
      assertEquals(2, data.moveNumber);
      assertEquals(101709, data.getPlayouts());
      assertEquals(101709, data.rootVisits);
      assertEquals("b11", data.engineName);
      assertEquals(0.2, data.winrate, 0.0001);
      assertEquals(-18.1, data.scoreMean, 0.0001);
      assertEquals(List.of("D4", "C4"), data.bestMoves.get(0).variation);

      BoardHistoryList saved = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      assertEquals(101709,
          saved.getStart().next().orElseThrow().next().orElseThrow().getData().getPlayouts());

      MoveData incoming = new MoveData();
      incoming.coordinate = "C4";
      incoming.isKataData = true;
      incoming.winrate = 25;
      incoming.scoreMean = -5;
      Object newStream = new Object();
      for (int visits : new int[] {1000, 101709, 101710}) {
        incoming.playouts = visits;
        BoardData.AnalysisAdoption adoption = data.adoptOrdinaryAnalysis(
            new ArrayList<>(List.of(incoming)), "b11", Lizzie.leelaz, visits, visits,
            null, newStream, false, false);
        boolean deeper = visits > 101709;
        assertEquals(deeper ? BoardData.AnalysisAdoption.FULL : BoardData.AnalysisAdoption.REJECTED,
            adoption);
        assertEquals(deeper ? visits : 101709, data.getPlayouts());
        assertEquals(deeper ? 25 : 0.2, data.winrate, 0.0001);
        assertEquals(deeper ? -5 : -18.1, data.scoreMean, 0.0001);
        assertEquals(deeper ? "C4" : "D4", data.bestMoves.get(0).coordinate);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"\n", "\\n", "\\r\\n"})
  void detachedAndLiveImportsKeepResultSeparateFromComments(String newline) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String sgf = "(;SZ[5]RE[B+R]" + newline + ";B[aa](;W[bb])(;W[cc]))";
      BoardHistoryList live = Lizzie.board.getHistory();
      live.getGameInfo().setResult("W+R");
      RulesLayerTestHarness.TrackingFrame frame =
          (RulesLayerTestHarness.TrackingFrame) Lizzie.frame;
      int publications = frame.resultPublications;
      var titleField = featurecat.lizzie.gui.LizzieFrame.class.getDeclaredField("resultTitle");
      titleField.setAccessible(true);
      Object title = titleField.get(frame);
      BoardHistoryList detached = SGFParser.parseSgf(sgf, true);
      assertSame(live, Lizzie.board.getHistory());
      assertEquals("W+R", live.getGameInfo().getResult());
      assertEquals(title, titleField.get(frame));
      assertEquals(publications, frame.resultPublications);
      assertTrue(SGFParser.loadFromString(sgf, false));
      assertTreeSemanticsEqual(Lizzie.board.getHistory(), detached);
      assertEquals("B+R", detached.getGameInfo().getResult());
      Lizzie.board.setHistory(detached);
      assertTrue(SGFParser.loadFromString(SGFParser.saveToString(false), false));
      assertEquals("B+R", Lizzie.board.getHistory().getGameInfo().getResult());
      assertTreeSemanticsEqual(detached, Lizzie.board.getHistory());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "(;SZ[5]\\r;B[aa])", "(;SZ[5]\\\\n;B[aa])",
      "(;SZ[5]K\\nM[6.5];B[aa])", "(;SZ[5]K\\n\\nM[6.5];B[aa])",
      "(;SZ[5]\\n;B[aa](;W[bb])", "(;SZ[5]\\n;B[aa]C[unfinished)",
      "(;SZ[5]\\n;B[aa])\\n(;SZ[5]"
  })
  void ambiguousStructuralEscapesCannotReplaceLoadedHistory(String invalid) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      assertTrue(SGFParser.loadFromString("(;SZ[5];B[cc])", false));
      BoardHistoryList original = Lizzie.board.getHistory();
      BoardHistoryNode current = original.getCurrentHistoryNode();
      assertNull(SGFParser.parseSgf(invalid, true));
      assertFalse(SGFParser.loadFromString(invalid, false));
      assertSame(original, Lizzie.board.getHistory());
      assertFalse(SGFParser.loadFromStringforedit(invalid));
      assertSame(original, Lizzie.board.getHistory());
      assertSame(current, original.getCurrentHistoryNode());
      Path file = tempDirectory.resolve("ambiguous.sgf");
      Files.writeString(file, invalid);
      assertFalse(SGFParser.load(file.toString(), false, false));
      assertSame(original, Lizzie.board.getHistory());
      assertSame(current, original.getCurrentHistoryNode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"\\n", "\\r\\n"})
  void structuralNewlinesPreserveMovesBranchesAndPropertyValues(String newline) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String normal =
          "(;SZ[5]\nKM[6.5]XX[keep\\\\n]C[中文 \\] \\\\ slash\nsecond line]"
              + "\n;B[aa]\n(;W[bb]C[main];B[cc])\n(;W[dd]C[variation];B[ee]))";
      String escaped = normal.replace("\n;", newline + ";")
          .replace("\nKM", newline + "KM").replace("\n(", newline + "(");
      BoardHistoryList expected = SGFParser.parseSgf(normal, true);
      BoardHistoryList live = Lizzie.board.getHistory();
      BoardHistoryList actual = SGFParser.parseSgf(escaped, true);
      assertSame(live, Lizzie.board.getHistory());
      assertTreeSemanticsEqual(expected, actual);
      assertEquals(expected.getStart().getData().getProperty("XX"),
          actual.getStart().getData().getProperty("XX"));
      assertEquals(List.of("BLACK 0,0", "WHITE 1,1", "BLACK 2,2"), mainlineMoves(actual));
      assertTrue(SGFParser.loadFromString(escaped, false));
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      assertTrue(SGFParser.loadFromStringforedit(escaped));
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      Path input = tempDirectory.resolve("escaped.sgf");
      Files.writeString(input, escaped);
      assertTrue(SGFParser.load(input.toString(), false, false));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      assertEquals(escaped, Files.readString(input));
      Path saved = tempDirectory.resolve("saved.sgf");
      Files.writeString(saved, SGFParser.saveToString(false));
      assertTrue(SGFParser.load(saved.toString(), false, false));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      assertTreeSemanticsEqual(expected, SGFParser.parseSgf(SGFParser.saveToString(false), true));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void exactRootAndSparseCandidateMetricsSurviveAdoptAndRepeatedSave(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      boolean secondary = tag.endsWith("2");
      String sgf = "(;SZ[5]" + tag
          + "[KataGo 45 12.3k 2.5 1.2 0 rootVisits=12345\n"
          + "move C3 visits 50000 winrate 5500 order 10 edgeVisits 0 pv C3 D4])";
      for (int round = 0; round < 3; round++) {
        BoardHistoryList history = SGFParser.parseSgf(sgf, true);
        Lizzie.board.setHistory(history);
        BoardData data = history.getStart().getData();
        assertEquals(12345, secondary ? data.getPlayouts2() : data.getPlayouts());
        assertEquals(12345, secondary ? data.rootVisits2 : data.rootVisits);
        var move = (secondary ? data.bestMoves2 : data.bestMoves).get(0);
        assertEquals(50000, move.playouts);
        assertEquals(10, move.order);
        assertEquals(0, move.edgeVisits);
        assertEquals(List.of("C3", "D4"), move.variation);
        BoardData copy = data.clone();
        assertEquals(12345, secondary ? copy.rootVisits2 : copy.rootVisits);
        sgf = SGFParser.saveToString(false);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"0,0", "12345,12345", "000123,123", "2147483647,2147483647",
      "12.3k,12300", "1.25M,1250000", "'1,234',1234", "+25,25", "-25,0",
      "12.6,13", "visits:123,123", "invalid,0", "１２３,0",
      // Preserve the existing overflow fallback in this performance-only change.
      "2147483648,-2147483648"})
  void analysisNumbersKeepIntegerAndLegacyImportSemantics(String text, int expected)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      for (String tag : List.of("LZ", "LZOP", "LZ2", "LZOP2")) {
        boolean secondary = tag.endsWith("2");
        BoardData header = SGFParser.parseSgf(
            "(;SZ[5]" + tag + "[KataGo 45 " + text + "])", true).getStart().getData();
        assertEquals(expected, secondary ? header.getPlayouts2() : header.getPlayouts(), tag);
        BoardData data = SGFParser.parseSgf("(;SZ[5]" + tag
            + "[KataGo 45 100\nmove C3 visits " + text + " winrate " + text
            + " pv C3 D4])", true).getStart().getData();
        MoveData move = (secondary ? data.bestMoves2 : data.bestMoves).get(0);
        assertEquals(expected, move.playouts, tag);
        assertEquals(expected / 100.0, move.winrate, tag);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void analysisWhitespaceKeepsCandidatesPvVisitsAndOwnership(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      Lizzie.config.limitMaxSuggestion = 0;
      Lizzie.config.limitBranchLength = 2;
      boolean secondary = tag.endsWith("2");
      BoardData data = SGFParser.parseSgf("(;SZ[5]" + tag
          + "[KataGo\t45   100\t2.5 1.2 0\n"
          + "move\tC3   visits\t100\fwinrate\t5500 prior\u000B1000 pv C3\tD4 E5 pvVisits 100\t90 80"
          + " info move D4 visits 50 winrate 4500 pv D4 C3"
          + " ownership\t0.25  -0.5\t1.0 trailing])", true).getStart().getData();
      List<MoveData> moves = secondary ? data.bestMoves2 : data.bestMoves;
      assertEquals(2, moves.size());
      assertEquals("C3", moves.get(0).coordinate);
      assertEquals(100, moves.get(0).playouts);
      assertEquals(55, moves.get(0).winrate);
      assertEquals(10, moves.get(0).policy);
      assertEquals(1.2, moves.get(0).scoreStdev);
      assertEquals(List.of("C3", "D4"), moves.get(0).variation);
      assertEquals(List.of("100", "90", "80"), moves.get(0).pvVisits);
      assertEquals("D4", moves.get(1).coordinate);
      assertEquals(50, moves.get(1).playouts);
      assertEquals(List.of(0.25, -0.5, 1.0), secondary ? data.estimateArray2 : data.estimateArray);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", "\r\n", "\f", "\u000B", " \t\n\r\f\u000B"})
  void branchSuffixWhitespacePreservesBothVariationsAcrossEntryPoints(String gap) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String branches = "(;W[bb]C[first])(;W[cc]C[second])" + gap + ")";
      String sgf = "(;SZ[5];B[aa]" + branches;
      BoardHistoryList expected = SGFParser.parseSgf(sgf, true);
      BoardHistoryNode fork = expected.getStart().next().orElseThrow();
      assertEquals(2, fork.numberOfChildren());
      assertEquals("first", fork.getVariation(0).orElseThrow().getData().comment);
      assertEquals("second", fork.getVariation(1).orElseThrow().getData().comment);
      assertTrue(SGFParser.loadFromString(sgf, false));
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      BoardHistoryList imported = SGFParser.parseSgf("(;SZ[5];B[aa])", true);
      imported.setHead(imported.getEnd());
      SGFParser.parseBranch(imported, branches);
      assertTreeSemanticsEqual(expected, imported);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void headerOnlyExactRootAndDowngradedLegacyRemainDistinct(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      boolean secondary = tag.endsWith("2");
      String sgf = "(;SZ[5]" + tag + "[KataGo 45 12.3k 2.5 1.2 0 rootVisits=12345])";
      for (int round = 0; round < 2; round++) {
        BoardHistoryList history = SGFParser.parseSgf(sgf, true);
        Lizzie.board.setHistory(history);
        BoardData data = history.getStart().getData();
        assertEquals(12345, secondary ? data.getPlayouts2() : data.getPlayouts());
        assertEquals(12345, secondary ? data.rootVisits2 : data.rootVisits);
        sgf = SGFParser.saveToString(false);
      }
      BoardData legacy = SGFParser.parseSgf(
          "(;SZ[5]" + tag + "[KataGo 45 12k 2.5 1.2 0])", true).getStart().getData();
      assertEquals(12000, secondary ? legacy.getPlayouts2() : legacy.getPlayouts());
      assertEquals(-1, secondary ? legacy.rootVisits2 : legacy.rootVisits);
    }
  }

  @Test
  void mainlineMovesPreserveOrderColorAndBoard() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5];B[ba];W[cc];B[de];W[ee])", true);

      assertEquals(List.of("BLACK 1,0", "WHITE 2,2", "BLACK 3,4", "WHITE 4,4"), mainlineMoves(history));
      BoardHistoryNode end = history.getEnd();
      assertEquals(Stone.BLACK, end.getData().stones[Board.getIndex(1, 0)]);
      assertEquals(Stone.WHITE, end.getData().stones[Board.getIndex(2, 2)]);
      assertEquals(4, end.getData().moveNumber);
      assertTrue(end.getData().blackToPlay);
    }
  }

  @Test
  void variationsKeepParentChildStructureAndDistinctBoards() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5];B[aa](;W[bb];B[cc])(;W[dd];B[ee]))", true);

      BoardHistoryNode root = history.getStart();
      BoardHistoryNode black = root.next().orElseThrow();
      assertEquals(2, black.numberOfChildren());
      BoardHistoryNode mainWhite = black.getVariation(0).orElseThrow();
      BoardHistoryNode varWhite = black.getVariation(1).orElseThrow();
      assertTrue(RulesLayerTestHarness.sameLastMove(mainWhite.getData(), 1, 1));
      assertTrue(RulesLayerTestHarness.sameLastMove(varWhite.getData(), 3, 3));
      assertSame(black, mainWhite.previous().orElseThrow());
      assertSame(black, varWhite.previous().orElseThrow());
      assertEquals(Stone.WHITE, mainWhite.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(Stone.EMPTY, mainWhite.getData().stones[Board.getIndex(3, 3)]);
      assertEquals(Stone.WHITE, varWhite.getData().stones[Board.getIndex(3, 3)]);
      assertEquals(Stone.EMPTY, varWhite.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(List.of("BLACK 0,0", "WHITE 1,1", "BLACK 2,2"), mainlineMoves(history));
    }
  }

  @Test
  void abAwAndPlApplyAsRootSetupNotAsMoves() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5]AB[aa][ee]AW[cc]PL[W])", true);

      BoardHistoryNode root = history.getStart();
      assertEquals(0, root.numberOfChildren());
      assertEquals(0, root.getData().moveNumber);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(4, 4)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(2, 2)]);
      assertFalse(root.getData().blackToPlay);
      assertTrue(root.getData().getProperties().containsKey("AB"));
      assertTrue(root.getData().getProperties().containsKey("AW"));
    }
  }

  @Test
  void komiIsAppliedWhenParseSgfFirstFlagIsTrue() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList withKomi = SGFParser.parseSgf("(;SZ[5]KM[6.5];B[aa])", true);
      assertEquals(6.5, withKomi.getGameInfo().getKomi(), 0.0001);

      BoardHistoryList withoutFirstFlag = SGFParser.parseSgf("(;SZ[5]KM[6.5];B[aa])", false);
      assertEquals(
          7.5,
          withoutFirstFlag.getGameInfo().getKomi(),
          0.0001,
          "parseSgf(..., false) currently skips applying the KM tag to GameInfo.");
    }
  }

  @Test
  void handicapPropertySetsGameInfoAndWhiteToPlayOnAbSetup() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5]HA[2]AB[aa][ee];W[cc])", true);

      assertEquals(2, history.getGameInfo().getHandicap());
      BoardHistoryNode root = history.getStart();
      assertFalse(root.getData().blackToPlay, "handicap AB without PL should leave White to play.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(4, 4)]);
      assertEquals(1, root.numberOfChildren());
      assertEquals(Stone.WHITE, root.next().orElseThrow().getData().lastMoveColor);
    }
  }

  @Test
  void passIsEmptyMoveValueAndDoesNotChangeStones() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[cc];W[];B[aa])", true);

      BoardHistoryNode pass = history.getStart().next().orElseThrow().next().orElseThrow();
      assertTrue(pass.getData().isPassNode());
      assertEquals(Stone.WHITE, pass.getData().lastMoveColor);
      assertEquals(Stone.BLACK, pass.getData().stones[Board.getIndex(2, 2)]);
      assertEquals(Stone.EMPTY, pass.getData().stones[Board.getIndex(0, 0)]);
      BoardHistoryNode afterPass = pass.next().orElseThrow();
      assertEquals(Stone.BLACK, afterPass.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(3, afterPass.getData().moveNumber);
    }
  }

  @Test
  void commentsSupportChineseUnicodeEscapedBracketBackslashAndMultiline() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String sgf =
          "(;SZ[5]C[根注释：中文 and \\] and \\\\ slash\nsecond line];B[aa]C[move \\] and κ])";
      BoardHistoryList history = SGFParser.parseSgf(sgf, true);

      assertEquals(
          "根注释：中文 and ] and \\ slash\nsecond line",
          history.getStart().getData().comment);
      assertEquals(
          "move ] and κ",
          history.getStart().next().orElseThrow().getData().comment);
    }
  }

  @Test
  void unknownNonCriticalPropertiesAreKeptOnTheModel() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5]XX[keep-me]GN[Friendly];B[aa]LB[aa:A])", true);

      assertEquals("keep-me", history.getStart().getData().getProperty("XX"));
      assertEquals("Friendly", history.getStart().getData().getProperty("GN"));
      assertEquals("aa:A", history.getStart().next().orElseThrow().getData().getProperty("LB"));
    }
  }

  @Test
  void tolerantPrefixSuffixAndOptionalRootSemicolonStillParse() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList wrapped =
          SGFParser.parseSgf("junk before\n(;SZ[5];B[aa]) trailing", true);
      assertNotNull(wrapped);
      assertEquals(List.of("BLACK 0,0"), mainlineMoves(wrapped));

      BoardHistoryList optionalSemicolon = SGFParser.parseSgf("(SZ[5];B[bb])", true);
      assertNotNull(optionalSemicolon);
      assertEquals(List.of("BLACK 1,1"), mainlineMoves(optionalSemicolon));
    }
  }

  @Test
  void emptyAndNonSgfStringsParseWithoutThrowing() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      assertDoesNotThrow(() -> SGFParser.parseSgf("", true));
      assertNull(SGFParser.parseSgf("", true));
      assertDoesNotThrow(() -> SGFParser.parseSgf("not sgf", true));
      assertFalse(SGFParser.isSGF("not sgf"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not sgf",
        "(",
        ")",
        ";",
        "B[aa]",
        "(;SZ[5];B[",
        "(;SZ[5];B[aa]"
      })
  void incompleteOrNonSgfInputIsHandledWithoutThrowing(String raw) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList parsed = assertDoesNotThrow(() -> SGFParser.parseSgf(raw, true));
      if (parsed != null) {
        assertTrue(
            parsed.getStart() != null,
            "a parsed tree should have a root even when the input is nonstandard.");
      }
    }
  }

  @Test
  void invalidCoordinatesAreDroppedRatherThanThrown() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          assertDoesNotThrow(() -> SGFParser.parseSgf("(;SZ[5];B[zz];W[aa])", true));
      assertNotNull(history);
      // Current behavior: off-board B[zz] is ignored, so White's move becomes the first child.
      assertEquals(List.of("WHITE 0,0"), mainlineMoves(history));
    }
  }

  @Test
  void parseAppliesCapturesAndMoveNumbers() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[ba];W[aa];B[ab])", true);
      BoardHistoryNode capture = history.getEnd();
      assertEquals(Stone.EMPTY, capture.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(1, capture.getData().blackCaptures);
      assertEquals(3, capture.getData().moveNumber);
    }
  }

  @Test
  void representativeSemanticRoundTripPreservesTreeSetupAndText() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      String source =
          "(;SZ[5]KM[6.5]HA[2]PL[W]AB[ee]AW[ce]"
              + "C[根注释：中文 and \\] and \\\\ slash\nsecond line]"
              + "XX[keep-me]"
              + ";W[ba]C[white approach]"
              + ";B[aa]"
              + ";W[ab]C[captures the corner]"
              + ";B[]C[black pass]"
              + ";W[cc]"
              + "(;B[cd]C[mainline]"
              + ";W[dc])"
              + "(;B[db]C[branch unicode κ]))";

      BoardHistoryList first = SGFParser.parseSgf(source, true);
      assertNotNull(first);
      Lizzie.board.setHistory(first);
      String written = SGFParser.saveToString(false);
      BoardHistoryList second = SGFParser.parseSgf(written, true);
      assertNotNull(second);

      assertTreeSemanticsEqual(first, second);
      assertFalse(
          written.equals(source),
          "round-trip is allowed to change whitespace, property order, and wrappers.");
    }
  }

  @Test
  void livePlayCaptureVariationAndCommentRoundTrip() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.getHistory().getGameInfo().setKomiNoMenu(0.5);
      board.setupPlaceStone(4, 4, Stone.BLACK);
      board.setupSetSideToPlay(true);
      board.getHistory().getStart().getData().comment = "live root 中文";

      board.place(1, 0, Stone.BLACK);
      board.place(0, 0, Stone.WHITE);
      board.place(0, 1, Stone.BLACK);
      env.current().getData().comment = "captured with ] and \\";
      BoardHistoryNode capture = env.current();
      board.pass(Stone.WHITE);
      board.previousMove(false);
      board.place(2, 2, Stone.WHITE, true);
      env.current().getData().comment = "variation";

      String written = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(written, true);
      assertNotNull(roundTrip);

      assertEquals(0.5, roundTrip.getGameInfo().getKomi(), 0.0001);
      assertEquals(Stone.BLACK, roundTrip.getStart().getData().stones[Board.getIndex(4, 4)]);
      assertTrue(roundTrip.getStart().getData().comment.contains("live root 中文"));

      BoardHistoryNode rtCapture = findCaptureNode(roundTrip.getStart());
      assertNotNull(rtCapture);
      assertEquals(1, rtCapture.getData().blackCaptures);
      assertEquals(Stone.EMPTY, rtCapture.getData().stones[Board.getIndex(0, 0)]);
      assertTrue(rtCapture.getData().comment.contains("captured with ] and \\"));
      assertEquals(2, capture.numberOfChildren());
      assertEquals(2, rtCapture.numberOfChildren());
    }
  }

  @Test
  void foxStandaloneHandicapNormalizesToWhiteToPlayAndPreservesRoundTrip() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      String source = "(;SZ[19]HA[7]KM[0.5];AB[pd][dp][dd][pp][dj][pj][jj];W[fq];B[qq])";
      BoardHistoryList history = SGFParser.parseSgf(source, true);
      assertNotNull(history);

      BoardHistoryNode root = history.getStart();
      BoardData rootData = root.getData();
      assertEquals(BoardNodeKind.SNAPSHOT, rootData.getNodeKind());
      assertFalse(rootData.isHistoryActionNode());
      assertEquals(0, rootData.moveNumber);
      assertTrue(rootData.blackToPlay, "empty root defaults to Black to play");
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(root, 7, 19, 19),
          "empty root with HA should not be recognized as initial handicap setup");

      BoardHistoryNode setupNode = root.next().orElseThrow();
      BoardData setupData = setupNode.getData();
      assertEquals(BoardNodeKind.SNAPSHOT, setupData.getNodeKind());
      assertFalse(setupData.isHistoryActionNode());
      assertEquals(0, setupData.moveNumber);
      assertFalse(
          setupData.blackToPlay,
          "eligible standalone initial handicap setup must be normalized to White to play");
      assertTrue(
          InitialHandicapSetup.isInitialHandicap(setupNode, 7, 19, 19),
          "standalone setup with 7 valid black stones and HA=7 must be recognized");

      BoardHistoryNode move1 = setupNode.next().orElseThrow();
      BoardData move1Data = move1.getData();
      assertEquals(BoardNodeKind.MOVE, move1Data.getNodeKind());
      assertTrue(move1Data.isHistoryActionNode());
      assertEquals(Stone.WHITE, move1Data.lastMoveColor);
      assertEquals(1, move1Data.moveNumber);
      assertTrue(move1Data.blackToPlay);

      BoardHistoryNode move2 = move1.next().orElseThrow();
      BoardData move2Data = move2.getData();
      assertEquals(BoardNodeKind.MOVE, move2Data.getNodeKind());
      assertTrue(move2Data.isHistoryActionNode());
      assertEquals(Stone.BLACK, move2Data.lastMoveColor);
      assertEquals(2, move2Data.moveNumber);
      assertFalse(move2Data.blackToPlay);

      Lizzie.board.setHistory(history);
      String written = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(written, true);
      assertNotNull(roundTrip);

      BoardHistoryNode rtRoot = roundTrip.getStart();
      BoardHistoryNode rtSetup = rtRoot.next().orElseThrow();
      BoardHistoryNode rtMove1 = rtSetup.next().orElseThrow();
      BoardHistoryNode rtMove2 = rtMove1.next().orElseThrow();

      assertEquals(BoardNodeKind.SNAPSHOT, rtRoot.getData().getNodeKind());
      assertEquals(0, rtRoot.getData().moveNumber);
      assertTrue(rtRoot.getData().blackToPlay);

      assertEquals(BoardNodeKind.SNAPSHOT, rtSetup.getData().getNodeKind());
      assertEquals(0, rtSetup.getData().moveNumber);
      assertFalse(rtSetup.getData().blackToPlay);
      assertArrayEquals(setupData.stones, rtSetup.getData().stones);

      assertEquals(BoardNodeKind.MOVE, rtMove1.getData().getNodeKind());
      assertEquals(Stone.WHITE, rtMove1.getData().lastMoveColor);
      assertEquals(1, rtMove1.getData().moveNumber);

      assertEquals(BoardNodeKind.MOVE, rtMove2.getData().getNodeKind());
      assertEquals(Stone.BLACK, rtMove2.getData().lastMoveColor);
      assertEquals(2, rtMove2.getData().moveNumber);

      assertTreeSemanticsEqual(history, roundTrip);
    }
  }

  @Test
  void foxStandaloneHandicapExplicitWhiteToPlayPreserved() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      String source = "(;SZ[19]HA[7]KM[0.5];AB[pd][dp][dd][pp][dj][pj][jj]PL[W];W[fq];B[qq])";
      BoardHistoryList history = SGFParser.parseSgf(source, true);
      assertNotNull(history);

      BoardHistoryNode root = history.getStart();
      assertTrue(root.getData().blackToPlay);

      BoardHistoryNode setupNode = root.next().orElseThrow();
      assertEquals(0, setupNode.getData().moveNumber);
      assertFalse(setupNode.getData().blackToPlay, "explicit PL[W] must be White to play");
      assertTrue(
          InitialHandicapSetup.isInitialHandicap(setupNode, 7, 19, 19),
          "explicit PL[W] initial handicap must be recognized");

      BoardHistoryNode move1 = setupNode.next().orElseThrow();
      assertEquals(Stone.WHITE, move1.getData().lastMoveColor);
      assertEquals(1, move1.getData().moveNumber);

      BoardHistoryNode move2 = move1.next().orElseThrow();
      assertEquals(Stone.BLACK, move2.getData().lastMoveColor);
      assertEquals(2, move2.getData().moveNumber);

      Lizzie.board.setHistory(history);
      String written = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(written, true);
      assertNotNull(roundTrip);
      assertTreeSemanticsEqual(history, roundTrip);
    }
  }

  @Test
  void foxStandaloneHandicapExplicitBlackToPlayPreservedAndNotRecognized() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      String source = "(;SZ[19]HA[7]KM[0.5];AB[pd][dp][dd][pp][dj][pj][jj]PL[B];B[fq];W[qq])";
      BoardHistoryList history = SGFParser.parseSgf(source, true);
      assertNotNull(history);

      BoardHistoryNode root = history.getStart();
      assertTrue(root.getData().blackToPlay);

      BoardHistoryNode setupNode = root.next().orElseThrow();
      assertEquals(0, setupNode.getData().moveNumber);
      assertTrue(
          setupNode.getData().blackToPlay,
          "explicit PL[B] must remain Black to play and must not be overwritten to White");
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(setupNode, 7, 19, 19),
          "explicit PL[B] must not be recognized as initial handicap setup");

      BoardHistoryNode move1 = setupNode.next().orElseThrow();
      assertEquals(Stone.BLACK, move1.getData().lastMoveColor);
      assertEquals(1, move1.getData().moveNumber);

      BoardHistoryNode move2 = move1.next().orElseThrow();
      assertEquals(Stone.WHITE, move2.getData().lastMoveColor);
      assertEquals(2, move2.getData().moveNumber);

      Lizzie.board.setHistory(history);
      String written = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(written, true);
      assertNotNull(roundTrip);
      assertTreeSemanticsEqual(history, roundTrip);
    }
  }

  @Test
  void legacyRootHandicapSetupPreservesWhiteToPlayAndRoundTrip() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      String withoutPl = "(;SZ[19]HA[2]AB[dp][pd];W[pp];B[dd])";
      BoardHistoryList history1 = SGFParser.parseSgf(withoutPl, true);
      assertNotNull(history1);

      BoardHistoryNode root1 = history1.getStart();
      assertEquals(0, root1.getData().moveNumber);
      assertFalse(root1.getData().blackToPlay, "root handicap setup without PL defaults to White to play");
      assertTrue(InitialHandicapSetup.isInitialHandicap(root1, 2, 19, 19));

      BoardHistoryNode move1 = root1.next().orElseThrow();
      assertEquals(Stone.WHITE, move1.getData().lastMoveColor);
      assertEquals(1, move1.getData().moveNumber);

      BoardHistoryNode move2 = move1.next().orElseThrow();
      assertEquals(Stone.BLACK, move2.getData().lastMoveColor);
      assertEquals(2, move2.getData().moveNumber);

      Lizzie.board.setHistory(history1);
      String written1 = SGFParser.saveToString(false);
      BoardHistoryList roundTrip1 = SGFParser.parseSgf(written1, true);
      assertNotNull(roundTrip1);
      assertTreeSemanticsEqual(history1, roundTrip1);

      String withPlW = "(;SZ[19]HA[2]AB[dp][pd]PL[W];W[pp];B[dd])";
      BoardHistoryList history2 = SGFParser.parseSgf(withPlW, true);
      assertNotNull(history2);
      BoardHistoryNode root2 = history2.getStart();
      assertFalse(root2.getData().blackToPlay);
      assertTrue(InitialHandicapSetup.isInitialHandicap(root2, 2, 19, 19));
    }
  }

  @Test
  void invalidOrConflictingSetupsAreNotRecognizedAsInitialHandicap() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      // 1. Count conflict: HA=5 but only 2 stones placed
      String countConflict = "(;SZ[19]HA[5]KM[0.5];AB[pd][dp];W[fq])";
      BoardHistoryList historyConflict = SGFParser.parseSgf(countConflict, true);
      assertNotNull(historyConflict);
      BoardHistoryNode conflictNode = historyConflict.getStart().next().orElseThrow();
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(conflictNode, 5, 19, 19),
          "count conflict (2 stones vs HA=5) must not be recognized as initial handicap");
      assertFalse(
          conflictNode.getData().blackToPlay,
          "first White move determines turn independently of handicap eligibility");

      // 2. Setup with AW (preset white stone)
      String withAw = "(;SZ[19]HA[2]KM[0.5];AB[pd][dp]AW[dd];W[fq])";
      BoardHistoryList historyAw = SGFParser.parseSgf(withAw, true);
      assertNotNull(historyAw);
      BoardHistoryNode awNode = historyAw.getStart().next().orElseThrow();
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(awNode, 2, 19, 19),
          "setup with AW must not be recognized as initial handicap");
      assertFalse(
          awNode.getData().blackToPlay,
          "mixed setup can be White to play without handicap eligibility");

      // 3. Setup with AE
      String withAe = "(;SZ[19]HA[2]KM[0.5];AB[pd][dp]AE[dd];W[fq])";
      BoardHistoryList historyAe = SGFParser.parseSgf(withAe, true);
      assertNotNull(historyAe);
      BoardHistoryNode aeNode = historyAe.getStart().next().orElseThrow();
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(aeNode, 2, 19, 19),
          "setup with AE must not be recognized as initial handicap");

      // 4. Midgame setup: setup node appears after real moves
      String midgame = "(;SZ[19]HA[2]KM[0.5];B[dp];W[pd];AB[dd][pp];W[fq])";
      BoardHistoryList historyMidgame = SGFParser.parseSgf(midgame, true);
      assertNotNull(historyMidgame);
      BoardHistoryNode b1 = historyMidgame.getStart().next().orElseThrow();
      BoardHistoryNode w1 = b1.next().orElseThrow();
      BoardHistoryNode midSetup = w1.next().orElseThrow();
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(midSetup, 2, 19, 19),
          "midgame setup with genuine move ancestors must not be recognized as initial handicap");

      // 5. Empty root with HA
      String emptyRootSgf = "(;SZ[19]HA[7]KM[0.5];W[fq])";
      BoardHistoryList historyEmptyRoot = SGFParser.parseSgf(emptyRootSgf, true);
      assertNotNull(historyEmptyRoot);
      BoardHistoryNode emptyRoot = historyEmptyRoot.getStart();
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(emptyRoot, 7, 19, 19),
          "empty root must not be recognized as initial handicap");
      assertFalse(emptyRoot.getData().blackToPlay, "first White move determines empty-root turn");
    }
  }

  private static BoardHistoryNode findCaptureNode(BoardHistoryNode root) {
    ArrayList<BoardHistoryNode> pending = new ArrayList<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      BoardHistoryNode node = pending.remove(pending.size() - 1);
      if (node.getData().blackCaptures > 0 || node.getData().whiteCaptures > 0) {
        return node;
      }
      pending.addAll(node.getVariations());
    }
    return null;
  }

  static List<String> mainlineMoves(BoardHistoryList history) {
    List<String> moves = new ArrayList<>();
    BoardHistoryNode node = history.getStart();
    while (node.next().isPresent()) {
      node = node.next().get();
      moves.add(describeMove(node));
    }
    return moves;
  }

  private static String describeMove(BoardHistoryNode node) {
    BoardData data = node.getData();
    if (data.isPassNode()) {
      return data.lastMoveColor + " pass";
    }
    if (data.lastMove.isPresent()) {
      int[] coord = data.lastMove.get();
      return data.lastMoveColor + " " + coord[0] + "," + coord[1];
    }
    return String.valueOf(data.getNodeKind());
  }

  static void assertTreeSemanticsEqual(BoardHistoryList expected, BoardHistoryList actual) {
    assertEquals(expected.getGameInfo().getKomi(), actual.getGameInfo().getKomi(), 0.0001);
    assertEquals(expected.getGameInfo().getHandicap(), actual.getGameInfo().getHandicap());
    assertNodeSemanticsEqual(expected.getStart(), actual.getStart());
  }

  private static void assertNodeSemanticsEqual(BoardHistoryNode expected, BoardHistoryNode actual) {
    BoardData expectedData = expected.getData();
    BoardData actualData = actual.getData();
    assertEquals(expectedData.getNodeKind(), actualData.getNodeKind());
    assertEquals(expectedData.moveNumber, actualData.moveNumber);
    assertEquals(expectedData.lastMoveColor, actualData.lastMoveColor);
    assertEquals(expectedData.blackToPlay, actualData.blackToPlay);
    assertEquals(expectedData.blackCaptures, actualData.blackCaptures);
    assertEquals(expectedData.whiteCaptures, actualData.whiteCaptures);
    assertEquals(expectedData.dummy, actualData.dummy);
    assertEquals(normalizeComment(expectedData.comment), normalizeComment(actualData.comment));
    assertLastMoveEqual(expectedData.lastMove, actualData.lastMove);
    assertArrayEquals(expectedData.stones, actualData.stones);
    assertPropertyPresentIfOriginalHadIt(expectedData, actualData, "XX");
    assertPropertyPresentIfOriginalHadIt(expectedData, actualData, "AB");
    assertPropertyPresentIfOriginalHadIt(expectedData, actualData, "AW");
    assertEquals(expected.numberOfChildren(), actual.numberOfChildren());
    for (int i = 0; i < expected.numberOfChildren(); i++) {
      assertNodeSemanticsEqual(
          expected.getVariation(i).orElseThrow(), actual.getVariation(i).orElseThrow());
    }
  }

  private static void assertLastMoveEqual(Optional<int[]> expected, Optional<int[]> actual) {
    assertEquals(expected.isPresent(), actual.isPresent());
    expected.ifPresent(value -> assertArrayEquals(value, actual.orElseThrow()));
  }

  private static void assertPropertyPresentIfOriginalHadIt(
      BoardData expected, BoardData actual, String key) {
    String expectedValue = expected.getProperty(key);
    if (expectedValue != null && !expectedValue.isEmpty()) {
      assertNotNull(actual.getProperty(key), "property " + key + " should survive round-trip");
    }
  }

  private static String normalizeComment(String comment) {
    return comment == null ? "" : comment;
  }
}
