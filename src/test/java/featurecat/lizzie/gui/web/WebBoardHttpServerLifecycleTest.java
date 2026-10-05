package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class WebBoardHttpServerLifecycleTest {
  private WebBoardHttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop();
  }

  @Test
  void malformedEscapesAreBadRequestsAndDoNotBreakLaterRequests() throws Exception {
    start(2, 2, 10000);
    assertStatus(400, "GET /%zz HTTP/1.1\r\nHost: localhost\r\n\r\n");
    assertStatus(400, "GET /% HTTP/1.1\r\n\r\n");
    assertStatus(400, "GET /%00 HTTP/1.1\r\n\r\n");
    assertStatus(404, "GET /missing-lifecycle-resource HTTP/1.1\r\n\r\n");
  }

  @Test
  void rejectsOversizedRequestLineWithoutWaitingForItsTerminator() throws Exception {
    start(1, 1, 10000);
    assertStatus(414, "GET /" + "x".repeat(5000));
    assertStatus(404, "GET /missing-lifecycle-resource HTTP/1.1\r\n\r\n");
  }

  @Test
  void boundsBothHeaderBytesAndHeaderCount() throws Exception {
    start(1, 2, 10000);
    assertStatus(431, "GET / HTTP/1.1\r\nX: " + "x".repeat(17000));
    assertStatus(431, "GET / HTTP/1.1\r\n" + "X: y\r\n".repeat(65) + "\r\n");
    assertStatus(431, "GET / HTTP/1.1\r\n" + ("X: " + "x".repeat(4000) + "\r\n").repeat(5));
    assertStatus(404, "GET /missing-lifecycle-resource HTTP/1.1\r\n\r\n");
  }

  @Test
  void incompleteAndMalformedHeadersNeverDispatchAResource() throws Exception {
    start(1, 2, 10000);
    try (Socket socket = connect()) {
      send(socket, "GET / HTTP/1.1\r\nHost: localhost\r\n");
      socket.shutdownOutput();
      assertTrue(status(socket).startsWith("HTTP/1.1 400"));
    }
    assertStatus(400, "GET / HTTP/1.1\r\ninvalid-header\r\n\r\n");
    assertStatus(400, "GET / HTTP/9.9\r\n\r\n");
    assertStatus(405, "POST / HTTP/1.1\r\n\r\n");
    assertStatus(403, "GET /%2e%2e/pom.xml HTTP/1.1\r\n\r\n");
  }

  @Test
  void completeRequestsStillServeUtf8IndexWithConfiguredWebSocketPort() throws Exception {
    start(1, 2, 10000);
    server.setWsPort(43210);
    try (Socket socket = connect()) {
      send(socket, "GET /?test=lifecycle HTTP/1.1\r\nHost: localhost\r\n\r\n");
      String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(response.startsWith("HTTP/1.1 200 OK"));
      assertTrue(response.contains("Content-Type: text/html; charset=utf-8"));
      assertTrue(response.contains("Connection: close\r\n"));
      assertTrue(response.contains("43210"));
      assertFalse(response.contains("__WS_PORT__"));
      int bodyStart = response.indexOf("\r\n\r\n") + 4;
      int bytes = response.substring(bodyStart).getBytes(StandardCharsets.UTF_8).length;
      assertTrue(response.contains("Content-Length: " + bytes + "\r\n"));
    }
  }

  @Test
  void queueSaturationClosesOnlyRejectedClientAndStopClosesActiveAndQueuedClients()
      throws Exception {
    start(1, 1, 10000);
    try (Socket active = connect(); Socket queued = connect(); Socket rejected = connect()) {
      // Rejection proves both slots were accepted before stop; no timing sleep is needed.
      assertClosed(rejected);
      server.stop();
      assertClosed(active);
      assertClosed(queued);
    }
    server.stop();
    server.start();
    int port = server.getPort();
    server.start();
    assertEquals(port, server.getPort(), "repeated start must not replace the live run");
    assertStatus(404, "GET /missing-lifecycle-resource HTTP/1.1\r\n\r\n");
  }

  @Test
  void tricklingBytesCannotExtendTheWholeRequestDeadline() throws Exception {
    start(1, 1, 1500);
    AtomicInteger written = new AtomicInteger();
    var writer = Executors.newSingleThreadScheduledExecutor();
    try (Socket socket = connect()) {
      send(socket, "GET /");
      writer.scheduleAtFixedRate(() -> {
        try {
          send(socket, "x");
          written.incrementAndGet();
        } catch (IOException e) {
          // Expected once the server enforces the whole-request deadline.
        }
      }, 0, 50, TimeUnit.MILLISECONDS);
      assertClosed(socket);
      assertTrue(written.get() >= 2, "fixture must make progress rather than only time out an idle read");
    } finally {
      writer.shutdownNow();
      assertTrue(writer.awaitTermination(2, TimeUnit.SECONDS));
    }
    assertStatus(404, "GET /missing-lifecycle-resource HTTP/1.1\r\n\r\n");
  }

  @Test
  void stopRacingAcceptanceCannotCloseOrLeakIntoTheNextRun() throws Exception {
    start(1, 1, 10000);
    var stopper = Executors.newSingleThreadExecutor();
    try {
      for (int attempt = 0; attempt < 10; attempt++) {
        try (Socket socket = connect()) {
          stopper.submit(server::stop).get(3, TimeUnit.SECONDS);
          assertClosed(socket);
        }
        server.start();
        assertStatus(404, "GET /missing-lifecycle-resource HTTP/1.1\r\n\r\n");
      }
    } finally {
      stopper.shutdownNow();
      assertTrue(stopper.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  private void start(int workers, int queuedClients, int timeoutMillis) throws IOException {
    server = new WebBoardHttpServer(0, workers, queuedClients, timeoutMillis);
    server.start();
    assertTrue(server.getPort() > 0);
  }

  private Socket connect() throws IOException {
    Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 2000);
      socket.setSoTimeout(3000);
      return socket;
    } catch (IOException e) {
      socket.close();
      throw e;
    }
  }

  private void assertStatus(int expected, String request) throws IOException {
    try (Socket socket = connect()) {
      send(socket, request);
      String firstLine = status(socket);
      assertTrue(firstLine.startsWith("HTTP/1.1 " + expected + " "), firstLine);
    }
  }

  private static String status(Socket socket) throws IOException {
    String line = new BufferedReader(new InputStreamReader(
        socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
    assertNotNull(line, "server closed before sending a status line");
    return line;
  }

  private static void send(Socket socket, String request) throws IOException {
    socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  private static void assertClosed(Socket socket) throws IOException {
    try {
      assertEquals(-1, socket.getInputStream().read(), "connection must close without an application response");
    } catch (SocketException e) {
      // TCP reset also proves closure. SocketTimeoutException is deliberately not accepted.
    }
  }
}
