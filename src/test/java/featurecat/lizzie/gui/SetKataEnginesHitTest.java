package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.lang.reflect.Field;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetKataEnginesHitTest {
  @TempDir Path root;

  @Test
  void threadCheckboxReceivesMouseAtEverySupportedFontSize() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    int previousSize = Config.frameFontSize;
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      Lizzie.frame =
          (QuietFrame) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(QuietFrame.class);
      Lizzie.setPrimaryEngine(null);
      SwingUtilities.invokeAndWait(
          () -> {
            for (int size : new int[] {12, 16, 20}) {
              Config.frameFontSize = size;
              SetKataEngines dialog = new SetKataEngines();
              try {
                dialog.setVisible(true);
                Field checkboxField = SetKataEngines.class.getDeclaredField("chkEditThreads");
                checkboxField.setAccessible(true);
                JCheckBox checkbox = (JCheckBox) checkboxField.get(dialog);
                Point center = SwingUtilities.convertPoint(
                    checkbox, checkbox.getWidth() / 2, checkbox.getHeight() / 2,
                    dialog.getContentPane());
                assertSame(
                    checkbox,
                    SwingUtilities.getDeepestComponentAt(
                        dialog.getContentPane(), center.x, center.y),
                    "Thread checkbox must receive mouse input at font size " + size);
              } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
              } finally {
                dialog.dispose();
              }
            }
          });
    } finally {
      Config.frameFontSize = previousSize;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.resourceBundle = previousBundle;
    }
  }

  @Test
  void acknowledgedOverrideReopensCheckedAndLateQueryKeepsDraft() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousConsole = Lizzie.gtpConsole;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    Leelaz engine = null;
    SetKataEngines[] dialogs = new SetKataEngines[3];
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      Lizzie.frame = (QuietFrame) unsafe.allocateInstance(QuietFrame.class);
      Lizzie.gtpConsole = (QuietConsole) unsafe.allocateInstance(QuietConsole.class);
      engine = new Leelaz("katago gtp");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      Lizzie.setPrimaryEngine(engine);
      var set = engine.captureRuntimeSearchThreads().apply(10);
      engineHook(engine, "processCommandResponseLineForTest", String.class,
          "=" + commandId(output, "kata-set-param numSearchThreads 10"));
      assertEquals(10, set.get(2, TimeUnit.SECONDS));

      SwingUtilities.invokeAndWait(() -> {
        dialogs[0] = new SetKataEngines();
        dialogs[0].setVisible(true);
        assertTrue(field(dialogs[0], "chkEditThreads", JCheckBox.class).isSelected());
        assertEquals("10", field(dialogs[0], "txtThreads", JTextField.class).getText(),
            "confirmed value must be visible before the query responds");
        field(dialogs[0], "txtThreads", JTextField.class).setText("12");
      });
      int queryId = commandId(output, "kata-get-param numSearchThreads");
      engineHook(engine, "processCommandResponseLineForTest", String.class,
          "=" + queryId + " 10");
      output.reset();
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("12", field(dialogs[0], "txtThreads", JTextField.class).getText());
        field(dialogs[0], "btnCancel", JButton.class).doClick();
        dialogs[1] = new SetKataEngines();
        dialogs[1].setVisible(true);
        assertTrue(field(dialogs[1], "chkEditThreads", JCheckBox.class).isSelected());
        assertEquals("10", field(dialogs[1], "txtThreads", JTextField.class).getText());
      });
      assertEquals(Leelaz.RuntimeThreadOverrideState.ON,
          engine.captureRuntimeSearchThreads().overrideState());
      engineHook(engine, "processCommandResponseLineForTest", String.class,
          "?" + commandId(output, "kata-get-param numSearchThreads") + " unavailable");
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("10", field(dialogs[1], "txtThreads", JTextField.class).getText());
        assertEquals(Lizzie.resourceBundle.getString("SetKataEngines.threadReadFailed"),
            field(dialogs[1], "lblThreadReadStatus", javax.swing.JLabel.class).getText());
        dialogs[1].setVisible(false);
      });
      output.reset();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      SwingUtilities.invokeAndWait(() -> {
        dialogs[2] = new SetKataEngines();
        dialogs[2].setVisible(true);
        assertEquals("", field(dialogs[2], "txtThreads", JTextField.class).getText(),
            "replacement process must not inherit the old confirmed value");
        assertEquals(Lizzie.resourceBundle.getString("SetKataEngines.threadReading"),
            field(dialogs[2], "lblThreadReadStatus", javax.swing.JLabel.class).getText());
      });
      engineHook(engine, "processCommandResponseLineForTest", String.class,
          "=" + commandId(output, "kata-get-param numSearchThreads") + " 6");
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("6", field(dialogs[2], "txtThreads", JTextField.class).getText());
        assertEquals("", field(dialogs[2], "lblThreadReadStatus", javax.swing.JLabel.class).getText());
      });
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        for (SetKataEngines dialog : dialogs) if (dialog != null) dialog.dispose();
      });
      if (engine != null) {
        engine.isNormalEnd = true;
        engine.forceQuit();
      }
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.gtpConsole = previousConsole;
      Lizzie.resourceBundle = previousBundle;
    }
  }

  private static <T> T field(SetKataEngines dialog, String name, Class<T> type) {
    try {
      Field field = SetKataEngines.class.getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(dialog));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void engineHook(Leelaz engine, String name, Class<?> argumentType,
      Object argument) throws Exception {
    var hook = Leelaz.class.getDeclaredMethod(name, argumentType);
    hook.setAccessible(true);
    hook.invoke(engine, argument);
  }

  private static int commandId(ByteArrayOutputStream output, String suffix) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    do {
      for (String line : output.toString(StandardCharsets.UTF_8).split("\\R")) {
        if (line.endsWith(suffix)) return Integer.parseInt(line.substring(0, line.indexOf(' ')));
      }
      Thread.sleep(5);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Missing command " + suffix);
  }

  private static final class QuietConsole extends GtpConsolePane {
    private QuietConsole() { super(null); }
    @Override public void addLine(String line) {}
  }

  private static final class QuietFrame extends LizzieFrame {
    @Override
    public GraphicsConfiguration getGraphicsConfiguration() {
      return GraphicsEnvironment.getLocalGraphicsEnvironment()
          .getDefaultScreenDevice().getDefaultConfiguration();
    }
  }
}
