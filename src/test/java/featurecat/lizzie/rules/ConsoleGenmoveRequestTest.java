package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.LeelazResponseTestBridge;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import featurecat.lizzie.gui.GtpConsolePane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        .filter(row -> row.contains("genmove")).reduce((first, second) -> second).orElseThrow();
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
    response(id, "D4");
    assertEquals(0, rules.current().getData().moveNumber);
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
