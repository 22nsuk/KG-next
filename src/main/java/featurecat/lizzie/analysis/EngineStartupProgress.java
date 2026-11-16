package featurecat.lizzie.analysis;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** One process incarnation's loading budget, shared by its startup waiters and presentation. */
public final class EngineStartupProgress {
  // Allow two of the existing ten-minute cold-GPU preparation windows, but never an endless
  // extension from a stuck tuning flag or a noisy engine. This is a budget, not a loading estimate.
  static final long MAXIMUM_STARTUP_MILLIS = TimeUnit.MINUTES.toMillis(20);
  private static final long TUNING_STEP_MILLIS = TimeUnit.MINUTES.toMillis(2);
  private static final Pattern TESTING_CONFIGS =
      Pattern.compile("Testing [1-9][0-9]* different configs");
  private static final Pattern TIMESTAMP =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[^\\r\\n]*?: ");

  public enum Stage {
    WAITING_RESPONSE,
    GPU_TUNING,
    TENSORRT_PLAN,
    SYNCHRONIZING,
    EXPIRED,
    RETIRED
  }

  public record Snapshot(Stage stage, long elapsedMillis, long remainingMillis) {}

  private final long startedNanos;
  private final long maximumNanos;
  private final long preparationNanos;
  private long budgetNanos;
  private Stage stage = Stage.WAITING_RESPONSE;

  EngineStartupProgress(long startedNanos, long initialMillis, long preparationMillis) {
    this.startedNanos = startedNanos;
    maximumNanos = TimeUnit.MILLISECONDS.toNanos(MAXIMUM_STARTUP_MILLIS);
    preparationNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, preparationMillis));
    budgetNanos = Math.min(maximumNanos, TimeUnit.MILLISECONDS.toNanos(Math.max(1L, initialMillis)));
  }

  /** Only real progress received before expiration can move the deadline forward. */
  synchronized boolean observe(String line, long now) {
    if (stage == Stage.SYNCHRONIZING || expired(now)) return false;
    String message = TIMESTAMP.matcher(line.trim()).replaceFirst("");
    long grace;
    Stage next;
    if (message.startsWith("Creating new plan cache")) {
      next = Stage.TENSORRT_PLAN;
      grace = preparationNanos;
    } else if (message.startsWith("Performing autotuning")
        || message.startsWith("Started OpenCL SGEMM")) {
      next = Stage.GPU_TUNING;
      grace = preparationNanos;
    } else if (!message.startsWith("Tuning dummy thread numeric total")
        && (message.startsWith("Beginning GPU tuning")
            || message.startsWith("Tuning ")
            || TESTING_CONFIGS.matcher(message).matches())) {
      next = stage == Stage.TENSORRT_PLAN ? Stage.TENSORRT_PLAN : Stage.GPU_TUNING;
      grace = Math.min(preparationNanos, TimeUnit.MILLISECONDS.toNanos(TUNING_STEP_MILLIS));
    } else {
      return false;
    }
    long elapsed = elapsedNanos(now);
    budgetNanos = Math.max(budgetNanos, elapsed + Math.min(grace, maximumNanos - elapsed));
    stage = next;
    return true;
  }

  /** Name/post-command readiness does not imply the manager's final board/selection commit. */
  synchronized boolean loaded(long now) {
    if (expired(now)) return false;
    stage = Stage.SYNCHRONIZING;
    return true;
  }

  synchronized boolean expired(long now) {
    if (stage == Stage.RETIRED || stage == Stage.EXPIRED) return true;
    if (elapsedNanos(now) >= budgetNanos) {
      stage = Stage.EXPIRED;
      return true;
    }
    return false;
  }

  synchronized void retire() {
    stage = Stage.RETIRED;
  }

  synchronized long deadlineNanos() {
    return startedNanos + budgetNanos;
  }

  synchronized long remainingNanos(long now) {
    return expired(now) ? 0L : budgetNanos - elapsedNanos(now);
  }

  synchronized Snapshot snapshot(long now) {
    long remaining = Math.max(0L, budgetNanos - elapsedNanos(now));
    Stage displayed =
        stage != Stage.SYNCHRONIZING && stage != Stage.RETIRED && remaining == 0L
            ? Stage.EXPIRED : stage;
    return new Snapshot(
        displayed,
        TimeUnit.NANOSECONDS.toMillis(elapsedNanos(now)),
        TimeUnit.NANOSECONDS.toMillis(remaining));
  }

  private long elapsedNanos(long now) {
    // Subtraction also works across System.nanoTime's signed wrap, within this bounded attempt.
    return Math.max(0L, now - startedNanos);
  }
}
