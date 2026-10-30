package featurecat.lizzie.gui;

import com.sun.management.OperatingSystemMXBean;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.logging.SgfPhase;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.util.AtomicSgfFileWriter;
import featurecat.lizzie.util.EncodingDetector;
import featurecat.lizzie.util.SgfFileReader;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Manual production-window measurement, deliberately not an automatically discovered test.
 *
 * <p>After test-compile, run this main with the test classpath, an operational desktop and an
 * isolated application directory: {@code SgfPerformanceProbe OUTPUT_DIRECTORY [SAMPLES [WARMUPS]]}.
 * Generated fixtures, raw JFRs and summary.json are retained under OUTPUT_DIRECTORY. No timing
 * assertions are made. Use an owned Xvfb/desktop, not the user's installed application session.
 */
public final class SgfPerformanceProbe {
  private static final String PHASE_EVENT = "kg.sgf.Phase";
  private static final String SHORT_COMMENT = "성능 검증: 한국어 日本語 中文 English ] \\ \n끝";
  private static final int CANDIDATES = 32;
  private static final int OWNERSHIP = 19 * 19;
  private static final OperatingSystemMXBean OS =
      (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
  private static long edtThread;

  private SgfPerformanceProbe() {}

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
    require(args.length >= 1, "Supply an evidence output directory");
    require(!GraphicsEnvironment.isHeadless(), "An owned operational desktop is required");
    Path output = Path.of(args[0]).toAbsolutePath();
    int samples = args.length > 1 ? Integer.parseInt(args[1]) : 3;
    int warmups = args.length > 2 ? Integer.parseInt(args[2]) : 1;
    require(samples >= 1 && warmups >= 0, "Invalid sample/warmup count");
    Files.createDirectories(output);
    Path work = Files.createDirectory(output.resolve("application-work"));
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},"
            + "\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,"
            + "\"play-sound\":false,\"use-language\":3}}");
    System.setProperty("lizzie.work.dir", work.toString());
    JSONObject summary =
        new JSONObject()
            .put("started", Instant.now().toString())
            .put("environment", environment())
            .put("samples", samples)
            .put("warmupsExcluded", warmups)
            .put("engine", "offline; native engine synchronization not measured")
            .put("cache", "warm filesystem/JVM samples; no OS cache eviction")
            .put("completion", "open future followed by EDT barrier and !isLoadingFile")
            .put("phaseAccounting", "Nested phases overlap; do not add file-apply to its children")
            .put("cpuAccounting", "Process and EDT CPU are cumulative CPU deltas, not wait time")
            .put(
                "heartbeat",
                "One queued EDT callback at a time, every 20ms; queue delay, not display FPS")
            .put(
                "savePath",
                "captureSave on EDT, AtomicSgfFileWriter off EDT, immediate open after write")
            .put(
                "legacyReader",
                "Reference old detect + InputStreamReader.ready/read character loop only; not old full-app latency");
    Files.writeString(output.resolve("summary.json"), summary.toString(2));
    List<Fixture> fixtures = fixtures(output);
    JSONArray fixtureEvidence = new JSONArray();
    JSONArray observations = new JSONArray();
    summary.put("fixtures", fixtureEvidence).put("observations", observations);
    Lizzie.main(new String[0]);
    onEdt(
        () -> {
          require(Lizzie.frame != null, "Production frame did not initialize");
          edtThread = Thread.currentThread().getId();
          for (Window window : Window.getWindows()) {
            if (window instanceof JDialog) window.dispose();
          }
          Lizzie.setPrimaryEngine(null);
          require(Lizzie.leelaz == null, "Probe must remain offline");
          Lizzie.frame.setBounds(60, 60, 1180, 820);
          Lizzie.config.loadSgfLast = true;
          Lizzie.config.appendWinrateToComment = false;
          Lizzie.config.limitMaxSuggestion = 0;
          Lizzie.config.limitBranchLength = 0;
          return null;
        });

    for (Fixture fixture : fixtures) {
      fixtureEvidence.put(fixture.evidence());
      for (int index = -warmups; index < samples; index++) {
        boolean warmup = index < 0;
        String stem = fixture.name + "-" + (warmup ? "warm" + (-index) : "sample" + index);
        Path save = output.resolve(stem + "-saved.sgf");
        for (String reader : List.of("legacy-reader", "current-reader")) {
          observe(
              output,
              observations,
              fixture,
              stem,
              reader,
              warmup,
              () -> {
                String actual =
                    reader.equals("legacy-reader")
                        ? legacyRead(fixture.path)
                        : SgfFileReader.read(fixture.path);
                require(actual.equals(fixture.text), reader + " did not preserve fixture text");
                return null;
              });
        }
        observe(
            output,
            observations,
            fixture,
            stem,
            "open",
            warmup,
            () -> {
              open(fixture.path);
              return null;
            });
        String before = onEdt(() -> validate(fixture));
        observe(
            output,
            observations,
            fixture,
            stem,
            "save",
            warmup,
            () -> {
              String payload = onEdt(() -> SGFParser.captureSave(Lizzie.board, false));
              try (var phase = new SgfPhase("probe-atomic-write")) {
                AtomicSgfFileWriter.write(save, payload);
              }
              return null;
            });
        observe(
            output,
            observations,
            fixture,
            stem,
            "reopen",
            warmup,
            () -> {
              open(save);
              return null;
            });
        String after = onEdt(() -> validate(fixture));
        require(before.equals(after), "Save/reopen changed semantic tree: " + fixture.name);
        observations
            .getJSONObject(observations.length() - 1)
            .put("semanticSha256", after)
            .put("savedBytes", Files.size(save))
            .put("savedSha256", sha256(Files.readAllBytes(save)));
        summary.put("aggregates", aggregate(observations));
        Files.writeString(output.resolve("summary.json"), summary.toString(2));
        System.out.println(
            stem + " PASS nodes=" + fixture.nodes + " bytes=" + Files.size(fixture.path));
      }
    }
    summary.put("finished", Instant.now().toString()).put("result", "PASS");
    Files.writeString(output.resolve("summary.json"), summary.toString(2));
  }

  private static void open(Path path) throws Exception {
    require(
        onEdt(() -> Lizzie.frame.openFileAsync(path.toFile(), false, false))
            .get(90, TimeUnit.SECONDS),
        "Open rejected: " + path);
    onEdt(
        () -> {
          require(!Lizzie.board.isLoadingFile, "Final navigation was not complete");
          return null;
        });
  }

  private static void observe(
      Path output,
      JSONArray rows,
      Fixture fixture,
      String stem,
      String operation,
      boolean warmup,
      Callable<Void> action)
      throws Exception {
    Path jfr = output.resolve(stem + "-" + operation + ".jfr");
    try (Recording recording = new Recording();
        Heartbeat heartbeat = new Heartbeat()) {
      recording.enable(PHASE_EVENT).withoutThreshold();
      recording.enable("jdk.GarbageCollection").withoutThreshold();
      recording.start();
      heartbeat.start();
      long processCpu = OS.getProcessCpuTime();
      long edtCpu = edtCpu();
      long started = System.nanoTime();
      action.call();
      long wall = System.nanoTime() - started;
      long process = OS.getProcessCpuTime() - processCpu;
      long edt = edtCpu() - edtCpu;
      heartbeat.stop();
      // Drain outside the action interval: a reader-only sample must not include repaint waits.
      // The open action itself already waits for the final navigation callback.
      onEdt(() -> null);
      recording.stop();
      recording.dump(jfr);
      JSONObject row =
          new JSONObject()
              .put("fixture", fixture.name)
              .put("operation", operation)
              .put("warmup", warmup)
              .put("wallMs", milliseconds(wall))
              .put("processCpuMs", milliseconds(process))
              .put("edtCpuMs", milliseconds(edt))
              .put("edtQueueDelayMaxMs", heartbeat.maxMs())
              .put("heartbeatCallbacks", heartbeat.delays.size())
              .put("jfr", jfr.getFileName().toString())
              .put("phases", phases(jfr));
      rows.put(row);
    }
  }

  private static JSONObject phases(Path file) throws Exception {
    Map<String, JSONObject> phases = new LinkedHashMap<>();
    for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
      if (!event.getEventType().getName().equals(PHASE_EVENT)) continue;
      String name = event.getString("phase");
      JSONObject row =
          phases.computeIfAbsent(
              name,
              ignored ->
                  new JSONObject()
                      .put("count", 0)
                      .put("sumMs", 0.0)
                      .put("maxMs", 0.0)
                      .put("threads", new JSONArray()));
      double duration = milliseconds(event.getDuration().toNanos());
      row.put("count", row.getInt("count") + 1)
          .put("sumMs", row.getDouble("sumMs") + duration)
          .put("maxMs", Math.max(row.getDouble("maxMs"), duration));
      String thread = event.getThread() == null ? "unknown" : event.getThread().getJavaName();
      JSONArray threads = row.getJSONArray("threads");
      if (!threads.toList().contains(thread)) threads.put(thread);
    }
    return new JSONObject(phases);
  }

  private static JSONArray aggregate(JSONArray observations) {
    Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
    for (int index = 0; index < observations.length(); index++) {
      JSONObject row = observations.getJSONObject(index);
      if (row.getBoolean("warmup")) continue;
      String key = row.getString("fixture") + "/" + row.getString("operation");
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
    }
    JSONArray result = new JSONArray();
    groups.forEach(
        (key, rows) -> {
          JSONObject group = new JSONObject().put("fixtureOperation", key).put("n", rows.size());
          for (String metric :
              List.of("wallMs", "processCpuMs", "edtCpuMs", "edtQueueDelayMaxMs")) {
            group.put(metric, statistics(rows.stream().map(row -> row.getDouble(metric)).toList()));
          }
          Map<String, List<Double>> phaseDurations = new LinkedHashMap<>();
          for (JSONObject row : rows) {
            JSONObject phase = row.getJSONObject("phases");
            for (String name : phase.keySet()) {
              phaseDurations
                  .computeIfAbsent(name, ignored -> new ArrayList<>())
                  .add(phase.getJSONObject(name).getDouble("sumMs"));
            }
          }
          JSONObject phaseStats = new JSONObject();
          phaseDurations.forEach((name, values) -> phaseStats.put(name, statistics(values)));
          result.put(group.put("phaseSumMs", phaseStats));
        });
    return result;
  }

  private static JSONObject statistics(List<Double> values) {
    List<Double> ordered = values.stream().sorted().toList();
    int middle = ordered.size() / 2;
    double median =
        ordered.size() % 2 == 0
            ? (ordered.get(middle - 1) + ordered.get(middle)) / 2
            : ordered.get(middle);
    return new JSONObject().put("median", median).put("max", ordered.get(ordered.size() - 1));
  }

  private static List<Fixture> fixtures(Path output) throws Exception {
    StringBuilder variations = header(SHORT_COMMENT, false);
    appendBranches(variations, 0, 7);
    variations.append(')');
    StringBuilder dense = header(SHORT_COMMENT, true);
    for (int index = 0; index < 300; index++) {
      dense
          .append(';')
          .append(index % 2 == 0 ? 'B' : 'W')
          .append("[]C[")
          .append(escape(SHORT_COMMENT))
          .append(']')
          .append(analysis());
    }
    dense.append(')');
    String longComment = SHORT_COMMENT.repeat(400);
    StringBuilder comments = header(longComment, false);
    for (int index = 0; index < 200; index++) {
      comments
          .append(';')
          .append(index % 2 == 0 ? 'B' : 'W')
          .append("[]C[")
          .append(escape(longComment))
          .append(']');
    }
    comments.append(')');
    return List.of(
        fixture(
            output, "wide-variations", variations.toString(), 21845, 5461, SHORT_COMMENT, false),
        fixture(output, "dense-analysis-ownership", dense.toString(), 301, 0, SHORT_COMMENT, true),
        fixture(
            output, "long-multilingual-comments", comments.toString(), 201, 0, longComment, false));
  }

  private static Fixture fixture(
      Path output, String name, String text, int nodes, int forks, String comment, boolean analysis)
      throws Exception {
    Path path = output.resolve(name + ".sgf");
    Files.writeString(path, text, StandardCharsets.UTF_8);
    return new Fixture(name, path, text, nodes, forks, comment, analysis);
  }

  private static StringBuilder header(String comment, boolean analysis) {
    return new StringBuilder("(;FF[4]GM[1]CA[UTF-8]SZ[19]KM[6.5]GN[SGF measurement]")
        .append("C[")
        .append(escape(comment))
        .append(']')
        .append(analysis ? analysis() : "");
  }

  private static void appendBranches(StringBuilder text, int level, int depth) {
    if (level == depth) return;
    for (int branch = 0; branch < 4; branch++) {
      text.append("(;")
          .append(level % 2 == 0 ? 'B' : 'W')
          .append('[')
          .append((char) ('a' + branch))
          .append((char) ('a' + level))
          .append("]C[")
          .append(escape(SHORT_COMMENT))
          .append(']');
      appendBranches(text, level + 1, depth);
      text.append(')');
    }
  }

  private static String analysis() {
    StringBuilder payload = new StringBuilder("LZ[KataGo 45 12345 2.5 1.25 0 rootVisits=12345\n");
    String columns = "ABCDEFGHJKLMNOPQRST";
    for (int candidate = 0; candidate < CANDIDATES; candidate++) {
      if (candidate > 0) payload.append(" info ");
      String coordinate = "" + columns.charAt(candidate % 19) + (candidate / 19 + 1);
      payload
          .append("move ")
          .append(coordinate)
          .append(" visits ")
          .append(1000 - candidate)
          .append(" winrate 5500 prior 1000 scoreMean 2.5 order ")
          .append(candidate)
          .append(" pv ")
          .append(coordinate)
          .append(" D4 Q16 C3 R17 D16 Q4 E5");
    }
    payload.append(" ownership");
    for (int point = 0; point < OWNERSHIP; point++)
      payload.append(point % 2 == 0 ? " 0.25" : " -0.25");
    return payload.append(']').toString();
  }

  private static String validate(Fixture fixture) throws Exception {
    ArrayDeque<BoardHistoryNode> pending = new ArrayDeque<>();
    pending.push(Lizzie.board.getHistory().getStart());
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    int nodes = 0;
    int forks = 0;
    while (!pending.isEmpty()) {
      BoardHistoryNode node = pending.pop();
      BoardData data = node.getData();
      nodes++;
      if (node.numberOfChildren() > 1) forks++;
      require(data.comment.equals(fixture.comment), "Comment lost or altered at node " + nodes);
      update(digest, node.numberOfChildren() + ":" + data.moveNumber + ":" + data.blackToPlay);
      update(digest, data.comment);
      update(digest, data.lastMove.map(point -> point[0] + ":" + point[1]).orElse("pass/root"));
      update(digest, java.util.Arrays.toString(data.stones));
      if (fixture.analysis) {
        List<MoveData> candidates = new ArrayList<>(data.bestMoves);
        if (data.bestMovesOutOfRange != null) candidates.addAll(data.bestMovesOutOfRange);
        require(candidates.size() == CANDIDATES, "Candidate count changed at node " + nodes);
        require(data.rootVisits == 12345, "Exact root visits changed");
        require(
            data.estimateArray != null && data.estimateArray.size() == OWNERSHIP,
            "Ownership shape changed at node " + nodes);
        for (int point = 0; point < OWNERSHIP; point++) {
          require(
              data.estimateArray.get(point) == (point % 2 == 0 ? 0.25 : -0.25),
              "Ownership value changed at " + point);
        }
        for (int candidate = 0; candidate < candidates.size(); candidate++) {
          MoveData move = candidates.get(candidate);
          require(move.playouts == 1000 - candidate, "Candidate visits changed");
          require(move.variation != null && move.variation.size() == 8, "PV was truncated");
          String coordinate =
              "" + "ABCDEFGHJKLMNOPQRST".charAt(candidate % 19) + (candidate / 19 + 1);
          require(coordinate.equals(move.coordinate), "Candidate coordinate changed");
          require(
              move.variation.equals(
                  List.of(coordinate, "D4", "Q16", "C3", "R17", "D16", "Q4", "E5")),
              "PV content changed");
          require(move.winrate == 55.0 && move.scoreMean == 2.5, "Candidate evaluation changed");
          update(
              digest,
              move.coordinate
                  + ":"
                  + move.playouts
                  + ":"
                  + move.winrate
                  + ":"
                  + move.scoreMean
                  + ":"
                  + move.variation);
        }
        update(digest, data.rootVisits + ":" + data.estimateArray);
      }
      List<BoardHistoryNode> children = node.getVariations();
      for (int index = children.size() - 1; index >= 0; index--) pending.push(children.get(index));
    }
    require(nodes == fixture.nodes, "Node count: expected " + fixture.nodes + ", got " + nodes);
    require(forks == fixture.forks, "Fork count: expected " + fixture.forks + ", got " + forks);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String legacyRead(Path path) throws Exception {
    String encoding = EncodingDetector.detect(path.toString());
    if (encoding.equals("WINDOWS-1252")) encoding = "GB18030";
    StringBuilder result = new StringBuilder();
    try (var reader = new InputStreamReader(new FileInputStream(path.toFile()), encoding)) {
      while (reader.ready()) result.append((char) reader.read());
    }
    return result.toString();
  }

  private static JSONObject environment() throws Exception {
    JSONObject hashes = new JSONObject();
    Path sourceRoot = Path.of("src/main/java");
    if (Files.isDirectory(sourceRoot)) {
      try (var paths = Files.walk(sourceRoot)) {
        for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
          hashes.put(path.toString().replace('\\', '/'), sha256(Files.readAllBytes(path)));
        }
      }
    }
    Path probe = Path.of("src/test/java/featurecat/lizzie/gui/SgfPerformanceProbe.java");
    if (Files.exists(probe)) hashes.put(probe.toString(), sha256(Files.readAllBytes(probe)));
    return new JSONObject()
        .put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
        .put("architecture", System.getProperty("os.arch"))
        .put("javaVersion", System.getProperty("java.runtime.version"))
        .put("javaVendor", System.getProperty("java.vendor"))
        .put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments())
        .put("maxHeapBytes", Runtime.getRuntime().maxMemory())
        .put("logicalProcessors", Runtime.getRuntime().availableProcessors())
        .put("sourceSha256", hashes);
  }

  private static long edtCpu() {
    return ManagementFactory.getThreadMXBean().getThreadCpuTime(edtThread);
  }

  private static double milliseconds(long nanoseconds) {
    return nanoseconds / 1_000_000.0;
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("]", "\\]");
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void update(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(90, TimeUnit.SECONDS);
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }

  private record Fixture(
      String name, Path path, String text, int nodes, int forks, String comment, boolean analysis) {
    JSONObject evidence() throws Exception {
      return new JSONObject()
          .put("name", name)
          .put("bytes", Files.size(path))
          .put("sha256", sha256(Files.readAllBytes(path)))
          .put("expectedNodes", nodes)
          .put("expectedForks", forks)
          .put("commentCharactersPerNode", comment.length())
          .put("expectedCandidatesPerNode", analysis ? CANDIDATES : 0)
          .put("expectedOwnershipValuesPerNode", analysis ? OWNERSHIP : 0);
    }
  }

  private static final class Heartbeat implements AutoCloseable {
    private final List<Long> delays = new ArrayList<>();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(
            action -> {
              Thread thread = new Thread(action, "sgf-probe-heartbeat");
              thread.setDaemon(true);
              return thread;
            });

    void start() {
      executor.scheduleAtFixedRate(
          () -> {
            if (!pending.compareAndSet(false, true)) return;
            long queued = System.nanoTime();
            SwingUtilities.invokeLater(
                () -> {
                  synchronized (delays) {
                    delays.add(System.nanoTime() - queued);
                  }
                  pending.set(false);
                });
          },
          0,
          20,
          TimeUnit.MILLISECONDS);
    }

    void stop() {
      executor.shutdownNow();
    }

    double maxMs() {
      synchronized (delays) {
        return milliseconds(delays.stream().max(Comparator.naturalOrder()).orElse(0L));
      }
    }

    @Override
    public void close() {
      stop();
    }
  }
}
