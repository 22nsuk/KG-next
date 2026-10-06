package featurecat.lizzie.gui.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

public class WebBoardServer extends WebSocketServer {
  @FunctionalInterface
  public interface MessageHandler {
    void handle(WebSocket conn, JSONObject message);
  }

  private final CompletableFuture<Void> startup = new CompletableFuture<>();
  private final int maxConnections;
  private final WebBoardClientUpdates updates = new WebBoardClientUpdates();
  private volatile MessageHandler messageHandler;

  public WebBoardServer(InetSocketAddress address, int maxConnections) {
    super(address);
    if (maxConnections < 1) throw new IllegalArgumentException("maxConnections must be positive");
    this.maxConnections = maxConnections;
    setReuseAddr(true);
  }

  public void setMessageHandler(MessageHandler h) {
    this.messageHandler = h;
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    if (getConnections().size() > maxConnections) {
      conn.close(1013, "Max connections reached");
      return;
    }
    updates.connected(conn);
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    updates.disconnected(conn);
  }

  @Override
  public void stop(int timeout, String closeMessage) throws InterruptedException {
    messageHandler = null;
    startup.completeExceptionally(new IOException("WebSocket server stopped before startup"));
    updates.close();
    super.stop(timeout, closeMessage);
  }

  @Override
  public void onMessage(WebSocket conn, String message) {
    MessageHandler h = messageHandler;
    if (h == null) return;
    try {
      JSONObject json = new JSONObject(message);
      h.handle(conn, json);
    } catch (org.json.JSONException ignored) {
    }
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {
    if (conn == null) startup.completeExceptionally(ex);
  }

  @Override
  public void onStart() {
    startup.complete(null);
  }

  /** start() only schedules binding; callers must observe readiness before publishing a URL. */
  void awaitStarted(long timeout, TimeUnit unit) throws IOException, InterruptedException {
    try {
      startup.get(timeout, unit);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof IOException) throw (IOException) cause;
      throw new IOException("WebSocket startup failed", cause);
    } catch (TimeoutException timeoutFailure) {
      throw new IOException("WebSocket startup timed out", timeoutFailure);
    }
  }

  public void broadcastMessage(String json) {
    broadcast(json);
  }

  public void broadcastFullState(String json) {
    updates.fullState(json);
  }

  public void broadcastAnalysis(String json) {
    updates.analysis(json);
  }

  public void broadcastTrialState(String json) {
    updates.trialState(json);
  }

  public void broadcastHistory(String json) {
    updates.history(json);
  }

  public void sendToConnection(WebSocket conn, String json) {
    if (conn != null && conn.isOpen()) {
      try {
        conn.send(json);
      } catch (WebsocketNotConnectedException disconnected) {
        // The peer closed between isOpen and send. It may resume with its private credential.
      }
    }
  }
}
