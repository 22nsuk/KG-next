package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontTextField;
import featurecat.lizzie.gui.LizzieFrame;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
  private final List<RecordingEngine> testEngines = new ArrayList<>();

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
    engine = newEngine();
    engine.pda = 1.5;
    engine.pdaCap = 0.2;
    engine.isStaticPda = true;
    Lizzie.setPrimaryEngine(engine);
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
  void tearDown() throws Exception {
    try {
      for (RecordingEngine testEngine : testEngines) testEngine.close();
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        Lizzie.gtpConsole = previousConsole;
        LizzieFrame.toolbar = previousToolbar;
        Lizzie.leelaz2 = previousSecondary;
        if (rules != null) rules.close();
      });
    }
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
    RecordingEngine secondary = newEngine();
    secondary.pda = 2;
    Lizzie.leelaz2 = secondary;
    Lizzie.config.extraMode = ExtraMode.Double_Engine;
    engine.accept = false;
    assertRejected("pda 0");
    assertEquals(2, secondary.pda);
  }

  @ParameterizedTest
  @CsvSource({
    "pda, -1.5", "pda, 0", "pda, 1.5",
    "dympdacap, -0.3", "dympdacap, 0", "dympdacap, 0.3"
  })
  void consolePdaUpdatesBothRecipientsBeforeSecondaryAnalysisIsSaved(String command, double value)
      throws Exception {
    RecordingEngine secondary = comparisonEngine();
    boolean dynamic = command.equals("dympdacap");

    submit(command + " " + value);

    List<String> expected = dynamic
        ? List.of("pda 0", "dympdacap " + value) : List.of("pda " + value);
    assertEquals(expected, engine.commands);
    assertEquals(expected, secondary.commands);
    double expectedPda = dynamic ? 0 : value;
    assertPdaState(engine, expectedPda, dynamic ? value : 0.2, !dynamic);
    assertPdaState(secondary, expectedPda, dynamic ? value : 0.7, !dynamic);
    assertEquals(dynamic ? "0.000" : String.valueOf(value), LizzieFrame.menu.txtPDA.getText());
    assertEquals(1, engine.ponders);
    assertEquals(0, board.analysisClears);
    assertSecondarySgfPda(secondary, expectedPda);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pda -1.5", "dympdacap 0.3"})
  void occupiedSecondaryRejectsBothStatesAndTheSameConsoleInputCanBeRetried(String command)
      throws Exception {
    RecordingEngine secondary = comparisonEngine();
    try (var reservation = secondary.beginExclusiveGtpLifecycleReservation()) {
      assertNotNull(reservation);
      assertRejected(command);
      assertPdaState(secondary, 2, 0.7, false);
      assertTrue(secondary.commands.isEmpty());
    }

    assertTrue(engine.commands.isEmpty());
    assertTrue(secondary.commands.isEmpty());
    submit(command);
    boolean dynamic = command.startsWith("dympdacap");
    assertPdaState(engine, dynamic ? 0 : -1.5, dynamic ? 0.3 : 0.2, !dynamic);
    assertPdaState(secondary, dynamic ? 0 : -1.5, dynamic ? 0.3 : 0.7, !dynamic);
    assertEquals(engine.commands, secondary.commands);
    assertEquals(dynamic ? 2 : 1, engine.commands.size());
    assertEquals(1, engine.ponders);
  }

  @ParameterizedTest
  @CsvSource({
    "false, pda 0", "false, dympdacap 0.3",
    "true, pda 0", "true, dympdacap 0.3"
  })
  void consolePdaDoesNotChangeAnEngineExcludedFromMirroring(boolean legacyLeela, String command)
      throws Exception {
    RecordingEngine secondary = comparisonEngine();
    if (legacyLeela) secondary.isLeela0110 = true;
    else Lizzie.config.extraMode = ExtraMode.Normal;

    submit(command);

    assertFalse(engine.commands.isEmpty());
    assertTrue(secondary.commands.isEmpty());
    assertPdaState(secondary, 2, 0.7, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"replacement", "reselection", "restart"})
  void changedPrimaryDuringSendDoesNotReceiveStaleConsolePublication(String change)
      throws Exception {
    RecordingEngine replacement = newEngine();
    replacement.pda = 4;
    replacement.pdaCap = 0.9;
    replacement.isStaticPda = false;
    engine.afterPdaAdmission = () -> {
      switch (change) {
        case "replacement" -> Lizzie.setPrimaryEngine(replacement);
        case "reselection" -> {
          Lizzie.setPrimaryEngine(replacement);
          Lizzie.setPrimaryEngine(engine);
        }
        case "restart" -> {
          engine.installOutput();
          engine.pda = 4;
          engine.pdaCap = 0.9;
          engine.isStaticPda = false;
        }
        default -> fail(change);
      }
    };

    submit("pda -0.5");

    assertEquals("1.5", LizzieFrame.menu.txtPDA.getText());
    assertEquals(0, engine.ponders);
    assertEquals(0, replacement.ponders);
    assertPdaState(replacement, 4, 0.9, false);
    if (change.equals("restart")) assertPdaState(engine, 4, 0.9, false);
  }

  @Test
  void mirrorChangedDuringSendDoesNotReceiveThePreviousRecipientsPda() throws Exception {
    RecordingEngine secondary = comparisonEngine();
    RecordingEngine replacement = newEngine();
    replacement.pda = 4;
    replacement.pdaCap = 0.9;
    replacement.isStaticPda = false;
    engine.afterPdaAdmission = () -> Lizzie.leelaz2 = replacement;

    submit("pda -1.5");

    assertPdaState(engine, -1.5, 0.2, true);
    assertPdaState(secondary, -1.5, 0.7, true);
    assertPdaState(replacement, 4, 0.9, false);
    assertTrue(replacement.commands.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"replacement", "reselection", "restart", "mirror-replacement", "mirror-restart"})
  void consoleAdmissionRejectsRecipientsChangedWhileWaitingForSelection(String change)
      throws Exception {
    RecordingEngine secondary = comparisonEngine();
    RecordingEngine replacement = newEngine();
    Field selectionField = EngineManager.class.getDeclaredField("ENGINE_SELECTION_STATE_LOCK");
    selectionField.setAccessible(true);
    Object selection = selectionField.get(null);
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    Thread sender = new Thread(() -> {
      try { result.complete(engine.sendConsolePda(0.3, true)); }
      catch (Throwable failure) { result.completeExceptionally(failure); }
    }, "console-pda-selection-race");
    try {
      synchronized (selection) {
        sender.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (sender.isAlive() && sender.getState() != Thread.State.BLOCKED
            && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(Thread.State.BLOCKED, sender.getState(), "admission must wait for selection");
        switch (change) {
          case "replacement" -> Lizzie.setPrimaryEngine(replacement);
          case "reselection" -> {
            Lizzie.setPrimaryEngine(replacement);
            Lizzie.setPrimaryEngine(engine);
          }
          case "restart" -> engine.installOutput();
          case "mirror-replacement" -> Lizzie.leelaz2 = replacement;
          case "mirror-restart" -> secondary.installOutput();
          default -> fail(change);
        }
      }
      assertFalse(result.get(3, TimeUnit.SECONDS));
      assertSettingsUnchanged();
      assertPdaState(secondary, 2, 0.7, false);
      assertTrue(secondary.commands.isEmpty());
      assertTrue(replacement.commands.isEmpty());
    } finally {
      sender.join(3000);
      assertFalse(sender.isAlive());
    }
  }

  @ParameterizedTest
  @CsvSource({"false, false", "false, true", "true, false", "true, true"})
  void consolePdaRequiresBothRecipientsToBeReady(boolean second, boolean stopped) throws Exception {
    RecordingEngine secondary = comparisonEngine();
    RecordingEngine target = second ? secondary : engine;
    if (stopped) target.started = false;
    else target.isLoaded = false;

    assertRejected("dympdacap 0.3");

    assertPdaState(secondary, 2, 0.7, false);
    assertTrue(secondary.commands.isEmpty());
  }

  @Test
  void consolePdaWithoutAReaderDoesNotPublishState() throws Exception {
    Leelaz unbound = new Leelaz("");
    unbound.started = unbound.isLoaded = true;
    unbound.pda = 1.5;
    Lizzie.setPrimaryEngine(unbound);
    assertNull(unbound.engineIncarnationToken());

    assertFalse(unbound.sendConsolePda(0.3, true));

    assertEquals(1.5, unbound.pda);
    assertNull(unbound.engineIncarnationToken());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptedConsolePdaQueuedBeforeReaderReplacementCannotReachTheNewOutput(boolean dynamic)
      throws Exception {
    engine.requireResponseBeforeSend = true;
    engine.withholdResponses = true;
    engine.sendCommandNoLeelaz2("protocol_version");
    assertEquals(List.of("protocol_version"), engine.commands);
    assertTrue(engine.sendConsolePda(0.3, dynamic));
    assertEquals(List.of("protocol_version"), engine.commands, "PDA must still be queued");

    engine.installOutput();
    engine.withholdResponses = false;
    engine.pda = 4;
    engine.pdaCap = 0.9;
    engine.isStaticPda = false;
    engine.sendCommandNoLeelaz2("protocol_version");
    engine.awaitIdle();

    assertEquals(List.of("protocol_version"),
        engine.transports.get(engine.transports.size() - 1).commands());
    assertPdaState(engine, 4, 0.9, false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void consolePdaRetiresOldAnalysisBeforeEitherRecipientReceivesTheCommand(boolean dynamic)
      throws Exception {
    Lizzie.frame = RulesLayerTestHarness.allocate(AnalysisFrame.class);
    LizzieFrame.toolbar = RulesLayerTestHarness.allocate(BottomToolbar.class);
    RecordingEngine secondary = comparisonEngine();
    engine.isKatago = secondary.isKatago = true;
    engine.startAnalysis();
    engine.awaitIdle();
    secondary.awaitIdle();
    engine.info(100);
    secondary.info(100);
    assertEquals(100, board.getData().getPlayouts());
    assertEquals(100, board.getData().getPlayouts2());
    assertEquals(1.5, board.getData().pda);
    assertEquals(2, board.getData().pda2);

    // The primary is writing PDA, before either engine has replied or the mirror receives it.
    engine.beforePdaWrite = () -> {
      assertTrue(secondary.commands.stream().noneMatch(c -> c.startsWith("pda ")));
      engine.info(200);
      secondary.info(200);
    };
    assertTrue(engine.sendConsolePda(dynamic ? 0.3 : -1.5, dynamic));
    engine.awaitIdle();
    secondary.awaitIdle();
    assertEquals(100, board.getData().getPlayouts());
    assertEquals(100, board.getData().getPlayouts2());
    BoardData saved = SGFParser.parseSgf(SGFParser.saveToString(false), true).getData();
    assertEquals(1.5, saved.pda);
    assertEquals(2, saved.pda2);
    assertEquals(100, saved.getPlayouts());
    assertEquals(100, saved.getPlayouts2());

    // A newly written analysis command must still publish results with the admitted PDA.
    engine.startAnalysis();
    engine.awaitIdle();
    secondary.awaitIdle();
    engine.info(300);
    secondary.info(300);
    saved = SGFParser.parseSgf(SGFParser.saveToString(false), true).getData();
    assertEquals(dynamic ? 0 : -1.5, saved.pda);
    assertEquals(dynamic ? 0 : -1.5, saved.pda2);
    assertEquals(300, saved.getPlayouts());
    assertEquals(300, saved.getPlayouts2());
  }

  @Test
  void consolePdaRetiresAnOlderAnalysisAlreadyWaitingToWrite() throws Exception {
    engine.isKatago = true;
    Field fenceField = EngineManager.class.getDeclaredField("ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK");
    fenceField.setAccessible(true);
    var fence = (java.util.concurrent.locks.ReentrantLock) fenceField.get(null);
    Field writerField = Leelaz.class.getDeclaredField("normalCommandBeingSent");
    writerField.setAccessible(true);
    Method queueMethod = Leelaz.class.getDeclaredMethod("commandQueue");
    queueMethod.setAccessible(true);
    Object queue = queueMethod.invoke(engine);
    CompletableFuture<Void> analysis;
    fence.lock();
    try {
      analysis = CompletableFuture.runAsync(engine::startAnalysis);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (true) {
        synchronized (queue) {
          if (writerField.get(engine) != null) break;
        }
        if (System.nanoTime() >= deadline) fail("Analysis did not reach the physical writer");
        Thread.sleep(5);
      }
      assertTrue(engine.sendConsolePda(-1.5, false));
    } finally {
      fence.unlock();
    }
    analysis.get(3, TimeUnit.SECONDS);
    engine.awaitIdle();
    assertEquals(List.of("pda -1.5"), engine.commands);
    engine.startAnalysis();
    engine.awaitIdle();
    assertEquals(List.of("pda -1.5", "kata-analyze B 10"), engine.commands);
  }

  private RecordingEngine comparisonEngine() throws IOException {
    RecordingEngine secondary = newEngine();
    secondary.pda = 2;
    secondary.pdaCap = 0.7;
    secondary.isStaticPda = false;
    Lizzie.config.extraMode = ExtraMode.Double_Engine;
    Lizzie.leelaz2 = secondary;
    return secondary;
  }

  private RecordingEngine newEngine() throws IOException {
    RecordingEngine result = new RecordingEngine();
    testEngines.add(result);
    return result;
  }

  private static void assertPdaState(Leelaz target, double pda, double cap, boolean isStatic) {
    assertEquals(pda, target.pda);
    assertEquals(cap, target.pdaCap);
    assertEquals(isStatic, target.isStaticPda);
  }

  private void assertSecondarySgfPda(Leelaz secondary, double pda) throws IOException {
    MoveData move = new MoveData();
    move.coordinate = "C3";
    move.playouts = 100;
    move.winrate = 55;
    move.scoreMean = 1.25;
    move.scoreStdev = 2;
    move.isKataData = true;
    move.variation = List.of("C3", "D4");
    BoardData data = board.getHistory().getData();
    data.tryToSetBestMoves2FromEngine(
        new ArrayList<>(List.of(move)), "console-secondary", secondary, 100, null);
    assertEquals(pda, data.pda2);

    String sgf = SGFParser.saveToString(false);
    assertTrue(sgf.contains("LZ2[") || sgf.contains("LZOP2["), sgf);
    BoardData reopened = SGFParser.parseSgf(sgf, true).getData();
    assertEquals(pda, reopened.pda2);
    assertEquals(100, reopened.getPlayouts2());
    assertEquals("C3", reopened.bestMoves2.get(0).coordinate);
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
    for (RecordingEngine testEngine : testEngines) testEngine.awaitIdle();
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

  private static final class AnalysisFrame extends LizzieFrame {
    @Override
    public void requestAnalysisRefresh() {}

    @Override
    public void requestAnalysisTitleUpdate() {}
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

  private static final class RecordingEngine extends Leelaz implements AutoCloseable {
    final List<String> commands = new CopyOnWriteArrayList<>();
    final List<ExactSnapshotRestoreProtocolFixture.Transport> transports = new ArrayList<>();
    boolean accept = true, fail;
    int ponders, heatmaps, admissionAttempts;
    Runnable afterPdaAdmission;
    Runnable beforePdaWrite;
    boolean withholdResponses;

    RecordingEngine() throws IOException {
      super("");
      started = isLoaded = true;
      installOutput();
    }

    void installOutput() {
      try {
        var transport = ExactSnapshotRestoreProtocolFixture.installAsync(this, command -> {
          commands.add(command);
          if (command.startsWith("pda ") && beforePdaWrite != null) {
            Runnable callback = beforePdaWrite;
            beforePdaWrite = null;
            callback.run();
          }
          return withholdResponses ? null : ExactSnapshotRestoreProtocolFixture.Response.success();
        });
        var install = Leelaz.class.getDeclaredMethod("installFreshCommandOutputForTest", OutputStream.class);
        install.setAccessible(true);
        install.invoke(this, transport);
        transports.add(transport);
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    }

    void startAnalysis() {
      assertTrue(super.sendRawConsoleCommand("kata-analyze B 10"));
    }

    void info(int visits) {
      try {
        Method parse = Leelaz.class.getDeclaredMethod("parseLine", String.class);
        parse.setAccessible(true);
        parse.invoke(this, "info move C3 visits " + visits
            + " winrate 0.55 scoreMean 1.25 scoreStdev 2 pv C3 D4");
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    }

    void awaitIdle() throws Exception {
      var queueMethod = Leelaz.class.getDeclaredMethod("commandQueue");
      queueMethod.setAccessible(true);
      var queue = (java.util.Deque<?>) queueMethod.invoke(this);
      Field writing = Leelaz.class.getDeclaredField("normalCommandSendInProgress");
      writing.setAccessible(true);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (true) {
        synchronized (queue) {
          if (queue.isEmpty() && !writing.getBoolean(this)) break;
        }
        if (System.nanoTime() >= deadline) fail("Console command output did not finish");
        Thread.sleep(5);
      }
      for (var transport : transports) transport.awaitResponses();
    }

    @Override
    public void close() throws Exception {
      awaitIdle();
      for (var transport : transports) transport.close();
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
      admissionAttempts++;
      if (fail) throw new IllegalStateException("test transport failure");
      boolean accepted = accept && super.sendConsolePda(value, dynamic);
      if (accepted && afterPdaAdmission != null) {
        try { awaitIdle(); }
        catch (Exception failure) { throw new AssertionError(failure); }
        Runnable callback = afterPdaAdmission;
        afterPdaAdmission = null;
        callback.run();
      }
      return accepted;
    }

    private boolean admit(List<String> batch) {
      admissionAttempts++;
      if (fail) throw new IllegalStateException("test transport failure");
      if (accept) commands.addAll(batch);
      return accept;
    }
  }
}
