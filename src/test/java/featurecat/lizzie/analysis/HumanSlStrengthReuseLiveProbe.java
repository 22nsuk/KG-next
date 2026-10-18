package featurecat.lizzie.analysis;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit real CPU policy integration using synthetic analysis, never rank-accuracy evidence. */
public final class HumanSlStrengthReuseLiveProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("Expected verified human model, engine, new output directory");
    }
    Path human = Path.of(args[0]).toAbsolutePath(),
        engine = Path.of(args[1]).toAbsolutePath(),
        out = Path.of(args[2]).toAbsolutePath();
    Files.createDirectory(out);
    Lizzie.config = ConfigTestHelper.createForTests(out);
    if (!HumanSlStrengthReuseSession.fileHash(human).equals(HumanSlStrength40.HUMAN_SHA)) {
      throw new IllegalArgumentException("Wrong human network");
    }
    JSONObject input = syntheticInput();
    JSONObject game = input.getJSONObject("game");
    JSONArray moves = game.getJSONArray("moves"), base = input.getJSONArray("base");
    String before = input.toString();
    Files.writeString(out.resolve("synthetic-input.json"), input.toString(2));
    String command = HumanSlStrengthReuseSession.policyCommand(engine, human, out);
    AtomicInteger queries = new AtomicInteger(), progress = new AtomicInteger();
    AtomicReference<ExecutorService> reader = new AtomicReference<>();
    List<Process> processes = new ArrayList<>();
    JSONArray trace = new JSONArray();
    long started = System.nanoTime();
    JSONObject policies;
    HumanSlAnalysisRunner runner =
        new HumanSlAnalysisRunner(
            HumanSlAnalysisRunner.buildHumanSlCommand(command, human),
            builder -> {
              Process process = builder.start();
              processes.add(process);
              return process;
            },
            HumanSlAnalysisRunner.LaunchProfile.POLICY_SUPPLEMENT);
    var launch = runner.preparePolicyLaunch();
    if (launch == null) throw new IllegalStateException(runner.getUnavailableReason());
    String fingerprint = HumanSlStrengthReuseSession.fingerprint(launch);
    try (runner) {
      policies =
          HumanSlStrengthReuseSession.prefetchPolicies(
              game,
              query -> {
                if (query.getInt("maxVisits") != 1) {
                  throw new AssertionError("Unexpected ordinary search");
                }
                queries.incrementAndGet();
                JSONObject response = runner.request(query, Duration.ofSeconds(180));
                synchronized (trace) {
                  trace.put(new JSONObject().put("request", query).put("response", response));
                  if (reader.get() == null) {
                    Field field = HumanSlAnalysisRunner.class.getDeclaredField("readerExecutor");
                    field.setAccessible(true);
                    reader.set((ExecutorService) field.get(runner));
                  }
                }
                return response;
              },
              out.resolve("cache"),
              fingerprint,
              () -> false,
              count -> {
                progress.set(count);
                System.out.println("POLICY " + count + "/12");
              });
    } finally {
      Files.writeString(out.resolve("policy-trace.json"), trace.toString());
    }
    if (processes.size() != 1 || processes.get(0).isAlive() || runner.isStarted()) {
      throw new AssertionError("Owned engine process did not terminate");
    }
    if (reader.get() == null || !reader.get().awaitTermination(10, TimeUnit.SECONDS)) {
      throw new AssertionError("Owned engine reader did not terminate");
    }
    if (AnalysisResourceCoordinator.hasActiveLocalComputeProcess()) {
      throw new AssertionError("Engine remains registered as active compute");
    }
    if (queries.get() != 36 || progress.get() != 12) {
      throw new AssertionError("Did not execute every position and all three HumanSL profiles");
    }
    if (!before.equals(input.toString())) {
      throw new AssertionError("Frozen ordinary analysis or game was changed");
    }
    var sides = HumanSlStrength40.evaluate(moves, base, policies, 19);
    JSONObject replay =
        HumanSlStrengthReuseSession.prefetchPolicies(
            game,
            query -> {
              throw new AssertionError("Cache replay queried an engine");
            },
            out.resolve("cache"),
            fingerprint,
            () -> false,
            count -> {});
    var replaySides = HumanSlStrength40.evaluate(moves, base, replay, 19);
    JSONArray predictions = new JSONArray();
    for (int i = 0; i < sides.size(); i++) {
      var side = sides.get(i);
      if (side.samples() != 6
          || !Double.isFinite(side.rank())
          || side.rank() < 0
          || side.rank() > 12
          || side.rank() != replaySides.get(i).rank()
          || !java.util.Arrays.equals(side.features(), replaySides.get(i).features())) {
        throw new AssertionError("Invalid result or non-identical cache replay");
      }
      predictions.put(new JSONObject().put("color", side.color()).put("samples", side.samples())
          .put("syntheticRank", side.rank()));
    }
    String boosterHash;
    try (var resource = HumanSlStrength40.class.getResourceAsStream(HumanSlStrength40.RESOURCE)) {
      if (resource == null) throw new AssertionError("Missing bundled booster");
      boosterHash = HumanSlStrengthReuseSession.hash(resource.readAllBytes());
    }
    JSONObject evidence =
        new JSONObject()
            .put("input", "Synthetic game and ordinary analysis; no rank-accuracy claim")
            .put("engine_sha256", HumanSlStrengthReuseSession.fileHash(launch.executable()))
            .put("human_sha256", HumanSlStrengthReuseSession.fileHash(human))
            .put("booster_sha256", boosterHash)
            .put("policy_queries", queries.get())
            .put("maxVisits", 1)
            .put("bounded_position_concurrency", 4)
            .put("ordinary_analysis_queries", 0)
            .put("ordinary_analysis_unchanged", true)
            .put("cache_replay_exact", true)
            .put("engine_pid", processes.get(0).pid())
            .put("engine_closed", true)
            .put("reader_terminated", true)
            .put("active_compute_released", true)
            .put("predictions", predictions)
            .put("elapsed_seconds", (System.nanoTime() - started) / 1e9);
    Files.writeString(out.resolve("reuse-live-evidence.json"), evidence.toString(2));
    System.out.println("PASS " + evidence);
  }

  private static JSONObject syntheticInput() {
    String[] coordinates = {"D4", "Q16", "Q4", "D16", "F3", "C14", "R14", "F17", "C6", "R6", "K4", "K16"};
    JSONArray moves = new JSONArray(), base = new JSONArray();
    for (int i = 0; i < coordinates.length; i++) {
      moves.put(new JSONArray().put(i % 2 == 0 ? "B" : "W").put(coordinates[i]));
    }
    for (int i = 0; i <= coordinates.length; i++) {
      base.put(new JSONObject()
          .put("rootInfo", new JSONObject().put("scoreMean", .2).put("winrate", .5))
          .put("moveInfos", new JSONArray()
              .put(new JSONObject().put("move", i < coordinates.length ? coordinates[i] : "pass")
                  .put("order", 0).put("scoreMean", .2).put("winrate", .5).put("prior", .2))
              .put(new JSONObject().put("move", i < coordinates.length ? "pass" : "A1")
                  .put("order", 1).put("scoreMean", -.8).put("winrate", .48).put("prior", .01))));
    }
    return new JSONObject()
        .put("game", new JSONObject().put("moves", moves).put("rules", "chinese")
            .put("komi", 7.5).put("boardXSize", 19).put("boardYSize", 19))
        .put("base", base);
  }
}
