package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Dimension;
import java.util.ResourceBundle;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Edits the board's candidate display limit without changing the engine's search. */
final class SuggestionLimitField {
  static final String SHOWN_KEY = "show-suggestion-limit-in-menu";

  private final Config config;
  private final Runnable repaint;
  final JFontCheckBoxMenuItem toggle;
  final JFontLabel label;
  final JFontTextField field;
  private final JPanel panel = new JPanel(new BorderLayout(4, 0));
  private boolean syncing;

  SuggestionLimitField(Config config, ResourceBundle strings, Runnable relayout, Runnable repaint) {
    this.config = config;
    this.repaint = repaint;
    toggle = new JFontCheckBoxMenuItem(strings.getString("Menu.showSuggestionLimit"));
    toggle.addActionListener(
        e -> {
          config.showSuggestionLimitInMenu = toggle.isSelected();
          config.uiConfig.put(SHOWN_KEY, toggle.isSelected());
          relayout.run();
        });
    label = new JFontLabel(strings.getString("Menu.lblSuggestionLimit"));
    field = new JFontTextField();
    field.setDocument(new IntDocument());
    field.setColumns(3);
    field.setHorizontalAlignment(JFontTextField.RIGHT);
    String hint = strings.getString("Menu.txtSuggestionLimit.toolTip");
    field.setToolTipText(hint);
    AccessibilitySupport.labelFor(label, field, hint);
    panel.setOpaque(false);
    panel.add(label, BorderLayout.WEST);
    panel.add(field, BorderLayout.CENTER);
    field
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                apply();
              }

              public void removeUpdate(DocumentEvent e) {
                apply();
              }

              public void changedUpdate(DocumentEvent e) {
                apply();
              }
            });
    sync();
  }

  boolean shown() {
    return config.showSuggestionLimitInMenu;
  }

  /** Refreshes after a settings-dialog edit without publishing an intermediate empty value. */
  void sync() {
    toggle.setSelected(shown());
    String limit = String.valueOf(config.limitMaxSuggestion);
    if (limit.equals(field.getText())) return;
    syncing = true;
    try {
      field.setText(limit);
    } finally {
      syncing = false;
    }
  }

  void addTo(Container toolbar) {
    sync();
    Dimension size = panel.getPreferredSize();
    panel.setMaximumSize(new Dimension(size.width, Math.max(size.height, Config.menuHeight)));
    toolbar.add(panel);
  }

  private void apply() {
    if (syncing) return;
    // Empty or invalid drafts retain the previous limit; zero deliberately means all candidates.
    int limit = Utils.parseTextToInt(field, config.limitMaxSuggestion);
    if (limit < 0 || limit == config.limitMaxSuggestion) return;
    config.limitMaxSuggestion = limit;
    config.leelazConfig.put("limit-max-suggestion", limit);
    repaint.run();
  }
}
