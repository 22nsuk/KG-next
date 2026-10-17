package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.KataGoRules;
import java.awt.Container;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ResourceBundle;
import javax.swing.AbstractButton;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import org.json.JSONObject;

/**
 * Rules editor rows shared by the KataGo and analysis rules dialogs. A preset button fills every
 * rule field with that preset; territory scoring locks the area-only fields.
 */
final class KataRulesEditor {
  private int bottom;
  private int width;

  final JFontRadioButton rdoArea;
  final JFontRadioButton rdoTerritory;
  final JFontRadioButton rdoSimpleKo;
  final JFontRadioButton rdoPositionKo;
  final JFontRadioButton rdoSituationalKo;
  final JFontRadioButton rdoSuicide;
  final JFontRadioButton rdoNoSuicide;
  final JFontRadioButton rdoNoTax;
  final JFontRadioButton rdoSeKiTax;
  final JFontRadioButton rdoAllTax;
  final JFontRadioButton rdoNoHandicapKomi;
  final JFontRadioButton rdoHandicapKomiN;
  final JFontRadioButton rdoHandicapKomiN1;
  final JFontRadioButton rdoButtonGo;
  final JFontRadioButton rdoNoButtonGo;
  final JFontRadioButton rdoFriendlyPass;
  final JFontRadioButton rdoNoFriendlyPass;
  final JFontButton btnChnRule;
  final JFontButton btnJpnRule;
  final JFontButton btnChnOldRule;
  final JFontButton btnTTRule;
  final JFontButton btnGoeRule;

  private final Container pane;
  private Runnable changeListener = () -> {};

  KataRulesEditor(Container pane, ResourceBundle strings) {
    this.pane = pane;
    boolean small = Lizzie.config.isFrameFontSmall();
    boolean middle = Lizzie.config.isFrameFontMiddle();
    int thirdColumn = small || middle ? 401 : 450;
    int secondColumnWidth = small || middle ? 119 : 130;

    label(strings, "SetKataRules.lblClassicRules", 10, 9, 91);
    btnChnRule = presetButton(strings, "SetKataRules.btnChnRule", "chinese", 99, 93);
    btnJpnRule = presetButton(strings, "SetKataRules.btnJpnRule", "japanese", 202, 93);
    btnChnOldRule = presetButton(strings, "SetKataRules.btnChnOldRule", "stone-scoring", 305, 101);
    int ttWidth = small || middle ? 142 : 180;
    btnTTRule = presetButton(strings, "SetKataRules.btnTTRule", "tromp-taylor", 416, ttWidth);
    btnGoeRule =
        presetButton(
            strings,
            "SetKataRules.btnGoeRule",
            "goe",
            416 + ttWidth + 10,
            small ? 110 : (middle ? 120 : 150));

    label(strings, "SetKataRules.lblScoringRule", 21, 48, 146);
    rdoArea = radio(strings, "SetKataRules.rdoArea", 173, 50, 105);
    rdoTerritory = radio(strings, "SetKataRules.rdoTerritory", 280, 50, 119);
    group(rdoArea, rdoTerritory);
    rdoArea.addActionListener(event -> updateScoringLocks());
    rdoTerritory.addActionListener(event -> updateScoringLocks());

    label(strings, "SetKataRules.lblKoRule", 21, 85, 129);
    rdoSimpleKo = radio(strings, "SetKataRules.rdoSimpleKo", 173, 87, 105);
    rdoPositionKo = radio(strings, "SetKataRules.rdoPositionKo", 280, 87, secondColumnWidth);
    rdoSituationalKo = radio(strings, "SetKataRules.rdoSituationalKo", thirdColumn, 87, 295);
    group(rdoSimpleKo, rdoPositionKo, rdoSituationalKo);

    label(strings, "SetKataRules.lblMultiStoneSuicide", 21, 122, 146);
    rdoSuicide = radio(strings, "SetKataRules.rdoSuicide", 173, 124, 105);
    rdoNoSuicide = radio(strings, "SetKataRules.rdoNoSuicide", 280, 124, 119);
    group(rdoSuicide, rdoNoSuicide);

    label(strings, "SetKataRules.lblTaxRule", 21, 159, 146);
    rdoNoTax = radio(strings, "SetKataRules.rdoNoTax", 173, 161, 84);
    rdoSeKiTax = radio(strings, "SetKataRules.rdoSeKiTax", 280, 161, secondColumnWidth);
    rdoAllTax = radio(strings, "SetKataRules.rdoAllTax", thirdColumn, 161, 143);
    group(rdoNoTax, rdoSeKiTax, rdoAllTax);

    label(strings, "SetKataRules.lblWhiteHandicapBonus", 21, 196, 153);
    rdoNoHandicapKomi = radio(strings, "SetKataRules.rdoNoHandicapKomi", 173, 198, 105);
    rdoHandicapKomiN = radio(strings, "SetKataRules.rdoHandicapKomiN", 280, 198, 119);
    rdoHandicapKomiN1 = radio(strings, "SetKataRules.rdoHandicapKomiN1", thirdColumn, 198, 143);
    group(rdoNoHandicapKomi, rdoHandicapKomiN, rdoHandicapKomiN1);

    label(strings, "SetKataRules.lblHasButton", 21, 233, 256);
    rdoButtonGo = radio(strings, "SetKataRules.rdoButtonGo", 280, 235, 105);
    rdoNoButtonGo = radio(strings, "SetKataRules.rdoNoButtonGo", thirdColumn, 235, 143);
    group(rdoButtonGo, rdoNoButtonGo);

    label(strings, "SetKataRules.lblFriendlyPassOk", 21, 270, 256);
    rdoFriendlyPass = radio(strings, "SetKataRules.rdoButtonGo", 280, 272, 105);
    rdoNoFriendlyPass = radio(strings, "SetKataRules.rdoNoButtonGo", thirdColumn, 272, 143);
    group(rdoFriendlyPass, rdoNoFriendlyPass);
    layoutForContent();
  }

