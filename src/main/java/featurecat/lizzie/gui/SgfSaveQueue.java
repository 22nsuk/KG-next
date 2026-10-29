package featurecat.lizzie.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Serial saves, including owner preparation/publication before the next disk operation. */
final class SgfSaveQueue {
  private static final int MAX_WAITING = 16;

  @FunctionalInterface
  interface Writer {
    void write(Path path, String snapshot) throws IOException;
  }

  @FunctionalInterface
  interface IOOperation<T> {
    T run() throws IOException;
  }

  @FunctionalInterface
  interface Preparation<T> {
    IOOperation<T> prepare() throws IOException;
  }

  private final Executor worker;
  private final Executor owner;
  private final Writer writer;
  private final Set<CompletableFuture<?>> inFlight = new HashSet<>();
  private final ArrayDeque<Request<?>> waiting = new ArrayDeque<>();
  private Request<?> active;

  private record Request<T>(
      Preparation<T> preparation,
      BooleanSupplier stillCurrent,
      Consumer<T> success,
      Consumer<Throwable> failure,
      CompletableFuture<T> completion) {}

  SgfSaveQueue(Executor worker, Executor owner, Writer writer) {
    this.worker = worker;
    this.owner = owner;
    this.writer = writer;
  }

  CompletableFuture<Void> submit(
      Path path,
      String snapshot,
      BooleanSupplier stillCurrent,
      Runnable success,
      Consumer<Throwable> failure) {
    return submit(
        () -> {
          writer.write(path, snapshot);
          return null;
        },
        stillCurrent,
        ignored -> success.run(),
        failure);
  }

  <T> CompletableFuture<T> submit(
      IOOperation<T> operation,
      BooleanSupplier stillCurrent,
      Consumer<T> success,
      Consumer<Throwable> failure) {
    return submitPrepared(() -> operation, stillCurrent, success, failure);
  }

  synchronized <T> CompletableFuture<T> submitPrepared(
      Preparation<T> preparation,
      BooleanSupplier stillCurrent,
      Consumer<T> success,
      Consumer<Throwable> failure) {
    Request<T> request =
        new Request<>(preparation, stillCurrent, success, failure, new CompletableFuture<>());
    inFlight.add(request.completion());
    if (active == null) {
      active = request;
      owner.execute(() -> prepare(request));
    } else if (waiting.size() < MAX_WAITING) {
      waiting.add(request);
    } else {
      owner.execute(
          () -> finish(request, null,
              new java.util.concurrent.RejectedExecutionException("SGF save queue is full")));
    }
    return request.completion();
  }

  private <T> void prepare(Request<T> request) {
    try {
      IOOperation<T> operation = Objects.requireNonNull(request.preparation().prepare());
      worker.execute(() -> write(request, operation));
    } catch (IOException | RuntimeException failure) {
      finish(request, null, failure);
    }
  }

  private <T> void write(Request<T> request, IOOperation<T> operation) {
    T value = null;
    Throwable error = null;
    try {
      value = operation.run();
    } catch (IOException | RuntimeException caught) {
      error = caught;
    }
    T result = value;
    Throwable failure = error;
    owner.execute(() -> finish(request, result, failure));
  }

  private <T> void finish(Request<T> request, T value, Throwable error) {
    try {
      if (error != null) {
        request.failure().accept(error);
      } else if (request.stillCurrent().getAsBoolean()) {
        request.success().accept(value);
      }
    } catch (RuntimeException callbackFailure) {
      error = callbackFailure;
    }
    Request<?> next = null;
    synchronized (this) {
      inFlight.remove(request.completion());
      if (active == request) {
        active = waiting.poll();
        next = active;
      }
    }
    if (error != null) request.completion().completeExceptionally(error);
    else request.completion().complete(value);
    if (next != null) {
      Request<?> following = next;
      owner.execute(() -> prepare(following));
    }
  }

  synchronized CompletableFuture<Void> pending() {
    // Each shutdown request owns a snapshot of the writes it actually waits for. A failure
    // rejects that barrier, but already reported failures do not poison future requests.
    return CompletableFuture.allOf(inFlight.toArray(new CompletableFuture<?>[0]));
  }
}
