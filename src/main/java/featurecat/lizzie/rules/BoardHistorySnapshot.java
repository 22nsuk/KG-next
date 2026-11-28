package featurecat.lizzie.rules;

import featurecat.lizzie.analysis.MoveData;
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
  BoardHistoryList restore(boolean isPkBoard, int matchAiMoves, double matchAiPercent) {
    if (history == null) throw new IllegalStateException("History snapshot already consumed");
    for (NodeSnapshot entry : nodes) {
      BoardData data = entry.node().getData();
      entry.position().restoreInto(data);
      data.materializeRestoreCollections();
      entry.node().materializeRestoreNodeInfo();
      if (normalizeTrial && entry.parentIndex() >= 0) {
        restoreOrdinaryNumbering(entry, nodes.get(entry.parentIndex()));
      }
      if (!isPkBoard && data.isMoveNode() && entry.parentIndex() >= 0) {
        BoardHistoryNode parent = nodes.get(entry.parentIndex()).node();
        // Publish caches for the current policy, including the inactive engine and the selected
        // branch. No new visits or asynchronous refresh should be required after adoption.
        restoreMatch(parent.nodeInfo, parent.nodeInfoMain, data, parent.getData().bestMoves,
            matchAiMoves, matchAiPercent);
        restoreMatch(parent.nodeInfo2, parent.nodeInfoMain2, data, parent.getData().bestMoves2,
            matchAiMoves, matchAiPercent);
      }
    }
    BoardHistoryList restored = history;
    history = null;
    nodes = null;
    return restored;
  }

  private static void restoreMatch(NodeInfo branch, NodeInfo main, BoardData move,
      List<MoveData> candidates, int count, double percent) {
    boolean branchMatches = branch.describesMove(move, move.moveNumber);
    boolean mainMatches = main.describesMove(move, move.moveNumber);
    if (!branchMatches && !mainMatches) return;
    MoveMatch match = MoveMatch.evaluate(move, candidates, count, percent);
    if (branchMatches) match.applyTo(branch);
    if (mainMatches) match.applyTo(main);
  }

  private static void restoreOrdinaryNumbering(NodeSnapshot entry, NodeSnapshot parent) {
    BoardData data = entry.node().getData();
    BoardHistoryNode parentNode = parent.node();
    parentNode.renumberCachedChildMove(data, entry.originalMoveNumber());
    BoardHistoryList.TrialNumbering.normalizeMoveNumberGrid(
        data, parentNode.getData(), entry.originalMoveNumber(), parent.position()::moveNumberAt);
  }
}
