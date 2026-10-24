package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.awt.event.ActionEvent;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.LeelazResponseTestBridge;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Real command output, reader dispatcher and Board; no engine process or network. */
class ConsoleGenmoveRequestTest {
  private RulesLayerTestHarness rules;
  private GtpConsolePane previousConsole;
  private Leelaz engine;
  private ByteArrayOutputStream output;

  @BeforeEach
  void setUp() throws Exception {
    rules = RulesLayerTestHarness.open(19);
    previousConsole = Lizzie.gtpConsole;
    Lizzie.gtpConsole = RulesLayerTestHarness.allocate(GtpConsolePane.class);
    engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = true;
    Lizzie.setPrimaryEngine(engine);
    output = new ByteArrayOutputStream();
    LeelazResponseTestBridge.installOutput(engine, output);
  }

  @AfterEach
  void tearDown() {
    LeelazResponseTestBridge.reset(engine);
    Lizzie.gtpConsole = previousConsole;
    rules.close();
  }

  private void line(String text) throws Exception {
    LeelazResponseTestBridge.dispatch(engine, text);
  }

  private int request(boolean analyze, String color) {
    engine.isKatago = analyze;
    assertTrue(engine.genmove(color, true));
    String command = output.toString(StandardCharsets.UTF_8).lines()
        .filter(row -> row.contains("genmove")).reduce((first, second) -> second)
        .orElseThrow(() -> new AssertionError("Missing genmove: " + output.toString(StandardCharsets.UTF_8)));
    assertTrue(command.matches("[0-9]+ .*genmove.*"), command);
    return Integer.parseInt(command.substring(0, command.indexOf(' ')));
  }

  private void response(int id, String move) throws Exception {
    line("=" + id + " " + move);
    line("");
  }

  private void idle() {
    assertFalse(engine.isThinking);
    assertFalse(engine.isInputCommand);
  }

  static Stream<Arguments> consoleGameMoves() {
    return Stream.of("review", "human", "analysis").flatMap(mode ->
        Stream.of(false, true).flatMap(analyze ->
            Stream.of("b", "w").flatMap(color ->
                Stream.of("D4", "pass").map(move -> Arguments.of(mode, analyze, color, move)))));
  }

