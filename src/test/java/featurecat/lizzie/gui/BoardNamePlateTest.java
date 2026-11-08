package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class BoardNamePlateTest {
  @Test
  void longNamesYieldSpaceToWinratesSourceLabelsAndBatchScores() {
    BufferedImage image = new BufferedImage(900, 120, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    Font font = new Font("Dialog", Font.PLAIN, 24);
    for (int width : new int[] {350, 160, 65}) {
      String blackSuffix = "  56.3% (저장 분석)  12";
      String whitePrefix = "8  43.7% (저장 분석)  ";
      var geometry = new BoardNamePlate.Geometry(450, 10, 10, 30, width);
      var layout =
          BoardNamePlate.layout(
              g,
              font,
              font,
              "아주 긴 대국자 이름 AlphaGo",
              "長い対局者の名前 BetaGo",
              blackSuffix,
              whitePrefix,
              geometry);
      assertTrue(layout.black().text().startsWith("아주"));
      assertTrue(layout.white().text().endsWith("…"));
      assertTrue(layout.black().text().endsWith(blackSuffix));
      assertTrue(layout.white().text().startsWith(whitePrefix));
      assertTrue(
          g.getFontMetrics(layout.black().font()).stringWidth(layout.black().text()) <= width);
      assertTrue(
          g.getFontMetrics(layout.white().font()).stringWidth(layout.white().text()) <= width);
      assertTrue(layout.bounds().x >= 450 - 24 - width);
      assertTrue(layout.bounds().getMaxX() <= 450 + 25 + width);
    }
    g.dispose();
  }

  @Test
  void extremelyNarrowNamesRemainInsideTheBoardAndDoNotChangeGraphicsState() {
    BufferedImage image = new BufferedImage(240, 100, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    Font font = new Font("Dialog", Font.PLAIN, 24);
    g.setFont(font);
    g.setColor(Color.MAGENTA);
    BoardNamePlate.paint(
        g,
        font,
        font,
        "흑",
        "백",
        " 100.0% (Saved analysis)",
        "0.0% (Saved analysis) ",
        new BoardNamePlate.Geometry(120, 10, 6, 20, 1),
        true);
    assertEquals(Color.MAGENTA, g.getColor());
    assertEquals(font, g.getFont());
    g.dispose();
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) >>> 24) != 0) assertTrue(x >= 103 && x <= 138);
      }
    }
  }

  @Test
  void truncationKeepsCompleteUnicodeCodePointsAndShortNames() {
    Graphics2D g = new BufferedImage(200, 40, BufferedImage.TYPE_INT_ARGB).createGraphics();
    g.setFont(new Font("Dialog", Font.PLAIN, 18));
    String name = "한글😀𐐀ABC";
    for (int width = 0; width < 150; width++) {
      String fitted = BoardNamePlate.fitName(name, g.getFontMetrics(), width);
      assertTrue(g.getFontMetrics().stringWidth(fitted) <= width);
      assertTrue(fitted.codePoints().noneMatch(c -> c >= 0xD800 && c <= 0xDFFF));
    }
    assertEquals("이세돌", BoardNamePlate.fitName("이세돌", g.getFontMetrics(), 180));
    g.dispose();
  }
}
