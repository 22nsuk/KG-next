package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontTextField;
import featurecat.lizzie.gui.LizzieFrame;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real console dispatch on EDT, with recording board actions/engine I/O and a real history. */
class GtpConsoleSettingsTest {
  private RulesLayerTestHarness rules;
  private Board realBoard;
  private RecordingBoard board;
  private RecordingEngine engine;
  private GtpConsolePane console;
  private GtpConsolePane previousConsole;
  private BottomToolbar previousToolbar;
  private Leelaz previousSecondary;

  @BeforeEach
  void setUp() throws Exception {
    previousConsole = Lizzie.gtpConsole;
    previousToolbar = LizzieFrame.toolbar;
    previousSecondary = Lizzie.leelaz2;
    rules = RulesLayerTestHarness.open(5);
    realBoard = rules.board();
    board = RulesLayerTestHarness.allocate(RecordingBoard.class);
    board.setHistory(realBoard.getHistory());
    Lizzie.board = board;
    engine = new RecordingEngine();
    engine.pda = 1.5;
    engine.pdaCap = 0.2;
    engine.isStaticPda = true;
    Lizzie.leelaz = engine;
    Lizzie.leelaz2 = null;
    EngineManager.isEmpty = false;
    LizzieFrame.toolbar = null;
    console = RulesLayerTestHarness.allocate(GtpConsolePane.class);
    SwingUtilities.invokeAndWait(
        () -> {
          setConsoleField("txtCommand", new JFontTextField());
          setConsoleField("resourceBundle", Lizzie.resourceBundle);
          LizzieFrame.menu.txtPDA = new JFontTextField("1.5");
          board.getHistory().getGameInfo().setKomi(7.5);
        });
    Lizzie.gtpConsole = console;
  }

  @AfterEach
  void tearDown() {
    Lizzie.gtpConsole = previousConsole;
    LizzieFrame.toolbar = previousToolbar;
    Lizzie.leelaz2 = previousSecondary;
    if (rules != null) rules.close();
  }

  @Test
  void missingKomiDoesNotInvalidateAnalysis() throws Exception {
    assertRejected("komi");
  }

