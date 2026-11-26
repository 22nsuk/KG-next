package featurecat.lizzie.rules;

import java.util.Arrays;

public class NodeInfo {
  public boolean analyzed;
  public boolean analyzedMatchValue;
  public int[] coords;
  public int moveNum;
  public boolean isBlack;
  public double winrate;
  public double diffWinrate;
  public int playouts;
  public int previousPlayouts;
  public double scoreMeanDiff;
  public double scoreLead;
  public boolean isMatchAi;
  public double percentsMatch;
  public boolean isBest;

  boolean describesMove(BoardData move, int moveNumber) {
    return moveNum == moveNumber
        && isBlack == !move.blackToPlay
        && Arrays.equals(coords, move.lastMove.orElseThrow());
  }

  public double getScoreMeanDiff() {
    if (this.isBest) return 0;
    else return scoreMeanDiff;
  }

  public double getWinrateDiff() {
    if (this.isBest) return 0;
    else return diffWinrate;
  }
}
