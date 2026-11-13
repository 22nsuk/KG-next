package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.rules.SgfDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

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

  private static String comment(SgfDocument document) {
    return document.events().stream().filter(event -> event.name().equals("C"))
        .findFirst().orElseThrow().rawValue();
  }

  private static CompletableFuture<SgfDocument> submit(SgfReadQueue queue, String path) throws Exception {
    AtomicReference<CompletableFuture<SgfDocument>> result = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> result.set(queue.prepare(Path.of(path))));
    return result.get();
  }
}