  @Test
  void invalidDynamicPdaDoesNotResetStaticPda() throws Exception {
    assertRejected("dympdacap invalid");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "boardsize",
        "boardsize abc",
        "boardsize 0",
        "boardsize 1 9",
        "boardsize -2",
        "boardsize 5 0",
        "boardsize 50000",
        "boardsize 65536",
        "boardsize 2147483647 2",
        "boardsize 99999999999999999999",
        "boardsize 5 5 extra",
        "boardsize ５",
        "rectangular_boardsize 5 nope",
        "rectangular_boardsize 50000 50000",
        "komi 6.5 extra",
        "komi NaN",
        "komi Infinity",
        "komi -Infinity",
        "komi 1e309",
        "komi 1e100",
        "komi 0x1.8p2",
        "komi 6.5d",
        "komi --1",
        "komi .",
        "pda",
        "pda nope",
        "pda NaN",
        "pda Infinity",
        "pda -1e309",
        "pda 1 extra",
        "pda 1f",
        "dympdacap",
        "dympdacap NaN",
        "dympdacap -Infinity",
        "dympdacap 1e309",
        "dympdacap 1 extra",
        "getpda 1",
        "getdympdacap 1",
        "clear_board extra",
        "undo extra",
        "heatmap extra",
        "showboard extra"
      })
  void invalidSettingsLeaveBoardAnalysisAndEngineUntouched(String command) throws Exception {
    assertRejected(command);
  }

  @ParameterizedTest
  @CsvSource({
    "boardsize 9,9,9",
    "  BOARDSIZE\t13   9  ,13,9",
    "boardsize 52 53,52,53",
    "boardsize +7,7,7",
    "rectangular_boardsize 9 13,9,13"
  })
  void validDimensionsReachBoardOnceWithoutArtificialNineteenLineLimit(
      String command, int width, int height) throws Exception {
    submit(command);
    assertEquals(1, board.resizeCalls);
    assertEquals(width, board.width);
    assertEquals(height, board.height);
    assertTrue(engine.commands.isEmpty()); // Board.reopen owns engine forwarding, not the console.
  }

  @ParameterizedTest
  @CsvSource({"komi 6.5,6.5", "  KOMI\t-5e-1  ,-0.5", "komi +0,0", "komi .5,0.5"})
  void validKomiCommitsOnceAfterAdmission(String command, double value) throws Exception {
    submit(command);
    assertEquals(value, board.getHistory().getGameInfo().getKomi());
    assertTrue(board.getHistory().getGameInfo().changedKomi);
    assertEquals(1, board.analysisClears);
    assertEquals(List.of("komi " + value), engine.commands);
    assertEquals(1, engine.ponders);
    assertEquals(String.valueOf(value), LizzieFrame.menu.txtKomi.getText());
  }

  @ParameterizedTest
  @ValueSource(strings = {"komi 6.5", "pda -0.5", "dympdacap 0.3"})
  void rejectedAdmissionDoesNotPublishSettingsOrRestartPonder(String command) throws Exception {
    engine.accept = false;
    assertRejected(command);
    assertEquals(1, engine.admissionAttempts);
  }

  @ParameterizedTest
  @ValueSource(strings = {"komi 6.5", "pda -0.5", "dympdacap 0.3"})
  void synchronousSendFailureDoesNotPublishSettings(String command) throws Exception {
    engine.fail = true;
    assertThrows(IllegalStateException.class, () -> submit(command));
    assertSettingsUnchanged();
  }

  @Test
  void signedPdaAndDynamicResetPreserveTheCommandOrder() throws Exception {
    submit("  PDA\t-5e-1 ");
    assertEquals(-0.5, engine.pda);
    assertTrue(engine.isStaticPda);
    assertEquals(List.of("pda -0.5"), engine.commands);
    engine.commands.clear();
    submit(" DyMpDaCaP\t.3 ");
    assertFalse(engine.isStaticPda);
    assertEquals(0, engine.pda);
    assertEquals(0.3, engine.pdaCap);
    assertEquals("0.000", LizzieFrame.menu.txtPDA.getText());
    assertEquals(List.of("pda 0", "dympdacap 0.3"), engine.commands);
    assertEquals(2, engine.ponders);
  }

  @Test
  void rejectedPdaDoesNotResetSecondaryEngineSetting() throws Exception {
    RecordingEngine secondary = new RecordingEngine();
    secondary.pda = 2;
    Lizzie.leelaz2 = secondary;
    Lizzie.config.extraMode = ExtraMode.Double_Engine;
    engine.accept = false;
    assertRejected("pda 0");
    assertEquals(2, secondary.pda);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "boardsize_custom 5",
        "komi_extra 3",
        "pda_extra 1",
        "dympdacap_extra 2",
        "getpda_extra",
        "getdympdacap_extra",
        "Known_Extension  A\tB"
      })
  void unrelatedExtensionsAreForwardedWithoutLocalInterpretation(String command) throws Exception {
    submit(command);
    assertEquals(List.of(command), engine.commands);
    assertEquals(0, board.resizeCalls);
    assertEquals(0, board.analysisClears);
    assertEquals(1.5, engine.pda);
    assertTrue(engine.isStaticPda);
    assertEquals(0, engine.ponders);
  }

  @ParameterizedTest
  @ValueSource(strings = {"GETPDA", "GETDYMPDACAP", "SHOWBOARD"})
  void argumentFreeQueriesKeepTheirExistingPonderBehavior(String command) throws Exception {
    submit(command);
    assertEquals(List.of(command.toLowerCase(java.util.Locale.ROOT)), engine.commands);
    assertEquals(1, engine.ponders);
    assertEquals(0, board.analysisClears);
  }

  @Test
  void uppercaseUndoUsesLocalHistoryNotRawEngineOnly() throws Exception {
    submit("UNDO");
    assertEquals(1, board.undos);
    assertTrue(engine.commands.isEmpty());
  }

  @Test
  void reopenRejectsOverflowBeforeChangingDimensionsHistoryOrHashTables() {
    BoardHistoryNode original = realBoard.getHistory().getCurrentHistoryNode();
    Zobrist hash = new Zobrist();
    hash.toggleStone(1, 1, Stone.BLACK);
    assertThrows(IllegalArgumentException.class, () -> realBoard.reopen(50000, 50000));
    assertEquals(5, Board.boardWidth);
    assertEquals(5, Board.boardHeight);
    assertSame(original, realBoard.getHistory().getCurrentHistoryNode());
    Zobrist after = new Zobrist();
    after.toggleStone(1, 1, Stone.BLACK);
    assertEquals(hash, after);
  }

  @Test
  void realConsoleEnterAndButtonRejectInvalidSettingsAndAcceptValidKomi() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "requires display for real console actions");
    SwingUtilities.invokeAndWait(
        () -> {
          GtpConsolePane window = new GtpConsolePane(null);
          Lizzie.gtpConsole = window;
          try {
            window.txtCommand.setText("komi NaN");
            window.txtCommand.postActionEvent();
            assertSettingsUnchanged();
            window.txtCommand.setText("dympdacap invalid");
            window.send.doClick(0);
            assertSettingsUnchanged();
            window.txtCommand.setText("KOMI\t6.5");
            window.send.doClick(0);
            assertEquals(6.5, board.getHistory().getGameInfo().getKomi());
            assertEquals(List.of("komi 6.5"), engine.commands);
            assertEquals(1, board.analysisClears);
          } finally {
            window.dispose();
            Lizzie.gtpConsole = console;
          }
        });
  }

  private void assertRejected(String command) throws Exception {
    BoardHistoryNode before = board.getHistory().getCurrentHistoryNode();
    Stone[] stones = before.getData().stones.clone();
    Zobrist hash = before.getData().zobrist.clone();
    submit(command);
    assertSettingsUnchanged();
    assertSame(before, board.getHistory().getCurrentHistoryNode());
    assertArrayEquals(stones, before.getData().stones);
    assertEquals(hash, before.getData().zobrist);
    assertTrue(before.getData().blackToPlay);
    assertEquals(0, before.numberOfChildren());
    assertEquals(5, Board.boardWidth);
    assertEquals(5, Board.boardHeight);
  }

  private void assertSettingsUnchanged() {
    assertEquals(7.5, board.getHistory().getGameInfo().getKomi());
    assertFalse(board.getHistory().getGameInfo().changedKomi);
    assertEquals(0, board.analysisClears, "invalid command invalidated analysis");
    assertEquals(0, board.resizeCalls);
    assertEquals(0, board.clears);
    assertEquals(0, board.undos);
    assertEquals(1.5, engine.pda);
    assertEquals(0.2, engine.pdaCap);
    assertTrue(engine.isStaticPda);
    assertEquals("1.5", LizzieFrame.menu.txtPDA.getText());
    assertEquals("7.5", LizzieFrame.menu.txtKomi.getText());
    assertTrue(engine.commands.isEmpty(), engine.commands.toString());
    assertEquals(0, engine.ponders);
    assertEquals(0, engine.heatmaps);
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

  private void setConsoleField(String name, Object value) {
    try {
      Field field = GtpConsolePane.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(console, value);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class RecordingBoard extends Board {
    int resizeCalls, width, height, analysisClears, clears, undos;

    @Override
    public void reopen(int width, int height) {
      resizeCalls++;
      this.width = width;
      this.height = height;
    }

    @Override
    public void clearBestMovesAfter(BoardHistoryNode node) {
      analysisClears++;
    }

    @Override
    public void clear(boolean fromEngine) {
      clears++;
    }

    @Override
    public boolean previousMove(boolean refresh) {
      undos++;
      return true;
    }
  }

  private static final class RecordingEngine extends Leelaz {
    final List<String> commands = new ArrayList<>();
    boolean accept = true, fail;
    int ponders, heatmaps, admissionAttempts;

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
      return true;
    }

    @Override
    public void ponder() {
      ponders++;
    }

    @Override
    public void toggleHeatmap(boolean raw) {
      heatmaps++;
    }

    @Override
    public void sendCommand(String command) {
      commands.add(command);
    }

    @Override
    public boolean sendRawConsoleCommand(String command) {
      return admit(List.of(command));
    }

    @Override
    public boolean sendConsolePda(double value, boolean dynamic) {
      return admit(dynamic ? List.of("pda 0", "dympdacap " + value) : List.of("pda " + value));
    }

    private boolean admit(List<String> batch) {
      admissionAttempts++;
      if (fail) throw new IllegalStateException("test transport failure");
      if (accept) commands.addAll(batch);
      return accept;
    }
  }
}
