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

  VariationPreviewState() {}

  Selection selected() {
    return selection;
  }

  VariationPreviewGenerator.Result published() {
    return published;
  }

  void select(Selection next) {
    this.selection = next;
    this.published = null;
  }

  void clear() {
    this.selection = null;
    this.published = null;
  }

  void cancelPreview() {
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
    this.published = null;
    return true;
  }
}
