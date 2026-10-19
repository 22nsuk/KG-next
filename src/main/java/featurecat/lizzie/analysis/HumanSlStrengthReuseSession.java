package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reuses frozen primary analysis; the supplementary engine supplies only human policies. */
public final class HumanSlStrengthReuseSession {
  @FunctionalInterface
  interface Query {
    JSONObject request(JSONObject query) throws Exception;
  }

  private HumanSlStrengthReuseSession() {}

  public record Capture(
      BoardHistoryNode start,
      JSONObject game,
      JSONArray base,
      boolean analysisCaptured) {
    public Capture {
      game = new JSONObject(game.toString());
      base = new JSONArray(base.toString());
    }

    @Override
    public JSONObject game() {
      return new JSONObject(game.toString());
    }

    @Override
    public JSONArray base() {
      return new JSONArray(base.toString());
    }
  }

  public static Capture capture() {
    return capture(210, true);
  }

  public static Capture preparationSnapshot() {
    return capture(Integer.MAX_VALUE, false);
  }

  private static Capture capture(int limit, boolean requireAnalysis) {
    if (Board.boardWidth != 19 || Board.boardHeight != 19)
      throw new IllegalArgumentException("HumanSL40.boardSize");
    if (Lizzie.board.getHistory().getGameInfo().getHandicap() > 0)
      throw new IllegalArgumentException("HumanSL40.setupUnsupported");
    BoardHistoryNode start = Lizzie.board.getHistory().getStart(), end = start;
    JSONArray base =
        new JSONArray().put(requireAnalysis ? position(start.getData()) : JSONObject.NULL);
    int count = 0;
    Optional<BoardHistoryNode> next = end.next(true);
    while (next.isPresent() && count < limit) {
      end = next.get();
      if (!end.getData().isHistoryActionNode())
        throw new IllegalArgumentException("HumanSL40.setupUnsupported");
      base.put(requireAnalysis ? position(end.getData()) : JSONObject.NULL);
      count++;
      next = end.next(true);
    }
    JSONObject game =
        AnalysisRequestBuilder.buildRequest("strength40-capture", end, 1, false, false, false);
    JSONArray initial = game.optJSONArray("initialStones");
    if (initial != null && !initial.isEmpty())
      throw new IllegalArgumentException("HumanSL40.setupUnsupported");
    game.remove("initialStones");
    game.remove("analyzeTurns");
    validateHistory(game);
    if (requireAnalysis) validateGame(game, base);
    return new Capture(start, game, base, requireAnalysis);
  }

  static Object position(BoardData data) {
    // clone() shares MoveData objects, so serialize under the payload-commit lock too.
    synchronized (data) {
      return positionSnapshot(data.clone());
    }
  }

  private static Object positionSnapshot(BoardData data) {
    if (!data.isKataData
        || !data.hasPrimaryAnalysisPayload()
        || !Double.isFinite(data.scoreMean)
        || !Double.isFinite(data.winrate)
        || data.winrate < 0
        || data.winrate > 100) return JSONObject.NULL;
    List<MoveData> searched = new ArrayList<>();
    if (data.bestMoves != null) searched.addAll(data.bestMoves);
    if (data.bestMovesOutOfRange != null) searched.addAll(data.bestMovesOutOfRange);
    var unique = new LinkedHashMap<String, MoveData>();
    for (MoveData move : searched) {
      if (move == null
          || !move.isKataData
          || !Double.isFinite(move.scoreMean)
          || move.coordinate == null) continue;
      unique.putIfAbsent(move.coordinate.toUpperCase(Locale.ROOT), move);
    }
    searched = new ArrayList<>(unique.values());
    searched.sort(Comparator.comparingInt(move -> move.order));
    JSONArray candidates = new JSONArray();
    for (MoveData move : searched) {
      double score = move.scoreMean;
      if (move.scoreMeanIsBlackPerspective && !data.blackToPlay) score = -score;
      JSONObject candidate =
          new JSONObject()
              .put("move", move.coordinate)
              .put("order", move.order)
              .put("scoreMean", score)
              .put("prior", Math.max(0, move.policy) / 100.0);
      if (Double.isFinite(move.winrate) && move.winrate >= 0 && move.winrate <= 100)
        candidate.put("winrate", move.winrate / 100.0);
      candidates.put(candidate);
    }
    double score = data.scoreMean;
    if (data.scoreMeanIsBlackPerspective && !data.blackToPlay) score = -score;
    return new JSONObject()
        .put(
            "rootInfo",
            new JSONObject().put("scoreMean", score).put("winrate", data.winrate / 100.0))
        .put("moveInfos", candidates);
  }

