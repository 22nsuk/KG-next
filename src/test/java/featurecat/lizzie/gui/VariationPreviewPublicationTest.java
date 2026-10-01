package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.analysis.Branch;
import featurecat.lizzie.rules.BoardData;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class VariationPreviewPublicationTest {
  private final ArrayDeque<Runnable> worker = new ArrayDeque<>();
  private final ArrayDeque<Runnable> edt = new ArrayDeque<>();
  private final VariationPreviewScheduler scheduler =
      new VariationPreviewScheduler(worker::add, edt::add);
  private final VariationPreviewState state = new VariationPreviewState(scheduler);
  private final BoardData data = BoardData.empty(3, 3);
  private VariationPreviewGenerator.Geometry geometry =
      new VariationPreviewGenerator.Geometry(3, 3, 100, 100, 20, 20, 30, 30, 10, 1, 1);
  private final VariationPreviewGenerator.Style style = new VariationPreviewGenerator.Style(
      false, false, false, false, true, false, false, false, false, true, true, 0,
      199, 199, 0, new Font("Dialog", Font.PLAIN, 12), null, null, null, 0,
      Color.ORANGE, Color.ORANGE,
      VariationPreviewGenerator.captureSourceStones(data.stones, null), "pass");
  private VariationPreviewState.Mode mode =
      new VariationPreviewState.Mode(ExtraMode.Normal, false, false);
  private VariationPreviewState.Selection live;

  private VariationPreviewState.Selection selection(String coordinate, int visits) {
    Branch.Input input = new Branch.Input(new Branch.Position(3, 3, data, data.stones, true),
        List.of(coordinate, "C1"), List.of(Integer.toString(visits), "200"), 2, false, true);
    return new VariationPreviewState.Selection(null, coordinate, input, 2);
  }

  private void request() {
    state.request(live, geometry, style, mode, this::request, () -> {});
  }

  private void select(String coordinate, int visits) {
    live = selection(coordinate, visits);
    state.select(live);
    request();
  }

  private void finish() {
    worker.remove().run();
    edt.remove().run();
  }

  @Test
  void pendingPresentationDistinguishesReplacementFromRefreshAndCancelledInput() {
    select("B2", 1);
    assertTrue(state.isPending());
    finish();
    assertFalse(state.isPending());
    live = selection("B2", 2);
    request();
    assertFalse(state.isPending(), "soft refresh keeps a complete visible result");
    finish();
    state.setDisplayedLength(1);
    live = state.selected();
    request();
    assertTrue(state.isPending(), "length replacement must not expose idle candidates");
    finish();
    select("A3", 3);
    assertTrue(state.isPending(), "candidate replacement is still preview presentation");
    state.cancelPreview();
    assertFalse(state.isPending(), "cancel restores candidates even with retained gesture input");
    finish();
    assertFalse(state.isPending());
    assertNull(state.published());
  }

  @Test
  void lateA1CannotPublishOverReselectedA2() {
    select("B2", 1);
    worker.remove().run();
    select("A3", 2);
    select("B2", 3);
    edt.remove().run();
    assertNull(state.published());
    assertEquals(List.of("3", "200"), state.selected().input().pvVisits);
    finish();
    assertEquals(3, state.published().branch().pvVisitsList[4]);
    assertEquals(Color.WHITE.getRGB(), state.published().stones().getRGB(80, 80));
  }

  @Test
  void liveGeometryAndModeChangesAreCheckedAtPublicationWithoutAnotherPaint() {
    select("B2", 1);
    worker.remove().run();
    geometry = new VariationPreviewGenerator.Geometry(3, 3, 150, 150, 30, 30, 40, 40, 15, 1, 1);
    edt.remove().run();
    assertNull(state.published());
    worker.remove().run();
    mode = new VariationPreviewState.Mode(ExtraMode.Normal, true, false);
    edt.remove().run();
    assertNull(state.published());
    finish();
    assertEquals(150, state.published().stones().getWidth());
    assertEquals(1, state.published().branch().pvVisitsList[4]);
  }

  @Test
  void updatesPreserveVisibleSelectionUntilWholeNewResultAndEventuallyCatchUp() {
    select("B2", 1);
    for (int i = 2; i <= 100; i++) {
      live = selection("B2", i);
      request();
    }
    assertEquals(List.of("1", "200"), state.selected().input().pvVisits);
    finish();
    assertEquals(1, state.published().branch().pvVisitsList[4]);
    assertEquals(List.of("1", "200"), state.selected().input().pvVisits);
    finish();
    assertEquals(100, state.published().branch().pvVisitsList[4]);
    assertEquals(List.of("100", "200"), state.selected().input().pvVisits);
    var cached = state.published();
    request();
    assertSame(cached, state.published());
    assertTrue(worker.isEmpty());
  }

  @Test
  void cancelKeepsInputButClearAndClosePreventQueuedResultsFromReturning() {
    select("B2", 1);
    worker.remove().run();
    state.cancelPreview();
    edt.remove().run();
    assertNull(state.published());
    assertEquals(List.of("B2", "C1"), state.selected().input().variation);
    select("B2", 2);
    worker.remove().run();
    state.clear();
    edt.remove().run();
    assertNull(state.selected());
    assertNull(state.published());
    select("B2", 3);
    worker.remove().run();
    scheduler.close();
    edt.remove().run();
    assertNull(state.published());
  }

  @Test
  void explicitLengthRetiresRefreshAndPublishesTheSelectedPrefixFirst() {
    select("B2", 1);
    finish();
    live = selection("B2", 2);
    request();
    worker.remove().run();
    state.setDisplayedLength(1);
    live = state.selected();
    request();
    edt.remove().run();
    assertNull(state.published());
    finish();
    assertEquals(1, state.published().branch().length);
    assertEquals(List.of("1", "200"), state.selected().input().pvVisits);
  }
}
