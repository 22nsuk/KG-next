package featurecat.lizzie;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Observable startup state used by the main window instead of modal engine progress dialogs. */
public final class EngineStartupStatus {
  public enum State {
    READY,
    CHECKING,
    NEEDS_REPAIR,
    START_FAILED
  }

  public static final class Snapshot {
    private final EngineStartupStatus owner;
    public final long revision;
    public final State state;
    public final String messageKey;
    public final String fallback;
    public final String detail;

    private Snapshot(
        EngineStartupStatus owner,
        long revision,
        State state,
        String messageKey,
        String fallback,
        String detail) {
      this.owner = owner;
      this.revision = revision;
      this.state = state;
      this.messageKey = messageKey == null ? "" : messageKey;
      this.fallback = fallback == null ? "" : fallback;
      this.detail = detail == null ? "" : detail;
    }

    public boolean isActionable() {
      return state == State.NEEDS_REPAIR || state == State.START_FAILED;
    }

    /** True only while this exact publication is still the owner's latest state. */
    public boolean isCurrent() {
      return owner != null && owner.snapshot == this;
    }
  }

  /**
   * A state publication whose logical commit has already happened, but whose observer callbacks
   * are intentionally deferred until the caller has released lifecycle/engine locks.
   */
  public static final class PreparedNotification implements Runnable {
    private final EngineStartupStatus owner;
    private final Snapshot snapshot;

    private PreparedNotification(EngineStartupStatus owner, Snapshot snapshot) {
      this.owner = owner;
      this.snapshot = snapshot;
    }

    public Snapshot snapshot() {
      return snapshot;
    }

    public boolean isCurrent() {
      return snapshot.isCurrent();
    }

    @Override
    public void run() {
      if (isCurrent()) {
        owner.notifyPreparedListeners(snapshot);
      }
    }
  }

  private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners =
      new CopyOnWriteArrayList<>();
  private final AtomicLong revisions = new AtomicLong();
  private volatile Snapshot snapshot =
      new Snapshot(this, 0L, State.READY, "", "", "");

  public Snapshot snapshot() {
    return snapshot;
  }

  public void addListener(Consumer<Snapshot> listener) {
    Consumer<Snapshot> safeListener = Objects.requireNonNull(listener, "listener");
    listeners.add(safeListener);
    safeListener.accept(snapshot);
  }

  public void removeListener(Consumer<Snapshot> listener) {
    listeners.remove(listener);
  }

  public void ready() {
    update(State.READY, "", "", "");
  }

  /** Commits READY without invoking arbitrary listeners under the caller's identity locks. */
  public PreparedNotification prepareReady() {
    return prepare(State.READY, "", "");
  }

  public PreparedNotification prepareChecking(String messageKey, String fallback) {
    return prepare(State.CHECKING, messageKey, fallback);
  }

  private synchronized PreparedNotification prepare(State state, String messageKey, String fallback) {
    Snapshot next = newSnapshot(state, messageKey, fallback, "");
    snapshot = next;
    return new PreparedNotification(this, next);
  }

  public void checking(String messageKey, String fallback) {
    update(State.CHECKING, messageKey, fallback, "");
  }

  public void needsRepair(String messageKey, String fallback, String detail) {
    update(State.NEEDS_REPAIR, messageKey, fallback, detail);
  }

  public void failed(String messageKey, String fallback, String detail) {
    update(State.START_FAILED, messageKey, fallback, detail);
  }

  /**
   * Atomically installs a failed state only while {@code expected} is still current, but defers
   * listener callbacks to the returned action. Callers may therefore perform the identity check
   * and state commit under their own lock, then invoke the returned action after releasing it.
   */
  public PreparedNotification prepareFailedIfCurrent(
      Snapshot expected, String messageKey, String fallback, String detail) {
    if (expected == null) {
      return null;
    }
    PreparedNotification notification;
    synchronized (this) {
      if (snapshot != expected) {
        return null;
      }
      Snapshot next = newSnapshot(State.START_FAILED, messageKey, fallback, detail);
      notification = new PreparedNotification(this, next);
      snapshot = next;
    }
    return notification;
  }

  /** Installs a failed state immediately while still deferring listener callbacks. */
  public PreparedNotification prepareFailed(String messageKey, String fallback, String detail) {
    PreparedNotification notification;
    synchronized (this) {
      Snapshot next = newSnapshot(State.START_FAILED, messageKey, fallback, detail);
      notification = new PreparedNotification(this, next);
      snapshot = next;
    }
    return notification;
  }

  private void update(State state, String messageKey, String fallback, String detail) {
    Snapshot next;
    synchronized (this) {
      next = newSnapshot(state, messageKey, fallback, detail);
      snapshot = next;
    }
    notifyListeners(next);
  }

  private Snapshot newSnapshot(
      State state, String messageKey, String fallback, String detail) {
    return new Snapshot(this, revisions.incrementAndGet(), state, messageKey, fallback, detail);
  }

  private void notifyListeners(Snapshot next) {
    for (Consumer<Snapshot> listener : listeners) {
      listener.accept(next);
    }
  }

  private void notifyPreparedListeners(Snapshot next) {
    for (Consumer<Snapshot> listener : listeners) {
      try {
        listener.accept(next);
      } catch (RuntimeException | Error failure) {
        failure.printStackTrace();
      }
    }
  }
}
