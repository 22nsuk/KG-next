package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/** Deterministic ownership handoffs that cannot be scheduled reliably by an HTTP server. */
class TeacherResponseBudgetTest {
  private static final String COMPLETE =
      "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n";

  @Test
  void closesCompletedResponseWhenCancelledBeforePendingFutureIsPublished() {
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    TrackedBody body = new TrackedBody(COMPLETE);
    StubClient http =
        new StubClient(
            () -> {
              CompletableFuture<HttpResponse<InputStream>> completed = completed(200, body);
              cancellation.cancel();
              return completed;
            });
    assertThrows(CancellationException.class, () -> stream(http, cancellation, ignored -> {}));
    assertTrue(body.closes.get() > 0, "A completed future cannot be cancelled to close its body");
    assertEquals(0, body.reads.get());
    assertEquals(1, http.calls.get());
  }

  @Test
  void cancellationOnCompletedResponseObservationStillClosesTheUnclaimedBody() {
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    TrackedBody body = new TrackedBody(COMPLETE);
    CompletableFuture<HttpResponse<InputStream>> response =
        new CompletableFuture<>() {
          @Override
          public CompletableFuture<HttpResponse<InputStream>> whenComplete(
              BiConsumer<? super HttpResponse<InputStream>, ? super Throwable> action) {
            CompletableFuture<HttpResponse<InputStream>> dependent = super.whenComplete(action);
            cancellation.cancel();
            return dependent;
          }

          @Override
          public HttpResponse<InputStream> get() throws InterruptedException, ExecutionException {
            cancellation.cancel();
            return super.get();
          }
        };
    response.complete(completed(200, body).join());
    StubClient http = new StubClient(() -> response);
    assertThrows(CancellationException.class, () -> stream(http, cancellation, ignored -> {}));
    assertTrue(
        body.closes.get() > 0,
        "Cancellation after response observation must close the unclaimed body");
    assertEquals(0, body.reads.get());
  }

  @Test
  void cancellationDuringFallbackClosePreventsTheSecondPaidRequest() {
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    TrackedBody body =
        new TrackedBody("not found") {
          @Override
          public void close() throws IOException {
            super.close();
            cancellation.cancel();
          }
        };
    StubClient http = new StubClient(() -> completed(404, body));
    assertThrows(CancellationException.class, () -> stream(http, cancellation, ignored -> {}));
    assertTrue(body.closes.get() > 0);
    assertEquals(0, body.reads.get(), "Unavailable endpoints must be closed, not drained");
    assertEquals(1, http.calls.get());
  }

  @Test
  void receiverFailureClosesBodyAndAllowsReuseOfTheToken() throws Exception {
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    TrackedBody first = new TrackedBody(COMPLETE);
    TrackedBody second = new TrackedBody(COMPLETE);
    AtomicInteger attempt = new AtomicInteger();
    StubClient http =
        new StubClient(() -> completed(200, attempt.getAndIncrement() == 0 ? first : second));
    IllegalStateException callbackFailure = new IllegalStateException("receiver failed");
    assertSame(
        callbackFailure,
        assertThrows(
            IllegalStateException.class,
            () ->
                stream(
                    http,
                    cancellation,
                    ignored -> {
                      throw callbackFailure;
                    })));
    assertTrue(first.closes.get() > 0);
    assertFalse(cancellation.isCancelled());
    assertEquals("ok", stream(http, cancellation, ignored -> {}));
    assertTrue(second.closes.get() > 0);
  }

