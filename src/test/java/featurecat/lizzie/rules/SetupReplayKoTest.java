package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SetupReplayKoTest {
  // Three independent kos. Recoloring ba lets the sixth capture recreate the setup.
  private static final String SETUP =
      "AB[aa][ca][ab][cb][fb][ec][gc][fd][ia][ka][ib][kb][ja]"
          + "AW[bb][ac][cc][bd][ba][ea][ga][eb][gb][fa][jb][ic][kc][jd]";
  private static final String MOVES = ";B[bc];W[fc];B[jc];W[bb];B[fb];W[jb]";

  @Test
  void illegalSideVariationRejectsLiveEditAndKeepsExistingUndo() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(13)) {
      Board board = env.board();
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[13]RU[Tromp-Taylor]PL[B]" + SETUP + "(;B[ml])(" + MOVES + "))", true);
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode cycle = root.getVariation(1).orElseThrow();
      while (cycle.next().isPresent()) cycle = cycle.next().orElseThrow();
      assertEquals(6, cycle.getData().moveNumber);
      history.setHead(root.getVariation(0).orElseThrow());
      board.setHistory(history);
      BoardHistoryNode selected = history.getCurrentHistoryNode();
      selected.getData().comment = "previous edit";
      board.saveListForEdit();
      selected.getData().comment = "current";
      board.editMove(new int[] {1, 0}, true, false);

      assertSame(history, board.getHistory());
      assertSame(selected, history.getCurrentHistoryNode());
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(1, 0)]);
      assertEquals("current", selected.getData().comment);
      board.cleanedit();
      assertEquals("previous edit", board.getHistory().getData().comment);
    }
  }

  @ParameterizedTest
  @CsvSource({"'', false", "RU[Japanese], true"})
  void confirmedEngineKoAppliesOnlyWithoutADeclaredRule(String declaration, boolean legal)
      throws Exception {
    try (var env = RulesLayerTestHarness.open(13)) {
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[13]PL[B]" + declaration + SETUP + MOVES + ")", true);
      env.board().setHistory(history);
      SGFParser.adoptCurrentHistoryExternalRules();
      KataGoRules positional = KataGoRules.parse("Tromp-Taylor").orElseThrow();
      Lizzie.setPrimaryEngine(new Leelaz("") {
        @Override
        public KataGoRules currentConfirmedRules() {
          return positional;
        }
      });
      assertEquals(legal, recolor(history) != null);
    }
  }

  @ParameterizedTest
  @CsvSource({
      "Tromp-Taylor, B, false",
      "Tromp-Taylor, W, false",
      "AGA, B, false",
      "AGA, W, true",
      "Japanese, B, true"
  })
  void rulesAndSideToPlayDetermineWhetherTheCycleIsLegal(
      String rules, String rootPlayer, boolean legal) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(13)) {
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[13]RU[" + rules + "]PL[" + rootPlayer + "]" + SETUP + MOVES + ")", true);
      assertEquals(legal, recolor(history) != null);
    }
  }

  @ParameterizedTest
  @CsvSource({
      "POSITIONAL, false, false",
      "SITUATIONAL, false, false",
      "SPIGHT,     false, true",
      "SITUATIONAL, true, true"
  })
  void onlySpightAndTakingTheButtonLiftSuperkoAtAPass(
      String ko, boolean button, boolean legal) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(13)) {
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[13]PL[B]" + SETUP + MOVES.replace(";W[fc]", ";W[];W[fc]") + ")", true);
      history.publishManualRules(KataGoRules.parse("aga").orElseThrow()
          .overlayEditor("AREA", ko, false, "NONE", "N-1", button, true));
      assertEquals(legal, recolor(history) != null);
    }
  }

  @ParameterizedTest
  @CsvSource({"SIMPLE,true", "SITUATIONAL,true", "POSITIONAL,false"})
  void consecutiveBlackMovesCanSelfCaptureWithoutMakingASimpleKo(String ko, boolean legal)
      throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[5]RU[New Zealand]PL[B]AB[ca]AW[ab][bb];B[aa];B[ba])", true);
      history.publishManualRules(KataGoRules.parse("new-zealand").orElseThrow()
          .overlayEditor("AREA", ko, true, "NONE", "0", false, true));
      BoardHistoryList edited = history.editSetupStone(history.getStart(), 2, 0, Stone.WHITE, null);
      assertEquals(legal, edited != null);
      if (legal) {
        assertEquals(2, edited.getStart().getLast().getData().moveNumber);
        assertEquals(edited.getStart().getData().zobrist, edited.getStart().getLast().getData().zobrist);
        env.board().setHistory(edited);
        BoardHistoryList reopened = SGFParser.parseSgf(SGFParser.saveToString(false), true);
        assertEquals(2, reopened.getStart().getLast().getData().moveNumber);
        assertArrayEquals(edited.getStart().getLast().getData().stones,
            reopened.getStart().getLast().getData().stones);
      }
    }
  }

  @Test
  void setupRestartsKoHistoryInsideAVariation() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(13)) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[13]RU[Tromp-Taylor]PL[B]"
          + SETUP + MOVES.replace(";W[bb]", ";AB[aa];W[bb]") + ")", true);
      assertNotNull(recolor(history));
    }
  }

  @ParameterizedTest
  @CsvSource({"W,false", "B,true"})
  void aPlayerOnlySnapshotInterruptsTheCycleOnlyWhenItChangesTheTurn(String next, boolean legal)
      throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(13)) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[13]RU[Tromp-Taylor]PL[B]"
          + SETUP + MOVES.replace(";W[fc]", ";PL[" + next + "];W[fc]") + ")", true);
      assertEquals(legal, recolor(history) != null);
    }
  }

  @Test
  void erasingTheOnlyMidgameSetupPointRestoresEarlierKoHistory() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(13)) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[13]RU[Tromp-Taylor]PL[B]"
          + closedCycleSetup() + MOVES.replace(";B[jc]", ";AW[mm];B[jc]") + ")", true);
      history.setHead(history.getStart().getLast());
      BoardHistoryNode owner = history.setupStoneOwner(12, 12);
      assertNotNull(owner);
      assertNull(history.editSetupStone(owner, 12, 12, Stone.EMPTY, null));
      assertEquals(Stone.WHITE, owner.getData().stones[Board.getIndex(12, 12)]);
    }
  }

  @Test
  void legacySetupOnTheRecaptureCannotHideItsIllegalPlayedPosition() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(13)) {
      BoardHistoryList history = SGFParser.parseSgf(
          "(;SZ[13]RU[Tromp-Taylor]PL[B]" + SETUP + MOVES + ")", true);
      history.setHead(history.getStart().getLast());
      history.addExtraStone(12, 12, Stone.BLACK);
      assertNull(recolor(history));
      assertEquals(Stone.BLACK, history.getData().stones[Board.getIndex(12, 12)]);
    }
  }

  @Test
  void unrelatedEditPreservesAlreadyRecordedCycle() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(13)) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[13]RU[Tromp-Taylor]PL[B]"
          + closedCycleSetup() + "[mm]" + MOVES + ")", true);
      BoardHistoryNode root = history.getStart();
      assertEquals(root.getData().zobrist, root.getLast().getData().zobrist);
      assertNotNull(history.editSetupStone(root, 12, 12, Stone.BLACK, null));
    }
  }

  private static String closedCycleSetup() {
    return SETUP.replace("AW[bb]", "[ba]AW[bb]").replace("[bd][ba]", "[bd]");
  }

  private static BoardHistoryList recolor(BoardHistoryList history) {
    return history.editSetupStone(history.getStart(), 1, 0, Stone.BLACK, null);
  }
}
