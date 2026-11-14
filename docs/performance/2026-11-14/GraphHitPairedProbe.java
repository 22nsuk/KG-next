package featurecat.lizzie.gui;

import com.sun.management.ThreadMXBean;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import javax.swing.SwingUtilities;

/** Adjacent AB/BA batches with a baseline renamed only at its class declaration. */
public final class GraphHitPairedProbe {
  private static final Class<?> FIXTURE = WinrateGraphVariationHitTest.class;
  private static final ThreadMXBean BEAN =
      (ThreadMXBean) ManagementFactory.getThreadMXBean();
  private static final IdentityHashMap<BoardHistoryNode, Integer> IDS = new IdentityHashMap<>();
  private static volatile long sink;

  public static void main(String[] args) throws Exception {
    BEAN.setThreadAllocatedMemoryEnabled(true);
    BEAN.setThreadCpuTimeEnabled(true);
    SwingUtilities.invokeAndWait(() -> {
      try {
        for (int size : new int[] {400, 2000}) {
          run(size, 0);
        }
        run(2000, 128);
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
    });
  }

  private static void run(int length, int branches) throws Exception {
    Class<?> envClass = Class.forName(FIXTURE.getName() + "$TestEnvironment");
    Method open = envClass.getDeclaredMethod("open");
    open.setAccessible(true);
    try (AutoCloseable ignored = (AutoCloseable) open.invoke(null)) {
      IDS.clear();
      Board board = (Board) allocate(Class.forName(FIXTURE.getName() + "$TrackingBoard"));
      board.startStonelist = new ArrayList<>();
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(3, 3));
      List<BoardHistoryNode> main = new ArrayList<>();
      BoardHistoryNode node = history.getCurrentHistoryNode();
      main.add(node);
      for (int n = 1; n <= length; n++) {
        node = append(node, n, n);
        main.add(node);
      }
      BoardHistoryNode current = main.get(length / 2);
      if (branches > 0) {
        BoardHistoryNode fork = main.get(length / 2);
        for (int branch = 0; branch < branches; branch++) {
          node = fork;
          int branchLength = branch == branches - 1 ? length : 64;
          for (int n = 1; n <= branchLength; n++) {
            node = append(node, length / 2 + n, n + branch * 7);
            if (branch == branches - 1 && n == branchLength / 2) current = node;
          }
        }
      }
      history.setHead(current);
      board.setHistory(history);
      Lizzie.board = board;
      Lizzie.frame = (LizzieFrame) allocate(Class.forName(FIXTURE.getName() + "$TrackingFrame"));
      for (boolean pk : new boolean[] {false, true}) {
        board.isPkBoard = pk;
        for (boolean overview : new boolean[] {false, true}) {
          Lizzie.config.showWinrateOverview = overview;
          WinrateGraph graph = new WinrateGraph();
          BaselineWinrateGraph baseline = new BaselineWinrateGraph();
          LizzieFrame.winrateGraph = graph;
          invokeFixture("renderGraph", new Class<?>[] {WinrateGraph.class, int.class, int.class},
              graph, 1200, 260);
          renderBaseline(baseline);
          int[] p = (int[]) field(graph, "params");
          if (!java.util.Arrays.equals(p, (int[]) field(baseline, "params")))
            throw new AssertionError("Different rendered geometry");
          int[][] coordinates = new int[][] {
            {p[0] + p[2] / 4, 30}, {p[0] + p[2] / 2, 90},
            {p[0] + p[2] * 3 / 4, 150}, {p[0] + p[2] / 2, 230}
          };
          sample(graph, baseline, coordinates, length, branches, pk, overview, "fresh");
          double savedWinrate = current.getData().winrate;
          current.getData().winrate = 3.0;
          sample(graph, baseline, coordinates, length, branches, pk, overview, "stale");
          current.getData().winrate = savedWinrate;
        }
      }
    }
  }

  private static BoardHistoryNode append(BoardHistoryNode parent, int number, int seed)
      throws Exception {
    BoardData data = (BoardData) invokeFixture("moveNode",
        new Class<?>[] {int.class, int.class, Stone.class, boolean.class, int.class, double.class},
        seed % 3, (seed / 3) % 3, number % 2 == 1 ? Stone.BLACK : Stone.WHITE,
        number % 2 == 0, number, 25.0 + seed % 51);
    BoardHistoryNode node = parent.addAtLast(data);
    IDS.put(node, IDS.size() + 1);
    return node;
  }

