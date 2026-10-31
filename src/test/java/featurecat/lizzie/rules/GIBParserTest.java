package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.ReadBoard;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Direct GIBParser.load regression: successful non-empty GIB must return true and keep explicit
 * STO/SKI as MOVE/PASS. Missing or empty inputs stay false without clearing a pre-existing board.
 */
class GIBParserTest {
  private static final int SIZE = 19;
  private static final int BLACK_X = 3;
  private static final int BLACK_Y = 3;
  private static final int WHITE_X = 15;
  private static final int WHITE_Y = 15;
  private static final int SEEDED_X = 4;
  private static final int SEEDED_Y = 4;
  private static final String BLACK_NAME = "BlackPlayer";
  private static final String WHITE_NAME = "WhitePlayer";
  private static final double KOMI = 6.5;

  private static final String MINIMAL_GIB =
      "\\[GAMEINFOMAIN=GONGJE:65,\\]\r\n"
          + "\\[GAMEBLACKNAME="
          + BLACK_NAME
          + "\\]\r\n"
          + "\\[GAMEWHITENAME="
          + WHITE_NAME
          + "\\]\r\n"
          + "STO 0 1 1 "
          + BLACK_X
          + " "
          + BLACK_Y
          + "\n"
          + "STO 0 2 2 "
          + WHITE_X
          + " "
          + WHITE_Y
          + "\n"
          + "SKI 0 3\n";

  @TempDir Path tempDir;

  @Test
  void supportedGibLoadReturnsTrueAndKeepsExplicitMovesAndPass() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      Lizzie.config.loadSgfLast = false;
      Lizzie.config.readKomi = true;
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = false;
      Path file = writeGib("supported.gib", MINIMAL_GIB);

      assertTrue(GIBParser.load(file.toString()));

