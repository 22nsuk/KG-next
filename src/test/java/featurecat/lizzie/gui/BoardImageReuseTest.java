package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.swing.JPanel;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercise production painting entry points without starting a window or an engine. */
class BoardImageReuseTest {
  private Config oldConfig;
  private Board oldBoard;
  private LizzieFrame oldFrame;
  private Leelaz oldEngine;
  private Leelaz oldSecondEngine;
  private boolean oldScaled;

  @BeforeEach
  void setup() throws Exception {
    oldConfig = Lizzie.config;
    oldBoard = Lizzie.board;
    oldFrame = Lizzie.frame;
    oldEngine = Lizzie.leelaz;
    oldSecondEngine = Lizzie.leelaz2;
    oldScaled = Config.isScaled;
    Lizzie.config = allocate(Config.class);
    Lizzie.config.persisted = new JSONObject().put("ui-persist", new JSONObject());
    Lizzie.board = allocate(Board.class);
    field(Board.class, "history")
        .set(
            Lizzie.board,
            new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight)));
    Lizzie.frame = allocate(LizzieFrame.class);
    Lizzie.leelaz = null;
    Lizzie.leelaz2 = null;
    Config.isScaled = false;
  }

  @AfterEach
  void restore() {
    Lizzie.config = oldConfig;
    Lizzie.board = oldBoard;
    Lizzie.frame = oldFrame;
    Lizzie.leelaz = oldEngine;
    Lizzie.leelaz2 = oldSecondEngine;
    Config.isScaled = oldScaled;
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void ownershipMethodsReuseStorageAndMatchFreshRendering(int kind) throws Exception {
    Object renderer = renderer(kind);
    List<Double> ownership =
        new ArrayList<>(Collections.nCopies(Board.boardWidth * Board.boardHeight, 0.7));
    Set<Object> pixels = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int step = 0; step < 24; step++) {
      boolean size = step % 2 == 0;
      boolean reverse = step % 3 == 0;
      boolean raw = step % 4 == 0;
      Lizzie.config.showKataGoEstimateBigBelow = step % 5 == 0;
      Lizzie.config.showKataGoEstimateSmall = step % 3 == 0;
      Lizzie.config.showKataGoEstimateNotOnlive = step % 4 == 0;
      Lizzie.config.showPureEstimateNotOnlive = step % 4 == 1;
      Lizzie.board.getData().blackToPlay = step % 3 != 0;
      Lizzie.board.getData().stones[0] = step % 2 == 0 ? Stone.BLACK : Stone.WHITE;
      // Mutate the same list: identity alone must never stand in for current ownership values.
      for (int i = 0; i < ownership.size(); i++)
        ownership.set(i, (i + step) % 3 == 0 ? 0.0 : (step % 2 == 0 ? 0.7 : -0.4));
      drawOwnership(renderer, ownership, size, reverse, raw);
      BufferedImage actual = ownershipImage(renderer);
      pixels.add(actual.getRaster().getDataBuffer());
      Object fresh = renderer(kind);
      drawOwnership(fresh, ownership, size, reverse, raw);
      assertPixelsEqual(ownershipImage(fresh), actual);
    }
    assertEquals(2, pixels.size(), "repaints at one size must alternate two pixel buffers");
    geometry(renderer, 120, 100);
    drawOwnership(renderer, Collections.nCopies(ownership.size(), 0.0), false, false, false);
    assertEquals(120, ownershipImage(renderer).getWidth());
    assertEquals(100, ownershipImage(renderer).getHeight());
    assertTransparent(ownershipImage(renderer));
    renderer.getClass().getMethod("removeKataEstimateImage").invoke(renderer);
    assertTransparent(ownershipImage(renderer));
    drawOwnership(renderer, ownership, false, false, false);
    Object fresh = renderer(kind);
    geometry(fresh, 120, 100);
    drawOwnership(fresh, ownership, false, false, false);
    assertPixelsEqual(ownershipImage(fresh), ownershipImage(renderer));
  }

  @Test
  void legacySubBoardCountBlocksAlsoReuseAndClearTheirPixels() throws Exception {
    SubBoardRenderer renderer = (SubBoardRenderer) renderer(1);
    Set<Object> pixels = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int step = 0; step < 8; step++) {
      renderer.drawcountblock(new ArrayList<>(List.of(step % 2 == 0 ? 1 : -1, 0, 1)));
      pixels.add(ownershipImage(renderer).getRaster().getDataBuffer());
    }
    assertEquals(2, pixels.size());
    renderer.drawcountblock(new ArrayList<>(List.of(0, 0, 0)));
    assertTransparent(ownershipImage(renderer));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void boardPanelsReuseBuffersAndIndependentCapturesAreDetached(int kind) throws Exception {
    Object window = panelWindow(kind);
    BufferedImageSurface surface =
        (BufferedImageSurface) field(window.getClass(), "boardImage").get(window);
    Set<Object> pixels = Collections.newSetFromMap(new IdentityHashMap<>());
    paintPanel(window);
    BufferedImage capture =
        kind == 0
            ? ((IndependentMainBoard) window).snapshotImage()
            : kind == 1 ? ((IndependentSubBoard) window).snapshotImage() : surface.snapshot();
    int[] captured = pixels(capture);
    for (int step = 0; step < 16; step++) {
      paintPanel(window);
      pixels.add(front(surface).getRaster().getDataBuffer());
    }
    assertEquals(2, pixels.size());
    assertArrayEquals(captured, pixels(capture));
    if (kind == 2) {
      ((FloatBoard) window).hideSuggestion = true;
      paintPanel(window);
      assertTransparent(surface.snapshot());
    } else {
      JPanel panel = (JPanel) field(window.getClass(), "mainPanel").get(window);
      panel.setSize(140, 110);
      paintPanel(window);
      assertEquals(140, surface.snapshot().getWidth());
      assertEquals(110, surface.snapshot().getHeight());
    }
  }

  private static Object renderer(int kind) throws Exception {
    Object renderer =
        switch (kind) {
          case 0 -> new BoardRenderer(false);
          case 1 -> new SubBoardRenderer(false);
          case 2 -> new FloatBoardRenderer();
          default -> throw new IllegalArgumentException("renderer kind");
        };
    geometry(renderer, 240, 220);
    return renderer;
  }

  private static void geometry(Object renderer, int width, int height) throws Exception {
    String[] names = {
      "boardWidth",
      "boardHeight",
      "scaledMarginWidth",
      "scaledMarginHeight",
      "squareWidth",
      "squareHeight",
      "stoneRadius"
    };
    int[] values = {width, height, 20, 20, 10, 10, 5};
    for (int i = 0; i < names.length; i++)
      field(renderer.getClass(), names[i]).setInt(renderer, values[i]);
  }

  private static void drawOwnership(
      Object renderer, List<Double> ownership, boolean size, boolean reverse, boolean raw)
      throws Exception {
    if (size)
      renderer
          .getClass()
          .getMethod("drawKataEstimateBySize", List.class, boolean.class)
          .invoke(renderer, ownership, reverse);
    else
      renderer
          .getClass()
          .getMethod("drawKataEstimateByTransparent", List.class, boolean.class, boolean.class)
          .invoke(renderer, ownership, reverse, raw);
  }

  private static BufferedImage ownershipImage(Object renderer) throws Exception {
    Object image = field(renderer.getClass(), "kataEstimateImage").get(renderer);
    // The former BufferedImage field allows the allocation regression to be run on the base
    // revision.
    return image instanceof BufferedImage
        ? (BufferedImage) image
        : front((BufferedImageSurface) image);
  }

  private static BufferedImage front(BufferedImageSurface surface) throws Exception {
    return (BufferedImage) field(BufferedImageSurface.class, "front").get(surface);
  }

  private static Object panelWindow(int kind) throws Exception {
    Object window;
    Object renderer;
    if (kind == 0) {
      window = allocate(IndependentMainBoard.class);
      renderer =
          new BoardRenderer(false) {
            int paints;

            @Override
            public void setLocation(int x, int y) {}

            @Override
            public void setBoardLength(int width, int height) {}

            @Override
            public void draw(Graphics2D g) {
              drawMarker(g, ++paints);
            }
          };
    } else if (kind == 1) {
      window = allocate(IndependentSubBoard.class);
      renderer =
          new SubBoardRenderer(false) {
            int paints;

            @Override
            public void setLocation(int x, int y) {}

            @Override
            public void setBoardLength(int width, int height) {}

            @Override
            public void draw(Graphics2D g) {
              drawMarker(g, ++paints);
            }
          };
    } else {
      window = allocate(FloatBoard.class);
      renderer =
          new FloatBoardRenderer() {
            int paints;

            @Override
            public void setLocation(int x, int y) {}

            @Override
            public void setBoardLength(int width, int height) {}

            @Override
            public void draw(Graphics2D g) {
              drawMarker(g, ++paints);
            }
          };
      field(FloatBoard.class, "posWidth").setInt(window, 240);
      field(FloatBoard.class, "posHeight").setInt(window, 220);
    }
    JPanel panel = new JPanel();
    panel.setSize(240, 220);
    field(window.getClass(), "mainPanel").set(window, panel);
    field(window.getClass(), kind == 1 ? "subBoardRenderer" : "boardRenderer")
        .set(window, renderer);
    field(window.getClass(), "boardImage").set(window, new BufferedImageSurface());
    return window;
  }

  private static void drawMarker(Graphics2D graphics, int paints) {
    graphics.setColor(new Color(paints * 7 % 256, paints * 11 % 256, paints * 13 % 256));
    graphics.fillRect(20, 20, 10, 10);
  }

  private static void paintPanel(Object window) throws Exception {
    BufferedImage target = new BufferedImage(240, 220, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = target.createGraphics();
    try {
      Method paint = window.getClass().getDeclaredMethod("paintMianPanel", Graphics.class);
      paint.setAccessible(true);
      paint.invoke(window, graphics);
    } finally {
      graphics.dispose();
    }
  }

  private static void assertPixelsEqual(BufferedImage expected, BufferedImage actual) {
    assertEquals(expected.getWidth(), actual.getWidth());
    assertEquals(expected.getHeight(), actual.getHeight());
    assertArrayEquals(pixels(expected), pixels(actual));
  }

  private static int[] pixels(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }

  private static void assertTransparent(BufferedImage image) {
    assertArrayEquals(new int[image.getWidth() * image.getHeight()], pixels(image));
  }

  private static Field field(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    return type.cast(
        ((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null))
            .allocateInstance(type));
  }
}