  int bottom() { return bottom; }

  int width() { return width; }

  /** Runs after any rule choice changes, including preset buttons and programmatic readbacks. */
  void onChange(Runnable listener) {
    changeListener = listener;
  }

  /** Measure translated text and native control insets instead of trusting pixel-sized columns. */
  private void layoutForContent() {
    List<List<Component>> rows = new ArrayList<>();
    for (int i = 0; i < 8; i++) rows.add(new ArrayList<>());
    for (Component component : pane.getComponents()) {
      int row = component.getY() < 40 ? 0 : 1 + (component.getY() - 48) / 37;
      rows.get(row).add(component);
    }
    rows.forEach(row -> row.sort(Comparator.comparingInt(Component::getX)));
    int[] columns = new int[4];
    for (List<Component> row : rows.subList(1, rows.size())) {
      for (int i = 0; i < row.size(); i++) {
        columns[i] = Math.max(columns[i], row.get(i).getPreferredSize().width);
      }
    }
    int y = 12;
    for (int index = 0; index < rows.size(); index++) {
      List<Component> row = rows.get(index);
      if (row.get(0) instanceof JLabel label && row.size() > 1) {
        label.setLabelFor(row.get(1));
      }
      int height = row.stream().mapToInt(c -> c.getPreferredSize().height).max().orElse(27);
      int x = 16;
      for (int i = 0; i < row.size(); i++) {
        Component component = row.get(i);
        Dimension size = component.getPreferredSize();
        int cellWidth = index == 0 ? size.width : columns[i];
        component.setBounds(x, y + (height - size.height) / 2, cellWidth, size.height);
        x += cellWidth + 14;
      }
      width = Math.max(width, x + 2);
      y += height + 12;
    }
    bottom = y;
  }

  void applyPreset(String name) {
    apply(KataGoRules.parse(name).orElseThrow().toJson());
  }

  /** Shows a rules JSON; absent booleans follow KataGo's JSON defaults (false). */
  void apply(JSONObject rules) {
    if (rules == null) {
      return;
    }
    select(rules.optBoolean(KataGoRules.HAS_BUTTON, false), rdoButtonGo, rdoNoButtonGo);
    select(rules.optBoolean(KataGoRules.SUICIDE, false), rdoSuicide, rdoNoSuicide);
    select(
        rules.optBoolean(KataGoRules.FRIENDLY_PASS_OK, false), rdoFriendlyPass, rdoNoFriendlyPass);
    switch (rules.optString(KataGoRules.KO, "")) {
      case "SIMPLE" -> rdoSimpleKo.setSelected(true);
      case "POSITIONAL" -> rdoPositionKo.setSelected(true);
      case "SITUATIONAL" -> rdoSituationalKo.setSelected(true);
      default -> {}
    }
    switch (rules.optString(KataGoRules.SCORING, "")) {
      case "AREA" -> rdoArea.setSelected(true);
      case "TERRITORY" -> rdoTerritory.setSelected(true);
      default -> {}
    }
    switch (rules.optString(KataGoRules.WHITE_HANDICAP_BONUS, "")) {
      case "0" -> rdoNoHandicapKomi.setSelected(true);
      case "N" -> rdoHandicapKomiN.setSelected(true);
      case "N-1" -> rdoHandicapKomiN1.setSelected(true);
      default -> {}
    }
    switch (rules.optString(KataGoRules.TAX, "")) {
      case "NONE" -> rdoNoTax.setSelected(true);
      case "SEKI" -> rdoSeKiTax.setSelected(true);
      case "ALL" -> rdoAllTax.setSelected(true);
      default -> {}
    }
    updateScoringLocks();
  }

