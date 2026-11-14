package featurecat.lizzie.gui;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.Transferable;

/** Retires deferred SGF copies when another request or OS clipboard write supersedes them. */
final class ClipboardCopy {
  private static Request current;

  private ClipboardCopy() {}

  static synchronized Request beginRequest() {
    cancelPending();
    return beginRequest(NativeClipboardWatch.capture());
  }

  static synchronized Request beginRequest(NativeClipboardWatch watch) {
    cancelPending();
    current = new Request(watch);
    return current;
  }

  static synchronized void cancelPending() {
    if (current != null) current.close();
  }

  static synchronized void setContents(Transferable contents) {
    cancelPending();
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents, null);
  }

  static synchronized boolean publish(Request request, Transferable contents) {
    return publish(request, Toolkit.getDefaultToolkit().getSystemClipboard(), contents);
  }

  static synchronized boolean publish(Request request, Clipboard clipboard, Transferable contents) {
    try {
      if (request != current || !request.watch.unchanged()) return false;
      // Native observation covers uncoordinated writers too. OS APIs do not provide a portable
      // compare-and-set: another process can still write concurrently with this final publication.
      clipboard.setContents(contents, null);
      return true;
    } finally {
      request.close();
    }
  }

  static final class Request implements AutoCloseable {
    private final NativeClipboardWatch watch;
    private boolean closed;

    private Request(NativeClipboardWatch watch) { this.watch = watch; }

    @Override public void close() {
      synchronized (ClipboardCopy.class) {
        if (closed) return;
        closed = true;
        if (current == this) current = null;
        watch.close();
      }
    }
  }
}
