package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
  @Timeout(25)
  void socketsPreserveCommandOrderAndRequirePrivateReconnectCredentials() throws Exception {
    try (WebTrialTestFixture board = new WebTrialTestFixture(false)) {
      WebBoardManager manager = new WebBoardManager();
      AtomicReference<BoardHistoryNode> display = new AtomicReference<>(board.anchor);
      ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
      WebBoardDataCollector collector =
          new WebBoardDataCollector(executor, display::get, () -> board.anchor);
      CountDownLatch ownerDisconnected = new CountDownLatch(1);
      AtomicReference<WebSocket> ownerConnection = new AtomicReference<>();
      WebBoardServer server =
          new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 4) {
            @Override
            public void onMessage(WebSocket connection, String text) {
              super.onMessage(connection, text);
              JSONObject message = new JSONObject(text);
              if ("enter_trial".equals(message.optString("type"))) ownerConnection.set(connection);
              if (message.has("requestId")) {
                // Barrier follows the production dispatch on the SAME executor. No sleeps or
                // assumption that coalesced broadcasts deliver every intermediate state.
                executor.execute(
                    () ->
                        sendToConnection(
                            connection,
                            new JSONObject()
                                .put("type", "processed")
                                .put("requestId", message.getInt("requestId"))
                                .toString()));
              }
            }

            @Override
            public void onClose(WebSocket connection, int code, String reason, boolean remote) {
              super.onClose(connection, code, reason, remote);
              if (connection == ownerConnection.get()) ownerDisconnected.countDown();
            }
          };
      List<Client> clients = new ArrayList<>();
      try {
        Lizzie.webBoardManager = manager;
        manager.setOverrideSinkForTest(n -> display.set(n == null ? board.anchor : n));
        manager.setDesktopRefresherForTest(() -> {});
        manager.setMainlineTailSupplier(() -> board.anchor);
        manager.setCollectorForTest(collector);
        collector.setServer(server);
        manager.attachWebSocketServer(server);
        server.start();
        server.awaitStarted(3, TimeUnit.SECONDS);
        Client owner = connect(server, clients);
        Client visitor = connect(server, clients);
        owner.send(command("enter_trial", null).toString());
        JSONObject grant = owner.next("trial_granted");
        String session = grant.getString("sessionId");
        String token = grant.getString("resumeToken");
        assertEquals(43, token.length());
        JSONObject observed = visitor.next("trial_state");
        assertActiveAt(observed, 0);
        assertEquals(session, observed.getString("sessionId"));
        assertFalse(observed.has("resumeToken"));

        // Same public clientId and sessionId on a distinct real connection confer no rights.
        visitor.send(command("trial_move", session).put("x", 1).put("y", 1).toString());
        visitor.send(command("exit_trial", session).put("requestId", 1).toString());
        assertEquals(1, visitor.next("processed").getInt("requestId"));
        assertSame(board.anchor, display.get());
        assertEquals("owner", manager.getCurrentTrialOwner());

        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(
            () -> {
              blocked.countDown();
              try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
        assertTrue(blocked.await(3, TimeUnit.SECONDS));
        owner.send(command("trial_move", session).put("x", 3).put("y", 3).toString());
        owner.send(command("trial_reset", session).put("requestId", 2).toString());
        release.countDown();
        assertEquals(2, owner.next("processed").getInt("requestId"));
        assertSame(board.anchor, display.get(), "reset must follow, not overtake, the move");
        assertSame(board.anchor, board.board.getHistory().getCurrentHistoryNode());

        owner.closeBlocking();
        assertTrue(ownerDisconnected.await(3, TimeUnit.SECONDS));
        Client resumed = connect(server, clients);
        resumed.send(command("resume_trial", session).put("resumeToken", token).toString());
        JSONObject renewed = resumed.next("trial_granted");
        assertEquals(session, renewed.getString("sessionId"));
        assertNotEquals(token, renewed.getString("resumeToken"));
        visitor.send(command("resume_trial", session).put("resumeToken", token).toString());
        assertEquals("expired", visitor.next("trial_denied").getString("reason"));
        resumed.send(
            command("trial_move", session).put("x", 4).put("y", 4).put("requestId", 3).toString());
        assertEquals(3, resumed.next("processed").getInt("requestId"));
        assertArrayEquals(new int[] {4, 4}, display.get().getData().lastMove.orElseThrow());
        Client observer = connect(server, clients);
        assertActiveAt(observer.next("trial_state"), 1);
        resumed.send(command("exit_trial", session).toString());
        assertFalse(observer.next("trial_state").getBoolean("active"));
        assertSame(board.anchor, display.get());
        for (Client client : clients) {
          assertNull(client.error.get());
          if (client == visitor || client == observer)
            assertFalse(client.received.stream().anyMatch(m -> m.has("resumeToken")));
        }
      } finally {
        manager.forceExitTrial();
        collector.shutdown();
        for (Client client : clients) client.closeConnection(1000, "fixture cleanup");
        server.stop(1000);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        for (Client client : clients) assertTrue(client.closed.await(3, TimeUnit.SECONDS));
      }
    }
  }

  private static JSONObject command(String type, String session) {
    JSONObject msg = new JSONObject().put("type", type).put("clientId", "owner");
    if (session != null) msg.put("sessionId", session);
    return msg;
  }

  private static Client connect(WebBoardServer server, List<Client> clients) throws Exception {
    Client client = new Client(server.getPort());
    clients.add(client);
    assertTrue(client.connectBlocking(3, TimeUnit.SECONDS));
    return client;
  }

  private static void assertActiveAt(JSONObject message, int number) {
    assertTrue(message.getBoolean("active"));
    assertEquals("owner", message.getString("ownerClientId"));
    assertEquals(number, message.getInt("displayMoveNumber"));
  }

  private static final class Client extends WebSocketClient {
    final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<>();
    final List<JSONObject> received = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch closed = new CountDownLatch(1);
    final AtomicReference<Exception> error = new AtomicReference<>();

    Client(int port) {
      super(URI.create("ws://127.0.0.1:" + port));
    }

    @Override
    public void onOpen(ServerHandshake handshake) {}

    @Override
    public void onMessage(String text) {
      JSONObject message = new JSONObject(text);
      received.add(message);
      messages.add(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
      closed.countDown();
    }

    @Override
    public void onError(Exception failure) {
      error.compareAndSet(null, failure);
    }

    JSONObject next(String type) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      while (System.nanoTime() < deadline) {
        JSONObject msg =
            messages.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        assertNotNull(msg, "missing " + type + " frame");
        if (type.equals(msg.optString("type"))) return msg;
      }
      throw new AssertionError("missing " + type + " frame");
    }
  }
}
