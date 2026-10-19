package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed-budget feature recipe for the experimental teacher-label HumanSL model. */
final class HumanSlStrength40 {
  static final String RESOURCE = "/models/strength/humansl40_booster.json";
  static final String HUMAN_SHA =
      "637746e44f0efe00ad1245a50aa9bbf0716efe364c43965ead97bd6835d84ab5";
  static final String[] PROFILES = {"rank_1d", "rank_5d", "rank_9d"};
  private static final String[] NAMES = {
    "first_choice_rate",
    "top3_rate",
    "top5_rate",
    "average_ai_rank_fit",
    "excellent_rate",
    "good_move_rate",
    "non_inaccuracy_rate",
    "match_rate",
    "non_mistake_rate",
    "non_blunder_rate",
    "median_loss_fit",
    "p75_loss_fit",
    "difficulty_fit",
    "opening_good_move_rate",
    "middlegame_good_move_rate",
    "endgame_good_move_rate",
    "first_choice_x_difficulty",
    "good_move_x_difficulty",
    "match_x_difficulty",
    "top5_x_difficulty",
    "capped_mean_loss_3",
    "capped_mean_loss_5",
    "capped_mean_loss_10",
    "mean_log_loss",
    "top3_loss_share",
    "lost_position_fraction",
    "reversal_fraction",
    "adjacent_root_fraction",
    "rank_1d_mean_surprisal",
    "rank_1d_difficulty",
    "rank_1d_coverage_mean",
    "rank_1d_reliable_fraction",
    "rank_5d_mean_surprisal",
    "rank_5d_difficulty",
    "rank_5d_coverage_mean",
    "rank_5d_reliable_fraction",
    "rank_9d_mean_surprisal",
    "rank_9d_difficulty",
    "rank_9d_coverage_mean",
    "rank_9d_reliable_fraction"
  };

  private HumanSlStrength40() {}

  static String[] featureNames() {
    return NAMES.clone();
  }

  record Side(String color, int samples, double rank, double[] features) {
    public Side {
      features = features.clone();
    }

    @Override
    public double[] features() {
      return features.clone();
    }
  }

  static final class Move {
    int number, rank;
    String color, coordinate;
    double loss, winrateLoss, complexity, actorWinrate, delta;
    boolean adjacent, reversal;
    double[][] human = new double[3][];
  }

  static List<Side> evaluate(
      JSONArray moves, JSONArray base, JSONObject policies, int size) {
    List<Move> all = extract(moves, base, policies, size, false);
    List<Side> sides = new ArrayList<>();
    for (String color : new String[] {"B", "W"}) {
      List<Move> selected = all.stream().filter(m -> m.color.equals(color)).toList();
      if (selected.size() < 6)
        throw new IllegalArgumentException("At least 6 valid moves per side are required");
      double[] features = features(selected);
      sides.add(new Side(color, selected.size(), predict(selected), features));
    }
    return sides;
  }

  static double predict(List<Move> moves) {
    if (moves.size() < 6) return Double.NaN;
    return XGBoostStrengthModel.predictUncalibrated(RESOURCE, features(moves), NAMES);
  }

