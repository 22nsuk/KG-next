package featurecat.lizzie.rules;

import java.util.List;

/**
 * Complete undo data with independently packed positions. The private history is deliberately not
 * playable until restore() materializes every stones/grid array; save/export snapshots are unrelated.
 */
final class BoardHistorySnapshot {
  record NodeSnapshot(BoardHistoryNode node, BoardPositionSnapshot position,
      int parentIndex, int originalMoveNumber) {}

  private BoardHistoryList history;
  private List<NodeSnapshot> nodes;
  private final boolean normalizeTrial;

  BoardHistorySnapshot(
      BoardHistoryList history, List<NodeSnapshot> nodes, boolean normalizeTrial) {
    this.history = history;
    this.nodes = nodes;
    this.normalizeTrial = normalizeTrial;
  }

  void keepCurrentGameInfoAndRules(BoardHistoryList current) {
    history.setGameInfo(current.getGameInfo());
    history.restoreSessionRulesTarget(current.captureSessionRules());
  }

  /** Transfer only a fully playable tree; a failed materialization can be retried privately. */
  BoardHistoryList restore() {
    if (history == null) throw new IllegalStateException("History snapshot already consumed");
    for (NodeSnapshot entry : nodes) {
      BoardData data = entry.node().getData();
      entry.position().restoreInto(data);
      data.materializeRestoreCollections();
      entry.node().materializeRestoreNodeInfo();
      if (normalizeTrial && entry.parentIndex() >= 0) {
        restoreOrdinaryNumbering(entry, nodes.get(entry.parentIndex()));
      }
    }
    BoardHistoryList restored = history;
    history = null;
    nodes = null;
    return restored;
  }

  private static void restoreOrdinaryNumbering(NodeSnapshot entry, NodeSnapshot parent) {
    BoardData data = entry.node().getData();
    BoardData parentData = parent.node().getData();
    int moveIndex = data.isMoveNode()
        ? data.zobrist.index(data.lastMove.orElseThrow()[0], data.lastMove.get()[1]) : -1;
    for (int i = 0; i < data.moveNumberList.length; i++) {
      int number = data.moveNumberList[i];
      // Zeroes hide old stones on a new branch or after setup edits. Preserve MN overrides;
      // only replace the trial's relative own count and inherited negative/relative labels.
      if (number == 0) continue;
      if (i == moveIndex && (number < 0 || entry.originalMoveNumber() != data.moveNumber)) {
        data.moveNumberList[i] = data.moveMNNumber >= 0 ? data.moveMNNumber : data.moveNumber;
      } else if (number == parent.position().moveNumberAt(i)
          && data.stones[i] == parentData.stones[i]) {
        data.moveNumberList[i] = parentData.moveNumberList[i];
      }
    }
  }
}