  @Test
  void manualGenmoveRefreshesBreadthOnStartAndFailedCompletion() throws Exception {
    featurecat.lizzie.gui.Menu previousMenu = LizzieFrame.menu;
    var previousEngineMenu = featurecat.lizzie.gui.Menu.engineMenu;
    featurecat.lizzie.gui.Menu menu =
        RulesLayerTestHarness.allocate(featurecat.lizzie.gui.Menu.class);
    var checkbox = new featurecat.lizzie.gui.JFontCheckBox();
    Field checkboxField = featurecat.lizzie.gui.Menu.class.getDeclaredField("chkWRN");
    checkboxField.setAccessible(true);
    checkboxField.set(menu, checkbox);
    menu.txtWRN = new JFontTextField();
    try {
      engine.isKatago = true;
      SwingUtilities.invokeAndWait(
          () -> {
            LizzieFrame.menu = menu;
            featurecat.lizzie.gui.Menu.engineMenu = null;
            checkbox.setSelected(true);
            menu.txtWRN.setText("0.37");
            menu.refreshBreadthAdmission();
            assertTrue(checkbox.isEnabled());
          });
      int id = request(true, "b");
      SwingUtilities.invokeAndWait(
          () -> {
            assertFalse(checkbox.isEnabled());
            assertFalse(menu.txtWRN.isEnabled());
          });
      line("?" + id + " controlled rejection");
      line("");
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(checkbox.isEnabled());
            assertTrue(menu.txtWRN.isEnabled());
            assertEquals("0.37", menu.txtWRN.getText());
          });
      idle();
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            LizzieFrame.menu = previousMenu;
            featurecat.lizzie.gui.Menu.engineMenu = previousEngineMenu;
          });
    }
  }

  @Test
  void pendingImportedPositionRejectsConsoleGenmoveUntilAlignmentIsConfirmed() throws Exception {
    Board board = Lizzie.board;
    board.requireEngineAlignment();
    assertFalse(engine.genmove("w", true));
    assertEquals("", output.toString(StandardCharsets.UTF_8));
    idle();
    board.completeEngineAlignment(board.getHistory().getStart(),
        board.getHistory().getCurrentHistoryNode(), board.getContextRevision());
    int id = request(false, "w");
    response(id, "D4");
    assertEquals(Stone.WHITE, board.getData().lastMoveColor);
    idle();
  }

  static Stream<Arguments> outputAdmissionChanges() {
    return Stream.of(false, true).flatMap(analyze ->
        Stream.of("unchanged", "navigation", "alignment", "primary", "primary-return", "manager")
            .map(change -> Arguments.of(analyze, change)));
  }

  @ParameterizedTest
  @MethodSource("outputAdmissionChanges")
  void outputAdmissionRechecksTargetWithoutTakingBoardMonitor(boolean analyze, String change)
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    engine.isKatago = analyze;
    EngineManager.isEmpty = false; // Preserve real Board -> engine undo forwarding.
    rules.board().getHistory().place(3, 3, Stone.BLACK);
    Field lockField =
        EngineManager.class.getDeclaredField("ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK");
    lockField.setAccessible(true);
    ReentrantLock admission = (ReentrantLock) lockField.get(null);
    AtomicReference<Thread> writer = new AtomicReference<>();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      admission.lock();
      try {
        Future<Boolean> sent = executor.submit(() -> {
          writer.set(Thread.currentThread());
          return engine.genmove("w", true);
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((writer.get() == null || !admission.hasQueuedThread(writer.get()))
            && System.nanoTime() < deadline) Thread.sleep(1);
        assertNotNull(writer.get());
        assertTrue(admission.hasQueuedThread(writer.get()),
            "genmove must reach physical output admission after its initial Board check");
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        synchronized (rules.board()) {
          long revision = rules.board().getContextRevision();
          long generation = Lizzie.capturePrimaryEngineGeneration(engine);
          if (change.equals("navigation")) assertTrue(rules.board().previousMove(false));
          else if (change.equals("alignment")) rules.board().requireEngineAlignment();
          else if (change.equals("primary") || change.equals("primary-return")) {
            Lizzie.setPrimaryEngine(new Leelaz(""));
            if (change.equals("primary-return")) Lizzie.setPrimaryEngine(engine);
            assertNotEquals(generation, Lizzie.capturePrimaryEngineGeneration(engine));
            assertEquals(revision, rules.board().getContextRevision());
          } else if (change.equals("manager")) {
            Lizzie.setEngineManager(new EngineManager(List.of(engine)));
            assertEquals(generation, Lizzie.capturePrimaryEngineGeneration(engine));
            assertEquals(revision, rules.board().getContextRevision());
          }
          admission.unlock();
          // Keep Board held while the writer finishes: a synchronized post-admission check
          // would recreate the Board -> admission / admission -> Board lock inversion.
          sent.get(3, TimeUnit.SECONDS);
        }
      } finally {
        if (admission.isHeldByCurrentThread()) admission.unlock();
      }
      String commands = output.toString(StandardCharsets.UTF_8);
      if (change.equals("unchanged")) {
        assertEquals(1, commands.lines().filter(row -> row.contains("genmove")).count());
        int id = Integer.parseInt(commands.substring(0, commands.indexOf(' ')));
        response(id, "D4");
        assertEquals(2, rules.current().getData().moveNumber);
        assertEquals(Stone.WHITE, rules.current().getData().lastMoveColor);
      } else {
        assertFalse(commands.contains("genmove"), "obsolete genmove reached the engine: " + commands);
        if (change.equals("navigation")) {
          assertEquals(1, commands.lines().count());
          assertTrue(commands.trim().endsWith(" undo"), commands);
          assertEquals(0, rules.current().getData().moveNumber);
          int undoId = Integer.parseInt(commands.substring(0, commands.indexOf(' ')));
          response(undoId, "");
        } else {
          assertEquals("", commands);
          if (change.equals("alignment")) {
            rules.board().completeEngineAlignment(rules.board().getHistory().getStart(),
                rules.current(), rules.board().getContextRevision());
          }
        }
        idle();
        if (change.equals("primary")) Lizzie.setPrimaryEngine(engine);
        if (change.equals("manager")) Lizzie.setEngineManager(previousManager);
        // Cancellation must retire the old request and leave the queue usable.
        int id = request(analyze, "w");
        response(id, "D4");
        assertEquals(Stone.WHITE, rules.current().getData().lastMoveColor);
      }
      idle();
    } finally {
      executor.shutdownNow();
      try {
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      } finally {
        Lizzie.setEngineManager(previousManager);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("consoleGameMoves")
  void consoleGenmovePreservesColorInEveryGameMode(
      String mode, boolean analyze, String color, String move) throws Exception {
    Lizzie.frame.isPlayingAgainstLeelaz = mode.equals("human");
    Lizzie.frame.isAnaPlayingAgainstLeelaz = mode.equals("analysis");
    boolean black = color.equals("b");
    Lizzie.frame.playerIsBlack = !black;
    // Deliberately request the color opposite the current turn; never rewrite this parent.
    BoardHistoryNode parent = rules.current();
    parent.getData().blackToPlay = !black;
    engine.isKatago = analyze;
    Method post = GtpConsolePane.class.getDeclaredMethod("postCommand", ActionEvent.class);
    post.setAccessible(true);
    SwingUtilities.invokeAndWait(() -> {
      try {
        Field input = GtpConsolePane.class.getDeclaredField("txtCommand");
        input.setAccessible(true);
        input.set(Lizzie.gtpConsole, new JFontTextField());
        Lizzie.gtpConsole.txtCommand.setText("genmove " + color);
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    });
    post.invoke(Lizzie.gtpConsole, new ActionEvent(Lizzie.gtpConsole, 0, "send"));
    String sent = output.toString(StandardCharsets.UTF_8).lines()
        .filter(row -> row.contains("genmove")).findFirst()
        .orElseThrow(() -> new AssertionError("Missing genmove: " + output.toString(StandardCharsets.UTF_8)));
    // Accept the old unnumbered transport too, so the pre-fix failure is the wrong Board color.
    String id = sent.matches("[0-9]+ .*" ) ? sent.substring(0, sent.indexOf(' ')) : "";
    if (analyze) {
      line("=" + id);
      line("play " + move);
    } else {
      line("=" + id + " " + move);
    }
    line("");
    assertEquals(black ? Stone.BLACK : Stone.WHITE, rules.current().getData().lastMoveColor);
    assertEquals(!black, parent.getData().blackToPlay);
    assertEquals(1, rules.current().getData().moveNumber);
    assertEquals(move.equals("pass"), rules.current().getData().isPassNode());
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("play "),
        "A generated move must not be echoed to the generating engine");
    assertEquals(1, output.toString(StandardCharsets.UTF_8).lines()
        .filter(row -> row.contains("genmove")).count());
    idle();
  }

  @Test
  void undoCannotOvertakeTheCommittedMoveToTheMirror() throws Exception {
    Leelaz previousMirror = Lizzie.leelaz2;
    Leelaz mirror = new Leelaz("");
    mirror.started = mirror.isLoaded = true;
    MirrorSelectionEngine primary = new MirrorSelectionEngine();
    primary.started = primary.isLoaded = true;
    LeelazResponseTestBridge.reset(engine);
    engine = primary;
    Lizzie.setPrimaryEngine(primary);
    EngineManager.isEmpty = false;
    LeelazResponseTestBridge.installOutput(primary, output);
    ByteArrayOutputStream mirrorOutput = new ByteArrayOutputStream();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      LeelazResponseTestBridge.installOutput(mirror, mirrorOutput);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      rules.board().getHistory().place(0, 18, Stone.BLACK);
      primary.sendCommandNoLeelaz2("play B A1");
      mirror.sendCommandNoLeelaz2("play B A1");
      line("=" + output.toString(StandardCharsets.UTF_8).split(" ")[0]);
      line("");
      LeelazResponseTestBridge.dispatch(mirror,
          "=" + mirrorOutput.toString(StandardCharsets.UTF_8).split(" ")[0]);
      LeelazResponseTestBridge.dispatch(mirror, "");
      BoardHistoryNode parent = rules.current();
      int id = request(false, "w");
      line("=" + id + " D4");
      primary.pauseSelection = true;
      Future<?> response = executor.submit(() -> {
        try { line(""); } catch (Exception failure) { throw new RuntimeException(failure); }
      });
      assertTrue(primary.selectionEntered.await(3, TimeUnit.SECONDS));
      AtomicReference<Thread> navigationThread = new AtomicReference<>();
      CountDownLatch navigationStarted = new CountDownLatch(1);
      Future<Boolean> navigation = executor.submit(() -> {
        navigationThread.set(Thread.currentThread());
        navigationStarted.countDown();
        return rules.board().previousMove(false);
      });
      assertTrue(navigationStarted.await(3, TimeUnit.SECONDS));
      // Wait for either the original faulty undo to finish or the fixed Board lock to fence it.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      boolean blockedOnBoard = false;
      while (!navigation.isDone() && System.nanoTime() < deadline) {
        var info = ManagementFactory.getThreadMXBean().getThreadInfo(navigationThread.get().getId());
        blockedOnBoard = info != null && info.getThreadState() == Thread.State.BLOCKED
            && info.getLockInfo() != null
            && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(rules.board());
        if (blockedOnBoard) break;
        Thread.yield();
      }
      assertTrue(navigation.isDone() || blockedOnBoard, "Navigation never reached the Board boundary");
      primary.releaseSelection.countDown();
      response.get(3, TimeUnit.SECONDS);
      assertTrue(navigation.get(3, TimeUnit.SECONDS));
      assertSame(parent, rules.current());
      List<String> positionCommands = mirrorOutput.toString(StandardCharsets.UTF_8).lines()
          .map(row -> row.replaceFirst("^[0-9]+ ", ""))
          .filter(row -> row.startsWith("play ") || row.equals("undo")).toList();
      assertEquals(List.of("play B A1", "play W D4", "undo"), positionCommands);
      idle();
    } finally {
      primary.releaseSelection.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      LeelazResponseTestBridge.reset(mirror);
      Lizzie.leelaz2 = previousMirror;
    }
  }

  static class MirrorSelectionEngine extends Leelaz {
    final CountDownLatch selectionEntered = new CountDownLatch(1);
    final CountDownLatch releaseSelection = new CountDownLatch(1);
    volatile boolean pauseSelection;

    MirrorSelectionEngine() throws IOException { super(""); }

    @Override public Leelaz activeComparisonEngine() {
      Leelaz selected = super.activeComparisonEngine();
      if (pauseSelection) {
        pauseSelection = false;
        selectionEntered.countDown();
        try {
          if (!releaseSelection.await(5, TimeUnit.SECONDS))
            throw new AssertionError("Mirror selection release timed out");
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failure);
        }
      }
      return selected;
    }
  }

  @Test
  void previousProtocolResponseCannotBecomeAWhitePass() throws Exception {
    engine.sendCommand("protocol_version");
    BoardHistoryNode parent = rules.current();
    int id = request(false, "w");
    line("= 2");
    line("");
    assertSame(parent, rules.current());
    assertTrue(parent.getData().blackToPlay);
    assertTrue(engine.isThinking);
    response(id, "D4");
    assertEquals(Stone.WHITE, rules.current().getData().lastMoveColor);
    assertTrue(parent.getData().blackToPlay);
    idle();
  }

  @Test
  void analyzeHeaderAndPlayRemainOwnedUntilBlankAndCommitOnlyOnce() throws Exception {
    BoardHistoryNode parent = rules.current();
    int id = request(true, "w");
    line("=" + id);
    assertTrue(engine.isThinking);
    assertFalse(engine.genmove("b", true));
    line("play D4");
    line("play C3");
    assertSame(parent, rules.current());
    line("");
    assertEquals(Stone.WHITE, rules.current().getData().lastMoveColor);
    assertArrayEquals(Board.asCoordinates("D4").orElseThrow(), rules.current().getData().lastMove.orElseThrow());
    assertEquals(1, rules.current().getData().moveNumber);
    line("play D4");
    response(id, "D4");
    assertEquals(1, rules.current().getData().moveNumber);
    idle();
  }

  @Test
  void exactEngineErrorLeavesTurnUnchangedAndAllowsRetry() throws Exception {
    int id = request(true, "w");
    line("?" + id + " forced genmove rejection");
    line("");
    assertTrue(rules.current().getData().blackToPlay);
    assertEquals(0, rules.current().getData().moveNumber);
    idle();
    response(request(false, "b"), "D4");
    assertEquals(Stone.BLACK, rules.current().getData().lastMoveColor);
    idle();
  }

  @Test
  void analyzeInternalFailureWithoutPlayReleasesRequest() throws Exception {
    int id = request(true, "w");
    line("=" + id);
    line("");
    idle();
    assertEquals(0, rules.current().getData().moveNumber);
    assertTrue(rules.current().getData().blackToPlay);
  }

  @ParameterizedTest
  @ValueSource(strings = {"resign", "D400", "I4", "D0", "D4 extra", "2", "", "garbage"})
  void invalidOrResignedResponseNeverBecomesPass(String move) throws Exception {
    response(request(false, "w"), move);
    idle();
    assertEquals(0, rules.current().getData().moveNumber);
    assertTrue(rules.current().getData().blackToPlay);
  }

  @Test
  void passUsesRequestedColorWithoutChangingParentTurn() throws Exception {
    BoardHistoryNode parent = rules.current();
    response(request(false, "w"), "pass");
    assertTrue(rules.current().getData().isPassNode());
    assertEquals(Stone.WHITE, rules.current().getData().lastMoveColor);
    assertTrue(parent.getData().blackToPlay);
    idle();
  }

  @Test
  void resetAfterAnalyzeHeaderReleasesBusyStateAndOldReplyCannotApply() throws Exception {
    int oldId = request(true, "w");
    line("=" + oldId);
    LeelazResponseTestBridge.reset(engine);
    idle();
    int nextId = request(true, "b");
    line("play C3");
    response(oldId, "D4");
    assertEquals(0, rules.current().getData().moveNumber);
    assertTrue(engine.isThinking);
    line("=" + nextId);
    line("play D4");
    line("");
    assertEquals(Stone.BLACK, rules.current().getData().lastMoveColor);
    idle();
  }

  @Test
  void primaryAwayAndBackInvalidatesCapturedRequest() throws Exception {
    int id = request(false, "w");
    Lizzie.setPrimaryEngine(new Leelaz(""));
    Lizzie.setPrimaryEngine(engine);
    response(id, "D4");
    assertEquals(0, rules.current().getData().moveNumber);
    idle();
  }

  @Test
  void changedBoardRevisionRejectsReplyEvenAtSameNode() throws Exception {
    int id = request(false, "w");
    rules.board().requireEngineAlignment();
    // Completing the newer restore must not revive the old request's revision.
    rules.board().completeEngineAlignment(rules.board().getHistory().getStart(),
        rules.current(), rules.board().getContextRevision());
    response(id, "D4");
    assertEquals(0, rules.current().getData().moveNumber);
    idle();
    response(request(false, "w"), "D4");
    assertEquals(Stone.WHITE, rules.current().getData().lastMoveColor);
    idle();
  }

  @Test
  void readerReplacementCancelsOutstandingRequest() throws Exception {
    int oldId = request(true, "w");
    line("=" + oldId);
    LeelazResponseTestBridge.installOutput(engine, output);
    idle();
    int id = request(false, "b");
    response(oldId, "C3");
    response(id, "D4");
    assertEquals(Stone.BLACK, rules.current().getData().lastMoveColor);
    idle();
  }
  @Test
  void shutdownFenceRejectsLateResultBeforeReaderTermination() throws Exception {
    int id = request(false, "w");
    LeelazResponseTestBridge.requestShutdown(engine);
    response(id, "D4");
    assertEquals(0, rules.current().getData().moveNumber);
    idle();
  }

  @Test
  void queuedRequestReservesAdmissionAndRejectsChangedPositionBeforeWrite() throws Exception {
    Field gate = Leelaz.class.getDeclaredField("requireResponseBeforeSend");
    gate.setAccessible(true);
    gate.setBoolean(engine, true);
    engine.sendCommand("protocol_version");
    assertTrue(engine.genmove("w", true));
    assertTrue(engine.isThinking);
    assertFalse(engine.genmove("b", true));
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("genmove"));
    rules.board().requireEngineAlignment();
    line("= 2");
    line("");
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("genmove"));
    idle();
  }

  @Test
  void replayedPassIsNotSentBackToGeneratingEngine() throws Exception {
    rules.board().getHistory().pass(Stone.WHITE);
    BoardHistoryNode pass = rules.current();
    rules.board().getHistory().setHead(pass.previous().orElseThrow());
    response(request(false, "w"), "pass");
    assertSame(pass, rules.current());
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("play "));
    idle();
  }

  @Test
  void actualNavigationAwayAndBackInvalidatesReply() throws Exception {
    rules.board().getHistory().place(3, 3, Stone.BLACK);
    BoardHistoryNode parent = rules.current();
    int id = request(false, "w");
    rules.board().previousMove(false);
    rules.board().nextMove(false);
    assertSame(parent, rules.current());
    response(id, "C3");
    assertSame(parent, rules.current());
    idle();
  }

  @Test
  void mirrorReceivesOnlyPrimaryChosenMoveWithExplicitColor() throws Exception {
    Leelaz previousMirror = Lizzie.leelaz2;
    try {
      Leelaz mirror = new Leelaz("");
      mirror.started = mirror.isLoaded = true;
      ByteArrayOutputStream mirrorOutput = new ByteArrayOutputStream();
      LeelazResponseTestBridge.installOutput(mirror, mirrorOutput);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      int id = request(false, "w");
      assertFalse(mirrorOutput.toString(StandardCharsets.UTF_8).contains("genmove"));
      response(id, "D4");
      String sent = mirrorOutput.toString(StandardCharsets.UTF_8);
      assertEquals(1, sent.lines().filter(row -> row.contains("play W D4")).count(), sent);
      assertFalse(output.toString(StandardCharsets.UTF_8).contains("play "));
      LeelazResponseTestBridge.reset(mirror);
    } finally {
      Lizzie.leelaz2 = previousMirror;
    }
  }

  @Test
  void consoleShowsOriginalOwnedResponseWithoutSyntheticDuplicate() throws Exception {
    RecordingConsole console = RulesLayerTestHarness.allocate(RecordingConsole.class);
    console.lines = new ArrayList<>();
    Lizzie.gtpConsole = console;
    Lizzie.config.alwaysGtp = true;
    int id = request(true, "w");
    line("=" + id);
    line("play D4");
    line("");
    assertEquals(List.of("=" + id + "\n", "play D4\n", "\n"), console.lines);
  }

  @Test
  void analysisInfoRemainsVisibleWhileFinalMoveIsPending() throws Exception {
    int id = request(true, "w");
    line("=" + id);
    LizzieFrame frame = Lizzie.frame;
    BottomToolbar toolbar = LizzieFrame.toolbar;
    try {
      LizzieFrame.toolbar = RulesLayerTestHarness.allocate(BottomToolbar.class);
      Lizzie.frame = RulesLayerTestHarness.allocate(InfoFrame.class);
      line("info move D4 visits 40 winrate 0.51 scoreLead 2.5 prior 0.2 pv D4");
    } finally {
      Lizzie.frame = frame;
      LizzieFrame.toolbar = toolbar;
    }
    assertFalse(engine.getBestMoves().isEmpty());
    assertEquals("D4", engine.getBestMoves().get(0).coordinate);
    assertTrue(engine.isThinking);
    line("play D4");
    line("");
    idle();
  }

  @Test
  void unnumberedAndWrongIdHeadersCannotSettleRequest() throws Exception {
    int id = request(false, "w");
    line("? forced genmove rejection");
    line("=" + (id + 1) + " C3");
    line("");
    assertTrue(engine.isThinking);
    assertEquals(0, rules.current().getData().moveNumber);
    response(id, "D4");
    idle();
  }

  @Test
  void writeFailureReleasesReservation() throws Exception {
    LeelazResponseTestBridge.installOutput(engine, new OutputStream() {
      @Override public void write(int value) throws IOException {
        throw new IOException("intentional genmove write failure");
      }
    });
    try { engine.genmove("w", true); } catch (RuntimeException expected) { }
    idle();
    assertTrue(rules.current().getData().blackToPlay);
    assertEquals(0, rules.current().getData().moveNumber);
  }

  @Test
  void blockedMirrorDoesNotHoldSelectionOrResumeAfterPrimaryReplacement() throws Exception {
    Leelaz previousMirror = Lizzie.leelaz2;
    Leelaz mirror = new Leelaz("");
    mirror.started = mirror.isLoaded = true;
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      LeelazResponseTestBridge.installOutput(mirror, new OutputStream() {
        @Override public void write(int value) throws IOException {
          entered.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS))
              throw new IOException("mirror release timed out");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
          }
        }
      });
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      int id = request(false, "w");
      line("=" + id + " D4");
      Future<?> response = executor.submit(() -> {
        try { line(""); } catch (Exception failure) { throw new RuntimeException(failure); }
      });
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      Leelaz replacement = new Leelaz("");
      executor.submit(() -> Lizzie.setPrimaryEngine(replacement)).get(2, TimeUnit.SECONDS);
      release.countDown();
      response.get(3, TimeUnit.SECONDS);
      assertSame(replacement, Lizzie.leelaz);
      assertFalse(output.toString(StandardCharsets.UTF_8).contains("name"));
      idle();
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      LeelazResponseTestBridge.reset(mirror);
      Lizzie.leelaz2 = previousMirror;
    }
  }

  static class InfoFrame extends LizzieFrame {
    @Override public void requestAnalysisRefresh() { }
    @Override public void requestAnalysisTitleUpdate() { }
  }

  static class RecordingConsole extends GtpConsolePane {
    RecordingConsole() { super(null); }
    List<String> lines;
    @Override public void addLine(String line) { lines.add(line); }
  }

}
