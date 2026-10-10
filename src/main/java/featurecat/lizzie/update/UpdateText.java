package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;

final class UpdateText {
  private UpdateText() {}

  static String tr(String key, String chineseText, String englishText) {
    try {
      if (Lizzie.resourceBundle != null && Lizzie.resourceBundle.containsKey(key)) {
        return Lizzie.resourceBundle.getString(key);
      }
    } catch (Exception ignored) {
    }
    return Lizzie.config != null && Lizzie.config.isChinese ? chineseText : englishText;
  }
}
