import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.rules.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import jdk.jfr.Recording;
import jdk.jfr.consumer.*;
import org.json.*;

/** Same probe source/class runs against both revisions; engine responses use the real parser. */
public final class MovelistRefreshProbe {
  private static final String FIXTURE = "featurecat.lizzie.analysis.AnalysisEngineRequestTest";
  private static final java.lang.management.ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
  private static final com.sun.management.ThreadMXBean MEMORY =
      (com.sun.management.ThreadMXBean) CPU;
  private static final Method ALLOCATE = method(FIXTURE, "allocate", Class.class);
  private static final Method MOVE =
      method(
          FIXTURE, "moveNode", Stone[].class, int[].class, Stone.class, boolean.class, int.class);
  private static final Method INSTALL =
      method(FIXTURE, "installCompleteAnalysis", BoardData.class, int.class, boolean.class);
  private static final Method RESPONSE =
      method(FIXTURE, "analysisResult", int.class, int.class, double.class);

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    CPU.setThreadCpuTimeEnabled(true);
    MEMORY.setThreadAllocatedMemoryEnabled(true);
    JSONArray rows = new JSONArray();
    try (AutoCloseable environment =
        (AutoCloseable) method(FIXTURE + "$TestEnvironment", "open").invoke(null)) {
      for (int sideDepth : new int[] {0, 20}) {
        for (String pace : List.of("burst", "paced-2ms", "navigation-burst")) {
          for (int repetition = -2; repetition < 5; repetition++) {
            JSONObject row = run(output, sideDepth, pace, repetition);
            rows.put(row);
            Files.writeString(output.resolve("measurements.json"), rows.toString(2));
          }
        }
      }
    }
    System.out.println("Recorded " + rows.length() + " runs in " + output);
  }

  private static JSONObject run(Path output, int sideDepth, String pace, int repetition)
      throws Exception {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(3, 3));
    List<BoardHistoryNode> mainline = new ArrayList<>();
    mainline.add(history.getStart());
    INSTALL.invoke(null, history.getStart().getData(), 100, false);
    for (int number = 1; number <= 200; number++) {
      mainline.add(mainline.get(number - 1).addAtLast(data(number, 0)));
    }
    for (int number = 0; number < 200; number++) {
      BoardHistoryNode node = mainline.get(number);
      for (int depth = 1; depth <= sideDepth; depth++) {
        node = node.addAtLast(data(number + depth, 1));
      }
    }
    MeasuringBoard board = (MeasuringBoard) ALLOCATE.invoke(null, MeasuringBoard.class);
    board.stats = new ConcurrentHashMap<>();
    board.failure = new AtomicReference<>();
    board.setHistory(history);
    Lizzie.board = board;
    AnalysisEngine engine =
        (AnalysisEngine) method(FIXTURE + "$TrackingAnalysisEngine", "create").invoke(null);
    engine.setKeepAliveAfterCurrentRequest(true);
    if (engine.startWholeGameRequest(mainline, 200, false) != mainline.size()) {
      throw new AssertionError("all main-line positions must be requested");
    }
    List<String> responses = new ArrayList<>();
    for (int id = 1; id <= mainline.size(); id++) {
      responses.add((String) RESPONSE.invoke(null, id, 200, 40.0 + (id % 30)));
    }
    // Clear request/setup UI work before timing. No engine process or disk access is timed.
    SwingUtilities.invokeAndWait(() -> {});
    Path jfr = output.resolve(sideDepth + "-" + pace + "-" + repetition + ".jfr");
    long start;
    long submitted;
    long settled;
    try (Recording recording = new Recording()) {
      recording.enable("jdk.ThreadStart").withoutStackTrace();
      recording.enable("jdk.ThreadEnd");
      recording.start();
      if (pace.equals("navigation-burst")) {
        Field navigation = Board.class.getDeclaredField("lastMoveNavigationAt");
        navigation.setAccessible(true);
        navigation.setLong(board, System.currentTimeMillis());
      }
      start = System.nanoTime();
      for (String response : responses) {
        engine.parseResult(response);
        if (pace.equals("paced-2ms")) TimeUnit.MILLISECONDS.sleep(2);
      }
      submitted = System.nanoTime();
      CountDownLatch complete = new CountDownLatch(1);
      board.setMovelistAll(complete::countDown);
      if (!complete.await(20, TimeUnit.SECONDS))
        throw new AssertionError("metadata did not settle");
      settled = System.nanoTime();
      // Include superseded baseline workers, even ones cancelled before their first node update.
      for (Thread thread : Thread.getAllStackTraces().keySet()) {
        if (thread.getName().equals("lizzie-movelist-refresh")) {
          thread.join(20_000);
          if (thread.isAlive()) throw new AssertionError("worker did not exit");
        }
      }
      if (board.failure.get() != null) throw new AssertionError(board.failure.get());
      recording.stop();
      recording.dump(jfr);
    }
    List<RecordedEvent> events = RecordingFile.readAllEvents(jfr);
    events.sort(Comparator.comparing(RecordedEvent::getStartTime));
    int started = 0;
    Set<Long> live = new HashSet<>();
    Set<Long> starts = new HashSet<>();
    int peak = 0;
    for (RecordedEvent event : events) {
      if (!event.getThread("thread").getJavaName().equals("lizzie-movelist-refresh")) continue;
      long id = event.getThread("thread").getJavaThreadId();
      if (event.getEventType().getName().equals("jdk.ThreadStart")) {
        if (starts.add(id)) started++;
        live.add(id);
        peak = Math.max(peak, live.size());
      } else live.remove(id);
    }
    if (!live.isEmpty()) throw new AssertionError("unbalanced worker lifecycle events");
    String measured = digest(history.getStart());
    Deque<BoardHistoryNode> pending = new ArrayDeque<>();
    pending.push(history.getStart());
    while (!pending.isEmpty()) {
      BoardHistoryNode node = pending.pop();
      board.updateMovelist(node);
      for (int index = node.numberOfChildren() - 1; index >= 0; index--) {
        pending.push(node.getVariations().get(index));
      }
    }
    if (!measured.equals(digest(history.getStart()))) {
      throw new AssertionError("asynchronous metadata differs from a settled full traversal");
    }
    SwingUtilities.invokeAndWait(() -> {});
    long visits = board.stats.values().stream().mapToLong(stats -> stats[0]).sum();
    long cpu = board.stats.values().stream().mapToLong(stats -> stats[1]).sum();
    long bytes = board.stats.values().stream().mapToLong(stats -> stats[2]).sum();
    return new JSONObject()
        .put("nodes", 201 + 200 * sideDepth)
        .put("pace", pace)
        .put("repetition", repetition)
        .put("retained", repetition >= 0)
        .put("responses", responses.size())
        .put("workersStarted", started)
        .put("peakLiveWorkers", peak)
        .put("nodeVisits", visits)
        .put("sampledWorkerCpuMs", cpu / 1_000_000.0)
        .put("sampledWorkerAllocatedBytes", bytes)
        .put("totalWallMs", (settled - start) / 1_000_000.0)
        .put("settleAfterResponsesMs", (settled - submitted) / 1_000_000.0)
        .put("metadataSha256", measured)
        .put("matchesSettledTraversal", true);
  }

  private static BoardData data(int number, int branch) throws Exception {
    Stone[] stones = new Stone[9];
    Arrays.fill(stones, Stone.EMPTY);
    int[] point = {(number + branch) % 3, (number / 3 + branch) % 3};
    Stone color = number % 2 == 1 ? Stone.BLACK : Stone.WHITE;
    stones[Board.getIndex(point[0], point[1])] = color;
    BoardData data =
        (BoardData) MOVE.invoke(null, stones, point, color, color == Stone.WHITE, number);
    INSTALL.invoke(null, data, 100, false);
    return data;
  }

  private static String digest(BoardHistoryNode root) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    List<Field> fields = new ArrayList<>(List.of(NodeInfo.class.getFields()));
    fields.sort(Comparator.comparing(Field::getName));
    Deque<BoardHistoryNode> pending = new ArrayDeque<>();
    pending.push(root);
    while (!pending.isEmpty()) {
      BoardHistoryNode node = pending.pop();
      StringBuilder text = new StringBuilder().append(node.getData().lastMoveMatchCandidteNo);
      for (NodeInfo info :
          List.of(node.nodeInfo, node.nodeInfoMain, node.nodeInfo2, node.nodeInfoMain2)) {
        for (Field field : fields) {
          Object value = field.get(info);
          text.append('|').append(value instanceof int[] ? Arrays.toString((int[]) value) : value);
        }
      }
      digest.update(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      for (int index = node.numberOfChildren() - 1; index >= 0; index--) {
        pending.push(node.getVariations().get(index));
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static Method method(String owner, String name, Class<?>... arguments) {
    try {
      Method method = Class.forName(owner).getDeclaredMethod(name, arguments);
      method.setAccessible(true);
      return method;
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  public static final class MeasuringBoard extends Board {
    private Map<Thread, long[]> stats;
    private AtomicReference<Throwable> failure;

    @Override
    public void updateMovelist(BoardHistoryNode node) {
      Thread thread = Thread.currentThread();
      if (!thread.getName().equals("lizzie-movelist-refresh")) {
        super.updateMovelist(node);
        return;
      }
      long[] sample =
          stats.computeIfAbsent(
              thread,
              key -> {
                key.setUncaughtExceptionHandler(
                    (owner, error) -> failure.compareAndSet(null, error));
                return new long[3];
              });
      super.updateMovelist(node);
      sample[0]++;
      // Cumulative counters through each worker's last visited node. Exit/callback CPU is excluded.
      sample[1] = CPU.getCurrentThreadCpuTime();
      sample[2] = MEMORY.getThreadAllocatedBytes(thread.getId());
    }
  }
}
