package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.EngineGameOpeningParameters;
import featurecat.lizzie.enginegame.EngineGamePlan;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** The real opening dialog validates before mutation and hands its persisted values to the plan. */
class NewEngineGameParametersDesktopTest {
  @Test
  void openingDialogRejectsInvalidDraftsThenPersistsAndCapturesPositions() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(getClass(), "engine-game-opening", List.of(), List.of());
    JSONObject evidence = new JSONObject(Files.readString(result));
    assertEquals("PASS", evidence.getString("result"));
    assertEquals(2, evidence.getInt("rejectedDrafts"));
    assertTrue(Files.isRegularFile(result.resolveSibling("opening-parameters-ko.png")));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    try {
      JSONArray entries = new JSONArray();
      for (int index = 0; index < 2; index++) {
        entries.put(
            new JSONObject()
                .put("name", "검사 엔진 " + (index + 1))
                .put("command", "opening-dialog-engine-" + index)
                .put("preload", false)
                .put("isDefault", false)
                .put("width", 19)
                .put("height", 19)
                .put("komi", 7.5)
                .put("useJavaSSH", false));
      }
      Files.writeString(
          work.resolve("config.txt"),
          new JSONObject()
              .put("leelaz", new JSONObject().put("engine-settings-list", entries))
              .put("logging", new JSONObject().put("diagnostics-enabled", false))
              .put(
                  "ui",
                  new JSONObject()
                      .put("autoload-empty", true)
                      .put("autoload-default", false)
                      .put("autoload-last", false)
                      .put("first-time-load", false)
                      .put("auto-check-engine-alive", false)
                      .put("play-sound", false)
                      .put("use-language", 3))
              .toString(2));
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      awaitEdt(() -> Lizzie.frame != null && Lizzie.frame.isShowing() && Lizzie.engineManager != null);
      RecordingManager manager =
          onEdt(
              () -> {
                for (Window window : Window.getWindows()) {
                  if (window instanceof JDialog) window.dispose();
                }
                Lizzie.engineManager.autoCheckEngineAlive(false);
                Leelaz first = new Leelaz("opening-dialog-engine-0");
                Leelaz second = new Leelaz("opening-dialog-engine-1");
                first.currentEnginename = first.oriEnginename = "검사 엔진 1";
                second.currentEnginename = second.oriEnginename = "검사 엔진 2";
                RecordingManager recording = new RecordingManager(List.of(first, second));
                Lizzie.setPrimaryEngine(null);
                Lizzie.setEngineManager(recording);
                Lizzie.engineGame.resetForTest();
                Lizzie.engineGame.replaceChromeForTest(transition -> {});
                LizzieFrame.toolbar.isGenmoveToolbar = false;
                LizzieFrame.toolbar.engineBlackToolbar = 0;
                LizzieFrame.toolbar.engineWhiteToolbar = 1;
                return recording;
              });
      NewEngineGameDialog dialog =
          onEdt(
              () -> {
                NewEngineGameDialog created = new NewEngineGameDialog(Lizzie.frame);
                created.setGameInfo(new GameInfo());
                assertEquals("0", field(created, "txtOpeningWrnFirst", JTextField.class).getText());
                assertEquals("0", field(created, "txtOpeningPdaSecond", JTextField.class).getText());
                return created;
              });
      SwingUtilities.invokeLater(() -> dialog.setVisible(true));
      awaitEdt(dialog::isShowing);
      JSONObject before = onEdt(() -> new JSONObject(Lizzie.config.uiConfig.toString()));
      GameInfo beforeGame = onEdt(() -> Lizzie.board.getHistory().getGameInfo());
      double beforeKomi = beforeGame.getKomi();
      onEdt(
          () -> {
            field(dialog, "textFieldKomi", JTextField.class).setText("9.5");
            field(dialog, "txtOpeningWrnFirst", JTextField.class).setText("NaN");
            return null;
          });
      reject(dialog, "EngineGameParameters.invalidWrn");
      onEdt(
          () -> {
            assertTrue(before.similar(Lizzie.config.uiConfig));
            assertSame(beforeGame, Lizzie.board.getHistory().getGameInfo());
            assertEquals(beforeKomi, beforeGame.getKomi());
            assertNull(manager.captured);
            field(dialog, "txtOpeningWrnFirst", JTextField.class).setText("0.2");
            field(dialog, "txtOpeningWrnSecond", JTextField.class).setText("0.4");
            field(dialog, "txtOpeningPdaFirst", JTextField.class).setText("3.1");
            return null;
          });
      reject(dialog, "EngineGameParameters.invalidPda");
      EngineGameOpeningParameters expected = new EngineGameOpeningParameters(0.2, 0.4, 0.5, -0.5);
      onEdt(
          () -> {
            assertTrue(before.similar(Lizzie.config.uiConfig));
            assertSame(beforeGame, Lizzie.board.getHistory().getGameInfo());
            assertEquals(beforeKomi, beforeGame.getKomi());
            assertNull(manager.captured);
            field(dialog, "txtOpeningPdaFirst", JTextField.class).setText("0.5");
            field(dialog, "txtOpeningPdaSecond", JTextField.class).setText("-0.5");
            // These are position preferences, so selecting different engines keeps the drafts.
            dialog.enginePkBlack.setSelectedIndex(1);
            dialog.enginePkWhite.setSelectedIndex(0);
            assertEquals("0.2", field(dialog, "txtOpeningWrnFirst", JTextField.class).getText());
            assertEquals("-0.5", field(dialog, "txtOpeningPdaSecond", JTextField.class).getText());
            return null;
          });
      capture(dialog, result.resolveSibling("opening-parameters-ko.png"));
      onEdt(
          () -> {
            field(dialog, "okButton", JButton.class).doClick(0);
            assertNotNull(manager.captured);
            assertEquals(expected, manager.captured.openingParameters());
            assertEquals(expected, EngineGameOpeningParameters.stored(Lizzie.config));
            assertEquals(1, manager.captured.firstIndex());
            assertEquals(0, manager.captured.secondIndex());
            assertEquals(9.5, Lizzie.board.getHistory().getGameInfo().getKomi());
            Lizzie.config.save();
            dialog.dispose();
            Lizzie.frame.releaseEngineGameDialog();
            return null;
          });
      JSONObject saved = new JSONObject(Files.readString(work.resolve("config.txt"))).getJSONObject("ui");
      assertEquals(0.2, saved.getDouble("engine-game-first-wrn"));
      assertEquals(0.4, saved.getDouble("engine-game-second-wrn"));
      assertEquals(0.5, saved.getDouble("engine-game-first-pda"));
      assertEquals(-0.5, saved.getDouble("engine-game-second-pda"));
      Files.writeString(result, new JSONObject().put("result", "PASS").put("rejectedDrafts", 2).toString(2));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void reject(NewEngineGameDialog dialog, String key) throws Exception {
    FutureTask<Void> submitted =
        new FutureTask<>(() -> { field(dialog, "okButton", JButton.class).doClick(0); return null; });
    SwingUtilities.invokeLater(submitted);
    awaitEdt(() -> message() != null);
    onEdt(
        () -> {
          Message message = message();
          assertEquals(Lizzie.resourceBundle.getString(key), message.lblmessage.getText());
          message.dispose();
          return null;
        });
    submitted.get(10, TimeUnit.SECONDS);
    assertTrue(onEdt(dialog::isShowing));
  }

  private static Message message() {
    for (Window window : Window.getWindows()) {
      if (window instanceof Message message && message.isShowing()) return message;
    }
    return null;
  }

  private static <T> T field(NewEngineGameDialog dialog, String name, Class<T> type) throws Exception {
    Field field = NewEngineGameDialog.class.getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(dialog));
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(10, TimeUnit.SECONDS);
  }

  private static void awaitEdt(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!onEdt(condition::getAsBoolean)) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Opening dialog condition timed out");
      Thread.sleep(25);
    }
  }

  private static void capture(NewEngineGameDialog dialog, Path destination) throws Exception {
    Robot robot = new Robot();
    robot.waitForIdle();
    Rectangle bounds =
        onEdt(
            () -> {
              dialog.getRootPane().paintImmediately(0, 0, dialog.getWidth(), dialog.getHeight());
              Toolkit.getDefaultToolkit().sync();
              return dialog.getBounds();
            });
    assertTrue(ImageIO.write(robot.createScreenCapture(bounds), "png", destination.toFile()));
  }

  private static final class RecordingManager extends EngineManager {
    EngineGamePlan captured;

    RecordingManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    public boolean startEngineGame(EngineGamePlan plan) {
      assertTrue(SwingUtilities.isEventDispatchThread());
      captured = plan;
      return true;
    }
  }
}
