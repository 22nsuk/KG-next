package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.enginegame.BatchSummary;
import featurecat.lizzie.enginegame.EngineGameChromeTransition;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameView;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.GameActivity;
import featurecat.lizzie.enginegame.SwingEngineGameChrome;
import featurecat.lizzie.rules.BoardHistoryList;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Real Korean windows and production action listeners; file-save outcomes have contract tests. */
class EngineGameSaveDesktopTest {
  private static final String COMPLETED_SGF =
      "(;FF[4]GM[1]CA[UTF-8]SZ[19]KM[6.5]PB[흑 엔진]PW[백 엔진]RE[W+R]"
          + ";B[pd];W[dd];B[qp];W[dp];B[fc];W[cf];B[qf];W[qn]"
          + ";B[po];W[qo];B[pp];W[cp];B[cn];W[co];B[dn];W[do]C[백 불계승])";

  @Test
  void failedSaveKeepsKoreanRetryAndStopReachableAfterEnginesRetire() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.run(getClass(), "engine-game-save", List.of(), List.of());
    JSONObject evidence = new JSONObject(Files.readString(result));
    assertEquals("PASS", evidence.getString("result"));
    assertEquals(3, evidence.getInt("retryRoutes"));
    assertEquals(3, evidence.getInt("stopRoutes"));
    assertTrue(Files.isRegularFile(result.resolveSibling("save-failure-ko.png")));
    assertTrue(Files.isRegularFile(result.resolveSibling("save-failure-detailed-ko.png")));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},"
              + "\"logging\":{\"diagnostics-enabled\":false},"
              + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,"
              + "\"auto-check-engine-alive\":false,\"play-sound\":false,"
              + "\"show-double-menu\":true,\"show-double-menu-game-control\":true,"
              + "\"show-top-tool-bar\":true,\"use-language\":3}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      awaitEdt(
          () -> Lizzie.frame != null && Lizzie.frame.isShowing() && Lizzie.engineManager != null);
      RecordingSaveManager manager = new RecordingSaveManager();
      onEdt(
          () -> {
            for (Window window : Window.getWindows()) {
              if (window instanceof JDialog) window.dispose();
            }
            // The empty startup engine is only a placeholder; no engine process is used here.
            Lizzie.engineManager.autoCheckEngineAlive(false);
            Lizzie.setPrimaryEngine(null);
            Lizzie.setEngineManager(manager);
            Lizzie.frame.setBounds(60, 60, 1180, 820);
            Lizzie.config.loadSgfLast = true;
            return null;
          });
      Path fixture = Files.writeString(work.resolve("완료-대국.sgf"), COMPLETED_SGF);
      assertTrue(onEdt(() -> Lizzie.frame.loadFile(fixture.toFile(), true, false)));
      awaitEdt(() -> !Lizzie.board.isLoadingFile);
      BoardHistoryList completedHistory = onEdt(() -> Lizzie.board.getHistory());
      EngineGameSnapshot failed = failedSnapshot();
      AtomicInteger stopRoutes = new AtomicInteger();
      onEdt(
          () -> {
            Lizzie.engineGame.resetForTest();
            Lizzie.engineGame.publishSnapshotForTest(failed);
            Lizzie.engineGame.replaceChromeForTest(
                transition -> {
                  if (transition.kind() == EngineGameChromeTransition.Kind.SAVE_FAILED) {
                    stopRoutes.incrementAndGet();
                  }
                  SwingEngineGameChrome.INSTANCE.publish(transition);
                });
            SwingEngineGameChrome.INSTANCE.publish(
                new EngineGameChromeTransition(EngineGameChromeTransition.Kind.SAVE_FAILED, failed));
            Lizzie.frame.reSetLocNow();
            Lizzie.frame.validate();
            Lizzie.frame.refresh();
            return null;
          });
      awaitEdt(() -> topButton("toolbar.game-pause").isShowing());
      onEdt(
          () -> {
            assertNull(Lizzie.leelaz);
            assertFailureCaption();
            assertButton(topButton("toolbar.game-pause"), "재시도");
            assertButton(topButton("toolbar.game-stop"), text("Menu.endGameBtn"));
            return null;
          });
      DesktopProbeProcess.phase(result, "korean-save-failure-visible");
      capture(result.resolveSibling("save-failure-ko.png"));

      // These are the ordinary Korean toolbar and menu routes, with their real listeners.
      click(onEdt(() -> topButton("toolbar.game-pause")));
      awaitEdt(() -> manager.retries.get() == 1);
      click(onEdt(() -> topButton("toolbar.game-stop")));
      awaitEdt(() -> stopRoutes.get() == 1);
      clickMenu("menu.pauseEngineGame");
      awaitEdt(() -> manager.retries.get() == 2);
      clickMenu("menu.breakEngineGame");
      awaitEdt(() -> stopRoutes.get() == 2);

      // The legacy detail panel is normally collapsed in Korean. Exercise its existing controls
      // in the expanded production layout without changing the language or their listeners.
      onEdt(
          () -> {
            Lizzie.frame.toolbarHeight = 74;
            Lizzie.frame.reSetLocNow();
            LizzieFrame.toolbar.reSetButtonLocation();
            Lizzie.frame.validate();
            return null;
          });
      awaitEdt(() -> LizzieFrame.toolbar.btnEnginePkStop.isShowing());
      onEdt(
          () -> {
            assertButton(LizzieFrame.toolbar.btnEnginePkStop, "재시도");
            assertButton(LizzieFrame.toolbar.btnStartPk, "중지");
            return null;
          });
      capture(result.resolveSibling("save-failure-detailed-ko.png"));
      click(onEdt(() -> LizzieFrame.toolbar.btnEnginePkStop));
      awaitEdt(() -> manager.retries.get() == 3);
      click(onEdt(() -> LizzieFrame.toolbar.btnStartPk));
      awaitEdt(() -> stopRoutes.get() == 3);
      onEdt(
          () -> {
            assertSame(failed, Lizzie.engineGame.current());
            assertSame(completedHistory, Lizzie.board.getHistory());
            assertEquals(16, Lizzie.board.getCurrentMovenumber());
            assertFalse(Lizzie.engineGame.successorPending());
            assertEquals("기보 저장 실패", Menu.engineMenu.getText());
            return null;
          });
      assertEquals(COMPLETED_SGF, Files.readString(fixture));
      Files.writeString(
          result,
          new JSONObject()
              .put("result", "PASS")
              .put("retryRoutes", manager.retries.get())
              .put("stopRoutes", stopRoutes.get())
              .toString(2));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static EngineGameSnapshot failedSnapshot() {
    EngineParticipantIdentity first = new EngineParticipantIdentity("흑 엔진", "");
    EngineParticipantIdentity second = new EngineParticipantIdentity("백 엔진", "");
    EngineGameView view =
        new EngineGameView(first, second, EngineGamePlayMode.GENMOVE, 1, 0, 1, 0, 1, true, -1);
    BatchSummary batch =
        new BatchSummary(
            first, second, 1, 2, true, 0, 1, 0, 0, 0, 1, 0, 0, 0L, 0L, 0L, 0L, List.of());
    return new EngineGameSnapshot.BatchActive(batch, new GameActivity.SaveFailed(view));
  }

  private static AbstractButton topButton(String id) {
    AbstractButton button = (AbstractButton) LizzieFrame.menu.topFunctionTarget(id);
    assertNotNull(button, id);
    return button;
  }

  private static void assertFailureCaption() {
    assertEquals("기보 저장 실패", Menu.engineMenu.getText());
    if (Menu.engineMenu.isShowing()) return;
    for (var component : Lizzie.frame.windowMenuStrip.getComponents()) {
      if (component instanceof AbstractButton button
          && "기보 저장 실패".equals(button.getText())
          && button.isShowing()) return;
    }
    fail("The save-failure status must reach the visible menu surface");
  }

  private static void clickMenu(String id) throws Exception {
    JMenuItem item =
        onEdt(
            () -> {
              assertTrue(LizzieFrame.menu.locateFunction(id), id);
              List<JMenuItem> path = LizzieFrame.menu.functionPath(id);
              assertNotNull(path, id);
              return path.get(path.size() - 1);
            });
    awaitEdt(item::isShowing);
    click(item);
  }

  private static void click(AbstractButton button) throws Exception {
    Point center =
        onEdt(
            () -> {
              assertTrue(button.isShowing(), button.getText());
              assertTrue(button.isEnabled(), button.getText());
              Point location = button.getLocationOnScreen();
              location.translate(button.getWidth() / 2, button.getHeight() / 2);
              return location;
            });
    Robot robot = new Robot();
    robot.mouseMove(center.x, center.y);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    robot.waitForIdle();
  }

  private static void assertButton(AbstractButton button, String expected) {
    assertTrue(button.isShowing(), expected);
    assertTrue(button.isEnabled(), expected);
    assertEquals(expected, button.getText());
    Rectangle buttonBounds = button.getBounds();
    for (var sibling : button.getParent().getComponents()) {
      if (sibling != button && sibling.isVisible()) {
        assertFalse(
            buttonBounds.intersects(sibling.getBounds()),
            expected + " overlaps " + sibling.getClass().getSimpleName());
      }
    }
    assertEquals(-1, button.getFont().canDisplayUpTo(expected), expected + " missing glyph");
    Insets insets = button.getInsets();
    Rectangle view =
        new Rectangle(
            insets.left,
            insets.top,
            button.getWidth() - insets.left - insets.right,
            button.getHeight() - insets.top - insets.bottom);
    String displayed =
        SwingUtilities.layoutCompoundLabel(
            button,
            button.getFontMetrics(button.getFont()),
            button.getText(),
            button.getIcon(),
            button.getVerticalAlignment(),
            button.getHorizontalAlignment(),
            button.getVerticalTextPosition(),
            button.getHorizontalTextPosition(),
            view,
            new Rectangle(),
            new Rectangle(),
            button.getIconTextGap());
    assertEquals(expected, displayed, expected + " clipped at " + button.getSize());
  }

  private static String text(String key) {
    return Lizzie.resourceBundle.getString(key);
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeAndWait(task);
    return task.get();
  }

  private static void awaitEdt(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!onEdt(condition::getAsBoolean)) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Engine-game UI condition timed out");
      Thread.sleep(50);
    }
  }

  private static void capture(Path destination) throws Exception {
    Robot robot = new Robot();
    robot.waitForIdle();
    Rectangle bounds =
        onEdt(
            () -> {
              var root = Lizzie.frame.getRootPane();
              root.paintImmediately(0, 0, root.getWidth(), root.getHeight());
              Toolkit.getDefaultToolkit().sync();
              return Lizzie.frame.getBounds();
            });
    assertTrue(ImageIO.write(robot.createScreenCapture(bounds), "png", destination.toFile()));
  }

  private static final class RecordingSaveManager extends EngineManager {
    final AtomicInteger retries = new AtomicInteger();

    RecordingSaveManager() {
      super(List.of());
    }

    @Override
    public boolean retryEngineGameSave() {
      assertTrue(SwingUtilities.isEventDispatchThread());
      retries.incrementAndGet();
      return false;
    }
  }
}
