package featurecat.lizzie.gui;

import com.sun.management.ThreadMXBean;
import featurecat.lizzie.Lizzie;
import java.awt.Window;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Manual production-window probe, not a timing test. Run with the test classpath and an owned
 * desktop: {@code EditUndoPerformanceProbe OUTPUT_DIRECTORY [SAMPLES [WARMUPS]]}. Uses the SGF
 * probe's deterministic fixtures and semantic oracle. Measures synchronous EDT work, excluding
 * repaint, background move-list refresh and native engine synchronization (the probe is offline).
 *
 * <p>Each timed row also records what else happened inside the timed region, because for actions
 * that allocate tens of MiB the wall time is dominated by whether a stop-the-world collection
 * lands there: {@code gcCount}/{@code gcMs} (summed over all garbage-collector beans; millisecond
 * resolution), {@code processCpuMs} (all threads), {@code edtBlockedMs}/{@code edtBlockedCount}/
 * {@code edtWaitedMs} (EDT lock contention and waiting) and {@code heapUsedBeforeBytes}.
 *
 * <p>System properties:
 *
 * <ul>
 *   <li>{@code -Dprobe.fixture=NAME} runs only the fixture with that name.
 *   <li>{@code -Dprobe.gcBefore=true} requests {@code System.gc()} on the EDT immediately before
 *       every timed action (outside the timed region), to separate action cost from collection
 *       pauses. Off by default.
 * </ul>
 */
