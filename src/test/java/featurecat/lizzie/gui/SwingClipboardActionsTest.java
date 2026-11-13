package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.Test;

class SwingClipboardActionsTest {
  @Test
  void forwardsRichContentsAndTheOriginalClipboardOwner() throws Exception {
    Clipboard nativeClipboard = new Clipboard("local test clipboard");
    Clipboard ordered = new SwingClipboardActions.OrderedClipboard(nativeClipboard);
    CountDownLatch ownershipLost = new CountDownLatch(1);
    ClipboardOwner owner = (clipboard, contents) -> {
      assertSame(nativeClipboard, clipboard);
      ownershipLost.countDown();
    };
    Transferable rich = new Transferable() {
      public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[] {DataFlavor.stringFlavor, DataFlavor.selectionHtmlFlavor};
      }
      public boolean isDataFlavorSupported(DataFlavor flavor) {
        return flavor.equals(DataFlavor.stringFlavor) || flavor.equals(DataFlavor.selectionHtmlFlavor);
      }
      public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
        return flavor.equals(DataFlavor.stringFlavor) ? "text" : "<b>text</b>";
      }
    };
    long pending = ClipboardCopy.beginRequest();
    ordered.setContents(rich, owner);
    assertSame(rich, nativeClipboard.getContents(null));
    assertEquals("<b>text</b>", ordered.getData(DataFlavor.selectionHtmlFlavor));
    assertFalse(ClipboardCopy.publish(pending, new StringSelection("stale SGF")));
    nativeClipboard.setContents(new StringSelection("replacement"), null);
    assertTrue(ownershipLost.await(5, TimeUnit.SECONDS));
  }

  @Test
  void failedNativeWritePreservesTextAndPropagatesTheClipboardFailure() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      JTextPane text = new JTextPane();
      text.setText("must not be cut");
      text.selectAll();
      Clipboard unavailable = new Clipboard("busy") {
        @Override public void setContents(Transferable contents, ClipboardOwner owner) {
          throw new IllegalStateException("busy");
        }
      };
      assertThrows(IllegalStateException.class, () -> text.getTransferHandler().exportToClipboard(
          text, new SwingClipboardActions.OrderedClipboard(unavailable), TransferHandler.MOVE));
      assertEquals("must not be cut", text.getText());
    });
  }
}
