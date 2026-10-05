package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.lang.reflect.Field;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebTrialIdleQueueTest {
  @Test
  void repeatedTrialActivityRetainsOnlyTheLiveDeadlineAndExitRemovesIt() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    BoardHistoryNode anchor =
        new BoardHistoryNode(BoardData.empty(Board.boardWidth, Board.boardHeight));
    WebBoardDataCollector collector =
        new WebBoardDataCollector(executor, () -> anchor, () -> anchor);
    WebBoardManager manager = new WebBoardManager();
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    WebBoardManager previousManager = Lizzie.webBoardManager;
    try {
      Lizzie.frame = null;
      Lizzie.leelaz = new Leelaz("");
      Lizzie.webBoardManager = manager;
      manager.setCollectorForTest(collector);
      manager.setOverrideSinkForTest(node -> {});
      manager.setDesktopRefresherForTest(() -> {});
      manager.setDesktopPlayingProbe(() -> false);
      assertTrue(manager.enterTrial("owner", anchor));
      for (int i = 0; i < 10_000; i++) manager.trialReset("owner");
      drainImmediateWork(executor);
      assertEquals(
          1, executor.getQueue().size(), "only the current five-minute deadline may remain");
      assertTrue(executor.getQueue().stream().noneMatch(task -> ((Future<?>) task).isCancelled()));
      assertEquals("owner", manager.getCurrentTrialOwner());
      manager.exitTrial("owner");
      drainImmediateWork(executor);
      assertTrue(
          executor.getQueue().isEmpty(), "exit must remove the final idle deadline immediately");
      assertEquals("", manager.getCurrentTrialOwner());
    } finally {
      manager.forceExitTrial();
      collector.shutdown();
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
      Lizzie.webBoardManager = previousManager;
    }
  }

  @Test
  void defaultCollectorAlsoRemovesCancelledDeadlines() throws Exception {
    WebBoardDataCollector collector = new WebBoardDataCollector();
    try {
      Field field = WebBoardDataCollector.class.getDeclaredField("executor");
      field.setAccessible(true);
      ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) field.get(collector);
      ScheduledFuture<?> task =
          collector.scheduleOnExecutor(() -> fail("cancelled task ran"), 5, TimeUnit.MINUTES);
      assertNotNull(task);
      assertTrue(task.cancel(false));
      assertTrue(executor.getQueue().isEmpty());
      assertTrue(executor.getRemoveOnCancelPolicy());
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    } finally {
      collector.shutdown();
    }
    assertNull(collector.scheduleOnExecutor(() -> {}, 5, TimeUnit.MINUTES));
  }

  private static void drainImmediateWork(ScheduledThreadPoolExecutor executor) throws Exception {
    // Publication can schedule its coalesced follow-up before completing. Two barriers also drain
    // it.
    executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
  }
}
