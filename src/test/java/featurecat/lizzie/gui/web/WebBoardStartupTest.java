package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(20)
class WebBoardStartupTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({
    "http-port,65536",
    "ws-port,65536",
    "ws-port,-1",
    "http-port,4294967296",
    "ws-port,1.5",
    "max-connections,0",
    "max-connections,4294967296"
  })
  void invalidConfigurationDoesNotOpenTheHttpListener(String key, String number) throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false, true);
        ServerSocket available = new ServerSocket(0)) {
      int http = available.getLocalPort();
      available.close();
      configure(f, http, 0);
      Lizzie.config.config.getJSONObject("web-board").put(key, new java.math.BigDecimal(number));
      assertFalse(f.manager.start());
      assertStopped(f.manager);
      f.manager.stop();
      assertBindable(http);
    }
  }

  @Test
  void exhaustedWebSocketPortsRollBackHttpAndPermitRetry() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false, true)) {
      List<ServerSocket> occupied = consecutivePorts(10);
      int base = occupied.get(0).getLocalPort();
      int http = unusedPort();
      try {
        configure(f, http, base);
        assertFalse(f.manager.start());
        assertStopped(f.manager);
        assertBindable(http);
        f.manager.stop();
      } finally {
        for (ServerSocket socket : occupied) socket.close();
      }
      assertTrue(f.manager.start());
      assertTrue(f.manager.isRunning());
      assertEquals(base, f.manager.getWsPort());
      f.manager.stop();
      assertBindable(http);
      assertBindable(base);
    }
  }

  @Test
  void actualBindFallbackReadyPortsAndRestartRemainConsistent() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false, true);
        ServerSocket httpOccupied = new ServerSocket(0);
        ServerSocket wsOccupied = new ServerSocket(0)) {
      configure(f, httpOccupied.getLocalPort(), wsOccupied.getLocalPort());
      assertTrue(f.manager.start());
      int http = URI.create(f.manager.getAccessUrl()).getPort();
      int ws = f.manager.getWsPort();
      assertNotEquals(httpOccupied.getLocalPort(), http);
      assertNotEquals(wsOccupied.getLocalPort(), ws);
      try (Socket request = new Socket("127.0.0.1", http)) {
        request.setSoTimeout(3000);
        request
            .getOutputStream()
            .write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        String response =
            new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(response.contains("window.WS_PORT = " + ws), response);
      }
      WebBoardDataCollector old = f.manager.getCollector();
      assertTrue(f.manager.start());
      assertSame(old, f.manager.getCollector(), "start must be idempotent");
      f.manager.stop();
      assertStopped(f.manager);
      assertNull(old.scheduleOnExecutor(() -> fail("closed collector"), 0, TimeUnit.SECONDS));
      assertBindable(http);
      assertBindable(ws);
      configure(f, 0, 0);
      assertTrue(f.manager.start());
      assertNotSame(old, f.manager.getCollector());
      assertTrue(f.manager.getWsPort() > 0);
      f.manager.stop();
      f.manager.stop();
      assertStopped(f.manager);
    }
  }

  @Test
  void exitCallbackFailureCannotKeepEitherListenerAlive() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false, true)) {
      configure(f, 0, 0);
      assertTrue(f.manager.start());
      int http = URI.create(f.manager.getAccessUrl()).getPort();
      int ws = f.manager.getWsPort();
      assertTrue(f.manager.enterTrial("desktop", f.anchor));
      f.manager.setDesktopRefresherForTest(
          () -> {
            throw new IllegalStateException("fixture exit");
          });
      assertThrows(IllegalStateException.class, f.manager::stop);
      assertStopped(f.manager);
      assertBindable(http);
      assertBindable(ws);
      assertEquals("", f.manager.getCurrentTrialOwner());
    }
  }

  @Test
  void startupWaitReportsBindFailureTimeoutAndStopRatherThanSuccess() throws Exception {
    try (ServerSocket occupied = new ServerSocket(0)) {
      WebBoardServer server =
          new WebBoardServer(new InetSocketAddress("127.0.0.1", occupied.getLocalPort()), 1);
      try {
        server.start();
        assertThrows(java.net.BindException.class, () -> server.awaitStarted(3, TimeUnit.SECONDS));
      } finally {
        server.stop(1000);
      }
    }
    WebBoardServer unstarted = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 1);
    try {
      assertTrue(
          assertThrows(IOException.class, () -> unstarted.awaitStarted(1, TimeUnit.MILLISECONDS))
              .getMessage()
              .contains("timed out"));
      unstarted.stop(1000);
      assertTrue(
          assertThrows(IOException.class, () -> unstarted.awaitStarted(3, TimeUnit.SECONDS))
              .getMessage()
              .contains("stopped"));
    } finally {
      unstarted.stop(1000);
    }
  }

  @Test
  void interruptedStartupRollsBackAndPreservesTheInterrupt() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture(false, true)) {
      int http = unusedPort();
      configure(f, http, 0);
      Thread.currentThread().interrupt();
      try {
        assertFalse(f.manager.start());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertStopped(f.manager);
      assertBindable(http);
      assertTrue(f.manager.start());
      f.manager.stop();
    }
  }

  private void configure(WebTrialTestFixture f, int http, int ws) throws Exception {
    Lizzie.config = ConfigTestHelper.createForTests(directory);
    Lizzie.config.config =
        new JSONObject()
            .put(
                "web-board",
                new JSONObject()
                    .put("http-port", http)
                    .put("ws-port", ws)
                    .put("max-connections", 3));
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    StateFrame frame =
        (StateFrame) ((sun.misc.Unsafe) field.get(null)).allocateInstance(StateFrame.class);
    frame.display = f.anchor;
    Lizzie.frame = frame;
  }

  private static void assertStopped(WebBoardManager manager) {
    assertFalse(manager.isRunning());
    assertNull(manager.getAccessUrl());
    assertEquals(0, manager.getWsPort());
    assertNull(manager.getCollector());
  }

  private static int unusedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void assertBindable(int port) throws IOException {
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress(port));
    }
  }

  private static List<ServerSocket> consecutivePorts(int count) throws IOException {
    for (int attempt = 0; attempt < 100; attempt++) {
      List<ServerSocket> sockets = new ArrayList<>();
      try {
        ServerSocket first = new ServerSocket(0);
        sockets.add(first);
        int base = first.getLocalPort();
        if (base + count > 65535) continue;
        for (int i = 1; i < count; i++) sockets.add(new ServerSocket(base + i));
        return sockets;
      } catch (java.net.BindException occupied) {
        // Retry only the fixture's port-range acquisition, not a failed application assertion.
      } finally {
        if (sockets.size() != count) for (ServerSocket socket : sockets) socket.close();
      }
    }
    throw new IOException("could not reserve a consecutive test port range");
  }

  static class StateFrame extends LizzieFrame {
    BoardHistoryNode display;

    @Override
    public BoardHistoryNode getDisplayNode() {
      return display;
    }
  }
}
