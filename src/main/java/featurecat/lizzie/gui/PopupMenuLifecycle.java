package featurecat.lizzie.gui;

import java.awt.Component;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/** EDT-confined input guard shared by the board popups belonging to one desktop window. */
final class PopupMenuLifecycle {
  static final int RELEASE_DELAY_MS = 200;

  @FunctionalInterface
  interface Scheduler {
    /** Schedules on the EDT and returns a cancellation action. */
    Runnable schedule(int delayMillis, Runnable task);
  }

  private final Consumer<Boolean> showing;
  private final Scheduler scheduler;
  private final Consumer<Runnable> later;
  private Opening current;
  private Runnable cancelRelease;

  PopupMenuLifecycle(JComponent owner, Consumer<Boolean> showing) {
    this(owner, showing, PopupMenuLifecycle::scheduleSwingTimer, SwingUtilities::invokeLater);
  }

  PopupMenuLifecycle(
      JComponent owner, Consumer<Boolean> showing, Scheduler scheduler, Consumer<Runnable> later) {
    requireEdt();
    this.showing = Objects.requireNonNull(showing);
    this.scheduler = Objects.requireNonNull(scheduler);
    this.later = Objects.requireNonNull(later);
    // The owner holds this listener, not a global registry. Disposing it retires pending work even
    // when the popup was invoked from the independent board rather than this window's hierarchy.
    owner.addHierarchyListener(
        event -> {
          if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0
              && !owner.isDisplayable()) {
            reset();
          }
        });
  }

  static void install(
      JPopupMenu popup, Supplier<PopupMenuLifecycle> owner, Supplier<Runnable> afterHide) {
    popup.addPopupMenuListener(
        new PopupMenuListener() {
          private PopupMenuLifecycle lifecycle;

          @Override
          public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
            PopupMenuLifecycle next = owner.get();
            if (lifecycle != null && lifecycle != next) lifecycle.release(popup);
            lifecycle = next;
            lifecycle.opened(popup, afterHide.get());
          }

          @Override
          public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
            if (lifecycle != null) lifecycle.hidden(popup);
          }

          @Override
          public void popupMenuCanceled(PopupMenuEvent event) {
            if (lifecycle != null) lifecycle.hidden(popup);
          }
        });
  }

  private static Runnable scheduleSwingTimer(int delayMillis, Runnable task) {
    Timer timer = new Timer(delayMillis, event -> task.run());
    timer.setRepeats(false);
    timer.start();
    return timer::stop;
  }

  private void opened(JPopupMenu popup, Runnable afterHide) {
    requireEdt();
    retireCurrent();
    Opening opening = new Opening(popup, afterHide);
    current = opening;
    if (opening.invoker != null) opening.invoker.addHierarchyListener(opening.hierarchyListener);
    showing.accept(true);
  }

  private void hidden(JPopupMenu popup) {
    requireEdt();
    Opening opening = current;
    if (opening == null || opening.popup != popup || opening.hidden) return;
    opening.hidden = true;
    // Preserve the post-action hover cleanup and click-through grace period. Both callbacks belong
    // to this opening, never a later reopening of the same menu or a different board popup.
    later.accept(
        () -> {
          requireEdt();
          if (current == opening) opening.afterHide.run();
        });
    cancelRelease =
        scheduler.schedule(
            RELEASE_DELAY_MS,
            () -> {
              requireEdt();
              if (current == opening) reset();
            });
  }

  private void release(JPopupMenu popup) {
    requireEdt();
    if (current != null && current.popup == popup) reset();
  }

  private void reset() {
    requireEdt();
    if (current == null) return;
    retireCurrent();
    showing.accept(false);
  }

  private void retireCurrent() {
    Opening previous = current;
    current = null;
    if (cancelRelease != null) {
      cancelRelease.run();
      cancelRelease = null;
    }
    if (previous != null && previous.invoker != null) {
      previous.invoker.removeHierarchyListener(previous.hierarchyListener);
    }
  }

  private final class Opening {
    final JPopupMenu popup;
    final Component invoker;
    final Runnable afterHide;
    final HierarchyListener hierarchyListener;
    boolean hidden;

    Opening(JPopupMenu popup, Runnable afterHide) {
      this.popup = popup;
      this.invoker = popup.getInvoker();
      this.afterHide = Objects.requireNonNull(afterHide);
      hierarchyListener =
          event -> {
            if ((event.getChangeFlags()
                        & (HierarchyEvent.DISPLAYABILITY_CHANGED | HierarchyEvent.SHOWING_CHANGED))
                    != 0
                && !invoker.isShowing()
                && current == this) {
              reset();
            }
          };
    }
  }

  private static void requireEdt() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("Popup lifecycle changes must run on the EDT");
    }
  }
}
