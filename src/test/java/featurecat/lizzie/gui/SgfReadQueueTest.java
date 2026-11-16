package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.rules.SGFParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SgfReadQueueTest {
  @Test
  void keepsOnlyActiveAndLatestWaitingReadAndSettlesSupersededRequests() throws Exception {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    List<Path> reads = new ArrayList<>();
    SgfReadQueue queue = new SgfReadQueue(worker::add, path -> {
      assertFalse(SwingUtilities.isEventDispatchThread());
      reads.add(path);
      return "(;C[" + path + "])";
    });
    var first = submit(queue, "first");
    var second = submit(queue, "second");
    var latest = submit(queue, "latest");
    assertTrue(first.isCancelled());
    assertTrue(second.isCancelled());
    assertEquals(1, worker.size());
    worker.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals(1, worker.size());
    worker.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals("latest", comment(latest.join()));
    assertEquals(List.of(Path.of("first"), Path.of("latest")), reads);
  }

  @Test
  void ioFailureAndExecutorRejectionDoNotStrandLaterRequests() throws Exception {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    SgfReadQueue queue = new SgfReadQueue(worker::add, path -> {
      if (path.toString().equals("bad")) throw new IOException("read failed");
      return "(;C[ok])";
    });
    var failed = submit(queue, "bad");
    worker.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    assertInstanceOf(IOException.class, assertThrows(java.util.concurrent.CompletionException.class,
        failed::join).getCause());
    var good = submit(queue, "good");
    worker.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals("ok", comment(good.join()));
    var rejecting = new SgfReadQueue(task -> { throw new RejectedExecutionException(); }, path -> "");
    assertTrue(submit(rejecting, "first").isCompletedExceptionally());
    assertTrue(submit(rejecting, "next").isCompletedExceptionally());
  }

  @Test
  void invalidSyntaxSettlesBeforeTheNextPreparedDocument() throws Exception {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    SgfReadQueue queue = new SgfReadQueue(worker::add,
        path -> path.toString().equals("bad") ? "(;AB[aa:])" : "(;C[ok])");
    var failed = submit(queue, "bad");
    worker.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    assertNull(failed.join());
    var good = submit(queue, "good");
    AtomicReference<Boolean> callbackOnEdt = new AtomicReference<>();
    good.thenAccept(document -> callbackOnEdt.set(SwingUtilities.isEventDispatchThread()));
    worker.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals("ok", comment(good.join()));
    assertEquals(true, callbackOnEdt.get());
  }

  private static String comment(SgfReadQueue.PreparedFile file) {
    return assertInstanceOf(SgfReadQueue.PreparedSgf.class, file).document().events().stream()
        .filter(event -> event.name().equals("C"))
        .findFirst().orElseThrow().rawValue();
  }

  @Test
  void stringsShareTheBoundedQueueAndKeepTheirCapturedOptions() throws Exception {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    List<Path> reads = new ArrayList<>();
    SgfReadQueue queue = new SgfReadQueue(worker::add, path -> {
      reads.add(path);
      return "(;C[file])";
    });
    var file = submit(queue, "file");
    var options = new SGFParser.ParseOptions(false, true, false, true, 3, 2, 4.5);
    AtomicReference<CompletableFuture<SgfReadQueue.PreparedFile>> first = new AtomicReference<>();
    AtomicReference<CompletableFuture<SgfReadQueue.PreparedFile>> latest = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> {
      first.set(queue.prepare("(;C[first])", options));
      latest.set(queue.prepare("(;C[latest])", options));
    });
    assertTrue(file.isCancelled());
    assertTrue(first.get().isCancelled());
    assertEquals(1, worker.size());
    worker.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals(1, worker.size());
    worker.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    var prepared = assertInstanceOf(SgfReadQueue.PreparedSgf.class, latest.get().join());
    assertEquals("latest", comment(prepared));
    assertSame(options, prepared.options(), "string policy must not rebuild from live settings");
    assertEquals(List.of(Path.of("file")), reads, "strings perform no file read");
    assertTrue(worker.isEmpty());
  }

  @Test
  void supersededRebuildKeepsTheActiveSlotUntilTheLatestSelectionCanStart() throws Exception {
    double previousKomi = GameInfo.DEFAULT_KOMI;
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    List<Path> reads = new ArrayList<>();
    SgfReadQueue queue = new SgfReadQueue(worker::add, path -> {
      reads.add(path);
      return "(;C[" + path + "])";
    });
    try {
      var first = submit(queue, "first");
      SwingUtilities.invokeAndWait(() -> GameInfo.DEFAULT_KOMI = previousKomi + 1);
      worker.remove().run();
      SwingUtilities.invokeAndWait(() -> {});
      assertFalse(first.isDone());
      assertEquals(1, worker.size(), "the same document is waiting to rebuild");
      var latest = submit(queue, "latest");
      assertTrue(first.isCancelled());
      assertEquals(1, worker.size(), "a pending rebuild still owns the active slot");
      worker.remove().run();
      SwingUtilities.invokeAndWait(() -> {});
      assertEquals(1, worker.size());
      worker.remove().run();
      SwingUtilities.invokeAndWait(() -> {});
      assertEquals("latest", comment(latest.join()));
      assertEquals(List.of(Path.of("first"), Path.of("latest")), reads);
    } finally {
      SwingUtilities.invokeAndWait(() -> GameInfo.DEFAULT_KOMI = previousKomi);
    }
  }

  @Test
  void rejectedRebuildSettlesBeforeAReentrantCompletionStartsAnotherRead() throws Exception {
    double previousKomi = GameInfo.DEFAULT_KOMI;
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    var submissions = new java.util.concurrent.atomic.AtomicInteger();
    SgfReadQueue queue = new SgfReadQueue(task -> {
      if (submissions.incrementAndGet() == 2) throw new RejectedExecutionException();
      worker.add(task);
    }, path -> "(;C[" + path + "])");
    try {
      var first = submit(queue, "first");
      AtomicReference<CompletableFuture<SgfReadQueue.PreparedFile>> next = new AtomicReference<>();
      first.whenComplete((value, failure) -> {
        assertTrue(SwingUtilities.isEventDispatchThread());
        next.set(queue.prepare(Path.of("next")));
      });
      SwingUtilities.invokeAndWait(() -> GameInfo.DEFAULT_KOMI = previousKomi + 1);
      worker.remove().run();
      SwingUtilities.invokeAndWait(() -> {});
      assertInstanceOf(RejectedExecutionException.class,
          assertThrows(java.util.concurrent.CompletionException.class, first::join).getCause());
      assertNotNull(next.get());
      assertEquals(1, worker.size());
      worker.remove().run();
      SwingUtilities.invokeAndWait(() -> {});
      assertEquals("next", comment(next.get().join()));
    } finally {
      SwingUtilities.invokeAndWait(() -> GameInfo.DEFAULT_KOMI = previousKomi);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"syntax", "tree", "rebuild"})
  void supersedingAnInFlightPreparationStopsItsComputationBeforeTheNextRead(String stage)
      throws Exception {
    double previousKomi = GameInfo.DEFAULT_KOMI;
    var worker = Executors.newSingleThreadExecutor();
    var scheduled = new AtomicInteger();
    var execution = new AtomicInteger();
    var running = new AtomicInteger();
    var maximumRunning = new AtomicInteger();
    int cancelledExecution = stage.equals("rebuild") ? 2 : 1;
    var first = new PausedPreparation(
        stage.equals("syntax") ? "featurecat.lizzie.rules.SgfDocument" : SGFParser.class.getName(),
        stage.equals("syntax") ? "valueEnd" : "buildFileHistory", execution, cancelledExecution);
    List<Path> reads = new ArrayList<>();
    String large = stage.equals("syntax")
        ? "(;SZ[9]C[" + "x".repeat(128 * 1024) + "])"
        : "(;SZ[9]" + ";B[];W[]".repeat(1000) + ")";
    SgfReadQueue queue = new SgfReadQueue(task -> {
      int index = scheduled.incrementAndGet();
      worker.execute(() -> {
        execution.set(index);
        maximumRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
        try {
          task.run();
        } finally {
          running.decrementAndGet();
        }
      });
    }, path -> {
      assertFalse(SwingUtilities.isEventDispatchThread());
      reads.add(path);
      if (!path.equals(Path.of("first"))) return "(;SZ[9]C[latest])";
      if (stage.equals("rebuild")) {
        try {
          SwingUtilities.invokeAndWait(() -> GameInfo.DEFAULT_KOMI = previousKomi + 1);
        } catch (Exception failure) {
          throw new IOException(failure);
        }
      }
      return large;
    });
    AtomicReference<Throwable> firstOutcome = new AtomicReference<>();
    first.whenComplete((value, failure) -> firstOutcome.set(failure));
    try {
      submitObserved(queue, first);
      assertTrue(first.reached.await(10, TimeUnit.SECONDS),
          "the real parser must reach the middle of " + stage);
      var latest = submit(queue, "latest");
      assertTrue(first.isCancelled());
      assertInstanceOf(java.util.concurrent.CancellationException.class, firstOutcome.get());
      assertEquals(cancelledExecution, scheduled.get(),
          "cancelling a result must not release the active computation slot");
      assertEquals(List.of(Path.of("first")), reads);
      AtomicReference<Boolean> callbackOnEdt = new AtomicReference<>();
      latest.thenAccept(value -> callbackOnEdt.set(SwingUtilities.isEventDispatchThread()));
      first.release.countDown();
      assertEquals("latest", comment(latest.get(10, TimeUnit.SECONDS)));
      SwingUtilities.invokeAndWait(() -> {});
      assertTrue(first.observedCancellation, "A must consume the cancellation inside its preparation");
      assertFalse(first.continuedAfterCancellation,
          "A must unwind at that checkpoint, not scan/build its remaining input before B");
      assertEquals(true, callbackOnEdt.get());
      assertEquals(List.of(Path.of("first"), Path.of("latest")), reads,
          "rebuilding must reuse syntax rather than reread A");
      assertEquals(1, maximumRunning.get());
    } finally {
      first.release.countDown();
      worker.shutdown();
      assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> GameInfo.DEFAULT_KOMI = previousKomi);
    }
  }

  // Observe the real request's cancellation polls without adding a product parser/test hook.
  private static void submitObserved(SgfReadQueue queue, PausedPreparation result) throws Exception {
    Class<?> requestType = Class.forName(SgfReadQueue.class.getName() + "$Request");
    var constructor = requestType.getDeclaredConstructor(
        Path.class, String.class, SGFParser.ParseOptions.class, CompletableFuture.class);
    constructor.setAccessible(true);
    Object request = constructor.newInstance(Path.of("first"), null, null, result);
    var submit = SgfReadQueue.class.getDeclaredMethod("submit", requestType);
    submit.setAccessible(true);
    SwingUtilities.invokeAndWait(() -> {
      try {
        assertSame(result, submit.invoke(queue, request));
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    });
  }

  private static final class PausedPreparation extends CompletableFuture<SgfReadQueue.PreparedFile> {
    private final String parserClass;
    private final String parserMethod;
    private final AtomicInteger execution;
    private final int targetExecution;
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private int polls;
    private volatile boolean observedCancellation;
    private volatile boolean continuedAfterCancellation;

    private PausedPreparation(
        String parserClass, String parserMethod, AtomicInteger execution, int targetExecution) {
      this.parserClass = parserClass;
      this.parserMethod = parserMethod;
      this.execution = execution;
      this.targetExecution = targetExecution;
    }

    @Override public boolean isCancelled() {
      if (!SwingUtilities.isEventDispatchThread() && execution.get() == targetExecution
          && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
              frame.getClassName().equals(parserClass) && frame.getMethodName().equals(parserMethod)))) {
        if (observedCancellation) continuedAfterCancellation = true;
        // More than the entry checks: both fixtures have substantial input remaining here.
        if (++polls == 64) {
          reached.countDown();
          try {
            assertTrue(release.await(10, TimeUnit.SECONDS), "preparation checkpoint was not released");
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
          }
        }
        if (super.isCancelled()) observedCancellation = true;
      }
      return super.isCancelled();
    }
  }

  private static CompletableFuture<SgfReadQueue.PreparedFile> submit(SgfReadQueue queue, String path)
      throws Exception {
    AtomicReference<CompletableFuture<SgfReadQueue.PreparedFile>> result = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> result.set(queue.prepare(Path.of(path))));
    return result.get();
  }
}
