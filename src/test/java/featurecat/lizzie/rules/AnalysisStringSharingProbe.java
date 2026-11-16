package featurecat.lizzie.rules;

import com.sun.management.ThreadMXBean;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EditUndoPerformanceProbe;
import featurecat.lizzie.gui.SgfPerformanceProbe;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Manual headless comparison of SGF read/parse and edit costs, not a timing test or GUI benchmark.
 * Uses the existing deterministic fixtures, semantic oracle and rules harness. Arguments:
 * {@code OUTPUT_DIRECTORY [SAMPLES [WARMUPS]]}; optional {@code -Dprobe.fixture=NAME}.
 * UI/engine callbacks are stubbed. Settlement and semantic checks are outside timed operations.
 */
public final class AnalysisStringSharingProbe {
  private static final ThreadMXBean THREADS =
      (ThreadMXBean) ManagementFactory.getThreadMXBean();
  private static final com.sun.management.OperatingSystemMXBean OS =
      (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

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
    Files.createDirectory(output);
    int samples = args.length > 1 ? Integer.parseInt(args[1]) : 10;
    int warmups = args.length > 2 ? Integer.parseInt(args[2]) : 5;
    String filter = System.getProperty("probe.fixture");
    JSONArray fixtures = new JSONArray();
    JSONArray observations = new JSONArray();
    JSONObject summary = new JSONObject()
        .put("started", Instant.now().toString())
        .put("java", System.getProperty("java.runtime.version"))
        .put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        .put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments())
        .put("maxHeapBytes", Runtime.getRuntime().maxMemory())
        .put("samples", samples).put("warmupsExcluded", warmups)
        .put("fixtureFilter", filter == null ? JSONObject.NULL : filter)
        .put("measurement", "Headless rules harness; synchronous calling-thread work. Read rows "
            + "include UTF-8 Files.readString, SGFParser.parseSgf and Board.setHistory. Edit rows "
            + "use production delete/undo/redo/insertion and Board trial operations. UI/engine "
            + "callbacks are stubbed; settlement/validation and GUI file-open lifecycle are excluded.")
        .put("fixtures", fixtures).put("observations", observations);
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(19)) {
      Lizzie.config.newMoveNumberInBranch = true;
      Lizzie.config.limitMaxSuggestion = 0;
      Lizzie.config.limitBranchLength = 0;
      Board board = Lizzie.board;
      for (var fixture : SgfPerformanceProbe.fixtures(output)) {
        if (filter != null && !filter.equals(fixture.name())) continue;
        fixtures.put(fixture.evidence());
        for (int sample = -warmups; sample < samples; sample++) {
          read(observations, fixture, sample, "read-delete");
          String expected = SgfPerformanceProbe.validate(fixture);
          observe(observations, fixture, sample, "delete", () -> {
            board.deleteMoveNoHintAfter(); return null;
          });
          observe(observations, fixture, sample, "undo-delete", () -> {
            board.cleanedit(); return null;
          });
          verify(fixture, expected);
          observe(observations, fixture, sample, "redo-delete", () -> {
            board.reedit(); return null;
          });

          read(observations, fixture, sample, "read-insert");
          observe(observations, fixture, sample, "insert", () -> {
            Lizzie.frame.insertMove(new int[] {18, 18}, true); return null;
          });
          EditUndoPerformanceProbe.verifyInsertion(fixture);
          observe(observations, fixture, sample, "undo-insert", () -> {
            board.cleanedit(); return null;
          });
          verify(fixture, expected);

          read(observations, fixture, sample, "read-trial");
          Board.GameStateSnapshot trial = observe(
              observations, fixture, sample, "trial-begin", board::beginTrial);
          board.getHistory().place(18, 18, Stone.BLACK);
          observe(observations, fixture, sample, "trial-restore", () -> {
            board.restoreGameState(trial);
            board.cleanedittemp();
            return null;
          });
          verify(fixture, expected);
          Files.writeString(output.resolve("summary.json"), summary.toString(2) + "\n");
          System.out.println(fixture.name() + " sample=" + sample + " PASS");
        }
      }
    }
    if (fixtures.isEmpty()) throw new AssertionError("No fixture named " + filter);
    summary.put("finished", Instant.now().toString()).put("result", "PASS");
    Files.writeString(output.resolve("summary.json"), summary.toString(2) + "\n");
  }

  private static void read(JSONArray rows, SgfPerformanceProbe.Fixture fixture, int sample,
      String operation) throws Exception {
    Lizzie.board.cleanedittemp();
    observe(rows, fixture, sample, operation, () -> {
      BoardHistoryList history = SGFParser.parseSgf(Files.readString(fixture.path()), true);
      if (history == null) throw new AssertionError("Parse failed: " + fixture.name());
      history.setHead(history.getStart());
      Lizzie.board.setHistory(history);
      return null;
    });
  }

  private static void verify(SgfPerformanceProbe.Fixture fixture, String expected) throws Exception {
    if (!expected.equals(SgfPerformanceProbe.validate(fixture))) {
      throw new AssertionError("Restore changed " + fixture.name());
    }
    if (Lizzie.board.getHistory().getCurrentHistoryNode() != Lizzie.board.getHistory().getStart()) {
      throw new AssertionError("Restore changed cursor");
    }
  }

  private static <T> T observe(JSONArray rows, SgfPerformanceProbe.Fixture fixture, int sample,
      String operation, Callable<T> action) throws Exception {
    long id = Thread.currentThread().getId();
    long heapBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    long[] gcBefore = collections();
    long processCpu = OS.getProcessCpuTime();
    long allocation = THREADS.getThreadAllocatedBytes(id);
    long cpu = THREADS.getCurrentThreadCpuTime();
    long start = System.nanoTime();
    T result = action.call();
    long elapsed = System.nanoTime() - start;
    long cpuElapsed = THREADS.getCurrentThreadCpuTime() - cpu;
    long allocated = THREADS.getThreadAllocatedBytes(id) - allocation;
    long processCpuElapsed = OS.getProcessCpuTime() - processCpu;
    long[] gcAfter = collections();
    rows.put(new JSONObject().put("fixture", fixture.name()).put("sample", sample)
        .put("warmup", sample < 0).put("operation", operation)
        .put("wallMs", elapsed / 1_000_000.0)
        .put("callingThreadCpuMs", cpuElapsed / 1_000_000.0)
        .put("callingThreadAllocatedBytes", allocated)
        .put("processCpuMs", processCpuElapsed / 1_000_000.0)
        .put("gcCount", gcAfter[0] - gcBefore[0]).put("gcMs", gcAfter[1] - gcBefore[1])
        .put("heapUsedBeforeBytes", heapBefore));
    CountDownLatch settled = new CountDownLatch(1);
    Lizzie.board.setMovelistAll(settled::countDown);
    if (!settled.await(30, TimeUnit.SECONDS)) throw new AssertionError("Move list did not settle");
    return result;
  }

  private static long[] collections() {
    long count = 0;
    long millis = 0;
    for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
      count += Math.max(0, collector.getCollectionCount());
      millis += Math.max(0, collector.getCollectionTime());
    }
    return new long[] {count, millis};
  }
}
