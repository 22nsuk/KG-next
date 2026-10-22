package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.FileAccessTestSupport;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.theme.Theme;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Real settings buttons, permission failures, error dialogs, and retry in the same window. */
class ThemeSaveDesktopTest {
  private static final String THEME_NAME = "사용자 &copy; 테마";
  private static final String CUSTOM_THRESHOLDS = "[-6,100]";
  private static final String CUSTOM_COLORS = "[[12,34,56],[78,90,123]]";
  private static final String DEFAULT_THRESHOLDS = "[-24,-12,-6,-3,-1,3,100]";
  private static final String DEFAULT_COLORS =
      "[[155,25,150],[208,16,19],[200,140,50],[180,180,0],[140,202,34],[0,220,0],[0,210,210]]";
  private static final AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

  @Test
  void failedThemeSavePreservesFilesAndAllowsRetry() throws Exception {
    runScenario("save");
  }

  @Test
  void failedColorResetKeepsDialogOpenAndAllowsRetry() throws Exception {
    runScenario("reset");
  }

  private void runScenario(String scenario) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            getClass(), "theme-" + scenario, List.of(), List.of(scenario));
    assertEquals("PASS", Files.readString(result));
    assertTrue(Files.isRegularFile(result.resolveSibling("failure-ko.png")));
    assertTrue(Files.isRegularFile(result.resolveSibling("retry-ko.png")));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      // Install before startup so the application's crash handler delegates to this observer.
      Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
      Thread.setDefaultUncaughtExceptionHandler(
          (thread, failure) -> {
            unexpectedFailure.compareAndSet(null, failure);
            if (previous != null) previous.uncaughtException(thread, failure);
            else failure.printStackTrace();
          });
      Path themeFile = prepareApplication(work);
      SwingUtilities.invokeLater(() -> Lizzie.frame.openConfigDialog2(1));
      awaitEdt(
          () ->
              Lizzie.frame.configDialog2 != null
                  && Lizzie.frame.configDialog2.isShowing()
                  && field(Lizzie.frame.configDialog2, "theme", Theme.class) != null);
      ConfigDialog2 dialog = onEdt(() -> Lizzie.frame.configDialog2);
      assertEquals(
          2, onEdt(() -> field(dialog, "tblBlunderNodes", JTable.class).getRowCount()));
      DesktopProbeProcess.phase(result, "settings-ready");
      if ("save".equals(args[0])) verifySave(dialog, themeFile, work, result);
      else if ("reset".equals(args[0])) verifyReset(dialog, themeFile, result);
      else throw new IllegalArgumentException("Unknown scenario: " + args[0]);
      onEdt(() -> null);
      assertNoUnexpectedFailure();
      Files.writeString(result, "PASS");
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static Path prepareApplication(Path work) throws Exception {
    Path themeFile =
        Files.createDirectories(work.resolve("theme").resolve(THEME_NAME)).resolve("theme.txt");
    Files.writeString(
        themeFile,
        new JSONObject()
            .put("winrate-stroke-width", 1.7)
            .put("stone-indicator-type", 0)
            .put("background-filter", 20)
            .put("use-pure-board", true)
            .put("use-pure-background", true)
            .put("use-pure-stone", true)
            .put("blunder-winrate-thresholds", new JSONArray(CUSTOM_THRESHOLDS))
            .put("blunder-node-colors", new JSONArray(CUSTOM_COLORS))
            .toString(2));
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject()
            .put("leelaz", new JSONObject().put("engine-settings-list", new JSONArray()))
            .put("logging", new JSONObject().put("diagnostics-enabled", false))
            .put(
                "ui",
                new JSONObject()
                    .put("theme", THEME_NAME)
                    .put("autoload-empty", true)
                    .put("first-time-load", false)
                    .put("auto-check-engine-alive", false)
                    .put("play-sound", false)
                    .put("use-language", 3))
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
          assertNotNull(Lizzie.leelaz, "Keep the production startup engine placeholder");
          Lizzie.frame.setBounds(60, 60, 1180, 820);
          return null;
        });
    return themeFile;
  }

  private static void verifySave(
      ConfigDialog2 dialog, Path themeFile, Path work, Path result) throws Exception {
    int originalIndicator = onEdt(() -> Lizzie.config.stoneIndicatorType);
    float originalFilter = onEdt(() -> Lizzie.frame.filter20.getRadius());
    byte[] originalTheme = Files.readAllBytes(themeFile);
    byte[] originalConfig = Files.readAllBytes(work.resolve("config.txt"));
    onEdt(
        () -> {
          field(dialog, "spnWinrateStrokeWidth", JSpinner.class).setValue(3.4);
          field(dialog, "rdoStoneIndicatorCircle", JRadioButton.class).setSelected(true);
          field(dialog, "txtBackgroundFilter", JTextField.class).setText("7");
          return null;
        });
    try (AutoCloseable ignored = FileAccessTestSupport.denyParentWrite(themeFile.getParent())) {
      assertCreationDenied(themeFile.getParent());
      click(onEdt(() -> field(dialog, "okButton", AbstractButton.class)));
      awaitSaveFailure(dialog);
      assertSaveError(themeFile);
      capture(result.resolveSibling("failure-ko.png"));
      dismissError();
      assertArrayEquals(originalTheme, Files.readAllBytes(themeFile));
      assertArrayEquals(originalConfig, Files.readAllBytes(work.resolve("config.txt")));
      onEdt(
          () -> {
            assertSame(dialog, Lizzie.frame.configDialog2);
            assertTrue(dialog.isShowing());
            assertEquals(
                3.4,
                ((Number) field(dialog, "spnWinrateStrokeWidth", JSpinner.class).getValue())
                    .doubleValue());
            assertTrue(field(dialog, "rdoStoneIndicatorCircle", JRadioButton.class).isSelected());
            assertEquals("7", field(dialog, "txtBackgroundFilter", JTextField.class).getText());
            assertEquals(originalIndicator, Lizzie.config.stoneIndicatorType);
            assertEquals(originalFilter, Lizzie.frame.filter20.getRadius());
            return null;
          });
    }
    DesktopProbeProcess.phase(result, "save-failed-with-edits-preserved");
    click(onEdt(() -> field(dialog, "okButton", AbstractButton.class)));
    awaitEdt(() -> !dialog.isShowing());
    JSONObject saved = new JSONObject(Files.readString(themeFile));
    assertEquals(3.4, saved.getDouble("winrate-stroke-width"));
    assertEquals(1, saved.getInt("stone-indicator-type"));
    assertEquals(7, saved.getInt("background-filter"));
    assertEquals(
        THEME_NAME,
        new JSONObject(Files.readString(work.resolve("config.txt")))
            .getJSONObject("ui")
            .getString("theme"));
    onEdt(
        () -> {
          assertEquals(1, Lizzie.config.stoneIndicatorType);
          assertEquals(7f, Lizzie.frame.filter20.getRadius());
          return null;
        });
    capture(result.resolveSibling("retry-ko.png"));
    DesktopProbeProcess.phase(result, "save-retry-persisted");
  }

  private static void verifyReset(ConfigDialog2 dialog, Path themeFile, Path result)
      throws Exception {
    DenyAfterSaveTheme fault =
        onEdt(
            () -> {
              DenyAfterSaveTheme value =
                  new DenyAfterSaveTheme(field(dialog, "theme", Theme.class), themeFile);
              Field theme = ConfigDialog2.class.getDeclaredField("theme");
              theme.setAccessible(true);
              theme.set(dialog, value);
              return value;
            });
    AbstractButton reset =
        onEdt(
            () ->
                find(
                    field(dialog, "themeTab", Container.class),
                    AbstractButton.class,
                    button ->
                        Lizzie.resourceBundle
                            .getString("LizzieConfig.button.reset")
                            .equals(button.getText())));
    assertNotNull(reset);
    try {
      click(reset);
      awaitSaveFailure(dialog);
      assertSaveError(themeFile);
      assertEquals(1, fault.saves);
      assertArrayEquals(fault.savedBeforeFailure, Files.readAllBytes(themeFile));
      assertRules(themeFile, CUSTOM_THRESHOLDS, CUSTOM_COLORS);
      capture(result.resolveSibling("failure-ko.png"));
      dismissError();
      onEdt(
          () -> {
            assertSame(dialog, Lizzie.frame.configDialog2);
            assertTrue(dialog.isShowing());
            assertEquals(2, field(dialog, "tblBlunderNodes", JTable.class).getRowCount());
            assertRuntimeRules(CUSTOM_THRESHOLDS, CUSTOM_COLORS);
            return null;
          });
    } finally {
      fault.restoreWrite();
    }
    DesktopProbeProcess.phase(result, "reset-failed-with-custom-colors-preserved");
    click(reset);
    awaitEdt(
        () ->
            Lizzie.frame.configDialog2 != dialog
                && Lizzie.frame.configDialog2.isShowing()
                && field(Lizzie.frame.configDialog2, "tblBlunderNodes", JTable.class) != null);
    assertRules(themeFile, DEFAULT_THRESHOLDS, DEFAULT_COLORS);
    onEdt(
        () -> {
          assertFalse(dialog.isShowing());
          JTable table = field(Lizzie.frame.configDialog2, "tblBlunderNodes", JTable.class);
          assertEquals(7, table.getRowCount());
          assertEquals(-24.0, table.getValueAt(0, 0));
          assertEquals(100.0, table.getValueAt(6, 0));
          assertRuntimeRules(DEFAULT_THRESHOLDS, DEFAULT_COLORS);
          table.scrollRectToVisible(new Rectangle(0, 0, table.getWidth(), table.getHeight()));
          return null;
        });
    capture(result.resolveSibling("retry-ko.png"));
    DesktopProbeProcess.phase(result, "reset-retry-persisted");
  }

  private static final class DenyAfterSaveTheme extends Theme {
    private final Path target;
    private AutoCloseable restriction;
    private byte[] savedBeforeFailure;
    private int saves;

    private DenyAfterSaveTheme(Theme original, Path target) {
      super(THEME_NAME);
      this.config = original.config;
      this.path = original.path;
      this.target = target;
    }

    @Override
    public void save() throws IOException {
      super.save();
      if (++saves == 1) {
        savedBeforeFailure = Files.readAllBytes(target);
        // The reset action first saves the editor, then creates a real Theme for its second write.
        restriction = FileAccessTestSupport.denyParentWrite(target.getParent());
        assertCreationDenied(target.getParent());
      }
    }

    private void restoreWrite() throws Exception {
      if (restriction != null) restriction.close();
      restriction = null;
    }
  }

  private static void assertCreationDenied(Path directory) throws IOException {
    Path probe = directory.resolve("write-permission-probe");
    try {
      assertThrows(IOException.class, () -> Files.createFile(probe));
    } finally {
      Files.deleteIfExists(probe);
    }
  }

  private static void assertRules(Path themeFile, String thresholds, String colors)
      throws IOException {
    JSONObject saved = new JSONObject(Files.readString(themeFile));
    assertEquals(thresholds, saved.getJSONArray("blunder-winrate-thresholds").toString());
    assertEquals(colors, saved.getJSONArray("blunder-node-colors").toString());
  }

  private static HtmlMessage errorDialog() {
    for (Window window : Window.getWindows()) {
      if (window instanceof HtmlMessage message && message.isShowing()) return message;
    }
    return null;
  }

  private static void assertRuntimeRules(String thresholds, String colors) {
    JSONArray expectedThresholds = new JSONArray(thresholds);
    JSONArray expectedColors = new JSONArray(colors);
    var actualThresholds = Lizzie.config.blunderWinrateThresholds.orElseThrow();
    var actualColors = Lizzie.config.blunderNodeColors.orElseThrow();
    assertEquals(expectedThresholds.length(), actualThresholds.size());
    for (int index = 0; index < expectedThresholds.length(); index++) {
      double threshold = expectedThresholds.getDouble(index);
      JSONArray color = expectedColors.getJSONArray(index);
      assertEquals(threshold, actualThresholds.get(index));
      assertEquals(
          new Color(color.getInt(0), color.getInt(1), color.getInt(2)), actualColors.get(threshold));
    }
  }

  private static void awaitSaveFailure(ConfigDialog2 dialog) throws Exception {
    awaitEdt(() -> errorDialog() != null || !dialog.isShowing());
    onEdt(
        () -> {
          assertSame(dialog, Lizzie.frame.configDialog2, "A failed save must not rebuild the editor");
          assertTrue(dialog.isShowing(), "A failed save must keep the editor open");
          assertNotNull(errorDialog(), "A failed save must display its error");
          return null;
        });
  }

  private static void assertSaveError(Path themeFile) throws Exception {
    onEdt(
        () -> {
          JTextPane message = find(errorDialog(), JTextPane.class, component -> true);
          assertNotNull(message);
          String text = message.getDocument().getText(0, message.getDocument().getLength());
          assertTrue(
              text.contains(Lizzie.resourceBundle.getString("LizzieConfig.saveFailed")),
              text);
          assertTrue(text.contains(themeFile.toString()), text);
          assertTrue(errorDialog().getWidth() <= 800, "Save failure should wrap long paths");
          return null;
        });
  }

  private static void dismissError() throws Exception {
    onEdt(
        () -> {
          HtmlMessage error = errorDialog();
          assertNotNull(error);
          error.dispatchEvent(new WindowEvent(error, WindowEvent.WINDOW_CLOSING));
          return null;
        });
    awaitEdt(() -> errorDialog() == null);
  }

  private static <T> T field(ConfigDialog2 dialog, String name, Class<T> type) {
    try {
      Field field = ConfigDialog2.class.getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(dialog));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static <T extends Component> T find(
      Component component, Class<T> type, Predicate<T> predicate) {
    if (type.isInstance(component) && predicate.test(type.cast(component))) return type.cast(component);
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        T match = find(child, type, predicate);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static void click(AbstractButton button) throws Exception {
    Point center =
        onEdt(
            () -> {
              assertTrue(button.isShowing(), button.getText());
              assertTrue(button.isEnabled(), button.getText());
              button.scrollRectToVisible(new Rectangle(0, 0, button.getWidth(), button.getHeight()));
              Point point = button.getLocationOnScreen();
              point.translate(button.getWidth() / 2, button.getHeight() / 2);
              return point;
            });
    Robot robot = new Robot();
    robot.mouseMove(center.x, center.y);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    robot.waitForIdle();
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeAndWait(task);
    return task.get();
  }

  private static void awaitEdt(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (true) {
      assertNoUnexpectedFailure();
      if (onEdt(condition::getAsBoolean)) {
        assertNoUnexpectedFailure();
        return;
      }
      if (System.nanoTime() >= deadline) throw new AssertionError("Theme save UI condition timed out");
      Thread.sleep(50);
    }
  }

  private static void assertNoUnexpectedFailure() {
    Throwable failure = unexpectedFailure.get();
    if (failure != null) throw new AssertionError("Unexpected asynchronous application failure", failure);
  }

  private static void capture(Path destination) throws Exception {
    Robot robot = new Robot();
    robot.waitForIdle();
    Rectangle bounds =
        onEdt(
            () -> {
              Rectangle area = Lizzie.frame.getBounds();
              for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()) {
                  var root = dialog.getRootPane();
                  root.paintImmediately(0, 0, root.getWidth(), root.getHeight());
                  area = area.union(dialog.getBounds());
                }
              }
              Toolkit.getDefaultToolkit().sync();
              return area;
            });
    assertTrue(ImageIO.write(robot.createScreenCapture(bounds), "png", destination.toFile()));
  }
}
