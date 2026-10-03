package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class RootTreeReuseCapabilityTest {
  @Test
  void aNameOrVersionNeverImpliesSupport() throws Exception {
    Leelaz engine = new Leelaz("katago");
    engine.isKatago = true;
    assertFalse(engine.supportsRootTreeReuse());
    engine.advertiseCommandsForTest(List.of("kata-analyze", "clear_cache"));
    assertFalse(engine.supportsRootTreeReuse());
  }
  @Test
  void extensionMustBeAdvertisedByTheCurrentKataGo() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.advertiseCommandsForTest(List.of("kg-reuse-root-tree"));
    assertFalse(engine.supportsRootTreeReuse());
    engine.isKatago = true;
    assertTrue(engine.supportsRootTreeReuse());
    engine.advertiseCommandsForTest(List.of("kata-analyze"));
    assertFalse(engine.supportsRootTreeReuse());
  }

  @Test
  void replacementProcessCannotInheritThePreviousProcessCapability() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.isKatago = true;
    engine.advertiseCommandsForTest(List.of("kata-analyze", "kg-reuse-root-tree"));
    assertTrue(engine.supportsRootTreeReuse());
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    assertFalse(engine.supportsRootTreeReuse());
    engine.advertiseCommandsForTest(List.of("kata-analyze"));
    assertFalse(engine.supportsRootTreeReuse());
    engine.advertiseCommandsForTest(List.of("kata-analyze", "kg-reuse-root-tree"));
    assertTrue(engine.supportsRootTreeReuse());
  }

  @Test
  void nativeRestrictionPreservesCachedCountsWhileTheCommandIsPending() throws Exception {
    withBoard(true);
  }

  @Test
  void legacyRestrictionDoesNotReceiveAnUnsupportedExtension() throws Exception {
    withBoard(false);
  }

  private void withBoard(boolean supported) throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    Leelaz previousEngine = Lizzie.leelaz;
    try {
      Lizzie.config = allocate(Config.class);
      Lizzie.config.analyzeUpdateIntervalCentisec = 10;
      Lizzie.config.enableLizzieCache = true;
      Lizzie.board = allocate(Board.class);
      BoardData root = BoardData.empty(Board.boardWidth, Board.boardHeight);
      BoardData current = BoardData.empty(Board.boardWidth, Board.boardHeight);
      root.setPlayouts(500);
      current.setPlayouts(1000);
      BoardHistoryList history = new BoardHistoryList(root);
      history.add(current);
      Lizzie.board.setHistory(history);
      RecordingEngine engine = new RecordingEngine();
      Lizzie.leelaz = engine;
      engine.isKatago = true;
      engine.advertiseCommandsForTest(supported
          ? List.of("kata-analyze", "kg-reuse-root-tree") : List.of("kata-analyze"));
      engine.analyzeAvoid("allow", "D4,Q16", 1);
      assertEquals(1, engine.sent.size());
      assertTrue(engine.sent.get(0).contains("allow b D4,Q16 1 allow w D4,Q16 1"));
      assertEquals(supported, engine.sent.get(0).contains("reuseRootTree true"));
      assertEquals(supported, engine.sent.get(0).contains("rootInfo true"));
      assertEquals(500, root.getPlayouts());
      assertEquals(1000, current.getPlayouts());
      assertFalse(root.isChanged);
      assertFalse(current.isChanged);
      if (supported) {
        current.rootVisits = 1000;
        var restricted = KataGoAnalysisPayload.parse(
            "info move D4 visits 300 winrate 0.6 pv D4 rootInfo visits 1000");
        assertEquals(BoardData.AnalysisAdoption.FULL,
            current.adoptOrdinaryAnalysis(
                restricted.moves, "KataGo", engine, restricted.totalVisits(),
                restricted.rootVisits, null, new Object(), false, true));
        assertEquals(1000, current.getPlayouts());
        assertEquals(1000, current.rootVisits);
        assertEquals(300, current.bestMoves.get(0).playouts);
        assertEquals(500, root.getPlayouts());
      }
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousEngine;
    }
  }

  private static class RecordingEngine extends Leelaz {
    final List<String> sent = new ArrayList<>();
    RecordingEngine() throws Exception {
      super("");
    }

    @Override
    public void sendCommand(String command) {
      sent.add(command);
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    // Follow the repository's headless fixture pattern: no Config/Board startup or user-file IO.
    var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
