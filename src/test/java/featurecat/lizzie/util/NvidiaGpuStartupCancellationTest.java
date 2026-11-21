package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NvidiaGpuStartupCancellationTest {
  @ParameterizedTest
  @ValueSource(strings = {"cuda", "opencl"})
  void cancelledUncachedDriverQueryStopsRetriesAndCanBeRetried(String backend, @TempDir Path root)
      throws Exception {
    NvidiaGpuDiscoveryFixture.runFork(getClass(), backend, root);
  }

  @Test
  void cancelledDriverLockWaitPreservesTheActiveQueryAndCache(@TempDir Path root) throws Exception {
    NvidiaGpuDiscoveryFixture.runFork(getClass(), "waiter", root);
  }

  static void runGpuScenario(String scenario, Path root) throws Exception {
    System.setProperty("os.name", "Windows 10");
    System.clearProperty("lizzie.opencl.nvidiaDriverVersion");
    Lizzie.config = ConfigTestHelper.createForTests(Files.createDirectories(root.resolve("runtime")));
    Lizzie.config.config = new JSONObject();
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.config.leelazConfig = new JSONObject();
    boolean cuda = !scenario.equals("opencl");
    Path engineDir = Files.createDirectories(root.resolve("engines/katago/"
        + (cuda ? "windows-x64-nvidia" : "windows-x64")));
    Path engine = Files.writeString(engineDir.resolve("katago.exe"), "fixture engine");
    if (cuda) KataGoRuntimeHelperTest.touchRequiredCuda12_8Dlls(engineDir);
    else Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "opencl");
    List<String> command = List.of(engine.toString(), "analysis", "-model",
        Files.writeString(root.resolve("model.bin.gz"), "model").toString(), "-config",
        Files.writeString(root.resolve("analysis.cfg"), "numAnalysisThreads = 1\n").toString());
    if (cuda) assertTrue(KataGoRuntimeHelper.inspectNvidiaRuntime(engine).ready);
    else assertTrue(KataGoRuntimeHelper.isBundledOpenClPath(engine));
    try (NvidiaGpuDiscoveryFixture fixture = new NvidiaGpuDiscoveryFixture(root)) {
      if (scenario.equals("waiter")) preservesActiveQuery(fixture, engine, command);
      else cancelsQuery(fixture, engine, command, cuda);
    }
  }

  private static void prepare(Path engine, List<String> command, boolean cuda,
      BooleanSupplier cancelled) throws Exception {
    prepare(engine, command, cuda, cancelled, process -> {});
  }

  private static void prepare(Path engine, List<String> command, boolean cuda,
      BooleanSupplier cancelled, Consumer<Process> started) throws Exception {
    if (cuda)
      KataGoRuntimeHelper.ensureBundledRuntimeReady(
          engine, command, null, null, null, cancelled, started);
    else
      KataGoRuntimeHelper.prepareBundledLaunchCommand(command, engine,
          KataGoRuntimeHelper.LaunchPurpose.ANALYSIS, cancelled, started);
  }

  private static void cancelsQuery(NvidiaGpuDiscoveryFixture fixture, Path engine,
      List<String> command, boolean cuda) throws Exception {
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicReference<CompletableFuture<Process>> queryExit = new AtomicReference<>();
    FutureTask<Void> result = new FutureTask<>(() -> {
      prepare(engine, command, cuda, cancelled::get, process -> queryExit.set(process.onExit()));
      return null;
    });
    Thread worker = new Thread(result, "uncached-driver-query");
    try {
      worker.start();
      ProcessHandle query = fixture.awaitQuery();
      cancelled.set(true);
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
      if (cuda) assertInstanceOf(InterruptedIOException.class, failure.getCause());
      else assertInstanceOf(CancellationException.class, failure.getCause());
      queryExit.get().get(5, TimeUnit.SECONDS);
      assertFalse(query.isAlive(), "the retained completion did not wait for the exact query");
      assertEquals(1, fixture.queryCount(), "cancelled discovery started another query");
      if (cuda) assertFalse(Files.exists(KataGoRuntimeHelper.cudaCompatibilityProbeMarker(engine)));

      fixture.release();
      prepare(engine, command, cuda, () -> false);
      assertEquals(2, fixture.queryCount(), "cancellation must not populate the driver cache");
      prepare(engine, command, cuda, () -> false);
      assertEquals(2, fixture.queryCount(), "a completed discovery should populate the driver cache");
    } finally {
      cancelled.set(true);
      worker.interrupt();
      worker.join(5000);
      assertFalse(worker.isAlive());
    }
  }

  private static void preservesActiveQuery(NvidiaGpuDiscoveryFixture fixture, Path engine,
      List<String> command) throws Exception {
    AtomicBoolean cancelled = new AtomicBoolean();
    FutureTask<Void> first = new FutureTask<>(() -> {
      prepare(engine, command, true, () -> false);
      return null;
    });
    FutureTask<Void> second = new FutureTask<>(() -> {
      prepare(engine, command, true, cancelled::get);
      return null;
    });
    Thread active = new Thread(first, "active-driver-query");
    Thread waiting = new Thread(second, "waiting-driver-query");
    try {
      active.start();
      ProcessHandle query = fixture.awaitQuery();
      var lockField = KataGoRuntimeHelper.class.getDeclaredField("NVIDIA_DRIVER_DETECTION_LOCK");
      lockField.setAccessible(true);
      ReentrantLock lock = (ReentrantLock) lockField.get(null);
      waiting.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      boolean queued = false;
      while (waiting.isAlive() && System.nanoTime() < deadline) {
        if (lock.hasQueuedThread(waiting)) {
          queued = true;
          break;
        }
        Thread.sleep(5);
      }
      assertTrue(queued, "second lookup did not wait behind the active query");
      cancelled.set(true);
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
      assertInstanceOf(InterruptedIOException.class, failure.getCause());
      assertTrue(query.isAlive(), "cancelling a waiter stopped another caller's query");
      assertEquals(1, fixture.queryCount());
      fixture.release();
      first.get(5, TimeUnit.SECONDS);
      assertFalse(query.isAlive());
      prepare(engine, command, true, () -> false);
      assertEquals(1, fixture.queryCount(), "the other caller's successful cache was lost");
    } finally {
      cancelled.set(true);
      active.interrupt();
      waiting.interrupt();
      active.join(5000);
      waiting.join(5000);
      assertFalse(active.isAlive());
      assertFalse(waiting.isAlive());
    }
  }
}
