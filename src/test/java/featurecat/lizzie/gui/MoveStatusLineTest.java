package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.rules.BoardData;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class MoveStatusLineTest {
  @Test
  void emphasisUsesTheSameSideAndScoreAsTheWrittenChange() {
    ResourceBundle en = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    BoardData previous = measured(true, 60, 2);
    BoardData current = measured(false, 50, 3);
    var change = MoveStatusText.previousMoveStatus(en, current, previous, true);
    assertEquals("Prev. move -10.0% -5.0pts", change.text());
    assertEquals(MoveStatusLine.LOSS, MoveStatusLine.color(change.emphasisChange(), Color.WHITE));
    current.scoreMeanIsBlackPerspective = true;
    current.scoreMean = 4;
    change = MoveStatusText.previousMoveStatus(en, current, previous, true);
    assertEquals("Prev. move -10.0% +2.0pts", change.text());
    assertEquals(MoveStatusLine.GAIN, MoveStatusLine.color(change.emphasisChange(), Color.WHITE));
    change = MoveStatusText.previousMoveStatus(en, current, previous, false);
    assertEquals(MoveStatusLine.LOSS, MoveStatusLine.color(change.emphasisChange(), Color.WHITE));
    assertEquals(Color.WHITE, MoveStatusLine.color(-0.04, Color.WHITE));
    assertEquals(Color.WHITE, MoveStatusLine.color(Double.NaN, Color.WHITE));
  }

  @Test
  void engineGameTwoMoveComparisonKeepsTheLastPlayersPerspective() {
    for (boolean black : new boolean[] {true, false}) {
      BoardData earlier = measured(black, 50, 0);
      BoardData current = measured(black, 60, 0);
      assertEquals(-10, MoveStatusText.engineGameWinrateChange(current, earlier));
      current.winrate = 40;
      assertEquals(10, MoveStatusText.engineGameWinrateChange(current, earlier));
      earlier.blackToPlay = !black;
      earlier.winrate = 70;
      assertEquals(-10, MoveStatusText.engineGameWinrateChange(current, earlier));
    }
  }

  @Test
  void sameTurnSetupPositionsKeepTheirOwnSummaryPerspective() {
    ResourceBundle en = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    var change =
        MoveStatusText.previousMoveStatus(en, measured(false, 65, 3), measured(false, 60, 2), true);
    assertEquals("Prev. move +5.0% +1.0pts", change.text());
    assertEquals(MoveStatusLine.GAIN, MoveStatusLine.color(change.emphasisChange(), Color.WHITE));
  }

  @Test
  void multilingualRowsFitAndNeverPaintOverAdjacentPanelsAtEitherScale() {
    for (Locale locale :
        new Locale[] {Locale.US, Locale.KOREAN, Locale.JAPAN, Locale.forLanguageTag("th-TH")}) {
      ResourceBundle strings = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      String lead =
          strings.getString("AnalysisReadability.saved")
              + " · "
              + MoveStatusText.lead(strings, -7.3);
      String previous = MoveStatusText.previousMove(strings, 12, -12.3, -4.5);
      for (int width : new int[] {600, 240, 80, 2}) {
        for (int scale : new int[] {1, 2}) {
          BufferedImage image =
              new BufferedImage(660 * scale, 90 * scale, BufferedImage.TYPE_INT_ARGB);
          Graphics2D g = image.createGraphics();
          g.scale(scale, scale);
          Font font = new Font("Dialog", Font.PLAIN, 18);
          g.setFont(font);
          g.setColor(Color.WHITE);
          Rectangle clip = new Rectangle(0, 0, 650, 80);
          g.setClip(clip);
          var layout = MoveStatusLine.layout(g, font, 20, 40, 28, width, 18, lead, previous);
          assertTrue(layout.previousFont().isBold());
          assertTrue(layout.previousFont().getSize2D() <= 27);
          if (width == 240) {
            assertTrue(
                layout.leadBaseline() < layout.baseline(), "Narrow panels use separate lines");
            assertTrue(layout.previousFont().getSize2D() > layout.leadFont().getSize2D());
          }
          if (width >= 240) {
            assertTrue(
                layout.previousX() + g.getFontMetrics(layout.previousFont()).stringWidth(previous)
                    <= 20 + width);
          }
          MoveStatusLine.draw(
              g,
              font,
              20,
              40,
              28,
              width,
              18,
              lead,
              new MoveStatusText.PreviousMove(previous, -12.3, -4.5));
          assertEquals(Color.WHITE, g.getColor());
          assertEquals(font, g.getFont());
          assertEquals(clip, g.getClipBounds());
          g.dispose();
          for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
              if ((image.getRGB(x, y) >>> 24) != 0) {
                assertTrue(x >= 20 * scale && x < (20 + width) * scale);
                assertTrue(y >= 26 * scale && y < 54 * scale);
              }
            }
          }
        }
      }
    }
  }

  private static BoardData measured(boolean black, double winrate, double score) {
    BoardData data = BoardData.empty(19, 19);
    data.analysisHeaderSlots = 3;
    data.setPlayouts(100);
    data.blackToPlay = black;
    data.winrate = winrate;
    data.isKataData = true;
    data.scoreMean = score;
    return data;
  }
}
