package featurecat.lizzie.gui;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** Runs filesystem mutations off the EDT and returns their result to the UI owner. */
final class SetupBackgroundAction {
  private SetupBackgroundAction() {}

  static <T> Thread start(
      String name,
      Callable<T> action,
      Consumer<T> succeeded,
      Consumer<Exception> failed,
      Consumer<Thread> finished) {
    Thread worker =
        new Thread(
            () -> {
              Runnable publication = () -> {};
              try {
                T result = action.call();
                publication = () -> succeeded.accept(result);
              } catch (Exception failure) {
                publication = () -> failed.accept(failure);
              } finally {
                Thread completed = Thread.currentThread();
                Runnable deliver = publication;
                // A modal success/error dialog pumps the EDT. Keep cleanup in the same callback
                // so a nested event loop cannot unlock a result that is still being delivered.
                SwingUtilities.invokeLater(
                    () -> {
                      try {
                        deliver.run();
                      } finally {
                        finished.accept(completed);
                      }
                    });
              }
            },
            name);
    worker.start();
    return worker;
  }
}
