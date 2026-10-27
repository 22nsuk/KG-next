package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameView;
import java.awt.Insets;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

/**
 * The views of the engine-game parameters outside the panel: the "game parameters" button next to
 * pause, the same entry in the game menu (the way in when the top toolbar is hidden or combined
 * into the menu bar), and the explanation on the ordinary breadth/aggressiveness controls, which a
 * analysis-mode engine game locks because its participants take parameters through the game panel.
 */
final class EngineGameParameterControls {
  private EngineGameParameterControls() {}

  /** The "game parameters" button; hidden until {@link #refreshEntry} sees an analysis game. */
  static JFontButton createEntry() {
    JFontButton button =
        new JFontButton(Lizzie.resourceBundle.getString("EngineGameParameters.entry"));
    button.setFocusable(false);
    button.setVisible(false);
    button.setMargin(new Insets(0, 8, 0, 8));
    button.addActionListener(
        event -> EngineGameParametersDialog.open(SwingUtilities.getWindowAncestor(button)));
    return button;
  }

  /**
   * Adds the "game parameters" entry to {@code menu}. It is refreshed from {@code snapshot} each
   * time the menu opens, with the toolbar button's visibility and enablement rules.
   */
  static JMenuItem addMenuEntry(JMenu menu, Supplier<EngineGameSnapshot> snapshot) {
    JFontMenuItem item =
        new JFontMenuItem(Lizzie.resourceBundle.getString("EngineGameParameters.entry"));
    item.putClientProperty(AutoAnalyzeMenu.ACTION_PROPERTY, "game.parameters");
    item.setVisible(false);
    // The custom menu's source tree may be detached when search invokes it directly.
    item.addActionListener(event -> EngineGameParametersDialog.open(Lizzie.frame));
    menu.add(item);
    menu.addMenuListener(
        new MenuListener() {
          @Override
          public void menuSelected(MenuEvent event) {
            refreshEntry(item, snapshot.get());
          }

          @Override
          public void menuDeselected(MenuEvent event) {}

          @Override
          public void menuCanceled(MenuEvent event) {}
        });
    return item;
  }

  /**
   * Shows the button for an analysis-mode engine game and enables it while a game is running or
   * paused. Between games the snapshot carries no play mode, so the batch keeps its visibility.
   */
  static void refreshEntry(JComponent button, EngineGameSnapshot snapshot) {
    if (button == null) return;
    EngineGameView view = snapshot.view();
    if (view != null) button.setVisible(!view.genmove());
    else if (!snapshot.betweenGames()) button.setVisible(false);
    button.setEnabled(canOpen(snapshot));
  }

  /** Shared admission for the existing menu/toolbar entry and function-search navigation. */
  static boolean canOpen(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot.view();
    return snapshot.playing() && view != null && !view.genmove();
  }

  /**
   * Points the toolbar's breadth and aggressiveness controls at the game panel while an analysis
   * batch locks them, and restores their own tooltips afterwards. Enabled state is the caller's.
   */
  static void explainToolbarLock(
      boolean locked,
      JComponent chkWrn,
      JComponent txtWrn,
      JComponent chkPda,
      JComponent txtPda,
      JComponent customPda) {
    String lockedTip =
        locked ? Lizzie.resourceBundle.getString("EngineGameParameters.useGamePanel") : null;
    String wrnTip = locked ? lockedTip : Lizzie.resourceBundle.getString("Menu.chkWRN.toolTopText");
    String pdaTip = locked ? lockedTip : Lizzie.resourceBundle.getString("Menu.chkPDA.toolTopText");
    chkWrn.setToolTipText(wrnTip);
    txtWrn.setToolTipText(wrnTip);
    chkPda.setToolTipText(pdaTip);
    txtPda.setToolTipText(pdaTip);
    if (customPda != null) customPda.setToolTipText(lockedTip);
  }

  /**
   * Points the advanced-parameter dialog's breadth/aggressiveness editors at the game panel while
   * an analysis batch locks them, and clears that explanation afterwards. Enabled state is the
   * caller's.
   */
  static void explainAdvancedLock(boolean locked, JComponent... controls) {
    String tip =
        locked ? Lizzie.resourceBundle.getString("EngineGameParameters.useGamePanel") : null;
    for (JComponent control : controls) control.setToolTipText(tip);
  }
}
