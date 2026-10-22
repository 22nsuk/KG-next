package featurecat.lizzie.teacher;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the transport resources and limits for one commentary call, including endpoint fallback. */
final class TeacherResponseBudget implements AutoCloseable {
  record Limits(
      Duration totalTimeout,
      Duration idleTimeout,
      int lineCharacters,
      int eventCharacters,
      long bodyBytes,
      int textCharacters) {
    static final Limits DEFAULT =
        new Limits(
            Duration.ofMinutes(5), Duration.ofSeconds(60), 65_536, 262_144, 8_388_608, 131_072);

    Limits {
      if (totalTimeout.toNanos() <= 0
          || idleTimeout.toNanos() <= 0
          || lineCharacters <= 0
          || eventCharacters <= 0
          || bodyBytes <= 0
          || textCharacters <= 0) {
        throw new IllegalArgumentException("Response limits must be positive.");
      }
    }
  }

  record Response(int statusCode, InputStream body) {}

  private static final ScheduledThreadPoolExecutor DEADLINES = createDeadlineExecutor();
  private final Limits limits;
  private final TeacherLlmClient.Cancellation cancellation;
  private final Runnable cancelAction = () -> stop(null);
  private final long started = System.nanoTime();
  private long lastProgress;
  private long bytesRead;
  private long timerGeneration;
  private ScheduledFuture<?> timer;
  private CompletableFuture<?> pending;
  private Body body;
  private IOException failure;
  private boolean stopped;

  TeacherResponseBudget(Limits limits, TeacherLlmClient.Cancellation cancellation) {
    this.limits = limits;
    this.cancellation = cancellation;
    cancellation.attach(cancelAction);
    synchronized (this) {
      if (!stopped) {
        scheduleDeadline();
      }
    }
  }

  Response send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
    check();
    CompletableFuture<HttpResponse<InputStream>> exchange =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    boolean received = false;
    try {
      synchronized (this) {
        check();
        pending = exchange;
      }
      HttpResponse<InputStream> response = exchange.get();
      synchronized (this) {
        check();
        body = new Body(response.body());
        lastProgress = System.nanoTime();
        scheduleDeadline();
        received = true;
        return new Response(response.statusCode(), body);
      }
    } catch (ExecutionException error) {
      check();
      if (error.getCause() instanceof IOException io) {
        throw io;
      }
      throw new IOException("AI service request failed.", error.getCause());
    } catch (java.util.concurrent.CancellationException cancelled) {
      check();
      throw cancelled;
    } finally {
      synchronized (this) {
        if (pending == exchange) {
          pending = null;
        }
      }
      if (!received) {
        // This also closes an already completed response when cancellation won before pending
        // was published. Cancelling a completed future alone cannot release its body.
        exchange.whenComplete(
            (response, error) -> {
              if (response != null) {
                closeQuietly(response.body());
              }
            });
        exchange.cancel(true);
      }
    }
  }

  synchronized void check() throws IOException {
    cancellation.check();
    if (failure != null) {
      throw failure;
    }
    IOException expired = expiration();
    if (expired != null) {
      throw expired;
    }
    if (stopped) {
      throw new IOException("AI commentary response is closed.");
    }
  }

  private IOException expiration() {
    long now = System.nanoTime();
    if (now - started >= limits.totalTimeout().toNanos()) {
      return new HttpTimeoutException(
          TeacherStrings.get(
              "Teacher.error.responseTimeout",
              "The reply exceeded the time limit and was not saved. Please try again."));
    }
    if (body != null && now - lastProgress >= limits.idleTimeout().toNanos()) {
      return new HttpTimeoutException(
          TeacherStrings.get(
              "Teacher.error.responseIdle",
              "The service stopped sending reply data. Nothing was saved. Please try again."));
    }
    return null;
  }

  static IOException tooLarge() {
    return new IOException(
        TeacherStrings.get(
            "Teacher.error.responseTooLarge",
            "The reply exceeded the size limit and was not saved. Please try again."));
  }

  // Called under this monitor. Progress updates do not allocate a timer for every chunk.
  private void scheduleDeadline() {
    if (timer != null) {
      timer.cancel(false);
    }
    long generation = ++timerGeneration;
    long now = System.nanoTime();
    long remaining = limits.totalTimeout().toNanos() - (now - started);
    if (body != null) {
      remaining = Math.min(remaining, limits.idleTimeout().toNanos() - (now - lastProgress));
    }
    timer =
        DEADLINES.schedule(() -> expire(generation), Math.max(1, remaining), TimeUnit.NANOSECONDS);
  }

  private void expire(long generation) {
    synchronized (this) {
      if (stopped || generation != timerGeneration) {
        return;
      }
      failure = expiration();
      if (failure == null) {
        scheduleDeadline();
        return;
      }
    }
    stop(failure);
  }

  private void stop(IOException cause) {
    CompletableFuture<?> exchange;
    Body source;
    synchronized (this) {
      if (stopped) {
        return;
      }
      stopped = true;
      if (cause != null) {
        failure = cause;
      }
      if (timer != null) {
        timer.cancel(false);
      }
      exchange = pending;
      pending = null;
      source = body;
      body = null;
    }
    if (exchange != null) {
      exchange.cancel(true);
    }
    closeQuietly(source);
  }

  @Override
  public void close() {
    stop(null);
    cancellation.detach(cancelAction);
  }

  private final class Body extends FilterInputStream {
    private final AtomicBoolean closed = new AtomicBoolean();

    Body(InputStream source) {
      super(source);
    }

    @Override
    public int read() throws IOException {
      byte[] single = new byte[1];
      return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      check();
      if (length == 0) {
        return 0;
      }
      try {
        // Read at most one byte beyond the budget, even with a large caller buffer.
        int allowed = (int) Math.min(length, limits.bodyBytes() - bytesRead + 1);
        int count = in.read(buffer, offset, allowed);
        synchronized (TeacherResponseBudget.this) {
          check();
          if (count > 0) {
            bytesRead += count;
            if (bytesRead > limits.bodyBytes()) {
              throw tooLarge();
            }
            lastProgress = System.nanoTime();
          }
        }
        return count;
      } catch (IOException error) {
        check();
        throw error;
      }
    }

    @Override
    public void close() throws IOException {
      if (closed.compareAndSet(false, true)) {
        synchronized (TeacherResponseBudget.this) {
          if (body == this) {
            body = null;
            if (!stopped) {
              scheduleDeadline();
            }
          }
        }
        in.close();
      }
    }
  }

  private static void closeQuietly(InputStream stream) {
    if (stream != null) {
      try {
        stream.close();
      } catch (IOException ignored) {
        // Cleanup must preserve the original cancellation, timeout or parse failure.
      }
    }
  }

  private static ScheduledThreadPoolExecutor createDeadlineExecutor() {
    ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "lizzie-ai-response-deadline");
              thread.setDaemon(true);
              return thread;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }
}
