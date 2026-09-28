package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import java.awt.Component;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class B11SpeedNoticePanelTest {
  @Test
  void allLanguagesWrapAtDesktopAndNarrowWidthsAtEveryRequestedFontScale() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (String locale :
              List.of("en-US", "zh-CN", "zh-TW", "zh-HK", "ja-JP", "ko", "th-TH")) {
            ResourceBundle strings =
                ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(locale));
            for (float scale : List.of(1f, 1.25f, 1.5f, 2f)) {
              for (int width : List.of(780, 560, 320)) {
                B11SpeedNoticePanel panel =
                    new B11SpeedNoticePanel(
                        strings, new Font(Font.DIALOG, Font.PLAIN, Math.round(13 * scale)));
                panel.setSize(width, 1000);
                panel.setSize(width, panel.getPreferredSize().height);
                panel.doLayout();
                Rectangle bounds = new Rectangle(0, 0, panel.getWidth(), panel.getHeight());
                assertFalse(panel.getAccessibleContext().getAccessibleName().isBlank());
                assertFalse(panel.getAccessibleContext().getAccessibleDescription().isBlank());
                for (Component component : panel.getComponents()) {
                  assertTrue(bounds.contains(component.getBounds()), locale + " " + scale);
                  JTextArea area = (JTextArea) component;
                  assertTrue(area.getLineWrap());
                  assertTrue(area.getHeight() >= area.getPreferredSize().height);
                  assertFalse(area.isEditable());
                }
              }
            }
          }
        });
  }

  @Test
  void titleKeepsSpeedAndPlacesNoticeBeforeTheGameNameWithoutAccumulatingIt() throws Exception {
    Leelaz b11 =
        new Leelaz("katago gtp") {
          @Override
          public boolean usesB11ForSpeedNotice() {
            return true;
          }
        };
    for (int i = 0; i < 2; i++) {
      StringBuilder title = new StringBuilder("850 visits/s");
      LizzieFrame.appendSpeedModelNotice(title, b11);
      title.append(" - review.sgf");
      String hint = Lizzie.resourceBundle.getString("B11SpeedNotice.title");
      assertTrue(title.indexOf(hint) > title.indexOf("visits/s"));
      assertTrue(title.indexOf(hint) < title.indexOf("review.sgf"));
    }
    StringBuilder other = new StringBuilder("850 visits/s");
    LizzieFrame.appendSpeedModelNotice(other, new Leelaz("B11-name-only"));
    LizzieFrame.appendSpeedModelNotice(other, null);
    assertEquals("850 visits/s", other.toString());
  }
}
