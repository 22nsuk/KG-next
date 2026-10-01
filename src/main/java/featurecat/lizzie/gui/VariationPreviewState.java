package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.Branch;
import java.util.Objects;

/**
 * Selection truth and whole preview result publication on the EDT.
 */
final class VariationPreviewState {

  record Selection(
      BranchInputCapture source,
      String coordinate,
      Branch.Input input,
      int displayedLength) {
    Selection {
      Objects.requireNonNull(input, "input");
    }

    Selection withSimulation(boolean removeDeadChains, boolean recordPvVisits) {
      if (input.removeDeadChains == removeDeadChains && input.recordPvVisits == recordPvVisits) {
        return this;
      }
      return new Selection(source, coordinate,
          new Branch.Input(input.position, input.variation, input.pvVisits, input.maxLength,
              removeDeadChains, recordPvVisits), displayedLength);
    }
  }

  private Selection selection;
  private VariationPreviewGenerator.Result published;
  private VariationPreviewScheduler scheduler;
  private Request requested;
  private long generation;

  record Mode(featurecat.lizzie.ExtraMode mode, boolean frozen, boolean autoReplay) {}

  private record Request(Selection selection, VariationPreviewGenerator.Geometry geometry,
      VariationPreviewGenerator.Style style, Mode mode) {
    boolean sameContext(Request other) {
      Branch.Input input = selection.input();
      Branch.Input prior = other.selection.input();
      BranchInputCapture source = selection.source();
      BranchInputCapture oldSource = other.selection.source();
      return Objects.equals(selection.coordinate(), other.selection.coordinate())
          && selection.displayedLength() == other.selection.displayedLength()
          && (source == oldSource || source != null && oldSource != null
              && source.sourceData == oldSource.sourceData
              && source.analysisData == oldSource.analysisData && oldSource.isCurrent())
          && input.maxLength == prior.maxLength
          && input.removeDeadChains == prior.removeDeadChains
          && input.recordPvVisits == prior.recordPvVisits
          && geometry.equals(other.geometry) && style.sameRendering(other.style)
          && mode.equals(other.mode);
    }

    boolean sameContent(Request other) {
      return sameContext(other)
          && selection.input().variation.equals(other.selection.input().variation)
          && Objects.equals(selection.input().pvVisits, other.selection.input().pvVisits);
    }
  }

  VariationPreviewState() {}

  VariationPreviewState(VariationPreviewScheduler scheduler) {
    this.scheduler = scheduler;
  }

  /** The host rechecks live context on EDT before any whole-result publication. */
  void request(Selection next, VariationPreviewGenerator.Geometry geometry,
      VariationPreviewGenerator.Style style, Mode mode, Runnable validate, Runnable onPublished) {
    if (selection == null) return;
    Request request = new Request(next, geometry, style, mode);
    if (requested != null) {
      if (request.sameContent(requested)) return;
      if (!request.sameContext(requested)) cancelPreview();
    }
    requested = request;
    long expectedGeneration = generation;
    if (scheduler == null) scheduler = VariationPreviewScheduler.shared();
    scheduler.submit(this, next.input(), geometry, style, result -> {
      if (generation != expectedGeneration || selection == null) return;
      validate.run();
      if (generation != expectedGeneration || selection == null
          || next.source() != null && !next.source().isCurrent()) return;
      publish(selection, next, result);
      onPublished.run();
    });
  }

  Selection selected() {
    return selection;
  }

  VariationPreviewGenerator.Result published() {
    return published;
  }

  void select(Selection next) {
    cancelPreview();
    this.selection = next;
  }

  void clear() {
    cancelPreview();
    this.selection = null;
  }

  void cancelPreview() {
    generation++;
    if (scheduler != null) scheduler.cancel(this);
    requested = null;
    this.published = null;
  }

  boolean publish(
      Selection expected,
      Selection replacement,
      VariationPreviewGenerator.Result result) {
    Objects.requireNonNull(replacement, "replacement");
    Objects.requireNonNull(result, "result");
    if (this.selection == null || this.selection != expected) {
      return false;
    }
    this.selection = replacement;
    this.published = result;
    return true;
  }

  boolean setDisplayedLength(int n) {
    if (selection == null || selection.displayedLength() == n) {
      return false;
    }
    Branch.Input oldInput = selection.input();
    Branch.Input newInput =
        new Branch.Input(
            oldInput.position,
            oldInput.variation,
            oldInput.pvVisits,
            n > 0 ? n : 199,
            oldInput.removeDeadChains,
            oldInput.recordPvVisits);
    this.selection =
        new Selection(selection.source(), selection.coordinate(), newInput, n);
    cancelPreview();
    return true;
  }
}
