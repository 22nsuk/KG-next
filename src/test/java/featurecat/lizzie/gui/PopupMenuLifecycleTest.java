package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class PopupMenuLifecycleTest {
  @Test
  void hideKeepsInputGuardUntilDelayAndCleansHoverAfterTheMenuAction() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            List<String> order = new ArrayList<>();
            f.cleanup = () -> order.add("cleanup");
            f.first.setVisible(true);
            f.first.setVisible(false);
            JMenuItem item = (JMenuItem) f.first.getComponent(0);
            item.addActionListener(event -> order.add("action"));
            item.doClick(0);
            assertEquals(List.of(true), f.states);
            assertEquals(List.of("action"), order);
            assertEquals(200, f.tasks.get(0).delay);
            f.deferred.get(0).run();
            assertEquals(List.of("action", "cleanup"), order);
            f.tasks.get(0).fire();
            assertEquals(List.of(true, false), f.states);
          }
        });
  }

  @Test
  void reopeningSameMenuRejectsAlreadyQueuedCleanupAndTimer() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            f.first.setVisible(true);
            f.first.setVisible(false);
            Task old = f.tasks.get(0);
            f.first.setVisible(true);
            assertTrue(old.cancelled);
            f.deferred.get(0).run();
            old.fire(); // Cancellation cannot retract a callback already queued for delivery.
            assertTrue(f.first.isVisible());
            assertEquals(0, f.cleanups);
            assertEquals(List.of(true, true), f.states);
            f.first.setVisible(false);
            f.tasks.get(1).fire();
            assertEquals(List.of(true, true, false), f.states);
          }
        });
  }

  @Test
  void switchingMenuKindsSharesOneGuardAndRejectsOldHideEvents() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            f.first.setVisible(true);
            f.first.setVisible(false);
            f.second.setVisible(true);
            f.deferred.get(0).run();
            f.tasks.get(0).fire();
            assertEquals(List.of(true, true), f.states);
            assertEquals(0, f.cleanups);
            // Also exercise reversed notification order: the new popup opens before the old hides.
            f.first.setVisible(true);
            int scheduled = f.tasks.size();
            // Swing may already have hidden the old popup when switching the selection path.
            // Deliver that old notification again after the new opening to test the owner check.
            for (var listener : f.second.getPopupMenuListeners()) {
              listener.popupMenuWillBecomeInvisible(new javax.swing.event.PopupMenuEvent(f.second));
            }
            assertEquals(scheduled, f.tasks.size(), "an old menu's hide must not schedule a release");
            f.first.setVisible(false);
            f.tasks.get(f.tasks.size() - 1).fire();
            assertEquals(List.of(true, true, true, false), f.states);
          }
        });
  }

  @Test
  void oldTimerCannotShortenTheNewMenusOwnGracePeriod() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            f.first.setVisible(true);
            f.first.setVisible(false);
            f.second.setVisible(true);
            f.second.setVisible(false);
            f.tasks.get(0).fire();
            assertEquals(List.of(true, true), f.states);
            f.tasks.get(1).fire();
            assertEquals(List.of(true, true, false), f.states);
          }
        });
  }

  @Test
  void cancelThenInvisibleSchedulesOnlyOneRelease() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            f.first.setVisible(true);
            // Swing's cancellation path fires canceled followed by invisible.
            f.first.cancel();
            assertEquals(1, f.tasks.size());
            assertEquals(1, f.deferred.size());
            f.tasks.get(0).fire();
            assertEquals(List.of(true, false), f.states);
          }
        });
  }

  @Test
  void hiddenEventsWithoutAnOpeningDoNotChangeOtherState() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            f.first.setVisible(false);
            f.second.setVisible(false);
            assertTrue(f.tasks.isEmpty());
            assertTrue(f.states.isEmpty());
          }
        });
  }

  @Test
  void invokerDisposalRetiresBothCallbacksAndRemovesItsListener() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            int initialListeners = f.mainBoard.getHierarchyListeners().length;
            f.first.setVisible(true);
            assertEquals(initialListeners + 1, f.mainBoard.getHierarchyListeners().length);
            f.first.setVisible(false);
            f.mainBoard.removeNotify();
            assertTrue(f.tasks.get(0).cancelled);
            assertEquals(initialListeners, f.mainBoard.getHierarchyListeners().length);
            f.deferred.get(0).run();
            f.tasks.get(0).fire();
            assertEquals(0, f.cleanups);
            assertEquals(List.of(true, false), f.states);
          }
        });
  }

  @Test
  void hidingInvokerWhilePopupIsVisibleReleasesTheGuard() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            f.first.setVisible(true);
            f.mainBoard.setVisible(false);
            f.first.setVisible(false);
            assertEquals(List.of(true, false), f.states);
            assertTrue(f.tasks.isEmpty());
          }
        });
  }

  @Test
  void desktopDisposalAlsoRetiresAPopupOnTheIndependentBoard() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            f.second.setVisible(true);
            f.second.setVisible(false);
            f.owner.removeNotify();
            assertTrue(f.tasks.get(0).cancelled);
            f.tasks.get(0).fire();
            assertEquals(List.of(true, false), f.states);
          }
        });
  }

  @Test
  void switchingBoardsDetachesOldInvokerAndItsDisposalCannotResetNewPopup() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            int initialListeners = f.mainBoard.getHierarchyListeners().length;
            f.first.setVisible(true);
            f.first.setVisible(false);
            f.first.setInvoker(f.independentBoard);
            f.first.setVisible(true);
            assertEquals(initialListeners, f.mainBoard.getHierarchyListeners().length);
            f.mainBoard.removeNotify();
            f.tasks.get(0).fire();
            assertEquals(List.of(true, true), f.states);
            f.independentBoard.removeNotify();
            assertEquals(List.of(true, true, false), f.states);
          }
        });
  }

  @Test
  void replacementDesktopCannotBeChangedByOldCallbacksEvenWhenMenuIsReused() throws Exception {
    onEdt(
        () -> {
          try (Fixture old = new Fixture(); Fixture replacement = new Fixture()) {
            old.first.setVisible(true);
            old.first.setVisible(false);
            old.scope.set(replacement.lifecycle);
            old.first.setInvoker(replacement.mainBoard);
            old.first.setVisible(true);
            assertTrue(old.tasks.get(0).cancelled);
            old.deferred.get(0).run();
            old.tasks.get(0).fire();
            old.owner.removeNotify();
            assertEquals(List.of(true, false), old.states);
            assertEquals(List.of(true), replacement.states);
            assertEquals(0, old.cleanups);
            old.first.setVisible(false);
            replacement.tasks.get(0).fire();
            assertEquals(List.of(true, false), replacement.states);
          }
        });
  }

  @Test
  void repeatedOpeningsKeepAtMostOneTimerAndDoNotAccumulateInvokerListeners() throws Exception {
    onEdt(
        () -> {
          try (Fixture f = new Fixture()) {
            int initialListeners = f.mainBoard.getHierarchyListeners().length;
            for (int i = 0; i < 100; i++) {
              f.first.setVisible(true);
              f.first.setVisible(false);
              assertEquals(initialListeners + 1, f.mainBoard.getHierarchyListeners().length);
              assertEquals(1L, f.tasks.stream().filter(task -> !task.cancelled).count());
            }
            f.tasks.get(99).fire();
            assertEquals(initialListeners, f.mainBoard.getHierarchyListeners().length);
            assertEquals(0L, f.tasks.stream().filter(task -> !task.cancelled).count());
          }
        });
  }

  @Test
  void realSwingTimerAndDeferredCleanupBothRunOnEdt() throws Exception {
    CountDownLatch released = new CountDownLatch(1);
    CountDownLatch cleaned = new CountDownLatch(1);
    AtomicInteger offEdt = new AtomicInteger();
    AtomicInteger releaseCount = new AtomicInteger();
    AtomicReference<JPanel> owner = new AtomicReference<>();
    onEdt(
        () -> {
          JPanel panel = new JPanel();
          panel.addNotify();
          owner.set(panel);
          PopupMenuLifecycle lifecycle =
              new PopupMenuLifecycle(
                  panel,
                  value -> {
                    if (!SwingUtilities.isEventDispatchThread()) offEdt.incrementAndGet();
                    if (!value) {
                      releaseCount.incrementAndGet();
                      released.countDown();
                    }
                  });
          JPopupMenu popup = new JPopupMenu();
          popup.add(new JMenuItem("item"));
          popup.setInvoker(panel);
          PopupMenuLifecycle.install(
              popup,
              () -> lifecycle,
              () -> () -> {
                if (!SwingUtilities.isEventDispatchThread()) offEdt.incrementAndGet();
                cleaned.countDown();
              });
          popup.setVisible(true);
          popup.setVisible(false);
        });
    try {
      assertTrue(cleaned.await(5, TimeUnit.SECONDS));
      assertTrue(released.await(5, TimeUnit.SECONDS));
      onEdt(() -> {});
      assertEquals(0, offEdt.get());
      assertEquals(1, releaseCount.get());
    } finally {
      onEdt(() -> owner.get().removeNotify());
    }
  }

  private static void onEdt(Runnable task) throws Exception {
    SwingUtilities.invokeAndWait(task);
  }

  private static final class TestPopup extends JPopupMenu {
    private static final long serialVersionUID = 1L;

    void cancel() {
      firePopupMenuCanceled();
      setVisible(false);
    }
  }

  private static final class Task {
    final int delay;
    final Runnable callback;
    boolean cancelled;

    Task(int delay, Runnable callback) {
      this.delay = delay;
      this.callback = callback;
    }

    void fire() {
      callback.run();
    }
  }

  /** Real Swing popup events and lightweight peers; no global Lizzie state, Unsafe, or sleeps. */
  private static final class Fixture implements AutoCloseable {
    final JPanel owner = new JPanel();
    final JPanel mainBoard = new JPanel();
    final JPanel independentBoard = new JPanel();
    final List<Boolean> states = new ArrayList<>();
    final List<Task> tasks = new ArrayList<>();
    final List<Runnable> deferred = new ArrayList<>();
    final PopupMenuLifecycle lifecycle;
    final AtomicReference<PopupMenuLifecycle> scope = new AtomicReference<>();
    final TestPopup first = new TestPopup();
    final JPopupMenu second = new JPopupMenu();
    int cleanups;
    Runnable cleanup = () -> cleanups++;

    Fixture() {
      owner.addNotify();
      mainBoard.addNotify();
      independentBoard.addNotify();
      lifecycle =
          new PopupMenuLifecycle(
              owner,
              value -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                states.add(value);
              },
              (delay, callback) -> {
                Task task = new Task(delay, callback);
                tasks.add(task);
                return () -> task.cancelled = true;
              },
              deferred::add);
      scope.set(lifecycle);
      first.add(new JMenuItem("empty intersection"));
      second.add(new JMenuItem("stone"));
      first.setInvoker(mainBoard);
      second.setInvoker(independentBoard);
      PopupMenuLifecycle.install(first, scope::get, () -> cleanup);
      PopupMenuLifecycle.install(second, scope::get, () -> () -> {});
    }

    @Override
    public void close() {
      first.setVisible(false);
      second.setVisible(false);
      owner.removeNotify();
      mainBoard.removeNotify();
      independentBoard.removeNotify();
      MenuSelectionManager.defaultManager().clearSelectedPath();
    }
  }
}
