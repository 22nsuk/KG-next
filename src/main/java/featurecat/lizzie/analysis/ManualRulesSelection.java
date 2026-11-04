package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
  private boolean inheritedUncertainChange;
  private boolean unchanged;
  private volatile boolean done;
  private volatile Leelaz.EngineRulesOperation operation;
  private RetainedCache primaryCache;
  private RetainedCache mirrorCache;

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
      if (!started) selection.finish();
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
            // Endpoint admission is fenced here. Check queues before the observation fence:
            // physical observations remain useful for cache provenance, but cannot justify a
            // no-op while earlier admitted work can still change either endpoint's rules.
            boolean pendingRulesWrite = primary.hasPendingRulesWrite()
                || (mirror != null && mirror.hasPendingRulesWrite());
            primary.withManualRulesCache(() -> {
              boolean pending = history.hasPendingManualRulesIntent();
              ManualRulesSelection predecessor = primary.manualRulesCacheOwner.get();
              inheritedUncertainChange = pending;
              KataGoRules confirmedRules = primary.currentConfirmedRules();
              previousRules = pending ? null : confirmedRules;
              previousMirrorRules = mirror == null ? null : mirror.currentConfirmedRules();
              BoardHistoryList.SessionRulesTarget target = history.captureSessionRules();
              previousOverride = history.permitsAnalysisWithCurrentRules(
                  target, primary, primaryGeneration, mirror);
              // Only an explicitly harmless predecessor can hand off retained cache provenance.
              // A confirmed or in-flight setter may already have changed rules before settling.
              if (pending && predecessor != null && predecessor.history == history
                  && predecessor.isCurrent() && predecessor.hasCacheProvenance()
                  && predecessor.operation != null
                  && rejectedWithoutChange(predecessor.operation.snapshot())
                  && (confirmedRules == null || matches(predecessor.previousRules, confirmedRules))) {
                inheritedUncertainChange = false;
                previousRules = predecessor.previousRules;
                previousOverride |= predecessor.previousOverride
                    && predecessor.mirror == mirror
                    && predecessor.mirrorIncarnation == mirrorIncarnation
                    && (mirror == null || matches(predecessor.previousMirrorRules, previousMirrorRules));
              }
              unchanged = !pending && !pendingRulesWrite
                  && matches(previousRules, requested)
                  && (mirror == null || matches(previousMirrorRules, requested))
                  && compatibleTarget(target, requested);
              List<WeakReference<BoardData>> retained = captureRetainedData(history);
              primaryCache = primary.retainManualRulesCache(
                  new RetainedCache(this, retained, false), pending);
              if (mirror != null) {
                mirrorCache = mirror.retainManualRulesCache(
                    new RetainedCache(this, retained, true), pending);
              }
              if (!unchanged) {
                intent = history.beginManualRulesIntent(this::currentIdentity);
                primary.manualRulesCacheOwner.set(this);
              }
            });
            if (!unchanged) {
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
      if (!succeeded) finish();
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
        finish();
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
        primary.withManualRulesCache(() -> {
          // The published generation is a nonblocking snapshot of protocol ownership.
          boolean operationStillCurrent = currentOperation
              && primary.engineRulesResult().generation() == settledOperation.generation();
          KataGoRules currentRules = primary.currentConfirmedRules();
          boolean confirmed = operationStillCurrent && result != null && result.isConfirmed()
              && matches(result.observed(), currentRules);
          boolean selected = confirmed && matches(result.observed(), requested);
          KataGoRules observed = confirmed ? result.observed()
              : operationStillCurrent && recovered != null && recovered.isConfirmed()
                  && matches(recovered.observed(), currentRules) ? recovered.observed() : null;
          boolean preserveCache = canPreserveCache(result, observed);
          if (selected) {
            if (!preserveCache
                || !compatibleTarget(intent.previousTarget(), requested)
                || (currentMirror != null && !matches(currentMirror.currentConfirmedRules(), requested))) {
              List<WeakReference<BoardData>> currentData = captureRetainedData(history);
              primaryCache.invalidateAtSettlement(primary, currentData);
              if (Lizzie.config.isDoubleEngineMode()) {
                if (currentMirror != null && currentMirror == mirror
                    && currentMirror.engineIncarnationSnapshot() == mirrorIncarnation) {
                  mirrorCache.invalidateAtSettlement(mirror, currentData);
                } else {
                  board.clearBestMovesAfterForSecondEngine(history.getStart());
                }
              }
            }
            published[0] = history.publishManualRulesIfCurrent(intent, observed).orElse(null);
          } else if (!preserveCache) {
            primaryCache.invalidateAtSettlement(primary, captureRetainedData(history));
          }
          if (!selected && preserveCache && previousOverride && matches(previousRules, observed)
              && currentMirror == mirror
              && (mirror == null || (mirror.engineIncarnationSnapshot() == mirrorIncarnation
                  && matches(previousMirrorRules, mirror.currentConfirmedRules())))) {
            history.authorizeAnalysisWithCurrentRules(
                intent.previousTarget(), primary, primaryGeneration, mirror);
          }
          resume[0] = !selected && observed != null;
          history.finishManualRulesIntent(intent);
        });
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

  // A current request may still need to settle after another writer revoked its cache provenance.
  private boolean hasCacheProvenance() {
    return !inheritedUncertainChange && primary.manualRulesCacheOwner.get() == this;
  }

  private boolean canPreserveCache(EngineRulesResult result, KataGoRules observed) {
    return hasCacheProvenance()
        && (matches(previousRules, observed)
            || (rejectedWithoutChange(result) && observed == null));
  }

  private void finish() {
    history.finishManualRulesIntent(intent);
    primary.manualRulesCacheOwner.compareAndSet(this, null);
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

  // Capture under history admission; physical writers must never traverse or lock the history.
  private static List<WeakReference<BoardData>> captureRetainedData(BoardHistoryList history) {
    List<WeakReference<BoardData>> retained = new ArrayList<>();
    ArrayDeque<BoardHistoryNode> nodes = new ArrayDeque<>();
    nodes.push(history.getStart());
    while (!nodes.isEmpty()) {
      BoardHistoryNode node = nodes.pop();
      retained.add(new WeakReference<>(node.getData()));
      nodes.addAll(node.getVariations());
    }
    return List.copyOf(retained);
  }

  /** One-shot protection of the payloads retained by a manual selection, per engine slot. */
  static final class RetainedCache {
    private final WeakReference<Board> board;
    private final WeakReference<BoardHistoryList> history;
    private final WeakReference<Leelaz> primary;
    private final long primaryGeneration;
    private final boolean secondary;
    private final List<WeakReference<BoardData>> retained;
    private boolean invalidated;

    private RetainedCache(ManualRulesSelection selection,
        List<WeakReference<BoardData>> retained, boolean secondary) {
      board = new WeakReference<>(selection.board);
      history = new WeakReference<>(selection.history);
      primary = new WeakReference<>(selection.primary);
      primaryGeneration = selection.primaryGeneration;
      this.secondary = secondary;
      this.retained = retained;
    }

    boolean sameSelection(RetainedCache other) {
      return board.get() != null && board.get() == other.board.get()
          && history.get() != null && history.get() == other.history.get()
          && primary.get() != null && primary.get() == other.primary.get()
          && primaryGeneration == other.primaryGeneration && secondary == other.secondary;
    }

    // Only state snapshots and BoardData locks here: writers may hold endpoint/protocol locks.
    synchronized void invalidate(Leelaz receiver) {
      if (invalidated) return;
      Board currentBoard = board.get();
      Leelaz currentPrimary = primary.get();
      if (currentBoard == null || currentPrimary == null || Lizzie.board != currentBoard
          || currentBoard.getHistory() != history.get()
          || Lizzie.capturePrimaryEngineGenerationSnapshot(currentPrimary) != primaryGeneration
          || receiver != (secondary ? currentPrimary.activeComparisonEngine() : currentPrimary)) return;
      invalidated = true;
      for (WeakReference<BoardData> reference : retained) {
        BoardData data = reference.get();
        if (data == null) continue;
        if (secondary) data.invalidateSecondaryAnalysis();
        else data.invalidatePrimaryAnalysis();
      }
    }

    // Preserve full-tree settlement semantics if editing/sync added cached nodes while pending.
    // The caller captures currentData under history admission, before entering this monitor.
    synchronized void invalidateAtSettlement(
        Leelaz receiver, List<WeakReference<BoardData>> currentData) {
      invalidate(receiver);
      Set<BoardData> captured = Collections.newSetFromMap(new IdentityHashMap<>());
      for (WeakReference<BoardData> reference : retained) {
        BoardData data = reference.get();
        if (data != null) captured.add(data);
      }
      for (WeakReference<BoardData> reference : currentData) {
        BoardData data = reference.get();
        if (data == null || captured.contains(data)) continue;
        if (secondary) data.invalidateSecondaryAnalysis();
        else data.invalidatePrimaryAnalysis();
      }
    }
  }
}
