package featurecat.lizzie.rules;

import featurecat.lizzie.analysis.KataGoRules;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Checks whether a setup edit makes a played move repeat a new ancestor on its own variation. */
final class SetupReplayKo {
  private enum Ko { SIMPLE, POSITIONAL, SITUATIONAL, SPIGHT }

  private final Ko ko;
  private final boolean rulesHaveButton;
  private final Map<BoardHistoryNode, Played> actionsBeforeSetup = new IdentityHashMap<>();

  SetupReplayKo(KataGoRules rules) {
    ko = switch (rules == null ? "" : rules.string(KataGoRules.KO)) {
      case "POSITIONAL" -> Ko.POSITIONAL;
      case "SITUATIONAL" -> Ko.SITUATIONAL;
      case "SPIGHT" -> Ko.SPIGHT;
      default -> Ko.SIMPLE;
    };
    rulesHaveButton = rules != null && rules.bool(KataGoRules.HAS_BUTTON);
  }

  private record Played(Zobrist original, Zobrist edited) {}

  /** Legacy moves can carry a setup applied after play. Freeze the move before either hash changes. */
  void recordActionBeforeSetup(BoardHistoryNode source, BoardData oldPlayed, BoardData played) {
    actionsBeforeSetup.put(source, new Played(oldPlayed.zobrist.clone(), played.zobrist.clone()));
  }

  // The history segment changes when a setup or a rule-specific pass lifts old ko bans.
  private record State(boolean blackToPlay, boolean hasButton, int segment, Zobrist simpleKoBan) {}
  private record Position(Zobrist stones, boolean blackToPlay, int segment) {}

  private static final class Frame {
    final BoardHistoryNode original;
    final BoardHistoryNode edited;
    State originalState;
    State editedState;
    Position addedOriginal;
    Position addedEdit;
    int nextChild;

    Frame(BoardHistoryNode original, BoardHistoryNode edited) {
      this.original = original;
      this.edited = edited;
    }
  }

  boolean introducesRepetition(BoardHistoryNode owner, BoardHistoryNode copiedOwner) {
    // Erasing the only setup point can expose positions before the owner.
    ArrayDeque<Frame> prefix = new ArrayDeque<>();
    for (BoardHistoryNode original = owner, edited = copiedOwner; original != null;
        original = original.previous().orElse(null), edited = edited.previous().orElse(null)) {
      prefix.push(new Frame(original, edited));
    }
    Map<Position, Set<Position>> history = new HashMap<>();
    Frame parent = null;
    while (!prefix.isEmpty()) {
      Frame next = prefix.pop();
      remember(parent, next, history);
      parent = next;
    }

    // Push/pop indexes only the active path without copying a history at each fork.
    ArrayDeque<Frame> path = new ArrayDeque<>();
    path.push(parent);
    while (!path.isEmpty()) {
      parent = path.peek();
      if (parent.nextChild == parent.original.numberOfChildren()) {
        path.pop();
        if (parent.addedEdit != null) {
          Set<Position> originals = history.get(parent.addedEdit);
          originals.remove(parent.addedOriginal);
          if (originals.isEmpty()) history.remove(parent.addedEdit);
        }
      } else {
        int child = parent.nextChild++;
        Frame next = new Frame(parent.original.getVariations().get(child),
            parent.edited.getVariations().get(child));
        if (introducesKo(parent, next, history)) return true;
        remember(parent, next, history);
        path.push(next);
      }
    }
    return false;
  }

  private boolean introducesKo(Frame parent, Frame node, Map<Position, Set<Position>> history) {
    BoardData original = node.original.getData();
    if (!original.isMoveNode()) return false;
    Played played = actionsBeforeSetup.get(node.original);
    Zobrist oldResult = played == null ? original.zobrist : played.original;
    Zobrist newResult = played == null ? node.edited.getData().zobrist : played.edited;
    if (newResult.equals(parent.editedState.simpleKoBan)
        && !oldResult.equals(parent.originalState.simpleKoBan)) {
      return true;
    }
    if (ko == Ko.SIMPLE) return false;
    boolean blackAfter = original.lastMoveColor == Stone.WHITE;
    Set<Position> matches = history.get(key(newResult, blackAfter, parent.editedState.segment));
    // All matching ancestors must already have matched the recorded move in its original segment.
    return matches != null && (matches.size() > 1
        || !matches.contains(key(oldResult, blackAfter, parent.originalState.segment)));
  }

  private void remember(Frame parent, Frame node, Map<Position, Set<Position>> history) {
    node.originalState = advance(node.original, parent == null ? null : parent.originalState);
    node.editedState = advance(node.edited, parent == null ? null : parent.editedState);
    if (ko == Ko.SIMPLE) return;
    Position original = key(node.original.getData().zobrist,
        node.originalState.blackToPlay, node.originalState.segment);
    Position edited = key(node.edited.getData().zobrist,
        node.editedState.blackToPlay, node.editedState.segment);
    if (history.computeIfAbsent(edited, ignored -> new HashSet<>()).add(original)) {
      node.addedOriginal = original;
      node.addedEdit = edited;
    }
  }

  private Position key(Zobrist stones, boolean blackToPlay, int segment) {
    return new Position(stones, ko == Ko.SITUATIONAL && blackToPlay, segment);
  }

  private State advance(BoardHistoryNode node, State prior) {
    BoardData data = node.getData();
    if (prior == null) return new State(data.blackToPlay, rulesHaveButton, 0, null);
    BoardData parent = node.previous().orElseThrow().getData();
    if (data.isSnapshotNode()) {
      // A redundant PL alone does not interrupt play. Explicit placements do, even when idempotent.
      if (data.blackToPlay == prior.blackToPlay && SnapshotSetup.points(data, parent).isEmpty()) {
        return prior;
      }
      return new State(data.blackToPlay, rulesHaveButton, prior.segment + 1, null);
    }
    boolean placeholder = data.isPassNode() && data.dummy;
    boolean blackAfter = placeholder ? prior.blackToPlay : data.lastMoveColor == Stone.WHITE;
    boolean playerChange = (blackAfter ? "W" : "B").equalsIgnoreCase(data.getProperty("PL"));
    if (node.hasSetupEdits() || playerChange) {
      return new State(playerChange ? !blackAfter : blackAfter, rulesHaveButton, prior.segment + 1, null);
    }
    if (placeholder) return prior;
    if (data.isPassNode()) {
      int segment = ko == Ko.SPIGHT || prior.hasButton ? prior.segment + 1 : prior.segment;
      return new State(blackAfter, false, segment, null);
    }
    // Returning to the position before a single capture proves an immediate recapture. Consecutive
    // same-color SGF moves can also restore an earlier board by self-capture without a simple ko.
    Zobrist ban = data.capturedSingleOpponentSince(parent) ? parent.zobrist : null;
    return new State(blackAfter, prior.hasButton, prior.segment, ban);
  }
}
