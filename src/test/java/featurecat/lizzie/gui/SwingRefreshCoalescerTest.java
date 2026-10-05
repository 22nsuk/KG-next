package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SwingRefreshCoalescerTest {
  @Test
  void burstRequestsRunOnceOnEventThread() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    AtomicBoolean ranOnEventThread = new AtomicBoolean();
    CountDownLatch firstRun = new CountDownLatch(1);
    SwingRefreshCoalescer coalescer =
        new SwingRefreshCoalescer(
            25,
            () -> {
              runs.incrementAndGet();
              ranOnEventThread.set(SwingUtilities.isEventDispatchThread());
              firstRun.countDown();
            });

    for (int i = 0; i < 100; i++) {
      coalescer.request();
    }

    assertTrue(firstRun.await(2, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals(1, runs.get());
    assertTrue(ranOnEventThread.get());
  }

  @Test
  void laterRequestRunsAfterMinimumInterval() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch runsCompleted = new CountDownLatch(2);
    SwingRefreshCoalescer coalescer =
        new SwingRefreshCoalescer(
            30,
            () -> {
              runs.incrementAndGet();
              runsCompleted.countDown();
            });

    coalescer.request();
    while (runs.get() == 0) {
      Thread.sleep(2L);
    }
    coalescer.request();

    assertTrue(runsCompleted.await(2, TimeUnit.SECONDS));
    assertEquals(2, runs.get());
  }

  @Test
  void requestArrivingDuringTaskIsNotLost() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstTask = new CountDownLatch(1);
    CountDownLatch secondRun = new CountDownLatch(1);
    SwingRefreshCoalescer coalescer =
        new SwingRefreshCoalescer(
            5,
            () -> {
              int run = runs.incrementAndGet();
              if (run == 1) {
                taskStarted.countDown();
                try {
                  releaseFirstTask.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              } else {
                secondRun.countDown();
              }
            });

    coalescer.request();
    assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
    coalescer.request();
    releaseFirstTask.countDown();

    assertTrue(secondRun.await(2, TimeUnit.SECONDS));
    assertEquals(2, runs.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 33})
  void slowCallbackYieldsToMouseInputBeforeCoalescedFollowUp(int minimumIntervalMillis)
      throws Exception {
    AtomicInteger runs = new AtomicInteger();
    AtomicInteger version = new AtomicInteger();
    AtomicInteger adoptedVersion = new AtomicInteger(-1);
    AtomicInteger runsAtInput = new AtomicInteger(-1);
    AtomicBoolean callbacksOnEdt = new AtomicBoolean(true);
    AtomicBoolean callbackReleased = new AtomicBoolean();
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);
    CountDownLatch followUp = new CountDownLatch(1);
    CountDownLatch inputHandled = new CountDownLatch(1);
    SwingRefreshCoalescer coalescer =
        new SwingRefreshCoalescer(
            minimumIntervalMillis,
            () -> {
              if (!SwingUtilities.isEventDispatchThread()) callbacksOnEdt.set(false);
              adoptedVersion.set(version.get());
              if (runs.incrementAndGet() == 1) {
                taskStarted.countDown();
                callbackReleased.set(awaitLatch(releaseTask));
              } else {
                followUp.countDown();
              }
            });
    AtomicReference<JPanel> inputTarget = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          JPanel panel = new JPanel();
          panel.addMouseListener(
              new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                  if (!SwingUtilities.isEventDispatchThread()) callbacksOnEdt.set(false);
                  runsAtInput.set(runs.get());
                  // A request between the old callback and its queued continuation must join
                  // that continuation, not run inline or schedule a duplicate refresh.
                  version.set(1001);
                  coalescer.request();
                  inputHandled.countDown();
                }
              });
          inputTarget.set(panel);
        });

    coalescer.request();
    try {
      assertTrue(taskStarted.await(5, TimeUnit.SECONDS));
      long blockedSince = System.nanoTime();
      for (int i = 1; i <= 1000; i++) {
        version.set(i);
        coalescer.request();
      }
      Toolkit.getDefaultToolkit()
          .getSystemEventQueue()
          .postEvent(
              new MouseEvent(
                  inputTarget.get(), MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false,
                  MouseEvent.BUTTON1));
      // Hold the callback past the real rate limit so the original code takes its inline
      // follow-up path. Assert event order, not a machine-dependent latency threshold.
      long interval = TimeUnit.MILLISECONDS.toNanos(minimumIntervalMillis);
      long remaining;
      while ((remaining = interval - (System.nanoTime() - blockedSince)) > 0) {
        LockSupport.parkNanos(remaining);
      }
    } finally {
      releaseTask.countDown();
    }

    assertTrue(inputHandled.await(5, TimeUnit.SECONDS));
    assertTrue(followUp.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(() -> {});
    assertTrue(callbackReleased.get(), "fixture must release the blocked callback");
    assertTrue(callbacksOnEdt.get());
    assertEquals(1, runsAtInput.get(), "queued mouse input must precede the follow-up refresh");
    assertEquals(2, runs.get(), "background burst and input request must share one follow-up");
    assertEquals(1001, adoptedVersion.get(), "follow-up must adopt the latest requested state");
  }

  @Test
  void repeatedReentrantRequestsUseSeparateEventQueueTurns() throws Exception {
    int expectedRuns = 64;
    AtomicInteger runs = new AtomicInteger();
    Set<AWTEvent> dispatches = Collections.newSetFromMap(new IdentityHashMap<>());
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<SwingRefreshCoalescer> reference = new AtomicReference<>();
    reference.set(
        new SwingRefreshCoalescer(
            0,
            () -> {
              dispatches.add(EventQueue.getCurrentEvent());
              if (runs.incrementAndGet() < expectedRuns) {
                reference.get().request();
              } else {
                completed.countDown();
              }
            }));

    reference.get().request();
    assertTrue(completed.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals(expectedRuns, runs.get());
    assertEquals(expectedRuns, dispatches.size(), "follow-ups must not recurse in one dispatch");
  }

  @Test
  void idleEventThreadRequestStillRunsImmediately() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    SwingRefreshCoalescer coalescer = new SwingRefreshCoalescer(33, runs::incrementAndGet);
    SwingUtilities.invokeAndWait(
        () -> {
          coalescer.request();
          assertEquals(1, runs.get(), "only follow-ups should change to deferred execution");
        });
  }

  private static boolean awaitLatch(CountDownLatch latch) {
    try {
      return latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
