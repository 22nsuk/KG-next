package featurecat.lizzie.gui.web;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineFollowController;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Enumeration;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.json.JSONObject;

public class WebBoardManager {
  private static final SecureRandom TOKENS = new SecureRandom();
  private static final int START_TIMEOUT_MS = 5000;
  private final Object lifecycleLock = new Object();
  private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000L;

  @FunctionalInterface
  public interface DisplayNodeOverrideSink {
    void set(BoardHistoryNode node);
  }

  /** 试下状态变化后通知桌面端 EDT 重绘棋盘和历史树。 */
  @FunctionalInterface
  public interface DesktopRefresher {
    void refresh();
  }

  /** The trial path needs scheduling and publication, not the collector's UI/serialization setup. */
  interface TrialEvents {
    void execute(Runnable task);

    ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit);

    void publish(TrialSession session);
  }

  public static class TrialSession {
    public final String ownerClientId;
    public final String sessionId = UUID.randomUUID().toString();
    private WebBoardServer ownerServer;
    private WebSocket ownerConnection;
    private String resumeToken;
    public final BoardHistoryNode anchorNode;
    public volatile BoardHistoryNode displayNode;
    final int boardWidth;
    final int boardHeight;
    final Board sourceBoard;
    final BoardHistoryList sourceHistory;

    /** 进入试下时若 anchor 是 mainline 末端（variations 为空），插入的 dummy 占位节点。null 表示未插入。 */
    BoardHistoryNode mainlineDummy;

    volatile long lastActivityMs;
    ScheduledFuture<?> idleTimer;
    long idleGeneration;

    TrialSession(String owner, BoardHistoryNode anchor) {
      this(owner, anchor, null);
    }

    TrialSession(String owner, BoardHistoryNode anchor, Board sourceBoard) {
      this.ownerClientId = owner;
      this.anchorNode = anchor;
      this.displayNode = anchor;
      this.boardWidth = Board.boardWidth;
      this.boardHeight = Board.boardHeight;
      this.sourceBoard = sourceBoard;
      this.sourceHistory = sourceBoard == null ? null : sourceBoard.getHistory();
      this.lastActivityMs = System.currentTimeMillis();
    }
  }

  public static final class TrialEnterResult {
    public enum Kind {
      ENTERED,
      IDEMPOTENT,
      IN_USE,
      ENGINE_BUSY
    }

    public static final TrialEnterResult ENTERED = new TrialEnterResult(Kind.ENTERED, "");
    public static final TrialEnterResult IDEMPOTENT = new TrialEnterResult(Kind.IDEMPOTENT, "");
    public static final TrialEnterResult ENGINE_BUSY = new TrialEnterResult(Kind.ENGINE_BUSY, "");

    private final Kind kind;
    private final String capturedOwnerClientId;

    private TrialEnterResult(Kind kind, String capturedOwnerClientId) {
      this.kind = kind;
      this.capturedOwnerClientId = capturedOwnerClientId;
    }

    private static TrialEnterResult inUse(String ownerClientId) {
      return new TrialEnterResult(Kind.IN_USE, ownerClientId);
    }

    public Kind kind() {
      return kind;
    }

    public String capturedOwnerClientId() {
      return capturedOwnerClientId;
    }

    private boolean isAccepted() {
      return kind == Kind.ENTERED || kind == Kind.IDEMPOTENT;
    }
  }

  private volatile WebBoardServer wsServer;
  private volatile WebBoardHttpServer httpServer;
  private volatile WebBoardDataCollector collector;
  private volatile boolean running;
  private volatile String accessUrl;
  private int actualHttpPort;
  private int actualWsPort;

  private final TrialEvents trialEvents;

  public WebBoardManager() {
    trialEvents = new TrialEvents() {
      @Override
      public void execute(Runnable task) {
        WebBoardDataCollector current = collector;
        if (current != null) current.runOnExecutor(task);
      }

      @Override
      public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        WebBoardDataCollector current = collector;
        return current == null ? null : current.scheduleOnExecutor(task, delay, unit);
      }

      @Override
      public void publish(TrialSession session) {
        WebBoardDataCollector current = collector;
        if (current != null) {
          current.onBoardStateChanged();
          current.broadcastTrialState(session);
        }
      }
    };
  }

  WebBoardManager(TrialEvents trialEvents) {
    this.trialEvents = java.util.Objects.requireNonNull(trialEvents);
  }

  private volatile DisplayNodeOverrideSink overrideSink =
      node -> {
        if (Lizzie.frame != null) Lizzie.frame.setDisplayNodeOverride(node);
      };
  private volatile DesktopRefresher desktopRefresher =
      () -> {
        if (Lizzie.frame == null) return;
        // 强制变化树重画：常规 path 只在 treeNode != currentHistoryNode 时重画
        // (LizzieFrame.java:9772)，但试下不动真 currentNode，所以默认不会触发重画。
        Lizzie.frame.redrawTree = true;
        javax.swing.SwingUtilities.invokeLater(() -> Lizzie.frame.refresh());
        // 变化树由异步线程算缓存图，第一次 paint 启动线程时缓存还没更新；
        // 离散事件后没有连续 paint，延迟一次 repaint 让新缓存上屏。
        javax.swing.Timer timer =
            new javax.swing.Timer(
                300,
                ev -> {
                  if (Lizzie.frame != null) {
                    Lizzie.frame.redrawTree = true;
                    Lizzie.frame.repaint();
                  }
                });
        timer.setRepeats(false);
        timer.start();
      };
  private volatile TrialSession activeSession;

  private volatile EngineFollowController engineController;
  private volatile java.util.function.BooleanSupplier desktopPlayingProbe = () -> false;
  private volatile java.util.function.Supplier<BoardHistoryNode> mainlineTailSupplier =
      () -> {
        if (Lizzie.board == null) return null;
        BoardHistoryNode n = Lizzie.board.getHistory().getCurrentHistoryNode();
        while (n != null && n.getData() != null && n.getData().dummy) {
          n = n.previous().orElse(null);
        }
        return n;
      };

  public void setEngineFollowController(EngineFollowController c) {
    this.engineController = c;
  }

  public void setDesktopPlayingProbe(java.util.function.BooleanSupplier p) {
    if (p != null) this.desktopPlayingProbe = p;
  }

  public void setMainlineTailSupplier(java.util.function.Supplier<BoardHistoryNode> s) {
    if (s != null) this.mainlineTailSupplier = s;
  }

  public boolean start() {
    synchronized (lifecycleLock) {
      if (running) return true;
      JSONObject cfg = Lizzie.config.config.optJSONObject("web-board");
      int httpPort = configuredInteger(cfg, "http-port", 9998);
      int wsPort = configuredInteger(cfg, "ws-port", 9999);
      int maxConn = configuredInteger(cfg, "max-connections", 20);
      // Port zero requests an ephemeral port. Near 65535, retry only representable ports.
      if (!validPort(httpPort) || !validPort(wsPort) || maxConn < 1) return false;

      WebBoardHttpServer http = null;
      WebBoardServer socket = null;
      WebBoardDataCollector data = null;
      boolean started = false;
      try {
        http = startHttpServer(httpPort);
        socket = startWebSocketServer(wsPort, maxConn);
        data = new WebBoardDataCollector();
        data.setServer(socket);
        http.setWsPort(socket.getPort());
        synchronized (this) {
          httpServer = http;
          collector = data;
          actualHttpPort = http.getPort();
          actualWsPort = socket.getPort();
          attachWebSocketServer(socket);
          accessUrl = "http://" + getLanIp() + ":" + actualHttpPort;
          running = true;
          publishTrialState();
        }
        started = true;
        return true;
      } catch (IOException failure) {
        org.slf4j.LoggerFactory.getLogger(WebBoardManager.class)
            .warn("Could not start web board", failure);
        return false;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      } finally {
        if (!started) {
          synchronized (this) {
            if (wsServer == socket) wsServer = null;
            if (httpServer == http) httpServer = null;
            if (collector == data) collector = null;
            resetPublishedServerState();
          }
          closeServers(data, socket, http);
        }
      }
    }
  }

  private static int configuredInteger(JSONObject config, String key, int fallback) {
    if (config == null || !config.has(key)) return fallback;
    return trialCoordinate(config, key);
  }

  private static boolean validPort(int port) {
    return port >= 0 && port <= 65535;
  }

  private static int lastCandidatePort(int port) {
    return port == 0 ? 0 : Math.min(port + 9, 65535);
  }

  private static WebBoardHttpServer startHttpServer(int port) throws IOException {
    for (int candidate = port; ; candidate++) {
      WebBoardHttpServer server = new WebBoardHttpServer(candidate);
      boolean ready = false;
      try {
        server.start();
        ready = true;
        return server;
      } catch (BindException occupied) {
        if (candidate == lastCandidatePort(port)) throw occupied;
      } finally {
        if (!ready) server.stop();
      }
    }
  }

  private static WebBoardServer startWebSocketServer(int port, int maxConnections)
      throws IOException, InterruptedException {
    for (int candidate = port; ; candidate++) {
      WebBoardServer server =
          new WebBoardServer(new InetSocketAddress("0.0.0.0", candidate), maxConnections);
      boolean ready = false;
      try {
        server.start();
        server.awaitStarted(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        ready = true;
        return server;
      } catch (BindException occupied) {
        if (candidate == lastCandidatePort(port)) throw occupied;
      } finally {
        if (!ready) closeServers(null, server, null);
      }
    }
  }

  private synchronized void handleClientMessage(
      WebBoardServer source, WebSocket connection, JSONObject message) {
    if (!currentConnection(source, connection)) return;
    String type = message.optString("type");
    if (!isTrialCommand(type)) return;
    String clientId = message.optString("clientId");
    if (clientId.isBlank() || clientId.length() > 128) return;
    if (!"enter_trial".equals(type) && !"resume_trial".equals(type)) {
      if (!authorizedCommand(activeSession, source, connection, message)) return;
      // An invalidated board blocks edits, not the authenticated owner's ability to exit.
      if (!"exit_trial".equals(type) && !validTrialBoard(activeSession)) return;
      if ("trial_move".equals(type)
          && !validTrialCoordinates(activeSession,
              trialCoordinate(message, "x"), trialCoordinate(message, "y"))) return;
      if ("trial_navigate".equals(type)) {
        if (message.has("childIndex")) {
          if (trialCoordinate(message, "childIndex") < 0) return;
        } else if (!"back".equals(message.optString("direction"))
            && !"forward".equals(message.optString("direction"))) return;
      }
    }
    // One FIFO hop for ALL network commands, including enter/exit/navigation. Never queue a
    // second move behind a later reset. The production TrialEvents uses the collector's single
    // executor; synchronous desktop force-exit can still invalidate queued session commands.
    trialEvents.execute(() -> processClientMessage(source, connection, message));
  }

  private static boolean isTrialCommand(String type) {
    return "enter_trial".equals(type) || "resume_trial".equals(type)
        || "exit_trial".equals(type) || "trial_move".equals(type)
        || "trial_navigate".equals(type) || "trial_reset".equals(type);
  }

  private boolean currentConnection(WebBoardServer source, WebSocket connection) {
    return wsServer == source && connection != null && connection.isOpen();
  }

  private void processClientMessage(
      WebBoardServer source, WebSocket connection, JSONObject message) {
    String type = message.optString("type");
    String clientId = message.optString("clientId");
    if (!"enter_trial".equals(type)) {
      synchronized (this) {
        if (!currentConnection(source, connection)) return;
        TrialSession session = activeSession;
        if ("resume_trial".equals(type)) {
          if (session == null || session.ownerServer != source
              || !session.ownerClientId.equals(clientId)
              || !session.sessionId.equals(message.optString("sessionId"))
              || !validTrialBoard(session) || session.resumeToken == null
              || message.optString("resumeToken").length() != 43
              || !MessageDigest.isEqual(session.resumeToken.getBytes(StandardCharsets.US_ASCII),
                  message.optString("resumeToken").getBytes(StandardCharsets.US_ASCII))) {
            denyTrial(source, connection, "expired", "");
            return;
          }
          if (session.ownerConnection != connection && session.ownerConnection.isOpen()) {
            denyTrial(source, connection, "in_use", session.ownerClientId);
            return;
          }
          session.ownerConnection = connection;
          session.resumeToken = newResumeToken();
          touchActivity(session);
          publishTrialState();
          grantTrial(source, connection, session);
        } else if (authorizedCommand(session, source, connection, message)
            && ("exit_trial".equals(type) || validTrialBoard(session))) {
          handleTrialCommand(message);
        }
      }
      return;
    }

    Board capturedBoard;
    BoardHistoryNode anchor;
    synchronized (this) {
      if (!currentConnection(source, connection) || Lizzie.board == null) return;
      // A replaced board cannot be resumed. Retire its reservation before admitting a new trial.
      if (activeSession != null && !validTrialBoard(activeSession)) endTrial();
      capturedBoard = Lizzie.board;
      anchor = capturedBoard.getHistory().getCurrentHistoryNode();
    }
    // Engine admission must remain outside the manager monitor (engine -> manager lock order).
    TrialEnterResult result =
        enterTrialWithResult(clientId, anchor, capturedBoard, source, connection);
    if (!result.isAccepted()) {
      denyTrial(source, connection,
          result.kind() == TrialEnterResult.Kind.IN_USE ? "in_use" : "engine_busy",
          result.capturedOwnerClientId());
    } else {
      synchronized (this) {
        TrialSession session = activeSession;
        if (currentConnection(source, connection) && session != null
            && session.ownerConnection == connection) {
          publishTrialState();
          grantTrial(source, connection, session);
        }
      }
    }
  }

  private static boolean authorizedCommand(
      TrialSession session, WebBoardServer source, WebSocket connection, JSONObject message) {
    return session != null && session.ownerServer == source && session.ownerConnection == connection
        && session.ownerClientId.equals(message.optString("clientId"))
        && session.sessionId.equals(message.optString("sessionId"));
  }

  private static String newResumeToken() {
    byte[] bytes = new byte[32];
    TOKENS.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void grantTrial(WebBoardServer server, WebSocket connection, TrialSession session) {
    // Never broadcast this credential, include it in URLs, or use the public clientId as a secret.
    server.sendToConnection(connection, new JSONObject().put("type", "trial_granted")
        .put("sessionId", session.sessionId).put("resumeToken", session.resumeToken).toString());
  }

  private static void denyTrial(
      WebBoardServer server, WebSocket connection, String reason, String owner) {
    server.sendToConnection(connection, new JSONObject().put("type", "trial_denied")
        .put("reason", reason).put("ownerClientId", owner).toString());
  }

  /** Existing-session commands and their publications share the same state-transition lock. */
  private void handleTrialCommand(JSONObject msg) {
    switch (msg.optString("type")) {
      case "exit_trial":
        exitTrial(msg.optString("clientId"));
        break;
      case "trial_move":
        {
          int x = trialCoordinate(msg, "x");
          int y = trialCoordinate(msg, "y");
          if (x < 0 || y < 0) return;
          applyTrialMove(msg.optString("clientId"), x, y);
          break;
        }
      case "trial_navigate":
        if (msg.has("childIndex")) {
          int childIndex = trialCoordinate(msg, "childIndex");
          if (childIndex < 0) return;
          trialNavigateForward(msg.optString("clientId"), childIndex);
        } else {
          trialNavigate(msg.optString("clientId"), msg.optString("direction"));
        }
        break;
      case "trial_reset":
        trialReset(msg.optString("clientId"));
        break;
      default:
        // unknown type — ignore
    }
  }

  private static int trialCoordinate(JSONObject message, String key) {
    Object value = message.opt(key);
    if (!(value instanceof Number)) return -1;
    try {
      int coordinate = new java.math.BigDecimal(value.toString()).intValueExact();
      return coordinate >= 0 ? coordinate : -1;
    } catch (NumberFormatException | ArithmeticException e) {
      return -1;
    }
  }

  void attachWebSocketServer(WebBoardServer server) {
    wsServer = server;
    server.setMessageHandler((conn, message) -> handleClientMessage(server, conn, message));
  }

  public void stop() {
    synchronized (lifecycleLock) {
      WebBoardServer socket = wsServer;
      WebBoardHttpServer http = httpServer;
      WebBoardDataCollector data = collector;
      try {
        synchronized (this) {
          if (socket != null) socket.setMessageHandler(null);
          wsServer = null; // Invalidate old callbacks before waiting for server threads.
          try {
            forceExitTrial();
          } finally {
            collector = null;
            httpServer = null;
            resetPublishedServerState();
          }
        }
      } finally {
        // Even an exit callback failure must release partially started resources. Never join
        // socket threads under the transition monitor they may be waiting to enter.
        closeServers(data, socket, http);
      }
    }
  }

  private void resetPublishedServerState() {
    running = false;
    accessUrl = null;
    actualHttpPort = 0;
    actualWsPort = 0;
  }

  private static void closeServers(
      WebBoardDataCollector data, WebBoardServer socket, WebBoardHttpServer http) {
    // Preserve interruption without letting it bypass the other resources' cleanup.
    boolean interrupted = Thread.interrupted();
    try {
      if (data != null) data.shutdown();
      if (socket != null) {
        socket.setMessageHandler(null);
        try {
          socket.stop(1000);
        } catch (InterruptedException stopInterrupted) {
          interrupted = true;
        }
      }
    } finally {
      if (http != null) http.stop();
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  public boolean isRunning() {
    return running;
  }

  public String getAccessUrl() {
    return accessUrl;
  }

  public int getWsPort() {
    return actualWsPort;
  }

  public WebBoardDataCollector getCollector() {
    return collector;
  }

  static String getLanIp() {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface ni = interfaces.nextElement();
        if (ni.isLoopback() || !ni.isUp()) continue;
        Enumeration<InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress addr = addrs.nextElement();
          if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
            return addr.getHostAddress();
          }
        }
      }
    } catch (SocketException ignored) {
    }
    return "127.0.0.1";
  }

  // --- Trial session API ---

  public synchronized String getCurrentTrialOwner() {
    return activeSession != null ? activeSession.ownerClientId : "";
  }

  public boolean enterTrial(String clientId, BoardHistoryNode anchor) {
    TrialEnterResult result = enterTrialWithResult(clientId, anchor);
    return result.isAccepted();
  }

  public TrialEnterResult enterTrialWithResult(String clientId, BoardHistoryNode anchor) {
    return enterTrialWithResult(clientId, anchor, null, null, null);
  }

  TrialEnterResult enterTrialWithResult(
      String clientId,
      BoardHistoryNode anchor,
      Board capturedBoard,
      WebBoardServer capturedServer,
      org.java_websocket.WebSocket capturedConnection) {
    Leelaz capturedEngine;
    synchronized (this) {
      if (activeSession != null) {
        return sameTrialOwner(activeSession, clientId, capturedServer, capturedConnection)
            ? TrialEnterResult.IDEMPOTENT
            : TrialEnterResult.inUse(activeSession.ownerClientId);
      }
      if (desktopPlayingProbe.getAsBoolean()) {
        return TrialEnterResult.ENGINE_BUSY;
      }
      capturedEngine = Lizzie.leelaz;
    }
    if (capturedEngine == null) {
      return TrialEnterResult.ENGINE_BUSY;
    }
    Leelaz.EngineModeReservation reservation = capturedEngine.beginEngineModeReservation();
    if (reservation == null) {
      return TrialEnterResult.ENGINE_BUSY;
    }
    boolean reservationClosed = false;
    try {
      synchronized (this) {
        if (activeSession != null) {
          return sameTrialOwner(activeSession, clientId, capturedServer, capturedConnection)
              ? TrialEnterResult.IDEMPOTENT
              : TrialEnterResult.inUse(activeSession.ownerClientId);
        }
        if (desktopPlayingProbe.getAsBoolean()
            || Lizzie.leelaz != capturedEngine
            || (capturedServer != null && !currentConnection(capturedServer, capturedConnection))
            || (capturedBoard != null
                && (Lizzie.board != capturedBoard
                    || capturedBoard.getHistory().getCurrentHistoryNode() != anchor))) {
          return TrialEnterResult.ENGINE_BUSY;
        }
        TrialSession session = new TrialSession(clientId, anchor, capturedBoard);
        session.ownerServer = capturedServer;
        session.ownerConnection = capturedConnection;
        session.resumeToken = capturedConnection == null ? null : newResumeToken();
        // 试下子要走分叉而非接续 mainline。若 anchor 是 mainline 末端（无主线下一手），
        // 先插一个 dummy 占据 variations[0]，让后续试下子永远 add 到 index>=1。
        // ReadBoard 同步推进 mainline 时会识别 dummy 并把它替换走（line ~1035），互不干扰。
        if (anchor.variations.isEmpty()) {
          BoardData dummyData = anchor.getData().clone();
          dummyData.dummy = true;
          // 清掉 lastMove，否则 BoardRenderer 画 variations[0] 的 ghost 时会落在 anchor 真实棋子位置。
          dummyData.lastMove = java.util.Optional.empty();
          BoardHistoryNode dummy = new BoardHistoryNode(dummyData);
          anchor.variations.add(dummy);
          anchor.setPreviousForChild(dummy);
          session.mainlineDummy = dummy;
        }
        activeSession = session;
        applyOverrideAndRefresh(anchor);
        scheduleIdleTimeout(session);
        EngineFollowController controller = engineController;
        reservation.close();
        reservationClosed = true;
        if (controller != null) {
          controller.onTrialEnter(anchor);
        }
        return TrialEnterResult.ENTERED;
      }
    } finally {
      if (!reservationClosed) {
        reservation.close();
      }
    }
  }

  private static boolean sameTrialOwner(
      TrialSession session, String clientId, WebBoardServer server, WebSocket connection) {
    return session.ownerClientId.equals(clientId) && session.ownerServer == server
        && session.ownerConnection == connection;
  }

  public boolean isEngineOperationExcludedByTrial() {
    EngineFollowController controller = engineController;
    return activeSession != null || (controller != null && controller.isTrialActive());
  }

  public synchronized void exitTrial(String clientId) {
    if (activeSession == null || !activeSession.ownerClientId.equals(clientId)) return;
    endTrial();
  }

  public synchronized void forceExitTrial() {
    if (activeSession == null) return;
    endTrial();
  }

  /** Called under the manager monitor; only a completed transition publishes an exit. */
  private void endTrial() {
    cancelIdleTimer(activeSession);
    cleanupMainlineDummy(activeSession);
    activeSession = null;
    applyOverrideAndRefresh(null);
    EngineFollowController c = engineController;
    if (c != null) {
      BoardHistoryNode tail = mainlineTailSupplier.get();
      if (tail != null) c.onTrialExit(tail);
    }
    publishTrialState();
  }

  private void publishTrialState() {
    trialEvents.publish(activeSession);
  }

  /**
   * 退出试下时清理 enter 时插入的 dummy 占位。
   *
   * <p>仅当用户进入试下后**没有落任何子**（anchor.variations 里只有 dummy）才删 dummy 让 anchor 回到原来"无主线下一手"的状态。否则保留 dummy
   * 当 mainline 占位，让试下子永远停在 variations[1+] 当分叉——若退出时把 dummy 删了，试下子会接续 mainline 污染主线。
   *
   * <p>保留的 dummy 是 EndDummay（dummy=true && variations.isEmpty()），lizzie 现有渲染会跳过它
   * （VariationTree.java:222），同步管线 ReadBoard 推新主线时会自动替换它（line ~1035）。
   */
  private static void cleanupMainlineDummy(TrialSession s) {
    BoardHistoryNode dummy = s.mainlineDummy;
    if (dummy == null) return;
    boolean hasNonDummy = false;
    for (BoardHistoryNode v : s.anchorNode.variations) {
      if (v != dummy && !v.getData().dummy) {
        hasNonDummy = true;
        break;
      }
    }
    if (!hasNonDummy) {
      s.anchorNode.variations.remove(dummy);
    }
    s.mainlineDummy = null;
  }

  public synchronized void applyTrialMove(String clientId, int x, int y) {
    TrialSession s = activeSession;
    if (s == null || !s.ownerClientId.equals(clientId) || !validTrialCoordinates(s, x, y)) return;
    doApplyMove(s, x, y);
    touchActivity(s);
  }

  private static boolean validTrialCoordinates(TrialSession s, int x, int y) {
    return x >= 0 && y >= 0 && x < s.boardWidth && y < s.boardHeight && validTrialBoard(s);
  }

  private static boolean validTrialBoard(TrialSession s) {
    if (Board.boardWidth != s.boardWidth || Board.boardHeight != s.boardHeight
        || (s.sourceBoard != null
            && (Lizzie.board != s.sourceBoard || s.sourceBoard.getHistory() != s.sourceHistory))) {
      return false;
    }
    BoardData data = s.displayNode.getData();
    long points = (long) s.boardWidth * s.boardHeight;
    return data != null && data.zobrist != null && data.stones != null && data.stones.length == points
        && (data.moveNumberList == null || data.moveNumberList.length == points);
  }

  public synchronized void trialNavigate(String clientId, String direction) {
    TrialSession s = activeSession;
    if (s == null || !s.ownerClientId.equals(clientId)) return;
    if ("back".equals(direction)) {
      if (s.displayNode == s.anchorNode) return;
      Optional<BoardHistoryNode> prev = s.displayNode.previous();
      if (!prev.isPresent()) return;
      s.displayNode = prev.get();
    } else if ("forward".equals(direction)) {
      // 跳过 dummy 占位（enter 时插的 mainline 占位 + ReadBoard 同步可能产生的 dummy）
      BoardHistoryNode firstReal = null;
      for (BoardHistoryNode v : s.displayNode.variations) {
        if (!v.getData().dummy) {
          firstReal = v;
          break;
        }
      }
      if (firstReal == null) return;
      s.displayNode = firstReal;
    } else {
      return;
    }
    applyOverrideAndRefresh(s.displayNode);
    {
      EngineFollowController c = engineController;
      if (c != null) c.onTrialDisplayNodeChanged(s.displayNode);
    }
    publishTrialState();
    touchActivity(s);
  }

  public synchronized void trialNavigateForward(String clientId, int childIndex) {
    TrialSession s = activeSession;
    if (s == null || !s.ownerClientId.equals(clientId)) return;
    if (childIndex < 0 || childIndex >= s.displayNode.variations.size()) return;
    BoardHistoryNode target = s.displayNode.variations.get(childIndex);
    if (target.getData().dummy) return; // 不允许跳到 dummy 占位
    s.displayNode = target;
    applyOverrideAndRefresh(s.displayNode);
    {
      EngineFollowController c = engineController;
      if (c != null) c.onTrialDisplayNodeChanged(s.displayNode);
    }
    publishTrialState();
    touchActivity(s);
  }

  public synchronized void trialReset(String clientId) {
    TrialSession s = activeSession;
    if (s == null || !s.ownerClientId.equals(clientId)) return;
    s.displayNode = s.anchorNode;
    applyOverrideAndRefresh(s.anchorNode);
    {
      EngineFollowController c = engineController;
      if (c != null) c.onTrialDisplayNodeChanged(s.anchorNode);
    }
    publishTrialState();
    touchActivity(s);
  }

  private void doApplyMove(TrialSession s, int x, int y) {
    synchronized (this) {
      // A queued command must still belong to this session and board when it executes.
      if (activeSession != s || !validTrialCoordinates(s, x, y)) return;
      BoardHistoryNode parent = s.displayNode;
      BoardData parentData = parent.getData();
      // 试下诊断（默认关闭，-Dlizzie.trial.diag=true 打开）：用户落子坐标 + 落子前 displayNode 引擎首选
      if (featurecat.lizzie.analysis.TrialDiag.ENABLED) {
        try {
          String userCoord = featurecat.lizzie.rules.Board.convertCoordinatesToName(x, y);
          String topEng = "(none)";
          double topWR = -1;
          if (parentData.bestMoves != null && !parentData.bestMoves.isEmpty()) {
            featurecat.lizzie.analysis.MoveData top = parentData.bestMoves.get(0);
            topEng = top.coordinate;
            topWR = top.winrate;
          }
          System.out.printf(
              "[trial-apply] parent moveNum=%d blackToPlay=%s userClick=%s engineTop=%s engineTopWR=%.2f%n",
              parentData.moveNumber, parentData.blackToPlay, userCoord, topEng, topWR);
        } catch (Exception ignored) {
        }
      }

      int idx = Board.getIndex(x, y);
      if (parentData.stones[idx] != Stone.EMPTY) return;

      Stone color = parentData.blackToPlay ? Stone.BLACK : Stone.WHITE;

      // 用 BoardData.move(...) 工厂构造，保证 nodeKind=MOVE、moveNumberList、zobrist 等渲染必须字段齐全
      Stone[] newStones = parentData.stones.clone();
      newStones[idx] = color;
      featurecat.lizzie.rules.Zobrist newZobrist =
          parentData.zobrist == null ? null : parentData.zobrist.clone();
      if (newZobrist != null) newZobrist.toggleStone(x, y, color);

      // 提子：移除四邻方向上对方的死子链（与 Board.place 主流程一致）
      int capturedStones = 0;
      Stone opp = color.opposite();
      capturedStones += Board.removeDeadChain(x + 1, y, opp, newStones, newZobrist);
      capturedStones += Board.removeDeadChain(x, y + 1, opp, newStones, newZobrist);
      capturedStones += Board.removeDeadChain(x - 1, y, opp, newStones, newZobrist);
      capturedStones += Board.removeDeadChain(x, y - 1, opp, newStones, newZobrist);
      // 自杀手禁手：上面提子后，如果新落子链自身仍无气，拒绝该落子
      int suicidalStones = Board.removeDeadChain(x, y, color, newStones, newZobrist);
      boolean canSuicidal = Lizzie.leelaz != null && Lizzie.leelaz.canSuicidal;
      if (suicidalStones > 0 && (!canSuicidal || suicidalStones == 1)) return;

      int newMoveNumber = parentData.moveNumber + 1;
      int[] newMoveNumberList =
          parentData.moveNumberList == null ? null : parentData.moveNumberList.clone();
      if (newMoveNumberList != null) {
        newMoveNumberList[idx] = newMoveNumber;
        // 提子位置上的旧编号要清掉
        for (int i = 0; i < newStones.length; i++) {
          if (newStones[i] == Stone.EMPTY) newMoveNumberList[i] = 0;
        }
      }

      int newBlackCaptures = parentData.blackCaptures + (color.isBlack() ? capturedStones : 0);
      int newWhiteCaptures = parentData.whiteCaptures + (color.isWhite() ? capturedStones : 0);

      BoardData newData =
          BoardData.move(
              newStones,
              new int[] {x, y},
              color,
              !parentData.blackToPlay,
              newZobrist,
              newMoveNumber,
              newMoveNumberList,
              newBlackCaptures,
              newWhiteCaptures,
              0,
              0);
      // 试下分支没有引擎分析（默认 bestMoves 即空、playouts=0）

      if (BoardHistoryList.violatesKoRule(parent, newData)) return;

      // 复用同位置子节点（跳过 dummy 占位）
      for (BoardHistoryNode existing : parent.variations) {
        BoardData ed = existing.getData();
        if (ed.dummy || !ed.isMoveNode() || ed.lastMoveColor != color) continue;
        if (ed.lastMove.isPresent() && ed.lastMove.get()[0] == x && ed.lastMove.get()[1] == y) {
          s.displayNode = existing;
          applyOverrideAndRefresh(existing);
          {
            EngineFollowController c = engineController;
            if (c != null) c.onTrialDisplayNodeChanged(existing);
          }
          publishTrialState();
          return;
        }
      }

      BoardHistoryNode child = new BoardHistoryNode(newData);
      // 试下永远走分叉：如果第一个子是 dummy 占位，把 child add 到末尾（自然在 dummy 之后）
      parent.variations.add(child);
      parent.setPreviousForChild(child);

      s.displayNode = child;
      applyOverrideAndRefresh(child);
      {
        EngineFollowController c = engineController;
        if (c != null) c.onTrialDisplayNodeChanged(child);
      }
      publishTrialState();
    }
  }

  private void scheduleIdleTimeout(TrialSession s) {
    long generation = ++s.idleGeneration;
    s.idleTimer =
        trialEvents.schedule(
            () -> {
              synchronized (this) {
                if (activeSession == s && s.idleGeneration == generation) forceExitTrial();
              }
            },
            IDLE_TIMEOUT_MS,
            TimeUnit.MILLISECONDS);
  }

  private void cancelIdleTimer(TrialSession s) {
    // A cancelled callback may already be waiting for the manager monitor.
    ++s.idleGeneration;
    if (s.idleTimer != null) {
      s.idleTimer.cancel(false);
      s.idleTimer = null;
    }
  }

  private void touchActivity(TrialSession s) {
    s.lastActivityMs = System.currentTimeMillis();
    cancelIdleTimer(s);
    scheduleIdleTimeout(s);
  }

  // --- Test hooks (package-private) ---

  void setOverrideSinkForTest(DisplayNodeOverrideSink sink) {
    this.overrideSink = sink;
  }

  void setDesktopRefresherForTest(DesktopRefresher refresher) {
    this.desktopRefresher = refresher;
  }

  /**
   * 设置 displayNode override 并通知桌面端 EDT 重绘。 因为 manager 直接操作 BoardHistoryNode.variations 而绕过了 lizzie
   * 的常规 place/refresh 链， 必须显式触发 refresh，否则棋盘画面与历史树视图都不会更新。
   */
  private void applyOverrideAndRefresh(BoardHistoryNode node) {
    overrideSink.set(node);
    // 切换 displayNode 后立刻清掉鼠标 hover：否则原坐标在新 displayNode 的 bestMoves 里同位置 move 上，
    // 渲染层会继续把它当 hover 画出 branch + 选点高亮，要把鼠标挪开才消失。
    if (Lizzie.frame != null) {
      Lizzie.frame.mouseOverCoordinate = featurecat.lizzie.gui.LizzieFrame.outOfBoundCoordinate;
    }
    desktopRefresher.refresh();
  }

  void setCollectorForTest(WebBoardDataCollector c) {
    this.collector = c;
  }

  String getTrialOwnerForTest() {
    return activeSession != null ? activeSession.ownerClientId : null;
  }

  BoardHistoryNode getDisplayNodeForTest() {
    return activeSession != null ? activeSession.displayNode : null;
  }

  BoardHistoryNode getTrialAnchorForTest() {
    return activeSession != null ? activeSession.anchorNode : null;
  }

  void setDisplayNodeForTest(BoardHistoryNode n) {
    if (activeSession != null) activeSession.displayNode = n;
  }
}