  /** Edited fields over {@code base}, keeping fields the editor does not show. */
  KataGoRules overlay(KataGoRules base) {
    if (base == null) {
      return KataGoRules.fromJson(toJson());
    }
    return base.overlayEditor(
        scoring(),
        ko(),
        rdoSuicide.isSelected(),
        tax(),
        whiteHandicapBonus(),
        rdoButtonGo.isSelected(),
        rdoFriendlyPass.isSelected());
  }

  /** Edited fields as KataGo rules JSON; unselected choice fields are omitted. */
  JSONObject toJson() {
    JSONObject json = new JSONObject();
    putIfSelected(json, KataGoRules.SCORING, scoring());
    putIfSelected(json, KataGoRules.KO, ko());
    json.put(KataGoRules.SUICIDE, rdoSuicide.isSelected());
    putIfSelected(json, KataGoRules.TAX, tax());
    putIfSelected(json, KataGoRules.WHITE_HANDICAP_BONUS, whiteHandicapBonus());
    json.put(KataGoRules.HAS_BUTTON, rdoButtonGo.isSelected());
    json.put(KataGoRules.FRIENDLY_PASS_OK, rdoFriendlyPass.isSelected());
    return json;
  }

  /** Area-only controls are disabled under territory scoring, without rewriting imported values. */
  private void updateScoringLocks() {
    boolean territory = rdoTerritory.isSelected();
    rdoButtonGo.setEnabled(!territory);
    rdoNoButtonGo.setEnabled(!territory);
    rdoFriendlyPass.setEnabled(!territory);
    rdoNoFriendlyPass.setEnabled(!territory);
  }

  private String scoring() {
    return rdoArea.isSelected() ? "AREA" : rdoTerritory.isSelected() ? "TERRITORY" : "";
  }

  private String ko() {
    return rdoSimpleKo.isSelected()
        ? "SIMPLE"
        : rdoPositionKo.isSelected() ? "POSITIONAL" : rdoSituationalKo.isSelected() ? "SITUATIONAL" : "";
  }

  private String tax() {
    return rdoNoTax.isSelected()
        ? "NONE"
        : rdoSeKiTax.isSelected() ? "SEKI" : rdoAllTax.isSelected() ? "ALL" : "";
  }

  private String whiteHandicapBonus() {
    return rdoNoHandicapKomi.isSelected()
        ? "0"
        : rdoHandicapKomiN.isSelected() ? "N" : rdoHandicapKomiN1.isSelected() ? "N-1" : "";
  }

  private static void putIfSelected(JSONObject json, String key, String value) {
    if (!value.isEmpty()) {
      json.put(key, value);
    }
  }

  private static void select(boolean yes, AbstractButton yesButton, AbstractButton noButton) {
    (yes ? yesButton : noButton).setSelected(true);
  }

  private void label(ResourceBundle strings, String key, int x, int y, int width) {
    JFontLabel label = new JFontLabel(strings.getString(key));
    label.setBounds(x, y, width, 27);
    pane.add(label);
  }

  private JFontRadioButton radio(ResourceBundle strings, String key, int x, int y, int width) {
    JFontRadioButton radio = new JFontRadioButton(strings.getString(key));
    radio.setBounds(x, y, width, 23);
    radio.addItemListener(event -> changeListener.run());
    pane.add(radio);
    return radio;
  }

  private JFontButton presetButton(
      ResourceBundle strings, String key, String preset, int x, int width) {
    JFontButton button = new JFontButton(strings.getString(key));
    button.setMargin(new Insets(0, 0, 0, 0));
    button.addActionListener(event -> applyPreset(preset));
    button.setBounds(x, 11, width, 23);
    pane.add(button);
    return button;
  }

  private static void group(AbstractButton... buttons) {
    ButtonGroup group = new ButtonGroup();
    for (AbstractButton button : buttons) {
      group.add(button);
    }
  }
}
