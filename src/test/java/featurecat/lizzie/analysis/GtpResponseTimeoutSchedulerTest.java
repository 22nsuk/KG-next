package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GtpResponseTimeoutSchedulerTest {
  @Test
  void blockedExpirationCannotHoldUpAnotherEngineDeadline() throws Exception {
    var scheduler = GtpResponseTimeoutScheduler.create();
    var commands = Executors.newFixedThreadPool(2);
    var release = new CompletableFuture<Void>();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch secondFinished = new CountDownLatch(1);
    try {
      GtpResponseTimeoutScheduler.schedule(
          scheduler,
          commands,
          () -> {
            firstStarted.countDown();
            release.join();
          },
          0);
      assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

      GtpResponseTimeoutScheduler.schedule(scheduler, commands, secondFinished::countDown, 0);

      assertTrue(
          secondFinished.await(2, TimeUnit.SECONDS),
          "a blocked transport callback must not hold the shared deadline worker");
    } finally {
      release.complete(null);
      scheduler.shutdownNow();
      commands.shutdownNow();
      assertTrue(commands.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void burstUsesOneWorkerAndCancellationRemovesPendingDeadlines() {
    var scheduler = GtpResponseTimeoutScheduler.create();
    try {
      var deadlines = new ArrayList<ScheduledFuture<?>>();
      for (int i = 0; i < 512; i++) {
        deadlines.add(
            GtpResponseTimeoutScheduler.schedule(
                scheduler, Runnable::run, () -> fail("cancelled deadline ran"),
                TimeUnit.DAYS.toMillis(1)));
      }
      assertEquals(1, scheduler.getLargestPoolSize());
      assertEquals(512, scheduler.getQueue().size());

      deadlines.forEach(deadline -> deadline.cancel(false));

      assertTrue(scheduler.getQueue().isEmpty(), "cancelled commands must not retain the engine");
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void failedDispatchDoesNotKillFollowingDeadlineAndWorkerIsDaemon() throws Exception {
    var scheduler = GtpResponseTimeoutScheduler.create();
    try {
      var failed =
          GtpResponseTimeoutScheduler.schedule(
              scheduler,
              command -> {
                throw new RejectedExecutionException("controlled dispatch failure");
              },
              () -> fail("rejected dispatch ran"),
              0);
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS));
      assertInstanceOf(RejectedExecutionException.class, failure.getCause());

      var daemon = new CompletableFuture<Boolean>();
      GtpResponseTimeoutScheduler.schedule(
          scheduler, Runnable::run, () -> daemon.complete(Thread.currentThread().isDaemon()), 0);

      assertTrue(daemon.get(2, TimeUnit.SECONDS));
      assertEquals(1, scheduler.getLargestPoolSize());
    } finally {
      scheduler.shutdownNow();
    }
  }
}
