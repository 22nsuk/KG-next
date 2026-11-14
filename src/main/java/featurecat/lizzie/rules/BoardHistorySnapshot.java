package featurecat.lizzie.rules;

import featurecat.lizzie.analysis.MoveData;
import java.util.Arrays;
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
    boolean branchMatches = describesMove(branch, move, move.moveNumber);
    boolean mainMatches = describesMove(main, move, move.moveNumber);
    if (!branchMatches && !mainMatches) return;
    MoveMatch match = MoveMatch.evaluate(move, candidates, count, percent);
    if (branchMatches) match.applyTo(branch);
    if (mainMatches) match.applyTo(main);
  }

  private static void restoreOrdinaryNumbering(NodeSnapshot entry, NodeSnapshot parent) {
    BoardData data = entry.node().getData();
    BoardData parentData = parent.node().getData();
    if (data.isMoveNode() && entry.originalMoveNumber() != data.moveNumber) {
      // These parent-owned caches describe a child move. Renumber only matching entries,
      // preserving branch selection and analysis even when no new visits trigger a refresh.
      BoardHistoryNode parentNode = parent.node();
      restoreMoveInfoNumber(parentNode.nodeInfo, data, entry.originalMoveNumber());
      restoreMoveInfoNumber(parentNode.nodeInfoMain, data, entry.originalMoveNumber());
      restoreMoveInfoNumber(parentNode.nodeInfo2, data, entry.originalMoveNumber());
      restoreMoveInfoNumber(parentNode.nodeInfoMain2, data, entry.originalMoveNumber());
    }
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

  private static void restoreMoveInfoNumber(NodeInfo info, BoardData move, int originalMoveNumber) {
    if (describesMove(info, move, originalMoveNumber)) {
      info.moveNum = move.moveNumber;
    }
  }

  private static boolean describesMove(NodeInfo info, BoardData move, int moveNumber) {
    return info.moveNum == moveNumber
        && info.isBlack == !move.blackToPlay
        && Arrays.equals(info.coords, move.lastMove.orElseThrow());
  }
}
