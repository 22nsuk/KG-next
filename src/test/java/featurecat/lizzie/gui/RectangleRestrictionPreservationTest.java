package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RectangleRestrictionPreservationTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void releasingASelectionPreservesCachedAnalysisUntilTheCommandOwnsItsStream(boolean allow)
      throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
    Menu previousMenu = LizzieFrame.menu;
    String previousAllowed = LizzieFrame.allowcoords;
    String previousAvoided = LizzieFrame.avoidcoords;
    boolean previousSelectionMode = Input.selectMode;
    try {
      Lizzie.config = allocate(Config.class);
      Lizzie.config.selectAllowMoves = 1;
      Lizzie.config.selectAvoidMoves = 1;
      Lizzie.config.analyzeUpdateIntervalCentisec = 10;
      BoardData root = BoardData.empty(Board.boardWidth, Board.boardHeight);
      BoardData current = BoardData.empty(Board.boardWidth, Board.boardHeight);
      root.setPlayouts(500);
      current.setPlayouts(1000);
      BoardHistoryList history = new BoardHistoryList(root);
      history.add(current);
      Lizzie.board = allocate(Board.class);
      Lizzie.board.setHistory(history);
      RecordingEngine engine = new RecordingEngine();
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      SelectionFrame frame = allocate(SelectionFrame.class);
      Lizzie.frame = frame;
      frame.selectForceAllow = allow;
      setSelectionOrigin(frame, "selectX1");
      setSelectionOrigin(frame, "selectY1");
      LizzieFrame.boardRenderer = allocate(SelectionRenderer.class);
      LizzieFrame.menu = allocate(Menu.class);
      LizzieFrame.allowcoords = "";
      LizzieFrame.avoidcoords = "";

      frame.selectReleased(20, 20);

      assertTrue(engine.command.contains(allow ? "allow b " : "avoid b "));
      assertFalse(root.isChanged, "A rectangle must not invalidate saved results at other positions");
      assertFalse(current.isChanged, "A pending restriction must not invalidate the live old stream");
      assertEquals(500, root.getPlayouts());
      assertEquals(1000, current.getPlayouts());
      assertFalse(Input.selectMode);
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      LizzieFrame.boardRenderer = previousRenderer;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.allowcoords = previousAllowed;
      LizzieFrame.avoidcoords = previousAvoided;
      Input.selectMode = previousSelectionMode;
    }
  }

  private static void setSelectionOrigin(LizzieFrame frame, String name) throws Exception {
    var field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    field.setInt(frame, 10);
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private static class SelectionFrame extends LizzieFrame {
    @Override
    public void repaint() {}
  }

  private static class SelectionRenderer extends BoardRenderer {
    SelectionRenderer() {
      super(false);
    }

    @Override
    public Optional<int[]> convertScreenToCoordinatesForSelect(int x1, int x2, int y1, int y2) {
      return Optional.of(new int[] {3, 3, 4, 4});
    }

    @Override
    public void drawAllSelectedRectByCoords(boolean isAllow, String coordinates) {}
  }

  private static class RecordingEngine extends Leelaz {
    String command;

    RecordingEngine() throws Exception {
      super("");
    }

    @Override
    public void sendCommand(String command) {
      this.command = command;
    }
  }
}
