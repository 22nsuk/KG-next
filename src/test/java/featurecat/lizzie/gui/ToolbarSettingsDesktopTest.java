package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the real menu, both toolbar layouts, search navigation and settings save path. */
class ToolbarSettingsDesktopTest {
  @ParameterizedTest
  @ValueSource(strings = {"ko", "ja", "en"})
  void toolbarSettingsStayReachableAndPreserveAnalysis(String language) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.runWithStartupTimeout(
            getClass(),
            "toolbar-settings-" + language,
            List.of(
                "-Dsun.java2d.uiScale=" + (language.equals("ko") ? "1.5" : "1"),
                "-Dlizzie.menu.presentation=" + (language.equals("ja") ? "native" : "custom")),
            List.of(language),
            120,
            90);
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) {
    try {
      runProbe(args[0], Path.of(args[1]), Path.of(args[2]));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void runProbe(String language, Path work, Path result) throws Exception {
    int locale =
        switch (language) {
          case "ko" -> AppLocale.KOREAN.configValue();
          case "ja" -> AppLocale.JAPANESE.configValue();
          default -> AppLocale.ENGLISH.configValue();
        };
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":"
            + locale
            + ",\"frame-font-size\":"
            + (language.equals("en") ? 16 : 12)
            + ",\"auto-check-engine-alive\":false,\"auto-quick-analyze-on-load\":false,"
            + "\"analysis-engine-preload\":false,\"play-sound\":false}}");
    System.setProperty("lizzie.work.dir", work.toString());
    DesktopProbeProcess.phase(result, "application-starting");
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
    RecordingEngine engine = new RecordingEngine();
    edt(
        () -> {
          for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
          Lizzie.engineManager.autoCheckEngineAlive(false);
          Rectangle screen = Lizzie.frame.getGraphicsConfiguration().getBounds();
          Lizzie.frame.setBounds(
              screen.x + 20,
              screen.y + 20,
              Math.min(1180, screen.width - 40),
              Math.min(820, screen.height - 60));
          Lizzie.setPrimaryEngine(engine);
          EngineManager.isEmpty = false;
          Lizzie.config.showPDAInMenu = true;
          Lizzie.config.showWRNInMenu = true;
          Lizzie.config.showTimeControlInMenu = false;
          Lizzie.config.showPlayoutControlInMenu = false;
          LizzieFrame.menu.setPdaAndWrn(-0.5, 0.5);
          Lizzie.board.getHistory().getData().setPlayouts(1234);
          return null;
        });
    DesktopProbeProcess.applicationReady(result);
    Menu menu = LizzieFrame.menu;
    JTextField candidates =
        edt(() -> (JTextField) menu.topFunctionTarget("toolbar.suggestion-limit"));
    Component timeLimit = edt(() -> toolbarComponent(menu, "txtTimeLimit"));
    Component visitLimit = edt(() -> toolbarComponent(menu, "txtPlayOutsLimit"));
    assertFalse(edt(candidates::isShowing));
    List<String> beforeCommands = edt(() -> List.copyOf(engine.commands));

    for (boolean separated : new boolean[] {true, false}) {
      DesktopProbeProcess.phase(result, separated ? "separated-toolbar" : "combined-toolbar");
      selectLayout(separated);
      await(() -> menu.txtWRN.isShowing() && menu.txtGfPDA.isShowing());
      checkVisibility("menu.showWrnInToolbar", menu.txtWRN, menu.txtGfPDA);
      checkVisibility("menu.showPdaInToolbar", menu.txtGfPDA, menu.txtWRN);
      edt(
          () -> {
            item("menu.showSuggestionLimit").doClick(0);
            return null;
          });
      await(candidates::isShowing);
      edt(
          () -> {
            candidates.setText("3");
            assertEquals(3, Lizzie.config.limitMaxSuggestion);
            candidates.setText("");
            assertEquals(3, Lizzie.config.limitMaxSuggestion);
            candidates.setText("0");
            assertEquals(0, Lizzie.config.limitMaxSuggestion);
            candidates.setText("5");
            assertEquals(5, Lizzie.config.leelazConfig.getInt("limit-max-suggestion"));
            assertEquals(1234, Lizzie.board.getHistory().getData().getPlayouts());
            assertEquals(
                beforeCommands, engine.commands, "display edits must not send engine commands");
            JLabel wrn = separated ? menu.lblWRNForDouble : menu.lblWRN;
            assertTrue(
                wrn.getWidth() >= wrn.getFontMetrics(wrn.getFont()).stringWidth(wrn.getText()),
                "translated WRN label is clipped: " + wrn.getBounds());
            assertEquals(
                Lizzie.resourceBundle.getString("Menu.lblSuggestionLimit"),
                candidates.getAccessibleContext().getAccessibleName());
            assertInsideHeader(candidates, "candidate editor");
            return null;
          });
      capture(
          result.resolveSibling(
              "toolbar-" + language + (separated ? "-separated.png" : "-combined.png")));
      if (!separated) {
        edt(
            () -> {
              Field field = Menu.class.getDeclaredField("doubleMenuNewGame");
              field.setAccessible(true);
              JButton newGame = (JButton) field.get(menu);
              assertTrue(newGame.isShowing());
              newGame.doClick(0);
              assertTrue(MenuSelectionManager.defaultManager().getSelectedPath().length > 0);
              MenuSelectionManager.defaultManager().clearSelectedPath();
              assertSettingsInsideHeader(menu);
              return null;
            });
        Rectangle normalBounds = edt(() -> Lizzie.frame.getBounds());
        edt(
            () -> {
              Lizzie.frame.setSize(Math.min(900, normalBounds.width), normalBounds.height);
              return null;
            });
        await(
            () ->
                menu.toolPanel.getWidth() > 0
                    && menu.toolPanel.getParent().getWidth() <= Lizzie.frame.getWidth()
                    && menu.toolPanel.getWidth() <= Lizzie.frame.getWidth());
        new Robot().waitForIdle();
        edt(
            () -> {
              assertInsideHeader(candidates, "candidate editor after resize");
              assertSettingsInsideHeader(menu);
              return null;
            });
        edt(
            () -> {
              Lizzie.config.showTimeControlInMenu = true;
              Lizzie.config.showPlayoutControlInMenu = true;
              menu.updateSingleMenu();
              return null;
            });
        await(() -> timeLimit.isShowing() && visitLimit.isShowing());
        new Robot().waitForIdle();
        edt(
            () -> {
              assertInsideHeader(candidates, "narrow candidate editor");
              assertInsideHeader(timeLimit, "time limit");
              assertInsideHeader(visitLimit, "visit limit");
              assertSettingsInsideHeader(menu);
              return null;
            });
        capture(result.resolveSibling("toolbar-" + language + "-narrow.png"));
        edt(
            () -> {
              Lizzie.frame.setBounds(normalBounds);
              Lizzie.config.showTimeControlInMenu = false;
              Lizzie.config.showPlayoutControlInMenu = false;
              menu.updateSingleMenu();
              return null;
            });
        await(() -> !timeLimit.isShowing() && !visitLimit.isShowing());
      }
      edt(
          () -> {
            item("menu.showSuggestionLimit").doClick(0);
            return null;
          });
      await(() -> !candidates.isShowing());
    }

    FunctionSearchController search = new FunctionSearchController(Lizzie.frame);
    DesktopProbeProcess.phase(result, "function-search");
    String preferences = edt(() -> Lizzie.config.uiConfig.toString());
    edt(
        () -> {
          assertNull(search.activate("menu.showWrnInToolbar"));
          assertTrue(item("menu.showWrnInToolbar").isSelected());
          assertTrue(Lizzie.config.showWRNInMenu, "search must locate, not toggle");
          return null;
        });
    capture(result.resolveSibling("toolbar-" + language + "-menu.png"));
    edt(
        () -> {
          MenuSelectionManager.defaultManager().clearSelectedPath();
          assertNull(search.activate("toolbar.suggestion-limit"));
          assertEquals(5, Lizzie.config.limitMaxSuggestion);
          assertEquals(
              preferences, Lizzie.config.uiConfig.toString(), "search must not persist visibility");
          return null;
        });
    await(candidates::isShowing);
    edt(
        () -> {
          // The search-revealed field can be explicitly hidden and shown to save the user's choice.
          item("menu.showSuggestionLimit").doClick(0);
          item("menu.showSuggestionLimit").doClick(0);
          return null;
        });
    await(candidates::isShowing);

    ConfigDialog2 settings =
        edt(
            () -> {
              ConfigDialog2 dialog = new ConfigDialog2();
              dialog.setModal(false);
              dialog.setVisible(true);
              assertTrue(dialog.locateSetting("config.analysis.suggestion-limit"));
              return dialog;
            });
    DesktopProbeProcess.phase(result, "settings-save");
    edt(
        () -> {
          Field field = ConfigDialog2.class.getDeclaredField("txtLimitBestMoveNum");
          field.setAccessible(true);
          ((JTextField) field.get(settings)).setText("8");
          settings.getRootPane().getDefaultButton().doClick(0);
          return null;
        });
    await(() -> !settings.isShowing() && candidates.getText().equals("8"));
    JSONObject saved = new JSONObject(Files.readString(work.resolve("config.txt")));
    assertEquals(8, saved.getJSONObject("leelaz").getInt("limit-max-suggestion"));
    assertTrue(saved.getJSONObject("ui").getBoolean(SuggestionLimitField.SHOWN_KEY));
    assertTrue(saved.getJSONObject("ui").getBoolean("show-pda-in-menu"));
    assertTrue(saved.getJSONObject("ui").getBoolean("show-wrn-in-menu"));
    Files.writeString(result, "PASS");
  }

  private static void selectLayout(boolean separated) throws Exception {
    edt(
        () -> {
          List<JMenuItem> path = LizzieFrame.menu.refreshFunctionPath("menu.topToolBar");
          JMenu menu = (JMenu) path.get(path.size() - 1);
          String label =
              Lizzie.resourceBundle.getString(
                  separated ? "Menu.topToolBarSeparated" : "Menu.topToolBarCombined");
          for (var component : menu.getMenuComponents()) {
            if (component instanceof JMenuItem option && option.getText().equals(label)) {
              option.doClick(0);
              return null;
            }
          }
          throw new AssertionError("missing layout menu item");
        });
    await(() -> Lizzie.config.showDoubleMenu == separated);
  }

  private static void checkVisibility(String id, JTextField target, JTextField other)
      throws Exception {
    edt(
        () -> {
          item(id).doClick(0);
          return null;
        });
    await(() -> !target.isShowing());
    assertTrue(edt(other::isShowing));
    edt(
        () -> {
          item(id).doClick(0);
          return null;
        });
    await(target::isShowing);
    assertTrue(edt(other::isShowing));
  }

  private static JCheckBoxMenuItem item(String id) {
    List<JMenuItem> path = LizzieFrame.menu.refreshFunctionPath(id);
    assertNotNull(path, id);
    return (JCheckBoxMenuItem) path.get(path.size() - 1);
  }

  private static void assertSettingsInsideHeader(Menu menu) throws Exception {
    for (String name :
        List.of("setRules", "tsumeGo", "setLzSaiParam", "setBoardSize", "saveLoad")) {
      assertInsideHeader(toolbarComponent(menu, name), name);
    }
  }

  private static Component toolbarComponent(Menu menu, String name) throws Exception {
    Field field = Menu.class.getDeclaredField(name);
    field.setAccessible(true);
    return (Component) field.get(menu);
  }

  private static void assertInsideHeader(Component component, String name) {
    assertTrue(component.isShowing(), name);
    assertTrue(component.getWidth() > 0 && component.getHeight() > 0, name);
    Rectangle bounds =
        SwingUtilities.convertRectangle(
            component, new Rectangle(component.getSize()), Lizzie.frame);
    assertTrue(
        new Rectangle(Lizzie.frame.getSize()).contains(bounds),
        name + " must be inside the window: " + bounds);
    Rectangle boardBounds =
        SwingUtilities.convertRectangle(
            Lizzie.frame.mainPanel, new Rectangle(Lizzie.frame.mainPanel.getSize()), Lizzie.frame);
    assertTrue(bounds.getMaxY() <= boardBounds.getY(), name + " must not overlap the board");
  }

  private static void capture(Path output) throws Exception {
    Robot robot = new Robot();
    robot.waitForIdle();
    Rectangle bounds = edt(() -> Lizzie.frame.getBounds());
    ImageIO.write(robot.createScreenCapture(bounds), "png", output.toFile());
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (edt(condition::getAsBoolean)) return;
      Thread.sleep(20);
    }
    assertTrue(
        edt(condition::getAsBoolean),
        edt(
            () ->
                "toolbar transition did not settle: frame="
                    + (Lizzie.frame == null ? null : Lizzie.frame.getBounds())
                    + ", menu="
                    + (LizzieFrame.menu == null ? null : LizzieFrame.menu.getBounds())
                    + ", toolbar="
                    + (LizzieFrame.menu == null || LizzieFrame.menu.toolPanel == null
                        ? null
                        : LizzieFrame.menu.toolPanel.getBounds())));
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) return action.call();
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(10, TimeUnit.SECONDS);
  }

  private static class RecordingEngine extends Leelaz {
    final List<String> commands = new ArrayList<>();

    RecordingEngine() throws java.io.IOException {
      super("");
      isKatago = true;
      isLoaded = true;
    }

    @Override
    public void sendCommand(String command) {
      commands.add(command);
    }
  }
}
