package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

/** Owns a manual selection until its cache decision and session rules publication finish. */
public final class ManualRulesSelection {
  private final Leelaz primary;
  private final KataGoRules requested;
  private final Board board;
  private final BoardHistoryList history;
  private final long primaryGeneration;
  private final Object incarnation;
  private final Leelaz mirror;
  private final Object mirrorIncarnation;
  private final long timeoutMillis;
  private BoardHistoryList.ManualRulesIntent intent;
  private KataGoRules previousRules;
  private KataGoRules previousMirrorRules;
  private boolean previousOverride;
  private boolean inheritedPendingChange;
  private boolean unchanged;
  private volatile boolean done;
  private Leelaz.EngineRulesOperation operation;

  private ManualRulesSelection(Leelaz primary, KataGoRules requested, long timeoutMillis) {
    this.primary = primary;
    this.requested = requested;
    this.timeoutMillis = timeoutMillis;
    board = Lizzie.board;
    history = board.getHistory();
    primaryGeneration = Lizzie.capturePrimaryEngineGeneration(primary);
    incarnation = primary.engineIncarnationSnapshot();
    mirror = primary.activeComparisonEngine();
    mirrorIncarnation = mirror == null ? null : mirror.engineIncarnationSnapshot();
  }

  /** Returns null when the captured foreground selection cannot admit this request. */
  public static ManualRulesSelection apply(Leelaz primary, KataGoRules requested) {
    return apply(primary, requested, 30_000L);
  }

  static ManualRulesSelection apply(Leelaz primary, KataGoRules requested, long timeoutMillis) {
    if (primary == null || Lizzie.board == null) return null;
    ManualRulesSelection selection = new ManualRulesSelection(primary, requested, timeoutMillis);
    if (!selection.prepare()) return null;
    if (selection.unchanged) {
      selection.done = true;
      return selection;
    }
    boolean started = false;
    try {
      if (Lizzie.frame != null) {
        Lizzie.frame.retireImportedRulesForManualSelection(
            selection.history, selection.intent.previousTarget());
      }
      selection.operation =
          primary.applyManualRulesOperation(requested, timeoutMillis, selection::isCurrent);
      if (selection.operation == null) return null;
      Thread completion =
          new Thread(selection::complete, "lizzie-set-kata-rules-completion");
      completion.setDaemon(true);
      completion.start();
      started = true;
      return selection;
    } finally {
      if (!started) selection.history.finishManualRulesIntent(selection.intent);
    }
  }

  public boolean unchanged() {
    return unchanged;
  }

  public boolean isDone() {
    return done;
  }

  public Leelaz.EngineRulesOperation operation() {
    return operation;
  }

  private boolean prepare() {
    List<Runnable> afterAdmission = new ArrayList<>();
    boolean[] prepared = {false};
    boolean succeeded = false;
    try {
      boolean admitted = atCurrentSelection(() -> {
        if (!primary.isLoaded() || !primary.isStarted()
            || primary.activeComparisonEngine() != mirror) return;
        synchronized (history) {
          Runnable prepareEndpoints = () -> {
            if (primary.isRulesMutationOccupied()
                || (mirror != null && mirror.isRulesMutationOccupied())) return;
            // A prior setter can already be confirmed while its cache effects are still pending.
            // Its successor must not treat that observation as the provenance of the retained cache.
            inheritedPendingChange = history.hasPendingManualRulesIntent();
            previousRules = inheritedPendingChange ? null : primary.currentConfirmedRules();
            previousMirrorRules = mirror == null ? null : mirror.currentConfirmedRules();
            BoardHistoryList.SessionRulesTarget target = history.captureSessionRules();
            unchanged = !inheritedPendingChange
                && matches(previousRules, requested)
                && (mirror == null || matches(previousMirrorRules, requested))
                && compatibleTarget(target, requested);
            if (!unchanged) {
              previousOverride = history.permitsAnalysisWithCurrentRules(
                  target, primary, primaryGeneration, mirror);
              intent = history.beginManualRulesIntent(this::currentIdentity);
              primary.retireOrdinaryAnalysisForRulesChange(incarnation, afterAdmission::add);
              if (mirror != null) {
                mirror.retireOrdinaryAnalysisForRulesChange(mirrorIncarnation, afterAdmission::add);
              }
            }
            prepared[0] = true;
          };
          if (mirror == null) {
            primary.runIfCurrentLiveEngineIncarnation(incarnation, prepareEndpoints);
          } else {
            Leelaz.runIfCurrentLiveEngineIncarnations(
                primary, incarnation, mirror, mirrorIncarnation, prepareEndpoints);
          }
        }
      }, SwingUtilities.isEventDispatchThread());
      for (Runnable action : afterAdmission) action.run();
      succeeded = admitted && prepared[0];
      return succeeded;
    } finally {
      if (!succeeded) history.finishManualRulesIntent(intent);
    }
  }

