package featurecat.lizzie.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Serial background writes; completion effects always run on the supplied owner executor. */
final class SgfSaveQueue {
  @FunctionalInterface
  interface Writer {
    void write(Path path, String snapshot) throws IOException;
  }

  private final Executor worker;
  private final Executor owner;
  private final Writer writer;
  private final Set<CompletableFuture<Void>> inFlight = new HashSet<>();

  SgfSaveQueue(Executor worker, Executor owner, Writer writer) {
    this.worker = worker;
    this.owner = owner;
    this.writer = writer;
  }

  synchronized CompletableFuture<Void> submit(
      Path path,
      String snapshot,
      BooleanSupplier stillCurrent,
      Runnable success,
      Consumer<Throwable> failure) {
    CompletableFuture<Void> completion = new CompletableFuture<>();
    inFlight.add(completion);
    Runnable write =
        () -> {
          Throwable error = null;
          try {
            writer.write(path, snapshot);
          } catch (IOException | RuntimeException caught) {
            error = caught;
          }
          Throwable result = error;
          owner.execute(() -> finish(stillCurrent, success, failure, completion, result));
        };
    try {
      worker.execute(write);
    } catch (RuntimeException rejected) {
      owner.execute(() -> finish(stillCurrent, success, failure, completion, rejected));
    }
    return completion;
  }

  private void finish(
      BooleanSupplier stillCurrent,
      Runnable success,
      Consumer<Throwable> failure,
      CompletableFuture<Void> completion,
      Throwable error) {
    try {
      if (error != null) {
        failure.accept(error);
      } else {
        if (stillCurrent.getAsBoolean()) success.run();
      }
    } catch (RuntimeException callbackFailure) {
      error = callbackFailure;
    }
    synchronized (this) {
      inFlight.remove(completion);
    }
    if (error != null) completion.completeExceptionally(error);
    else completion.complete(null);
  }

  synchronized CompletableFuture<Void> pending() {
    // Each shutdown request owns a snapshot of the writes it actually waits for. A failure
    // rejects that barrier, but already reported failures do not poison future requests.
    return CompletableFuture.allOf(inFlight.toArray(new CompletableFuture<?>[0]));
  }
}