  static boolean hasAnalysis(JSONArray base, int turn) {
    JSONObject before = base.optJSONObject(turn), after = base.optJSONObject(turn + 1);
    return before != null && after != null && !before.getJSONArray("moveInfos").isEmpty();
  }

  static List<Integer> validateGame(JSONObject game, JSONArray base) {
    JSONArray moves = game.getJSONArray("moves");
    if (game.getInt("boardXSize") != 19
        || game.getInt("boardYSize") != 19
        || moves.length() > 210
        || base.length() != moves.length() + 1
        || (game.optJSONArray("initialStones") != null
            && !game.getJSONArray("initialStones").isEmpty()))
      throw new IllegalArgumentException("HumanSL40.setupUnsupported");
    int black = 0, white = 0;
    List<Integer> turns = new ArrayList<>();
    for (int i = 0; i < moves.length(); i++) {
      String color = moves.getJSONArray(i).getString(0);
      if ((!color.equals("B") && !color.equals("W"))
          || (i > 0 && color.equals(moves.getJSONArray(i - 1).getString(0))))
        throw new IllegalArgumentException("HumanSL40.setupUnsupported");
      if (hasAnalysis(base, i)) {
        turns.add(i);
        if (color.equals("B")) black++;
        else white++;
      }
    }
    if (black < 6 || white < 6) throw new IllegalArgumentException("HumanSL40.needAnalysis");
    return turns;
  }

  private static void validateHistory(JSONObject game) {
    JSONArray moves = game.getJSONArray("moves");
    if (game.getInt("boardXSize") != 19 || game.getInt("boardYSize") != 19 || moves.isEmpty())
      throw new IllegalArgumentException("HumanSL40.needGame");
    for (int i = 0; i < moves.length(); i++) {
      String color = moves.getJSONArray(i).getString(0);
      if ((!color.equals("B") && !color.equals("W"))
          || (i > 0 && color.equals(moves.getJSONArray(i - 1).getString(0))))
        throw new IllegalArgumentException("HumanSL40.setupUnsupported");
    }
  }

  public static JSONObject request(JSONObject game, int turn, String profile) {
    JSONArray moves = game.getJSONArray("moves");
    if (turn < 0 || turn >= moves.length()) throw new IllegalArgumentException("Invalid turn");
    if (!java.util.Arrays.asList(HumanSlStrength40.PROFILES).contains(profile))
      throw new IllegalArgumentException("Unsupported HumanSL profile");
    JSONObject query = new JSONObject(game.toString());
    JSONArray prefix = new JSONArray();
    for (int i = 0; i < turn; i++) prefix.put(moves.getJSONArray(i));
    query.put("moves", prefix);
    query.remove("analyzeTurns");
    query.put("id", "strength40-policy-" + profile + "-" + turn);
    query.put("maxVisits", 1);
    query.put("includePolicy", true);
    query.put("includeOwnership", false);
    query.put("includePVVisits", false);
    query.put("includeMovesOwnership", false);
    query.put("analysisPVLen", 12);
    query.put(
        "overrideSettings",
        new JSONObject()
            .put("reportAnalysisWinratesAs", "SIDETOMOVE")
            .put("humanSLProfile", profile)
            .put("ignorePreRootHistory", false)
            .put("rootNumSymmetriesToSample", 2)
            .put("humanSLRootExploreProbWeightless", 0.0)
            .put("humanSLCpuctPermanent", 2.0));
    return query;
  }

  public static int positionCount(Capture capture) {
    return validateGame(capture.game(), capture.base()).size();
  }

  public static JSONObject policies(
      JSONObject game,
      JSONArray base,
      Query engine,
      Path cache,
      String engineFingerprint,
      BooleanSupplier cancelled,
      IntConsumer progress)
      throws Exception {
    return collectPolicies(
        game, validateGame(game, base), engine, cache, engineFingerprint, cancelled, progress, 1);
  }

