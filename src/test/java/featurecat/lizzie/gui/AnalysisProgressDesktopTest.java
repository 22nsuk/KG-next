package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Real progress window and graph painting with controlled analysis responses, without a GPU. */
class AnalysisProgressDesktopTest {
  private static final AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

  @Test
  void progressWindowShowsGraphBeforeAnalysisCompletes() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(getClass(), "analysis-progress", List.of(), List.of(), 45);
    assertEquals("PASS", Files.readString(result));
    assertTrue(Files.isRegularFile(result.resolveSibling("progress-ko.png")));
    assertTrue(Files.isRegularFile(result.resolveSibling("progress-graph.png")));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    int exit = 1;
    try {
      Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
      Thread.setDefaultUncaughtExceptionHandler(
          (thread, failure) -> {
            unexpectedFailure.compareAndSet(null, failure);
            if (previous != null) previous.uncaughtException(thread, failure);
            else failure.printStackTrace();
          });
      prepareApplication(work);
      ControlledAnalysisEngine engine =
          onEdt(
              () -> {
                ControlledAnalysisEngine worker = new ControlledAnalysisEngine();
                Lizzie.frame.analysisEngine = worker;
                worker.setKeepAliveAfterCurrentRequest(true);
                assertEquals(17, worker.startRequestMissingMainline(true));
                return worker;
              });
      awaitEdt(() -> engine.waitFrame.isShowing() && progressText(engine).endsWith("0/17"));
      assertEquals(17, engine.requests.size());
      DesktopProbeProcess.phase(result, "progress-window-ready");

      // Feed the same public result entry point used by the engine reader, off the EDT.
      // Leave nine requests outstanding so completion cannot cause the observed graph repaint.
      for (int index = 0; index < 8; index++) engine.complete(index);
      awaitEdt(
          () ->
              engine.waitFrame.isShowing()
                  && progressText(engine).endsWith("8/17")
                  && renderedAnalyzedMoves() == 8);
      onEdt(
          () -> {
            assertTrue(engine.isAnalysisInProgress());
            assertEquals(8, analyzedMoves());
            assertFalse(Lizzie.frame.isEnabled(), "the production progress window owns the frame");
            BufferedImage graph = field(Lizzie.frame, "cachedWinrateImage", BufferedImage.class);
            assertTrue(graph.getWidth() > 100 && graph.getHeight() > 20);
            ImageIO.write(graph, "png", result.resolveSibling("progress-graph.png").toFile());
            return null;
          });
      var bounds = onEdt(() -> Lizzie.frame.getBounds());
      ImageIO.write(
          new Robot().createScreenCapture(bounds),
          "png",
          result.resolveSibling("progress-ko.png").toFile());
      DesktopProbeProcess.phase(result, "eight-results-painted-with-progress-window-visible");

      for (int index = 8; index < 17; index++) engine.complete(index);
      awaitEdt(
          () ->
              !engine.isAnalysisInProgress()
                  && !engine.waitFrame.isShowing()
                  && Lizzie.frame.isEnabled()
                  && renderedAnalyzedMoves() == 17);
      assertNoUnexpectedFailure();
      DesktopProbeProcess.phase(result, "final-result-painted-and-window-closed");
      Files.writeString(result, "PASS");
      exit = 0;
    } catch (Throwable failure) {
      failure.printStackTrace();
    } finally {
      System.exit(exit);
    }
  }

  private static void prepareApplication(Path work) throws Exception {
    JSONObject ui =
        new JSONObject()
            .put("autoload-empty", true)
            .put("first-time-load", false)
            .put("auto-check-engine-alive", false)
            .put("auto-quick-analyze-on-load", false)
            .put("analysis-engine-preload", false)
            .put("analysis-reuse-current-engine", false)
            .put("analysis-engine-command", "progress-test analysis")
            .put("analysis-engine-command-customized", true)
            .put("analysis-auto-quit", false)
            .put("play-sound", false)
            .put("use-language", 3);
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject()
            .put("leelaz", new JSONObject().put("engine-settings-list", new JSONArray()))
            .put("logging", new JSONObject().put("diagnostics-enabled", false))
            .put("ui", ui)
            .toString(2));
    System.setProperty("lizzie.work.dir", work.toString());
    Lizzie.main(new String[0]);
    awaitEdt(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
    onEdt(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof JDialog) window.dispose();
          }
          Lizzie.engineManager.autoCheckEngineAlive(false);
          Lizzie.frame.setBounds(60, 60, 1180, 820);
          Lizzie.config.showWinrateGraph = true;
          Lizzie.config.showWinrateLine = true;
          Lizzie.config.showScoreLeadLine = true;
          StringBuilder sgf = new StringBuilder("(;FF[4]GM[1]SZ[19]KM[7.5]");
          for (int move = 0; move < 17; move++) {
            sgf.append(move % 2 == 0 ? ";B[" : ";W[")
                .append((char) ('a' + move))
                .append("d]");
          }
          assertTrue(SGFParser.loadFromString(sgf.append(')').toString(), false));
          Lizzie.board.goToMoveNumber(0);
          assertEquals(0, analyzedMoves());
          Lizzie.frame.refresh();
          return null;
        });
  }

  private static String progressText(AnalysisEngine engine) {
    return field(engine.waitFrame, "lblAnalsisProgress", JLabel.class).getText();
  }

  private static int analyzedMoves() {
    int analyzed = 0;
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    while ((node = node.next().orElse(null)) != null) {
      if (node.getData().getPlayouts() > 0) analyzed++;
    }
    return analyzed;
  }

  private static long renderedAnalyzedMoves() {
    List<?> points = field(LizzieFrame.winrateGraph, "renderedGraphPoints", List.class);
    return points.stream()
        .map(point -> field(point, "node", BoardHistoryNode.class))
        .filter(node -> node.getData().getPlayouts() > 0)
        .count();
  }

  private static <T> T field(Object owner, String name, Class<T> type) {
    try {
      Field field = owner.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(owner));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(10, TimeUnit.SECONDS);
  }

  private static void awaitEdt(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!onEdt(condition::getAsBoolean)) {
      assertNoUnexpectedFailure();
      if (System.nanoTime() >= deadline) throw new AssertionError("Progress UI did not settle");
      Thread.sleep(20);
    }
  }

  private static void assertNoUnexpectedFailure() {
    Throwable failure = unexpectedFailure.get();
    if (failure != null) throw new AssertionError("Unexpected application failure", failure);
  }

  private static final class ControlledAnalysisEngine extends AnalysisEngine {
    private final List<JSONObject> requests = new ArrayList<>();

    private ControlledAnalysisEngine() throws Exception {
      super(false);
      Field loaded = AnalysisEngine.class.getDeclaredField("isLoaded");
      loaded.setAccessible(true);
      loaded.setBoolean(this, true);
    }

    @Override
    public void startEngine(String command) {
      // Only engine I/O is replaced; request planning, result handling, and Swing remain real.
    }

    @Override
    public boolean sendCommand(String command) {
      requests.add(new JSONObject(command));
      return true;
    }

    private void complete(int index) {
      JSONObject move =
          new JSONObject()
              .put("move", "D4")
              .put("visits", 200)
              .put("winrate", 0.4 + (index % 5) * 0.1)
              .put("lcb", 0.35)
              .put("prior", 0.25)
              .put("scoreLead", -2.0 + index * 0.4)
              .put("scoreStdev", 0.2)
              .put("order", 0)
              .put("pv", new JSONArray().put("D4"));
      parseResult(
          new JSONObject()
              .put("id", requests.get(index).getString("id"))
              .put("moveInfos", new JSONArray().put(move))
              .toString());
    }
  }
}