  private void complete() {
    EngineRulesResult result = null;
    EngineRulesResult recovered = null;
    Leelaz.EngineRulesOperation settledOperation = operation;
    try {
      result = operation.await(timeoutMillis + 1_000L);
      if (rejectedWithoutChange(result) && isCurrent()) {
        Leelaz.EngineRulesOperation query =
            primary.queryEngineRulesAfter(operation, timeoutMillis, this::isCurrent);
        if (query != null) {
          settledOperation = query;
          recovered = query.await(timeoutMillis + 1_000L);
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      try {
        settle(result, recovered, settledOperation);
      } finally {
        history.finishManualRulesIntent(intent);
        done = true;
      }
    }
  }

  private void settle(EngineRulesResult result, EngineRulesResult recovered,
      Leelaz.EngineRulesOperation settledOperation) {
    BoardHistoryList.SessionRulesTarget[] published = {null};
    boolean[] resume = {false};
    List<Runnable> afterAdmission = new ArrayList<>();
    atCurrentSelection(() -> {
      if (!isCurrent()) return;
      Leelaz currentMirror = primary.activeComparisonEngine();
      boolean currentOperation = primary.engineRulesOperation() == settledOperation;
      if (currentOperation && currentMirror != null
          && (currentMirror != mirror || currentMirror.engineIncarnationSnapshot() != mirrorIncarnation)) {
        // A newly selected mirror must not release an old stream when the pending guard ends.
        // This state-only retirement is also required while a lifecycle reservation is pending.
        currentMirror.retireOrdinaryAnalysisForRulesChange(
            currentMirror.engineIncarnationSnapshot(), afterAdmission::add);
      }
      synchronized (history) {
        if (!isCurrent() || history.captureSessionRules() != intent.previousTarget()) return;
        currentOperation = currentOperation && primary.engineRulesOperation() == settledOperation;
        KataGoRules currentRules = primary.currentConfirmedRules();
        boolean confirmed = currentOperation && result != null && result.isConfirmed()
            && matches(result.observed(), currentRules);
        boolean selected = confirmed && matches(result.observed(), requested);
        KataGoRules observed = confirmed ? result.observed()
            : currentOperation && recovered != null && recovered.isConfirmed()
                && matches(recovered.observed(), currentRules) ? recovered.observed() : null;
        if (selected) {
          if (!matches(previousRules, observed)
              || !compatibleTarget(intent.previousTarget(), requested)
              || (currentMirror != null && !matches(currentMirror.currentConfirmedRules(), requested))) {
            board.clearBestMovesAfter(history.getStart());
          }
          published[0] = history.publishManualRulesIfCurrent(intent, observed).orElse(null);
        } else if (inheritedPendingChange
            || (!rejectedWithoutChange(result) && !matches(previousRules, observed))
            || (observed != null && !matches(previousRules, observed))) {
          // A rejected setter is harmless; an unknown or different actual state is not.
          board.clearBestMovesAfterForFirstEngine(history.getStart());
        }
        if (!selected && previousOverride && matches(previousRules, observed)
            && currentMirror == mirror
            && (mirror == null || (mirror.engineIncarnationSnapshot() == mirrorIncarnation
                && matches(previousMirrorRules, mirror.currentConfirmedRules())))) {
          history.authorizeAnalysisWithCurrentRules(
              intent.previousTarget(), primary, primaryGeneration, mirror);
        }
        resume[0] = !selected && observed != null;
        history.finishManualRulesIntent(intent);
      }
    }, false);
    for (Runnable action : afterAdmission) action.run();
    if (published[0] != null && Lizzie.frame != null) {
      Lizzie.frame.synchronizeManualRulesAfterSelection(
          history, published[0], intent, primary, primaryGeneration);
    } else if (resume[0] && currentIdentity() && history.isLatestManualRulesIntent(intent)
        && primary.isPondering()) {
      primary.ponder();
    }
  }

  private boolean atCurrentSelection(Runnable action, boolean nonBlocking) {
    do {
      Leelaz activeMirror = primary.activeComparisonEngine();
      Boolean[] selected = {null};
      Runnable attempt = () -> selected[0] = EngineManager.callIfCurrentAnalysisSelection(
          primary, activeMirror, null,
          () -> Lizzie.tryCallIfPrimaryEngine(primary, primaryGeneration, () -> {
            if (!currentIdentity()) return false;
            action.run();
            return true;
          }));
      boolean admitted = nonBlocking
          ? EngineManager.tryRunIfNoActiveEngineGameAnalysisOutput(attempt)
          : EngineManager.runIfNoActiveEngineGameAnalysisOutput(attempt);
      if (!admitted || selected[0] != null) return admitted && Boolean.TRUE.equals(selected[0]);
      // Release the output/selection fences before waiting for a primary-owned restore.
    } while (!nonBlocking && Lizzie.capturePrimaryEngineGeneration(primary) == primaryGeneration);
    return false;
  }

  // This predicate also runs under the protocol lock; it must use only nonblocking snapshots.
  private boolean currentIdentity() {
    return Lizzie.board == board && board.getHistory() == history
        && Lizzie.capturePrimaryEngineGenerationSnapshot(primary) == primaryGeneration
        && primary.engineIncarnationSnapshot() == incarnation;
  }

  private boolean isCurrent() {
    return currentIdentity() && history.ownsPendingManualRulesIntent(intent);
  }

  private static boolean compatibleTarget(
      BoardHistoryList.SessionRulesTarget target, KataGoRules rules) {
    return target.kind() == BoardHistoryList.SessionRulesKind.UNSPECIFIED
        || (target.kind() == BoardHistoryList.SessionRulesKind.VALID
            && target.parsedRules().filter(value -> value.semanticallyEquals(rules)).isPresent());
  }

  private static boolean matches(KataGoRules first, KataGoRules second) {
    return first != null && second != null && first.semanticallyEquals(second);
  }

  private static boolean rejectedWithoutChange(EngineRulesResult result) {
    if (result == null) return false;
    return switch (result.reason()) {
      case SET_REJECTED, SET_UNSUPPORTED, OCCUPIED, LIST_COMMANDS_FAILED, LIST_COMMANDS_TIMEOUT -> true;
      default -> false;
    };
  }
}
