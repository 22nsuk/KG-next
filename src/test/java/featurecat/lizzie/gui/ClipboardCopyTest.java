package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ClipboardCopyTest {
  private static final StringSelection RESULT = new StringSelection("SGF");

  private static class Watch implements NativeClipboardWatch {
    boolean unchanged = true;
    int closes;
    public boolean unchanged() { return unchanged; }
    public void close() { closes++; }
  }

  @Test void copyBurstRetiresQueuedWorkAndReleasesEveryWatch() throws Exception {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    SgfSaveQueue queue = new SgfSaveQueue(worker::add, Runnable::run,
        (path, text) -> fail("clipboard copies must not write files"));
    Clipboard clipboard = new Clipboard("test");
    clipboard.setContents(new StringSelection("unchanged"), null);
    List<Integer> serialized = new ArrayList<>();
    List<Watch> watches = new ArrayList<>();
    List<CompletableFuture<Integer>> copies = new ArrayList<>();
    for (int index = 0; index < 100; index++) {
      int clicked = index;
      Watch watch = new Watch();
      watches.add(watch);
      var request = ClipboardCopy.beginRequest(watch);
      var copy = queue.submitLatest(() -> {
        serialized.add(clicked);
        return clicked;
      }, () -> true,
          value -> ClipboardCopy.publish(request, clipboard, new StringSelection("SGF " + value)),
          error -> fail(error));
      copy.whenComplete((value, failure) -> request.close());
      copies.add(copy);
    }
    assertEquals(1, worker.size());
    assertEquals(98, copies.stream().filter(CompletableFuture::isCancelled).count());
    for (int index = 0; index < 99; index++) assertEquals(1, watches.get(index).closes);
    assertEquals(0, watches.get(99).closes);
    var pending = queue.pending();
    worker.remove().run();
    assertEquals("unchanged", clipboard.getData(DataFlavor.stringFlavor));
    assertFalse(pending.isDone());
    worker.remove().run();
    assertDoesNotThrow(pending::join);
    assertEquals(List.of(0, 99), serialized);
    assertEquals("SGF 99", clipboard.getData(DataFlavor.stringFlavor));
    assertTrue(worker.isEmpty());
    for (Watch watch : watches) assertEquals(1, watch.closes);
  }

  @Test void latestSerializationFailureReleasesWatchAndAllowsRetry() throws Exception {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    SgfSaveQueue queue = new SgfSaveQueue(worker::add, Runnable::run,
        (path, text) -> fail("clipboard copies must not write files"));
    Clipboard clipboard = new Clipboard("test");
    clipboard.setContents(new StringSelection("external"), null);
    Watch watch = new Watch();
    var request = ClipboardCopy.beginRequest(watch);
    List<Throwable> failures = new ArrayList<>();
    var failed = queue.<String>submitLatest(() -> { throw new IOException("serialize failed"); },
        () -> true, value -> fail("failed serialization must not publish"), failures::add);
    failed.whenComplete((value, failure) -> request.close());
    worker.remove().run();
    assertTrue(failed.isCompletedExceptionally());
    assertEquals(1, failures.size());
    assertEquals("serialize failed", failures.get(0).getMessage());
    assertEquals(1, watch.closes);
    assertEquals("external", clipboard.getData(DataFlavor.stringFlavor));
    Watch retryWatch = new Watch();
    var retry = ClipboardCopy.beginRequest(retryWatch);
    var recovered = queue.submitLatest(() -> "retry", () -> true,
        value -> ClipboardCopy.publish(retry, clipboard, new StringSelection(value)), error -> fail(error));
    recovered.whenComplete((value, failure) -> retry.close());
    worker.remove().run();
    assertEquals("retry", recovered.join());
    assertEquals("retry", clipboard.getData(DataFlavor.stringFlavor));
    assertEquals(1, retryWatch.closes);
    assertDoesNotThrow(() -> queue.pending().join());
  }

  @Test void unchangedClipboardPublishesAndClosesOnce() throws Exception {
    Watch watch = new Watch();
    Clipboard clipboard = new Clipboard("test");
    var request = ClipboardCopy.beginRequest(watch);
    assertTrue(ClipboardCopy.publish(request, clipboard, RESULT));
    assertEquals("SGF", clipboard.getData(DataFlavor.stringFlavor));
    request.close();
    assertEquals(1, watch.closes);
    assertFalse(ClipboardCopy.publish(request, clipboard, new StringSelection("duplicate")));
  }

  @Test void nativeChangePreservesNewContents() throws Exception {
    Watch watch = new Watch();
    Clipboard clipboard = new Clipboard("test");
    var request = ClipboardCopy.beginRequest(watch);
    clipboard.setContents(new StringSelection("native text"), null);
    watch.unchanged = false;
    assertFalse(ClipboardCopy.publish(request, clipboard, RESULT));
    assertEquals("native text", clipboard.getData(DataFlavor.stringFlavor));
    assertEquals(1, watch.closes);
  }

  @Test void supersedingRequestReleasesOldWatchWithoutClosingNewOne() {
    Watch first = new Watch();
    var old = ClipboardCopy.beginRequest(first);
    Watch second = new Watch();
    var latest = ClipboardCopy.beginRequest(second);
    assertEquals(1, first.closes);
    assertFalse(ClipboardCopy.publish(old, new Clipboard("test"), RESULT));
    assertEquals(0, second.closes);
    assertTrue(ClipboardCopy.publish(latest, new Clipboard("test"), RESULT));
    assertEquals(1, second.closes);
  }

  @Test void failedOrCancelledOperationClosesWithoutPublishing() {
    Watch watch = new Watch();
    var request = ClipboardCopy.beginRequest(watch);
    request.close(); // The serializer/completion failure path uses this same close operation.
    ClipboardCopy.cancelPending();
    assertFalse(ClipboardCopy.publish(request, new Clipboard("test"), RESULT));
    assertEquals(1, watch.closes);
  }

  @Test void observationFailureRejectsPublicationAndReleasesResources() {
    Watch watch = new Watch() {
      public boolean unchanged() { throw new IllegalStateException("observation failed"); }
    };
    var request = ClipboardCopy.beginRequest(watch);
    assertThrows(IllegalStateException.class,
        () -> ClipboardCopy.publish(request, new Clipboard("test"), RESULT));
    assertEquals(1, watch.closes);
  }

  @Test void nativeWriteFailureReleasesResourcesAndPropagates() {
    Watch watch = new Watch();
    var request = ClipboardCopy.beginRequest(watch);
    Clipboard busy = new Clipboard("busy") {
      public void setContents(Transferable contents, ClipboardOwner owner) {
        throw new IllegalStateException("busy");
      }
    };
    assertThrows(IllegalStateException.class, () -> ClipboardCopy.publish(request, busy, RESULT));
    assertEquals(1, watch.closes);
  }
}
