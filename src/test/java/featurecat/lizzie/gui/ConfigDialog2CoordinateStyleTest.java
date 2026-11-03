package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhlabs.image.GaussianFilter;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.theme.Theme;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigDialog2CoordinateStyleTest {
  @TempDir Path directory;

  @Test
  void productionThemeControlsLoadSaveAndSwitchBetweenDefaultAndNamedThemes() throws Exception {
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    ResourceBundle previousResources = Lizzie.resourceBundle;
    String previousPath = Theme.pathPrefix;
    String previousDefaultFont = Config.sysDefaultFontName;
    Font previousUiFont = LizzieFrame.uiFont;
    Font previousBoardFont = LizzieFrame.playoutsFont;
    Font previousWinrateFont = LizzieFrame.winrateFont;
    ConfigDialog2[] dialogHolder = new ConfigDialog2[1];
    try {
      Theme.pathPrefix = directory.resolve("themes").toString() + java.io.File.separator;
      String name = "좌표 테마";
      Path themeFile = Files.createDirectories(Path.of(Theme.pathPrefix, name)).resolve("theme.txt");
      Files.writeString(themeFile, "{}");
      Path profile = Files.createDirectory(directory.resolve("profile"));
      Files.writeString(
          profile.resolve("config.txt"),
          new JSONObject()
              .put("leelaz", new JSONObject().put("engine-settings-list", new JSONArray()))
              .put("ui", new JSONObject().put("theme", "").put("use-language", 3)
                  .put("coordinate-size", 140).put("coordinate-bold", true))
              .toString());
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.KOREAN);
      Lizzie.config = ConfigTestHelper.createBootstrapped(profile);
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.frame.filter20 = new GaussianFilter(Lizzie.config.backgroundFilter);

      SwingUtilities.invokeAndWait(() -> {
        try {
          // Exercise the production theme tab and its IO without requiring a native dialog peer.
          ConfigDialog2 dialog = allocate(ConfigDialog2.class);
          dialogHolder[0] = dialog;
          setField(dialog, "resourceBundle", Lizzie.resourceBundle);
          setField(dialog, "disposed", true); // Suppress only deferred window navigation/rebuild.
          PanelWithToolTips panel = new PanelWithToolTips();
          setField(dialog, "themeTab", panel);
          // These shared controls are created by the general tab's constructor before loadThemeTab.
          setField(dialog, "chkShowTrackingPointOutline", new JCheckBox());
          JPanel trackingOpacity = new JPanel();
          trackingOpacity.add(new javax.swing.JSlider(0, 100, 70));
          setField(dialog, "pnlTrackingPointOutlineOpacity", trackingOpacity);
          dialog.tabbedPane = new JTabbedPane();
          dialog.tabbedPane.addTab("general", new JPanel());
          dialog.tabbedPane.addTab("theme", panel);
          dialog.tabbedPane.setSelectedIndex(1);
          invoke(dialog, "loadThemeTab");
          dialog.timer.stop();

          JComboBox<?> sizes = field(dialog, "cmbCoordinateSize", JComboBox.class);
          JCheckBox bold = field(dialog, "chkCoordinateBold", JCheckBox.class);
          JComboBox<?> themes = field(dialog, "cmbThemes", JComboBox.class);
          assertEquals(1, sizes.getSelectedIndex());
          assertTrue(bold.isSelected());
          assertTrue(sizes.getItemAt(0).toString().contains("100%"));
          assertTrue(sizes.getItemAt(1).toString().contains("140%"));
          assertTrue(sizes.getItemAt(2).toString().contains("200%"));
          assertEquals("굵게", bold.getText());
          verifyRowLayout(panel, sizes, bold);

          themes.setSelectedItem(name);
          assertEquals(1, sizes.getSelectedIndex(), "An older theme inherits the general style");
          assertTrue(bold.isSelected());
          sizes.setSelectedIndex(2);
          bold.setSelected(false);
          invoke(dialog, "writeThemeValues");
          JSONObject savedTheme = new JSONObject(Files.readString(themeFile));
          assertEquals(200, savedTheme.getInt("coordinate-size"));
          assertFalse(savedTheme.getBoolean("coordinate-bold"));
          assertEquals(140, Lizzie.config.uiConfig.getInt("coordinate-size"));
          assertTrue(Lizzie.config.uiConfig.getBoolean("coordinate-bold"));

          themes.setSelectedIndex(0);
          assertEquals(1, sizes.getSelectedIndex());
          assertTrue(bold.isSelected());
          sizes.setSelectedIndex(0);
          bold.setSelected(false);
          invoke(dialog, "writeThemeValues");
          Lizzie.config.save();
          Config reloaded = ConfigTestHelper.createBootstrapped(profile);
          assertEquals(100, reloaded.theme.coordinateSize());
          assertFalse(reloaded.theme.coordinateBold());

          themes.setSelectedItem(name);
          assertEquals(2, sizes.getSelectedIndex(), "Named overrides survive a default-theme save");
          assertFalse(bold.isSelected());
          Lizzie.config.uiConfig.put("theme", name);
          Lizzie.config.save();
          Config namedReload = ConfigTestHelper.createBootstrapped(profile);
          assertEquals(200, namedReload.theme.coordinateSize());
          assertFalse(namedReload.theme.coordinateBold());
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      });
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        if (dialogHolder[0] != null && dialogHolder[0].timer != null) dialogHolder[0].timer.stop();
      });
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.resourceBundle = previousResources;
      Theme.pathPrefix = previousPath;
      Config.sysDefaultFontName = previousDefaultFont;
      LizzieFrame.uiFont = previousUiFont;
      LizzieFrame.playoutsFont = previousBoardFont;
      LizzieFrame.winrateFont = previousWinrateFont;
    }
  }

  private static void verifyRowLayout(Container panel, JComboBox<?> sizes, JCheckBox bold) {
    JComponent row = findRow(panel);
    assertNotNull(row, "The searchable coordinate setting must bind to a production theme row");
    for (int width : new int[] {640, 800}) {
      row.setSize(width, row.getPreferredSize().height);
      layout(row);
      Rectangle sizeBounds = SwingUtilities.convertRectangle(sizes.getParent(), sizes.getBounds(), row);
      Rectangle boldBounds = SwingUtilities.convertRectangle(bold.getParent(), bold.getBounds(), row);
      Rectangle rowBounds = new Rectangle(row.getSize());
      assertTrue(rowBounds.contains(sizeBounds), sizeBounds.toString());
      assertTrue(rowBounds.contains(boldBounds), boldBounds.toString());
      assertFalse(sizeBounds.intersects(boldBounds));
      assertTrue(sizeBounds.width >= sizes.getPreferredSize().width);
    }
  }

  private static JComponent findRow(Component component) {
    if (component instanceof JComponent
        && "config.theme.coordinates".equals(
            ((JComponent) component).getClientProperty("lizzie.config.settingTargetId"))) {
      return (JComponent) component;
    }
    if (component instanceof Container) {
      for (Component child : ((Container) component).getComponents()) {
        JComponent found = findRow(child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void layout(Container container) {
    container.doLayout();
    for (Component component : container.getComponents()) {
      if (component instanceof Container) layout((Container) component);
    }
  }

  private static void invoke(Object owner, String name) throws Exception {
    Method method = owner.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    try {
      method.invoke(owner);
    } catch (InvocationTargetException failure) {
      throw new AssertionError(failure.getCause());
    }
  }

  private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(owner));
  }

  private static void setField(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
