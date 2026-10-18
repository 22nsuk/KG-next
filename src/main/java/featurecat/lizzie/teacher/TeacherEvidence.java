package featurecat.lizzie.teacher;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.teacher.knowledge.LocalPatternMatcher;
import featurecat.lizzie.teacher.knowledge.MatchEngine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/** Immutable KataGo evidence copied from the SGF tree before an AI request starts. */
public final class TeacherEvidence {
  static final int MAX_CANDIDATES = 3;
  static final int MAX_PV_MOVES = 12;
  static final int MAX_RANGE_POSITIONS = 40;
  static final int MAX_RECORDED_CONTINUATION = 5;
  static final int MAX_KNOWLEDGE_HISTORY = 40;

  private TeacherEvidence() {}

  public static Optional<Position> current(BoardHistoryNode node) {
    return position(node).flatMap(position -> freeze(node, position));
  }

  public static Range mainLine(BoardHistoryNode root, int firstMove, int lastMove) {
    if (root == null) {
      return new Range(Collections.emptyList(), 0, 0);
    }
    int normalizedFirst = Math.max(1, firstMove);
    int normalizedLast = Math.max(normalizedFirst, lastMove);
    ArrayList<Position> available = new ArrayList<>();
    Map<Position, BoardHistoryNode> sources = new IdentityHashMap<>();
    BoardHistoryNode parent = root;
    while (parent != null && parent.next().isPresent()) {
      BoardHistoryNode child = parent.next().get();
      if (child.getData() == null) {
        break;
      }
      int moveNumber = child.getData().moveNumber;
      if (moveNumber >= normalizedFirst
          && moveNumber <= normalizedLast
          && isRecordedAction(parent.getData(), child)) {
        Optional<Position> evidence = position(parent);
        if (evidence.isPresent()) {
          available.add(evidence.get());
          sources.put(evidence.get(), parent);
        }
      }
      if (moveNumber > normalizedLast) {
        break;
      }
      parent = child;
    }
    List<Position> selected = selectKeyPositions(available, MAX_RANGE_POSITIONS);
    ArrayList<Position> frozen = new ArrayList<>();
    for (Position position : selected) {
      freeze(sources.get(position), position).ifPresent(frozen::add);
    }
    return new Range(frozen, available.size(), Math.max(0, available.size() - frozen.size()));
  }

  public static Range wholeGame(BoardHistoryNode root) {
    return mainLine(root, 1, Integer.MAX_VALUE);
  }

  static Range selectKeyPositions(Range range, int limit) {
    if (range.positions.size() <= limit) {
      return range;
    }
    List<Position> selected = selectKeyPositions(range.positions, limit);
    return new Range(selected, range.analyzedPositions, range.analyzedPositions - selected.size());
  }

  private static Optional<Position> freeze(BoardHistoryNode node, Position position) {
    TeacherBoardContext board = TeacherBoardContext.capture(node, position);
    if (board == null) {
      return Optional.empty();
    }
    return Optional.of(position.withContext(board, knowledgeMatchText(node, position, board)));
  }

  static Optional<Position> position(BoardHistoryNode parent) {
    if (parent == null || parent.getData() == null) {
      return Optional.empty();
    }
    BoardData data = parent.getData();
    List<MoveData> moves = stableCopy(data.bestMoves);
    if (moves.isEmpty()) {
      return Optional.empty();
    }

    ArrayList<Candidate> candidates = new ArrayList<>();
    for (MoveData move : moves) {
      Candidate candidate = candidate(candidates.size() + 1, move, data.blackToPlay);
      if (candidate != null) {
        candidates.add(candidate);
      }
      if (candidates.size() >= MAX_CANDIDATES) {
        break;
      }
    }
    if (candidates.isEmpty()) {
      return Optional.empty();
    }

    BoardHistoryNode child = parent.next().orElse(null);
    String actualMove = isRecordedAction(data, child) ? actualMove(child) : "";
    if (!actualMove.isEmpty()
        && candidates.stream().noneMatch(candidate -> candidate.coordinate.equals(actualMove))) {
      for (int index = 0; index < moves.size(); index++) {
        MoveData move = moves.get(index);
        if (move != null && actualMove.equals(normalizeCoordinate(move.coordinate))) {
          Candidate actual = candidate(index + 1, move, data.blackToPlay);
          if (actual != null) {
            candidates.add(actual);
          }
          break;
        }
      }
    }
    OptionalDouble actualLoss = OptionalDouble.empty();
    if (!actualMove.isEmpty()) {
      Candidate best = candidates.get(0);
      for (Candidate move : candidates) {
        if (actualMove.equals(move.coordinate)
            && Double.isFinite(move.winrate)
            && Double.isFinite(best.winrate)) {
          actualLoss = OptionalDouble.of(Math.max(0.0, best.winrate - move.winrate));
          break;
        }
      }
    }

    ArrayList<String> continuation = new ArrayList<>();
    BoardHistoryNode walk = child;
    BoardData previous = data;
    while (walk != null && continuation.size() < MAX_RECORDED_CONTINUATION) {
      if (!isRecordedAction(previous, walk)) {
        break;
      }
      String move = actualMove(walk);
      if (move.isEmpty()) {
        break;
      }
      continuation.add(move);
      previous = walk.getData();
      walk = walk.next().orElse(null);
    }

    return Optional.of(
        new Position(
            data.moveNumber,
            data.blackToPlay ? "B" : "W",
            data.getPlayouts(),
            actualMove,
            actualLoss,
            candidates,
            continuation));
  }

