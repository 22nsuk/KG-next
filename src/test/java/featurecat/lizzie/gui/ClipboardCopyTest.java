package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import org.junit.jupiter.api.Test;

class ClipboardCopyTest {
  private static final StringSelection RESULT = new StringSelection("SGF");

  private static class Watch implements NativeClipboardWatch {
    boolean unchanged = true;
    int closes;
    public boolean unchanged() { return unchanged; }
    public void close() { closes++; }
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
