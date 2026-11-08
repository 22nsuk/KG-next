package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.util.ResourceBundle;
import javax.swing.JMenu;

/** The toolbar menu shares the engine dialog's existing PDA and WRN visibility preferences. */
final class ToolbarParameterToggles {
  private final Config config;
  private final Runnable refresh;
  final JFontCheckBoxMenuItem pda;
  final JFontCheckBoxMenuItem wrn;

  ToolbarParameterToggles(Config config, ResourceBundle strings, Runnable refresh) {
    this.config = config;
    this.refresh = refresh;
    pda = new JFontCheckBoxMenuItem(strings.getString("Menu.lblPDA"));
    wrn = new JFontCheckBoxMenuItem(strings.getString("Menu.lblWRN"));
    pda.addActionListener(e -> show("show-pda-in-menu", pda.isSelected()));
    wrn.addActionListener(e -> show("show-wrn-in-menu", wrn.isSelected()));
    sync();
  }

  void addTo(JMenu menu) {
    menu.add(pda);
    menu.add(wrn);
  }

  void sync() {
    pda.setSelected(config.showPDAInMenu);
    wrn.setSelected(config.showWRNInMenu);
  }

  private void show(String key, boolean shown) {
    if (key.equals("show-pda-in-menu")) config.showPDAInMenu = shown;
    else config.showWRNInMenu = shown;
    config.uiConfig.put(key, shown);
    refresh.run();
  }
}
