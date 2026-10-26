package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineGameParticipantCommands.Leg;
import featurecat.lizzie.analysis.EngineManager.EngineGameOwnerTransaction;
import featurecat.lizzie.enginegame.EngineGameOpeningParameters;
import featurecat.lizzie.enginegame.EngineGameParameterRequest;
import featurecat.lizzie.enginegame.EngineGameParameterRevision;
import featurecat.lizzie.enginegame.EngineGameParameterState;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.SetKataEngines;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.rules.Board;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breadth ({@code analysisWideRootNoise}) and aggressiveness ({@code playoutDoublingAdvantage}) of
 * one analysis-mode engine-game batch.
 *
 * <p>Keeps apart the batch's frozen opening settings, the current game's requested targets, the
 * values each participant process confirmed by readback, and the values each participant process
 * had before the batch first changed them. Each game applies the opening settings before analysis
 * starts; in-game changes stay with that game and write only the edited parameters. When the batch
 * ends, only the exact captured processes are restored; a replacement process is never written with
 * another process's record. {@link Leelaz} keeps command queuing, numbered responses, timeouts and
 * output arbitration.
 */
public final class EngineGameParameterSession {
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.ENGINE);

  /** The parameters a game sets, in the order they are written. */
  private enum Parameter {
    WRN("analysisWideRootNoise"),
    PDA("playoutDoublingAdvantage");

    private final String gtpName;

    private static final MathContext WRN_READBACK_PRECISION =
        new MathContext(6, RoundingMode.HALF_EVEN);

    Parameter(String gtpName) {
      this.gtpName = gtpName;
    }

    /**
     * KataGo's WRN getter uses Global::doubleToString (six significant digits), whereas PDA
     * uses the JSON serializer's round-trip representation. Accept either an exact reply or
     * WRN's rounded representation, not an arbitrary absolute/relative error. This belongs
     * only to protocol verification: distinct requested values must still be sent in full.
     */
    private boolean matchesReadback(double expected, double actual) {
      return expected == actual
          || (this == WRN
              // Round the binary double itself, including values beside decimal halfway points.
              && new BigDecimal(expected, WRN_READBACK_PRECISION).doubleValue() == actual);
    }
  }

  private static final Parameter[] PARAMETERS = Parameter.values();

  private final EngineGameOpeningParameters opening;

  /** The game board whose analysis cache a confirmed change invalidates; may be null in tests. */
  private final Board board;

  private final AtomicBoolean restoreClaimed = new AtomicBoolean();
  private final CompletableFuture<Void> restored = new CompletableFuture<>();

  /** Replaced under this monitor; admission reads the immutable snapshot without taking it. */
  private volatile List<Original> originals = List.of();

  /** Retained until restoration finishes, including when a newer batch fails admission. */
  private volatile EngineGameParameterSession predecessor;

  /** Guarded by this; reads of {@link Game#blocked} are lock-free. */
  private volatile Game game;

  /** A process's values before the batch; null for a parameter the process does not take. */
  private record Original(String label, Leelaz engine, Object incarnation, Double wrn, Double pda) {
    private Double value(Parameter parameter) {
      return parameter == Parameter.WRN ? wrn : pda;
    }
  }

  /**
   * One game's parameter state. Arrays are indexed by [parameter ordinal][position], position 0 =
   * Engine 1, 1 = Engine 2.
   */
  private static final class Game {
    private final EngineGameOwnerTransaction owner;
    private final boolean[][] supported = new boolean[2][2];
    private final Double[][] confirmed = new Double[2][2];
    private final Double[][] target = new Double[2][2];
    private boolean pending;
    private boolean workerRunning;
    private String failure;
    private volatile boolean blocked;

    private Game(EngineGameOwnerTransaction owner) {
      this.owner = owner;
    }

    private void refreshBlocked() {
      blocked = pending || failure != null;
    }
  }

  private EngineGameParameterSession(
      EngineGameOpeningParameters opening, Board board, EngineGameParameterSession predecessor) {
    this.opening = Objects.requireNonNull(opening, "opening");
    this.board = board;
    this.predecessor = predecessor;
  }

  /**
   * Session for a new analysis batch. A genmove batch retains only the predecessor so startup can
   * wait for its restoration before sending time or search commands; it never installs that session
   * on the genmove owner. An analysis batch restores its predecessor before capturing any
   * parameters.
   */
  static EngineGameParameterSession forBatch(
      featurecat.lizzie.enginegame.EngineGamePlan plan,
      Board board,
      EngineGameParameterSession predecessor) {
    if (plan.genmove()) {
      if (predecessor != null) predecessor.restoreAsync();
      return predecessor;
    }
    return new EngineGameParameterSession(plan.openingParameters(), board, predecessor);
  }

  /**
   * Restores the batch's processes once a retired game leaves the batch idle. The stop path calls
   * this before releasing the participants; retirement completion and the successor continuation
   * are fallbacks for failed, cancelled or between-games endings and must not block their caller.
   * While a successor or a newer batch is active, the newer game's opening restores this batch
   * first.
   */
  static void restoreIfBatchEnded(EngineGameOwnerTransaction transaction, boolean async) {
    EngineGameParameterSession session = transaction.parameters;
    if (session == null
        || (Lizzie.engineGame != null
            && !(Lizzie.engineGame.current() instanceof EngineGameSnapshot.Idle))) {
      return;
    }
    if (async) {
      session.restoreAsync();
    } else {
      session.restoreOnce();
    }
  }

  // ---- Product-facing reads and requests -------------------------------------------------

  public static EngineGameParameterState state(Object ownerToken) {
    if (!(ownerToken instanceof EngineGameOwnerTransaction owner) || owner.parameters == null) {
      return null;
    }
    return owner.parameters.stateFor(owner);
  }

  /** Records one apply request as the game's latest target; only the update worker writes. */
  public static EngineGameParameterRevision revise(
      Object ownerToken, EngineGameParameterRequest request, Runnable onChange) {
    if (!(ownerToken instanceof EngineGameOwnerTransaction owner)
        || owner.parameters == null
        || request == null) {
      return EngineGameParameterRevision.NOT_PLAYING;
    }
    return owner.parameters.reviseFor(owner, request, onChange);
  }

  static boolean blocksSearch(EngineGameOwnerTransaction owner) {
    EngineGameParameterSession session = owner.parameters;
    Game current = session == null ? null : session.game;
    return current != null && current.owner == owner && current.blocked;
  }

  /** Parameter ownership outlives the last game's save and retirement until readback finishes. */
  boolean hasUnrestoredParameters() {
    EngineGameParameterSession previous = predecessor;
    return (!originals.isEmpty() && !restored.isDone())
        || (previous != null && previous.hasUnrestoredParameters());
  }

  private synchronized EngineGameParameterState stateFor(EngineGameOwnerTransaction owner) {
    Game current = game;
    if (current == null || current.owner != owner) {
      return null;
    }
    return new EngineGameParameterState(
        position(current, 0), position(current, 1), current.pending, current.failure);
  }

  private static EngineGameParameterState.Position position(Game current, int p) {
    return new EngineGameParameterState.Position(
        value(current, Parameter.WRN, p), value(current, Parameter.PDA, p));
  }

  private static EngineGameParameterState.Value value(Game current, Parameter parameter, int p) {
    int k = parameter.ordinal();
    Double target = current.target[k][p];
    Double confirmed = current.confirmed[k][p];
    boolean unmet = target != null && !sameValue(target, confirmed);
    return new EngineGameParameterState.Value(
        current.supported[k][p], confirmed, unmet ? target : null);
  }

  private EngineGameParameterRevision reviseFor(
      EngineGameOwnerTransaction owner, EngineGameParameterRequest request, Runnable onChange) {
    for (Parameter parameter : PARAMETERS) {
      for (int p = 0; p < 2; p++) {
        Double value = requested(request, parameter, p);
        if (value != null && !valid(parameter, value)) {
          return EngineGameParameterRevision.INVALID;
        }
      }
    }
    Game current;
    boolean start;
    synchronized (this) {
      current = game;
      if (current == null
          || current.owner != owner
          || owner.phase() != EngineManager.EngineGamePhase.ACTIVE
          || owner.isGenmove()
          || !EngineManager.isCurrentEngineGameTransaction(owner)) {
        return EngineGameParameterRevision.NOT_PLAYING;
      }
      // Every requested participant and parameter is checked before anything is recorded.
      Double[][] next = {current.target[0].clone(), current.target[1].clone()};
      for (Parameter parameter : PARAMETERS) {
        int k = parameter.ordinal();
        for (int p = 0; p < 2; p++) {
          Double value = requested(request, parameter, p);
          if (value == null) continue;
          if (!current.supported[k][p]) return EngineGameParameterRevision.UNSUPPORTED;
          next[k][p] = value;
        }
      }
      if (!current.pending && current.failure == null && !unmet(next, current.confirmed)) {
        return EngineGameParameterRevision.UNCHANGED;
      }
      for (int k = 0; k < 2; k++) {
        System.arraycopy(next[k], 0, current.target[k], 0, 2);
      }
      current.pending = true;
      current.refreshBlocked();
      start = !current.workerRunning;
      current.workerRunning = true;
    }
    onChange.run();
    if (start
        && !owner.searchUpdates.submit(
            "engine-game-parameters-" + owner.epoch(), () -> runUpdate(current, onChange))) {
      fail(current, text("EngineGameParameters.scheduleFailed"), onChange);
    }
    return EngineGameParameterRevision.ACCEPTED;
  }

  // ---- Opening -------------------------------------------------------------------------

  /**
   * Applies the batch's opening settings to both participants before the game's first analysis. The
   * first time a participant process appears in this batch, its actual values (after normal startup
   * and auto-load) are captured as the values to restore when the batch ends. A participant that
   * cannot take a parameter (not KataGo, or a dedicated-PDA engine for aggressiveness) is left
   * alone only when the opening value is zero. Throws when a participant cannot be captured, set or
   * confirmed; the game must not start then.
   */
  void applyOpening(EngineGameOwnerTransaction owner) throws InterruptedException {
    finishPredecessor(owner.manager.engineGameStartupTimeoutMillis());
    owner.parameters = this;
    Game next = new Game(owner);
    boolean[] capturing = new boolean[2];
    for (int p = 0; p < 2; p++) {
      Leelaz engine = engine(owner, p);
      for (Parameter parameter : PARAMETERS) {
        boolean supported = supports(engine, parameter);
        next.supported[parameter.ordinal()][p] = supported;
        if (!supported && openingValue(parameter, p) != 0) {
          throw new IllegalStateException(label(p) + " does not support " + parameter.gtpName);
        }
        capturing[p] |= supported;
      }
      capturing[p] &= original(engine, incarnation(owner, p)) == null;
    }
    // Every participant is checked and captured before the first write.
    Double[][] captured = new Double[2][2];
    for (Parameter parameter : PARAMETERS) {
      int k = parameter.ordinal();
      List<Leg> captures = new ArrayList<>();
      for (int p = 0; p < 2; p++) {
        if (!capturing[p] || !next.supported[k][p]) continue;
        int position = p;
        captures.add(
            new Leg(
                label(p),
                engine(owner, p),
                incarnation(owner, p),
                "kata-get-param " + parameter.gtpName,
                payload -> {
                  Double value = parse(payload);
                  if (value == null) {
                    return "unreadable " + parameter.gtpName + " '" + payload + "'";
                  }
                  captured[k][position] = value;
                  return null;
                }));
      }
      EngineGameParticipantCommands.confirmAll(owner, captures);
    }
    synchronized (this) {
      List<Original> updated = new ArrayList<>(originals);
      for (int p = 0; p < 2; p++) {
        if (capturing[p]) {
          updated.add(
              new Original(
                  label(p),
                  engine(owner, p),
                  incarnation(owner, p),
                  captured[Parameter.WRN.ordinal()][p],
                  captured[Parameter.PDA.ordinal()][p]));
        }
      }
      originals = List.copyOf(updated);
    }
    Double[][] values = new Double[2][2];
    for (Parameter parameter : PARAMETERS) {
      for (int p = 0; p < 2; p++) {
        if (next.supported[parameter.ordinal()][p]) {
          values[parameter.ordinal()][p] = openingValue(parameter, p);
        }
      }
    }
    applyValues(owner, values, null);
    synchronized (this) {
      for (int k = 0; k < 2; k++) {
        System.arraycopy(values[k], 0, next.confirmed[k], 0, 2);
        System.arraycopy(values[k], 0, next.target[k], 0, 2);
      }
      next.refreshBlocked();
      game = next;
    }
  }

  // ---- In-game update ------------------------------------------------------------------

  private void runUpdate(Game current, Runnable onChange) {
    EngineGameOwnerTransaction owner = current.owner;
    try {
      // Stop both searches and settle earlier position commands. A move that already passed its
      // commit gate may finish; uncommitted results lost their right to play when pending was set.
      EngineGameParticipantCommands.confirmAll(
          owner,
          List.of(
              new Leg(label(0), engine(owner, 0), incarnation(owner, 0), null, null),
              new Leg(label(1), engine(owner, 1), incarnation(owner, 1), null, null)));
      long deadline =
          System.nanoTime()
              + TimeUnit.MILLISECONDS.toNanos(owner.manager.engineGameStartupTimeoutMillis());
      while (EngineManager.isCurrentEngineGameTransaction(owner)
          && owner.operationsInFlight.get() > 1) {
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException(text("EngineGameParameters.turnTimeout"));
        }
        Thread.sleep(10L);
      }
      if (!EngineManager.isCurrentEngineGameTransaction(owner)) return;
      owner.mutationLock.lock();
      try {
        owner.searchRevision++;
      } finally {
        owner.mutationLock.unlock();
      }
      // The update consumes the current move's budget instead of starting a new one.
      long[] ponderStarts = {owner.blackEngine.startPonderTime, owner.whiteEngine.startPonderTime};
      long[] moveStarts = {owner.blackEngine.pkMoveStartTime, owner.whiteEngine.pkMoveStartTime};
      while (true) {
        Double[][] write = new Double[2][2];
        synchronized (this) {
          for (int k = 0; k < 2; k++) {
            for (int p = 0; p < 2; p++) {
              Double target = current.target[k][p];
              if (target != null && !sameValue(target, current.confirmed[k][p])) {
                // Only edited parameters are written. A timed-out setter may still apply later,
                // so the value is unknown until confirmed.
                write[k][p] = target;
                current.confirmed[k][p] = null;
              }
            }
          }
        }
        try {
          applyValues(owner, write, current);
        } finally {
          // Any write may have changed a value, even when another one failed: analysis from
          // before it must not be shown or played, also while a failed update keeps the game
          // paused.
          invalidateAnalysis();
        }
        boolean again;
        owner.mutationLock.lock();
        try {
          if (!EngineManager.isCurrentEngineGameTransaction(owner)) return;
          synchronized (this) {
            again = unmet(current.target, current.confirmed);
            if (!again) {
              current.pending = false;
              current.failure = null;
              current.workerRunning = false;
              current.refreshBlocked();
            }
          }
          if (!again) {
            if (!owner.paused && (owner.product == null || !owner.product.paused())) {
              // A fast reply may commit the next move as soon as analysis resumes. Keep the
              // commit fence until ponder's clock reset has been undone for this same move.
              EngineManager.continueEngineGame(owner);
              owner.blackEngine.startPonderTime = ponderStarts[0];
              owner.whiteEngine.startPonderTime = ponderStarts[1];
              owner.blackEngine.pkMoveStartTime = moveStarts[0];
              owner.whiteEngine.pkMoveStartTime = moveStarts[1];
            }
          }
        } finally {
          owner.mutationLock.unlock();
        }
        onChange.run();
        if (!again) {
          return;
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      fail(current, text("EngineGameParameters.updateInterrupted"), onChange);
    } catch (RuntimeException failure) {
      fail(current, failure.getMessage(), onChange);
    }
  }

  private void invalidateAnalysis() {
    Board board = this.board;
    if (board != null) {
      synchronized (board) {
        board.clearBestMovesAfter(board.getHistory().getStart());
      }
    }
  }

  /**
   * Sets and reads back each non-null value, one parameter at a time. With a game, every confirmed
   * value is published as it is verified so a partial failure still reports which participant holds
   * which value; the call fails unless every value was confirmed.
   */
  private void applyValues(EngineGameOwnerTransaction owner, Double[][] values, Game current)
      throws InterruptedException {
    StringBuilder failure = new StringBuilder();
    for (Parameter parameter : PARAMETERS) {
      Double[] parameterValues = values[parameter.ordinal()];
      List<Leg> sets = new ArrayList<>();
      List<Integer> positions = new ArrayList<>();
      for (int p = 0; p < 2; p++) {
        if (parameterValues[p] == null) continue;
        positions.add(p);
        sets.add(
            setLeg(
                label(p), engine(owner, p), incarnation(owner, p), parameter, parameterValues[p]));
      }
      if (sets.isEmpty()) continue;
      String[] setFailures = EngineGameParticipantCommands.confirm(owner, sets, false);
      List<Leg> reads = new ArrayList<>();
      List<Integer> readPositions = new ArrayList<>();
      for (int i = 0; i < sets.size(); i++) {
        if (setFailures[i] != null) continue;
        int p = positions.get(i);
        readPositions.add(p);
        reads.add(
            verifyLeg(
                label(p), engine(owner, p), incarnation(owner, p), parameter, parameterValues[p]));
      }
      String[] readFailures = EngineGameParticipantCommands.confirm(owner, reads, false);
      for (String detail : setFailures) append(failure, detail);
      for (int i = 0; i < reads.size(); i++) {
        int p = readPositions.get(i);
        if (readFailures[i] != null) {
          append(failure, readFailures[i]);
          continue;
        }
        // Readback can finish just before Stop retires this owner and restores the batch.
        // Publish under the terminal transition's fence, or leave the restored state alone.
        owner.mutationLock.lock();
        try {
          if (!EngineManager.isCurrentEngineGameTransaction(owner)) {
            throw new IllegalStateException(text("EngineGameParameters.transactionLost"));
          }
          if (!publish(engine(owner, p), incarnation(owner, p), parameter, parameterValues[p])) {
            append(failure, label(p) + ": " + text("EngineGameParameters.processChanged"));
            continue;
          }
          if (current != null) {
            synchronized (this) {
              current.confirmed[parameter.ordinal()][p] = parameterValues[p];
            }
          }
        } finally {
          owner.mutationLock.unlock();
        }
      }
    }
    if (failure.length() > 0) {
      throw new IllegalStateException(failure.toString());
    }
  }

  /** A failed update keeps the game paused; only a later successful apply resolves it. */
  private void fail(Game current, String detail, Runnable onChange) {
    EngineGameOwnerTransaction owner = current.owner;
    synchronized (this) {
      current.pending = false;
      current.workerRunning = false;
      current.failure = detail == null ? text("EngineGameParameters.updateFailedDetail") : detail;
      current.refreshBlocked();
    }
    LOG.warn("Engine-game parameter update failed: {}", current.failure);
    if (EngineManager.isCurrentEngineGameTransaction(owner)) {
      EngineManager.pauseEngineGame(owner);
    }
    onChange.run();
  }

  // ---- Batch restore -------------------------------------------------------------------

  /** Restores the batch's captured processes once. Safe to call from every batch-ending path. */
  void restoreOnce() {
    if (!restoreClaimed.compareAndSet(false, true)) {
      return;
    }
    try {
      EngineGameParameterSession previous = predecessor;
      if (previous != null) previous.restoreAsync();
      restoreOriginals();
    } finally {
      restored.complete(null);
      Menu.requestBreadthAdmissionRefresh();
      SwingUtilities.invokeLater(SetKataEngines::refreshOpenDialogs);
    }
  }

  void restoreAsync() {
    if (restoreClaimed.get()) {
      return;
    }
    Thread worker = new Thread(this::restoreOnce, "engine-game-parameter-restore");
    worker.setDaemon(true);
    worker.start();
  }

  /** Waits for restoration before a later batch uses the same participant processes. */
  void restoreAndWait(long timeoutMillis) throws InterruptedException {
    finishPredecessor(timeoutMillis);
    restoreOnce();
    try {
      restored.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException unfinished) {
      throw new IllegalStateException("The previous engine-game batch is still restoring");
    }
  }

  private void restoreOriginals() {
    List<Original> records;
    synchronized (this) {
      records = originals;
      game = null;
    }
    List<String> failures = new ArrayList<>();
    Map<Original, Leelaz.LifecycleCompletionClaim> claims = new LinkedHashMap<>();
    try {
      // Hold every live participant until both restores finish. The game may already be saved
      // and Idle, but ordinary commands must not interleave with these borrowed-parameter writes.
      for (Original original : records) {
        if (!original.engine().isCurrentLiveEngineIncarnation(original.incarnation())) {
          String gone = original.label() + ": " + text("EngineGameParameters.processChanged");
          failures.add(gone);
          LOG.warn("Engine-game parameter restore failed: {}", gone);
          continue;
        }
        Leelaz.LifecycleCompletionClaim claim =
            original.engine().tryBeginLifecycleCompletion(this, null);
        if (claim == null) {
          failRestore(
              original,
              original.label() + ": " + text("EngineGameParameters.updateFailedDetail"),
              failures);
        } else {
          claims.put(original, claim);
        }
      }
      for (Map.Entry<Original, Leelaz.LifecycleCompletionClaim> entry : claims.entrySet()) {
        entry.getValue().runAsCompletionFenceOwner(() -> restoreOriginal(entry.getKey(), failures));
      }
    } finally {
      for (Leelaz.LifecycleCompletionClaim claim : claims.values()) {
        claim.abandonBeforeFence();
      }
    }
    if (!failures.isEmpty() && Lizzie.engineGame != null) {
      Lizzie.engineGame.reportParameterRestoreFailure(String.join("; ", failures));
    }
  }

  private void restoreOriginal(Original original, List<String> failures) {
    String detail = null;
    try {
      for (Parameter parameter : PARAMETERS) {
        Double value = original.value(parameter);
        if (value == null) continue;
        Leelaz engine = original.engine();
        Object incarnation = original.incarnation();
        detail =
            first(
                EngineGameParticipantCommands.confirm(
                    null,
                    List.of(setLeg(original.label(), engine, incarnation, parameter, value)),
                    true));
        if (detail == null) {
          detail =
              first(
                  EngineGameParticipantCommands.confirm(
                      null,
                      List.of(verifyLeg(original.label(), engine, incarnation, parameter, value)),
                      true));
        }
        if (detail != null) break;
        if (!publish(engine, incarnation, parameter, value)) {
          detail = original.label() + ": " + text("EngineGameParameters.processChanged");
          break;
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      detail = original.label() + ": " + text("EngineGameParameters.restoreInterrupted");
    }
    if (detail != null) {
      failRestore(original, detail, failures);
    }
  }

  private void failRestore(Original original, String detail, List<String> failures) {
    // An unconfirmed process cannot be handed back with game parameters. Never stop a replacement.
    failures.add(detail);
    LOG.warn("Engine-game parameter restore failed: {}", detail);
    original.engine().forceQuitIfCurrentIncarnation(original.incarnation());
  }

  private void finishPredecessor(long timeoutMillis) throws InterruptedException {
    EngineGameParameterSession previous = predecessor;
    if (previous == null) {
      return;
    }
    previous.restoreAndWait(timeoutMillis);
    predecessor = null;
  }

  // ---- Helpers -------------------------------------------------------------------------

  private synchronized Original original(Leelaz engine, Object incarnation) {
    for (Original original : originals) {
      if (original.engine() == engine && original.incarnation() == incarnation) return original;
    }
    return null;
  }

  private static Leg setLeg(
      String label, Leelaz engine, Object incarnation, Parameter parameter, double value) {
    return new Leg(
        label,
        engine,
        incarnation,
        "kata-set-param " + parameter.gtpName + " " + plain(value),
        null);
  }

  private static Leg verifyLeg(
      String label, Leelaz engine, Object incarnation, Parameter parameter, double expected) {
    return new Leg(
        label,
        engine,
        incarnation,
        "kata-get-param " + parameter.gtpName,
        payload -> {
          Double actual = parse(payload);
          return actual != null && parameter.matchesReadback(expected, actual)
              ? null
              : text("EngineGameParameters.readbackMismatch", payload, plain(expected));
        });
  }

  /** A readback may complete just before the process is replaced; publish only to that process. */
  private static boolean publish(
      Leelaz engine, Object incarnation, Parameter parameter, double value) {
    return engine.runIfCurrentLiveEngineIncarnation(
        incarnation,
        () -> {
          if (parameter == Parameter.WRN) {
            engine.wrn = value;
          } else {
            engine.pda = value;
          }
        });
  }

  /**
   * KataGo takes breadth through {@code kata-set-param}; aggressiveness too, except on
   * dedicated-PDA engines, whose own PDA strategy is never overridden through that interface.
   */
  private static boolean supports(Leelaz engine, Parameter parameter) {
    return engine.isKatago
        && (parameter == Parameter.WRN || !(engine.isKataGoPda || engine.isDymPda));
  }

  private double openingValue(Parameter parameter, int position) {
    return parameter == Parameter.WRN ? opening.wrn(position == 0) : opening.pda(position == 0);
  }

  private static Double requested(
      EngineGameParameterRequest request, Parameter parameter, int position) {
    return parameter == Parameter.WRN ? request.wrn(position == 0) : request.pda(position == 0);
  }

  private static boolean valid(Parameter parameter, double value) {
    return parameter == Parameter.WRN
        ? EngineGameOpeningParameters.validWrn(value)
        : EngineGameOpeningParameters.validPda(value);
  }

  private static Leelaz engine(EngineGameOwnerTransaction owner, int position) {
    return black(owner, position) ? owner.blackEngine : owner.whiteEngine;
  }

  private static Object incarnation(EngineGameOwnerTransaction owner, int position) {
    return black(owner, position) ? owner.blackIncarnation : owner.whiteIncarnation;
  }

  private static boolean black(EngineGameOwnerTransaction owner, int position) {
    return (position == 0) == owner.plan.firstIsBlack();
  }

  /** The participant position as the user sees it, also in failure details shown to them. */
  private static String label(int position) {
    return text(
        position == 0 ? "EngineGameParameters.position1" : "EngineGameParameters.position2");
  }

  /** User-visible failure text; engine responses are only ever its arguments. */
  static String text(String key, Object... arguments) {
    return MessageFormat.format(Lizzie.resourceBundle.getString(key), arguments);
  }

  private static boolean unmet(Double[][] target, Double[][] confirmed) {
    for (int k = 0; k < 2; k++) {
      for (int p = 0; p < 2; p++) {
        if (target[k][p] != null && !sameValue(target[k][p], confirmed[k][p])) return true;
      }
    }
    return false;
  }

  /** Confirmed values retain the full request; readback formatting does not change user intent. */
  private static boolean sameValue(Double expected, Double actual) {
    return actual != null && expected.doubleValue() == actual.doubleValue();
  }

  private static Double parse(String payload) {
    try {
      double value = Double.parseDouble(payload.trim());
      return Double.isFinite(value) ? value : null;
    } catch (NumberFormatException | NullPointerException invalid) {
      return null;
    }
  }

  private static String plain(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  private static String first(String[] failures) {
    for (String failure : failures) {
      if (failure != null) return failure;
    }
    return null;
  }

  private static void append(StringBuilder failure, String detail) {
    if (detail == null) return;
    if (failure.length() > 0) failure.append("; ");
    failure.append(detail);
  }
}
