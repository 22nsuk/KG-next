package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.RulePreset;
import java.util.Optional;
import java.util.ResourceBundle;
import javax.swing.JPopupMenu;

/** The rules caption's current-game presets and link to the full rules editor. */
final class RulesQuickMenu {
  interface Actions {
    void apply(RulePreset preset);

    void openRulesDialog();
  }

  private RulesQuickMenu() {}

  static JPopupMenu build(
      ResourceBundle strings, Optional<RulePreset> current, boolean handicap, Actions actions) {
    JPopupMenu menu = new JPopupMenu();
    JFontMenuItem scope = new JFontMenuItem(strings.getString("RulesQuickMenu.currentGame"));
    scope.setEnabled(false);
    menu.add(scope);
    for (RulePreset preset : RulePreset.choices(handicap)) {
      String key = handicap ? preset.rules().summary().labelKey() : preset.labelKey();
      JFontCheckBoxMenuItem item = new JFontCheckBoxMenuItem(strings.getString(key));
      item.setSelected(current.orElse(null) == preset);
      item.addActionListener(event -> actions.apply(preset));
      menu.add(item);
    }
    if (current.isEmpty()) {
      JFontMenuItem custom = new JFontMenuItem(strings.getString("RulesQuickMenu.custom"));
      custom.setEnabled(false);
      menu.add(custom);
    }
    if (handicap) {
      JFontMenuItem note = new JFontMenuItem(strings.getString("RulesQuickMenu.handicapKomi"));
      note.setEnabled(false);
      menu.add(note);
    }
    menu.addSeparator();
    JFontMenuItem more = new JFontMenuItem(strings.getString("RulesQuickMenu.more"));
    more.addActionListener(event -> actions.openRulesDialog());
    menu.add(more);
    AppleStyleSupport.installPopupStyle(menu);
    return menu;
  }
}