  @Test
  void bufferedSuccessfulFinishCannotWinAfterTheDeadlineHasClosedItsBody() {
    CountDownLatch closed = new CountDownLatch(1);
    TrackedBody body =
        new TrackedBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n") {
          @Override
          public void close() throws IOException {
            super.close();
            closed.countDown();
          }
        };
    StubClient http = new StubClient(() -> completed(200, body));
    TeacherLlmClient client =
        new TeacherLlmClient(
            http,
            "http://127.0.0.1/v1",
            "secret",
            "model",
            new TeacherResponseBudget.Limits(
                Duration.ofMillis(200), Duration.ofSeconds(5), 1_024, 4_096, 8_192, 64));
    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    assertThrows(
        java.net.http.HttpTimeoutException.class,
        () ->
            client.stream(
                List.of(new TeacherLlmClient.Message("user", "Explain")),
                cancellation,
                text -> {
                  try {
                    assertTrue(
                        closed.await(3, TimeUnit.SECONDS),
                        "Deadline must close the body while the final callback is held");
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(interrupted);
                  }
                }));
    assertFalse(cancellation.isCancelled());
  }

  @Test
  void cleanupFailureDoesNotReplaceAParseFailure() {
    TrackedBody body =
        new TrackedBody("data: {broken}\n\n") {
          @Override
          public void close() throws IOException {
            super.close();
            throw new IOException("close failure");
          }
        };
    StubClient http = new StubClient(() -> completed(200, body));
    IOException failure = assertThrows(IOException.class, () -> stream(http, null, ignored -> {}));
    assertTrue(failure.getMessage().contains("interrupted"), failure.getMessage());
    assertTrue(body.closes.get() > 0);
  }

  @Test
  void errorBodyRetainsOnlyTheExistingBoundedRedactedExcerpt() {
    TrackedBody body = new TrackedBody("secret-value " + "x".repeat(20_000));
    StubClient http = new StubClient(() -> completed(500, body));
    IOException failure = assertThrows(IOException.class, () -> stream(http, null, ignored -> {}));
    assertTrue(failure.getMessage().contains("HTTP 500: [redacted]"));
    assertFalse(failure.getMessage().contains("secret-value"));
    assertTrue(body.bytesRead.get() <= 4_097);
    assertTrue(body.closes.get() > 0);
  }

  private static String stream(
      StubClient http,
      TeacherLlmClient.Cancellation cancellation,
      java.util.function.Consumer<String> receiver)
      throws IOException, InterruptedException {
    return new TeacherLlmClient(http, "http://127.0.0.1/v1", "secret-value", "model")
        .stream(List.of(new TeacherLlmClient.Message("user", "Explain")), cancellation, receiver);
  }

  private static CompletableFuture<HttpResponse<InputStream>> completed(
      int status, InputStream body) {
    return CompletableFuture.completedFuture(
        new HttpResponse<>() {
          public int statusCode() {
            return status;
          }

          public InputStream body() {
            return body;
          }

          public HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1/v1")).build();
          }

          public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
          }

          public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
          }

          public Optional<SSLSession> sslSession() {
            return Optional.empty();
          }

          public URI uri() {
            return URI.create("http://127.0.0.1/v1");
          }

          public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
          }
        });
  }

  private static class TrackedBody extends InputStream {
    final AtomicInteger closes = new AtomicInteger();
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger bytesRead = new AtomicInteger();
    private final ByteArrayInputStream delegate;

    TrackedBody(String content) {
      delegate = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    public int read() {
      reads.incrementAndGet();
      int value = delegate.read();
      if (value >= 0) {
        bytesRead.incrementAndGet();
      }
      return value;
    }

    public int read(byte[] bytes, int offset, int length) {
      reads.incrementAndGet();
      int count = delegate.read(bytes, offset, length);
      if (count > 0) {
        bytesRead.addAndGet(count);
      }
      return count;
    }

    public void close() throws IOException {
      closes.incrementAndGet();
      delegate.close();
    }
  }

  private static final class StubClient extends HttpClient {
    private final HttpClient delegate = HttpClient.newHttpClient();
    private final Supplier<CompletableFuture<HttpResponse<InputStream>>> response;
    final AtomicInteger calls = new AtomicInteger();

    StubClient(Supplier<CompletableFuture<HttpResponse<InputStream>>> response) {
      this.response = response;
    }

    public Optional<CookieHandler> cookieHandler() {
      return delegate.cookieHandler();
    }

    public Optional<Duration> connectTimeout() {
      return delegate.connectTimeout();
    }

    public Redirect followRedirects() {
      return delegate.followRedirects();
    }

    public Optional<ProxySelector> proxy() {
      return delegate.proxy();
    }

    public SSLContext sslContext() {
      return delegate.sslContext();
    }

    public SSLParameters sslParameters() {
      return delegate.sslParameters();
    }

    public Optional<Authenticator> authenticator() {
      return delegate.authenticator();
    }

    public Version version() {
      return delegate.version();
    }

    public Optional<Executor> executor() {
      return delegate.executor();
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      calls.incrementAndGet();
      return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) response.get();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushHandler) {
      throw new UnsupportedOperationException();
    }
  }
}
