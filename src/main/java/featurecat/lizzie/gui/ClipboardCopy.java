package featurecat.lizzie.gui;

import java.awt.Toolkit;
import java.awt.datatransfer.Transferable;

/** Orders the application's explicit clipboard writes, including deferred SGF copies. */
final class ClipboardCopy {
  private static long revision;

  private ClipboardCopy() {}

  static synchronized long beginRequest() {
    return ++revision;
  }

  static synchronized void setContents(Transferable contents) {
    publish(beginRequest(), contents);
  }

  static synchronized boolean publish(long request, Transferable contents) {
    // Check and write under the same lock so another copy cannot slip between them.
    if (request != revision) return false;
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents, null);
    return true;
  }
}
