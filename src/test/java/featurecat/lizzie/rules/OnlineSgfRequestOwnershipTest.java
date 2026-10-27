package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.SyncDiagnosticsRecorder;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.OnlineDialog;
import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.InvocationEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.*;
import javax.swing.JFormattedTextField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

/** Real HTTP, stop/start, EDT handoff, SGF parser and board replacement; no load mocks. */
@Isolated("Uses shared Lizzie state and installs an observing AWT event queue")
class OnlineSgfRequestOwnershipTest {
  private static final String OLD = "(;SZ[5]PB[old request];B[aa];W[bb])";
  private static final String NEW = "(;SZ[5]PB[new request];B[dd];W[ee])";
  @TempDir Path directory;
  private RulesLayerTestHarness rules;
  private QuietFrame frame;
  private QuietDialog dialog;
  private ServerSocket server;
  private ExecutorService httpWorkers;
  private ObservingQueue queue;
  private final List<Response> responses = new ArrayList<>();
  private final ConcurrentMap<String, BlockingQueue<Response>> routes = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<Throwable> httpFailures = new ConcurrentLinkedQueue<>();
  private ResourceBundle previousResources;
  private EngineManager previousManager;
  private boolean previousStopped;
  private boolean previousFromBrowser;

  @BeforeEach
  void prepare() throws Exception {
    previousResources = Lizzie.resourceBundle;
    previousManager = Lizzie.engineManager;
    previousStopped = (boolean) field(OnlineDialog.class, "isStoped").get(null);
    previousFromBrowser = (boolean) field(OnlineDialog.class, "fromBrowser").get(null);
    rules = RulesLayerTestHarness.open();
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.ENGLISH);
    Lizzie.engineManager = null;
    Lizzie.config = ConfigTestHelper.createForTests(directory);
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.config.persisted = new JSONObject().put("filesystem", new JSONObject());
    Lizzie.config.readKomi = true;
    frame = RulesLayerTestHarness.allocate(QuietFrame.class);
    Lizzie.frame = frame;
    dialog = RulesLayerTestHarness.allocate(QuietDialog.class);
    field(OnlineDialog.class, "resourceBundle").set(dialog, Lizzie.resourceBundle);
    field(OnlineDialog.class, "txtUrl").set(dialog, new JTextField());
    JFormattedTextField refresh = new JFormattedTextField();
    refresh.setText("1000");
    field(OnlineDialog.class, "txtRefreshTime").set(dialog, refresh);
    Class<?> state = Class.forName(OnlineDialog.class.getName() + "$YikeSessionState");
    Method empty = state.getDeclaredMethod("empty");
    empty.setAccessible(true);
    field(OnlineDialog.class, "activeYikeSession").set(dialog, empty.invoke(null));
    field(OnlineDialog.class, "pendingYikeSession").set(dialog, empty.invoke(null));
    queue = new ObservingQueue();
    Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
    server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    httpWorkers = Executors.newCachedThreadPool();
    httpWorkers.submit(() -> {
      try {
        while (!server.isClosed()) {
          Socket socket = server.accept();
          httpWorkers.submit(() -> serve(socket));
        }
      } catch (SocketException e) {
        if (!server.isClosed()) httpFailures.add(e);
      } catch (Throwable e) { httpFailures.add(e); }
    });
  }

  @AfterEach
  void restore() throws Exception {
    try {
      for (Response response : responses) response.release.countDown();
      if (dialog != null) SwingUtilities.invokeAndWait(dialog::stopSync);
      if (server != null) server.close();
      if (httpWorkers != null) {
        httpWorkers.shutdownNow();
        assertTrue(httpWorkers.awaitTermination(5, TimeUnit.SECONDS));
      }
      assertTrue(httpFailures.isEmpty(), () -> "HTTP fixture failed: " + httpFailures);
      SwingUtilities.invokeAndWait(() -> {
        frame.shutdownKifuEngineSyncCoordinator();
        queue.remove();
      });
    } finally {
      if (rules != null) rules.close();
      Lizzie.resourceBundle = previousResources;
      Lizzie.engineManager = previousManager;
      field(OnlineDialog.class, "isStoped").set(null, previousStopped);
      field(OnlineDialog.class, "fromBrowser").set(null, previousFromBrowser);
      SyncDiagnosticsRecorder.clearDefaultForTests();
    }
  }

  @Test
  void currentResponseLoadsThroughRealParser() throws Exception {
    Response response = response("/current", NEW);
    start(response);
    finish(response);
    assertNewGame();
  }

  @Test
  void responseArrivingAfterStopPreservesCurrentGame() throws Exception {
    Response response = response("/old", OLD);
    start(response);
    BoardHistoryList current = Lizzie.board.getHistory();
    SwingUtilities.invokeAndWait(dialog::stopSync);
    finish(response);
    assertSame(current, Lizzie.board.getHistory());
  }

  @Test
  void stopRejectsResponseAlreadyQueuedOnEdt() throws Exception {
    Response response = response("/queued", OLD);
    start(response);
    BoardHistoryList current = Lizzie.board.getHistory();
    SwingUtilities.invokeAndWait(() -> {
      response.release.countDown();
      await(queue.queued);
      dialog.stopSync();
    });
    SwingUtilities.invokeAndWait(() -> {});
    assertSame(current, Lizzie.board.getHistory());
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void newSyncWinsEvenWhenOldResponseArrivesLast(boolean stopFirst, boolean sameUrl) throws Exception {
    Response old = response("/old", OLD);
    Response next = response(sameUrl ? "/old" : "/new", NEW);
    start(old);
    if (stopFirst) SwingUtilities.invokeAndWait(dialog::stopSync);
    start(next);
    finish(next);
    assertNewGame();
    BoardHistoryList current = Lizzie.board.getHistory();
    queue.queued = new CountDownLatch(1);
    finish(old);
    assertSame(current, Lizzie.board.getHistory());
    assertNewGame();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ownershipSurvivesDeferredQuickAnalysisRestore(boolean stopBeforeRestore) throws Exception {
    Response response = response("/deferred", NEW);
    start(response);
    BoardHistoryList current = Lizzie.board.getHistory();
    SwingUtilities.invokeAndWait(() -> setFrame("analysisControlCleanupInProgress", true));
    finish(response);
    assertSame(current, Lizzie.board.getHistory(), "Load must wait for the actual restore boundary");
    assertNotNull(field(LizzieFrame.class, "pendingKifuOpen").get(frame));
    SwingUtilities.invokeAndWait(() -> {
      if (stopBeforeRestore) dialog.stopSync();
      setFrame("analysisControlCleanupInProgress", false);
      try {
        Method finish = LizzieFrame.class.getDeclaredMethod("finishDeferredKifuOpenAfterQuickAnalysisRestore");
        finish.setAccessible(true);
        assertEquals(true, finish.invoke(frame));
      } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    });
    if (stopBeforeRestore) assertSame(current, Lizzie.board.getHistory());
    else assertNewGame();
  }

  @ParameterizedTest
  @CsvSource({"true,true,true", "false,true,true", "true,false,true", "true,true,false"})
  void userPauseCleanupPreservesResumeWhenDeferredResponseIsStale(
      boolean stopBeforeRestore, boolean requestResume, boolean restoreSucceeded) throws Exception {
    Response response = response("/pause-cleanup", NEW);
    start(response);
    BoardHistoryList current = Lizzie.board.getHistory();
    // Hold the asynchronous engine-restore boundary. The request, deferred load, user resume
    // control and cleanup callback below are real; only engine I/O and native painting are quiet.
    SwingUtilities.invokeAndWait(
        () -> {
          setFrame("analysisControlCleanupInProgress", true);
          setFrame("userAnalysisPaused", true);
        });
    finish(response);
    assertNotNull(field(LizzieFrame.class, "pendingKifuOpen").get(frame));

    ResumeTrackingLeelaz engine = new ResumeTrackingLeelaz();
    Lizzie.setPrimaryEngine(engine);
    EngineManager.isEmpty = false;
    SwingUtilities.invokeAndWait(
        () -> {
          if (requestResume) frame.togglePonderMannul();
          if (stopBeforeRestore) dialog.stopSync();
        });
    assertEquals(
        requestResume,
        field(LizzieFrame.class, "pendingForegroundResumeAfterCleanup").getBoolean(frame));
    assertTrue(engine.events.isEmpty(), "Resume must wait for engine restoration");
    Method cleanup =
        LizzieFrame.class.getDeclaredMethod(
            "finishUserAnalysisPauseCleanup", long.class, boolean.class);
    cleanup.setAccessible(true);
    long generation = field(LizzieFrame.class, "analysisControlCleanupGeneration").getLong(frame);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            cleanup.invoke(frame, generation, restoreSucceeded);
          } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
          }
        });

    if (stopBeforeRestore) assertSame(current, Lizzie.board.getHistory());
    else assertNewGame();
    assertEquals(
        stopBeforeRestore && requestResume && restoreSucceeded
            ? List.of("sync", "ponder")
            : List.of(),
        engine.events,
        "A rejected load must not swallow resume; an accepted load owns its own resume");
    assertFalse(field(LizzieFrame.class, "pendingForegroundResumeAfterCleanup").getBoolean(frame));
    assertFalse(field(LizzieFrame.class, "analysisControlCleanupInProgress").getBoolean(frame));
    assertNull(field(LizzieFrame.class, "pendingKifuOpen").get(frame));
    if (stopBeforeRestore) {
      assertEquals(!requestResume || !restoreSucceeded, frame.isUserAnalysisPaused());
    }
  }

  private Response response(String path, String sgf) {
    Response response = new Response(path, sgf);
    responses.add(response);
    routes.computeIfAbsent(path, ignored -> new LinkedBlockingQueue<>()).add(response);
    return response;
  }

  private void serve(Socket socket) {
    try (socket) {
      socket.setSoTimeout(5000);
      BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      String request = in.readLine();
      assertNotNull(request);
      String headers;
      do { headers = in.readLine(); } while (headers != null && !headers.isEmpty());
      BlockingQueue<Response> route = routes.get(request.split(" ")[1]);
      assertNotNull(route, request);
      Response response = route.poll(5, TimeUnit.SECONDS);
      assertNotNull(response, request);
      response.entered.countDown();
      await(response.release);
      byte[] body = response.sgf.getBytes(StandardCharsets.UTF_8);
      var out = socket.getOutputStream();
      out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
    } catch (Throwable e) { httpFailures.add(e); }
  }

  private void start(Response response) throws Exception {
    queue.queued = new CountDownLatch(1);
    SwingUtilities.invokeAndWait(() -> dialog.applyChangeWeb(
        "http://127.0.0.1:" + server.getLocalPort() + response.path));
    await(response.entered);
  }

  private void finish(Response response) throws Exception {
    response.release.countDown();
    await(queue.queued);
    SwingUtilities.invokeAndWait(() -> {});
  }

  private void assertNewGame() {
    assertEquals("new request", Lizzie.board.getHistory().getGameInfo().getPlayerBlack());
    BoardData end = Lizzie.board.getHistory().getMainEnd().getData();
    assertEquals(2, end.moveNumber);
    assertEquals(Stone.BLACK, end.stones[Board.getIndex(3, 3)]);
    assertEquals(Stone.WHITE, end.stones[Board.getIndex(4, 4)]);
    assertEquals(Stone.EMPTY, end.stones[Board.getIndex(0, 0)]);
  }

  private void setFrame(String name, Object value) {
    try { field(LizzieFrame.class, name).set(frame, value); }
    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
  }

  private static Field field(Class<?> type, String name) throws NoSuchFieldException {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void await(CountDownLatch latch) {
    try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected lifecycle boundary was not reached"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
  }

  private record Response(String path, String sgf, CountDownLatch entered, CountDownLatch release) {
    Response(String path, String sgf) { this(path, sgf, new CountDownLatch(1), new CountDownLatch(1)); }
  }

  private static final class ObservingQueue extends EventQueue {
    volatile CountDownLatch queued = new CountDownLatch(1);
    @Override public void postEvent(AWTEvent event) {
      boolean sgfDispatch = event instanceof InvocationEvent && !SwingUtilities.isEventDispatchThread()
          && java.util.Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(
              frame -> frame.getClassName().equals(OnlineDialog.class.getName())
                  && frame.getMethodName().equals("parseSgf"));
      super.postEvent(event);
      if (sgfDispatch) queued.countDown();
    }
    void remove() { pop(); }
  }

  static final class QuietDialog extends OnlineDialog {
    private QuietDialog() { super(null); }
    @Override public void setVisible(boolean visible) {}
  }

  static final class QuietFrame extends LizzieFrame {
    @Override public boolean stopAiPlayingAndPolicy() { return false; }
    @Override public void setCommentPaneOrArea(boolean area) {}
    @Override public void cancelYikeLiveCurveCompletion() {}
    @Override public void setResult(String result) {}
    @Override public void setPlayers(String white, String black) {}
    @Override public void resetTitle() {}
    @Override public void updateTitle() {}
    @Override public void setVisible(boolean visible) {}
    @Override public void refresh() {}
    @Override public void refresh(int mode) {}
    @Override public void clearKataEstimate() {}
    @Override public void setCommentPaneContent() {}
    @Override public void onMainEnginePonder() {}
    @Override public void tryToResetByoTime() {}
    @Override public void requestProblemListRefresh() {}
    @Override public void refreshProblemListSnapshot() {}
    @Override protected void scheduleMovelistRefreshAfterKifuLoad() {}
    @Override public void scheduleResumeAnalysisAfterLoad(int delay, Runnable action) {}
  }

  static final class ResumeTrackingLeelaz extends Leelaz {
    final List<String> events = new ArrayList<>();

    ResumeTrackingLeelaz() throws java.io.IOException {
      super("");
    }

    @Override
    public boolean submitOrdinaryLiveBoardForwarding(
        EngineManager.OrdinaryLiveBoardForwardingIntent intent) {
      events.add("sync");
      return true;
    }

    @Override
    public void ponder() {
      events.add("ponder");
    }

    @Override
    public boolean isPondering() {
      return false;
    }
  }
}
