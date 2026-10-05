package featurecat.lizzie.gui;

import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/** Bounded ARGB storage; rendered contents are always replaced, never memoized. */
final class BufferedImageSurface {
  private static final BufferedImage EMPTY = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
  private final Object publicationLock = new Object();
  private BufferedImage front = EMPTY;
  private BufferedImage back;

  // Serialize producers, but allow readers to use the last completed image during rendering.
  // Painters must not retain the graphics or re-enter this surface.
  synchronized void render(int width, int height, Consumer<Graphics2D> painter) {
    if (width <= 0 || height <= 0) {
      clear();
      return;
    }
    if (back == null || back.getWidth() != width || back.getHeight() != height) {
      back = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }
    Graphics2D graphics = back.createGraphics();
    try {
      graphics.setComposite(AlphaComposite.Clear);
      graphics.fillRect(0, 0, width, height);
      graphics.setComposite(AlphaComposite.SrcOver);
      painter.accept(graphics);
    } finally {
      graphics.dispose();
    }
    // A failed painter leaves front untouched. Wait for current readers before recycling it.
    synchronized (publicationLock) {
      BufferedImage previous = front;
      front = back;
      back = previous == EMPTY ? null : previous;
    }
  }

  void drawTo(Graphics graphics, int x, int y) {
    synchronized (publicationLock) {
      graphics.drawImage(front, x, y, null);
    }
  }

  /** Capture callers own this copy, including after either reusable buffer is repainted. */
  BufferedImage snapshot() {
    synchronized (publicationLock) {
      BufferedImage copy =
          new BufferedImage(front.getWidth(), front.getHeight(), BufferedImage.TYPE_INT_ARGB);
      Graphics2D graphics = copy.createGraphics();
      try {
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(front, 0, 0, null);
      } finally {
        graphics.dispose();
      }
      return copy;
    }
  }

  // Serialize with producers so an in-flight render cannot republish an image after clear returns.
  synchronized void clear() {
    synchronized (publicationLock) {
      front = EMPTY;
      back = null;
    }
  }
}
