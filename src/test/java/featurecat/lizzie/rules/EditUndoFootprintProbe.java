package featurecat.lizzie.rules;

import featurecat.lizzie.Lizzie;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Manual snapshot object-graph measurement, not a timing or total retained-heap test. Run with
 * this class as a javaagent and main class, the test classpath, and java.lang/java.util opened.
 * Arguments: existing EditUndoPerformanceProbe fixture directory, new output JSON file.
 */
public final class EditUndoFootprintProbe {
  private static Instrumentation instrumentation;

  public static void premain(String ignored, Instrumentation instance) {
    instrumentation = instance;
  }

  public static void main(String[] args) throws Exception {
    try {
      run(Path.of(args[0]), Path.of(args[1]));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void run(Path fixtures, Path output) throws Exception {
    if (instrumentation == null) throw new IllegalStateException("Run with -javaagent");
    if (Files.exists(output)) throw new IllegalArgumentException("Output already exists");
    JSONArray rows = new JSONArray();
    for (String name : List.of("wide-variations", "dense-analysis-ownership",
        "long-multilingual-comments", "dense-analysis-long-pv-visits")) {
      byte[] input = Files.readAllBytes(fixtures.resolve(name + ".sgf"));
      try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(19)) {
        Lizzie.config.newMoveNumberInBranch = true;
        Lizzie.config.limitMaxSuggestion = 0;
        Lizzie.config.limitBranchLength = 0;
        Board board = Lizzie.board;
        BoardHistoryList history = SGFParser.parseSgf(
            new String(input, java.nio.charset.StandardCharsets.UTF_8), true);
        history.setHead(history.getStart());
        board.setHistory(history);
        settle(board);
        board.deleteMoveNoHintAfter();
        settle(board);
        Field pending = Board.class.getDeclaredField("stateBeforeEdit");
        pending.setAccessible(true);
        Object snapshot = pending.get(board);
        if (snapshot == null || !board.canUndoEdit()) throw new AssertionError("Missing undo");
        Graph saved = Graph.walk(snapshot);
        Graph live = Graph.walk(board.getHistory());
        rows.put(new JSONObject().put("fixture", name)
            .put("fixtureSha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input)))
            .put("snapshotGraph", saved.summary(Set.of()))
            .put("snapshotObjectsNotInLiveHistory", saved.summary(live.objects)));
      }
    }
    JSONObject result = new JSONObject()
        .put("java", System.getProperty("java.runtime.version"))
        .put("scope", "Sum of Instrumentation.getObjectSize over identity-distinct objects "
            + "reachable from the pending edit snapshot after a root deletion; skip the Board "
            + "owner back-reference, static fields and enum constants. Also subtract objects "
            + "reachable from the remaining live history. Other application/VM roots are not "
            + "subtracted: this is a graph footprint, not exclusive retained heap or peak RAM. "
            + "Headless rules harness replaces UI/engine callbacks; no latency is measured.")
        .put("fixtures", rows).put("result", "PASS");
    Files.writeString(output, result.toString(2) + "\n");
  }

  private static void settle(Board board) throws InterruptedException {
    CountDownLatch settled = new CountDownLatch(1);
    board.setMovelistAll(settled::countDown);
    if (!settled.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("Move-list refresh did not settle");
    }
  }

  private static final class Graph {
    final Set<Object> objects = Collections.newSetFromMap(new IdentityHashMap<>());

    static Graph walk(Object root) throws IllegalAccessException {
      Graph graph = new Graph();
      ArrayDeque<Object> pending = new ArrayDeque<>();
      Map<Class<?>, List<Field>> fields = new HashMap<>();
      pending.add(root);
      while (!pending.isEmpty()) {
        Object object = pending.removeLast();
        if (object instanceof Enum<?> || !graph.objects.add(object)) continue;
        if (object instanceof Board) throw new AssertionError("Traversal escaped snapshot/history");
        Class<?> type = object.getClass();
        if (type.isArray()) {
          if (!type.getComponentType().isPrimitive()) {
            for (Object child : (Object[]) object) if (child != null) pending.add(child);
          }
        } else {
          for (Field field : fields.computeIfAbsent(type, Graph::referenceFields)) {
            Object child = field.get(object);
            if (child != null) pending.add(child);
          }
        }
      }
      return graph;
    }

    private static List<Field> referenceFields(Class<?> type) {
      List<Field> result = new ArrayList<>();
      for (Class<?> current = type; current != null; current = current.getSuperclass()) {
        for (Field field : current.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
              || (current == Board.GameStateSnapshot.class && field.getName().equals("owner"))) {
            continue;
          }
          if (!field.trySetAccessible()) throw new IllegalStateException("Cannot inspect " + field);
          result.add(field);
        }
      }
      return result;
    }

    JSONObject summary(Set<Object> excluded) {
      Map<String, long[]> classes = new TreeMap<>();
      long count = 0;
      long bytes = 0;
      for (Object object : objects) {
        if (excluded.contains(object)) continue;
        long size = instrumentation.getObjectSize(object);
        count++;
        bytes += size;
        long[] row = classes.computeIfAbsent(object.getClass().getName(), ignored -> new long[2]);
        row[0]++;
        row[1] += size;
      }
      JSONObject details = new JSONObject();
      classes.forEach((name, row) -> details.put(name,
          new JSONObject().put("objects", row[0]).put("bytes", row[1])));
      return new JSONObject().put("objects", count).put("bytes", bytes).put("classes", details);
    }
  }
}