public final class EditUndoPerformanceProbe {
  private static final ThreadMXBean THREADS =
      (ThreadMXBean) ManagementFactory.getThreadMXBean();
  private static final com.sun.management.OperatingSystemMXBean OS =
      (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
  private static final String FIXTURE = System.getProperty("probe.fixture");
  private static final boolean GC_BEFORE = Boolean.getBoolean("probe.gcBefore");

  public static void main(String[] args) throws Exception {
    try {
      run(args);
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void run(String[] args) throws Exception {
    Path output = Path.of(args[0]).toAbsolutePath();
    int samples = args.length > 1 ? Integer.parseInt(args[1]) : 10;
    int warmups = args.length > 2 ? Integer.parseInt(args[2]) : 5;
    Files.createDirectories(output);
    Path work = Files.createDirectory(output.resolve("application-work"));
    Files.writeString(work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},"
            + "\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,"
            + "\"play-sound\":false,\"use-language\":3}}");
    System.setProperty("lizzie.work.dir", work.toString());
    JSONObject summary = new JSONObject()
        .put("started", Instant.now().toString())
        .put("java", System.getProperty("java.runtime.version"))
        .put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        .put("maxHeapBytes", Runtime.getRuntime().maxMemory())
        .put("samples", samples).put("warmupsExcluded", warmups)
        .put("fixtureFilter", FIXTURE == null ? JSONObject.NULL : FIXTURE)
        .put("gcBeforeEachAction", GC_BEFORE)
        .put("measurement", "synchronous production action on EDT; offline; warm JVM; "
            + "excludes repaint, deferred move metadata and native engine I/O");
    JSONArray fixtures = new JSONArray();
    JSONArray observations = new JSONArray();
    JSONArray retainedUndo = new JSONArray();
    summary.put("fixtures", fixtures).put("observations", observations)
        .put("retainedUndoHeap", retainedUndo)
        .put("heapMeasurement", "Separate untimed passes: used heap after full GC with pending "
            + "undo minus used heap after discarding undo; includes auxiliary edit lists");
    THREADS.setThreadContentionMonitoringEnabled(true);
    Lizzie.main(new String[0]);
    onEdt(() -> {
      for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
      Lizzie.setPrimaryEngine(null);
      Lizzie.frame.setBounds(60, 60, 1180, 820);
      Lizzie.config.loadSgfLast = false;
      Lizzie.config.appendWinrateToComment = false;
      Lizzie.config.limitMaxSuggestion = 0;
      Lizzie.config.limitBranchLength = 0;
      return null;
    });
    for (var fixture : SgfPerformanceProbe.fixtures(output)) {
      if (FIXTURE != null && !fixture.name().equals(FIXTURE)) continue;
      fixtures.put(fixture.evidence());
      for (int sample = -warmups; sample < samples; sample++) {
        open(fixture.path());
        String expected = onEdt(() -> SgfPerformanceProbe.validate(fixture));
        observe(observations, fixture.name(), sample, "delete", () ->
            Lizzie.board.deleteMoveNoHintAfter());
        observe(observations, fixture.name(), sample, "undo-delete", () ->
            Lizzie.board.cleanedit());
        verify(fixture, expected);
        observe(observations, fixture.name(), sample, "redo-delete", () ->
            Lizzie.board.reedit());

        open(fixture.path());
        observe(observations, fixture.name(), sample, "insert", () ->
            Lizzie.frame.insertMove(new int[] {18, 18}, true));
        observe(observations, fixture.name(), sample, "undo-insert", () ->
            Lizzie.board.cleanedit());
        verify(fixture, expected);

        open(fixture.path());
        observe(observations, fixture.name(), sample, "trial-enter", () ->
            Lizzie.frame.tryPlay(false));
        onEdt(() -> {
          if (!Lizzie.frame.isTrying) throw new AssertionError("Trial did not start");
          Lizzie.board.getHistory().place(18, 18, featurecat.lizzie.rules.Stone.BLACK);
          return null;
        });
        observe(observations, fixture.name(), sample, "trial-return", () ->
            Lizzie.frame.tryPlay(false));
        verify(fixture, expected);
        Files.writeString(output.resolve("summary.json"), summary.toString(2));
        System.out.println(fixture.name() + " sample=" + sample + " PASS");
      }
      for (int repeat = 0; repeat < 3; repeat++) {
        open(fixture.path());
        onEdt(() -> { Lizzie.board.deleteMoveNoHintAfter(); return null; });
        settle();
        long withUndo = usedHeapAfterGc();
        onEdt(() -> { Lizzie.board.cleanedittemp(); return null; });
        long withoutUndo = usedHeapAfterGc();
        retainedUndo.put(new JSONObject().put("fixture", fixture.name()).put("repeat", repeat)
            .put("withUndoBytes", withUndo).put("withoutUndoBytes", withoutUndo)
            .put("differenceBytes", withUndo - withoutUndo));
      }
    }
    if (fixtures.isEmpty()) throw new AssertionError("No fixture named " + FIXTURE);
    summary.put("finished", Instant.now().toString()).put("result", "PASS");
    Files.writeString(output.resolve("summary.json"), summary.toString(2));
  }

  private static void open(Path file) throws Exception {
    if (!onEdt(() -> Lizzie.frame.openFileAsync(file.toFile(), false, false))
        .get(90, TimeUnit.SECONDS)) throw new AssertionError("Open failed: " + file);
    settle();
  }

  private static void settle() throws Exception {
    CompletableFuture<Void> completed = new CompletableFuture<>();
    onEdt(() -> { Lizzie.board.setMovelistAll(() -> completed.complete(null)); return null; });
    completed.get(30, TimeUnit.SECONDS);
    onEdt(() -> null);
  }

  private static void verify(SgfPerformanceProbe.Fixture fixture, String expected) throws Exception {
    String actual = onEdt(() -> SgfPerformanceProbe.validate(fixture));
    if (!expected.equals(actual)) throw new AssertionError("Restore changed " + fixture.name());
    onEdt(() -> {
      if (Lizzie.board.getHistory().getCurrentHistoryNode() != Lizzie.board.getHistory().getStart())
        throw new AssertionError("Restore changed cursor");
      return null;
    });
  }

  private static void observe(JSONArray rows, String fixture, int sample, String operation,
      Runnable action) throws Exception {
    JSONObject row = onEdt(() -> {
      long id = Thread.currentThread().getId();
      if (GC_BEFORE) System.gc();
      var threadBefore = THREADS.getThreadInfo(id);
      long heapUsedBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
      long[] collectionsBefore = collections();
      long processCpu = OS.getProcessCpuTime();
      long allocation = THREADS.getThreadAllocatedBytes(id);
      long cpu = THREADS.getCurrentThreadCpuTime();
      long start = System.nanoTime();
      action.run();
      long elapsed = System.nanoTime() - start;
      long edtCpu = THREADS.getCurrentThreadCpuTime() - cpu;
      long allocated = THREADS.getThreadAllocatedBytes(id) - allocation;
      long processCpuElapsed = OS.getProcessCpuTime() - processCpu;
      long[] collectionsAfter = collections();
      var threadAfter = THREADS.getThreadInfo(id);
      return new JSONObject().put("fixture", fixture).put("sample", sample)
          .put("warmup", sample < 0).put("operation", operation)
          .put("wallMs", elapsed / 1_000_000.0)
          .put("edtCpuMs", edtCpu / 1_000_000.0)
          .put("edtAllocatedBytes", allocated)
          .put("gcCount", collectionsAfter[0] - collectionsBefore[0])
          .put("gcMs", collectionsAfter[1] - collectionsBefore[1])
          .put("processCpuMs", processCpuElapsed / 1_000_000.0)
          .put("edtBlockedMs", threadAfter.getBlockedTime() - threadBefore.getBlockedTime())
          .put("edtBlockedCount", threadAfter.getBlockedCount() - threadBefore.getBlockedCount())
          .put("edtWaitedMs", threadAfter.getWaitedTime() - threadBefore.getWaitedTime())
          .put("heapUsedBeforeBytes", heapUsedBefore);
    });
    rows.put(row);
    settle();
  }

  /** Total collection count and accumulated collection milliseconds over all collectors. */
  private static long[] collections() {
    long count = 0;
    long millis = 0;
    for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
      count += Math.max(0, collector.getCollectionCount());
      millis += Math.max(0, collector.getCollectionTime());
    }
    return new long[] {count, millis};
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeAndWait(task);
    return task.get();
  }

  private static long usedHeapAfterGc() throws Exception {
    onEdt(() -> null);
    System.gc();
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }
}
