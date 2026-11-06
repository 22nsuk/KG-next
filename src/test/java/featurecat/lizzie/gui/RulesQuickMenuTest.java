package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.RulePreset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class RulesQuickMenuTest {
  private static final ResourceBundle STRINGS =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.ENGLISH);

  @Test
  void choicesShowTheCurrentPairAndDispatchEachPresetOrTheFullEditor() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      List<RulePreset> applied = new ArrayList<>();
      boolean[] opened = {false};
      JPopupMenu menu = RulesQuickMenu.build(STRINGS, Optional.of(RulePreset.CHINESE_7), false,
          new RulesQuickMenu.Actions() {
            public void apply(RulePreset preset) { applied.add(preset); }
            public void openRulesDialog() { opened[0] = true; }
          });
      assertEquals(9, menu.getComponentCount());
      assertFalse(menu.getComponent(0).isEnabled());
      for (int i = 0; i < RulePreset.values().length; i++) {
        JCheckBoxMenuItem item = (JCheckBoxMenuItem) menu.getComponent(i + 1);
        RulePreset preset = RulePreset.values()[i];
        assertEquals(preset == RulePreset.CHINESE_7, item.isSelected());
        assertEquals(STRINGS.getString(preset.labelKey()), item.getText());
        item.doClick(0);
      }
      assertEquals(List.of(RulePreset.values()), applied);
      ((JMenuItem) menu.getComponent(menu.getComponentCount() - 1)).doClick(0);
      assertTrue(opened[0]);
    });
  }

  @Test
  void handicapMenuDoesNotPromiseAChangedKomiAndCustomValuesHaveNoFalseSelection() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      RulesQuickMenu.Actions unused = new RulesQuickMenu.Actions() {
        public void apply(RulePreset preset) { fail("Display must not mutate the game"); }
        public void openRulesDialog() { fail("Display must not open the editor"); }
      };
      JPopupMenu handicap = RulesQuickMenu.build(STRINGS, Optional.of(RulePreset.CHINESE), true, unused);
      int checked = 0;
      for (var component : handicap.getComponents()) {
        if (component instanceof JCheckBoxMenuItem item) {
          assertFalse(item.getText().contains("komi"));
          if (item.isSelected()) checked++;
        }
      }
      assertEquals(1, checked);
      assertEquals(STRINGS.getString("RulesQuickMenu.handicapKomi"),
          ((JMenuItem) handicap.getComponent(6)).getText());
      JPopupMenu custom = RulesQuickMenu.build(STRINGS, Optional.empty(), false, unused);
      for (var component : custom.getComponents()) {
        if (component instanceof JCheckBoxMenuItem item) assertFalse(item.isSelected());
      }
      assertEquals(STRINGS.getString("RulesQuickMenu.custom"),
          ((JMenuItem) custom.getComponent(7)).getText());
    });
  }
}
