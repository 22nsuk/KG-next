package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class DoubleMenuEngineCheckBoxLayoutTest {
  @Test
  void wrnAndPdaUseKomiStyleLabelColonAndAdjacentField() {
    for (ResourceBundle bundle :
        List.of(
            Lizzie.resourceBundle,
            ResourceBundle.getBundle("l10n.DisplayStrings", Locale.KOREAN),
            ResourceBundle.getBundle("l10n.DisplayStrings", Locale.JAPAN),
            ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE))) {
      assertKomiStyleGroup(bundle.getString("Menu.separateLblWrn"));
      assertKomiStyleGroup(bundle.getString("Menu.separateLblPda"));
    }
  }

  @Test
  void layoutTracksFontMetricsAndMenuHeightWithoutClipping() {
    int originalFontSize = Config.frameFontSize;
    int originalMenuHeight = Config.menuHeight;
    try {
      for (int fontSize : new int[] {12, 16, 18}) {
        for (int menuHeight : new int[] {20, 25, 30}) {
          Config.frameFontSize = fontSize;
          Config.menuHeight = menuHeight;
          for (String label : new String[] {"WRN", "탐색 폭(WRN)", "探索幅(WRN)"}) {
            assertScalableGroup(fontSize, menuHeight, label);
          }
        }
      }
    } finally {
      Config.frameFontSize = originalFontSize;
      Config.menuHeight = originalMenuHeight;
    }
  }

  private static void assertKomiStyleGroup(String rawLabel) {
    JFontCheckBox enable = new JFontCheckBox();
    JFontLabel label = new JFontLabel();
    JFontTextField field = new JFontTextField();
    JPanel panel = Menu.attachDoubleMenuLabeledField(enable, label, field, rawLabel);
    panel.setSize(panel.getPreferredSize());
    panel.doLayout();

    assertEquals("", enable.getText());
    assertEquals(rawLabel, enable.getAccessibleContext().getAccessibleName());
    assertTrue(
        label.getText().endsWith(":") || label.getText().endsWith("："),
        () -> "label should end with colon: " + label.getText());
    assertTrue(label.getText().startsWith(rawLabel.replaceAll("[:：]+$", "")));
    assertEquals(3, panel.getComponentCount());
    assertFalse(containsSpinner(panel));

    Icon icon = enable.getIcon();
    int iconWidth = icon == null ? 16 : icon.getIconWidth();
    int iconHeight = icon == null ? 16 : icon.getIconHeight();
    Insets insets = enable.getInsets();
    assertTrue(
        enable.getPreferredSize().width >= iconWidth + insets.left + insets.right,
        () -> "checkbox clips its icon horizontally: " + enable.getPreferredSize());
    assertTrue(
        enable.getPreferredSize().height >= iconHeight + insets.top + insets.bottom,
        () -> "checkbox clips its icon vertically: " + enable.getPreferredSize());
    assertTrue(
        enable.getPreferredSize().width >= Config.menuHeight
            && enable.getPreferredSize().height >= Config.menuHeight,
        () -> "checkbox target is smaller than the menu row: " + enable.getPreferredSize());

    assertEquals(0, field.getX() - (label.getX() + label.getWidth()));
    assertTrue(
        field.getX() - (enable.getX() + enable.getWidth()) >= label.getWidth() - 2,
        () -> "label not sitting between checkbox and field: " + panel.getBounds());
  }

  private static void assertScalableGroup(int fontSize, int menuHeight, String text) {
    JFontCheckBox enable = new JFontCheckBox();
    JFontLabel label = new JFontLabel();
    JFontTextField field = new JFontTextField();
    Dimension naturalFieldSize = field.getPreferredSize();
    JPanel panel = Menu.attachDoubleMenuLabeledField(enable, label, field, text);
    Dimension preferred = panel.getPreferredSize();
    panel.setSize(preferred);
    panel.doLayout();

    FontMetrics metrics = label.getFontMetrics(label.getFont());
    assertEquals(fontSize, label.getFont().getSize());
    assertTrue(
        label.getPreferredSize().height >= metrics.getHeight(),
        () -> "label clips font " + fontSize + ": " + label.getPreferredSize());
    assertTrue(
        label.getPreferredSize().width >= metrics.stringWidth(label.getText()),
        () -> "label clips text at font " + fontSize + ": " + label.getPreferredSize());
    assertTrue(
        enable.getPreferredSize().width >= menuHeight
            && enable.getPreferredSize().height >= menuHeight,
        () ->
            "checkbox target does not track menu height "
                + menuHeight
                + ": "
                + enable.getPreferredSize());
    assertTrue(
        field.getPreferredSize().height >= naturalFieldSize.height,
        () -> "field is shorter than its natural size at font " + fontSize);
    assertTrue(
        preferred.height
            >= Math.max(
                menuHeight,
                Math.max(label.getPreferredSize().height, field.getPreferredSize().height)),
        () ->
            "group clips font/menu combination "
                + fontSize
                + "/"
                + menuHeight
                + ": "
                + preferred);
    assertEquals(0, field.getX() - (label.getX() + label.getWidth()));
  }

  private static boolean containsSpinner(JPanel panel) {
    for (var component : panel.getComponents()) {
      if (component instanceof JButton) {
        return true;
      }
    }
    return false;
  }
}
