package featurecat.lizzie.gui;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Color;
import java.util.concurrent.atomic.AtomicBoolean;

/** Connects the real render snapshot boundary to the web package's JSON dispatcher fixture. */
public final class VariationTreeTrialCapture {
  private VariationTreeTrialCapture() {}

  public static void capture(Board board, BoardHistoryNode root, Runnable atFirstChild) {
    AtomicBoolean visited = new AtomicBoolean();
    synchronized (board) {
      VariationTreeSnapshot.capture(
          root, root, board.getHistory().getEnd(),
          new VariationTreeSnapshot.Style(false, true, Color.YELLOW, true, "SansSerif", 60, 2000),
          node -> {
            if (node != root && visited.compareAndSet(false, true)) atFirstChild.run();
            return Color.WHITE;
          },
          () -> false);
    }
  }
}
