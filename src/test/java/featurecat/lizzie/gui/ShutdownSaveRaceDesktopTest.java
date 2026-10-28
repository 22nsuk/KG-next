package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real board edits, automatic SGF/manifest writes, and shutdown; only process exit is intercepted. */
class ShutdownSaveRaceDesktopTest {
  @ParameterizedTest
  @ValueSource(strings = {"move", "play-undo", "comment", "replace", "later-save"})
  void editsDuringExitSaveCancelShutdownAndRetrySavesCurrentGame(String change) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.run(
        getClass(), "shutdown-save-" + change, List.of(), List.of(change));
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) throws Exception {
    String change = args[0];
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      Files.writeString(work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},"
              + "\"logging\":{\"diagnostics-enabled\":false},"
              + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,"
              + "\"auto-save-exit\":true,\"resume-previous-game\":false,"
              + "\"play-sound\":false,\"use-language\":3}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      awaitEdt(() -> Lizzie.frame != null && Lizzie.frame.isShowing() && startupPrepared());
      onEdt(() -> {
        dismissDialogs();
        Lizzie.setPrimaryEngine(null);
        Lizzie.config.autoSaveOnExit = true;
        Lizzie.board.place(0, 0, Stone.BLACK);
        assertEquals(1, Lizzie.board.getCurrentMovenumber());
        return null;
      });
      SgfSaveTestSupport.awaitPending();
      CompletableFuture<Integer> exited = new CompletableFuture<>();
      CountDownLatch laterStarted = new CountDownLatch(1);
      CountDownLatch releaseLater = new CountDownLatch(1);
      String expected;
      BoardHistoryList edited;
      Path exitSgf = work.resolve("save/autoGame1.sgf");
      try (var held = SgfSaveTestSupport.holdNextPublication()) {
        onEdt(() -> { shutdown(exited::complete); return null; });
        held.awaitWritten();
        String frozen = Files.readString(exitSgf);
        assertTrue(frozen.contains(";B[aa]"));
        assertFalse(exited.isDone());
        if (change.equals("later-save")) {
          onEdt(() -> {
            SgfSaveCoordinator.submitSave(() -> {
              laterStarted.countDown();
              try {
                if (!releaseLater.await(10, TimeUnit.SECONDS)) {
                  throw new java.io.IOException("later save was not released");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(interrupted);
              }
              return null;
            }, () -> true, ignored -> {}, failure -> fail(failure));
            return null;
          });
          held.release();
          assertTrue(laterStarted.await(5, TimeUnit.SECONDS));
        }
        onEdt(() -> {
          assertTrue(Lizzie.frame.isShowing());
          switch (change) {
            case "move", "later-save" -> Lizzie.board.place(1, 1, Stone.WHITE);
            case "play-undo" -> {
              Lizzie.board.place(1, 1, Stone.WHITE);
              assertTrue(Lizzie.board.previousMove(true));
            }
            case "comment" -> Lizzie.board.getData().comment = "edited during shutdown";
            case "replace" -> assertTrue(SGFParser.loadFromString("(;SZ[19];B[dd])"));
            default -> throw new AssertionError(change);
          }
          return null;
        });
        edited = onEdt(() -> Lizzie.board.getHistory());
        expected = onEdt(() -> SGFParser.captureSave(Lizzie.board, true));
        assertNotEquals(frozen, expected, "the test must change persisted game content");
        held.release();
        releaseLater.countDown();
      } finally {
        releaseLater.countDown();
      }
      // Await the shutdown continuation, not just the save future's completion.
      awaitEdt(() -> !shutdownWaiting());
      onEdt(() -> {
        assertFalse(exited.isDone(), "shutdown discarded edits accepted after the final snapshot");
        assertTrue(Lizzie.frame.isShowing());
        assertSame(edited, Lizzie.board.getHistory());
        assertEquals(expected, SGFParser.captureSave(Lizzie.board, true));
        dismissDialogs();
        return null;
      });
      assertNotEquals(expected, Files.readString(exitSgf), "the first save must remain its frozen snapshot");

      // An unchanged second close must complete normally and persist exactly the retained edits.
      onEdt(() -> { shutdown(exited::complete); return null; });
      assertEquals(0, exited.get(10, TimeUnit.SECONDS));
      assertEquals(expected, Files.readString(exitSgf));
      Files.writeString(result, "PASS");
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void shutdown(IntConsumer exit) throws Exception {
    Method shutdown = Lizzie.class.getDeclaredMethod("shutdown", IntConsumer.class);
    shutdown.setAccessible(true);
    shutdown.invoke(null, exit);
  }

  private static boolean shutdownWaiting() {
    try {
      Field field = Lizzie.class.getDeclaredField("shutdownSaveWait");
      field.setAccessible(true);
      return ((java.util.concurrent.atomic.AtomicReference<?>) field.get(null)).get() != null;
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static boolean startupPrepared() {
    try {
      Field field = LizzieFrame.class.getDeclaredField("autoSaveStartupPrepared");
      field.setAccessible(true);
      return field.getBoolean(Lizzie.frame);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void dismissDialogs() {
    for (Window window : Window.getWindows()) {
      if (window instanceof JDialog) window.dispose();
    }
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(5, TimeUnit.SECONDS);
  }

  private static void awaitEdt(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!onEdt(condition::getAsBoolean)) {
      if (System.nanoTime() >= deadline) throw new AssertionError("shutdown condition timed out");
      Thread.sleep(20);
    }
  }
}
