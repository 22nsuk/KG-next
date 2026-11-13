package featurecat.lizzie.gui;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.FlavorListener;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ContainerEvent;
import java.io.IOException;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;

/** Bridges standard Swing copy/cut actions without replacing transfer handlers or data formats. */
final class SwingClipboardActions {
  private static final Object INSTALLED = new Object();
  private static boolean listening;

  private SwingClipboardActions() {}

  static void install() {
    if (listening) return;
    listening = true;
    Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
      if (event instanceof ContainerEvent added
          && added.getID() == ContainerEvent.COMPONENT_ADDED) {
        installOn(added.getChild());
      }
    }, AWTEvent.CONTAINER_EVENT_MASK);
    for (Window window : Window.getWindows()) installOn(window);
  }

  private static void installOn(Component component) {
    if (component instanceof JComponent target && target.getClientProperty(INSTALLED) == null) {
      target.putClientProperty(INSTALLED, Boolean.TRUE);
      installActions(target);
      target.addPropertyChangeListener("UI", event -> installActions(target));
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) installOn(child);
    }
  }

  private static void installActions(JComponent target) {
    installAction(target, "copy", TransferHandler.COPY, TransferHandler.getCopyAction());
    installAction(target, "cut", TransferHandler.MOVE, TransferHandler.getCutAction());
  }

  private static void installAction(JComponent target, String key, int operation, Action standard) {
    Action action = target.getActionMap().get(key);
    // Preserve explicit application actions. JTextComponent.copy()/cut() use these literal keys
    // even when its UI has no entry; EditorKit keyboard actions call those same methods.
    if (action == standard || (action == null && target instanceof JTextComponent)) {
      target.getActionMap().put(key, new AbstractAction(key) {
        @Override public void actionPerformed(ActionEvent event) {
          TransferHandler handler = target.getTransferHandler();
          if (handler == null) return;
          try {
            handler.exportToClipboard(target,
                new OrderedClipboard(target.getToolkit().getSystemClipboard()), operation);
          } catch (IllegalStateException unavailable) {
            UIManager.getLookAndFeel().provideErrorFeedback(target);
          }
        }
      });
    }
  }

  // The original handler still constructs rich Transferables and owns exportDone (including
  // cut deletion after success). Only an actual write retires a deferred SGF request.
  static final class OrderedClipboard extends Clipboard {
    private final Clipboard delegate;

    OrderedClipboard(Clipboard delegate) {
      super(delegate.getName());
      this.delegate = delegate;
    }

    @Override public void setContents(Transferable contents, ClipboardOwner owner) {
      ClipboardCopy.setContents(delegate, contents, owner);
    }
    @Override public Transferable getContents(Object requestor) {
      return delegate.getContents(requestor);
    }
    @Override public DataFlavor[] getAvailableDataFlavors() {
      return delegate.getAvailableDataFlavors();
    }
    @Override public boolean isDataFlavorAvailable(DataFlavor flavor) {
      return delegate.isDataFlavorAvailable(flavor);
    }
    @Override public Object getData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
      return delegate.getData(flavor);
    }
    @Override public void addFlavorListener(FlavorListener listener) {
      delegate.addFlavorListener(listener);
    }
    @Override public void removeFlavorListener(FlavorListener listener) {
      delegate.removeFlavorListener(listener);
    }
    @Override public FlavorListener[] getFlavorListeners() {
      return delegate.getFlavorListeners();
    }
  }
}
