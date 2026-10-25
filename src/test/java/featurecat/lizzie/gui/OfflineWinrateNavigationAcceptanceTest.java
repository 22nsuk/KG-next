package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.util.Utils;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Actual startup placeholder, file import, graph painting, and production click/drag routing. */
public final class OfflineWinrateNavigationAcceptanceTest {
  @Test
  void opensAndNavigatesSgfBeforeAnyEngineHasStarted() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            OfflineWinrateNavigationAcceptanceTest.class,
            "offline-winrate-navigation",
            List.of(),
            List.of());
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    int exit = 1;
    ImportExecutor imports = new ImportExecutor();
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,"
              + "\"first-time-load\":false,\"play-sound\":false}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(Lizzie.frame.isDisplayable());
            assertTrue(EngineManager.isEmpty);
            assertNotNull(Lizzie.leelaz, "Keep the real startup placeholder for this regression");
            assertFalse(Lizzie.leelaz.isStarted());
            assertNull(Lizzie.leelaz.engineIncarnationToken());
            Lizzie.config.showWinrateGraph = true;
            Lizzie.frame.shutdownKifuEngineSyncCoordinator();
          });
      Field coordinator = LizzieFrame.class.getDeclaredField("kifuEngineSyncCoordinator");
      coordinator.setAccessible(true);
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              coordinator.set(Lizzie.frame, new KifuEngineSyncCoordinator(imports));
            } catch (IllegalAccessException failure) {
              throw new AssertionError(failure);
            }
          });
      Leelaz placeholder = Lizzie.leelaz;
      for (boolean analyzed : new boolean[] {true, false}) {
        Path sgf = work.resolve(analyzed ? "분석포함.sgf" : "일반기보.sgf");
        Files.writeString(sgf, game(analyzed));
        DesktopProbeProcess.phase(result, "import-" + sgf.getFileName());
        SwingUtilities.invokeAndWait(
            () -> assertTrue(Lizzie.frame.loadFile(sgf.toFile(), false, false)));
        // Drain the parser/frame adoption, then run only the captured import owner. Never
        // replace the startup placeholder or force the navigation gate open in this probe.
        SwingUtilities.invokeAndWait(() -> {});
        imports.runPending();
        SwingUtilities.invokeAndWait(() -> {});
        joinPostLoadContinuation();
        SwingUtilities.invokeAndWait(
            () -> {
              assertEquals(8, Lizzie.board.getHistory().mainTrunkLength());
              clickMove(3);
              clickMove(7);
              clickMove(1);
              Lizzie.frame.input.mousePressed(graphEvent(1, MouseEvent.MOUSE_PRESSED));
              Lizzie.frame.input.mouseDragged(graphEvent(6, MouseEvent.MOUSE_DRAGGED));
            });
        // Graph drags coalesce on the EDT. Verify the production deferred navigation too.
        SwingUtilities.invokeAndWait(
            () -> {
              assertMove(6, "drag");
              Lizzie.frame.input.mouseDragged(graphEvent(2, MouseEvent.MOUSE_DRAGGED));
            });
        SwingUtilities.invokeAndWait(
            () -> {
              assertMove(2, "drag");
              Lizzie.frame.input.mouseReleased(graphEvent(2, MouseEvent.MOUSE_RELEASED));
              assertSame(placeholder, Lizzie.leelaz);
              assertFalse(placeholder.isStarted());
              assertFalse(placeholder.isPondering());
              assertNull(placeholder.engineIncarnationToken());
              assertTrue(LizzieFrame.canGoAfterload);
              assertTrue(Lizzie.board.isEngineAlignmentPending());
            });
        DesktopProbeProcess.phase(result, "clicked-and-dragged-" + sgf.getFileName());
      }
      Files.writeString(result, "PASS");
      exit = 0;
    } catch (Throwable failure) {
      org.slf4j.LoggerFactory.getLogger(OfflineWinrateNavigationAcceptanceTest.class)
          .error("Offline winrate navigation acceptance failed", failure);
      Files.writeString(result, failure.toString());
    } finally {
      imports.shutdownNow();
      System.exit(exit);
    }
  }

  private static String game(boolean analyzed) {
    StringBuilder sgf = new StringBuilder("(;GM[1]FF[4]SZ[19]KM[6.5]RU[Japanese]");
    String[] moves = {"B[dd]", "W[pp]", "B[dp]", "W[pd]", "B[cf]", "W[qf]", "B[fc]", "W[qc]"};
    for (int index = 0; index < moves.length; index++) {
      sgf.append(';').append(moves[index]);
      if (analyzed) sgf.append("LZ[KataGo ").append(45 + index).append(" 123]");
    }
    return sgf.append(')').toString();
  }

  private static void clickMove(int move) {
    Lizzie.frame.input.mousePressed(graphEvent(move, MouseEvent.MOUSE_PRESSED));
    assertMove(move, "click");
    Lizzie.frame.input.mouseReleased(graphEvent(move, MouseEvent.MOUSE_RELEASED));
  }

  private static void assertMove(int move, String input) {
    assertEquals(
        move,
        Lizzie.board.getHistory().getMoveNumber(),
        "Graph "
            + input
            + " must navigate before engine startup; canGoAfterload="
            + LizzieFrame.canGoAfterload);
  }

  private static MouseEvent graphEvent(int move, int id) {
    LizzieFrame frame = Lizzie.frame;
    BufferedImage image =
        new BufferedImage(
            frame.mainPanel.getWidth(), frame.mainPanel.getHeight(), BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      frame.mainPanel.paint(graphics);
    } finally {
      graphics.dispose();
    }
    assertTrue(frame.grw > 0 && frame.grh > 0, "The production window must lay out the graph");
    int localY = frame.grh / 2;
    int firstX = -1;
    int lastX = -1;
    for (int x = 0; x < frame.grw; x++) {
      if (LizzieFrame.winrateGraph.moveNumber(x, localY) == move) {
        if (firstX < 0) firstX = x;
        lastX = x;
      }
    }
    assertTrue(firstX >= 0, "The rendered graph must expose move " + move);
    int localX = (firstX + lastX) / 2;
    return new MouseEvent(
        frame.mainPanel,
        id,
        0,
        id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.BUTTON1_DOWN_MASK : 0,
        Utils.zoomIn(frame.grx + localX),
        Utils.zoomIn(frame.gry + localY),
        id == MouseEvent.MOUSE_DRAGGED ? 0 : 1,
        false,
        id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1);
  }

  private static void joinPostLoadContinuation() throws Exception {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().equals("lizzie-post-load-analysis")) {
        thread.join(5_000);
        assertFalse(thread.isAlive(), "Post-load continuation did not finish");
      }
    }
    SwingUtilities.invokeAndWait(() -> {});
  }

  private static final class ImportExecutor extends ScheduledThreadPoolExecutor {
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

    ImportExecutor() {
      super(1);
    }

    @Override
    public synchronized void execute(Runnable command) {
      pending.add(command);
    }

    void runPending() {
      Runnable next;
      synchronized (this) {
        next = pending.poll();
      }
      assertNotNull(next, "The real import must submit its synchronization request");
      next.run();
    }
  }
}
