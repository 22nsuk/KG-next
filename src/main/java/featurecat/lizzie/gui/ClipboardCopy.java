package featurecat.lizzie.gui;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.Transferable;

/** Orders application and Swing clipboard writes against deferred SGF copies. */
final class ClipboardCopy {
  private static long revision;

  private ClipboardCopy() {}

  static synchronized long beginRequest() {
    return ++revision;
  }

  static synchronized void setContents(Transferable contents) {
    setContents(Toolkit.getDefaultToolkit().getSystemClipboard(), contents, null);
  }

  static synchronized void setContents(
      Clipboard clipboard, Transferable contents, ClipboardOwner owner) {
    ++revision;
    clipboard.setContents(contents, owner);
  }

  static synchronized boolean publish(long request, Transferable contents) {
    // Check and write under the same lock so another copy cannot slip between them.
    if (request != revision) return false;
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents, null);
    return true;
  }
}
