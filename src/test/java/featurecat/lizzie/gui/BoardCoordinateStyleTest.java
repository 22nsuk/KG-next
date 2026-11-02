package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.theme.Theme;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.ResourceBundle;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoardCoordinateStyleTest {
  private final Config previousConfig = Lizzie.config;
  private final Board previousBoard = Lizzie.board;
  private final LizzieFrame previousFrame = Lizzie.frame;
  private final ResourceBundle previousResources = Lizzie.resourceBundle;
  private final Font previousFont = LizzieFrame.uiFont;
  private final Leelaz previousEngine = Lizzie.leelaz;
  private final Leelaz previousSecondEngine = Lizzie.leelaz2;
  private final int previousWidth = Board.boardWidth;
  private final int previousHeight = Board.boardHeight;

  @BeforeEach
  void setUp() throws Exception {
    Lizzie.config = allocate(Config.class);
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.config.theme = new Theme();
    Lizzie.config.theme.getTheme(Lizzie.config.uiConfig);
    Lizzie.config.showCoordinates = true;
    Lizzie.config.usePureBoard = true;
    Lizzie.config.pureBoardColor = new Color(217, 152, 77);
    Lizzie.config.usePureStone = true;
    Lizzie.config.boardStyle = Config.BOARD_STYLE_JAPANESE;
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.KOREAN);
    LizzieFrame.uiFont = new Font("Dialog", Font.PLAIN, 12);
    Lizzie.frame = allocate(LizzieFrame.class);
    boardSize(19, 19);
  }

  @AfterEach
  void tearDown() {
    Lizzie.config = previousConfig;
    Lizzie.board = previousBoard;
    Lizzie.frame = previousFrame;
    Lizzie.resourceBundle = previousResources;
    LizzieFrame.uiFont = previousFont;
    Lizzie.leelaz = previousEngine;
    Lizzie.leelaz2 = previousSecondEngine;
    Board.boardWidth = previousWidth;
    Board.boardHeight = previousHeight;
    Zobrist.init();
  }

  @ParameterizedTest
  @CsvSource({"false,0", "false,1", "true,0"})
  void sizeChangeMovesCachedStonesEvenWhenImageDimensionsStayTheSame(
      boolean independent, int boardIndex) throws Exception {
    BoardRenderer renderer = new BoardRenderer(independent);
    renderer.setOrder(boardIndex);
    layout(renderer, 667, 667);
    assertArrayEquals(new int[] {667, 667, 36, 36, 33, 33}, geometry(renderer));
    render(renderer);
    BufferedImage oldStones = field(renderer, "cachedStonesImage", BufferedImage.class);
    assertEquals(Color.BLACK.getRGB(), oldStones.getRGB(36, 36));

    style(140, false);
    layout(renderer, 667, 667);
    render(renderer);
    assertArrayEquals(new int[] {667, 667, 45, 45, 32, 32}, geometry(renderer));
    BufferedImage newStones = field(renderer, "cachedStonesImage", BufferedImage.class);
    assertNotSame(oldStones, newStones, "The unchanged image size must not retain the old grid");
    assertEquals(Color.BLACK.getRGB(), newStones.getRGB(45, 45));
    assertEquals(0, newStones.getRGB(26, 26) >>> 24, "The old edge of the stone is cleared");
    assertBoardPoint(renderer, 0, 0);
    assertBoardPoint(renderer, 3, 15);
  }

  @Test
  void boldOnlyRepaintsCoordinatesWithoutMovingStonesOrChangingTheBaseFont() throws Exception {
    BoardRenderer renderer = new BoardRenderer(false);
    layout(renderer, 667, 667);
    render(renderer);
    int[] oldGeometry = geometry(renderer);
    BufferedImage oldBoard = field(renderer, "cachedBackgroundImage", BufferedImage.class);
    BufferedImage oldStones = field(renderer, "cachedStonesImage", BufferedImage.class);
    Font baseFont = LizzieFrame.uiFont;

    style(100, true);
    layout(renderer, 667, 667);
    render(renderer);
    BufferedImage boldBoard = field(renderer, "cachedBackgroundImage", BufferedImage.class);
    assertFalse(Arrays.equals(pixels(oldBoard), pixels(boldBoard)));
    assertArrayEquals(oldGeometry, geometry(renderer));
    assertSame(oldStones, field(renderer, "cachedStonesImage", BufferedImage.class));
    assertSame(baseFont, LizzieFrame.uiFont);
    assertFalse(LizzieFrame.uiFont.isBold());
  }

  @Test
  void hidingCoordinatesRestoresNameMarginsAndShowingThemRestoresLargeMargins() throws Exception {
    Lizzie.config.showNameInBoard = true;
    Lizzie.board.getHistory().getGameInfo().setPlayerBlack("이세돌");
    BoardRenderer renderer = new BoardRenderer(false);
    style(200, true);
    layout(renderer, 667, 667);
    int width = renderer.boardWidth;
    int height = renderer.boardHeight;
    int largeMargin = geometry(renderer)[2];

    Lizzie.config.showCoordinates = false;
    layout(renderer, width, height);
    BoardRenderer freshHiddenRenderer = new BoardRenderer(false);
    layout(freshHiddenRenderer, width, height);
    assertArrayEquals(geometry(freshHiddenRenderer), geometry(renderer));
    assertTrue(geometry(renderer)[2] < largeMargin);
    int[] hiddenGeometry = geometry(renderer);

    style(140, false);
    layout(renderer, width, height);
    assertArrayEquals(hiddenGeometry, geometry(renderer));
    Lizzie.config.showCoordinates = true;
    layout(renderer, width, height);
    BoardRenderer freshVisibleRenderer = new BoardRenderer(false);
    layout(freshVisibleRenderer, width, height);
    assertArrayEquals(geometry(freshVisibleRenderer), geometry(renderer));
    assertTrue(geometry(renderer)[2] > hiddenGeometry[2]);
  }

  @ParameterizedTest
  @CsvSource({"9,9", "13,13", "19,19", "19,13", "13,19", "3,3", "19,3", "3,19", "19,4", "4,19"})
  void enlargedCoordinatesKeepIntersectionsClickableOnSquareAndRectangularBoards(
      int width, int height) throws Exception {
    boardSize(width, height);
    BoardRenderer renderer = new BoardRenderer(false);
    for (int size : CoordinateLabels.SIZES) {
      style(size, true);
      layout(renderer, 900, 700);
      render(renderer);
      assertTrue(renderer.boardWidth > 0 && renderer.boardHeight > 0);
      assertTrue(renderer.boardWidth <= 900 && renderer.boardHeight <= 700);
      assertTrue(geometry(renderer)[4] > 0 && geometry(renderer)[5] > 0);
      for (int x : new int[] {0, width / 2, width - 1}) {
        for (int y : new int[] {0, height / 2, height - 1}) {
          assertBoardPoint(renderer, x, y);
        }
      }
    }
  }

  @Test
  void missingSettingsAndExplicitStandardSettingsRenderIdentically() throws Exception {
    BoardRenderer renderer = new BoardRenderer(false);
    layout(renderer, 667, 667);
    BufferedImage original = render(renderer);
    style(100, false);
    renderer.reDrawGobanAnyway();
    layout(renderer, 667, 667);
    assertArrayEquals(pixels(original), pixels(render(renderer)));
  }

  @ParameterizedTest
  @CsvSource({"false,0", "true,0", "false,1", "true,1"})
  void coordinateToggleRepaintsRawOwnershipAndClearsIncompleteResponses(
      boolean bySize, int boardIndex) throws Exception {
    Lizzie.leelaz = rawOwnershipEngine(0);
    Lizzie.leelaz2 = rawOwnershipEngine(19 * 19 - 1);
    Leelaz source = boardIndex == 1 ? Lizzie.leelaz2 : Lizzie.leelaz;
    int coordinate = boardIndex == 1 ? 18 : 0;
    BoardRenderer renderer = new BoardRenderer(false);
    renderer.setOrder(boardIndex);
    style(200, true);
    layout(renderer, 667, 667);
    Lizzie.frame.isShowingHeatmap = true;
    Lizzie.config.showPureEstimateBySize = bySize;
    paintRawOwnership(renderer, source.heatOwnership, bySize);
    int[] before = geometry(renderer);
    int oldPoint = before[2] + coordinate * before[4];
    assertTrue((ownershipImage(renderer).getRGB(oldPoint, oldPoint) >>> 24) > 0);

    Lizzie.config.toggleCoordinates();
    layout(renderer, 667, 667);
    int[] after = geometry(renderer);
    int newPoint = after[2] + coordinate * after[4];
    BufferedImage updated = ownershipImage(renderer);
    assertTrue((updated.getRGB(newPoint, newPoint) >>> 24) > 0);
    assertEquals(0, updated.getRGB(oldPoint, oldPoint) >>> 24);

    source.heatOwnership = new ArrayList<>();
    Lizzie.config.toggleCoordinates();
    layout(renderer, 667, 667);
    assertTrue(Arrays.stream(pixels(ownershipImage(renderer))).allMatch(pixel -> (pixel >>> 24) == 0));
    source.heatOwnership = rawOwnershipEngine(boardIndex == 1 ? 19 * 19 - 1 : 0).heatOwnership;
    paintRawOwnership(renderer, source.heatOwnership, bySize);
    assertTrue(Arrays.stream(pixels(ownershipImage(renderer))).anyMatch(pixel -> (pixel >>> 24) > 0));
  }

  private static Leelaz rawOwnershipEngine(int occupiedIndex) throws Exception {
    Leelaz engine = allocate(Leelaz.class);
    engine.isKatago = true;
    engine.iskataHeatmapShowOwner = true;
    engine.heatOwnership = new ArrayList<>(Collections.nCopies(19 * 19, 0.0));
    engine.heatOwnership.set(occupiedIndex, 0.9);
    return engine;
  }

  private static void paintRawOwnership(BoardRenderer renderer, ArrayList<Double> ownership, boolean bySize) {
    if (bySize) renderer.drawKataEstimateBySize(ownership, false);
    else renderer.drawKataEstimateByTransparent(ownership, false, true);
  }

  private static BufferedImage ownershipImage(BoardRenderer renderer) throws Exception {
    return field(renderer, "kataEstimateImage", BufferedImageSurface.class).snapshot();
  }

  private void boardSize(int width, int height) throws Exception {
    Board.boardWidth = width;
    Board.boardHeight = height;
    Zobrist.init();
    BoardData data = BoardData.empty(width, height);
    data.stones[Board.getIndex(0, 0)] = Stone.BLACK;
    data.stones[Board.getIndex(width - 1, height - 1)] = Stone.WHITE;
    Lizzie.board = allocate(Board.class);
    Lizzie.board.setHistory(new BoardHistoryList(data));
    Lizzie.board.isExtremlySmallBoard = width < 4;
    Lizzie.board.setSetupMode(true);
  }

  private static void style(int size, boolean bold) {
    Lizzie.config.theme.config.put("coordinate-size", size).put("coordinate-bold", bold);
  }

  private static void layout(BoardRenderer renderer, int width, int height) {
    renderer.setLocation(10, 10);
    renderer.setBoardLength(width, height);
  }

  private static BufferedImage render(BoardRenderer renderer) throws Exception {
    BufferedImage image = new BufferedImage(940, 740, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      renderer.draw(graphics);
    } finally {
      graphics.dispose();
    }
    // Setup mode returns before the normal paint epilogue; establish the same settled cache state.
    Field visibility = BoardRenderer.class.getDeclaredField("cachedShowCoords");
    visibility.setAccessible(true);
    visibility.setBoolean(renderer, Lizzie.config.showCoordinates);
    return image;
  }

  private static int[] geometry(BoardRenderer renderer) throws Exception {
    return new int[] {
      renderer.boardWidth, renderer.boardHeight,
      field(renderer, "scaledMarginWidth", Integer.class),
      field(renderer, "scaledMarginHeight", Integer.class),
      field(renderer, "squareWidth", Integer.class),
      field(renderer, "squareHeight", Integer.class)
    };
  }

  private static void assertBoardPoint(BoardRenderer renderer, int x, int y) throws Exception {
    int[] geometry = geometry(renderer);
    Point origin = renderer.getLocation();
    assertArrayEquals(
        new int[] {x, y},
        renderer.convertScreenToCoordinates(
            origin.x + geometry[2] + x * geometry[4],
            origin.y + geometry[3] + y * geometry[5]).orElseThrow());
  }

  private static int[] pixels(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }

  private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(owner));
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
