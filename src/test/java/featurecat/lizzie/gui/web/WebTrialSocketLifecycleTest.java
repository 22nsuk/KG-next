package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class WebTrialSocketLifecycleTest {
  @Test
  @Timeout(20)
  void socketCommandsKeepBothClientsConsistentThroughRejectedAndSuccessfulExit() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    WebBoardManager previousManager = Lizzie.webBoardManager;
    LizzieFrame previousFrame = Lizzie.frame;
    WebBoardManager manager = new WebBoardManager();
    BoardHistoryNode anchor = new BoardHistoryNode(BoardData.empty(Board.boardWidth, Board.boardHeight));
    AtomicReference<BoardHistoryNode> display = new AtomicReference<>(anchor);
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    WebBoardDataCollector collector =
        new WebBoardDataCollector(executor, display::get, () -> anchor);
    CountDownLatch ready = new CountDownLatch(1);
    AtomicReference<Exception> error = new AtomicReference<>();
    WebBoardServer server = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 2) {
      @Override
      public void onStart() {
        ready.countDown();
      }

      @Override
      public void onError(WebSocket connection, Exception failure) {
        error.compareAndSet(null, failure);
        ready.countDown();
      }

      @Override
      public void onMessage(WebSocket connection, String text) {
        super.onMessage(connection, text);
        // A test-only FIFO barrier observes absence of spurious control broadcasts without sleeping.
        JSONObject message = new JSONObject(text);
        if (message.has("requestId")) {
          sendToConnection(connection, new JSONObject().put("type", "processed")
              .put("requestId", message.getInt("requestId")).toString());
        }
      }
    };
    Client owner = null;
    Client visitor = null;
    try {
      Lizzie.frame = null;
      Lizzie.leelaz = new Leelaz("");
      Lizzie.webBoardManager = manager;
      manager.setOverrideSinkForTest(node -> display.set(node == null ? anchor : node));
      manager.setDesktopRefresherForTest(() -> {});
      manager.setMainlineTailSupplier(() -> anchor);
      manager.setCollectorForTest(collector);
      collector.setServer(server);
      manager.attachWebSocketServer(server);
      // Enter uses the real reservation path; subsequent commands use the full network entrypoint.
      assertTrue(manager.enterTrial("owner", anchor));
      server.start();
      assertTrue(ready.await(3, TimeUnit.SECONDS));
      assertNull(error.get());
      owner = new Client(server.getPort());
      visitor = new Client(server.getPort());
      assertTrue(owner.connectBlocking(3, TimeUnit.SECONDS));
      assertTrue(visitor.connectBlocking(3, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (server.getConnections().size() != 2 && System.nanoTime() < deadline) Thread.sleep(5);
      assertEquals(2, server.getConnections().size());

      owner.send("{\"type\":\"trial_move\",\"clientId\":\"owner\",\"x\":3,\"y\":3}");
      assertActiveAt(owner.nextControl(), 1);
      assertActiveAt(visitor.nextControl(), 1);
      assertArrayEquals(new int[] {3, 3}, display.get().getData().lastMove.orElseThrow());

      visitor.send("{\"type\":\"exit_trial\",\"clientId\":\"visitor\",\"requestId\":1}");
      assertProcessed(visitor.nextControl(), 1);
      assertEquals("owner", manager.getCurrentTrialOwner());
      assertNotSame(anchor, display.get());

      owner.send("{\"type\":\"trial_reset\",\"clientId\":\"owner\"}");
      assertActiveAt(owner.nextControl(), 0); // A spurious idle broadcast would appear first here.
      assertActiveAt(visitor.nextControl(), 0);
      assertSame(anchor, display.get());

      owner.send("{\"type\":\"exit_trial\",\"clientId\":\"owner\"}");
      assertIdle(owner.nextControl());
      assertIdle(visitor.nextControl());
      assertEquals("", manager.getCurrentTrialOwner());
      assertSame(anchor, display.get());

      owner.send("{\"type\":\"exit_trial\",\"clientId\":\"owner\",\"requestId\":2}");
      assertProcessed(owner.nextControl(), 2);
      visitor.send("{\"type\":\"barrier\",\"requestId\":3}");
      assertProcessed(visitor.nextControl(), 3);
      assertNull(error.get());
      assertNull(owner.error.get());
      assertNull(visitor.error.get());
    } finally {
      try {
        manager.forceExitTrial();
        collector.shutdown();
        if (owner != null) owner.closeConnection(1000, "fixture cleanup");
        if (visitor != null) visitor.closeConnection(1000, "fixture cleanup");
        server.stop(1000);
        if (owner != null) assertTrue(owner.closed.await(3, TimeUnit.SECONDS));
        if (visitor != null) assertTrue(visitor.closed.await(3, TimeUnit.SECONDS));
      } finally {
        executor.shutdownNow();
        Lizzie.leelaz = previousEngine;
        Lizzie.webBoardManager = previousManager;
        Lizzie.frame = previousFrame;
      }
    }
  }

  private static void assertActiveAt(JSONObject message, int moveNumber) {
    assertEquals("trial_state", message.getString("type"));
    assertTrue(message.getBoolean("active"));
    assertEquals("owner", message.getString("ownerClientId"));
    assertEquals(moveNumber, message.getInt("displayMoveNumber"));
  }

  private static void assertIdle(JSONObject message) {
    assertEquals("trial_state", message.getString("type"));
    assertFalse(message.getBoolean("active"));
  }

  private static void assertProcessed(JSONObject message, int requestId) {
    assertEquals("processed", message.getString("type"), "rejected/no-op commands must not publish trial state");
    assertEquals(requestId, message.getInt("requestId"));
  }

  private static final class Client extends WebSocketClient {
    final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<>();
    final CountDownLatch closed = new CountDownLatch(1);
    final AtomicReference<Exception> error = new AtomicReference<>();

    Client(int port) {
      super(URI.create("ws://127.0.0.1:" + port));
    }

    @Override
    public void onOpen(ServerHandshake handshake) {}

    @Override
    public void onMessage(String text) {
      messages.add(new JSONObject(text));
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
      closed.countDown();
    }

    @Override
    public void onError(Exception failure) {
      error.compareAndSet(null, failure);
    }

    JSONObject nextControl() throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < deadline) {
        JSONObject message = messages.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        assertNotNull(message, "client did not receive the expected control frame");
        String type = message.getString("type");
        if ("trial_state".equals(type) || "processed".equals(type)) return message;
      }
      throw new AssertionError("control frame deadline exceeded");
    }
  }
}
