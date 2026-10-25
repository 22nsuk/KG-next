package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Locks the heat-map output handling of {@link Leelaz} (KataGo kata-raw-nn and Leela heatmap). */
class LeelazHeatmapOutputTest {
  private static final int SIZE = 3;
  private int previousWidth;
  private int previousHeight;
  private Board previousBoard;
  private LizzieFrame previousFrame;
  private Config previousConfig;
  private boolean previousEmpty;

  @BeforeEach
  void setUp() throws Exception {
    previousWidth = Board.boardWidth;
    previousHeight = Board.boardHeight;
    previousBoard = Lizzie.board;
    previousFrame = Lizzie.frame;
    previousConfig = Lizzie.config;
    previousEmpty = EngineManager.isEmpty;
    Lizzie.config = allocate(Config.class);
    EngineManager.isEmpty = false;
    Board.boardWidth = SIZE;
    Board.boardHeight = SIZE;
    Board board = allocate(Board.class);
    board.setHistory(new BoardHistoryList(BoardData.empty(SIZE, SIZE)));
    Lizzie.board = board;
    Lizzie.frame = allocate(CountingFrame.class);
    CountingFrame.REFRESHES.set(0);
    CountingFrame.OWNERSHIP = List.of();
  }

  @AfterEach
  void tearDown() {
    Board.boardWidth = previousWidth;
    Board.boardHeight = previousHeight;
    Lizzie.board = previousBoard;
    Lizzie.frame = previousFrame;
    Lizzie.config = previousConfig;
    EngineManager.isEmpty = previousEmpty;
  }

  @Test
  void kataRawNnResponseFillsHeatmapAndRefreshesOnceWhenOwnershipIsComplete() throws Exception {
    Leelaz engine = kataGoHeatmapEngine();

    feed(
        engine,
        "= symmetry 3",
        "whiteWin 0.25",
        "whiteLoss 0.75",
        "whiteLead -1.5",
        "policy",
        "0.1 0.2 0.3",
        "0.0 NAN 0.5",
        "0.6 0.7",
        "0.6 0.7 0.8",
        "policyPass 0.01",
        "whiteOwnership",
        "0.1 -0.2 0.3",
        "1 0 -1");

    assertEquals(3, engine.symmetry);
    assertEquals(0.25, engine.heatwinrate);
    assertEquals(-1.5, engine.heatScore);
    assertEquals(
        List.of(100.0, 200.0, 300.0, 0.0, 0.0, 500.0, 600.0, 700.0, 800.0), engine.heatPolicy);
    assertEquals(0, CountingFrame.REFRESHES.get(), "nothing is shown before ownership is complete");
    assertFalse(Lizzie.frame.isShowingHeatmap);

    feed(engine, "0.5 0.5 0.5", "0.4 0.4 0.4");

    // Black to play: white ownership is negated for display.
    assertEquals(List.of(-0.1, 0.2, -0.3, -1.0, -0.0, 1.0, -0.5, -0.5, -0.5), engine.heatOwnership);
    assertEquals(List.of(100, 200, 300, 0, 0, 500, 600, 700, 800), engine.heatcount);
    assertEquals(1, CountingFrame.REFRESHES.get());
    assertTrue(Lizzie.frame.isShowingHeatmap);
  }

  @Test
  void whiteToPlayKeepsOwnershipSignAndANewResponseStartsFresh() throws Exception {
    Leelaz engine = kataGoHeatmapEngine();
    Lizzie.board.getHistory().getData().blackToPlay = false;

    feed(engine, "= symmetry 0", "policy", "1 1 1", "1 1 1", "1 1 1", "whiteOwnership");
    feed(engine, "0.1 0.2 0.3", "0.4 0.5 0.6", "0.7 0.8 0.9");
    assertEquals(List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9), engine.heatOwnership);
    assertEquals(0, engine.symmetry);
    assertEquals(1, CountingFrame.REFRESHES.get());

