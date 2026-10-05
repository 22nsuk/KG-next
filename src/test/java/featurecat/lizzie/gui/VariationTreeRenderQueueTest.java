package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class VariationTreeRenderQueueTest {
  @Test
  void burstKeepsOneWorkerAndOnlyTheLatestRequestAcrossModes() {
    Queue<Runnable> workers = new ArrayDeque<>();
    Queue<Runnable> completions = new ArrayDeque<>();
    List<Integer> drawn = new ArrayList<>();
    List<Integer> published = new ArrayList<>();
    try (VariationTreeImage images = new VariationTreeImage(workers::add, completions::add,
        (view, cancelled) -> { drawn.add(view.width()); return result(view); })) {
      for (int i = 1; i <= 10_000; i++) {
        images.request(view(i), () -> true, result -> published.add(result.view().width()));
        assertEquals(1, workers.size());
      }
      workers.remove().run();
      assertEquals(List.of(10_000), drawn);
      assertEquals(1, completions.size());
      completions.remove().run();
      assertEquals(List.of(10_000), published);
    }
  }

  @Test
  void blockedEdtRetainsOnlyOneCompletionWithTheLatestImage() {
    Queue<Runnable> workers = new ArrayDeque<>();
    Queue<Runnable> completions = new ArrayDeque<>();
    AtomicInteger published = new AtomicInteger();
    try (VariationTreeImage images = new VariationTreeImage(workers::add, completions::add,
        (view, cancelled) -> result(view))) {
      for (int i = 1; i <= 1_000; i++) {
        images.request(view(i), () -> true, result -> published.set(result.view().width()));
        workers.remove().run();
        assertEquals(1, completions.size(), "queued EDT tasks must not retain each old image");
      }
      completions.remove().run();
      assertEquals(1_000, published.get());
    }
  }

  @Test
  void runningDrawingIsCancelledAndFollowUpAdoptsLatestStateOnEdt() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    CountDownLatch drawing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch published = new CountDownLatch(1);
    List<Integer> draws = new ArrayList<>();
    AtomicReference<VariationTreeImage.Result> accepted = new AtomicReference<>();
    try (VariationTreeImage images = new VariationTreeImage(worker, SwingUtilities::invokeLater,
        (view, cancelled) -> {
          draws.add(view.width());
          if (view.width() == 1) {
            drawing.countDown();
            await(release);
            assertTrue(cancelled.getAsBoolean());
            throw new CancellationException();
          }
          return result(view);
        })) {
      images.request(view(1), () -> true, result -> fail("obsolete result"));
      assertTrue(drawing.await(5, TimeUnit.SECONDS));
      for (int i = 2; i <= 10_000; i++) {
        images.request(view(i), () -> {
          assertTrue(SwingUtilities.isEventDispatchThread());
          return true;
        }, result -> {
          assertTrue(SwingUtilities.isEventDispatchThread());
          accepted.set(result);
          published.countDown();
        });
      }
      release.countDown();
      assertTrue(published.await(5, TimeUnit.SECONDS));
      worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertEquals(List.of(1, 10_000), draws);
      assertEquals(10_000, accepted.get().view().width());
    } finally {
      release.countDown();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void cancelInvalidatesWaitingAndFinishedWorkButAllowsANewRequest() {
    Queue<Runnable> workers = new ArrayDeque<>();
    Queue<Runnable> completions = new ArrayDeque<>();
    AtomicInteger published = new AtomicInteger();
    try (VariationTreeImage images = new VariationTreeImage(workers::add, completions::add,
        (view, cancelled) -> result(view))) {
      images.request(view(1), () -> true, result -> fail("cancelled queued request"));
      images.cancel();
      workers.remove().run();
      assertTrue(completions.isEmpty());
      images.request(view(2), () -> true, result -> fail("cancelled completed drawing"));
      workers.remove().run();
      images.cancel();
      completions.remove().run();
      images.request(view(3), () -> true, result -> published.incrementAndGet());
      workers.remove().run();
      completions.remove().run();
      assertEquals(1, published.get());
    }
  }

  @Test
  void closedWindowAndChangedUiRejectPublication() {
    Queue<Runnable> workers = new ArrayDeque<>();
    Queue<Runnable> completions = new ArrayDeque<>();
    VariationTreeImage images = new VariationTreeImage(workers::add, completions::add,
        (view, cancelled) -> result(view));
    images.request(view(1), () -> false, result -> fail("stale viewport/settings/history"));
    workers.remove().run();
    completions.remove().run();
    images.request(view(2), () -> true, result -> fail("closed window"));
    workers.remove().run();
    images.close();
    completions.remove().run();
    images.request(view(3), () -> true, result -> fail("closed queue"));
    assertTrue(workers.isEmpty());
  }

  @Test
  void failedRenderAndRejectedExecutorDoNotPermanentlyBlockLaterRequests() {
    Queue<Runnable> workers = new ArrayDeque<>();
    Queue<Runnable> completions = new ArrayDeque<>();
    AtomicInteger submissions = new AtomicInteger();
    AtomicInteger published = new AtomicInteger();
    try (VariationTreeImage images = new VariationTreeImage(task -> {
      if (submissions.getAndIncrement() == 0) throw new RejectedExecutionException("fixture");
      workers.add(task);
    }, completions::add, (view, cancelled) -> {
      if (view.width() == 2) throw new IllegalArgumentException("fixture render failure");
      return result(view);
    })) {
      assertThrows(RejectedExecutionException.class,
          () -> images.request(view(1), () -> true, result -> fail("rejected")));
      images.request(view(2), () -> true, result -> fail("failed render"));
      workers.remove().run();
      completions.remove().run();
      images.request(view(3), () -> true, result -> published.incrementAndGet());
      workers.remove().run();
      completions.remove().run();
      assertEquals(1, published.get());
    }
  }

  @Test
  void renderErrorReleasesWorkerForLaterRequests() {
    Queue<Runnable> workers = new ArrayDeque<>();
    Queue<Runnable> completions = new ArrayDeque<>();
    AtomicInteger published = new AtomicInteger();
    AssertionError failure = new AssertionError("fixture layout failure");
    try (VariationTreeImage images = new VariationTreeImage(workers::add, completions::add,
        (view, cancelled) -> {
          if (view.width() == 1) throw failure;
          return result(view);
        })) {
      images.request(view(1), () -> true, result -> fail("failed drawing"));
      assertSame(failure, assertThrows(AssertionError.class, () -> workers.remove().run()));
      assertTrue(completions.isEmpty());
      images.cancel();
      images.request(view(2), () -> true, result -> published.set(result.view().width()));
      assertEquals(1, workers.size(), "failed drawing must not retain worker ownership");
      workers.remove().run();
      completions.remove().run();
      assertEquals(2, published.get());
    }
  }

  @Test
  void renderErrorReschedulesTheLatestRequestReceivedDuringDrawing() {
    Queue<Runnable> workers = new ArrayDeque<>();
    Queue<Runnable> completions = new ArrayDeque<>();
    AtomicInteger published = new AtomicInteger();
    AtomicReference<VariationTreeImage> reference = new AtomicReference<>();
    AssertionError failure = new AssertionError("fixture layout failure");
    try (VariationTreeImage images = new VariationTreeImage(workers::add, completions::add,
        (view, cancelled) -> {
          if (view.width() == 1) {
            for (int id = 2; id <= 1_000; id++) {
              reference.get().request(view(id), () -> true,
                  result -> published.set(result.view().width()));
            }
            throw failure;
          }
          return result(view);
        })) {
      reference.set(images);
      images.request(view(1), () -> true, result -> fail("failed drawing"));
      assertSame(failure, assertThrows(AssertionError.class, () -> workers.remove().run()));
      assertEquals(1, workers.size(), "latest pending request needs exactly one replacement worker");
      workers.remove().run();
      assertTrue(workers.isEmpty());
      assertEquals(1, completions.size());
      completions.remove().run();
      assertEquals(1_000, published.get());
    }
  }

  private static VariationTreeImage.View view(int id) {
    return new VariationTreeImage.View(null, null, null, null, id, 0, 0, id, 100,
        500, 500, null, id % 2 == 0, null);
  }

  private static VariationTreeImage.Result result(VariationTreeImage.View view) {
    return new VariationTreeImage.Result(view,
        new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), null, 0, 0, false);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
