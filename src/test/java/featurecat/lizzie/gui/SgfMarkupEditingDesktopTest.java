package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Uses the production toolbar and mouse listeners of both board windows. */
class SgfMarkupEditingDesktopTest {
  @Test
  void editsMarksWithoutSplittingLabelsOrRemovingOtherSgfProperties() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.runWithStartupTimeout(
            getClass(), "sgf-markup", List.of(), List.of(), 120, 90);
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
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":"
            + AppLocale.KOREAN.configValue()
            + ",\"auto-check-engine-alive\":false,\"auto-quick-analyze-on-load\":false,"
            + "\"analysis-engine-preload\":false,\"play-sound\":false}}");
    System.setProperty("lizzie.work.dir", work.toString());
    DesktopProbeProcess.phase(result, "application-starting");
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
    edt(
        () -> {
          for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
          Lizzie.engineManager.autoCheckEngineAlive(false);
          Lizzie.frame.setBounds(20, 20, 1180, 820);
          assertTrue(
              SGFParser.loadFromString(
                  "(;SZ[9]RU[Chinese]AB[cc][dd]LB[aa:A,B:C][bb :][ii :Z]CR[gg ]TR[ff ]"
                      + "AR[aa:bb][cc:dd]TB[cc][dd]XX[first,one][second:two])",
                  false));
          Lizzie.frame.refresh();
          return null;
        });
    await(() -> LizzieFrame.boardRenderer.boardWidth > 300);
    DesktopProbeProcess.applicationReady(result);
    selectTool("toolbar.markup-tools");
    capture(Lizzie.frame, result.resolveSibling("sgf-markup-main-ko.png"));

    DesktopProbeProcess.phase(result, "main-board-edit");
    selectTool("toolbar.markup-eraser");
    click(Lizzie.frame.mainPanel, LizzieFrame.boardRenderer, 8, 8);
    selectTool("toolbar.markup-number");
    click(Lizzie.frame.mainPanel, LizzieFrame.boardRenderer, 7, 7);
    selectTool("toolbar.markup-cross");
    click(Lizzie.frame.mainPanel, LizzieFrame.boardRenderer, 6, 6);
    edt(
        () -> {
          assertEquals(List.of("aa:A,B:C", "bb :", "hh:1"), data().getPropertyValues("LB"));
          assertTrue(data().getPropertyValues("CR").isEmpty());
          assertOtherProperties();
          Lizzie.frame.toggleIndependentMainBoard();
          Lizzie.frame.independentMainBoard.setBounds(40, 40, 680, 680);
          return null;
        });
    IndependentMainBoard independent = Lizzie.frame.independentMainBoard;
    await(() -> independent.isShowing() && independent.boardRenderer.boardWidth > 300);

    DesktopProbeProcess.phase(result, "independent-board-edit");
    selectTool("toolbar.markup-eraser");
    click(independent, independent.boardRenderer, 1, 1);
    selectTool("toolbar.markup-cross");
    click(independent, independent.boardRenderer, 5, 5);
    click(independent, independent.boardRenderer, 4, 4);
    edt(
        () -> {
          assertEquals(List.of("aa:A,B:C", "hh:1"), data().getPropertyValues("LB"));
          assertEquals(List.of("ee"), data().getPropertyValues("MA"));
          assertTrue(data().getPropertyValues("TR").isEmpty());
          assertOtherProperties();
          String saved = SGFParser.saveToString(false);
          BoardData reopened = SGFParser.parseSgf(saved, true).getStart().getData();
          assertEquals(data().getPropertyValues("LB"), reopened.getPropertyValues("LB"));
          Files.writeString(result.resolveSibling("edited.sgf"), saved);
          return null;
        });
    capture(independent, result.resolveSibling("sgf-markup-independent-ko.png"));

    DesktopProbeProcess.phase(result, "clear-marks");
    selectTool("toolbar.markup-clear");
    edt(
        () -> {
          assertTrue(data().getPropertyValues("LB").isEmpty());
          assertTrue(data().getPropertyValues("MA").isEmpty());
          assertOtherProperties();
          String saved = SGFParser.saveToString(false);
          BoardData reopened = SGFParser.parseSgf(saved, true).getStart().getData();
          for (String key : List.of("AB", "AR", "TB", "XX", "RU")) {
            assertEquals(data().getPropertyValues(key), reopened.getPropertyValues(key), key);
          }
          Files.writeString(result.resolveSibling("cleared.sgf"), saved);
          return null;
        });
    DesktopProbeProcess.phase(result, "first-node-move-markup");
    edt(
        () -> {
          assertTrue(SGFParser.loadFromString("(;SZ[9]LB[aa:A]B[bb]LB[cc:B])", false));
          assertTrue(Lizzie.board.nextMove(false));
          assertEquals(List.of("aa:A", "cc:B"), data().getPropertyValues("LB"));
          assertTrue(
              Lizzie.board.getHistory().getStart().getData().getPropertyValues("LB").isEmpty());
          Lizzie.frame.refresh();
          return null;
        });
    selectTool("toolbar.markup-eraser");
    click(independent, independent.boardRenderer, 0, 0);
    edt(
        () -> {
          assertEquals(List.of("cc:B"), data().getPropertyValues("LB"));
          BoardData reopened =
              SGFParser.parseSgf(SGFParser.saveToString(false), true)
                  .getStart()
                  .next()
                  .orElseThrow()
                  .getData();
          assertEquals(List.of("cc:B"), reopened.getPropertyValues("LB"));
          return null;
        });
    Files.writeString(result, "PASS");
  }

  private static BoardData data() {
    return Lizzie.board.getHistory().getData();
  }

  private static void assertOtherProperties() {
    assertEquals(List.of("cc", "dd"), data().getPropertyValues("AB"));
    assertEquals(List.of("aa:bb", "cc:dd"), data().getPropertyValues("AR"));
    assertEquals(List.of("cc", "dd"), data().getPropertyValues("TB"));
    assertEquals(List.of("first,one", "second:two"), data().getPropertyValues("XX"));
    assertEquals("Chinese", data().getProperty("RU"));
  }

  private static void selectTool(String id) throws Exception {
    edt(
        () -> {
          AbstractButton button = (AbstractButton) LizzieFrame.menu.topFunctionTarget(id);
          assertNotNull(button, id);
          button.doClick(0);
          return null;
        });
  }

  private static void click(Component component, BoardRenderer renderer, int column, int row)
      throws Exception {
    // dispatchEvent and the coordinate check run synchronously on the EDT; no native input is queued.
    edt(
        () -> {
          int x =
              renderer.getLocation().x
                  + field(renderer, "scaledMarginWidth")
                  + column * field(renderer, "squareWidth");
          int y =
              renderer.getLocation().y
                  + field(renderer, "scaledMarginHeight")
                  + row * field(renderer, "squareHeight");
          assertArrayEquals(
              new int[] {column, row}, renderer.convertScreenToCoordinates(x, y).orElseThrow());
          component.dispatchEvent(
              new MouseEvent(
                  component,
                  MouseEvent.MOUSE_PRESSED,
                  System.currentTimeMillis(),
                  0,
                  Utils.zoomIn(x),
                  Utils.zoomIn(y),
                  1,
                  false,
                  MouseEvent.BUTTON1));
          return null;
        });
  }

  private static int field(BoardRenderer renderer, String name) throws Exception {
    Field field = BoardRenderer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.getInt(renderer);
  }

  private static void capture(Window window, Path output) throws Exception {
    Robot robot = new Robot();
    robot.waitForIdle();
    ImageIO.write(robot.createScreenCapture(edt(window::getBounds)), "png", output.toFile());
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (edt(condition::getAsBoolean)) return;
      Thread.sleep(20);
    }
    assertTrue(edt(condition::getAsBoolean), "board did not become ready");
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(10, TimeUnit.SECONDS);
  }
}
