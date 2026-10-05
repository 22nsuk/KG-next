package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VariationTreeSnapshotTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void renderingAndHitTestingUseOnlyCapturedNodesAndSettings(boolean simple) {
    BoardHistoryNode root = node(0);
    BoardHistoryNode one = child(root, 1);
    BoardHistoryNode two = child(one, 2);
    BoardHistoryNode side = child(one, 2);
    side.getData().comment = "annotation";
    var snapshot = VariationTreeSnapshot.capture(root, two, two, style(false),
        n -> n == side ? Color.BLUE : Color.WHITE, () -> false);
    var view = view(simple, snapshot.style, two);
    var before = VariationTreeImage.render(view, snapshot, () -> false);
    assertSame(one, before.renderer().nodeAt(25, simple ? 185 : 45).orElseThrow());
    // A new game may replace these live relationships while the frozen layout is still drawing.
    root.variations.clear();
    one.getData().moveNumber = 500;
    two.getData().moveMNNumber = 900;
    side.getData().comment = "";
    one.variations.clear();
    var after = VariationTreeImage.render(view, snapshot, () -> false);
    assertArrayEquals(pixels(before.image()), pixels(after.image()));
    assertSame(one, after.renderer().nodeAt(25, simple ? 185 : 45).orElseThrow());
    assertSame(two, snapshot.displayNode.source);
    assertEquals(2, snapshot.root.getDepth());
    assertTrue(snapshot.displayNode.isCurTrunk());
    assertFalse(snapshot.root.next().orElseThrow().getVariation(1).orElseThrow().isCurTrunk());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void obsoleteDrawStopsDuringTraversal(boolean simple) {
    BoardHistoryNode root = node(0);
    BoardHistoryNode end = root;
    for (int i = 1; i < 300; i++) end = child(end, i);
    var snapshot = VariationTreeSnapshot.capture(root, end, end, style(false), n -> Color.WHITE, () -> false);
    AtomicInteger checks = new AtomicInteger();
    assertThrows(CancellationException.class, () -> VariationTreeImage.render(
        view(simple, snapshot.style, snapshot.displayNode.source), snapshot,
        () -> checks.incrementAndGet() >= 10));
    assertEquals(10, checks.get(), "do not finish an obsolete full-tree drawing");
  }

  @Test
  void captureStopsBeforeCopyingAnObsoleteWholeTree() {
    BoardHistoryNode root = node(0);
    for (int i = 0; i < 100; i++) child(root, 1);
    AtomicInteger colors = new AtomicInteger();
    assertThrows(CancellationException.class, () -> VariationTreeSnapshot.capture(
        root, root, root, style(false), n -> { colors.incrementAndGet(); return Color.WHITE; },
        () -> colors.get() >= 10));
    assertEquals(10, colors.get());
  }

  @Test
  void dummyDepthAndMoveNumberMetadataRetainTheirMeaning() {
    BoardHistoryNode root = node(0);
    BoardHistoryNode one = child(root, 1);
    BoardHistoryNode dummy = child(one, 1);
    dummy.getData().dummy = true;
    BoardHistoryNode side = child(one, 2);
    side.getData().moveMNNumber = 22;
    var snapshot = VariationTreeSnapshot.capture(root, side, side, style(true), n -> Color.WHITE, () -> false);
    assertEquals(1, snapshot.root.getDepth());
    assertEquals(0, snapshot.root.next().orElseThrow().getDepth());
    assertEquals(22, snapshot.displayNode.moveMNNumber);
    assertSame(side.findTop(), snapshot.displayNode.findTop().source);
    assertTrue(snapshot.root.next().orElseThrow().next(true).orElseThrow().isEndDummay());
  }

  @Test
  void scrollImageMeasuresFullExtentBeforeAllocatingAndRetainsHitCoordinates() {
    BoardHistoryNode root = node(0);
    BoardHistoryNode end = root;
    for (int i = 1; i <= 80; i++) end = child(end, i);
    var snapshot = VariationTreeSnapshot.capture(root, end, end, style(false), n -> Color.WHITE, () -> false);
    var result = VariationTreeImage.render(view(false, snapshot.style, end), snapshot, () -> false);
    assertEquals(20 + 80 * 20 + 60, result.image().getHeight());
    assertSame(end, result.renderer().nodeAt(result.currentX() + 5, result.currentY() + 5).orElseThrow());
    assertTrue(result.renderer().nodeAt(-1, 0).isEmpty());
  }

  static BoardHistoryNode node(int move) {
    BoardData data = BoardData.empty(19, 19);
    data.moveNumber = move;
    return new BoardHistoryNode(data);
  }

  static BoardHistoryNode child(BoardHistoryNode parent, int move) {
    BoardHistoryNode child = node(move);
    parent.variations.add(child);
    parent.setPreviousForChild(child);
    return child;
  }

  static VariationTreeSnapshot.Style style(boolean large) {
    return new VariationTreeSnapshot.Style(large, true, Color.YELLOW, true, "SansSerif", 60, 2000);
  }

  private static VariationTreeImage.View view(boolean simple, VariationTreeSnapshot.Style style, BoardHistoryNode node) {
    return new VariationTreeImage.View(null, null, node, node, 0, 0, 0, 320, 400, 1000, 800, null, simple, style);
  }

  private static int[] pixels(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }
}