  private static Candidate candidate(int rank, MoveData move, boolean blackToPlay) {
    String coordinate =
        TeacherBoardContext.normalizeMove(
            move == null ? null : move.coordinate, Board.boardWidth, Board.boardHeight);
    if (coordinate.isEmpty()) {
      return null;
    }
    ArrayList<String> variation = new ArrayList<>();
    if (move.variation != null) {
      for (String pvMove : move.variation) {
        // Preserve invalid tokens as a stopping point; skipping them would change move colors.
        variation.add(normalizeCoordinate(pvMove));
        if (variation.size() >= MAX_PV_MOVES) {
          break;
        }
      }
    }
    return new Candidate(
        rank,
        coordinate,
        move.winrate >= 0 && move.winrate <= 100 ? finiteOrNaN(move.winrate) : Double.NaN,
        finiteOrNaN(
            move.scoreMeanIsBlackPerspective && !blackToPlay ? -move.scoreMean : move.scoreMean),
        Math.max(0, move.playouts),
        variation);
  }

  private static String actualMove(BoardHistoryNode child) {
    if (child == null || child.getData() == null) {
      return "";
    }
    BoardData data = child.getData();
    if (data.dummy) {
      return "";
    }
    if (data.isPassNode()) {
      return "pass";
    }
    if (!data.isMoveNode() || data.lastMove.isEmpty()) {
      return "";
    }
    int[] move = data.lastMove.get();
    if (move.length != 2 || !Board.isValid(move[0], move[1])) {
      return "";
    }
    return normalizeCoordinate(Board.convertCoordinatesToName(move[0], move[1]));
  }

  private static boolean isRecordedAction(BoardData before, BoardHistoryNode child) {
    if (before == null || child == null || child.getData() == null) {
      return false;
    }
    BoardData after = child.getData();
    return after.isHistoryActionNode()
        && !after.dummy
        && after.moveNumber == before.moveNumber + 1
        && after.lastMoveColor == (before.blackToPlay ? Stone.BLACK : Stone.WHITE)
        && after.blackToPlay != before.blackToPlay
        && !child.hasRemovedStone()
        && (child.extraStones == null || child.extraStones.isEmpty());
  }

