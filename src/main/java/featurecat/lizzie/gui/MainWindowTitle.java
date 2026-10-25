package featurecat.lizzie.gui;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.util.Utils;

/**
 * Joins the parts of the main window's title: "app - [evaluation] engine speed - players (result) -
 * file". Empty parts are left out together with their separators, so a missing evaluation or speed
 * never runs the app and engine names together ("next-devKataGo - 搜索/秒").
 */
final class MainWindowTitle {
  private MainWindowTitle() {}

  static String evaluation(BoardData data, boolean withKomi) {
    if (!MoveStatusText.hasWinrate(data)) return "";
    String text = MoveStatusText.decimal(data.blackToPlay ? data.winrate : 100 - data.winrate);
    if (MoveStatusText.hasScore(data)) {
      double score = MoveStatusText.blackScore(data) + (withKomi ? data.getKomi() : 0);
      if (Double.isFinite(score)) text += " " + MoveStatusText.decimal(score);
    }
    return "[" + text + " " + Utils.getPlayoutsString(data.getPlayouts()) + "]";
  }

  static String compose(
      String app,
      String evaluation,
      String engine,
      String speed,
      String players,
      String result,
      String file) {
    return join(" - ", app, join(" ", evaluation, engine, speed), join(" ", players, result), file);
  }

  static String join(String separator, String... parts) {
    StringBuilder joined = new StringBuilder();
    for (String part : parts) {
      if (part == null || part.isBlank()) {
        continue;
      }
      if (joined.length() > 0) {
        joined.append(separator);
      }
      joined.append(part.trim());
    }
    return joined.toString();
  }
}