  private static void renderBaseline(BaselineWinrateGraph graph) {
    java.awt.image.BufferedImage a = new java.awt.image.BufferedImage(1200, 260, 2);
    java.awt.image.BufferedImage b = new java.awt.image.BufferedImage(1200, 260, 2);
    java.awt.image.BufferedImage c = new java.awt.image.BufferedImage(1200, 260, 2);
    java.awt.Graphics2D ga = a.createGraphics(), gb = b.createGraphics(), gc = c.createGraphics();
    try {
      graph.draw(ga, gb, gc, 0, 0, 1200, 260);
    } finally {
      ga.dispose(); gb.dispose(); gc.dispose();
    }
  }

  private static void sample(WinrateGraph graph, BaselineWinrateGraph baseline,
      int[][] coordinates, int length, int branches, boolean pk, boolean overview, String state) {
    for (int[] xy : coordinates) {
      BoardHistoryNode before = baseline.resolveMoveTargetNode(xy[0], xy[1]);
      BoardHistoryNode after = graph.resolveMoveTargetNode(xy[0], xy[1]);
      if (before != after || (state.equals("stale") && after != null))
        throw new AssertionError("Different or stale target");
    }
    int warmup = length == 400 && !pk && !overview && state.equals("fresh") ? 15000 : 1500;
    exercise(baseline, coordinates, warmup);
    exercise(graph, coordinates, warmup);
    for (int pair = 0; pair < 12; pair++) {
      boolean baselineFirst = pair % 2 == 0;
      double[] before, after;
      if (baselineFirst) {
        before = measure(baseline, coordinates);
        after = measure(graph, coordinates);
      } else {
        after = measure(graph, coordinates);
        before = measure(baseline, coordinates);
      }
      if (before[3] != after[3]) throw new AssertionError("Different target checksum");
      System.out.printf(Locale.ROOT,
          "%d,%d,%s,%s,%s,%d,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.0f%n",
          length, branches, pk, overview, state, pair, baselineFirst ? "AB" : "BA",
          before[0], after[0], before[1], after[1], before[2], after[2], before[3]);
    }
  }

  private static double[] measure(WinrateGraph graph, int[][] coordinates) {
    int iterations = 200;
    long id = Thread.currentThread().threadId();
    long allocated = BEAN.getThreadAllocatedBytes(id);
    long cpu = BEAN.getCurrentThreadCpuTime();
    long start = System.nanoTime();
    long result = exercise(graph, coordinates, iterations);
    long elapsed = System.nanoTime() - start;
    cpu = BEAN.getCurrentThreadCpuTime() - cpu;
    allocated = BEAN.getThreadAllocatedBytes(id) - allocated;
    return new double[] {cpu / (double) iterations, allocated / (double) iterations,
        elapsed / (double) iterations, result};
  }

  private static long exercise(WinrateGraph graph, int[][] coordinates, int iterations) {
    long result = 0;
    for (int i = 0; i < iterations; i++) {
      int[] xy = coordinates[i % coordinates.length];
      BoardHistoryNode node = graph.resolveMoveTargetNode(xy[0], xy[1]);
      result += node == null ? 0 : IDS.getOrDefault(node, -1);
    }
    sink = result;
    return result;
  }

  private static double[] measure(BaselineWinrateGraph graph, int[][] coordinates) {
    int iterations = 200;
    long id = Thread.currentThread().threadId();
    long allocated = BEAN.getThreadAllocatedBytes(id);
    long cpu = BEAN.getCurrentThreadCpuTime();
    long start = System.nanoTime();
    long result = exercise(graph, coordinates, iterations);
    long elapsed = System.nanoTime() - start;
    cpu = BEAN.getCurrentThreadCpuTime() - cpu;
    allocated = BEAN.getThreadAllocatedBytes(id) - allocated;
    return new double[] {cpu / (double) iterations, allocated / (double) iterations,
        elapsed / (double) iterations, result};
  }

  private static long exercise(BaselineWinrateGraph graph, int[][] coordinates, int iterations) {
    long result = 0;
    for (int i = 0; i < iterations; i++) {
      int[] xy = coordinates[i % coordinates.length];
      BoardHistoryNode node = graph.resolveMoveTargetNode(xy[0], xy[1]);
      result += node == null ? 0 : IDS.getOrDefault(node, -1);
    }
    sink = result;
    return result;
  }

  private static Object allocate(Class<?> type) throws Exception {
    return invokeFixture("allocate", new Class<?>[] {Class.class}, type);
  }

  private static Object field(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private static Object invokeFixture(String name, Class<?>[] types, Object... args)
      throws Exception {
    Method m = FIXTURE.getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(null, args);
  }
}
