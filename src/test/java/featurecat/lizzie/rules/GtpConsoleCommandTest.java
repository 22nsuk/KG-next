package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontTextField;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Uses the real console dispatcher and Board; only engine I/O and unrelated GUI work are stubbed.
 */
class GtpConsoleCommandTest {
  private RulesLayerTestHarness rules;
  private GtpConsolePane previousConsole;
  private GtpConsolePane console;
  private RecordingEngine engine;

  @BeforeEach
  void setUp() throws Exception {
    previousConsole = Lizzie.gtpConsole;
    rules = RulesLayerTestHarness.open(5);
    engine = new RecordingEngine();
    Lizzie.leelaz = engine;
    EngineManager.isEmpty = false;
    // Most cases are headless, but call the unchanged production postCommand entry point on EDT.
    console = RulesLayerTestHarness.allocate(GtpConsolePane.class);
    SwingUtilities.invokeAndWait(
        () -> {
          setField(console, "txtCommand", new JFontTextField());
          setField(console, "resourceBundle", Lizzie.resourceBundle);
        });
    Lizzie.gtpConsole = console;
  }

  @AfterEach
  void tearDown() {
    Lizzie.gtpConsole = previousConsole;
    if (rules != null) rules.close();
  }

  @Test
  void missingPlayVertexDoesNotChangeTurn() throws Exception {
    assertRejectedWithoutPositionChange("play w");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "play",
        "play b",
        "play w D4 extra",
        "play wrong D4",
        "play blackish D4",
        "play w invalid",
        "play w resign",
        "play w I1",
        "play w A0",
        "play w A6",
        "play w A50",
        "play w A-1",
        "play w F1",
        "play w AA1",
        "play w AAA1",
        "play w A99999999999999999999",
        "play w D4suffix",
        "genmove",
        "genmove invalid",
        "genmove w extra"
      })
  void malformedMoveCommandsDoNotChangeBoardOrReachEngine(String command) throws Exception {
    for (boolean blackToPlay : new boolean[] {true, false}) {
      rules.current().getData().blackToPlay = blackToPlay;
      assertRejectedWithoutPositionChange(command);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"b", "black", "BLACK", "w", "white", "WHITE"})
  void explicitColorPlayDoesNotRewriteTheParentTurn(String color) throws Exception {
    boolean black = color.equalsIgnoreCase("b") || color.equalsIgnoreCase("black");
    BoardHistoryNode parent = rules.current();
    parent.getData().blackToPlay = !black;
    submit("  PLAY\t" + color + "   d4  ");
    assertEquals(!black, parent.getData().blackToPlay);
    assertEquals(black ? Stone.BLACK : Stone.WHITE, rules.stoneAt(3, 1));
    assertEquals(!black, rules.current().getData().blackToPlay);
    assertEquals(1, rules.current().getData().moveNumber);
    assertEquals(List.of("play " + (black ? "B" : "W") + " D4"), engine.commands);
  }

  @ParameterizedTest
  @ValueSource(strings = {"b", "BLACK", "w", "White"})
  void onlyExplicitPassPassesWithTheRequestedColor(String color) throws Exception {
    boolean black = color.equalsIgnoreCase("b") || color.equalsIgnoreCase("black");
    BoardHistoryNode parent = rules.current();
    parent.getData().blackToPlay = !black;
    submit("play " + color + " PaSs");
    assertEquals(!black, parent.getData().blackToPlay);
    assertTrue(rules.current().getData().isPassNode());
    assertEquals(black ? Stone.BLACK : Stone.WHITE, rules.current().getData().lastMoveColor);
    assertEquals(!black, rules.current().getData().blackToPlay);
    assertEquals(List.of("play " + (black ? "B" : "W") + " pass"), engine.commands);
  }

  @Test
  void occupiedPointCannotChangeTheTurn() throws Exception {
    rules.board().setupPlaceStone(1, 1, Stone.BLACK);
    rules.current().getData().blackToPlay = true;
    engine.commands.clear();
    assertRejectedWithoutPositionChange("play w B4");
  }

  @ParameterizedTest
  @CsvSource({"b, w, D4", "w, b, D4", "b, w, pass", "w, b, pass"})
  void replayWithOppositeColorPreservesTheOriginalBranchAndSendsTheNewColor(
      String originalColor, String requestedColor, String vertex) throws Exception {
    BoardHistoryNode parent = rules.current();
    submit("play " + originalColor + " " + vertex);
    BoardHistoryNode original = rules.current();
    original.getData().comment = "preserve original continuation";
    submit("play " + requestedColor + " A1");
    BoardHistoryNode continuation = rules.current();
    assertTrue(rules.board().getHistory().previous().isPresent());
    assertTrue(rules.board().getHistory().previous().isPresent());
    boolean parentTurn = parent.getData().blackToPlay;
    engine.commands.clear();

    submit("play " + requestedColor + " " + vertex);

    Stone requested = requestedColor.equals("b") ? Stone.BLACK : Stone.WHITE;
    assertNotSame(original, rules.current());
    assertEquals(requested, rules.current().getData().lastMoveColor);
    assertEquals(requested == Stone.WHITE, rules.current().getData().blackToPlay);
    assertEquals(1, rules.current().getData().moveNumber);
    assertEquals(parentTurn, parent.getData().blackToPlay);
    assertEquals("pass".equals(vertex), rules.current().getData().isPassNode());
    if (!"pass".equals(vertex)) assertEquals(requested, rules.stoneAt(3, 1));
    assertEquals(2, parent.numberOfChildren());
    assertSame(original, parent.getVariation(0).orElseThrow());
    assertSame(continuation, original.next().orElseThrow());
    assertEquals("preserve original continuation", original.getData().comment);
    assertEquals(
        List.of("play " + requestedColor.toUpperCase(Locale.ROOT) + " " + vertex), engine.commands);
  }

  @ParameterizedTest
  @CsvSource({"b, D4", "w, D4", "b, pass", "w, pass"})
  void replayWithSameColorReusesTheOriginalNodeAndSendsExactlyOneMove(
      String color, String vertex) throws Exception {
    BoardHistoryNode parent = rules.current();
    submit("play " + color + " " + vertex);
    BoardHistoryNode original = rules.current();
    original.getData().comment = "keep analysis and comment";
    assertTrue(rules.board().getHistory().previous().isPresent());
    engine.commands.clear();

    submit("play " + color + " " + vertex);

    assertSame(original, rules.current());
    assertEquals(1, parent.numberOfChildren());
    assertEquals("keep analysis and comment", rules.current().getData().comment);
    assertEquals(List.of("play " + color.toUpperCase(Locale.ROOT) + " " + vertex), engine.commands);
  }

  @ParameterizedTest
  @CsvSource({
    "true, false, b, D4",
    "true, false, w, D4",
    "true, true, w, D4",
    "false, true, w, D4",
    "false, true, b, D4",
    "false, false, b, D4",
    "true, false, b, pass",
    "true, false, w, pass",
    "true, true, w, pass",
    "false, true, w, pass",
    "false, true, b, pass",
    "false, false, b, pass"
  })
  void humanGameRejectsConsolePlayOutsideTheHumanTurn(
      boolean humanIsBlack, boolean blackToPlay, String color, String vertex) throws Exception {
    Lizzie.frame.isPlayingAgainstLeelaz = true;
    Lizzie.frame.playerIsBlack = humanIsBlack;
    rules.current().getData().blackToPlay = blackToPlay;
    assertRejectedWithoutPositionChange("play " + color + " " + vertex);
  }

  @ParameterizedTest
  @CsvSource({"true, b, D4", "false, w, D4", "true, b, pass", "false, w, pass"})
  void humanGameAcceptsTheHumanMoveAndRequestsExactlyOneReply(
      boolean humanIsBlack, String color, String vertex) throws Exception {
    Lizzie.frame.isPlayingAgainstLeelaz = true;
    Lizzie.frame.playerIsBlack = humanIsBlack;
    BoardHistoryNode parent = rules.current();
    parent.getData().blackToPlay = humanIsBlack;
    submit("play " + color + " " + vertex);
    assertEquals(humanIsBlack, parent.getData().blackToPlay);
    assertEquals(humanIsBlack ? Stone.BLACK : Stone.WHITE, rules.current().getData().lastMoveColor);
    assertEquals(!humanIsBlack, rules.current().getData().blackToPlay);
    assertEquals(1, rules.current().getData().moveNumber);
    assertEquals("pass".equals(vertex), rules.current().getData().isPassNode());
    if (!"pass".equals(vertex)) {
      assertEquals(humanIsBlack ? Stone.BLACK : Stone.WHITE, rules.stoneAt(3, 1));
    }
    assertEquals(2, engine.commands.size());
    assertEquals("play " + (humanIsBlack ? "B" : "W") + " " + vertex, engine.commands.get(0));
    assertEquals(
        "genmove " + (humanIsBlack ? "w" : "b"), engine.commands.get(1).toLowerCase(Locale.ROOT));
  }

  @Test
  void suicidalMoveCannotChangeTheTurn() throws Exception {
    rules.board().setupPlaceStone(1, 0, Stone.BLACK);
    rules.board().setupPlaceStone(0, 1, Stone.BLACK);
    rules.current().getData().blackToPlay = true;
    engine.commands.clear();
    assertRejectedWithoutPositionChange("play w A5");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "playout_count",
        "play_extra w D4",
        "genmove_debug w",
        "lz-genmove_analyze w 10",
        "kata-genmove_analyze w 10"
      })
  void commandPrefixesAreNotLocalMoves(String command) throws Exception {
    BoardHistoryNode parent = rules.current();
    submit(command);
    assertSame(parent, rules.current());
    assertTrue(parent.getData().blackToPlay);
    assertEquals(0, parent.numberOfChildren());
    assertEquals(List.of("raw " + command), engine.commands);
  }

  @Test
  void genmoveValidatesColorAndRestoresTurnOnAdmissionFailure() throws Exception {
    rules.current().getData().blackToPlay = false;
    engine.acceptGenmove = false;
    submit("genmove BLACK");
    assertFalse(rules.current().getData().blackToPlay);
    assertEquals(List.of("genmove b"), engine.commands);
    engine.acceptGenmove = true;
    submit("genmove BLACK");
    assertTrue(rules.current().getData().blackToPlay);
  }

  @Test
  void genmoveRestoresTurnBeforePropagatingUnexpectedFailure() {
    engine.failGenmove = true;
    assertThrows(IllegalStateException.class, () -> submit("genmove w"));
    assertTrue(rules.current().getData().blackToPlay);
  }

  @Test
  void enterAndSendButtonUseTheValidatedDispatcher() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display for the real console window");
    SwingUtilities.invokeAndWait(
        () -> {
          GtpConsolePane window = new GtpConsolePane(null);
          Lizzie.gtpConsole = window;
          try {
            BoardHistoryNode parent = rules.current();
            window.txtCommand.setText("play w");
            window.txtCommand.postActionEvent();
            assertSame(parent, rules.current());
            assertTrue(parent.getData().blackToPlay);
            assertTrue(engine.commands.isEmpty());
            window.txtCommand.setText("play w A50");
            window.send.doClick(0);
            assertSame(parent, rules.current());
            assertTrue(parent.getData().blackToPlay);
            assertTrue(engine.commands.isEmpty());
            window.txtCommand.setText("play white D4");
            window.send.doClick(0);
            assertEquals(Stone.WHITE, rules.stoneAt(3, 1));
            assertTrue(parent.getData().blackToPlay);
            assertEquals(List.of("play W D4"), engine.commands);
          } finally {
            window.dispose();
            Lizzie.gtpConsole = console;
          }
        });
  }

  private void assertRejectedWithoutPositionChange(String command) throws Exception {
    BoardHistoryNode before = rules.current();
    BoardData data = before.getData();
    Stone[] stones = data.stones.clone();
    int[] moveNumbers = data.moveNumberList.clone();
    Zobrist hash = data.zobrist.clone();
    boolean blackToPlay = data.blackToPlay;
    int children = before.numberOfChildren();
    int moves = data.moveNumber;
    int blackCaptures = data.blackCaptures;
    int whiteCaptures = data.whiteCaptures;
    submit(command);
    assertEquals(blackToPlay, data.blackToPlay, command + " changed side to play");
    assertSame(before, rules.current(), command + " advanced history");
    assertArrayEquals(stones, data.stones);
    assertArrayEquals(moveNumbers, data.moveNumberList);
    assertEquals(hash, data.zobrist);
    assertEquals(children, before.numberOfChildren());
    assertEquals(moves, data.moveNumber);
    assertEquals(blackCaptures, data.blackCaptures);
    assertEquals(whiteCaptures, data.whiteCaptures);
    assertFalse(engine.isThinking);
    assertFalse(engine.isInputCommand);
    assertTrue(engine.commands.isEmpty(), command + " reached engine: " + engine.commands);
  }

  private void submit(String command) throws Exception {
    Method post = GtpConsolePane.class.getDeclaredMethod("postCommand", ActionEvent.class);
    post.setAccessible(true);
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            console.txtCommand.setText(command);
            try {
              post.invoke(
                  console,
                  new ActionEvent(console.txtCommand, ActionEvent.ACTION_PERFORMED, command));
            } catch (InvocationTargetException failure) {
              if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
              if (failure.getCause() instanceof Error error) throw error;
              throw new AssertionError(failure.getCause());
            } catch (IllegalAccessException failure) {
              throw new AssertionError(failure);
            }
          });
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      if (failure.getCause() instanceof Error error) throw error;
      throw failure;
    }
  }

  private static void setField(Object target, String name, Object value) {
    try {
      Field field = GtpConsolePane.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class RecordingEngine extends Leelaz {
    final List<String> commands = new ArrayList<>();
    boolean acceptGenmove = true;
    boolean failGenmove;

    RecordingEngine() throws IOException {
      super("");
    }

    @Override
    public boolean isLoaded() {
      return true;
    }

    @Override
    public boolean isStarted() {
      return true;
    }

    @Override
    public boolean isPondering() {
      return false;
    }

    @Override
    public void maybeAjustPDA(BoardHistoryNode node) {}

    @Override
    public void playMove(Stone color, String move) {
      commands.add("play " + (color == Stone.BLACK ? "B" : "W") + " " + move);
    }

    @Override
    public void playMove(Stone color, String move, boolean addPlayer, boolean blackToPlay) {
      playMove(color, move);
    }

    @Override
    public boolean genmove(String color, boolean inputCommand) {
      commands.add("genmove " + color);
      if (failGenmove) throw new IllegalStateException("test admission failure");
      return acceptGenmove;
    }

    @Override
    public boolean sendRawConsoleCommand(String command) {
      commands.add("raw " + command);
      return true;
    }
  }
}
