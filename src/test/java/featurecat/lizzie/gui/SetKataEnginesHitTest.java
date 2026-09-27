package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.JCheckBox;
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

  private static final class QuietFrame extends LizzieFrame {
    @Override
    public GraphicsConfiguration getGraphicsConfiguration() {
      return GraphicsEnvironment.getLocalGraphicsEnvironment()
          .getDefaultScreenDevice().getDefaultConfiguration();
    }
  }
}
