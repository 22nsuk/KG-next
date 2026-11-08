package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import java.awt.Container;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JToolBar;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuggestionLimitFieldTest {
  @TempDir Path workDirectory;
  private final AtomicInteger relayouts = new AtomicInteger();
  private final AtomicInteger repaints = new AtomicInteger();

  @Test
  void theFieldIsHiddenUntilTheCustomToolbarMenuShowsIt() {
    Config config = config();
    SuggestionLimitField limit = field(config);
    assertFalse(limit.shown(), "hidden by default");

    limit.toggle.doClick();

    assertTrue(limit.shown());
    assertTrue(config.uiConfig.getBoolean(SuggestionLimitField.SHOWN_KEY), "remembered");
    assertEquals(1, relayouts.get());
    JToolBar toolbar = new JToolBar();
    limit.addTo(toolbar);
    assertEquals(1, toolbar.getComponentCount(), "label and editor wrap as one group");
    assertSame(limit.label.getParent(), limit.field.getParent());
    assertSame(toolbar, limit.field.getParent().getParent());
    assertEquals("候选数", limit.label.getText());
    assertEquals("10", limit.field.getText());
    assertEquals(0, repaints.get(), "showing the field changes nothing on the board");
  }

  @Test
  void anEditSetsHowManyCandidatesTheBoardShowsAtOnce() {
    Config config = config();
    SuggestionLimitField limit = field(config);
    limit.sync();

    limit.field.setText("3");
    assertEquals(3, config.limitMaxSuggestion);
    assertEquals(3, config.leelazConfig.getInt("limit-max-suggestion"));
    assertEquals(1, repaints.get());

    limit.field.setText("");
    assertEquals(3, config.limitMaxSuggestion, "an emptied field keeps the limit");
    limit.field.setText("0");
    assertEquals(0, config.limitMaxSuggestion, "0 shows every candidate");
    assertEquals(2, repaints.get());
  }

  @Test
  void theFieldShowsWhatTheSettingsDialogChanged() {
    Config config = config();
    SuggestionLimitField limit = field(config);
    limit.sync();

    config.limitMaxSuggestion = 5;
    config.showSuggestionLimitInMenu = true;
    limit.sync();

    assertEquals("5", limit.field.getText());
    assertTrue(limit.toggle.isSelected());
    assertEquals(0, repaints.get(), "showing the dialog's number is not an edit");
  }

  @Test
  void everyLanguageNamesTheFieldAndExplainsZero() {
    for (Locale locale :
        new Locale[] {
          Locale.US,
          Locale.SIMPLIFIED_CHINESE,
          Locale.TRADITIONAL_CHINESE,
          Locale.forLanguageTag("zh-HK"),
          Locale.JAPAN,
          Locale.KOREAN,
          Locale.forLanguageTag("th-TH")
        }) {
      ResourceBundle strings = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      for (String key : new String[] {"Menu.showSuggestionLimit", "Menu.lblSuggestionLimit"}) {
        assertFalse(strings.getString(key).isBlank(), key + " in " + locale);
      }
      assertTrue(
          strings.getString("Menu.txtSuggestionLimit.toolTip").contains("0"), locale.toString());
    }
  }

  @Test
  void invalidDraftsDoNotChangeTheDisplayLimitOrEnginePreferences() {
    Config config = config();
    config.leelazConfig.put("unrelated", "keep");
    SuggestionLimitField limit = field(config);
    for (String draft : new String[] {"", "-1", "2147483648", "abc"}) {
      limit.field.setText(draft);
      assertEquals(10, config.limitMaxSuggestion, draft);
      assertEquals(0, repaints.get(), draft);
      assertEquals("{\"unrelated\":\"keep\"}", config.leelazConfig.toString());
    }
  }

  @Test
  void labelAndEditorStayTogetherAndRetainAccessibleNamesAcrossLayouts() {
    SuggestionLimitField limit = field(config());
    for (Container toolbar : new Container[] {new JToolBar(), new TopHeaderPanel()}) {
      limit.addTo(toolbar);
      Container group = limit.field.getParent();
      Dimension size = group.getPreferredSize();
      group.setSize(size);
      group.doLayout();
      assertSame(group, limit.label.getParent());
      assertTrue(limit.field.getX() >= limit.label.getX() + limit.label.getWidth());
      assertTrue(limit.field.getWidth() >= limit.field.getPreferredSize().width);
      assertSame(limit.field, limit.label.getLabelFor());
      assertEquals(limit.label.getText(), limit.field.getAccessibleContext().getAccessibleName());
    }
  }

  @Test
  void displayPreferencesAndLimitSurviveTheExistingConfigSaveAndReload() throws Exception {
    Config config = ConfigTestHelper.createBootstrapped(workDirectory);
    SuggestionLimitField limit = field(config);
    ToolbarParameterToggles parameters =
        new ToolbarParameterToggles(
            config, ResourceBundle.getBundle("l10n.DisplayStrings", Locale.KOREAN), () -> {});
    assertFalse(limit.shown());
    limit.toggle.doClick(0);
    limit.field.setText("7");
    parameters.wrn.doClick(0);
    config.save();

    Config restored = ConfigTestHelper.createBootstrapped(workDirectory);
    assertTrue(restored.showSuggestionLimitInMenu);
    assertEquals(7, restored.limitMaxSuggestion);
    assertTrue(restored.showPDAInMenu);
    assertFalse(restored.showWRNInMenu);
  }

  private SuggestionLimitField field(Config config) {
    return new SuggestionLimitField(
        config,
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE),
        relayouts::incrementAndGet,
        repaints::incrementAndGet);
  }

  private Config config() {
    Config config = ConfigTestHelper.createForTests(workDirectory);
    config.uiConfig = new JSONObject();
    config.leelazConfig = new JSONObject();
    config.limitMaxSuggestion = 10;
    return config;
  }
}
