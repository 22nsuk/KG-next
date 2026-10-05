package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BufferedImageSurfaceTest {
  @Test
  void fixedSizeUsesTwoPixelBuffersAndClearsBothBeforeReuse() throws Exception {
    BufferedImageSurface surface = new BufferedImageSurface();
    Set<Object> pixels = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int i = 0; i < 100; i++) {
      surface.render(240, 180, g -> fill(g, Color.BLACK, 240, 180));
      pixels.add(front(surface).getRaster().getDataBuffer());
    }
    assertEquals(2, pixels.size());
    for (int i = 0; i < 2; i++) {
      surface.render(240, 180, g -> {});
      assertTransparent(surface.snapshot());
    }
  }

  @Test
  void resizeAndEmptyBoundsNeverReuseOldSizedPixels() {
    BufferedImageSurface surface = new BufferedImageSurface();
    surface.render(12, 8, g -> fill(g, Color.BLACK, 12, 8));
    for (int i = 0; i < 3; i++) {
      surface.render(7, 5, g -> {});
      BufferedImage image = surface.snapshot();
      assertEquals(7, image.getWidth());
      assertEquals(5, image.getHeight());
      assertTransparent(image);
    }
    surface.render(0, 5, g -> fail("zero-sized surface must not invoke its painter"));
    assertTransparent(surface.snapshot());
    surface.render(5, -1, g -> fail("negative-sized surface must not invoke its painter"));
    assertTransparent(surface.snapshot());
  }

  @Test
  void snapshotsAndSeparateSurfacesNeverAliasReusablePixels() throws Exception {
    BufferedImageSurface first = new BufferedImageSurface();
    BufferedImageSurface second = new BufferedImageSurface();
    first.render(8, 8, g -> fill(g, Color.RED, 8, 8));
    BufferedImage capture = first.snapshot();
    second.render(8, 8, g -> fill(g, Color.BLUE, 8, 8));
    assertNotSame(
        front(first).getRaster().getDataBuffer(), front(second).getRaster().getDataBuffer());
    for (int i = 0; i < 4; i++) first.render(8, 8, g -> fill(g, Color.GREEN, 8, 8));
    assertUniform(capture, Color.RED.getRGB());
    capture.setRGB(0, 0, Color.BLACK.getRGB());
    assertUniform(first.snapshot(), Color.GREEN.getRGB());
    assertUniform(second.snapshot(), Color.BLUE.getRGB());
  }

  @Test
  void failedPainterLeavesCompletedImageIntactAndNextRenderClearsItsPartialWork() {
    BufferedImageSurface surface = new BufferedImageSurface();
    surface.render(8, 8, g -> fill(g, Color.RED, 8, 8));
    assertThrows(
        IllegalStateException.class,
        () ->
            surface.render(
                8,
                8,
                g -> {
                  fill(g, Color.BLUE, 4, 8);
                  throw new IllegalStateException("test painter");
                }));
    assertUniform(surface.snapshot(), Color.RED.getRGB());
    surface.render(8, 8, g -> {});
    assertTransparent(surface.snapshot());
  }

  @Test
  void readersUseCompletedFrameWhileProducerIsBlockedMidPaint() throws Exception {
    BufferedImageSurface surface = new BufferedImageSurface();
    surface.render(16, 16, g -> fill(g, Color.RED, 16, 16));
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    try {
      Future<?> writer =
          workers.submit(
              () ->
                  surface.render(
                      16,
                      16,
                      g -> {
                        fill(g, Color.BLUE, 8, 16);
                        entered.countDown();
                        await(resume);
                        fill(g, Color.BLUE, 16, 16);
                      }));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      Future<BufferedImage> reader =
          workers.submit(
              () -> {
                BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                try {
                  surface.drawTo(g, 0, 0);
                } finally {
                  g.dispose();
                }
                assertUniform(surface.snapshot(), Color.RED.getRGB());
                return image;
              });
      assertUniform(reader.get(5, TimeUnit.SECONDS), Color.RED.getRGB());
      resume.countDown();
      writer.get(5, TimeUnit.SECONDS);
      assertUniform(surface.snapshot(), Color.BLUE.getRGB());
    } finally {
      resume.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void clearWaitsForInFlightProducerAndCannotBeUndoneByItsLatePublication() throws Exception {
    BufferedImageSurface surface = new BufferedImageSurface();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch clearing = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    try {
      Future<?> writer =
          workers.submit(
              () ->
                  surface.render(
                      16,
                      16,
                      g -> {
                        entered.countDown();
                        await(resume);
                        fill(g, Color.BLUE, 16, 16);
                      }));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      Future<?> clear =
          workers.submit(
              () -> {
                clearing.countDown();
                surface.clear();
              });
      assertTrue(clearing.await(5, TimeUnit.SECONDS));
      assertFalse(clear.isDone());
      resume.countDown();
      writer.get(5, TimeUnit.SECONDS);
      clear.get(5, TimeUnit.SECONDS);
      assertEquals(1, surface.snapshot().getWidth());
      assertTransparent(surface.snapshot());
    } finally {
      resume.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void competingProducersAndReadersNeverObserveTornPixels() throws Exception {
    BufferedImageSurface surface = new BufferedImageSurface();
    ExecutorService workers = Executors.newFixedThreadPool(3);
    try {
      Future<?> red =
          workers.submit(
              () -> {
                for (int i = 0; i < 200; i++)
                  surface.render(32, 32, g -> fill(g, Color.RED, 32, 32));
              });
      Future<?> blue =
          workers.submit(
              () -> {
                for (int i = 0; i < 200; i++)
                  surface.render(32, 32, g -> fill(g, Color.BLUE, 32, 32));
              });
      Future<?> reader =
          workers.submit(
              () -> {
                for (int i = 0; i < 400; i++) {
                  BufferedImage image = surface.snapshot();
                  assertUniform(image, image.getRGB(0, 0));
                }
              });
      red.get(10, TimeUnit.SECONDS);
      blue.get(10, TimeUnit.SECONDS);
      reader.get(10, TimeUnit.SECONDS);
    } finally {
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static BufferedImage front(BufferedImageSurface surface) throws Exception {
    Field field = BufferedImageSurface.class.getDeclaredField("front");
    field.setAccessible(true);
    return (BufferedImage) field.get(surface);
  }

  private static void fill(Graphics2D graphics, Color color, int width, int height) {
    graphics.setColor(color);
    graphics.fillRect(0, 0, width, height);
  }

  private static void assertTransparent(BufferedImage image) {
    assertUniform(image, 0);
  }

  private static void assertUniform(BufferedImage image, int argb) {
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++) assertEquals(argb, image.getRGB(x, y));
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }
}