  private static List<MoveData> stableCopy(List<MoveData> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return new ArrayList<>(source);
      } catch (RuntimeException concurrentUpdate) {
        Thread.yield();
      }
    }
    return Collections.emptyList();
  }

  private static List<Position> selectKeyPositions(List<Position> positions, int limit) {
    if (positions.size() <= limit) {
      return Collections.unmodifiableList(new ArrayList<>(positions));
    }

    ArrayList<Position> ranked = new ArrayList<>(positions);
    ranked.sort(
        Comparator.comparingDouble(TeacherEvidence::importance)
            .reversed()
            .thenComparingInt(position -> position.moveNumber));
    LinkedHashSet<Position> selected = new LinkedHashSet<>();
    selected.add(positions.get(0));
    selected.add(positions.get(positions.size() - 1));
    for (Position position : ranked) {
      if (selected.size() >= limit) {
        break;
      }
      selected.add(position);
    }
    ArrayList<Position> chronological = new ArrayList<>(selected);
    chronological.sort(Comparator.comparingInt(position -> position.moveNumber));
    return Collections.unmodifiableList(chronological);
  }

  private static double importance(Position position) {
    if (position.actualWinrateLoss.isPresent()) {
      return position.actualWinrateLoss.getAsDouble();
    }
    return position.candidates.isEmpty() ? 0.0 : position.candidates.get(0).visits / 1_000_000.0;
  }

  private static String knowledgeMatchText(
      BoardHistoryNode node, Position position, TeacherBoardContext board) {
    // The existing knowledge matcher models a square board with A-T coordinates.
    if (board.width != board.height || board.width > 19) {
      return "";
    }
    MatchEngine.KnowledgeMatchQuery query = knowledgeQuery(node, position, board);
    List<MatchEngine.KnowledgeMatch> matches;
    try {
      matches = MatchEngine.searchKnowledgeMatchEngine(query);
    } catch (RuntimeException unavailableKnowledge) {
      // Optional catalog failures must not discard the frozen KataGo and board evidence.
      return "";
    }
    if (matches == null || matches.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    int shown = 0;
    for (MatchEngine.KnowledgeMatch match : matches) {
      if (match == null
          || match.title == null
          || match.title.isEmpty()
          || !hasGroundedKnowledgeEvidence(match)) {
        continue;
      }
      if (shown > 0) {
        builder.append("\n\n");
      }
      builder.append(MatchEngine.formatKnowledgeMatchForPrompt(match));
      if (++shown >= 2) {
        break;
      }
    }
    return builder.toString();
  }

  static MatchEngine.KnowledgeMatchQuery knowledgeQuery(
      BoardHistoryNode node, Position position, TeacherBoardContext board) {
    MatchEngine.KnowledgeMatchQuery query = new MatchEngine.KnowledgeMatchQuery();
    query.boardSize = board.width;
    query.moveNumber = position.moveNumber;
    query.playedMove = position.actualMove;
    query.text = "";
    query.lossScore =
        position.actualScoreLoss().isPresent() ? position.actualScoreLoss().getAsDouble() : null;

    List<MatchEngine.GameMove> recent = new ArrayList<>();
    BoardHistoryNode cursor = node;
    while (cursor != null && recent.size() < MAX_KNOWLEDGE_HISTORY) {
      BoardHistoryNode previous = cursor.previous().orElse(null);
      if (previous == null || !isRecordedAction(previous.getData(), cursor)) {
        break;
      }
      String coordinate = actualMove(cursor);
      if (coordinate.isEmpty() || "pass".equals(coordinate)) {
        // Do not join unrelated opening sequences across setup nodes or passes.
        break;
      }
      int[] xy = cursor.getData().lastMove.orElseThrow();
      MatchEngine.GameMove move = new MatchEngine.GameMove();
      move.col = xy[0];
      move.row = xy[1];
      move.gtp = coordinate;
      recent.add(move);
      cursor = previous;
    }
    Collections.reverse(recent);
    query.recentMoves.addAll(recent);
    for (Candidate candidate : position.candidates) {
      query.candidateMoves.add(candidate.coordinate);
    }
    if (!position.candidates.isEmpty()) {
      TeacherBoardContext.Line line = board.candidateLine(position.candidates.get(0));
      if (line != null) {
        query.principalVariation.addAll(line.moves());
      }
    }
    for (TeacherBoardContext.Group group : board.groups) {
      for (String point : group.stones()) {
        LocalPatternMatcher.BoardSnapshotStone snapshot =
            new LocalPatternMatcher.BoardSnapshotStone();
        snapshot.point = point;
        snapshot.color = group.color();
        query.boardSnapshot.add(snapshot);
      }
    }
    return query;
  }

  static boolean hasGroundedKnowledgeEvidence(
      featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch match) {
    if (match == null || match.reason == null) {
      return false;
    }
    if ("exact".equals(match.confidence)) {
      return true;
    }
    int independentSignals = 0;
    for (String reason : match.reason) {
      if (reason == null) {
        continue;
      }
      if ("geometry-strong-local-shape".equals(reason)) {
        return true;
      }
      if (reason.startsWith("sequence-overlap:")) {
        try {
          if (Double.parseDouble(reason.substring("sequence-overlap:".length())) >= 3.0) {
            return true;
          }
        } catch (NumberFormatException ignored) {
          // Ignore malformed internal evidence rather than treating it as grounded.
        }
      }
      if (reason.startsWith("specific-text:")
          || reason.startsWith("answer-")
          || reason.startsWith("candidate:")
          || reason.startsWith("pv:")) {
        independentSignals++;
      }
    }
    return "strong".equals(match.confidence) && independentSignals >= 2;
  }

  private static String normalizeCoordinate(String coordinate) {
    if (coordinate == null) {
      return "";
    }
    String normalized = coordinate.trim().toUpperCase(Locale.ROOT);
    return "PASS".equals(normalized) ? "pass" : normalized;
  }

  private static double finiteOrNaN(double value) {
    return Double.isFinite(value) ? value : Double.NaN;
  }

  public static final class Position {
    public final int moveNumber;
    public final String toPlay;
    public final int playouts;
    public final String actualMove;
    public final OptionalDouble actualWinrateLoss;
    public final List<Candidate> candidates;
    final TeacherBoardContext board;
    final String knowledge;

    /** 实战手之后的棋谱实际续走序列（最多 5 手），无则空列表。 */
    public final List<String> playedContinuation;

    Position(
        int moveNumber,
        String toPlay,
        int playouts,
        String actualMove,
        OptionalDouble actualWinrateLoss,
        Collection<Candidate> candidates) {
      this(moveNumber, toPlay, playouts, actualMove, actualWinrateLoss, candidates, List.of());
    }

    Position(
        int moveNumber,
        String toPlay,
        int playouts,
        String actualMove,
        OptionalDouble actualWinrateLoss,
        Collection<Candidate> candidates,
        Collection<String> playedContinuation) {
      this(
          moveNumber,
          toPlay,
          playouts,
          actualMove,
          actualWinrateLoss,
          candidates,
          playedContinuation,
          null,
          "");
    }

    private Position(
        int moveNumber,
        String toPlay,
        int playouts,
        String actualMove,
        OptionalDouble actualWinrateLoss,
        Collection<Candidate> candidates,
        Collection<String> playedContinuation,
        TeacherBoardContext board,
        String knowledge) {
      this.moveNumber = Math.max(0, moveNumber);
      this.toPlay = "W".equals(toPlay) ? "W" : "B";
      this.playouts = Math.max(0, playouts);
      this.actualMove = actualMove == null ? "" : actualMove;
      this.actualWinrateLoss =
          actualWinrateLoss == null ? OptionalDouble.empty() : actualWinrateLoss;
      this.candidates =
          Collections.unmodifiableList(
              new ArrayList<>(candidates == null ? List.of() : candidates));
      this.playedContinuation =
          Collections.unmodifiableList(
              new ArrayList<>(playedContinuation == null ? List.of() : playedContinuation));
      this.board = board;
      this.knowledge = knowledge;
    }

    private Position withContext(TeacherBoardContext board, String knowledge) {
      return new Position(
          moveNumber,
          toPlay,
          playouts,
          actualMove,
          actualWinrateLoss,
          candidates,
          playedContinuation,
          board,
          knowledge);
    }

    OptionalDouble actualScoreLoss() {
      if (candidates.isEmpty() || !Double.isFinite(candidates.get(0).scoreLead)) {
        return OptionalDouble.empty();
      }
      for (Candidate candidate : candidates) {
        if (candidate.coordinate.equals(actualMove) && Double.isFinite(candidate.scoreLead)) {
          return OptionalDouble.of(Math.max(0.0, candidates.get(0).scoreLead - candidate.scoreLead));
        }
      }
      return OptionalDouble.empty();
    }
  }

  public static final class Candidate {
    public final int rank;
    public final String coordinate;
    public final double winrate;
    public final double scoreLead;
    public final int visits;
    public final List<String> variation;

    Candidate(
        int rank,
        String coordinate,
        double winrate,
        double scoreLead,
        int visits,
        Collection<String> variation) {
      this.rank = rank;
      this.coordinate = coordinate;
      this.winrate = winrate;
      this.scoreLead = scoreLead;
      this.visits = visits;
      this.variation =
          Collections.unmodifiableList(new ArrayList<>(variation == null ? List.of() : variation));
    }
  }

  public static final class Range {
    public final List<Position> positions;
    public final int analyzedPositions;
    public final int omittedPositions;

    Range(List<Position> positions, int analyzedPositions, int omittedPositions) {
      this.positions = Collections.unmodifiableList(new ArrayList<>(positions));
      this.analyzedPositions = Math.max(0, analyzedPositions);
      this.omittedPositions = Math.max(0, omittedPositions);
    }

    public boolean isEmpty() {
      return positions.isEmpty();
    }
  }
}
