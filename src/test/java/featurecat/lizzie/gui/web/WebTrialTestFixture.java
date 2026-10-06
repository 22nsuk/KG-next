package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Real board/reservation and JSON dispatcher with an explicitly drainable command executor. */
final class WebTrialTestFixture implements AutoCloseable {
  private final Leelaz oldEngine = Lizzie.leelaz;
  private final Board oldBoard = Lizzie.board;
  private final Config oldConfig = Lizzie.config;
  private final LizzieFrame oldFrame = Lizzie.frame;
  private final WebBoardManager oldManager = Lizzie.webBoardManager;
  private final BoardRenderer oldRenderer = LizzieFrame.boardRenderer;
  private final BoardRenderer oldRenderer2 = LizzieFrame.boardRenderer2;
  private final String oldTitle = LizzieFrame.fileNameTitle;
  private final File oldFile = LizzieFrame.curFile;
  private final boolean oldRecreate = LizzieFrame.forceRecreate;
  final Events events = new Events();
  final WebBoardManager manager;
  final WebBoardServer server = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 3);
  final TrialTestConnection owner = new TrialTestConnection();
  final TrialTestConnection visitor = new TrialTestConnection();
  final AtomicReference<BoardHistoryNode> display = new AtomicReference<>();
  final Board board;
  final BoardHistoryNode anchor;
  String sessionId;
  String resumeToken;

  WebTrialTestFixture() throws Exception {
    this(true);
  }

  WebTrialTestFixture(boolean enter) throws Exception {
    this(enter, false);
  }

  WebTrialTestFixture(boolean enter, boolean liveExecutor) throws Exception {
    manager = liveExecutor ? new WebBoardManager() : new WebBoardManager(events);
    Lizzie.config = null;
    Lizzie.frame = null;
    LizzieFrame.boardRenderer = null;
    LizzieFrame.boardRenderer2 = null;
    Lizzie.leelaz = new Leelaz("");
    board = new Board();
    Lizzie.board = board;
    Lizzie.webBoardManager = manager;
    anchor = board.getHistory().getCurrentHistoryNode();
    manager.setOverrideSinkForTest(display::set);
    manager.setDesktopRefresherForTest(() -> {});
    manager.setMainlineTailSupplier(() -> anchor);
    manager.attachWebSocketServer(server);
    if (enter) enter();
  }

  void enter() {
    send(owner, "enter_trial");
    JSONObject grant =
        owner.messages.stream()
            .filter(m -> "trial_granted".equals(m.optString("type")))
            .reduce((a, b) -> b)
            .orElseThrow();
    sessionId = grant.getString("sessionId");
    resumeToken = grant.getString("resumeToken");
    events.publications.clear();
    owner.messages.clear();
  }

  void enqueue(TrialTestConnection connection, String type, Object... values) {
    JSONObject msg = new JSONObject().put("type", type).put("clientId", "owner");
    if (sessionId != null) msg.put("sessionId", sessionId);
    for (int i = 0; i < values.length; i += 2) msg.put((String) values[i], values[i + 1]);
    server.onMessage(connection.socket, msg.toString());
  }

  void send(TrialTestConnection connection, String type, Object... values) {
    enqueue(connection, type, values);
    events.drain();
  }

  @Override
  public void close() throws Exception {
    try {
      manager.stop();
      server.stop(1000);
    } finally {
      Lizzie.leelaz = oldEngine;
      Lizzie.board = oldBoard;
      Lizzie.config = oldConfig;
      Lizzie.frame = oldFrame;
      Lizzie.webBoardManager = oldManager;
      LizzieFrame.boardRenderer = oldRenderer;
      LizzieFrame.boardRenderer2 = oldRenderer2;
      LizzieFrame.fileNameTitle = oldTitle;
      LizzieFrame.curFile = oldFile;
      LizzieFrame.forceRecreate = oldRecreate;
    }
  }

  record Publication(String owner, BoardHistoryNode node) {}

  static final class Events implements WebBoardManager.TrialEvents {
    final ArrayDeque<Runnable> work = new ArrayDeque<>();
    final List<Timer> timers = new ArrayList<>();
    final List<Publication> publications = new ArrayList<>();

    public void execute(Runnable task) {
      work.add(task);
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
      assertEquals(5 * 60 * 1000L, unit.toMillis(delay));
      Timer timer = new Timer(task);
      timers.add(timer);
      return timer;
    }

    public void publish(WebBoardManager.TrialSession session) {
      publications.add(
          new Publication(
              session == null ? "" : session.ownerClientId,
              session == null ? null : session.displayNode));
    }

    void drain() {
      while (!work.isEmpty()) work.remove().run();
    }
  }

  static final class Timer extends FutureTask<Void> implements ScheduledFuture<Void> {
    final Runnable callback;

    Timer(Runnable callback) {
      super(callback, null);
      this.callback = callback;
    }

    public long getDelay(TimeUnit unit) {
      return 0;
    }

    public int compareTo(Delayed other) {
      return 0;
    }
  }
}
