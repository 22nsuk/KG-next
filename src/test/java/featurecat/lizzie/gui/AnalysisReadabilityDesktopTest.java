package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGameSnapshotFixtures;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Production windows and click routes with deterministic analysis data, without an engine. */
class AnalysisReadabilityDesktopTest {
  @Test
  void nameWinratesAndMoveSummaryRemainReadableAndInteractive() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.run(getClass(), "analysis-readability", List.of(), List.of());
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) {
    try {
      runProbe(Path.of(args[0]), Path.of(args[1]));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void runProbe(Path work, Path result) throws Exception {
    System.setProperty("lizzie.work.dir", work.toString());
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":3,"
            + "\"auto-check-engine-alive\":false,\"auto-quick-analyze-on-load\":false,"
            + "\"analysis-engine-preload\":false,\"play-sound\":false}}");
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
    edt(
        () -> {
          for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
          Lizzie.engineManager.autoCheckEngineAlive(false);
          Lizzie.frame.setBounds(30, 30, 1180, 850);
          Lizzie.config.showNameInBoard = true;
          Lizzie.config.showCoordinates = true;
          Lizzie.config.showWinrateGraph = true;
          assertTrue(
              SGFParser.loadFromString(
                  "(;FF[4]GM[1]SZ[19]KM[6.5]PB[신진서 분석 검증용 긴 이름]PW[박정환 분석 검증용 긴 이름]"
                      + ";B[pd];W[dd];B[pp];W[dp])",
                  false));
          Lizzie.board.goToMoveNumber(4);
          BoardData data = Lizzie.board.getHistory().getData();
          measured(data, 56.25, 3);
          measured(
              Lizzie.board.getHistory().getCurrentHistoryNode().previous().orElseThrow().getData(),
              50,
              2);
          Lizzie.frame.refresh();
          return null;
        });
    await(() -> nameBounds(LizzieFrame.boardRenderer) != null);
    Object node = edt(() -> Lizzie.board.getHistory().getCurrentHistoryNode());
    assertFalse(edt(() -> NameWinrates.shown(Lizzie.config.uiConfig)));
    clickNames(Lizzie.frame.mainPanel, LizzieFrame.boardRenderer);
    assertTrue(edt(() -> NameWinrates.shown(Lizzie.config.uiConfig)));
    assertSame(node, edt(() -> Lizzie.board.getHistory().getCurrentHistoryNode()));
    capture(Lizzie.frame, result.resolveSibling("analysis-readability-ko.png"));

    edt(
        () -> {
          assertNull(new FunctionSearchController(Lizzie.frame).activate("menu.showNameWinrates"));
          var path = LizzieFrame.menu.functionPath("menu.showNameWinrates");
          var item = (JCheckBoxMenuItem) path.get(path.size() - 1);
          assertTrue(item.isSelected());
          assertTrue(
              NameWinrates.shown(Lizzie.config.uiConfig), "Search navigation must not toggle");
          item.doClick(0);
          assertFalse(item.isSelected());
          MenuSelectionManager.defaultManager().clearSelectedPath();
          return null;
        });
    JSONObject saved = new JSONObject(Files.readString(work.resolve("config.txt")));
    assertFalse(NameWinrates.shown(saved.getJSONObject("ui")));

    // The real input listener takes the manual path during engine games.
    edt(
        () -> {
          EngineGameSnapshotFixtures.publishPlaying();
          Lizzie.frame.refresh();
          return null;
        });
    clickNames(Lizzie.frame.mainPanel, LizzieFrame.boardRenderer);
    assertTrue(edt(() -> NameWinrates.shown(Lizzie.config.uiConfig)));
    assertSame(node, edt(() -> Lizzie.board.getHistory().getCurrentHistoryNode()));
    edt(
        () -> {
          EngineGameSnapshotFixtures.publishIdle();
          return null;
        });
    checkComparisonRendererOwnership();
    checkArrowPerspectiveAndDirection();

    edt(
        () -> {
          Lizzie.config.theme.config.put("coordinate-size", 200).put("coordinate-bold", true);
          Lizzie.frame.setSize(880, 720);
          Lizzie.board.getHistory().getData().isChanged = true;
          Lizzie.frame.refresh();
          return null;
        });
    capture(Lizzie.frame, result.resolveSibling("analysis-readability-narrow-ko.png"));
    edt(
        () -> {
          Rectangle names = nameBounds(LizzieFrame.boardRenderer);
          assertNotNull(names);
          assertTrue(names.x >= LizzieFrame.boardRenderer.getLocation().x);
          assertTrue(
              names.getMaxX()
                  <= LizzieFrame.boardRenderer.getLocation().x
                      + LizzieFrame.boardRenderer.boardWidth);
          Rectangle previousBounds = new Rectangle(names);
          Lizzie.config.showNameInBoard = false;
          paintMain();
          assertFalse(LizzieFrame.boardRenderer.isOnPlayerName(previousBounds.x, previousBounds.y));
          assertNull(nameBounds(LizzieFrame.boardRenderer));
          Lizzie.config.showNameInBoard = true;
          Lizzie.board.setSetupMode(true);
          paintMain();
          assertNull(nameBounds(LizzieFrame.boardRenderer));
          Lizzie.board.setSetupMode(false);
          Lizzie.frame.refresh();
          return null;
        });

    edt(
        () -> {
          Lizzie.frame.toggleIndependentMainBoard();
          Lizzie.frame.independentMainBoard.setBounds(40, 40, 680, 680);
          return null;
        });
    IndependentMainBoard independent = Lizzie.frame.independentMainBoard;
    await(() -> nameBounds(independent.boardRenderer) != null);
    // The independent window owns this listener, whose coordinates match its undecorated panel.
    clickNames(independent, independent.boardRenderer);
    assertFalse(edt(() -> NameWinrates.shown(Lizzie.config.uiConfig)));
    edt(
        () -> {
          EngineGameSnapshotFixtures.publishPlaying();
          independent.refresh();
          return null;
        });
    clickNames(independent, independent.boardRenderer);
    assertTrue(edt(() -> NameWinrates.shown(Lizzie.config.uiConfig)));
    assertSame(node, edt(() -> Lizzie.board.getHistory().getCurrentHistoryNode()));
    edt(
        () -> {
          EngineGameSnapshotFixtures.publishIdle();
          independent.refresh();
          return null;
        });
    capture(independent, result.resolveSibling("analysis-readability-independent-ko.png"));
    assertTrue(
        NameWinrates.shown(
            new JSONObject(Files.readString(work.resolve("config.txt"))).getJSONObject("ui")));

    Files.writeString(result, "PASS");
    edt(
        () -> {
          for (Window window : Window.getWindows()) window.dispose();
          return null;
        });
  }

  private static void checkArrowPerspectiveAndDirection() throws Exception {
    edt(
        () -> {
          var current = Lizzie.board.getHistory().getCurrentHistoryNode();
          BoardData data = current.getData();
          BoardData previous = current.previous().orElseThrow().getData();
          boolean oldTurn = previous.blackToPlay;
          double oldRate = data.winrate;
          data.winrate = 60;
          previous.blackToPlay = data.blackToPlay;
          previous.winrate = 50;
          assertArrowColor(statisticsPixels(), false);
          measured(current.previous().orElseThrow().previous().orElseThrow().getData(), 50, 0);
          EngineGameSnapshotFixtures.publishPlaying();
          assertArrowColor(statisticsPixels(), true);
          EngineGameSnapshotFixtures.publishIdle();
          previous.blackToPlay = oldTurn;
          data.winrate = oldRate;
          return null;
        });
  }

  private static BufferedImage statisticsPixels() throws Exception {
    BufferedImage image = new BufferedImage(600, 120, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    Method draw =
        LizzieFrame.class.getDeclaredMethod(
            "drawMoveStatistics", Graphics2D.class, int.class, int.class, int.class, int.class);
    draw.setAccessible(true);
    try {
      draw.invoke(Lizzie.frame, graphics, 0, 0, 600, 120);
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static void assertArrowColor(BufferedImage image, boolean loss) {
    // At 60%, the new boundary is x=359. This antialiased triangle edge is two pixels
    // above the horizontal line, so the opposite arrow direction cannot color it.
    java.awt.Color pixel = new java.awt.Color(image.getRGB(355, 38), true);
    if (loss) assertTrue(pixel.getRed() > 80 && pixel.getGreen() < 40, pixel.toString());
    else assertTrue(pixel.getGreen() > 80 && pixel.getRed() < 40, pixel.toString());
  }

  private static void checkComparisonRendererOwnership() throws Exception {
    edt(
        () -> {
          BoardData data = Lizzie.board.getHistory().getData();
          data.winrate2 = 25;
          data.analysisHeaderSlots2 = 3;
          data.setPlayouts2(100);
          BoardRenderer renderer = new BoardRenderer(false);
          renderer.setOrder(1);
          renderer.setLocation(0, 0);
          renderer.setBoardLength(600, 600);
          int[] original = namePixels(renderer);
          double old = data.winrate;
          data.winrate = 90;
          assertArrayEquals(
              original, namePixels(renderer), "Comparison names must ignore the primary slot");
          data.winrate2 = 70;
          assertFalse(Arrays.equals(original, namePixels(renderer)));
          data.winrate = old;
          return null;
        });
  }

  private static int[] namePixels(BoardRenderer renderer) throws Exception {
    BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    Method draw = BoardRenderer.class.getDeclaredMethod("drawName", Graphics2D.class);
    draw.setAccessible(true);
    try {
      draw.invoke(renderer, graphics);
    } finally {
      graphics.dispose();
    }
    return image.getRGB(0, 0, 600, 600, null, 0, 600);
  }

  private static void measured(BoardData data, double winrate, double score) {
    data.setPlayouts(1000);
    data.analysisHeaderSlots = 3;
    data.isKataData = true;
    data.winrate = winrate;
    data.scoreMean = score;
  }

  private static void clickNames(Component component, BoardRenderer renderer) throws Exception {
    edt(
        () -> {
          if (component instanceof JComponent panel) {
            panel.paintImmediately(0, 0, panel.getWidth(), panel.getHeight());
          }
          Rectangle bounds = nameBounds(renderer);
          assertNotNull(bounds);
          int x = Utils.zoomIn(bounds.x + bounds.width / 2);
          int y = Utils.zoomIn(bounds.y + bounds.height / 2);
          component.dispatchEvent(
              new MouseEvent(
                  component,
                  MouseEvent.MOUSE_PRESSED,
                  System.currentTimeMillis(),
                  0,
                  x,
                  y,
                  1,
                  false,
                  MouseEvent.BUTTON1));
          return null;
        });
  }

  private static Rectangle nameBounds(BoardRenderer renderer) {
    try {
      Field field = BoardRenderer.class.getDeclaredField("playerNameBounds");
      field.setAccessible(true);
      return (Rectangle) field.get(renderer);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void paintMain() {
    Lizzie.frame.refresh();
    Lizzie.frame.mainPanel.paintImmediately(
        0, 0, Lizzie.frame.mainPanel.getWidth(), Lizzie.frame.mainPanel.getHeight());
  }

  private static void capture(Window window, Path output) throws Exception {
    edt(
        () -> {
          window.toFront();
          paintMain();
          return null;
        });
    Robot robot = new Robot();
    robot.waitForIdle();
    ImageIO.write(robot.createScreenCapture(edt(window::getBounds)), "png", output.toFile());
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (edt(condition::getAsBoolean)) return;
      Thread.sleep(10);
    }
    assertTrue(edt(condition::getAsBoolean), "Readability display did not settle");
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(10, TimeUnit.SECONDS);
  }
}
