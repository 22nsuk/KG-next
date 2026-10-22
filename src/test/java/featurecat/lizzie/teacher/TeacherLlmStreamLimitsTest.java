package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Loopback transport tests: the server stays blocked until the client has returned. */
class TeacherLlmStreamLimitsTest {
  private final CountDownLatch release = new CountDownLatch(1);
  private final ExecutorService serverWorkers = Executors.newCachedThreadPool();
  private final ExecutorService callers = Executors.newSingleThreadExecutor();
  private final AtomicInteger chatCalls = new AtomicInteger();
  private final AtomicInteger responsesCalls = new AtomicInteger();
  private HttpServer server;
  private String baseUrl;
  private final HttpClient http = HttpClient.newBuilder().build();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(serverWorkers);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void stop() throws InterruptedException {
    release.countDown();
    server.stop(0);
    callers.shutdownNow();
    serverWorkers.shutdownNow();
    assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
    assertTrue(serverWorkers.awaitTermination(5, TimeUnit.SECONDS));
  }

  @ParameterizedTest
  @CsvSource({"false,200", "true,200", "false,500", "true,500"})
  void idleTimeoutUnblocksSuccessAndErrorBodiesWithoutCancellingTheToken(
      boolean fallback, int status) throws Exception {
    CountDownLatch headersSent = new CountDownLatch(1);
    serve(fallback, exchange -> hold(exchange, status, "", headersSent));
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    Future<String> result = request(client(5_000, 200), cancellation, new ArrayList<>());
    assertTrue(headersSent.await(3, TimeUnit.SECONDS));

    assertInstanceOf(HttpTimeoutException.class, failed(result));
    assertFalse(cancellation.isCancelled(), "A limit is a visible failure, not user cancellation");
    assertEquals(1, release.getCount(), "Server must still be holding its response open");
    assertEquals(1, chatCalls.get());
    assertEquals(fallback ? 1 : 0, responsesCalls.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {": heartbeat\n\n", "x"})
  void absoluteDeadlineStopsContinuousCommentsAndUnfinishedLines(String chunk) throws Exception {
    CountDownLatch sent = new CountDownLatch(1);
    serve(
        false,
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream output = exchange.getResponseBody()) {
            while (release.getCount() != 0) {
              output.write(chunk.getBytes(StandardCharsets.UTF_8));
              output.flush();
              sent.countDown();
              if (awaitRelease(25)) {
                break;
              }
            }
          } catch (IOException clientClosed) {
          }
        });
    Future<String> result = request(client(1_200, 5_000), null, new ArrayList<>());
    assertTrue(sent.await(3, TimeUnit.SECONDS));
    assertInstanceOf(HttpTimeoutException.class, failed(result));
    assertEquals(1, release.getCount());
    assertEquals(0, responsesCalls.get());
  }

  @ParameterizedTest
  @CsvSource({"false,200", "false,500", "true,200", "true,500"})
  void cancellationUnblocksAllBodyStatuses(boolean fallback, int status) throws Exception {
    CountDownLatch sent = new CountDownLatch(1);
    serve(fallback, exchange -> hold(exchange, status, "data: ", sent));
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    Future<String> result = request(client(10_000, 5_000), cancellation, new ArrayList<>());
    assertTrue(sent.await(3, TimeUnit.SECONDS));
    cancellation.cancel();
    assertInstanceOf(CancellationException.class, failed(result));
    assertEquals(1, release.getCount());
    assertEquals(fallback ? 1 : 0, responsesCalls.get());
  }

  @Test
  void cancellationBeforeSendMakesNoRequest() throws Exception {
    serve(false, exchange -> reply(exchange, delta("unexpected") + done(false)));
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    cancellation.cancel();
    assertInstanceOf(
        CancellationException.class,
        failed(request(client(5_000, 500), cancellation, new ArrayList<>())));
    assertEquals(0, chatCalls.get());
    assertEquals(0, responsesCalls.get());
  }

  @Test
  void cancellationUnblocksHeaderWait() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    serve(
        false,
        exchange -> {
          entered.countDown();
          awaitRelease(10_000);
          exchange.close();
        });
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    Future<String> result = request(client(10_000, 500), cancellation, new ArrayList<>());
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    cancellation.cancel();
    assertInstanceOf(CancellationException.class, failed(result));
    assertEquals(1, release.getCount());
  }

  @Test
  void totalDeadlineAlsoCoversHeaderWait() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    serve(
        false,
        exchange -> {
          entered.countDown();
          awaitRelease(10_000);
          exchange.close();
        });
    Future<String> result = request(client(1_200, 5_000), null, new ArrayList<>());
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    assertInstanceOf(HttpTimeoutException.class, failed(result));
    assertEquals(1, release.getCount());
  }

  @Test
  void endpointFallbackDoesNotRestartTheTotalDeadline() throws Exception {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          chatCalls.incrementAndGet();
          awaitRelease(800);
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.createContext(
        "/v1/responses",
        exchange -> {
          responsesCalls.incrementAndGet();
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream output = exchange.getResponseBody()) {
            for (int i = 0; i < 40; i++) {
              output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
              output.flush();
              if (awaitRelease(40)) {
                return;
              }
            }
            output.write((responseDelta("ok") + done(true)).getBytes(StandardCharsets.UTF_8));
          } catch (IOException clientClosed) {
          }
        });
    server.start();
    assertInstanceOf(
        HttpTimeoutException.class, failed(request(client(2_000, 5_000), null, new ArrayList<>())));
    assertEquals(1, chatCalls.get());
    assertEquals(1, responsesCalls.get());
  }

  @Test
  void completedRequestsDeadlineCannotAbortTheNextCallUsingTheSameToken() throws Exception {
    serve(
        false,
        exchange -> {
          if (chatCalls.get() == 1) {
            reply(exchange, delta("first") + done(false));
            return;
          }
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream output = exchange.getResponseBody()) {
            for (int i = 0; i < 40; i++) {
              output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
              output.flush();
              if (awaitRelease(40)) {
                return;
              }
            }
            output.write((delta("second") + done(false)).getBytes(StandardCharsets.UTF_8));
          }
        });
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    assertEquals(
        "first",
        request(client(1_000, 500), cancellation, new ArrayList<>()).get(5, TimeUnit.SECONDS));
    assertEquals(
        "second",
        request(client(5_000, 500), cancellation, new ArrayList<>()).get(5, TimeUnit.SECONDS));
    assertFalse(cancellation.isCancelled());
  }

  @ParameterizedTest
  @CsvSource({
    "false,line",
    "true,line",
    "false,event",
    "true,event",
    "false,body",
    "true,body",
    "false,text",
    "true,text"
  })
  void rejectsEachSizeLimitBeforePublishingExcessText(boolean fallback, String limit)
      throws Exception {
    String text = "한글😀".repeat(8); // 32 UTF-16 units, 80 UTF-8 bytes.
    String chunk = fallback ? responseDelta(text) : delta(text);
    String payload =
        switch (limit) {
          case "line" -> ":" + "x".repeat(1_024); // Deliberately no newline or terminal event.
          case "event" -> (":" + "x".repeat(999) + "\n").repeat(5);
          case "body" -> ": heartbeat\n\n".repeat(700);
          case "text" -> chunk.repeat(3);
          default -> throw new AssertionError(limit);
        };
    serve(
        fallback,
        exchange -> {
          if (limit.equals("line")) {
            hold(exchange, 200, payload, new CountDownLatch(0));
          } else {
            reply(exchange, payload + done(fallback));
          }
        });
    List<String> displayed = new CopyOnWriteArrayList<>();
    IOException error =
        assertInstanceOf(IOException.class, failed(request(client(5_000, 2_000), null, displayed)));
    assertFalse(error instanceof HttpTimeoutException);
    assertTrue(error.getMessage().contains("size limit"), error.getMessage());
    assertEquals(limit.equals("text") ? List.of(text, text) : List.of(), displayed);
    assertEquals(1, chatCalls.get());
    assertEquals(fallback ? 1 : 0, responsesCalls.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"\n", "\r\n", "\r"})
  void acceptsBoundariesUnicodeAndExistingLineSeparators(String newline) throws Exception {
    String text = "한글😀".repeat(16); // Exactly the configured 64 UTF-16-unit output limit.
    String payload =
        (":" + "x".repeat(1_023) + "\n\n" + delta(text) + done(false)).replace("\n", newline);
    serve(false, exchange -> reply(exchange, payload));
    List<String> displayed = new CopyOnWriteArrayList<>();
    assertEquals(text, request(client(5_000, 2_000), null, displayed).get(5, TimeUnit.SECONDS));
    assertEquals(List.of(text), displayed);
  }

  @ParameterizedTest
  @CsvSource({"event,0", "event,1", "body,0", "body,1"})
  void acceptsExactEventAndWireLimitsButRejectsOneOver(String limit, int over) throws Exception {
    String payload = (":" + "x".repeat(126) + "\n").repeat(4) + "\n" + delta("ok") + done(false);
    serve(false, exchange -> reply(exchange, payload));
    TeacherLlmClient client =
        new TeacherLlmClient(
            http,
            baseUrl,
            "secret",
            "model",
            new TeacherResponseBudget.Limits(
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                128,
                limit.equals("event") ? 512 - over : 512,
                payload.getBytes(StandardCharsets.UTF_8).length - (limit.equals("body") ? over : 0),
                64));
    Future<String> result = request(client, null, new ArrayList<>());
    if (over == 0) {
      assertEquals("ok", result.get(5, TimeUnit.SECONDS));
    } else {
      assertInstanceOf(IOException.class, failed(result));
    }
  }

  @Test
  void partialUtf8LinesCountAsIdleProgress() throws Exception {
    String text = "한글😀";
    serve(
        false,
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream output = exchange.getResponseBody()) {
            byte[] content = (delta(text) + done(false)).getBytes(StandardCharsets.UTF_8);
            for (int offset = 0; offset < content.length; offset += 4) {
              output.write(content, offset, Math.min(4, content.length - offset));
              output.flush();
              if (awaitRelease(40)) {
                break;
              }
            }
          }
        });
    // The complete line takes longer than 400 ms; individual raw reads must refresh idle time.
    assertEquals(
        text, request(client(5_000, 400), null, new ArrayList<>()).get(5, TimeUnit.SECONDS));
  }

  @Test
  void timeoutDoesNotPublishOrTriggerGroundedCorrectionAndNextRequestCanSucceed() throws Exception {
    CountDownLatch firstSent = new CountDownLatch(1);
    serve(
        false,
        exchange -> {
          if (chatCalls.get() == 1) {
            hold(exchange, 200, delta("Compare D4."), firstSent);
          } else {
            reply(exchange, delta("Compare D4.") + done(false));
          }
        });
    TeacherLlmClient transport = client(5_000, 200);
    GroundedCommentaryClient grounded = grounded(transport);
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    List<String> displayed = new CopyOnWriteArrayList<>();
    Future<String> result = request(grounded, cancellation, displayed);
    assertTrue(firstSent.await(3, TimeUnit.SECONDS));
    assertInstanceOf(HttpTimeoutException.class, failed(result));
    assertTrue(displayed.isEmpty());
    assertEquals(1, chatCalls.get());
    assertEquals(0, responsesCalls.get());

    assertEquals(
        "Compare D4.", request(grounded, cancellation, displayed).get(5, TimeUnit.SECONDS));
    assertEquals(List.of("Compare D4."), displayed);
    assertEquals(2, chatCalls.get());
    assertEquals(1, release.getCount(), "Old server response must not need to finish for retry");
  }

  @Test
  void groundedCorrectionStillVerifiesOnlyTheCompletedReply() throws Exception {
    serve(
        false,
        exchange ->
            reply(
                exchange, delta(chatCalls.get() == 1 ? "Play Q16." : "Compare D4.") + done(false)));
    List<String> displayed = new CopyOnWriteArrayList<>();
    assertEquals(
        "Compare D4.",
        request(grounded(client(5_000, 500)), null, displayed).get(5, TimeUnit.SECONDS));
    assertEquals(List.of("Compare D4."), displayed);
    assertEquals(2, chatCalls.get());
    assertEquals(0, responsesCalls.get());
  }

  @Test
  void controllerReportsTimeoutAsFailureAndCanReplaceTheStalledRequest() throws Exception {
    CountDownLatch firstSent = new CountDownLatch(1);
    serve(
        false,
        exchange -> {
          if (chatCalls.get() == 1) {
            hold(exchange, 200, delta("first"), firstSent);
          } else {
            reply(exchange, delta("second") + done(false));
          }
        });
    try (TeacherRequestController controller = new TeacherRequestController()) {
      RecordingListener first = new RecordingListener();
      controller.start(client(5_000, 200), prompt(), first);
      assertTrue(firstSent.await(3, TimeUnit.SECONDS));
      assertTrue(first.finished.await(5, TimeUnit.SECONDS));
      assertEquals(1, first.errors.size());
      assertInstanceOf(HttpTimeoutException.class, first.errors.get(0));
      assertTrue(first.completions.isEmpty());
      assertEquals(0, first.cancelled.get());
      RecordingListener second = new RecordingListener();
      controller.start(client(5_000, 500), prompt(), second);
      assertTrue(second.finished.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("second"), second.completions);
      assertTrue(second.errors.isEmpty());
      assertEquals(1, release.getCount());
    }
  }

  @Test
  void controllerReplacementSucceedsWhileCancelledServerStillHoldsTheOldBody() throws Exception {
    CountDownLatch firstSent = new CountDownLatch(1);
    serve(
        false,
        exchange -> {
          if (chatCalls.get() == 1) {
            hold(exchange, 200, delta("old"), firstSent);
          } else {
            reply(exchange, delta("replacement") + done(false));
          }
        });
    try (TeacherRequestController controller = new TeacherRequestController()) {
      RecordingListener first = new RecordingListener();
      controller.start(client(10_000, 5_000), prompt(), first);
      assertTrue(firstSent.await(3, TimeUnit.SECONDS));
      RecordingListener second = new RecordingListener();
      controller.start(client(5_000, 500), prompt(), second);
      assertTrue(second.finished.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("replacement"), second.completions);
      assertTrue(second.errors.isEmpty());
      assertTrue(first.completions.isEmpty());
      assertTrue(first.errors.isEmpty());
      assertEquals(0, first.cancelled.get());
      assertEquals(1, release.getCount());
    }
  }

  private TeacherLlmClient client(long totalMillis, long idleMillis) {
    return new TeacherLlmClient(
        http,
        baseUrl,
        "secret",
        "model",
        new TeacherResponseBudget.Limits(
            Duration.ofMillis(totalMillis),
            Duration.ofMillis(idleMillis),
            1_024,
            4_096,
            8_192,
            64));
  }

  private Future<String> request(
      CommentaryClient client, TeacherLlmClient.Cancellation cancellation, List<String> displayed) {
    return callers.submit(() -> client.stream(prompt(), cancellation, displayed::add));
  }

  private static Throwable failed(Future<String> result) {
    return assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause();
  }

  private void serve(boolean fallback, HttpHandler handler) {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          chatCalls.incrementAndGet();
          if (fallback) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
          } else {
            handler.handle(exchange);
          }
        });
    server.createContext(
        "/v1/responses",
        exchange -> {
          responsesCalls.incrementAndGet();
          if (fallback) {
            handler.handle(exchange);
          } else {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
          }
        });
    server.start();
  }

  private void hold(HttpExchange exchange, int status, String prefix, CountDownLatch sent)
      throws IOException {
    exchange.sendResponseHeaders(status, 0);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(prefix.getBytes(StandardCharsets.UTF_8));
      output.flush();
      sent.countDown();
      awaitRelease(10_000);
    } catch (IOException clientClosed) {
    }
  }

  private boolean awaitRelease(long millis) {
    try {
      return release.await(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return true;
    }
  }

  private static void reply(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static String delta(String text) {
    return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n";
  }

  private static String responseDelta(String text) {
    return "data: {\"type\":\"response.output_text.delta\",\"delta\":\"" + text + "\"}\n\n";
  }

  private static String done(boolean fallback) {
    return fallback
        ? "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
        : "data: [DONE]\n\n";
  }

  private static List<TeacherLlmClient.Message> prompt() {
    return List.of(new TeacherLlmClient.Message("user", "Explain D4."));
  }

  private static GroundedCommentaryClient grounded(CommentaryClient client) {
    return new GroundedCommentaryClient(
        client,
        List.of(
            new TeacherEvidence.Position(
                10,
                "B",
                1_000,
                "D4",
                OptionalDouble.empty(),
                List.of(new TeacherEvidence.Candidate(1, "D4", 55, 1.2, 800, List.of("D4"))))));
  }

  private static final class RecordingListener implements TeacherRequestController.Listener {
    final List<String> completions = new CopyOnWriteArrayList<>();
    final List<Throwable> errors = new CopyOnWriteArrayList<>();
    final AtomicInteger cancelled = new AtomicInteger();
    final CountDownLatch finished = new CountDownLatch(1);

    public void onText(String text) {}

    public void onComplete(String text) {
      completions.add(text);
      finished.countDown();
    }

    public void onFailure(Throwable failure) {
      errors.add(failure);
      finished.countDown();
    }

    public void onCancelled() {
      cancelled.incrementAndGet();
      finished.countDown();
    }
  }
}
