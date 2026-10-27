package featurecat.lizzie.gui;

import java.util.concurrent.atomic.AtomicInteger;

/** Merges analysis display requests; callbacks read current state only on the event thread. */
final class AnalysisUiRefresh {
  private static final int TITLE = 1;
  private static final int BOARD = 2;
  private static final int GRAPH = 4;
  private static final int CANDIDATES = 8;

  private final AtomicInteger requested = new AtomicInteger();
  private final SwingRefreshCoalescer coalescer = new SwingRefreshCoalescer(33, this::flush);
  private final Runnable title;
  private final Runnable board;
  private final Runnable graph;
  private final Runnable candidates;

  AnalysisUiRefresh(Runnable title, Runnable board, Runnable graph, Runnable candidates) {
    this.title = java.util.Objects.requireNonNull(title, "title");
    this.board = java.util.Objects.requireNonNull(board, "board");
    this.graph = java.util.Objects.requireNonNull(graph, "graph");
    this.candidates = java.util.Objects.requireNonNull(candidates, "candidates");
  }

  void requestAnalysis() {
    request(TITLE | BOARD);
  }

  void requestTitle() {
    request(TITLE);
  }

  void requestTracking() {
    request(TITLE | BOARD | CANDIDATES);
  }

  void requestGraph() {
    request(GRAPH);
  }

  private void request(int parts) {
    requested.getAndUpdate(pending -> pending | parts);
    coalescer.request();
  }

  private void flush() {
    int parts = requested.getAndSet(0);
    // Selection changes may collapse all the way to an empty snapshot before this runs.
    // Preserve the need to refilter, not any historical selection or analysis payload.
    if ((parts & CANDIDATES) != 0) candidates.run();
    if ((parts & TITLE) != 0) title.run();
    // A board refresh already paints the graph. Graph-only progress must not demote it.
    if ((parts & BOARD) != 0) board.run();
    else if ((parts & GRAPH) != 0) graph.run();
  }
}
