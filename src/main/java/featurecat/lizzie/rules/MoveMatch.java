package featurecat.lizzie.rules;

import featurecat.lizzie.analysis.MoveData;
import java.util.Arrays;
import java.util.List;

/** Match judgments are derived from analysis candidates and the current display policy. */
record MoveMatch(boolean isBest, double percents, int candidateNumber, boolean isMatch) {
  static MoveMatch evaluate(BoardData played, List<MoveData> candidates,
      int bestNums, double percentPlayouts) {
    boolean best = false;
    boolean match = false;
    double percent = 0;
    int candidateNumber = 0;
    double maxPlayouts = 0;
    for (MoveData candidate : candidates) maxPlayouts = Math.max(maxPlayouts, candidate.playouts);
    if (played.isMoveNode()) {
      for (int i = 0; i < candidates.size(); i++) {
        MoveData candidate = candidates.get(i);
        var coordinate = Board.asCoordinates(candidate.coordinate, played.zobrist.height());
        if (coordinate.isPresent() && Arrays.equals(coordinate.get(), played.lastMove.orElseThrow())) {
          double ratio = candidate.playouts / maxPlayouts;
          if (ratio * 100 >= percentPlayouts && i < bestNums) {
            best |= i == 0;
            match = true;
          }
          percent = i == 0 ? 1 : ratio;
          candidateNumber = i + 1;
        }
      }
    }
    return new MoveMatch(best, percent, candidateNumber, match);
  }

  void applyTo(NodeInfo info) {
    info.isBest = isBest;
    info.isMatchAi = isMatch;
    info.percentsMatch = percents;
  }
}
