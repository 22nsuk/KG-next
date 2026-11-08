package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.io.IOException;
import java.util.ResourceBundle;
import org.json.JSONObject;

/** Opt-in current-position readings; absent analysis never borrows an earlier position's value. */
final class NameWinrates {
  static final String SHOWN_KEY = "show-name-winrates";

  private NameWinrates() {}

  static boolean shown(JSONObject settings) {
    return settings != null && settings.optBoolean(SHOWN_KEY, false);
  }

  static void toggle(Config config) throws IOException {
    config.updateConfigSections((ui, leelaz) -> ui.put(SHOWN_KEY, !shown(ui)));
  }

  static String value(WinrateReading reading, boolean black, ResourceBundle strings) {
    String value = reading.percent(black);
    String source = reading.known() ? reading.sourceText(strings) : "";
    return source.isEmpty() ? value : value + " (" + source + ")";
  }
}