  public static JSONObject prefetchPolicies(
      JSONObject game,
      Query engine,
      Path cache,
      String fingerprint,
      BooleanSupplier cancelled,
      IntConsumer progress)
      throws Exception {
    validateHistory(game);
    List<Integer> turns =
        java.util.stream.IntStream.range(0, game.getJSONArray("moves").length()).boxed().toList();
    return collectPolicies(game, turns, engine, cache, fingerprint, cancelled, progress, 4);
  }

  private static JSONObject collectPolicies(
      JSONObject game,
      List<Integer> turns,
      Query engine,
      Path cache,
      String engineFingerprint,
      BooleanSupplier cancelled,
      IntConsumer progress,
      int concurrency)
      throws Exception {
    int positions = game.getJSONArray("moves").length() + 1;
    JSONObject policies = new JSONObject();
    for (String profile : HumanSlStrength40.PROFILES) {
      JSONArray array = new JSONArray();
      for (int i = 0; i < positions; i++) array.put(JSONObject.NULL);
      policies.put(profile, array);
    }
    Files.createDirectories(cache);
    int done = 0;
    var pool =
        java.util.concurrent.Executors.newFixedThreadPool(
            concurrency,
            task -> {
              Thread thread = new Thread(task, "strength40-policy-query");
              thread.setDaemon(true);
              return thread;
            });
    try {
      // A bounded window lets KataGo batch different positions without queuing the whole game.
      for (int offset = 0; offset < turns.size(); offset += concurrency) {
        List<java.util.concurrent.Future<?>> batch = new ArrayList<>();
        for (int index = offset; index < Math.min(turns.size(), offset + concurrency); index++) {
          int turn = turns.get(index);
          batch.add(
              pool.submit(
                  () -> {
                    try {
                      for (String profile : HumanSlStrength40.PROFILES) {
                        if (cancelled.getAsBoolean()) throw new CancellationException();
                        JSONObject query = request(game, turn, profile);
                        String key =
                            hash(
                                ("strength40-policy-v2|"
                                        + engineFingerprint
                                        + "|"
                                        + HumanSlStrength40.HUMAN_SHA
                                        + "|"
                                        + canonical(query))
                                    .getBytes(StandardCharsets.UTF_8));
                        Path file = cache.resolve(key + ".json");
                        JSONArray policy = null;
                        if (Files.isRegularFile(file)) {
                          try {
                            JSONObject cached = new JSONObject(Files.readString(file));
                            policy = validatePolicy(cached, query.getString("id"));
                          } catch (IOException | RuntimeException invalidCache) {
                            policy = null;
                          }
                        }
                        if (policy == null) {
                          JSONObject response = engine.request(query);
                          if (cancelled.getAsBoolean()) throw new CancellationException();
                          policy = validatePolicy(response, query.getString("id"));
                          JSONObject value =
                              new JSONObject()
                                  .put("id", query.getString("id"))
                                  .put("humanPolicy", policy);
                          Path temporary = Files.createTempFile(cache, key, ".tmp");
                          try {
                            Files.writeString(temporary, value.toString());
                            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                          } finally {
                            Files.deleteIfExists(temporary);
                          }
                        }
                        synchronized (policies) {
                          policies.getJSONArray(profile).put(turn, policy);
                        }
                      }
                      return null;
                    } catch (Exception error) {
                      throw new java.util.concurrent.CompletionException(error);
                    }
                  }));
        }
        for (var future : batch) {
          try {
            future.get();
          } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof java.util.concurrent.CompletionException
                && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof Exception failure) throw failure;
            throw error;
          }
          progress.accept(++done);
        }
      }
    } finally {
      pool.shutdownNow();
      if (!pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        throw new IOException("HumanSL policy queries did not terminate");
    }
    return policies;
  }

  private static JSONArray validatePolicy(JSONObject response, String id) {
    if (response.has("error")
        || response.has("warning")
        || response.optBoolean("isDuringSearch")
        || !id.equals(response.optString("id")))
      throw new IllegalArgumentException("HumanSL40.invalidResponse");
    // The dedicated policy engine uses the verified human network as its main model too.
    JSONArray policy = response.optJSONArray("humanPolicy");
    if (policy == null) policy = response.optJSONArray("policy");
    if (policy == null || policy.length() != 362)
      throw new IllegalArgumentException("HumanSL40.invalidResponse");
    double total = 0;
    for (int i = 0; i < policy.length(); i++) {
      double p = policy.getDouble(i);
      if (!Double.isFinite(p) || (p < 0 && p != -1) || p > 1)
        throw new IllegalArgumentException("HumanSL40.invalidResponse");
      total += Math.max(0, p);
    }
    if (Math.abs(total - 1) > .001) throw new IllegalArgumentException("HumanSL40.invalidResponse");
    return policy;
  }

  public static PlayerStrengthEstimator.Report report(Capture captured, JSONObject policies) {
    JSONArray base = captured.base();
    JSONObject game = captured.game();
    JSONObject prefix = new JSONObject();
    for (String profile : HumanSlStrength40.PROFILES) {
      JSONArray source = policies.getJSONArray(profile), selected = new JSONArray();
      for (int i = 0; i < base.length(); i++) selected.put(source.get(i));
      prefix.put(profile, selected);
    }
    return PlayerStrengthEstimator.humanSlReport(
        HumanSlStrength40.extract(game.getJSONArray("moves"), base, prefix, 19, true));
  }

  public static boolean stillCurrent(Capture captured) {
    if (Lizzie.board == null || Lizzie.board.getHistory().getStart() != captured.start())
      return false;
    try {
      // Only history owns a result; later analysis updates must not change the frozen samples.
      Capture current = capture(captured.analysisCaptured() ? 210 : Integer.MAX_VALUE, false);
      return canonical(current.game()).equals(canonical(captured.game()));
    } catch (RuntimeException changed) {
      return false;
    }
  }

  public static String policyCommand(Path engine, Path human, Path cache) throws IOException {
    Files.createDirectories(cache);
    Path config = cache.resolve("policy-analysis.cfg");
    try (var resource =
        HumanSlStrength40.class.getResourceAsStream("/models/strength/humansl40_policy.cfg")) {
      if (resource == null) throw new IOException("Missing HumanSL policy configuration");
      Files.copy(resource, config, StandardCopyOption.REPLACE_EXISTING);
    }
    List<String> command =
        List.of(
            engine.toAbsolutePath().toString(),
            "analysis",
            "-model",
            human.toAbsolutePath().toString(),
            "-config",
            config.toString(),
            "-override-config",
            "logDir=" + cache.resolve("engine-logs"),
            "-quit-without-waiting");
    return command.stream()
        .map(HumanSlStrengthReuseSession::quoteCommandArgument)
        .collect(Collectors.joining(" "));
  }

  static String quoteCommandArgument(String argument) {
    // Utils.splitCommand is not a shell: backslashes are literal, including Windows paths.
    // Alternate quote groups for a literal double quote instead of adding an escape character.
    return "\"" + argument.replace("\"", "\"'\"'\"") + "\"";
  }

  static String fingerprint(HumanSlAnalysisRunner.PreparedLaunch launch) throws IOException {
    JSONArray configs = new JSONArray();
    List<String> commands = launch.commands();
    for (int i = 0; i < commands.size() - 1; i++) {
      if (commands.get(i).equals("-config") || commands.get(i).equals("--config"))
        configs.put(fileHash(Path.of(commands.get(++i))));
    }
    if (configs.isEmpty()) throw new IOException("Missing HumanSL policy configuration");
    return canonical(
        new JSONObject()
            .put("executableSha256", fileHash(launch.executable()))
            .put("configSha256", configs)
            .put("commands", new JSONArray(commands)));
  }

  static String fileHash(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var stream = Files.newInputStream(path)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = stream.read(buffer)) >= 0) digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String canonical(Object value) {
    if (value instanceof JSONObject object) {
      return object.keySet().stream()
          .sorted()
          .map(k -> JSONObject.quote(k) + ":" + canonical(object.get(k)))
          .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
    if (value instanceof JSONArray array) {
      List<String> parts = new ArrayList<>();
      for (int i = 0; i < array.length(); i++) parts.add(canonical(array.get(i)));
      return String.join(",", parts).transform(s -> "[" + s + "]");
    }
    return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
  }
}
