package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EngineStartupProgressTest {
  private static long seconds(long value) {
    return TimeUnit.SECONDS.toNanos(value);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "Performing autotuning (this may take a while)",
      "2026-11-16 12:34:56+0900: Performing autotuning",
      "Started OpenCL SGEMM tuner",
      "Beginning GPU tuning",
      "Tuning xGemmDirect",
      "Testing 123 different configs",
      "Creating new plan cache",
      "2026-11-16 12:34:56+0900: Creating new plan cache for model"
  })
  void realProgressExtendsTheAttemptFromItsArrivalTime(String message) {
    EngineStartupProgress progress = new EngineStartupProgress(0L, 90_000L, 600_000L);
    assertTrue(progress.observe(message, seconds(80)));
    assertTrue(progress.deadlineNanos() >= seconds(200));
    assertFalse(progress.expired(seconds(91)), "the same attempt outlives its original budget");
    assertTrue(progress.snapshot(seconds(91)).remainingMillis() > 0L);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "This operation took a long time", "Finished autotuning", "Tuning dummy thread numeric total: 17",
      "Testing 0 different configs", "Testing unknown different configs", "info visits 100",
      "unrelated output", ""
  })
  void noiseAndCompletionDoNotExtendTheBudget(String message) {
    EngineStartupProgress progress = new EngineStartupProgress(0L, 90_000L, 600_000L);
    assertFalse(progress.observe(message, seconds(80)));
    assertEquals(seconds(90), progress.deadlineNanos());
    assertTrue(progress.expired(seconds(90)));
  }

  @Test
  void pollingAndAStuckTuningStageCannotExtendAnAttempt() {
    EngineStartupProgress progress = new EngineStartupProgress(0L, 90_000L, 600_000L);
    assertTrue(progress.observe("Performing autotuning", seconds(1)));
    for (int second = 2; second < 601; second++) {
      assertFalse(progress.expired(seconds(second)));
      assertEquals(seconds(601), progress.deadlineNanos());
      assertEquals((601L - second) * 1000L, progress.snapshot(seconds(second)).remainingMillis());
    }
    assertTrue(progress.expired(seconds(601)));
    assertFalse(progress.observe("Creating new plan cache", seconds(602)));
    assertFalse(progress.loaded(seconds(602)), "late Ready cannot revive the attempt");
  }

  @Test
  void repeatedRealProgressHasAnAbsoluteMaximumAndCannotReviveAfterExpiry() {
    EngineStartupProgress progress = new EngineStartupProgress(0L, 90_000L, 600_000L);
    for (int second = 80; second < 1200; second += 100) {
      assertTrue(progress.observe("Creating new plan cache", seconds(second)));
      assertTrue(progress.deadlineNanos() <= seconds(1200));
    }
    assertEquals(seconds(1200), progress.deadlineNanos());
    assertFalse(progress.observe("Tuning xGemmDirect", seconds(1200)));
    assertFalse(progress.loaded(seconds(1201)));
    assertEquals(EngineStartupProgress.Stage.EXPIRED, progress.snapshot(seconds(1201)).stage());
  }

  @Test
  void loadedStopsGpuExtensionsButKeepsTheFiniteCommandListBudget() {
    EngineStartupProgress progress = new EngineStartupProgress(0L, 90_000L, 600_000L);
    assertTrue(progress.loaded(seconds(10)));
    assertEquals(EngineStartupProgress.Stage.SYNCHRONIZING, progress.snapshot(seconds(11)).stage());
    assertFalse(progress.observe("Performing autotuning", seconds(20)));
    assertEquals(seconds(90), progress.deadlineNanos());
    assertEquals(seconds(1), progress.remainingNanos(seconds(89)));
    assertEquals(0L, progress.remainingNanos(seconds(90)));
  }

  @Test
  void retiredAttemptsCannotChangeAndNewAttemptsHaveTheirOwnBudget() {
    EngineStartupProgress retired = new EngineStartupProgress(0L, 90_000L, 600_000L);
    retired.observe("Creating new plan cache", seconds(1));
    retired.retire();
    assertFalse(retired.observe("Performing autotuning", seconds(2)));
    assertFalse(retired.loaded(seconds(2)));
    EngineStartupProgress replacement = new EngineStartupProgress(seconds(2), 90_000L, 600_000L);
    assertEquals(seconds(92), replacement.deadlineNanos());
    assertEquals(EngineStartupProgress.Stage.WAITING_RESPONSE, replacement.snapshot(seconds(3)).stage());
  }

  @Test
  void monotonicClockWrapDoesNotExpireOrExtendEarly() {
    long start = Long.MAX_VALUE - seconds(30);
    EngineStartupProgress progress = new EngineStartupProgress(start, 90_000L, 600_000L);
    assertFalse(progress.expired(start + seconds(80)));
    assertTrue(progress.observe("Tuning xGemmDirect", start + seconds(80)));
    assertEquals(seconds(120), progress.remainingNanos(start + seconds(80)));
    assertTrue(progress.expired(start + seconds(200)));
  }
}
