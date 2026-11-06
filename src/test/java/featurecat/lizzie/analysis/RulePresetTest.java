package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class RulePresetTest {
  @Test
  void selectionRequiresTheExactRulesAndKomiWhileHandicapOffersOnlyDistinctRules() {
    for (RulePreset preset : RulePreset.values()) {
      assertSame(preset, RulePreset.current(preset.rules(), preset.komi(), false).orElseThrow());
    }
    assertTrue(RulePreset.current(KataGoRules.parse("chinese-ogs").orElseThrow(), 7.5, false).isEmpty());
    assertTrue(RulePreset.current(RulePreset.JAPANESE.rules(), 7.5, false).isEmpty());
    assertTrue(RulePreset.current(null, 7.5, false).isEmpty());
    var custom = RulePreset.CHINESE.rules().toJson().put("experimentalRule", true);
    assertTrue(RulePreset.current(KataGoRules.fromJson(custom), 7.5, false).isEmpty());
    assertEquals(5, RulePreset.choices(true).size());
    assertSame(RulePreset.CHINESE,
        RulePreset.current(RulePreset.CHINESE.rules(), 0.5, true).orElseThrow());
  }

  @Test
  void everyLanguageLabelsActualPresetValuesWithoutClaimingAnEntireRulesEdition() {
    for (String language : List.of("en-US", "ko", "ja-JP", "zh-CN", "zh-TW", "zh-HK", "th-TH")) {
      ResourceBundle strings = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(language));
      for (RulePreset preset : RulePreset.values()) {
        String label = strings.getString(preset.labelKey());
        assertFalse(label.isBlank());
        assertTrue(label.contains(preset.komi() == 7.0 ? "7" : String.valueOf(preset.komi())));
        assertFalse(label.contains("2027"));
      }
      assertFalse(strings.getString("RulesQuickMenu.handicapKomi").isBlank());
      assertFalse(strings.getString("RulesQuickMenu.currentGame").isBlank());
    }
  }
}