      assertFalse(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
      GameInfo info = env.board().getHistory().getGameInfo();
      assertEquals(BLACK_NAME, info.getPlayerBlack());
      assertEquals(WHITE_NAME, info.getPlayerWhite());
      assertEquals(KOMI, info.getKomi(), 0.0001);
      assertSame(env.board().getHistory().getStart(), env.current());
      assertEquals(BoardNodeKind.SNAPSHOT, env.current().getData().getNodeKind());
      assertEquals(Stone.EMPTY, env.stoneAt(BLACK_X, BLACK_Y));
      assertEquals(Stone.EMPTY, env.stoneAt(WHITE_X, WHITE_Y));
      assertExplicitStoSkiMainline(env.board().getHistory().getStart());
    }
  }

  @Test
  void loadSgfLastStopsOnLastExplicitAction() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      Lizzie.config.loadSgfLast = true;
      Lizzie.config.readKomi = true;
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = false;
      Path file = writeGib("supported-last.gib", MINIMAL_GIB);

      assertTrue(GIBParser.load(file.toString()));

      assertFalse(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
      BoardHistoryNode pass = lastRealAction(env.board().getHistory().getStart());
      assertSame(pass, env.current());
      assertEquals(BoardNodeKind.PASS, env.current().getData().getNodeKind());
      assertEquals(Stone.BLACK, env.stoneAt(BLACK_X, BLACK_Y));
      assertEquals(Stone.WHITE, env.stoneAt(WHITE_X, WHITE_Y));
      assertExplicitStoSkiMainline(env.board().getHistory().getStart());
    }
  }

  @Test
  void missingFileReturnsFalseWithoutClearingExistingBoard() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      seedExistingPosition(env);
      BoardHistoryNode before = env.current();
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = false;

      assertFalse(GIBParser.load(tempDir.resolve("missing.gib").toString()));

      assertSame(before, env.current());
      assertEquals(Stone.BLACK, env.stoneAt(SEEDED_X, SEEDED_Y));
      assertFalse(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
    }
  }

  @Test
  void emptyFileReturnsFalseWithoutClearingExistingBoard() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      seedExistingPosition(env);
      BoardHistoryNode before = env.current();
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = false;
      Path empty = writeGib("empty.gib", "");

      assertFalse(GIBParser.load(empty.toString()));

      assertSame(before, env.current());
      assertEquals(Stone.BLACK, env.stoneAt(SEEDED_X, SEEDED_Y));
      assertFalse(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
    }
  }

  static Stream<Arguments> invalidGibs() {
    return Stream.of(
        Arguments.of("unrecognized", "This is not a GIB game."),
        Arguments.of("whitespace", " \r\n\t"),
        Arguments.of("short-ini", "INI\n"),
        Arguments.of("invalid-handicap", "INI 0 0 invalid\n"),
        Arguments.of("short-sto", "STO 0 1 1 3\n"),
        Arguments.of("invalid-coordinate", "STO 0 1 1 3 3\nSTO 0 2 2 x 4\n"),
        Arguments.of("invalid-komi", "STO 0 1 1 3 3\n\\[GAMEINFOMAIN=GONGJE:x,\\]\n"),
        Arguments.of("unterminated-komi", "\\[GAMEINFOMAIN=GONGJE:65,"),
        Arguments.of("unterminated-name", "\\[GAMEBLACKNAME="));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidGibs")
  void invalidGibPreservesExistingGameAndTemporaryFlags(String name, String content)
      throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      env.board().setHistory(SGFParser.parseSgf(
          "(;SZ[19]KM[5.5]PB[original black]PW[original white]C[root]"
              + ";B[aa](;W[bb]C[selected])(;W[cc]C[variation]))", true));
      BoardHistoryList history = env.board().getHistory();
      BoardHistoryNode selected = history.getStart().next().orElseThrow()
          .getVariation(1).orElseThrow();
      history.setHead(selected);
      String before = SGFParser.saveToString(false);
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = false;

      assertFalse(GIBParser.load(writeGib(name + ".gib", content).toString()));

      assertSame(history, env.board().getHistory());
      assertSame(selected, env.current());
      assertEquals(before, SGFParser.saveToString(false));
      assertFalse(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
    }
  }

  @Test
  void failedApplicationRestoresBoardAndTemporaryFlags() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      seedExistingPosition(env);
      BoardHistoryList history = env.board().getHistory();
      BoardHistoryNode selected = env.current();
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = false;
      LoadTrackingFrame frame = RulesLayerTestHarness.allocate(LoadTrackingFrame.class);
      frame.failPlayerUpdate = true;
      Lizzie.frame = frame;

      IllegalStateException failure = assertThrows(IllegalStateException.class,
          () -> GIBParser.load(writeGib("apply-failure.gib", MINIMAL_GIB).toString()));

      assertEquals("player update failed", failure.getMessage());
      assertNotSame(history, frame.historyAtFailure, "the failure must occur after adoption starts");
      assertSame(history, env.board().getHistory());
      assertSame(selected, env.current());
      assertEquals(Stone.BLACK, env.stoneAt(SEEDED_X, SEEDED_Y));
      assertFalse(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
    }
  }

  @Test
  void failedRollbackKeepsTheApplicationFailureAndRestoresTemporaryFlags() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      seedExistingPosition(env);
      BoardHistoryList history = env.board().getHistory();
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = true;
      Lizzie.frame.readBoard = RulesLayerTestHarness.allocate(FailingReadBoard.class);

      IllegalStateException failure = assertThrows(IllegalStateException.class,
          () -> GIBParser.load(writeGib("rollback-failure.gib", MINIMAL_GIB).toString()));

      assertEquals("history notification 1", failure.getMessage());
      assertEquals(1, failure.getSuppressed().length);
      assertEquals("history notification 2", failure.getSuppressed()[0].getMessage());
      assertSame(history, env.board().getHistory());
      assertEquals(Stone.BLACK, env.stoneAt(SEEDED_X, SEEDED_Y));
      assertTrue(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"INI 0 0 0\n", "\\[GAMEBLACKNAME=Unplayed\\]\n"})
  void recognizedUnplayedGamesRemainSupported(String content) throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      seedExistingPosition(env);

      assertTrue(GIBParser.load(writeGib("unplayed.gib", content).toString()));

      assertSame(env.board().getHistory().getStart(), env.current());
      assertFalse(env.current().next().isPresent());
      assertEquals(Stone.EMPTY, env.stoneAt(SEEDED_X, SEEDED_Y));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 5, 7, 9, 10})
  void handicapPlacementAndExistingNineStoneLimitRemainSupported(int handicap) throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      Lizzie.config.loadSgfLast = true;

      assertTrue(GIBParser.load(writeGib("handicap.gib", "INI 0 0 " + handicap + "\n")
          .toString()));

      int count = Math.min(handicap, 9);
      int[][] expected = {
          {3, 15}, {15, 3}, {15, 15}, {3, 3}, {3, 9}, {15, 9}, {9, 3}, {9, 15}, {9, 9}
      };
      assertEquals(count, env.current().getData().moveNumber);
      if (count == 5 || count == 7) {
        assertEquals(Stone.BLACK, env.stoneAt(9, 9));
        count--;
      }
      for (int i = 0; i < count; i++) {
        assertEquals(Stone.BLACK, env.stoneAt(expected[i][0], expected[i][1]));
      }
    }
  }

  static Stream<Arguments> playerNameEncodings() {
    return Stream.of("UTF-8", "EUC-KR").flatMap(encoding ->
        Stream.of("\n", "\r\n").map(newline -> Arguments.of(encoding, newline)));
  }

  @ParameterizedTest
  @MethodSource("playerNameEncodings")
  void playerNamesKeepTheirTerminatorIndependentOfLineEnding(String encoding, String newline)
      throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      String black = "한국기원 검은돌 선수";
      String white = "한국기원 하얀돌 선수";
      Path file = tempDir.resolve("names.gib");
      Files.writeString(file, "\\[GAMEBLACKNAME=" + black + "\\]" + newline
          + "\\[GAMEWHITENAME=" + white + "\\]" + newline
          + "INI 0 0 0" + newline, Charset.forName(encoding));

      assertTrue(GIBParser.load(file.toString()));

      assertEquals(black, env.board().getHistory().getGameInfo().getPlayerBlack());
      assertEquals(white, env.board().getHistory().getGameInfo().getPlayerWhite());
    }
  }

  @Test
  void disabledReadKomiKeepsExistingKomiAndUnknownRecordsRemainIgnorable() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      Lizzie.setPrimaryEngine(null);
      Lizzie.config.readKomi = false;
      env.board().getHistory().getGameInfo().setKomi(5.5);

      assertTrue(GIBParser.load(writeGib("preserved-komi.gib",
          "UNKNOWN extension record\n" + MINIMAL_GIB).toString()));

      assertEquals(5.5, env.board().getHistory().getGameInfo().getKomi(), 0.0001);
      assertExplicitStoSkiMainline(env.board().getHistory().getStart());
    }
  }

  @Test
  void successfulGibLoadStartsFreshAnalysisControlContext() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      Lizzie.config.autoQuickAnalyzeOnLoad = true;
      Lizzie.config.persisted =
          new org.json.JSONObject().put("filesystem", new org.json.JSONObject());
      Lizzie.config.loadSgfLast = false;
      EngineManager.isEmpty = false;
      LoadTrackingFrame frame = RulesLayerTestHarness.allocate(LoadTrackingFrame.class);
      Lizzie.frame = frame;
      setFrameField(frame, "userAnalysisPaused", true);
      assertTrue(frame.isUserAnalysisPaused());

      assertTrue(frame.loadFile(writeGib("fresh-context.gib", MINIMAL_GIB).toFile(), true, false));
      assertFalse(frame.isUserAnalysisPaused());
    }
  }

  private static RulesLayerTestHarness openSizedHarness() throws Exception {
    RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE);
    LizzieFrame.boardRenderer.setBoardLength(400, 400);
    return env;
  }

  private Path writeGib(String name, String body) throws IOException {
    Path file = tempDir.resolve(name);
    Files.writeString(file, body, StandardCharsets.UTF_8);
    return file;
  }

  private static void seedExistingPosition(RulesLayerTestHarness env) {
    env.board().place(SEEDED_X, SEEDED_Y, Stone.BLACK);
    assertEquals(Stone.BLACK, env.stoneAt(SEEDED_X, SEEDED_Y));
  }

  private static void assertExplicitStoSkiMainline(BoardHistoryNode root) {
    assertEquals(BoardNodeKind.SNAPSHOT, root.getData().getNodeKind());

    BoardHistoryNode blackMove = root.next().orElseThrow();
    assertEquals(BoardNodeKind.MOVE, blackMove.getData().getNodeKind());
    assertFalse(blackMove.getData().dummy);
    assertEquals(Stone.BLACK, blackMove.getData().lastMoveColor);
    assertTrue(RulesLayerTestHarness.sameLastMove(blackMove.getData(), BLACK_X, BLACK_Y));

    BoardHistoryNode whiteMove = blackMove.next().orElseThrow();
    assertEquals(BoardNodeKind.MOVE, whiteMove.getData().getNodeKind());
    assertFalse(whiteMove.getData().dummy);
    assertEquals(Stone.WHITE, whiteMove.getData().lastMoveColor);
    assertTrue(RulesLayerTestHarness.sameLastMove(whiteMove.getData(), WHITE_X, WHITE_Y));

    BoardHistoryNode pass = whiteMove.next().orElseThrow();
    assertEquals(BoardNodeKind.PASS, pass.getData().getNodeKind());
    assertFalse(pass.getData().dummy);
    assertEquals(Stone.BLACK, pass.getData().lastMoveColor);
    assertFalse(pass.getData().lastMove.isPresent());
    assertFalse(pass.next().isPresent());
    assertEquals(Stone.BLACK, pass.getData().stones[Board.getIndex(BLACK_X, BLACK_Y)]);
    assertEquals(Stone.WHITE, pass.getData().stones[Board.getIndex(WHITE_X, WHITE_Y)]);
  }

  private static void setFrameField(LizzieFrame frame, String name, Object value)
      throws ReflectiveOperationException {
    java.lang.reflect.Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(frame, value);
  }

  private static final class LoadTrackingFrame extends LizzieFrame {
    boolean failPlayerUpdate;
    BoardHistoryList historyAtFailure;

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis) {}

    @Override
    protected void scheduleMovelistRefreshAfterKifuLoad() {}

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void setPlayers(String whitePlayer, String blackPlayer) {
      if (failPlayerUpdate) {
        historyAtFailure = Lizzie.board.getHistory();
        throw new IllegalStateException("player update failed");
      }
    }

    @Override
    public void updateTitle() {}

    @Override
    public void refresh() {}

    @Override
    public boolean stopAiPlayingAndPolicy() {
      return false;
    }
  }

  private static final class FailingReadBoard extends ReadBoard {
    int notifications;

    private FailingReadBoard() throws Exception {
      super(false, false);
    }

    @Override
    public void onHistoryOverwritten() {
      throw new IllegalStateException("history notification " + ++notifications);
    }
  }

  private static BoardHistoryNode lastRealAction(BoardHistoryNode root) {
    BoardHistoryNode node = root;
    while (node.next().isPresent()) {
      node = node.next().orElseThrow();
    }
    return node;
  }
}
