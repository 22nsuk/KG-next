package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.util.Utils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Render-only history: no stone arrays, analysis lists, Swing components or live tree traversal. */
final class VariationTreeSnapshot {
  record Style(
      boolean largeScaled,
      boolean showCommentNodeColor,
      Color commentNodeColor,
      boolean showVarMove,
      String uiFontName,
      int margin,
      int maxWidth) {
    static Style current() {
      return new Style(
          Config.isScaled && Lizzie.javaScaleFactor >= 1.5,
          Lizzie.config.showCommentNodeColor,
          Lizzie.config.commentNodeColor,
          Lizzie.config.showVarMove,
          Lizzie.config.uiFontName,
          Utils.zoomOut(60),
          Lizzie.config.maxTreeWidth);
    }
  }

  /** Built privately in one pass, then only read by the renderer and its hit tester. */
  static final class Node {
    // The live identity is opaque to rendering; only nodeAt returns it for guarded navigation.
    final BoardHistoryNode source;
    final int moveNumber;
    final int moveMNNumber;
    final String comment;
    final Color color;
    private final Node parent;
    private final boolean dummy;
    private final boolean currentTrunk;
    private List<Node> children;
    private int depth;

    private Node(BoardHistoryNode source, Node parent, boolean currentTrunk, Color color) {
      this.source = source;
      this.parent = parent;
      this.moveNumber = source.getData().moveNumber;
      this.moveMNNumber = source.getData().moveMNNumber;
      // Only presence matters; do not retain arbitrarily large comments in render snapshots.
      this.comment = source.getData().comment.isEmpty() ? "" : "comment";
      this.dummy = source.getData().dummy;
      this.currentTrunk = currentTrunk;
      this.color = color;
    }

    Optional<Node> previous() {
      return Optional.ofNullable(parent);
    }

    Optional<Node> next() {
      return next(false);
    }

    Optional<Node> next(boolean includeDummy) {
      return children.isEmpty() || (!includeDummy && children.get(0).isEndDummay())
          ? Optional.empty() : Optional.of(children.get(0));
    }

    boolean isEndDummay() {
      return dummy && children.isEmpty();
    }

    boolean isCurTrunk() {
      return currentTrunk;
    }

    int numberOfChildren() {
      return children.size();
    }

    Optional<Node> getVariation(int index) {
      return index < 0 || index >= children.size() ? Optional.empty() : Optional.of(children.get(index));
    }

    int getDepth() {
      return depth;
    }

    Node findTop() {
      Node start = this;
      Node top = start;
      while (start.parent != null) {
        Node previous = start.parent;
        if (previous.next().isPresent() && previous.next().get() != start) top = previous;
        start = previous;
      }
      return top;
    }

    int findIndexOfNode(Node target) {
      if (next().isEmpty()) return -1;
      for (int i = 0; i < children.size(); i++) {
        for (Node child = children.get(i); child != null; child = child.next().orElse(null)) {
          if (child == target) return i;
        }
      }
      return -1;
    }
  }

  final Node root;
  final Node displayNode;
  final Style style;

  private VariationTreeSnapshot(Node root, Node displayNode, Style style) {
    this.root = root;
    this.displayNode = displayNode;
    this.style = style;
  }

  static VariationTreeSnapshot current() {
    synchronized (Lizzie.board) {
      return capture(Lizzie.board.getHistory().getStart(), Lizzie.frame.getDisplayNode(),
          Lizzie.board.getHistory().getEnd(), Style.current(), Lizzie.frame::getBlunderNodeColor,
          () -> false);
    }
  }

  void navigateTo(BoardHistoryNode node) {
    if (Lizzie.board != null && Lizzie.board.getHistory().getStart() == root.source) {
      Lizzie.frame.clearSuggestionTablePreview();
      Lizzie.board.navigateToNode(node);
    }
  }

  /** Caller owns the board monitor. All expensive layout and pixel work happens after return. */
  static VariationTreeSnapshot capture(
      BoardHistoryNode root, BoardHistoryNode displayNode, BoardHistoryNode end, Style style,
      Function<BoardHistoryNode, Color> colors, BooleanSupplier cancelled) {
    Set<BoardHistoryNode> trunk = Collections.newSetFromMap(new IdentityHashMap<>());
    for (BoardHistoryNode node = end; node != null; node = node.previous().orElse(null)) {
      checkCancelled(cancelled);
      if (!trunk.add(node)) throw new IllegalStateException("Cyclic variation parent chain");
    }
    IdentityHashMap<BoardHistoryNode, Node> copies = new IdentityHashMap<>();
    List<Node> order = new ArrayList<>();
    Node rootCopy = new Node(root, null, trunk.contains(root), colors.apply(root));
    copies.put(root, rootCopy);
    order.add(rootCopy);
    for (int index = 0; index < order.size(); index++) {
      checkCancelled(cancelled);
      Node parent = order.get(index);
      List<Node> children = new ArrayList<>(parent.source.variations.size());
      for (BoardHistoryNode source : parent.source.variations) {
        checkCancelled(cancelled);
        Node child = new Node(source, parent, trunk.contains(source), colors.apply(source));
        if (copies.put(source, child) != null) {
          throw new IllegalStateException("Repeated node in variation tree");
        }
        children.add(child);
        order.add(child);
      }
      parent.children = List.copyOf(children);
    }
    // Cache leftmost depths in linear time instead of repeatedly walking the live main line.
    for (int index = order.size() - 1; index >= 0; index--) {
      checkCancelled(cancelled);
      Node node = order.get(index);
      node.depth = node.next().map(child -> child.depth + 1).orElse(0);
    }
    Node selected = copies.get(displayNode);
    if (selected == null) throw new CancellationException("Display node no longer belongs to history");
    return new VariationTreeSnapshot(rootCopy, selected, style);
  }

  static void checkCancelled(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) throw new CancellationException("Obsolete variation drawing");
  }
}
