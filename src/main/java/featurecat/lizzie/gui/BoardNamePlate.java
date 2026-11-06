package featurecat.lizzie.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;

/** The existing name/stone strip, with reserved space for readings and match scores. */
final class BoardNamePlate {
  record Geometry(int center, int top, int stoneRadius, int margin, int sideWidth) {}

  record Side(String text, Font font) {}

  record Layout(
      Side black, Side white, int blackLeft, int whiteLeft, int baseline, Rectangle bounds) {}

  private BoardNamePlate() {}

  static Layout layout(
      Graphics2D g,
      Font blackFont,
      Font whiteFont,
      String black,
      String white,
      String blackSuffix,
      String whitePrefix,
      Geometry geometry) {
    Side b = fit(g, blackFont, black, blackSuffix, geometry.sideWidth(), true);
    Side w = fit(g, whiteFont, white, whitePrefix, geometry.sideWidth(), false);
    FontMetrics bm = g.getFontMetrics(b.font());
    FontMetrics wm = g.getFontMetrics(w.font());
    int left = geometry.center() - bm.stringWidth(b.text()) - geometry.margin() * 4 / 5;
    int right = geometry.center() + geometry.margin() * 4 / 5 + 1;
    int ascent = Math.max(bm.getAscent(), wm.getAscent());
    int descent = Math.max(bm.getDescent(), wm.getDescent());
    int baseline =
        geometry.top()
            + geometry.stoneRadius() * 13 / 8
            + (Math.max(bm.getHeight(), wm.getHeight()) + 2) / 4;
    int boundsLeft =
        Math.max(left, geometry.center() - geometry.margin() * 4 / 5 - geometry.sideWidth());
    return new Layout(
        b,
        w,
        left,
        right,
        baseline,
        new Rectangle(
            boundsLeft,
            baseline - ascent,
            right + Math.min(geometry.sideWidth(), wm.stringWidth(w.text())) - boundsLeft,
            ascent + descent));
  }

  static Rectangle paint(
      Graphics2D graphics,
      Font blackFont,
      Font whiteFont,
      String black,
      String white,
      String blackSuffix,
      String whitePrefix,
      Geometry geometry,
      boolean blackToPlay) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      Layout layout =
          layout(g, blackFont, whiteFont, black, white, blackSuffix, whitePrefix, geometry);
      int r = geometry.stoneRadius();
      paintStone(g, geometry.center(), geometry.top(), r, !blackToPlay);
      paintStone(g, geometry.center(), geometry.top(), r, blackToPlay);
      Shape clip = g.getClip();
      g.clipRect(
          geometry.center() - geometry.margin() * 4 / 5 - geometry.sideWidth(),
          layout.bounds().y - 2,
          geometry.sideWidth(),
          layout.bounds().height + 4);
      paintName(g, layout.black(), layout.blackLeft(), layout.baseline(), false);
      g.setClip(clip);
      g.clipRect(
          layout.whiteLeft(),
          layout.bounds().y - 2,
          geometry.sideWidth(),
          layout.bounds().height + 4);
      paintName(g, layout.white(), layout.whiteLeft(), layout.baseline(), true);
      return layout.bounds();
    } finally {
      g.dispose();
    }
  }

  private static void paintStone(Graphics2D g, int center, int top, int radius, boolean black) {
    g.setColor(black ? Color.BLACK : Color.WHITE);
    g.fillOval(
        center - radius * (black ? 9 : 3) / 10, top + radius, radius * 5 / 4, radius * 5 / 4);
  }

  private static void paintName(Graphics2D g, Side side, int left, int baseline, boolean white) {
    if (side.text().isEmpty()) return;
    Shape outline =
        new TextLayout(side.text(), side.font(), g.getFontRenderContext())
            .getOutline(AffineTransform.getTranslateInstance(left, baseline));
    g.setColor(Color.BLACK);
    if (white) {
      g.setStroke(new BasicStroke(2));
      g.draw(outline);
      g.setColor(Color.WHITE);
    }
    g.fill(outline);
  }

  private static Side fit(
      Graphics2D g, Font font, String name, String fixed, int width, boolean black) {
    width = Math.max(0, width);
    // Keep an identifiable part of each name even when source labels consume most of the strip.
    int codePoints = name.codePointCount(0, name.length());
    String minimumName =
        codePoints <= 3 ? name : name.substring(0, name.offsetByCodePoints(0, 2)) + "…";
    while (font.getSize2D() > 1
        && g.getFontMetrics(font).stringWidth(minimumName + fixed) > width) {
      font = font.deriveFont(Math.max(1, font.getSize2D() - 1));
    }
    FontMetrics metrics = g.getFontMetrics(font);
    String fitted = fitName(name, metrics, Math.max(0, width - metrics.stringWidth(fixed)));
    return new Side(black ? fitted + fixed : fixed + fitted, font);
  }

  static String fitName(String name, FontMetrics metrics, int width) {
    if (metrics.stringWidth(name) <= width) return name;
    String ellipsis = "…";
    if (metrics.stringWidth(ellipsis) > width) return "";
    int low = 0;
    int high = name.codePointCount(0, name.length());
    while (low < high) {
      int middle = (low + high + 1) / 2;
      String candidate = name.substring(0, name.offsetByCodePoints(0, middle)) + ellipsis;
      if (metrics.stringWidth(candidate) <= width) low = middle;
      else high = middle - 1;
    }
    return name.substring(0, name.offsetByCodePoints(0, low)) + ellipsis;
  }
}
