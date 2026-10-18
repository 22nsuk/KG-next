package featurecat.lizzie.teacher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local anti-fabrication check for the AI commentary.
 *
 * <p>After the LLM finishes, verify that every coordinate mentioned as a recommendation is present
 * in the KataGo evidence (top candidates, the recorded move, or one of the supplied PVs), that no
 * impossible winrate percentages were written, and that the wording is not more absolute than the
 * evidence supports. This mirrors the teacher-side verifier from the original integration while
 * staying a small, dependency-free class.
 */
public final class TeacherVerifier {
  private static final Pattern COORDINATE =
      // Extract coordinate-shaped tokens before validating them. Restricting this pattern to
      // legal coordinates would silently ignore invented points such as I9, D0 or D40.
      Pattern.compile("(?<![A-Za-z0-9])([A-Za-z][+-]?[0-9]+(?:\\.[0-9]+)?)(?![A-Za-z0-9])");
  private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
  private static final Pattern SCORE_PREFIX =
      Pattern.compile(
          "(?:score(?:Lead|\\s+(?:loss|lead))?|目差|亏|领先|落后|집\\s*손실|점수\\s*(?:차이|손실))"
              + "\\s*[:=：]?\\s*([-+]?\\d+(?:\\.\\d+)?)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern SCORE_SUFFIX =
      Pattern.compile(
          "([-+]?\\d+(?:\\.\\d+)?)\\s*(?:points?\\b|目|집|점)", Pattern.CASE_INSENSITIVE);
  private static final Pattern ABSOLUTE_WORDING =
      Pattern.compile(
          "明显恶手|必败|唯一|绝对|certainly|only\\s+move|forced|유일한\\s*수|무조건|절대",
          Pattern.CASE_INSENSITIVE);

  private TeacherVerifier() {}

  public static final class Result {
    public final List<String> violations = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public boolean ok() {
      return violations.isEmpty();
    }

    public boolean hasNotes() {
      return !violations.isEmpty() || !warnings.isEmpty();
    }
  }

  /** 校验 LLM 输出：坐标必须来自证据（候选/实战手/PV），百分比不可能超 100，措辞不过度绝对。 */
  public static Result verify(String markdown, TeacherEvidence.Position position) {
    return verify(markdown, position == null ? List.of() : List.of(position));
  }

  /**
   * Verifies commentary generated from one or more positions.
   *
   * <p>Range and whole-game reviews legitimately mention coordinates from several positions. An
   * empty position list therefore disables coordinate validation while retaining the independent
   * percentage and wording checks.
   */
  public static Result verify(String markdown, Collection<TeacherEvidence.Position> positions) {
    Result result = new Result();
    if (markdown == null || markdown.isBlank()) {
      return result;
    }
    Set<String> allowed = allowedCoordinates(positions);
    boolean hasEvidence = positions != null && positions.stream().anyMatch(position -> position != null);
    if (hasEvidence) {
      Matcher coordinateMatcher = COORDINATE.matcher(markdown);
      while (coordinateMatcher.find()) {
        String coordinate = coordinateMatcher.group(1).toUpperCase(Locale.ROOT);
        if (!allowed.contains(coordinate)) {
          result.violations.add(
              "Unsupported coordinate "
                  + coordinate
                  + ": not in the supplied KataGo or game-record evidence.");
        }
      }
    }
    Matcher percentMatcher = PERCENT.matcher(markdown);
    List<Double> knownPercentages = new ArrayList<>();
    List<Double> knownScores = new ArrayList<>();
    if (hasEvidence) {
      collectNumbers(positions, knownPercentages, knownScores);
    }
    while (percentMatcher.find()) {
      try {
        double value = Double.parseDouble(percentMatcher.group(1));
        if (value > 100) {
          result.violations.add("Impossible winrate percentage " + value + "%.");
        } else if (hasEvidence && !nearAny(value, knownPercentages, 0.6)) {
          result.warnings.add(
              "Percentage " + value + "% is not close to supplied winrate/loss evidence.");
        }
      } catch (NumberFormatException ignored) {
        // Non-numeric match is impossible by the pattern; ignore defensively.
      }
    }
    if (hasEvidence) {
      Set<Double> scoreMentions = new LinkedHashSet<>();
      collectScoreMentions(SCORE_PREFIX.matcher(markdown), scoreMentions);
      collectScoreMentions(SCORE_SUFFIX.matcher(markdown), scoreMentions);
      for (double value : scoreMentions) {
        if (!nearAny(value, knownScores, 0.8)) {
          result.warnings.add(
              "Score/point value " + value + " is not close to supplied score evidence.");
        }
      }
    }
    if (ABSOLUTE_WORDING.matcher(markdown).find()) {
      result.warnings.add(
          "The wording sounds too absolute; soften it unless the evidence clearly supports it.");
    }
    return result;
  }

  private static void collectNumbers(
      Collection<TeacherEvidence.Position> positions,
      List<Double> percentages,
      List<Double> scores) {
    for (TeacherEvidence.Position position : positions) {
      if (position == null) {
        continue;
      }
      position.actualWinrateLoss.ifPresent(percentages::add);
      position.actualScoreLoss().ifPresent(scores::add);
      for (TeacherEvidence.Candidate candidate : position.candidates) {
        if (Double.isFinite(candidate.winrate)) {
          percentages.add(candidate.winrate);
        }
        if (Double.isFinite(candidate.scoreLead)) {
          scores.add(candidate.scoreLead);
          // Natural-language "behind by 3 points" uses a positive magnitude for scoreLead=-3.
          scores.add(Math.abs(candidate.scoreLead));
        }
      }
    }
  }

  private static void collectScoreMentions(Matcher matcher, Set<Double> values) {
    while (matcher.find()) {
      values.add(Double.parseDouble(matcher.group(1)));
    }
  }

  private static boolean nearAny(double value, List<Double> known, double tolerance) {
    return known.stream().anyMatch(target -> Math.abs(value - target) <= tolerance);
  }

  /** 证据允许的坐标集合：候选点 + 实战手 + 各候选 PV 序列。 */
  private static Set<String> allowedCoordinates(Collection<TeacherEvidence.Position> positions) {
    Set<String> allowed = new HashSet<>();
    if (positions == null) {
      return allowed;
    }
    for (TeacherEvidence.Position position : positions) {
      if (position == null) {
        continue;
      }
      addCoordinate(allowed, position.actualMove);
      if (position.board != null) {
        for (TeacherBoardContext.Group group : position.board.groups) {
          allowed.addAll(group.stones());
          allowed.addAll(group.liberties());
        }
        for (TeacherBoardContext.Line line : position.board.lines) {
          for (String move : line.moves()) {
            addCoordinate(allowed, move);
          }
          if (line.frames().size() > 1) {
            TeacherBoardContext.Frame last = line.frames().get(line.frames().size() - 1);
            for (TeacherBoardContext.Group group :
                TeacherBoardContext.groups(
                    last.stones(), position.board.width, position.board.height)) {
              if (group.liberties().size() <= 2) {
                allowed.addAll(group.stones());
                allowed.addAll(group.liberties());
              }
            }
          }
        }
        for (TeacherEvidence.Candidate candidate : position.candidates) {
          addCoordinate(allowed, candidate.coordinate);
        }
        // Raw SGF/PV suffixes may have failed replay. Do not admit them through the fallback.
        continue;
      }
      for (String move : position.playedContinuation) {
        addCoordinate(allowed, move);
      }
      for (TeacherEvidence.Candidate candidate : position.candidates) {
        addCoordinate(allowed, candidate.coordinate);
        if (candidate.variation != null) {
          for (String move : candidate.variation) {
            addCoordinate(allowed, move);
          }
        }
      }
    }
    return allowed;
  }

  private static void addCoordinate(Set<String> coordinates, String coordinate) {
    if (coordinate != null && !coordinate.isEmpty()) {
      coordinates.add(coordinate.toUpperCase(Locale.ROOT));
    }
  }
}
