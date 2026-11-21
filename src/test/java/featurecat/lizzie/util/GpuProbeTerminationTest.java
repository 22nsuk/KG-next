package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GpuProbeTerminationTest {
  @ParameterizedTest
  @CsvSource({
    "nvidia, timeout", "cuda, timeout",
    "nvidia, cancel", "cuda, cancel",
    "nvidia, interrupt", "cuda, interrupt"
  })
  void resultDoesNotWaitForDelayedTerminationButTheExitSignalDoes(
      String backend, String outcome, @TempDir Path directory) throws Exception {
    try (DelayedTerminationProcess process = new DelayedTerminationProcess(directory)) {
      CountDownLatch observed = new CountDownLatch(1);
      AtomicReference<CompletableFuture<Process>> exit = new AtomicReference<>();
      AtomicBoolean cancelled = new AtomicBoolean();
      Consumer<Process> started = child -> {
        assertSame(process, child);
        exit.set(child.onExit());
        observed.countDown();
      };
      FutureTask<ProbeResult> result = new FutureTask<>(() -> {
        try {
          boolean success = awaitProbe(backend, process, cancelled::get, started,
              outcome.equals("timeout") ? 25 : 10_000);
          return new ProbeResult(success, null, Thread.currentThread().isInterrupted());
        } catch (Throwable failure) {
          return new ProbeResult(false, failure, Thread.currentThread().isInterrupted());
        }
      });
      Thread worker = new Thread(result, "delayed-termination-" + backend);
      worker.setDaemon(true);
      try {
        worker.start();
        assertTrue(observed.await(5, TimeUnit.SECONDS), "child was not registered before waiting");
        if (outcome.equals("cancel")) cancelled.set(true);
        if (outcome.equals("interrupt")) worker.interrupt();

        ProbeResult completed = result.get(5, TimeUnit.SECONDS);
        assertFalse(completed.success());
        if (outcome.equals("timeout")) assertNull(completed.failure());
        else if (backend.equals("nvidia")) {
          assertInstanceOf(CancellationException.class, completed.failure());
        } else {
          assertInstanceOf(InterruptedIOException.class, completed.failure());
        }
        assertEquals(outcome.equals("interrupt"), completed.interrupted());
        assertTrue(process.awaitTerminationRequest(1, TimeUnit.SECONDS));
        assertTrue(process.isAlive(), "fixture must still hold actual child termination");
        assertNotNull(exit.get());
        assertFalse(exit.get().isDone(), "helper result must not replace the real exit signal");

        process.finishTermination();
        assertSame(process, exit.get().get(5, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
      } finally {
        cancelled.set(true);
        process.finishTermination();
        worker.interrupt();
        worker.join(5_000);
        assertFalse(worker.isAlive());
      }
    }
  }

  private static boolean awaitProbe(
      String backend, Process process, BooleanSupplier cancelled, Consumer<Process> started,
      long timeoutMillis) throws Throwable {
    try {
      if (backend.equals("cuda")) {
        Method await = KataGoRuntimeHelper.class.getDeclaredMethod("awaitCompatibilityProbe",
            Process.class, StringBuilder.class, BooleanSupplier.class, Consumer.class, long.class);
        await.setAccessible(true);
        return (boolean) await.invoke(null, process, new StringBuilder(), cancelled, started,
            timeoutMillis);
      }
      Method read = NvidiaGpuDetector.class.getDeclaredMethod("readCommand", Process.class,
          BooleanSupplier.class, Consumer.class, long.class);
      read.setAccessible(true);
      Object command = read.invoke(null, process, cancelled, started, timeoutMillis);
      var success = command.getClass().getDeclaredField("success");
      success.setAccessible(true);
      return success.getBoolean(command);
    } catch (InvocationTargetException invoked) {
      throw invoked.getCause();
    }
  }

  private record ProbeResult(boolean success, Throwable failure, boolean interrupted) {}
}
