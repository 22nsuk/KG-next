package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RootTreeReuseAnalysisLifecycleTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oldInfoBetweenRestrictionRequestAndPhysicalWriteCannotConsumeTheNewResult(
      boolean rawParameters) throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.startAnalysis(fixture.primary, fixture.primaryTransport);
      fixture.primary.parseAnalysisLineForTest(info("Q16", 1000));
      fixture.primary.requireResponseBeforeSend = true;
      fixture.primary.sendCommand("name");
      if (rawParameters) fixture.primary.analyzeAvoid("avoid b Q16 2");
      else fixture.primary.analyzeAvoid("allow", "D4", 2);

      assertEquals(2, fixture.primaryTransport.commands().size(), "restriction must be queued");
      fixture.primary.parseAnalysisLineForTest(info("Q16", 1100));
      assertEquals(1100, fixture.current.getPlayouts(), "old stream remains live until replacement");
      fixture.respond(fixture.primary, fixture.primaryTransport, 1);
      assertEquals(3, fixture.primaryTransport.commands().size());
      fixture.primary.parseAnalysisLineForTest(info("D4", 50));

      assertEquals(50, fixture.current.getPlayouts(), "the new filtered search may reset visits");
      assertEquals("D4", fixture.current.bestMoves.get(0).coordinate);
      assertEquals(500, fixture.root.getPlayouts(), "other positions keep their cached analysis");
      assertFalse(fixture.root.isChanged);
      fixture.primary.parseAnalysisLineForTest(info("D4", 25));
      assertEquals(50, fixture.current.getPlayouts(), "the forced replacement is consumed once");
    }
  }

  @Test
  void aQueuedRestrictionReplacedByOrdinaryAnalysisDoesNotInvalidateTheCache() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.startAnalysis(fixture.primary, fixture.primaryTransport);
      fixture.primary.parseAnalysisLineForTest(info("Q16", 1000));
      fixture.primary.requireResponseBeforeSend = true;
      fixture.primary.sendCommand("name");
      fixture.primary.analyzeAvoid("allow", "D4", 2);
      fixture.primary.sendCommand("kata-analyze 10");
      fixture.respond(fixture.primary, fixture.primaryTransport, 1);

      assertEquals(List.of("kata-analyze 10", "name", "kata-analyze 10"),
          fixture.primaryTransport.commands());
      fixture.primary.parseAnalysisLineForTest(info("Q16", 50));
      assertEquals(1000, fixture.current.getPlayouts());
      assertEquals("Q16", fixture.current.bestMoves.get(0).coordinate);
      assertFalse(fixture.current.isChanged);
    }
  }

  @Test
  void nativeRestrictedPayloadKeepsTheExactReusedRootTotal() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.primary.advertiseCommandsForTest(List.of("kata-analyze", "kg-reuse-root-tree"));
      fixture.startAnalysis(fixture.primary, fixture.primaryTransport);
      fixture.primary.parseAnalysisLineForTest(info("Q16", 1000) + " rootInfo visits 1000");
      fixture.primary.analyzeAvoid("allow", "D4", 1);
      assertTrue(fixture.primaryTransport.commands().get(1).contains("reuseRootTree true"));
      fixture.primary.parseAnalysisLineForTest(info("D4", 300) + " rootInfo visits 1000");

      assertEquals(1000, fixture.current.getPlayouts());
      assertEquals(1000, fixture.current.rootVisits);
      assertEquals(300, fixture.current.bestMoves.get(0).playouts);
      assertEquals("D4", fixture.current.bestMoves.get(0).coordinate);
      assertEquals(500, fixture.root.getPlayouts());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void removingRestrictionsKeepsTheReplacementPendingUntilAFullResult(boolean restart)
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.primary.analyzeAvoid("allow", "D4", 2);
      fixture.respond(fixture.primary, fixture.primaryTransport, 0);
      fixture.primary.parseAnalysisLineForTest(info("D4", 1000));
      fixture.primary.requireResponseBeforeSend = true;
      fixture.primary.sendCommand("name");
      fixture.primary.sendCommand("kata-analyze 10");
      fixture.primary.parseAnalysisLineForTest(info("D4", 1100));
      fixture.respond(fixture.primary, fixture.primaryTransport, 1);
      fixture.respond(fixture.primary, fixture.primaryTransport, 2);
      if (restart) fixture.primary.sendCommand("kata-analyze 11");
      fixture.primary.parseAnalysisLineForTest("info");
      fixture.primary.parseAnalysisLineForTest(info("Q16", 50));

      assertEquals(50, fixture.current.getPlayouts());
      assertEquals("Q16", fixture.current.bestMoves.get(0).coordinate);
      fixture.primary.parseAnalysisLineForTest(info("Q16", 25));
      assertEquals(50, fixture.current.getPlayouts());
    }
  }

  @Test
  void restrictionRemovalCannotReplaceSavedAnalysisAtAnotherPosition() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.primary.analyzeAvoid("allow", "D4", 2);
      fixture.respond(fixture.primary, fixture.primaryTransport, 0);
      fixture.primary.parseAnalysisLineForTest(info("D4", 1000));
      BoardData other = BoardData.empty(Board.boardWidth, Board.boardHeight);
      other.setPlayouts(5000);
      BoardHistoryList history = new BoardHistoryList(other);
      Lizzie.board.setHistory(history);
      fixture.primary.sendCommand("undo");
      fixture.respond(fixture.primary, fixture.primaryTransport, 1);
      fixture.primary.sendCommand("kata-analyze 10");
      fixture.primary.parseAnalysisLineForTest(info("Q16", 50));

      assertEquals(5000, other.getPlayouts());
      assertEquals(1000, fixture.current.getPlayouts());
      assertFalse(other.isChanged);
    }
  }

  @Test
  void eachMirroredOwnerGetsItsOwnFirstRestrictedAdoption() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Leelaz secondary = new Leelaz("");
      secondary.started = true;
      secondary.isLoaded = true;
      secondary.isKatago = true;
      Lizzie.leelaz2 = secondary;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      ExactSnapshotRestoreProtocolFixture.Transport secondaryTransport =
          ExactSnapshotRestoreProtocolFixture.install(secondary, command -> null);
      fixture.primary.advertiseCommandsForTest(List.of("kata-analyze", "kg-reuse-root-tree"));
      secondary.advertiseCommandsForTest(List.of("kata-analyze"));
      fixture.primary.sendCommand("kata-analyze 10");
      fixture.respond(fixture.primary, fixture.primaryTransport, 0);
      fixture.respond(secondary, secondaryTransport, 0);
      fixture.primary.parseAnalysisLineForTest(info("Q16", 1000) + " rootInfo visits 1000");
      secondary.parseAnalysisLineForTest(info("Q16", 2000));
      fixture.primary.requireResponseBeforeSend = true;
      secondary.requireResponseBeforeSend = true;
      fixture.primary.sendCommand("name");
      fixture.primary.analyzeAvoid("allow", "D4", 1);
      secondary.parseAnalysisLineForTest(info("Q16", 2100));
      fixture.respond(fixture.primary, fixture.primaryTransport, 1);
      fixture.primary.parseAnalysisLineForTest(info("D4", 300) + " rootInfo visits 1000");
      assertEquals(1000, fixture.current.getPlayouts());
      assertEquals(2100, fixture.current.getPlayouts2());
      fixture.respond(secondary, secondaryTransport, 1);
      assertFalse(secondaryTransport.commands().get(2).contains("reuseRootTree"));
      secondary.parseAnalysisLineForTest(info("D4", 50));

      assertEquals(50, fixture.current.getPlayouts2());
      assertEquals("D4", fixture.current.bestMoves2.get(0).coordinate);
      assertEquals(1000, fixture.current.getPlayouts());
      assertEquals(300, fixture.current.bestMoves.get(0).playouts);
    }
  }

  @Test
  void legacyMirroredPayloadsUseTheSameOwnerBoundReplacement() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.primary.isKatago = false;
      Leelaz secondary = new Leelaz("");
      secondary.started = true;
      secondary.isLoaded = true;
      secondary.isKatago = false;
      Lizzie.leelaz2 = secondary;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      ExactSnapshotRestoreProtocolFixture.Transport secondaryTransport =
          ExactSnapshotRestoreProtocolFixture.install(secondary, command -> null);
      fixture.primary.sendCommand("lz-analyze 10");
      fixture.respond(fixture.primary, fixture.primaryTransport, 0);
      fixture.respond(secondary, secondaryTransport, 0);
      fixture.primary.parseAnalysisLineForTest(legacyInfo("Q16", 1000));
      secondary.parseAnalysisLineForTest(legacyInfo("Q16", 2000));
      assertEquals(1000, fixture.current.getPlayouts());
      assertEquals(2000, fixture.current.getPlayouts2());
      fixture.primary.requireResponseBeforeSend = true;
      secondary.requireResponseBeforeSend = true;
      fixture.primary.sendCommand("name");
      fixture.primary.analyzeAvoid("allow", "D4", 2);
      fixture.primary.parseAnalysisLineForTest(legacyInfo("Q16", 1100));
      secondary.parseAnalysisLineForTest(legacyInfo("Q16", 2100));
      assertEquals(1100, fixture.current.getPlayouts());
      assertEquals(2100, fixture.current.getPlayouts2());
      fixture.respond(fixture.primary, fixture.primaryTransport, 1);
      fixture.respond(secondary, secondaryTransport, 1);
      fixture.primary.parseAnalysisLineForTest(legacyInfo("D4", 50));
      secondary.parseAnalysisLineForTest(legacyInfo("D4", 50));

      assertEquals(50, fixture.current.getPlayouts());
      assertEquals(50, fixture.current.getPlayouts2());
      fixture.primary.parseAnalysisLineForTest(legacyInfo("D4", 25));
      secondary.parseAnalysisLineForTest(legacyInfo("D4", 25));
      assertEquals(50, fixture.current.getPlayouts());
      assertEquals(50, fixture.current.getPlayouts2());
    }
  }

  private static String info(String coordinate, int visits) {
    return "info move " + coordinate + " visits " + visits + " winrate 0.6 pv " + coordinate;
  }

  private static String legacyInfo(String coordinate, int visits) {
    return "info move " + coordinate + " visits " + visits + " winrate 6000 pv " + coordinate;
  }

  private static final class Fixture implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final GtpConsolePane previousConsole = Lizzie.gtpConsole;
    private final BottomToolbar previousToolbar = LizzieFrame.toolbar;
    private final Leelaz previousPrimary = Lizzie.leelaz;
    private final Leelaz previousSecondary = Lizzie.leelaz2;
    private final Leelaz primary;
    private final ExactSnapshotRestoreProtocolFixture.Transport primaryTransport;
    private final BoardData root;
    private final BoardData current;

    private Fixture() throws Exception {
      Lizzie.config = allocate(Config.class);
      Lizzie.config.enableLizzieCache = true;
      Lizzie.config.analyzeUpdateIntervalCentisec = 10;
      Lizzie.gtpConsole = allocate(GtpConsolePane.class);
      Lizzie.frame = allocate(HeadlessFrame.class);
      LizzieFrame.toolbar = allocate(BottomToolbar.class);
      Lizzie.frame.priorityMoveCoords = new ArrayList<>();
      root = BoardData.empty(Board.boardWidth, Board.boardHeight);
      current = BoardData.empty(Board.boardWidth, Board.boardHeight);
      root.setPlayouts(500);
      BoardHistoryList history = new BoardHistoryList(root);
      history.add(current);
      Lizzie.board = allocate(Board.class);
      Lizzie.board.setHistory(history);
      primary = new Leelaz("");
      primary.started = true;
      primary.isLoaded = true;
      primary.isKatago = true;
      primary.advertiseCommandsForTest(List.of("kata-analyze"));
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = null;
      primaryTransport = ExactSnapshotRestoreProtocolFixture.install(primary, command -> null);
    }

    private void startAnalysis(Leelaz engine, ExactSnapshotRestoreProtocolFixture.Transport transport) {
      engine.sendCommand("kata-analyze 10");
      respond(engine, transport, 0);
    }

    private void respond(Leelaz engine, ExactSnapshotRestoreProtocolFixture.Transport transport,
        int index) {
      String raw = transport.rawCommands().get(index);
      int split = raw.indexOf(' ');
      String id = split > 0 && Character.isDigit(raw.charAt(0)) ? raw.substring(0, split) : "";
      engine.processCommandResponseLineForTest("=" + id);
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousConsole;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
    }
  }

  private static final class HeadlessFrame extends LizzieFrame {
    @Override
    public BoardHistoryNode getDisplayNode() {
      return Lizzie.board.getHistory().getCurrentHistoryNode();
    }

    @Override
    public void requestAnalysisRefresh() {}

    @Override
    public void requestAnalysisTitleUpdate() {}
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