    feed(engine, "= symmetry 5", "policy", "0 0 0");
    assertEquals(5, engine.symmetry);
    assertEquals(List.of(0.0, 0.0, 0.0), engine.heatPolicy);
    assertTrue(engine.heatOwnership.isEmpty());
  }

  @Test
  void leelaHeatmapCollectsCountsAndWinrate() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.isheatmap = true;

    feed(engine, " 1 2 3", " 4 5 6", "notes", " 7 8 9", "pass: 0", "winrate: 0.55");

    assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), engine.heatcount);
    assertEquals(1, CountingFrame.REFRESHES.get());
    assertEquals(0.55, engine.heatwinrate);
    assertTrue(Lizzie.frame.isShowingHeatmap);
  }

  @Test
  void outputIsIgnoredWhenNoHeatmapWasRequested() throws Exception {
    Leelaz engine = kataGoHeatmapEngine();
    engine.isheatmap = false;

    feed(engine, "= symmetry 2", "whiteWin 0.9", "policy", "1 1 1");

    assertEquals(0, engine.symmetry);
    assertEquals(-1.0, engine.heatwinrate);
    assertTrue(engine.heatPolicy.isEmpty());
  }

  @Test
  void capturedTwoByThreeEngineResponseExcludesPassAndPublishesOwnership() throws Exception {
    // Captured from installed KataGo v1.18.2 (kata1-tf3-b11c768-s11003M-d5973M-7gres),
    // after rectangular_boardsize 2 3 and kata-raw-nn 0 on an empty Japanese-rules board.
    Board.boardWidth = 2;
    Board.boardHeight = 3;
    Leelaz engine = kataGoHeatmapEngine();
    engine.iskataHeatmapShowOwner = true;
    try (var input = getClass().getResourceAsStream("/analysis/katago-raw-nn-2x3.txt")) {
      feed(engine, new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
    }
    assertEquals(0.034049, engine.heatwinrate);
    assertEquals(-15.799, engine.heatScore);
    assertEquals(6, engine.heatPolicy.size(), "pass probability is not a seventh board cell");
    assertEquals(List.of(59, 42, 421, 360, 56, 58), engine.heatcount);
    assertEquals(
        List.of(0.6974432, 0.6778877, 0.82456, 0.8270447, 0.71122, 0.6841711),
        engine.heatOwnership);
    assertEquals(engine.heatOwnership, CountingFrame.OWNERSHIP);
    assertEquals(1, CountingFrame.REFRESHES.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"set", "toggle", "sub"})
  void resetAfterHeaderKeepsTheRemainingResponseConnectedToDisplayLists(String reset)
      throws Exception {
    Leelaz engine = kataGoHeatmapEngine();
    feed(engine, "= symmetry 2", "policy", "0.9 0.9 0.9");
    resetHeatmap(engine, reset);
    engine.iskataHeatmapShowOwner = true;
    assertTrue(engine.heatPolicy.isEmpty());
    feed(
        engine,
        "0.1 0.2 0.3",
        "0.4 0.5 0.6",
        "0.7 0.8 0.9",
        "policyPass 0.01",
        "whiteOwnership",
        "1 0 -1",
        "1 0 -1",
        "1 0 -1");
    assertEquals(List.of(100, 200, 300, 400, 500, 600, 700, 800, 900), engine.heatcount);
    assertEquals(List.of(-1.0, -0.0, 1.0, -1.0, -0.0, 1.0, -1.0, -0.0, 1.0), engine.heatOwnership);
    assertEquals(engine.heatOwnership, CountingFrame.OWNERSHIP);
    assertEquals(1, CountingFrame.REFRESHES.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"set", "toggle", "sub"})
  void resetDuringOwnershipCannotCompleteUsingDetachedRows(String reset) throws Exception {
    Leelaz engine = kataGoHeatmapEngine();
    feed(
        engine,
        "= symmetry 2",
        "policy",
        "1 1 1",
        "1 1 1",
        "1 1 1",
        "whiteOwnership",
        "0.9 0.9 0.9");
    resetHeatmap(engine, reset);
    engine.iskataHeatmapShowOwner = true;
    feed(engine, "0.1 0.2 0.3", "0.4 0.5 0.6");
    assertEquals(List.of(-0.1, -0.2, -0.3, -0.4, -0.5, -0.6), engine.heatOwnership);
    assertTrue(engine.heatcount.isEmpty());
    assertTrue(CountingFrame.OWNERSHIP.isEmpty());
    assertEquals(0, CountingFrame.REFRESHES.get(), "discarded rows cannot complete current output");

    feed(
        engine,
        "= symmetry 4",
        "policy",
        "1 1 1",
        "1 1 1",
        "1 1 1",
        "whiteOwnership",
        "1 1 1",
        "1 1 1",
        "1 1 1");
    assertEquals(9, engine.heatcount.size());
    assertEquals(9, CountingFrame.OWNERSHIP.size());
    assertEquals(1, CountingFrame.REFRESHES.get());
  }

  @Test
  void newHeaderDoesNotTreatScalarsAsRowsOfThePreviousResponse() throws Exception {
    Board.boardWidth = 2;
    Board.boardHeight = 2;
    Leelaz engine = kataGoHeatmapEngine();
    feed(engine, "= symmetry 0", "whiteOwnership", "0.9 0.9");
    feed(engine, "= symmetry 1", "whiteWin 0.25", "whiteLead -1.5");
    assertTrue(engine.heatOwnership.isEmpty());
    assertEquals(0, CountingFrame.REFRESHES.get());
    feed(engine, "policy", "0.1 0.2", "0.3 0.4", "whiteOwnership", "0.1 0.2", "0.3 0.4");
    assertEquals(List.of(100, 200, 300, 400), engine.heatcount);
    assertEquals(List.of(-0.1, -0.2, -0.3, -0.4), engine.heatOwnership);
    assertEquals(1, CountingFrame.REFRESHES.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void resizedBoardRejectsOldResponseGeometryUntilANewHeader(boolean resizeDuringLastRow)
      throws Exception {
    Leelaz engine = kataGoHeatmapEngine();
    engine.iskataHeatmapShowOwner = true;
    feed(
        engine,
        "= symmetry 0",
        "policy",
        "1 1 1",
        "1 1 1",
        "1 1 1",
        "whiteOwnership",
        "0.9 0.9 0.9",
        "0.1 0.2 0.3");
    if (resizeDuringLastRow) {
      Lizzie.board.setHistory(
          new BoardHistoryList(BoardData.empty(SIZE, SIZE)) {
            @Override
            public boolean isBlacksTurn() {
              Board.boardWidth = 2;
              Board.boardHeight = 2;
              return true;
            }
          });
    } else {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
    }
    feed(engine, "0.4 0.5 0.6");
    assertEquals(0, CountingFrame.REFRESHES.get());
    assertTrue(CountingFrame.OWNERSHIP.isEmpty());
    assertTrue(engine.heatcount.isEmpty());

    Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(2, 2)));
    feed(
        engine,
        "= symmetry 1",
        "policy",
        "0.1 0.2",
        "0.3 0.4",
        "whiteOwnership",
        "0.1 0.2",
        "0.3 0.4");
    assertEquals(List.of(100, 200, 300, 400), engine.heatcount);
    assertEquals(List.of(-0.1, -0.2, -0.3, -0.4), CountingFrame.OWNERSHIP);
    assertEquals(1, CountingFrame.REFRESHES.get());
  }

  private static void resetHeatmap(Leelaz engine, String reset) {
    switch (reset) {
      case "set":
        engine.setHeatmap();
        break;
      case "toggle":
        engine.toggleHeatmap(false);
        break;
      case "sub":
        engine.toggleHeatmapSub(false);
        break;
      default:
        throw new AssertionError(reset);
    }
  }

  private static Leelaz kataGoHeatmapEngine() throws Exception {
    Leelaz engine = new HeatmapEngine();
    engine.isheatmap = true;
    engine.isKatago = true;
    engine.iskataHeatmapShowOwner = false;
    return engine;
  }

  private static void feed(Leelaz engine, String... lines) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("parseHeatMap", String.class);
    method.setAccessible(true);
    for (String line : lines) {
      method.invoke(engine, line);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class CountingFrame extends LizzieFrame {
    // Static because the frame is allocated without running field initializers.
    static final AtomicInteger REFRESHES = new AtomicInteger();
    static List<Double> OWNERSHIP;

    @Override
    public void refresh() {
      REFRESHES.incrementAndGet();
    }

    @Override
    public void clearKataEstimate() {}

    @Override
    public void drawKataEstimate(Leelaz engine, ArrayList<Double> values) {
      OWNERSHIP = List.copyOf(values);
    }
  }

  private static final class HeatmapEngine extends Leelaz {
    HeatmapEngine() throws Exception {
      super("");
    }

    @Override
    public void sendCommand(String command) {
      // Exercise the real toggle/reset paths without starting a process or a second response.
    }
  }
}
