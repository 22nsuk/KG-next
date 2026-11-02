package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.socket.client.IO;
import io.socket.client.Manager;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ZhiziGtpTransportStartupTest {
  enum Stop {
    CLOSE,
    ABORT;

    void apply(ZhiziGtpTransport transport) {
      if (this == CLOSE) transport.close();
      else transport.abort();
    }
  }

  @ParameterizedTest
  @EnumSource(Stop.class)
  void terminationInterruptsPendingTokenRequest(Stop stop) throws Exception {
    CountDownLatch fetching = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger requests = new AtomicInteger();
    try (ZhiziGtpTransport transport =
            new ZhiziGtpTransport(
                api(
                    () -> {
                      requests.incrementAndGet();
                      fetching.countDown();
                      release.await();
                      return token();
                    }),
                "local-test",
                "");
        Starting start = new Starting(transport)) {
      try {
        await(fetching);
        stop.apply(transport);
        start.failed();
        assertEquals(1, requests.get());
        assertTerminal(transport);
      } finally {
        release.countDown();
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Stop.class)
  void lateTokenFailureAfterTerminationDoesNotRequestAnotherToken(Stop stop) throws Exception {
    CountDownLatch fetching = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger requests = new AtomicInteger();
    try (ZhiziGtpTransport transport =
            new ZhiziGtpTransport(
                api(
                    () -> {
                      if (requests.incrementAndGet() > 1) throw new IOException("401 unauthorized");
                      fetching.countDown();
                      awaitIgnoringInterrupt(release);
                      throw new IOException("temporary network failure");
                    }),
                "local-test",
                "");
        Starting start = new Starting(transport)) {
      try {
        await(fetching);
        stop.apply(transport);
        release.countDown();
        start.failed(8_000L);
        assertEquals(1, requests.get(), "shutdown must suppress transient startup retries");
        assertTerminal(transport);
      } finally {
        release.countDown();
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Stop.class)
  void lateTokenSuccessAfterTerminationDoesNotCreateSocket(Stop stop) throws Exception {
    CountDownLatch fetching = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger requests = new AtomicInteger();
    try (ZhiziGtpTransport transport =
            new ZhiziGtpTransport(
                api(
                    () -> {
                      requests.incrementAndGet();
                      fetching.countDown();
                      awaitIgnoringInterrupt(release);
                      return token();
                    }),
                "local-test",
                "") {
              @Override
              Socket createSocket(String url, IO.Options options) {
                throw new AssertionError("terminated transport must not allocate a socket");
              }
            };
        Starting start = new Starting(transport)) {
      try {
        await(fetching);
        stop.apply(transport);
        release.countDown();
        start.failed();
        assertEquals(1, requests.get());
        assertTerminal(transport);
      } finally {
        release.countDown();
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Stop.class)
  void terminationWakesRetryBackoff(Stop stop) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    try (ZhiziGtpTransport transport =
            new ZhiziGtpTransport(
                api(
                    () -> {
                      requests.incrementAndGet();
                      throw new IOException("temporary network failure");
                    }),
                "local-test",
                "");
        Starting start = new Starting(transport)) {
      awaitRetrySleep(start.thread);
      stop.apply(transport);
      start.failed(1_000L);
      assertEquals(1, requests.get());
      assertTerminal(transport);
    }
  }

  @ParameterizedTest
  @EnumSource(Stop.class)
  void socketCreatedAfterTerminationIsDisposedWithoutPublication(Stop stop) throws Exception {
    try (GatedTransport transport = new GatedTransport(Gate.CONSTRUCTION);
        Starting start = new Starting(transport)) {
      try {
        await(transport.entered);
        stop.apply(transport);
        transport.release.countDown();
        start.failed();
        assertEquals(0, transport.controlledSocket.connects.get());
        assertTrue(transport.controlledSocket.disposed);
        assertTrue(transport.client.dispatcher().executorService().isShutdown());
        assertTerminal(transport);
      } finally {
        transport.release.countDown();
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Stop.class)
  void terminationBetweenReadyAndActivationCannotReopenTransport(Stop stop) throws Exception {
    try (GatedTransport transport = new GatedTransport(Gate.ACTIVATION);
        Starting start = new Starting(transport)) {
      try {
        await(transport.entered);
        stop.apply(transport);
        transport.release.countDown();
        start.failed();
        assertTrue(transport.controlledSocket.disposed);
        assertTrue(transport.client.dispatcher().executorService().isShutdown());
        assertTerminal(transport);
      } finally {
        transport.release.countDown();
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Stop.class)
  void terminationWakesReadyWaitAndRejectsLateCallback(Stop stop) throws Exception {
    try (GatedTransport transport = new GatedTransport(Gate.READY);
        Starting start = new Starting(transport)) {
      await(transport.entered);
      stop.apply(transport);
      transport.controlledSocket.deliverReady();
      start.failed();
      assertTerminal(transport);
    }
  }

  @Test
  void abortDisposesPublishedClientWhileRealGracefulQuitPathIsBlocked() throws Exception {
    try (GatedTransport transport = new GatedTransport(Gate.GRACEFUL_QUIT)) {
      transport.start();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread graceful =
          new Thread(
              () -> {
                try {
                  transport.close();
                } catch (Throwable error) {
                  failure.set(error);
                }
              },
              "controlled-zhizi-quit");
      Thread abort = new Thread(transport::abort, "controlled-zhizi-abort");
      try {
        graceful.start();
        await(transport.entered);
        abort.start();
        abort.join(1_000L);
        assertFalse(abort.isAlive(), "abort must not wait for a blocked quit emission");
        assertTrue(transport.controlledSocket.disposed);
        assertTrue(transport.client.dispatcher().executorService().isShutdown());
        assertTerminal(transport);
      } finally {
        transport.release.countDown();
        graceful.join(3_000L);
        abort.join(3_000L);
        assertFalse(graceful.isAlive());
        assertFalse(abort.isAlive());
        assertNull(failure.get());
      }
    }
  }

  @Test
  void transientStartupFailuresStillUseThreeAttempts() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    try (ZhiziGtpTransport transport =
        new ZhiziGtpTransport(
            api(
                () -> {
                  requests.incrementAndGet();
                  throw new IOException("temporary network failure");
                }),
            "local-test",
            "")) {
      assertThrows(IOException.class, transport::start);
      assertEquals(3, requests.get());
      assertTerminal(transport);
    }
  }

  @Test
  void fatalFailureAndInterruptDoNotRetry() throws Exception {
    for (boolean interrupted : new boolean[] {false, true}) {
      AtomicInteger requests = new AtomicInteger();
      try (ZhiziGtpTransport transport =
              new ZhiziGtpTransport(
                  api(
                      () -> {
                        requests.incrementAndGet();
                        if (interrupted) throw new InterruptedException("controlled interruption");
                        throw new IOException("401 unauthorized");
                      }),
                  "local-test",
                  "");
          Starting start = new Starting(transport)) {
        start.failed();
        assertEquals(interrupted, start.interrupted);
        assertEquals(1, requests.get());
        assertTerminal(transport);
      }
    }
  }

  @Test
  void realSocketIoLoopbackPreservesReadyCommandAndOutputWiring() throws Exception {
    CountDownLatch listening = new CountDownLatch(1);
    AtomicReference<Exception> serverFailure = new AtomicReference<>();
    WebSocketServer server =
        new WebSocketServer(new InetSocketAddress("127.0.0.1", 0)) {
          @Override
          public void onOpen(WebSocket connection, ClientHandshake handshake) {
            connection.send(
                "0{\"sid\":\"local-session\",\"upgrades\":[],\"pingInterval\":10000,\"pingTimeout\":10000}");
          }

          @Override
          public void onMessage(WebSocket connection, String message) {
            if (message.startsWith("40")) {
              connection.send("40{\"sid\":\"local-namespace\"}");
              connection.send("42[\"ready\"]");
            } else if (message.equals("42[\"stdin\",\"name\\n\"]")) {
              connection.send("42[\"stdout\",\"= loopback\\n\\n\"]");
            }
          }

          @Override
          public void onClose(WebSocket connection, int code, String reason, boolean remote) {}

          @Override
          public void onError(WebSocket connection, Exception error) {
            serverFailure.set(error);
          }

          @Override
          public void onStart() {
            listening.countDown();
          }
        };
    server.start();
    try {
      await(listening);
      try (ZhiziGtpTransport transport =
              new ZhiziGtpTransport(
                  api(
                      () ->
                          new ZhiziApiClient.SocketToken(
                              "synthetic-token", "http://127.0.0.1:" + server.getPort())),
                  "local-test",
                  "");
          Starting start = new Starting(transport)) {
        start.thread.join(3_000L);
        assertFalse(start.thread.isAlive());
        assertNull(start.failure.get());
        assertTrue(transport.isOpen());
        transport.stdin().write("name\n".getBytes(StandardCharsets.UTF_8));
        transport.stdin().flush();
        // Bound the real output wait and let transport closure release it on any failure.
        var reader = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
          var response =
              reader.submit(
                  () -> new String(transport.stdout().readNBytes(12), StandardCharsets.UTF_8));
          assertEquals("= loopback\n\n", response.get(3, TimeUnit.SECONDS));
        } finally {
          transport.close();
          reader.shutdownNow();
          assertTrue(reader.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertTerminal(transport);
      }
      assertNull(serverFailure.get());
    } finally {
      server.stop(3_000);
    }
  }

  @Test
  void normalStartupBindsCommandsAndPreservesOutput() throws Exception {
    try (GatedTransport transport = new GatedTransport(Gate.NONE)) {
      transport.start();
      assertTrue(transport.isOpen());
      transport.stdin().write("name\n".getBytes(StandardCharsets.UTF_8));
      transport.stdin().flush();
      assertEquals(List.of("name\n"), transport.controlledSocket.commands);
      transport.controlledSocket.deliver("stdout", "= local-engine\n\n");
      assertEquals(
          "= local-engine\n\n",
          new String(transport.stdout().readNBytes(16), StandardCharsets.UTF_8));
      transport.close();
      assertTerminal(transport);
      assertEquals(List.of("name\n", "quit\n"), transport.controlledSocket.commands);
    }
  }

  private static void assertTerminal(ZhiziGtpTransport transport) throws Exception {
    assertFalse(transport.isOpen());
    assertNull(field(transport, "socket"));
    assertNull(field(transport, "socketHttpClient"));
    assertEquals(
        ZhiziGtpTransport.SessionState.CLOSED,
        ((ZhiziGtpTransport.SessionLifecycle) field(transport, "lifecycle")).state());
    assertEquals(-1, transport.stdout().read());
    assertEquals(-1, transport.stderr().read());
    assertThrows(IOException.class, transport::start);
    transport.stdin().write("name\n".getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, transport.stdin()::flush);
  }

  private static Object field(Object transport, String name) throws Exception {
    Field field = ZhiziGtpTransport.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(transport);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(3, TimeUnit.SECONDS), "expected startup boundary was not reached");
  }

  private static void awaitIgnoringInterrupt(CountDownLatch latch) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (latch.getCount() != 0) {
      try {
        assertTrue(latch.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      } catch (InterruptedException ignored) {
        // Model a late network completion even when cancellation has already been requested.
      }
    }
  }

  private static void awaitRetrySleep(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == Thread.State.TIMED_WAITING
          && java.util.Arrays.stream(thread.getStackTrace())
              .anyMatch(
                  frame ->
                      frame.getClassName().equals(TimeUnit.class.getName())
                          && frame.getMethodName().equals("sleep"))) return;
      Thread.yield();
    }
    fail("startup did not enter retry backoff");
  }

  private interface TokenRequest {
    ZhiziApiClient.SocketToken fetch() throws IOException, InterruptedException;
  }

  private static ZhiziApiClient api(TokenRequest request) {
    return new ZhiziApiClient(URI.create("http://127.0.0.1"), HttpClient.newHttpClient()) {
      @Override
      public SocketToken fetchSocketioToken(String account, String args)
          throws IOException, InterruptedException {
        return request.fetch();
      }
    };
  }

  private static ZhiziApiClient.SocketToken token() {
    return new ZhiziApiClient.SocketToken("synthetic-token", "http://127.0.0.1:1");
  }

  private static final class Starting implements AutoCloseable {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread thread;
    volatile boolean interrupted;

    Starting(ZhiziGtpTransport transport) {
      thread =
          new Thread(
              () -> {
                try {
                  transport.start();
                } catch (Throwable error) {
                  failure.set(error);
                } finally {
                  interrupted = Thread.currentThread().isInterrupted();
                }
              },
              "controlled-zhizi-start");
      thread.start();
    }

    void failed() throws InterruptedException {
      failed(2_000L);
    }

    void failed(long timeoutMillis) throws InterruptedException {
      thread.join(timeoutMillis);
      assertFalse(thread.isAlive(), "terminated transport must release startup wait");
      assertInstanceOf(IOException.class, failure.get());
    }

    @Override
    public void close() throws InterruptedException {
      thread.interrupt();
      thread.join(3_000L);
      assertFalse(thread.isAlive(), "startup test thread leaked");
    }
  }

  enum Gate {
    NONE,
    CONSTRUCTION,
    READY,
    ACTIVATION,
    GRACEFUL_QUIT
  }

  private static final class GatedTransport extends ZhiziGtpTransport {
    final Gate gate;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    ControlledSocket controlledSocket;
    OkHttpClient client;

    GatedTransport(Gate gate) throws IOException {
      super(api(ZhiziGtpTransportStartupTest::token), "local-test", "");
      this.gate = gate;
    }

    @Override
    Socket createSocket(String url, IO.Options options) throws URISyntaxException {
      client = (OkHttpClient) options.callFactory;
      controlledSocket = new ControlledSocket(this, options);
      if (gate == Gate.CONSTRUCTION) pause();
      return controlledSocket;
    }

    void pause() {
      entered.countDown();
      try {
        awaitIgnoringInterrupt(release);
      } catch (InterruptedException error) {
        throw new AssertionError(error);
      }
    }
  }

  /** Only network I/O is substituted; production start, listeners, lifecycle and cleanup run. */
  private static final class ControlledSocket extends Socket {
    final GatedTransport owner;
    final AtomicInteger connects = new AtomicInteger();
    final List<String> commands = new CopyOnWriteArrayList<>();
    List<Emitter.Listener> ready;
    volatile boolean disposed;

    ControlledSocket(GatedTransport owner, IO.Options options) {
      super(new Manager(URI.create("http://127.0.0.1:1"), options), "/", options);
      this.owner = owner;
    }

    @Override
    public Socket connect() {
      connects.incrementAndGet();
      deliver(Socket.EVENT_CONNECT);
      ready = List.copyOf(listeners("ready"));
      if (owner.gate == Gate.READY) owner.entered.countDown();
      else deliverReady();
      return this;
    }

    void deliverReady() {
      ready.forEach(listener -> listener.call());
    }

    void deliver(String event, Object... arguments) {
      listeners(event).forEach(listener -> listener.call(arguments));
    }

    @Override
    public boolean connected() {
      if (owner.gate == Gate.ACTIVATION) owner.pause();
      return !disposed;
    }

    @Override
    public Emitter emit(String event, Object... arguments) {
      if (event.equals("stdin")) commands.add((String) arguments[0]);
      if (event.equals("stdin")
          && arguments[0].equals("quit\n")
          && owner.gate == Gate.GRACEFUL_QUIT) owner.pause();
      return this;
    }

    @Override
    public Socket disconnect() {
      disposed = true;
      return this;
    }

    @Override
    public Socket close() {
      disposed = true;
      return this;
    }
  }
}
