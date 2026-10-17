package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Production rendering with a controlled worker boundary in an isolated application JVM. */
public class VariationPreviewVisibilityTest {
  @Test
  void candidateMarkersRemainUntilCompletePreviewAndCancellationRejectsOldImages() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.run(
        VariationPreviewVisibilityTest.class, "preview-visibility", List.of(), List.of());
    assertTrue(Files.readString(result).startsWith("PASS:"));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    try {
      Files.writeString(work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,"
              + "\"first-time-load\":false,\"use-language\":2}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      SwingUtilities.invokeAndWait(() -> {
        try {
          exercise(result);
        } catch (Exception failure) {
          throw new RuntimeException(failure);
        }
      });
      Files.writeString(result, "PASS: pending candidate markers, atomic replacement, reentry and cancellation\n");
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(result, failure.toString());
      System.exit(1);
    }
  }

  private static void exercise(Path result) throws Exception {
    assertTrue(Lizzie.frame.isShowing());
    Lizzie.config.showBranch = true;
    Lizzie.config.showSuggestionVariations = true;
    Lizzie.config.showBlackCandidates = true;
    Lizzie.config.showWhiteCandidates = true;
    Lizzie.config.usePureStone = true;
    Lizzie.config.usePureBoard = true;
    Lizzie.config.pureBoardColor = new Color(230, 190, 140);
    Lizzie.config.showStoneShadow = false;
    Lizzie.config.showHoverGlow = false;
    Lizzie.config.showStoneHighlight = false;
    Lizzie.config.showrect = 2;
    Lizzie.config.autoReplayBranch = false;
    Lizzie.config.noRefreshOnMouseMove = false;
    Lizzie.config.minPlayoutRatioForStats = 0.1;
    Lizzie.config.limitMaxSuggestion = 0;
    MoveData first = move("D4", 1000, 0, "Q16", "D16");
    MoveData second = move("Q4", 500, 1, "K10");
    MoveData minor = move("Q10", 1, 2, "K4");
    Lizzie.board.getData().bestMoves = new ArrayList<>(List.of(first, second, minor));
    var node = Lizzie.board.getHistory().getCurrentHistoryNode();
    var stones = Lizzie.board.getStones().clone();
    BoardRenderer renderer = LizzieFrame.boardRenderer;
    ArrayDeque<Runnable> work = new ArrayDeque<>();
    VariationPreviewState state = (VariationPreviewState) field(renderer, "preview");
    Field scheduler = VariationPreviewState.class.getDeclaredField("scheduler");
    scheduler.setAccessible(true);
    scheduler.set(state, new VariationPreviewScheduler(work::add, Runnable::run));
    Lizzie.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
    BufferedImage idle = draw(renderer);
    int firstMarker = sample(idle, renderer, "D4");
    int minorMarker = sample(idle, renderer, "Q10");
    int emptyContinuation = sample(idle, renderer, "D16");
    assertNotEquals(emptyContinuation, firstMarker, "fixture must show the main candidate");
    assertNotEquals(emptyContinuation, minorMarker, "fixture must show the low-visit candidate");

    hover(renderer, "D4");
    BufferedImage pending = draw(renderer);
    ImageIO.write(pending, "png", result.resolveSibling("pending.png").toFile());
    assertEquals(firstMarker, sample(pending, renderer, "D4"), "first pending request must keep its candidate marker");
    assertEquals(minorMarker, sample(pending, renderer, "Q10"), "pending preview must keep low-visit markers too");
    assertEquals(emptyContinuation, sample(pending, renderer, "D16"), "no partial variation before publication");
    finish(work);
    BufferedImage visible = draw(renderer);
    assertEquals(Color.BLACK.getRGB(), sample(visible, renderer, "D16"), "complete preview includes its third stone");

    hover(renderer, "Q4");
    BufferedImage replacing = draw(renderer);
    assertEquals(Color.BLACK.getRGB(), sample(replacing, renderer, "D16"), "direct replacement keeps the visible variation");
    assertEquals(sample(visible, renderer, "Q10"), sample(replacing, renderer, "Q10"), "replacement must not flash idle candidates");
    finish(work);
    assertEquals(Color.WHITE.getRGB(), sample(draw(renderer), renderer, "K10"));

    hover(renderer, "K16");
    BufferedImage left = draw(renderer);
    assertEquals(emptyContinuation, sample(left, renderer, "D16"));
    hover(renderer, "D4");
    assertEquals(firstMarker, sample(draw(renderer), renderer, "D4"), "reentry also keeps candidates while waiting");
    hover(renderer, "K16");
    finish(work);
    assertEquals(emptyContinuation, sample(draw(renderer), renderer, "D16"), "cancelled completion must not restore stones");
    assertSame(node, Lizzie.board.getHistory().getCurrentHistoryNode());
    assertArrayEquals(stones, Lizzie.board.getStones());
    ImageIO.write(visible, "png", result.resolveSibling("complete.png").toFile());
    exerciseFloating(result, true);
    exerciseFloating(result, false);
    assertSame(node, Lizzie.board.getHistory().getCurrentHistoryNode());
    assertArrayEquals(stones, Lizzie.board.getStones());
  }

  private static MoveData move(String coordinate, int visits, int order, String... continuation) {
    MoveData move = new MoveData();
    move.coordinate = coordinate;
    move.playouts = visits;
    move.order = order;
    move.winrate = 50;
    move.variation = new ArrayList<>(List.of(coordinate));
    move.variation.addAll(List.of(continuation));
    return move;
  }

  private static void hover(BoardRenderer renderer, String coordinate) throws Exception {
    int[] point = Board.convertNameToCoordinates(coordinate);
    int x = renderer.getLocation().x + (int) field(renderer, "scaledMarginWidth")
        + point[0] * (int) field(renderer, "squareWidth");
    int y = renderer.getLocation().y + (int) field(renderer, "scaledMarginHeight")
        + point[1] * (int) field(renderer, "squareHeight");
    assertTrue(Lizzie.frame.onMouseMoved(x, y));
  }

  private static void finish(ArrayDeque<Runnable> work) {
    while (!work.isEmpty()) work.remove().run();
  }

  private static BufferedImage draw(BoardRenderer renderer) {
    Rectangle bounds = renderer.getBoardBounds();
    BufferedImage image = new BufferedImage(
        Math.max(1, bounds.x + bounds.width), Math.max(1, bounds.y + bounds.height),
        BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    renderer.draw(graphics);
    graphics.dispose();
    return image;
  }
  private static void exerciseFloating(Path result, boolean editMode) throws Exception {
    FloatBoard board = new FloatBoard(30, 30, 500, 500, 0, false);
    Lizzie.frame.floatBoard = board;
    try {
      FloatBoardRenderer renderer = board.boardRenderer;
      renderer.editMode = editMode;
      renderer.setLocation(0, 0);
      renderer.setBoardLength(500, 500);
      ArrayDeque<Runnable> work = new ArrayDeque<>();
      VariationPreviewState state = (VariationPreviewState) field(renderer, "preview");
      Field scheduler = VariationPreviewState.class.getDeclaredField("scheduler");
      scheduler.setAccessible(true);
      scheduler.set(state, new VariationPreviewScheduler(work::add, Runnable::run));
      board.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      BufferedImage idle = draw(renderer);
      int emptyContinuation = sample(idle, renderer, "D16");
      board.mouseOverCoordinate = Board.convertNameToCoordinates("D4");
      renderer.selectNormalVariation();
      BufferedImage pending = draw(renderer);
      assertEquals(sample(idle, renderer, "D4"), sample(pending, renderer, "D4"),
          "floating board must retain its pending candidate");
      assertEquals(sample(idle, renderer, "Q10"), sample(pending, renderer, "Q10"),
          "floating board must retain low-visit markers");
      assertEquals(emptyContinuation, sample(pending, renderer, "D16"),
          "floating board must not expose a partial variation");
      finish(work);
      BufferedImage visible = draw(renderer);
      assertEquals(Color.BLACK.getRGB(), sample(visible, renderer, "D16"));

      board.mouseOverCoordinate = Board.convertNameToCoordinates("Q4");
      renderer.selectNormalVariation();
      BufferedImage replacing = draw(renderer);
      assertEquals(Color.BLACK.getRGB(), sample(replacing, renderer, "D16"),
          "floating replacement must retain the complete visible variation");
      assertEquals(sample(visible, renderer, "Q10"), sample(replacing, renderer, "Q10"),
          "floating replacement must not flash idle candidates");
      finish(work);
      assertEquals(Color.WHITE.getRGB(), sample(draw(renderer), renderer, "K10"));

      board.mouseOverCoordinate = Board.convertNameToCoordinates("K16");
      renderer.selectNormalVariation();
      assertEquals(emptyContinuation, sample(draw(renderer), renderer, "D16"));
      board.mouseOverCoordinate = Board.convertNameToCoordinates("D4");
      renderer.selectNormalVariation();
      assertEquals(sample(idle, renderer, "D4"), sample(draw(renderer), renderer, "D4"),
          "floating reentry must retain candidates while waiting");
      board.mouseOverCoordinate = Board.convertNameToCoordinates("K16");
      renderer.selectNormalVariation();
      finish(work);
      assertEquals(emptyContinuation, sample(draw(renderer), renderer, "D16"),
          "cancelled floating completion must not restore stones");
      assertNull(state.selected());
      assertNull(state.published());
      ImageIO.write(pending, "png", result.resolveSibling(
          editMode ? "floating-edit-pending.png" : "floating-browse-pending.png").toFile());
    } finally {
      board.dispose();
      Lizzie.frame.floatBoard = null;
    }
  }

  private static BufferedImage draw(FloatBoardRenderer renderer) {
    BufferedImage image = new BufferedImage(500, 500, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    renderer.draw(graphics);
    graphics.dispose();
    return image;
  }


  private static int sample(BufferedImage image, Object renderer, String coordinate) throws Exception {
    int[] point = Board.convertNameToCoordinates(coordinate);
    int radius = (int) field(renderer, "stoneRadius");
    int x = (int) field(renderer, "x") + (int) field(renderer, "scaledMarginWidth")
        + point[0] * (int) field(renderer, "squareWidth") + radius / 2;
    int y = (int) field(renderer, "y") + (int) field(renderer, "scaledMarginHeight")
        + point[1] * (int) field(renderer, "squareHeight") + radius / 2;
    return image.getRGB(x, y);
  }

  private static Object field(Object renderer, String name) throws Exception {
    Field field = renderer.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(renderer);
  }
}
