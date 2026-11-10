package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the production windows and their owner's close, reset and mode-switch paths. */
class AnalysisWindowLifecycleDesktopTest {
  @ParameterizedTest
  @ValueSource(strings = {"analysis", "moves"})
  void closedWindowsReleaseTimersAndReopenCleanly(String family) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            getClass(), "window-lifecycle-" + family, List.of(), List.of(family));
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) {
    try {
      runProbe(args[0].equals("analysis"), Path.of(args[1]), Path.of(args[2]));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void runProbe(boolean analysis, Path work, Path result) throws Exception {
    System.setProperty("lizzie.work.dir", work.toString());
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":3,"
            + "\"auto-check-engine-alive\":false,\"auto-quick-analyze-on-load\":false,"
            + "\"analysis-engine-preload\":false,\"play-sound\":false,"
            + "\"show-suggestions-frame\":false,\"show-badmoves-frame\":false}}");
    DesktopProbeProcess.phase(result, "application-starting");
    Lizzie.main(new String[0]);
    Leelaz offlineDescriptor = new Leelaz("");
    edt(
        () -> {
          for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
          Lizzie.engineManager.autoCheckEngineAlive(false);
          Lizzie.setPrimaryEngine(offlineDescriptor);
          EngineManager.isEmpty = true;
          Lizzie.frame.setBounds(30, 30, 1180, 820);
          return null;
        });
    DesktopProbeProcess.applicationReady(result);

    for (boolean kata : new boolean[] {false, true}) {
      edt(
          () -> {
            offlineDescriptor.isKatago = kata;
            return null;
          });
      DesktopProbeProcess.phase(result, "toggle-reopen-and-settings");
      JFrame original = edt(() -> open(analysis));
      String positionKey =
          edt(
              () -> analysis
                  ? "suggestions-list-position-" + table(original).getColumnCount()
                  : "badmoves-list-position");
      AtomicInteger retiredTicks = new AtomicInteger();
      edt(
          () -> {
            timer(original).addActionListener(event -> retiredTicks.incrementAndGet());
            return null;
          });
      awaitTick(original);
      Rectangle bounds = new Rectangle(140, 110, 740, 380);
      edt(
          () -> {
            original.setBounds(bounds);
            return null;
          });
      awaitBounds(original, bounds);
      edt(
          () -> {
            table(original).setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table(original).getColumnModel().getColumn(0).setWidth(121);
            if (!analysis) ((MoveListFrame) original).sortnum = 4;
            toggle(analysis);
            assertClosed(original);
            assertNoReferences(analysis);
            assertFalse(Lizzie.config.uiConfig.getBoolean(setting(analysis)));
            return null;
          });
      int stoppedTicks = retiredTicks.get();
      JSONObject persisted =
          new JSONObject(Files.readString(Path.of(Lizzie.config.getPersistFilePath())));
      JSONArray position = persisted.getJSONObject("ui-persist").getJSONArray(positionKey);
      int offset = analysis ? 0 : 1;
      assertEquals(bounds.x, position.getInt(offset));
      assertEquals(bounds.y, position.getInt(offset + 1));
      assertEquals(bounds.width, position.getInt(offset + 2));
      assertEquals(bounds.height, position.getInt(offset + 3));
      assertEquals(121, position.getInt(offset + 4));
      if (!analysis) assertEquals(4, position.getInt(0));

      JFrame reopened = edt(() -> open(analysis));
      awaitBounds(reopened, bounds);
      edt(
          () -> {
            assertNotSame(original, reopened);
            assertEquals(bounds, reopened.getBounds());
            assertEquals(121, table(reopened).getColumnModel().getColumn(0).getPreferredWidth());
            // A late native event from the disposed frame cannot act on the current pair.
            closeEvent(original);
            closeEvent(original);
            assertSame(reopened, primary(analysis));
            assertOpen(reopened);
            assertTrue(Lizzie.config.uiConfig.getBoolean(setting(analysis)));
            return null;
          });
      awaitTick(reopened);
      assertEquals(stoppedTicks, retiredTicks.get(), "retired timer delivered another callback");
      capture(result.resolveSibling(kata ? "reopened-kata.png" : "reopened-standard.png"));
      edt(
          () -> {
            toggle(analysis);
            return null;
          });
    }

    DesktopProbeProcess.phase(result, "paired-close-and-mode-exit");
    edt(
        () -> {
          mode(ExtraMode.Double_Engine);
          for (boolean closeSecondary : new boolean[] {false, true}) {
            JFrame main = open(analysis);
            JFrame sub = secondary(analysis);
            assertOpen(sub);
            closeEvent(closeSecondary ? sub : main);
            assertClosed(main);
            assertClosed(sub);
            assertNoReferences(analysis);
          }
          JFrame main = open(analysis);
          JFrame sub = secondary(analysis);
          mode(ExtraMode.Normal);
          assertSame(main, primary(analysis));
          assertOpen(main);
          assertClosed(sub);
          assertNull(secondary(analysis));
          closeEvent(sub);
          assertSame(main, primary(analysis));
          assertOpen(main);
          toggle(analysis);
          return null;
        });

    DesktopProbeProcess.phase(result, "reset-and-cancelled-reset");
    edt(
        () -> {
          Lizzie.config.uiConfig.put(setting(analysis), true);
          assertTrue(Lizzie.frame.resetMovelistFrameandAnalysisFrame());
          return null;
        });
    JFrame beforeReset = edt(() -> primary(analysis));
    assertNotNull(beforeReset, "startup preference did not restore the window");
    edt(
        () -> {
          assertTrue(Lizzie.frame.resetMovelistFrameandAnalysisFrame());
          return null;
        });
    JFrame replacement =
        edt(
            () -> {
              assertClosed(beforeReset);
              assertNotSame(beforeReset, primary(analysis));
              assertOpen(primary(analysis));
              assertNoReferences(!analysis);
              assertTrue(Lizzie.frame.resetMovelistFrameandAnalysisFrame());
              toggle(analysis);
              return open(analysis);
            });
    edt(
        () -> {
          assertSame(
              replacement, primary(analysis), "an older reset replaced the newly opened window");
          assertOpen(replacement);
          assertTrue(Lizzie.frame.resetMovelistFrameandAnalysisFrame());
          toggle(analysis);
          return null;
        });
    // This barrier follows the reset queued before the close; it must not reopen the window.
    edt(
        () -> {
          assertNoReferences(analysis);
          return null;
        });
    JFrame bothAnalysis = edt(() -> open(true));
    JFrame bothMoves = edt(() -> open(false));
    edt(
        () -> {
          assertTrue(Lizzie.frame.resetMovelistFrameandAnalysisFrame());
          return null;
        });
    edt(
        () -> {
          assertClosed(bothAnalysis);
          assertClosed(bothMoves);
          assertOpen(primary(true));
          assertOpen(primary(false));
          toggle(true);
          toggle(false);
          return null;
        });

    DesktopProbeProcess.phase(result, "direct-dispose-and-persist-failure");
    edt(
        () -> {
          JFrame frame = open(analysis);
          frame.dispose();
          frame.dispose();
          assertClosed(frame);
          JFrame next = open(analysis);
          assertNotSame(frame, next);
          closeEvent(frame);
          assertSame(next, primary(analysis));
          return null;
        });
    Path persist = Path.of(Lizzie.config.getPersistFilePath());
    Path backup = work.resolve("persist-backup");
    Files.move(persist, backup);
    Files.createDirectory(persist);
    Path blocker = Files.writeString(persist.resolve("blocker"), "owned fixture");
    try {
      edt(
          () -> {
            assertThrows(IOException.class, () -> Lizzie.config.persist());
            JFrame frame = primary(analysis);
            toggle(analysis);
            assertClosed(frame);
            assertNoReferences(analysis);
            return null;
          });
      assertEquals("owned fixture", Files.readString(blocker));
    } finally {
      Files.delete(blocker);
      Files.delete(persist);
      Files.move(backup, persist);
    }

    DesktopProbeProcess.phase(result, "owner-dispose");
    edt(
        () -> {
          mode(ExtraMode.Double_Engine);
          JFrame mainAnalysis = open(true);
          JFrame subAnalysis = secondary(true);
          JFrame mainMoves = open(false);
          JFrame subMoves = secondary(false);
          assertTrue(Lizzie.frame.resetMovelistFrameandAnalysisFrame());
          Lizzie.frame.dispose();
          for (JFrame frame : List.of(mainAnalysis, subAnalysis, mainMoves, subMoves)) {
            assertClosed(frame);
          }
          assertNoReferences(true);
          assertNoReferences(false);
          assertTrue(Lizzie.config.uiConfig.getBoolean(setting(true)));
          assertTrue(Lizzie.config.uiConfig.getBoolean(setting(false)));
          return null;
        });
    edt(
        () -> {
          assertNoReferences(true);
          assertNoReferences(false);
          for (Window window : Window.getWindows()) {
            if (window instanceof AnalysisFrame || window instanceof MoveListFrame) {
              assertClosed((JFrame) window);
            }
          }
          return null;
        });
    Files.writeString(result, "PASS");
  }

  private static JFrame open(boolean analysis) {
    toggle(analysis);
    JFrame frame = primary(analysis);
    assertOpen(frame);
    return frame;
  }

  private static void toggle(boolean analysis) {
    if (analysis) Lizzie.frame.toggleBestMoves();
    else Lizzie.frame.toggleBadMoves();
  }

  private static JFrame primary(boolean analysis) {
    return analysis ? Lizzie.frame.analysisFrame : Lizzie.frame.moveListFrame;
  }

  private static JFrame secondary(boolean analysis) {
    return analysis ? Lizzie.frame.analysisFrame2 : Lizzie.frame.moveListFrame2;
  }

  private static Timer timer(JFrame frame) {
    return frame instanceof AnalysisFrame analysis ? analysis.timer : ((MoveListFrame) frame).timer;
  }

  private static JTable table(JFrame frame) {
    return frame instanceof AnalysisFrame analysis ? analysis.table : ((MoveListFrame) frame).table;
  }

  private static String setting(boolean analysis) {
    return analysis ? "show-suggestions-frame" : "show-badmoves-frame";
  }

  private static void closeEvent(JFrame frame) {
    frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
  }

  private static void assertOpen(JFrame frame) {
    assertNotNull(frame);
    assertTrue(frame.isShowing(), "current window is not showing");
    assertTrue(timer(frame).isRunning(), "current window's refresh timer is stopped");
  }

  private static void assertClosed(JFrame frame) {
    assertFalse(timer(frame).isRunning(), "closed window's refresh timer is still running");
    assertFalse(frame.isDisplayable(), "closed window still owns a native peer");
  }

  private static void assertNoReferences(boolean analysis) {
    assertNull(primary(analysis));
    assertNull(secondary(analysis));
  }

  private static void mode(ExtraMode next) {
    ExtraMode previous = Lizzie.config.extraMode;
    Lizzie.config.extraMode = next;
    Lizzie.frame.extraMode(next, previous);
  }

  private static void awaitBounds(JFrame frame, Rectangle expected) throws Exception {
    // X11 can deliver intermediate configure events after setBounds/setVisible return.
    // Drain native events, then require the exact requested/restored rectangle before closing.
    new Robot().waitForIdle();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    Rectangle actual;
    do {
      actual = edt(frame::getBounds);
      if (expected.equals(actual)) return;
      Thread.sleep(20);
    } while (System.nanoTime() < deadline);
    assertEquals(expected, actual, "native window did not reach the saved bounds");
  }

  private static void awaitTick(JFrame frame) throws Exception {
    CountDownLatch tick = new CountDownLatch(1);
    java.awt.event.ActionListener listener = event -> tick.countDown();
    edt(
        () -> {
          timer(frame).addActionListener(listener);
          return null;
        });
    assertTrue(tick.await(5, TimeUnit.SECONDS), "visible window did not refresh");
    edt(
        () -> {
          timer(frame).removeActionListener(listener);
          return null;
        });
  }

  private static void capture(Path path) throws Exception {
    Rectangle screen = edt(() -> Lizzie.frame.getGraphicsConfiguration().getBounds());
    ImageIO.write(new Robot().createScreenCapture(screen), "png", path.toFile());
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(15, TimeUnit.SECONDS);
  }
}
