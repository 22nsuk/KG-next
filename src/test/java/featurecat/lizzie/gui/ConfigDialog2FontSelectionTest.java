package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.Font;
import org.junit.jupiter.api.Test;

class ConfigDialog2FontSelectionTest {
  @Test
  void savingAutomaticFontChoicesKeepsResolvedKoreanDefaultsAndUpdatesRuntimeNames()
      throws Exception {
    Config previousConfig = Lizzie.config;
    String previousDefault = Config.sysDefaultFontName;
    Font previousUi = LizzieFrame.uiFont;
    Font previousBoard = LizzieFrame.playoutsFont;
    Font previousWinrate = LizzieFrame.winrateFont;
    try {
      var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      Lizzie.config = (Config) ((sun.misc.Unsafe) field.get(null)).allocateInstance(Config.class);
      Lizzie.config.useLanguage = AppLocale.KOREAN.configValue();
      Config.sysDefaultFontName =
          featurecat.lizzie.util.LocaleFontSupport.resolveDefaultFontName("Dialog", java.util.Locale.KOREAN);
      ConfigDialog2.applyFontSelections("KG-next Default", "Lizzie Default", "Lizzie默认");
      assertEquals(Config.sysDefaultFontName, Lizzie.config.uiFontName);
      assertEquals(Config.sysDefaultFontName, Lizzie.config.fontName);
      assertEquals(Config.sysDefaultFontName, LizzieFrame.uiFont.getName());
      assertEquals(Config.sysDefaultFontName, LizzieFrame.playoutsFont.getName());
      assertEquals("Open Sans Semibold", LizzieFrame.winrateFont.getFontName(java.util.Locale.US));

      ConfigDialog2.applyFontSelections("Dialog", "Dialog", "Dialog");
      assertEquals("Dialog", Lizzie.config.uiFontName);
      assertEquals("Dialog", Lizzie.config.fontName);
      assertEquals("Dialog", Lizzie.config.winrateFontName);
      assertEquals("Dialog", LizzieFrame.winrateFont.getName());
    } finally {
      Lizzie.config = previousConfig;
      Config.sysDefaultFontName = previousDefault;
      LizzieFrame.uiFont = previousUi;
      LizzieFrame.playoutsFont = previousBoard;
      LizzieFrame.winrateFont = previousWinrate;
    }
  }
}
