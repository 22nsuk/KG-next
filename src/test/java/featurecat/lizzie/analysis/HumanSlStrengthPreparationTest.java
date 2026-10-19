package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HumanSlStrengthPreparationTest {
  @TempDir Path cache;

  private static HumanSlStrengthReuseSession.Capture snapshot(JSONObject game) {
    return new HumanSlStrengthReuseSession.Capture(null, game, new JSONArray(), false);
  }

  private static JSONObject policyArrays(JSONObject game) {
    JSONObject result = new JSONObject();
    for (String profile : HumanSlStrength40.PROFILES) {
      JSONArray array = new JSONArray();
      for (int i = 0; i <= game.getJSONArray("moves").length(); i++)
        array.put(
            HumanSlStrength40Test.response(new JSONObject().put("id", "stub"))
                .getJSONArray("humanPolicy"));
      result.put(profile, array);
    }
    return result;
  }

  private static final class Factory implements HumanSlStrengthPreparation.Factory {
    final AtomicInteger opened = new AtomicInteger(),
        live = new AtomicInteger(),
        peak = new AtomicInteger();
    final CountDownLatch firstStarted = new CountDownLatch(1),
        firstReleased = new CountDownLatch(1);

    public HumanSlStrengthPreparation.Worker open() {
      int number = opened.incrementAndGet();
      peak.accumulateAndGet(live.incrementAndGet(), Math::max);
      return new HumanSlStrengthPreparation.Worker() {
        final AtomicBoolean closed = new AtomicBoolean();

        public JSONObject prepare(
            JSONObject game, AtomicBoolean cancelled, java.util.function.IntConsumer progress)
            throws Exception {
          if (number == 1) {
            firstStarted.countDown();
            assertTrue(firstReleased.await(5, TimeUnit.SECONDS));
          }
          if (cancelled.get()) throw new java.util.concurrent.CancellationException();
          progress.accept(game.getJSONArray("moves").length());
          return policyArrays(game);
        }

        public void cancel() {
          firstReleased.countDown();
        }

        public void close() {
          if (closed.compareAndSet(false, true)) live.decrementAndGet();
        }
      };
    }
  }

  private static void await(java.util.function.BooleanSupplier check) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!check.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue(check.getAsBoolean(), "Preparation did not reach the expected state");
  }

  @Test
  void backgroundRequiresOptInAndPreferenceIsSaved() throws Exception {
    var previous = Lizzie.config;
    try {
      java.nio.file.Files.createDirectories(cache.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(cache);
      assertFalse(HumanSlStrengthPreparation.backgroundEnabled());
      HumanSlStrengthPreparation.saveBackgroundEnabled(false);
      assertFalse(HumanSlStrengthPreparation.backgroundEnabled());
      HumanSlStrengthPreparation.saveBackgroundEnabled(true);
      assertTrue(HumanSlStrengthPreparation.backgroundEnabled());
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void onlyForegroundAnalysisTriggersBackgroundAndRepeatedTicksDeduplicate() throws Exception {
    Factory factory = new Factory();
    try (var preparation = new HumanSlStrengthPreparation(factory)) {
      var snapshot = snapshot(HumanSlStrength40Test.game());
      preparation.observe(snapshot, true, false, false);
      assertEquals(0, factory.opened.get());
      preparation.observe(snapshot, false, true, false);
      assertEquals(0, factory.opened.get());
      preparation.observe(snapshot, true, true, false);
      assertTrue(factory.firstStarted.await(5, TimeUnit.SECONDS));
      for (int i = 0; i < 8; i++) preparation.observe(snapshot, true, true, false);
      assertEquals(1, factory.opened.get());
      // Completing the foreground job does not discard a nearly-complete supplement.
      preparation.observe(snapshot, true, false, false);
      factory.firstReleased.countDown();
      await(() -> preparation.status().key().equals("HumanSL40.backgroundReady"));
      preparation.observe(snapshot, true, true, false);
      assertEquals(1, factory.opened.get());
      assertEquals(0, factory.live.get());
    }
  }

  @Test
  void manualPreemptsBackgroundWithoutOverlappingWorkers() throws Exception {
    Factory factory = new Factory();
    try (var preparation = new HumanSlStrengthPreparation(factory)) {
      var snapshot = snapshot(HumanSlStrength40Test.game());
      preparation.observe(snapshot, true, true, false);
      assertTrue(factory.firstStarted.await(5, TimeUnit.SECONDS));
      var manual = preparation.manual(snapshot);
      preparation.observe(snapshot, false, true, false);
      manual.completion().get(5, TimeUnit.SECONDS);
      assertEquals(2, factory.opened.get());
      assertEquals(1, factory.peak.get());
      assertEquals(0, factory.live.get());
      assertEquals("HumanSL40.backgroundReady", preparation.status().key());
    }
  }

  @Test
  void gameChangeCancelsStaleJobAndPauseCanResumeFromCache() throws Exception {
    Factory factory = new Factory();
    try (var preparation = new HumanSlStrengthPreparation(factory)) {
      var snapshot = snapshot(HumanSlStrength40Test.game());
      preparation.observe(snapshot, true, true, false);
      assertTrue(factory.firstStarted.await(5, TimeUnit.SECONDS));
      preparation.observe(snapshot, true, false, true);
      await(() -> factory.live.get() == 0);
      JSONObject changed = HumanSlStrength40Test.game().put("komi", 0);
      preparation.observe(snapshot(changed), true, true, false);
      await(() -> preparation.status().key().equals("HumanSL40.backgroundReady"));
      assertEquals(2, factory.opened.get());
      assertEquals(1, factory.peak.get());
      assertNull(preparation.readyReport(snapshot));
    }
  }

  @Test
  void failuresStayQuietUntilExplicitRetryRatherThanLoopingEveryTick() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    try (var preparation =
        new HumanSlStrengthPreparation(
            () -> {
              attempts.incrementAndGet();
              throw new java.io.IOException("HumanSL40.downloadRequired");
            })) {
      var snapshot = snapshot(HumanSlStrength40Test.game());
      preparation.observe(snapshot, true, true, false);
      await(() -> preparation.status().key().equals("HumanSL40.downloadRequired"));
      for (int i = 0; i < 8; i++) preparation.observe(snapshot, true, true, false);
      assertEquals(1, attempts.get());
      preparation.resetBackground();
      preparation.observe(snapshot, true, true, false);
      await(() -> attempts.get() == 2);
    }
  }

  @Test
  void shutdownWaitsForCancelledWorkerToClose() throws Exception {
    Factory factory = new Factory();
    var preparation = new HumanSlStrengthPreparation(factory);
    preparation.observe(snapshot(HumanSlStrength40Test.game()), true, true, false);
    assertTrue(factory.firstStarted.await(5, TimeUnit.SECONDS));
    preparation.close();
    preparation.termination().get(5, TimeUnit.SECONDS);
    assertEquals(0, factory.live.get());
    preparation.close();
  }

  @Test
  void fullGamePoliciesAreBatchedBoundedAndReusedWithoutOrdinaryAnalysis() throws Exception {
    JSONObject game = HumanSlStrength40Test.game();
    for (int i = 12; i < 220; i++)
      game.getJSONArray("moves").put(new JSONArray().put(i % 2 == 0 ? "B" : "W").put("pass"));
    AtomicInteger calls = new AtomicInteger(),
        live = new AtomicInteger(),
        peak = new AtomicInteger();
    CountDownLatch firstFour = new CountDownLatch(4);
    JSONObject policies =
        HumanSlStrengthReuseSession.prefetchPolicies(
            game,
            query -> {
              peak.accumulateAndGet(live.incrementAndGet(), Math::max);
              try {
                assertEquals(1, query.getInt("maxVisits"));
                if (calls.incrementAndGet() <= 4) {
                  firstFour.countDown();
                  assertTrue(firstFour.await(5, TimeUnit.SECONDS));
                }
                return HumanSlStrength40Test.response(query);
              } finally {
                live.decrementAndGet();
              }
            },
            cache,
            "fake-engine",
            () -> false,
            n -> {});
    assertEquals(660, calls.get());
    assertEquals(4, peak.get());
    assertEquals(221, policies.getJSONArray("rank_9d").length());
    assertFalse(policies.getJSONArray("rank_9d").isNull(219));
    HumanSlStrengthReuseSession.prefetchPolicies(
        game,
        query -> {
          fail("Completed cache must not launch any engine query");
          return null;
        },
        cache,
        "fake-engine",
        () -> false,
        n -> {});
    // A report's 210-move input can use a subset of the same full-game policy cache.
    JSONObject prefix = HumanSlStrength40Test.game();
    HumanSlStrengthReuseSession.policies(
        prefix,
        HumanSlStrengthReuseSessionTest.base(13),
        query -> {
          fail("The ordinary report must share the background prefix cache");
          return null;
        },
        cache,
        "fake-engine",
        () -> false,
        n -> {});
  }
}
