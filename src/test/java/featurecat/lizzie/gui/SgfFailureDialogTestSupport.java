package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.InvocationEvent;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.SwingUtilities;

/** Gives real failure notices a native owner in tests whose frame was allocated without a constructor. */
public final class SgfFailureDialogTestSupport extends EventQueue implements AutoCloseable {
  private final Set<AWTEvent> notices = ConcurrentHashMap.newKeySet();

  private SgfFailureDialogTestSupport() {}

  public static SgfFailureDialogTestSupport install() {
    assertTrue(SwingUtilities.isEventDispatchThread());
    var queue = new SgfFailureDialogTestSupport();
    Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
    return queue;
  }

  @Override
  public void postEvent(AWTEvent event) {
    if (event instanceof InvocationEvent
        && Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
            frame.getClassName().equals(LizzieFrame.class.getName())
                && frame.getMethodName().equals("showOpenFileFailedMessageLater"))) {
      notices.add(event);
    }
    super.postEvent(event);
  }

  @Override
  protected void dispatchEvent(AWTEvent event) {
    if (!notices.remove(event)) {
      super.dispatchEvent(event);
      return;
    }
    // Keep the real frame throughout asynchronous ownership checks and parser application.
    // Only the actual JOptionPane notice uses Swing's shared owner; desktop tests cover
    // the production window owner. Do not depend on CompletableFuture listener ordering.
    LizzieFrame frame = Lizzie.frame;
    Lizzie.frame = null;
    try {
      super.dispatchEvent(event);
    } finally {
      if (Lizzie.frame == null) Lizzie.frame = frame;
    }
  }

  @Override
  public void close() {
    assertTrue(SwingUtilities.isEventDispatchThread());
    pop();
  }
}
