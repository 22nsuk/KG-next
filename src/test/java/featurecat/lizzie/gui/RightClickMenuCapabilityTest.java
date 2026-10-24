package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.analysis.Leelaz.MoveFocusCapability;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.PropertyResourceBundle;

class RightClickMenuCapabilityTest {
  @Test
  void onlyConfirmedUnsupportedEnginesShowUpgradeGuidance() {
    for (boolean paused : new boolean[] {false, true}) {
      assertEquals(
          "RightClickMenu.trackPoint.upgradeRequired",
          RightClickMenu.trackingUnavailableKey(MoveFocusCapability.UNSUPPORTED, paused, false));
      assertEquals(
          "RightClickMenu.trackPoint.upgradeTooltip",
          RightClickMenu.trackingUnavailableKey(MoveFocusCapability.UNSUPPORTED, paused, true));
    }
    for (MoveFocusCapability capability : MoveFocusCapability.values()) {
      if (capability == MoveFocusCapability.UNSUPPORTED) continue;
      assertEquals(
          "RightClickMenu.trackPoint.requiresCapability",
          RightClickMenu.trackingUnavailableKey(capability, false, false));
      assertEquals(
          "RightClickMenu.trackPoint.unavailableTooltip",
          RightClickMenu.trackingUnavailableKey(capability, false, true));
    }
  }

  @Test
  void aSupportedEngineThatIsNotAnalysingAsksToResumeAnalysis() {
    assertEquals(
        "RightClickMenu.trackPoint.paused",
        RightClickMenu.trackingUnavailableKey(MoveFocusCapability.SUPPORTED, true, false));
    assertEquals(
        "RightClickMenu.trackPoint.pausedTooltip",
        RightClickMenu.trackingUnavailableKey(MoveFocusCapability.SUPPORTED, true, true));
    // Resuming would not help an engine whose support is still unknown.
    assertEquals(
        "RightClickMenu.trackPoint.requiresCapability",
        RightClickMenu.trackingUnavailableKey(MoveFocusCapability.UNKNOWN, true, false));
  }

  @Test
  void everyUnavailableLabelKeepsTheItemsNameInEveryLanguage() throws Exception {
    // A disabled item is still where users look for it, so it keeps its name and adds the reason.
    for (String bundle :
        List.of("", "_en_US", "_ja_JP", "_ko", "_th_TH", "_zh_CN", "_zh_HK", "_zh_TW")) {
      PropertyResourceBundle strings;
      try (InputStream in =
          RightClickMenu.class.getResourceAsStream(
              "/l10n/DisplayStrings" + bundle + ".properties")) {
        strings = new PropertyResourceBundle(new InputStreamReader(in, StandardCharsets.UTF_8));
      }
      String name = strings.getString("RightClickMenu.trackPoint");
      for (String reason : List.of("upgradeRequired", "paused", "requiresCapability")) {
        String label = strings.getString("RightClickMenu.trackPoint." + reason);
        assertTrue(label.startsWith(name), bundle + " " + reason + ": " + label);
      }
      for (String hint : List.of("upgradeTooltip", "pausedTooltip", "unavailableTooltip")) {
        assertTrue(
            !strings.getString("RightClickMenu.trackPoint." + hint).isBlank(), bundle + " " + hint);
      }
    }
  }
}
