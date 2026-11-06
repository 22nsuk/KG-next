package featurecat.lizzie.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.font.TextAttribute;
import java.util.Map;

/** Fits the existing lead and emphasized previous-move summary within their own panel row. */
final class MoveStatusLine {
  static final Color GAIN = new Color(0x7C, 0xD9, 0x92);
  static final Color LOSS = new Color(0xFF, 0x7A, 0x6E);
  private static final String GAP = "  ";

  record Layout(
      Font leadFont,
      Font previousFont,
      int previousX,
      int leadBaseline,
      int baseline,
      Rectangle bounds) {}

  private MoveStatusLine() {}

  static void draw(
      Graphics2D graphics,
      Font base,
      int x,
      int centerY,
      int maxHeight,
      int maxWidth,
      int maxSize,
      String lead,
      MoveStatusText.PreviousMove previous) {
    if (maxHeight <= 0 || maxWidth <= 0) return;
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      Layout layout =
          layout(g, base, x, centerY, maxHeight, maxWidth, maxSize, lead, previous.text());
      g.clip(layout.bounds());
      if (!lead.isEmpty()) {
        g.setFont(layout.leadFont());
        g.drawString(lead, x, layout.leadBaseline());
      }
      if (!previous.text().isEmpty()) {
        g.setFont(layout.previousFont());
        g.setColor(color(previous.emphasisChange(), g.getColor()));
        g.drawString(previous.text(), layout.previousX(), layout.baseline());
      }
    } finally {
      g.dispose();
    }
  }

  static Layout layout(
      Graphics2D g,
      Font base,
      int x,
      int centerY,
      int maxHeight,
      int maxWidth,
      int maxSize,
      String lead,
      String previous) {
    String leadText = lead.isEmpty() || previous.isEmpty() ? lead : lead + GAP;
    Font leadFont = fit(g, base, Font.PLAIN, leadText, maxHeight, maxWidth, maxSize);
    Font previousFont = fit(g, base, Font.BOLD, previous, maxHeight, maxWidth, maxSize * 1.5f);
    Rectangle bounds =
        new Rectangle(x, centerY - maxHeight / 2, Math.max(0, maxWidth), Math.max(0, maxHeight));
    if (!lead.isEmpty()
        && !previous.isEmpty()
        && maxHeight >= 20
        && width(g, leadFont, leadText) + width(g, previousFont, previous) > maxWidth) {
      int leadHeight = maxHeight * 2 / 5;
      int previousHeight = maxHeight - leadHeight;
      leadFont = fit(g, base, Font.PLAIN, lead, leadHeight, maxWidth, maxSize);
      previousFont = fit(g, base, Font.BOLD, previous, previousHeight, maxWidth, maxSize * 1.5f);
      return new Layout(
          leadFont,
          previousFont,
          x,
          baseline(g, leadFont, bounds.y, leadHeight),
          baseline(g, previousFont, bounds.y + leadHeight, previousHeight),
          bounds);
    }
    while (width(g, leadFont, leadText) + width(g, previousFont, previous) > maxWidth
        && (leadFont.getSize2D() > 1 || previousFont.getSize2D() > 1)) {
      if (leadFont.getSize2D() > 1
          && (previousFont.getSize2D() <= leadFont.getSize2D() * 1.2f
              || previousFont.getSize2D() <= 1)) {
        leadFont = leadFont.deriveFont(Math.max(1, leadFont.getSize2D() - 1));
      } else {
        previousFont = previousFont.deriveFont(Math.max(1, previousFont.getSize2D() - 1));
      }
    }
    FontMetrics leadMetrics = g.getFontMetrics(leadFont);
    FontMetrics previousMetrics = g.getFontMetrics(previousFont);
    int ascent =
        previous.isEmpty()
            ? leadMetrics.getAscent()
            : Math.max(leadMetrics.getAscent(), previousMetrics.getAscent());
    int descent =
        previous.isEmpty()
            ? leadMetrics.getDescent()
            : Math.max(leadMetrics.getDescent(), previousMetrics.getDescent());
    int baseline = centerY + (ascent - descent) / 2;
    return new Layout(
        leadFont, previousFont, x + width(g, leadFont, leadText), baseline, baseline, bounds);
  }

  private static int baseline(Graphics2D g, Font font, int top, int height) {
    FontMetrics metrics = g.getFontMetrics(font);
    return top + (height + metrics.getAscent() - metrics.getDescent()) / 2;
  }

  static Color color(double change, Color unchanged) {
    if (!Double.isFinite(change) || "0.0".equals(MoveStatusText.decimal(Math.abs(change)))) {
      return unchanged;
    }
    return change > 0 ? GAIN : LOSS;
  }

  private static Font fit(
      Graphics2D g, Font base, int style, String text, int height, int width, float size) {
    Font font =
        base.deriveFont(style, Math.max(1, size))
            .deriveFont(Map.of(TextAttribute.KERNING, TextAttribute.KERNING_ON));
    FontMetrics metrics = g.getFontMetrics(font);
    float ratio =
        Math.min(1f, (float) Math.max(1, height) / (metrics.getAscent() + metrics.getDescent()));
    if (!text.isEmpty())
      ratio = Math.min(ratio, (float) Math.max(1, width) / Math.max(1, metrics.stringWidth(text)));
    font = font.deriveFont(Math.max(1, font.getSize2D() * ratio));
    while (font.getSize2D() > 1
        && (width(g, font, text) > width
            || g.getFontMetrics(font).getAscent() + g.getFontMetrics(font).getDescent() > height)) {
      font = font.deriveFont(Math.max(1, font.getSize2D() - 0.5f));
    }
    return font;
  }

  private static int width(Graphics2D g, Font font, String text) {
    return text.isEmpty() ? 0 : g.getFontMetrics(font).stringWidth(text);
  }
}