  static List<Move> extract(
      JSONArray moves, JSONArray base, JSONObject policies, int size, boolean allowMissing) {
    if (size != 19 || moves.length() > 210 || base.length() != moves.length() + 1) {
      throw new IllegalArgumentException(
          "Expected 19x19, at most 210 moves and all base positions");
    }
    for (String profile : PROFILES) {
      if (policies.getJSONArray(profile).length() != base.length()) {
        throw new IllegalArgumentException("Incomplete HumanSL profile: " + profile);
      }
    }
    List<Move> all = new ArrayList<>();
    for (int turn = 0; turn < moves.length(); turn++) {
      if (allowMissing && !HumanSlStrengthReuseSession.hasAnalysis(base, turn)) continue;
      JSONArray action = moves.getJSONArray(turn);
      JSONObject before = base.getJSONObject(turn), after = base.getJSONObject(turn + 1);
      List<JSONObject> candidates = candidates(before);
      if (candidates.isEmpty()) throw new IllegalArgumentException("No candidates at turn " + turn);
      Move move = new Move();
      move.number = turn + 1;
      move.color = action.getString(0);
      if (!move.color.equals("B") && !move.color.equals("W")) {
        throw new IllegalArgumentException("Invalid move color");
      }
      String played = action.getString(1);
      move.coordinate = played;
      move.rank = 999;
      JSONObject actual = null;
      for (int i = 0; i < candidates.size(); i++) {
        if (played.equalsIgnoreCase(candidates.get(i).getString("move"))) {
          actual = candidates.get(i);
          move.rank = i;
          break;
        }
      }
      double top = score(candidates.get(0));
      double rootBefore = score(before.getJSONObject("rootInfo"));
      double rootAfter = score(after.getJSONObject("rootInfo"));
      move.adjacent = actual == null;
      move.loss = Math.max(0, actual == null ? rootBefore + rootAfter : top - score(actual));
      move.actorWinrate = before.getJSONObject("rootInfo").getDouble("winrate");
      double topWinrate = candidates.get(0).optDouble("winrate", Double.NaN);
      double playedWinrate = actual == null ? Double.NaN : actual.optDouble("winrate", Double.NaN);
      move.winrateLoss =
          Math.max(
              0,
              100
                  * (validWinrate(topWinrate) && validWinrate(playedWinrate)
                      ? topWinrate - playedWinrate
                      : move.actorWinrate + after.getJSONObject("rootInfo").getDouble("winrate") - 1));
      double blackBefore = rootBefore * (move.color.equals("B") ? 1 : -1);
      String nextColor =
          turn + 1 < moves.length()
              ? moves.getJSONArray(turn + 1).getString(0)
              : (move.color.equals("B") ? "W" : "B");
      double blackAfter = rootAfter * (nextColor.equals("B") ? 1 : -1);
      move.delta = blackAfter - blackBefore;
      double priorSum = 0, lossSum = 0;
      for (JSONObject candidate : candidates) {
        double prior = Math.max(0, candidate.optDouble("prior", 0));
        if (candidate.optInt("order", 0) < 999 && prior > 0) {
          priorSum += prior;
          lossSum += prior * Math.max(0, top - score(candidate));
        }
      }
      move.complexity =
          candidates.size() < 2
              ? 0
              : clamp(
                  priorSum > 0
                      ? lossSum / priorSum
                      : Math.max(0, top - score(candidates.get(1))) / 1.2,
                  0,
                  1);
      for (int p = 0; p < 3; p++) {
        move.human[p] =
            human(candidates, policies.getJSONArray(PROFILES[p]).getJSONArray(turn), played, size);
      }
      all.add(move);
    }
    for (int end = 3; end < all.size(); end++) {
      List<Double> large = new ArrayList<>();
      int start = Math.max(0, end - 7), changes = 0;
      // Missing cached positions must not turn distant swings into an adjacent reversal window.
      while (start < end && all.get(start).number < all.get(end).number - 7) start++;
      boolean contiguous = true;
      for (int i = start + 1; i <= end; i++)
        if (all.get(i).number != all.get(i - 1).number + 1) contiguous = false;
      if (!contiguous) continue;
      for (int i = start; i <= end; i++)
        if (Math.abs(all.get(i).delta) >= 8) large.add(all.get(i).delta);
      for (int i = 1; i < large.size(); i++) if (large.get(i - 1) * large.get(i) < 0) changes++;
      if (changes >= 3) for (int i = start; i <= end; i++) all.get(i).reversal = true;
    }
    return all;
  }

