package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Single-owner preparation queue shared by explicit assessment and silent background prefetch. */
public final class HumanSlStrengthPreparation implements AutoCloseable {
  public static final String BACKGROUND_KEY = "strength-humansl-background-enabled";

  interface Worker extends AutoCloseable {
    JSONObject prepare(
        JSONObject game, AtomicBoolean cancelled, java.util.function.IntConsumer progress)
        throws Exception;

    void cancel();

    @Override
    void close();
  }

  @FunctionalInterface
  interface Factory {
    Worker open() throws Exception;
  }

  public record Status(String key, int completed, int total, long revision) {}

  public record Assets(Path engine, Path human) {}

  private record Prepared(BoardHistoryNode root, JSONObject game, JSONObject policies) {}

  public final class Task {
    private final BoardHistoryNode root;
    private final JSONObject game;
    private final boolean manual;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private volatile Worker worker;

    private Task(BoardHistoryNode root, JSONObject game, boolean manual) {
      this.root = root;
      this.game = new JSONObject(game.toString());
      this.manual = manual;
    }

    public CompletableFuture<Void> completion() {
      return completion;
    }

    public void cancel() {
      cancelTask(this, true);
    }
  }

  private final Factory factory;
  private final ExecutorService queue =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "strength40-preparation");
            thread.setDaemon(true);
            return thread;
          });
  private Task active;
  private BoardHistoryNode root;
  private String gameKey = "", attemptedKey = "";
  private Prepared prepared;
  private boolean closed;
  private final CompletableFuture<Void> termination = new CompletableFuture<>();
  private long revision;
  private volatile Status status = new Status("HumanSL40.backgroundOff", 0, 0, 0);

  public HumanSlStrengthPreparation() {
    this(HumanSlStrengthPreparation::openLocalWorker);
  }

  HumanSlStrengthPreparation(Factory factory) {
    this.factory = factory;
  }

  public static boolean backgroundEnabled() {
    return Lizzie.config != null
        && Lizzie.config.uiConfig != null
        && Lizzie.config.uiConfig.optBoolean(BACKGROUND_KEY, false);
  }

  public static void saveBackgroundEnabled(boolean enabled) throws IOException {
    JSONObject ui = new JSONObject(Lizzie.config.uiConfig.toString());
    ui.put(BACKGROUND_KEY, enabled);
    Lizzie.config.saveConfigSections(ui, Lizzie.config.leelazConfig);
  }

  public Status status() {
    return status;
  }

  public synchronized String contextKey() {
    return gameKey;
  }

  public synchronized boolean hasWork() {
    return active != null || prepared != null;
  }

  /** Called on the UI heartbeat; foreground completion can finish the already-started batch. */
  public synchronized void observe(
      HumanSlStrengthReuseSession.Capture snapshot,
      boolean enabled,
      boolean foregroundAnalyzing,
      boolean userPaused) {
    if (closed) return;
    String key = HumanSlStrengthReuseSession.canonical(snapshot.game());
    boolean changed = root != snapshot.start() || !key.equals(gameKey);
    if (changed) {
      cancelActive();
      root = snapshot.start();
      gameKey = key;
      attemptedKey = "";
      prepared = null;
      update("HumanSL40.backgroundIdle", 0, snapshot.game().getJSONArray("moves").length());
    }
    if (!enabled || userPaused) {
      if (active != null && !active.manual) cancelActive();
      if (active == null)
        update(
            enabled ? "HumanSL40.backgroundPaused" : "HumanSL40.backgroundOff", 0, status.total());
      return;
    }
    if (active == null && prepared != null)
      update("HumanSL40.backgroundReady", status.total(), status.total());
    else if (active == null
        && (status.key().equals("HumanSL40.backgroundOff")
            || status.key().equals("HumanSL40.backgroundPaused")))
      update("HumanSL40.backgroundIdle", status.completed(), status.total());
    if (active == null && prepared == null && foregroundAnalyzing && !attemptedKey.equals(gameKey))
      start(snapshot, false);
  }

  public synchronized Task manual(HumanSlStrengthReuseSession.Capture snapshot) {
    if (closed) throw new IllegalStateException("Preparation service is closed");
    cancelActive();
    root = snapshot.start();
    gameKey = HumanSlStrengthReuseSession.canonical(snapshot.game());
    return start(snapshot, true);
  }

  public synchronized void resetBackground() {
    attemptedKey = "";
    if (active != null && !active.manual) cancelActive();
  }

  public synchronized void invalidate() {
    cancelActive();
    root = null;
    gameKey = "";
    attemptedKey = "";
    prepared = null;
    update("HumanSL40.backgroundIdle", 0, 0);
  }

  private Task start(HumanSlStrengthReuseSession.Capture snapshot, boolean manual) {
    Task task = new Task(snapshot.start(), snapshot.game(), manual);
    active = task;
    attemptedKey = gameKey;
    update("HumanSL40.preparing", 0, task.game.getJSONArray("moves").length());
    queue.execute(() -> run(task));
    return task;
  }

  private void run(Task task) {
    try {
      if (task.cancelled.get()) throw new CancellationException();
      JSONObject policies;
      try (Worker worker = factory.open()) {
        task.worker = worker;
        if (task.cancelled.get()) throw new CancellationException();
        policies =
            worker.prepare(
                task.game,
                task.cancelled,
                n -> {
                  synchronized (this) {
                    if (active == task && !task.cancelled.get())
                      update("HumanSL40.progress", n, status.total());
                  }
                });
      } finally {
        task.worker = null;
      }
      synchronized (this) {
        if (active != task || task.cancelled.get()) throw new CancellationException();
        prepared = new Prepared(task.root, task.game, policies);
        active = null;
        update("HumanSL40.backgroundReady", status.total(), status.total());
      }
      task.completion.complete(null);
    } catch (Exception | Error error) {
      synchronized (this) {
        if (active == task) {
          active = null;
          String message = error.getMessage();
          update(
              task.cancelled.get()
                  ? "HumanSL40.cancelled"
                  : message != null && message.startsWith("HumanSL40.")
                      ? message
                      : "HumanSL40.failed",
              status.completed(),
              status.total());
        }
      }
      task.completion.completeExceptionally(error);
      if (error instanceof Error fatal) throw fatal;
    }
  }

  private synchronized void cancelTask(Task task, boolean suppressAutoRestart) {
    task.cancelled.set(true);
    Worker worker = task.worker;
    if (worker != null) {
      Thread cancellation = new Thread(worker::cancel, "strength40-cancel");
      cancellation.setDaemon(true);
      cancellation.start();
    }
    if (active == task) {
      active = null;
      if (suppressAutoRestart) attemptedKey = gameKey;
      else attemptedKey = "";
      update("HumanSL40.cancelled", status.completed(), status.total());
    }
  }

  private void cancelActive() {
    if (active != null) cancelTask(active, false);
  }

  private void update(String key, int done, int total) {
    if (status.key().equals(key) && status.completed() == done && status.total() == total) return;
    status = new Status(key, done, total, ++revision);
  }

  public synchronized PlayerStrengthEstimator.Report readyReport(
      HumanSlStrengthReuseSession.Capture capture) {
    if (prepared == null || prepared.root() != capture.start()) return null;
    JSONObject prefix = new JSONObject(prepared.game().toString());
    var moves = new org.json.JSONArray();
    int count = capture.game().getJSONArray("moves").length();
    if (count > prefix.getJSONArray("moves").length()) return null;
    for (int i = 0; i < count; i++) moves.put(prefix.getJSONArray("moves").getJSONArray(i));
    prefix.put("moves", moves);
    if (!HumanSlStrengthReuseSession.canonical(prefix)
        .equals(HumanSlStrengthReuseSession.canonical(capture.game()))) return null;
    return HumanSlStrengthReuseSession.report(capture, prepared.policies());
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    cancelActive();
    prepared = null;
    queue.execute(() -> termination.complete(null));
    queue.shutdown();
  }

  public CompletableFuture<Void> termination() {
    return termination;
  }

  public static Assets discoverAssets() {
    var setup = KataGoAutoSetupHelper.inspectLocalSetup();
    Path engine = setup.enginePath;
    if (engine == null || !Files.isRegularFile(engine)) {
      List<String> parts = Utils.splitCommand(Lizzie.config.analysisEngineCommand);
      if (!parts.isEmpty() && !parts.get(0).equals("ssh")) {
        Path candidate = KataGoRuntimeHelper.resolveCommandExecutable(parts);
        if (candidate != null && Files.isRegularFile(candidate)) engine = candidate;
      }
    }
    var model = KataGoAutoSetupHelper.inspectHumanSlModel(setup);
    Path human = model.isInstalled() ? model.modelPath : null;
    return new Assets(engine, human);
  }

  private static Worker openLocalWorker() throws Exception {
    Assets assets = discoverAssets();
    if (assets.engine() == null || !Files.isRegularFile(assets.engine()))
      throw new IOException("HumanSL40.needEngine");
    if (assets.human() == null) throw new IOException("HumanSL40.downloadRequired");
    if (!HumanSlStrengthReuseSession.fileHash(assets.human()).equals(HumanSlStrength40.HUMAN_SHA))
      throw new IOException("HumanSL40.invalidModel");
    Path cache =
        Path.of(System.getProperty("user.home"), ".lizzie", "strength-humansl40-policy-cache");
    String command =
        HumanSlStrengthReuseSession.policyCommand(assets.engine(), assets.human(), cache);
    HumanSlAnalysisRunner runner =
        new HumanSlAnalysisRunner(
            command, assets.human(), HumanSlAnalysisRunner.LaunchProfile.POLICY_SUPPLEMENT);
    var launch = runner.preparePolicyLaunch();
    if (launch == null) throw new IOException(runner.getUnavailableReason());
    String fingerprint = HumanSlStrengthReuseSession.fingerprint(launch);
    return new Worker() {
      public JSONObject prepare(
          JSONObject game, AtomicBoolean cancelled, java.util.function.IntConsumer progress)
          throws Exception {
        return HumanSlStrengthReuseSession.prefetchPolicies(
            game,
            q -> runner.request(q, Duration.ofSeconds(180)),
            cache,
            fingerprint,
            cancelled::get,
            progress);
      }

      public void cancel() {
        runner.close();
      }

      public void close() {
        runner.close();
      }
    };
  }
}
