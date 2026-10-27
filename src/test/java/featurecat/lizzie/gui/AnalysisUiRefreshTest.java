package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisUiRefreshTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void mixedBurstKeepsBoardAndTitleWorkAndReadsLatestState(boolean graphFirst) throws Exception {
    List<String> calls = new ArrayList<>();
    AtomicInteger current = new AtomicInteger();
    CountDownLatch painted = new CountDownLatch(1);
    AnalysisUiRefresh updates = new AnalysisUiRefresh(
        () -> record(calls, "title"),
        () -> { record(calls, "board:" + current.get()); painted.countDown(); },
        () -> record(calls, "graph"), () -> record(calls, "candidates"));
    whileEdtBlocked(() -> {
      if (graphFirst) updates.requestGraph();
      updates.requestAnalysis();
      for (int version = 1; version <= 1000; version++) {
        current.set(version);
        updates.requestGraph();
        updates.requestTitle();
      }
      if (!graphFirst) updates.requestAnalysis();
      assertTrue(calls.isEmpty(), "producer threads must only request UI work");
    });
    assertTrue(painted.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(() -> assertEquals(List.of("title", "board:1000"), calls));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void graphAndTitleOnlyRequestsDoNotRebuildBoard(boolean titleOnly) throws Exception {
    List<String> calls = new ArrayList<>();
    CountDownLatch completed = new CountDownLatch(1);
    AnalysisUiRefresh updates = new AnalysisUiRefresh(
        () -> { record(calls, "title"); completed.countDown(); },
        () -> record(calls, "board"),
        () -> { record(calls, "graph"); completed.countDown(); },
        () -> record(calls, "candidates"));
    whileEdtBlocked(() -> {
      for (int i = 0; i < 1000; i++) {
        if (titleOnly) updates.requestTitle();
        else updates.requestGraph();
      }
    });
    assertTrue(completed.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(
        () -> assertEquals(List.of(titleOnly ? "title" : "graph"), calls));
  }

  @Test
  void collapsedTrackingNotificationsStillRefilterLatestEmptySelection() throws Exception {
    List<String> calls = new ArrayList<>();
    AtomicReference<String> selection = new AtomicReference<>("A1");
    CountDownLatch completed = new CountDownLatch(1);
    AnalysisUiRefresh updates = new AnalysisUiRefresh(
        () -> record(calls, "title"),
        () -> { record(calls, "board"); completed.countDown(); },
        () -> record(calls, "graph"),
        () -> record(calls, "candidates:" + selection.get()));
    whileEdtBlocked(() -> {
      updates.requestTracking();
      selection.set("");
      updates.requestTracking();
      updates.requestGraph();
      updates.requestAnalysis();
    });
    assertTrue(completed.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(
        () -> assertEquals(List.of("candidates:", "title", "board"), calls));
  }

  @Test
  void requestDuringRefreshRetainsItsPartsAndYieldsToQueuedInput() throws Exception {
    List<String> calls = new ArrayList<>();
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<AnalysisUiRefresh> reference = new AtomicReference<>();
    AnalysisUiRefresh updates = new AnalysisUiRefresh(
        () -> record(calls, "title"),
        () -> { record(calls, "board"); completed.countDown(); },
        () -> {
          record(calls, "graph");
          SwingUtilities.invokeLater(() -> record(calls, "input"));
          reference.get().requestAnalysis();
          reference.get().requestGraph();
        }, () -> record(calls, "candidates"));
    reference.set(updates);
    updates.requestGraph();
    assertTrue(completed.await(5, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(
        () -> assertEquals(List.of("graph", "input", "title", "board"), calls));
  }

  private static void record(List<String> calls, String value) {
    assertTrue(SwingUtilities.isEventDispatchThread());
    calls.add(value);
  }

  static void whileEdtBlocked(Runnable work) throws Exception {
    assertFalse(SwingUtilities.isEventDispatchThread());
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeLater(() -> {
      blocked.countDown();
      try {
        assertTrue(release.await(5, TimeUnit.SECONDS));
      } catch (Throwable thrown) {
        failure.set(thrown);
      }
    });
    try {
      assertTrue(blocked.await(5, TimeUnit.SECONDS));
      work.run();
    } finally {
      release.countDown();
      SwingUtilities.invokeAndWait(() -> {});
    }
    if (failure.get() != null) throw new AssertionError(failure.get());
  }
}