  private static List<JSONObject> candidates(JSONObject response) {
    if (response.has("error")) throw new IllegalArgumentException(response.optString("error"));
    JSONArray array = response.getJSONArray("moveInfos");
    List<JSONObject> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) result.add(array.getJSONObject(i));
    result.sort(Comparator.comparingInt(m -> m.optInt("order", 0)));
    return result;
  }

  private static double score(JSONObject info) {
    double value =
        info.has("scoreMean") ? info.getDouble("scoreMean") : info.getDouble("scoreLead");
    if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite candidate score");
    return value;
  }

  private static boolean validWinrate(double value) {
    return Double.isFinite(value) && value >= 0 && value <= 1;
  }

  static double probability(JSONArray policy, String move, int size) {
    if (policy.length() != size * size + 1)
      throw new IllegalArgumentException("Wrong human policy size");
    int index;
    if ("pass".equalsIgnoreCase(move)) index = size * size;
    else {
      String coordinate = move.toUpperCase(java.util.Locale.ROOT);
      int x = "ABCDEFGHJKLMNOPQRST".indexOf(coordinate.charAt(0));
      int y = size - Integer.parseInt(coordinate.substring(1));
      if (x < 0 || x >= size || y < 0 || y >= size)
        throw new IllegalArgumentException("Invalid coordinate");
      index = y * size + x;
    }
    double value = policy.getDouble(index);
    if (!Double.isFinite(value) || value < 0 || value > 1)
      throw new IllegalArgumentException("Missing/invalid human probability for " + move);
    return value;
  }

  // Coverage measures searched human probability mass, not tactical certainty.
  static double[] human(List<JSONObject> candidates, JSONArray policy, String played, int size) {
    double mass = 0, good = 0, top = score(candidates.get(0));
    for (JSONObject candidate : candidates) {
      double p = probability(policy, candidate.getString("move"), size);
      mass += p;
      if (Math.max(0, top - score(candidate)) <= 1.2) good += p;
    }
    mass = clamp(mass, 0, 1);
    double lower = Math.max(mass - good, 0), upper = Math.min(1 - good, 1);
    return new double[] {
      -Math.log(Math.max(probability(policy, played, size), 1e-9)),
      mass >= .9 ? (lower + upper) / 2 : Double.NaN,
      mass,
      mass >= .9 ? 1 : 0
    };
  }

  private static double[] features(List<Move> moves) {
    int n = moves.size();
    double first = rate(moves, m -> m.rank == 0),
        top3 = rate(moves, m -> m.rank < 3),
        top5 = rate(moves, m -> m.rank < 5);
    double excellent = rate(moves, m -> m.loss < .2), good = rate(moves, m -> m.loss < 1.2);
    double mistake = rate(moves, m -> m.loss >= 4), blunder = rate(moves, m -> m.loss >= 10);
    double match = rounded(.45 * first + .45 * good + .10 * (1 - mistake), 4);
    double difficulty =
        clamp(
            (rounded(100 * mean(moves.stream().mapToDouble(m -> m.complexity).toArray()), 1) - 25)
                / 35,
            0,
            1);
    double[] losses = moves.stream().mapToDouble(m -> m.loss).toArray();
    double[] sorted = losses.clone();
    Arrays.sort(sorted);
    first = rounded(first, 4);
    top3 = rounded(top3, 4);
    top5 = rounded(top5, 4);
    good = rounded(good, 4);
    double averageRank =
        rounded(
            mean(
                moves.stream()
                    .mapToDouble(m -> m.rank >= 999 ? 10 : Math.min(m.rank + 1, 10))
                    .toArray()),
            3);
    double[] result = new double[40];
    double[] standard = {
      first,
      top3,
      top5,
      1 / (1 + averageRank),
      rounded(excellent, 4),
      good,
      1 - rounded(rate(moves, m -> m.loss >= 1.2 && m.loss < 4), 4),
      match,
      1 - rounded(mistake, 4),
      1 - rounded(blunder, 4),
      1 / (1 + Math.min(rounded(quantile(sorted, .5), 3), 50)),
      1 / (1 + Math.min(rounded(quantile(sorted, .75), 3), 50)),
      difficulty,
      phase(moves, 1, 60, good),
      phase(moves, 61, 160, good),
      phase(moves, 161, 210, good),
      first * difficulty,
      good * difficulty,
      match * difficulty,
      top5 * difficulty
    };
    System.arraycopy(standard, 0, result, 0, standard.length);
    for (int j = 0; j < 3; j++) {
      double cap = new double[] {3, 5, 10}[j];
      result[20 + j] = mean(Arrays.stream(losses).map(v -> Math.min(v, cap)).toArray());
    }
    result[23] = mean(Arrays.stream(losses).map(Math::log1p).toArray());
    double total = Arrays.stream(losses).sum();
    result[24] = total > 0 ? Arrays.stream(sorted).skip(Math.max(0, n - 3)).sum() / total : 0;
    result[25] = rate(moves, m -> m.actorWinrate < .05);
    result[26] = rate(moves, m -> m.reversal);
    result[27] = rate(moves, m -> m.adjacent);
    for (int p = 0; p < 3; p++)
      for (int j = 0; j < 4; j++) {
        final int profile = p, field = j;
        result[28 + 4 * p + j] =
            mean(
                moves.stream()
                    .mapToDouble(m -> m.human[profile][field])
                    .filter(Double::isFinite)
                    .toArray());
      }
    return result;
  }

  private static double phase(List<Move> moves, int first, int last, double fallback) {
    List<Move> phase = moves.stream().filter(m -> m.number >= first && m.number <= last).toList();
    return phase.isEmpty() ? fallback : rounded(rate(phase, m -> m.loss < 1.2), 4);
  }

  private static double rate(List<Move> moves, java.util.function.Predicate<Move> test) {
    return (double) moves.stream().filter(test).count() / moves.size();
  }

  private static double mean(double[] values) {
    return values.length == 0 ? Double.NaN : Arrays.stream(values).sum() / values.length;
  }

  private static double quantile(double[] sorted, double p) {
    double index = (sorted.length - 1) * p;
    int left = (int) index, right = Math.min(left + 1, sorted.length - 1);
    return sorted[left] + (sorted[right] - sorted[left]) * (index - left);
  }

  private static double rounded(double value, int places) {
    double scale = Math.pow(10, places);
    return Math.rint(value * scale) / scale;
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}
