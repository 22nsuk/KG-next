package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.web.WebBoardManager;
import featurecat.lizzie.rules.SGFParser;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JDialog;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JPasswordField;
import javax.swing.JTable;
import javax.swing.TransferHandler;
import javax.swing.text.DefaultEditorKit;
import javax.swing.SwingUtilities;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

class SgfCopyDesktopTest {
  private static final String LATEST = "(;SZ[5]C[클릭 시점의 주석];B[aa](;W[bb])(;W[cc]))";

  @Test
  void copiesTheClickTimeSnapshotAndOnlyPublishesTheLatestRequest() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.runWithStartupTimeout(
        getClass(), "sgf-copy", List.of(), List.of(), 120, 90);
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) {
    try {
      if (args[0].equals("native-clipboard-writer")) {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String value;
        while ((value = input.readLine()) != null) {
          Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
          System.out.println("written");
          System.out.flush();
        }
        System.exit(0);
      }
      runProbe(Path.of(args[0]), Path.of(args[1]));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void runProbe(Path work, Path result) throws Exception {
    Files.writeString(work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":3,"
            + "\"auto-save-exit\":false,\"resume-previous-game\":false,"
            + "\"auto-check-engine-alive\":false,\"auto-quick-analyze-on-load\":false,"
            + "\"analysis-engine-preload\":false,\"play-sound\":false}}");
    System.setProperty("lizzie.work.dir", work.toString());
    DesktopProbeProcess.phase(result, "application-starting");
    Lizzie.main(new String[0]);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    Field startup = field(LizzieFrame.class, "autoSaveStartupPrepared");
    while (!edt(() -> Lizzie.frame != null && Lizzie.frame.isShowing()
        && startup.getBoolean(Lizzie.frame))) {
      assertTrue(System.nanoTime() < deadline, "application did not start");
      Thread.sleep(10);
    }
    edt(() -> {
      for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
      Lizzie.setPrimaryEngine(null);
      Lizzie.config.loadSgfLast = true;
      assertTrue(SGFParser.loadFromString(LATEST, false));
      return null;
    });
    DesktopProbeProcess.applicationReady(result);
    String expected = edt(() -> SGFParser.saveToString(false));
    edt(() -> {
      assertTrue(SGFParser.loadFromString("(;SZ[5]C[older request];B[dd])", false));
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection("unchanged"), null);
      return null;
    });
    SgfSaveQueue queue = (SgfSaveQueue) field(LizzieFrame.class, "sgfCopies").get(null);
    Executor worker = (Executor) field(SgfSaveQueue.class, "worker").get(queue);
    long saveRevision = SgfSaveCoordinator.acceptedSaveRevision();
    Path jfr = result.resolveSibling("copy.jfr");
    DesktopProbeProcess.phase(result, "copy-ownership");
    try (var recording = new Recording(); var first = new WorkerBlock(worker)) {
      recording.enable("kg.sgf.Phase").withoutThreshold();
      recording.start();
      first.awaitStarted();
      edt(() -> { Lizzie.frame.copySgf(); return null; });
      // Ensure the first request has queued its serializer behind the first worker block.
      edt(() -> null);
      try (var second = new WorkerBlock(worker)) {
        edt(() -> {
          assertTrue(SGFParser.loadFromString(LATEST, false));
          Lizzie.frame.copySgf();
          Lizzie.board.getHistory().getStart().getData().setAuthoredComment("later edit");
          return null;
        });
        assertEquals("unchanged", clipboard());
        assertTrue(SgfSaveCoordinator.pendingSaves().isDone());
        assertEquals(saveRevision, SgfSaveCoordinator.acceptedSaveRevision());
        first.release();
        second.awaitStarted();
        edt(() -> null); // The older serialization has published or retired on the EDT.
        assertEquals("unchanged", clipboard(), "an older copy cannot publish after the newer click");
        second.release();
        queue.pending().get(20, TimeUnit.SECONDS);
      }
      assertEquals(expected, clipboard(), "later live edits cannot alter the clicked snapshot");
      recording.stop();
      recording.dump(jfr);
    }
    var serialized = RecordingFile.readAllEvents(jfr).stream()
        .filter(event -> event.getEventType().getName().equals("kg.sgf.Phase")
            && event.getString("phase").equals("save-serialize")).toList();
    assertEquals(2, serialized.size());
    for (var event : serialized) assertEquals("lizzie-sgf-copy", event.getThread().getJavaName());

    DesktopProbeProcess.phase(result, "copy-image-ownership");
    assertImageCopyWins(queue, worker, () -> Lizzie.frame.saveMainBoardToClipboard());
    assertImageCopyWins(queue, worker, () -> Lizzie.frame.copySubBoard());
    DesktopProbeProcess.phase(result, "copy-url-ownership");
    String url = "http://127.0.0.1:9998/?token=clipboard-test";
    WebBoardManager previousManager = Lizzie.webBoardManager;
    try {
      // Only server readiness/URL are substituted; exercise the real menu and clipboard writes.
      edt(() -> {
        Lizzie.webBoardManager = new WebBoardManager() {
          @Override public boolean isRunning() { return true; }
          @Override public String getAccessUrl() { return url; }
        };
        return null;
      });
      assertTextCopyWins(queue, worker, url, () -> {
        JMenuItem copyUrl = findMenuItem(LizzieFrame.menu.getComponents(),
            Lizzie.resourceBundle.getString("Menu.webBoardCopyUrl"));
        assertNotNull(copyUrl, "web board URL copy menu must be available");
        copyUrl.doClick();
      });
    } finally {
      edt(() -> { Lizzie.webBoardManager = previousManager; return null; });
    }
    DesktopProbeProcess.phase(result, "copy-comment-ownership");
    JIMSendTextPane comment = edt(() -> (JIMSendTextPane)
        field(LizzieFrame.class, "commentEditTextPane").get(Lizzie.frame));
    for (String operation : List.of("copy", "cut")) {
      String text = "주석 " + operation;
      assertTextCopyWins(queue, worker, text, () -> {
        comment.setText(text);
        comment.selectAll();
        JMenuItem action = findMenuItem(comment.getPop().getComponents(),
            Lizzie.resourceBundle.getString("JTextPane." + operation));
        assertNotNull(action);
        action.doClick();
        assertEquals(operation.equals("cut") ? "" : text, comment.getText());
      });
    }
    DesktopProbeProcess.phase(result, "copy-standard-swing");
    JPanel controls = edt(() -> {
      JPanel panel = new JPanel();
      Lizzie.frame.getLayeredPane().add(panel);
      return panel;
    });
    try {
      for (boolean refreshUi : List.of(false, true)) {
        assertTextCopyWins(queue, worker, "late field", () -> {
          JTextField field = new JTextField("late field");
          controls.add(field); // Added after startup; no explicit clipboard registration.
          if (refreshUi) SwingUtilities.updateComponentTreeUI(controls);
          TransferHandler original = field.getTransferHandler();
          field.selectAll();
          field.getActionMap().get(DefaultEditorKit.copyAction).actionPerformed(
              new ActionEvent(field, ActionEvent.ACTION_PERFORMED, "keyboard copy"));
          assertSame(original, field.getTransferHandler(), "keep the native paste/DnD handler");
          field.setText("");
          field.paste();
          assertEquals("late field", field.getText());
          controls.remove(field);
        });
      }
      assertTextCopyWins(queue, worker, "table cell", () -> {
        JTable table = new JTable(new Object[][] {{"table cell"}}, new Object[] {"column"});
        controls.add(table);
        table.selectAll();
        table.getActionMap().get("copy").actionPerformed(
            new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "copy"));
        controls.remove(table);
      });
      try (var blocked = new WorkerBlock(worker)) {
        blocked.awaitStarted();
        String noOpExpected = edt(() -> {
          String captured = SGFParser.saveToString(false);
          Lizzie.frame.copySgf();
          JTextField field = new JTextField("read only");
          controls.add(field);
          field.copy(); // Empty selection must not retire the SGF copy.
          field.setEditable(false);
          field.selectAll();
          field.cut();
          assertEquals("read only", field.getText());
          JPasswordField password = new JPasswordField("secret");
          controls.add(password);
          password.selectAll();
          password.copy();
          password.cut();
          assertEquals("secret", new String(password.getPassword()));
          controls.removeAll();
          return captured;
        });
        blocked.release();
        queue.pending().get(20, TimeUnit.SECONDS);
        assertEquals(noOpExpected, clipboard(), "non-copy actions must not cancel SGF publication");
      }
    } finally {
      edt(() -> { Lizzie.frame.getLayeredPane().remove(controls); return null; });
    }
    DesktopProbeProcess.phase(result, "copy-native-process");
    assertNativeCopyWins(queue, worker, result);
    // A new SGF request after another copy must still be allowed to publish.
    edt(() -> {
      assertTrue(SGFParser.loadFromString(LATEST, false));
      Lizzie.frame.copySgf();
      return null;
    });
    queue.pending().get(20, TimeUnit.SECONDS);
    assertEquals(expected, clipboard());

    DesktopProbeProcess.phase(result, "copy-window-close");
    try (var blocked = new WorkerBlock(worker)) {
      blocked.awaitStarted();
      edt(() -> {
        Lizzie.board.getHistory().getStart().getData().setAuthoredComment("copy after close");
        assertNotEquals(expected, SGFParser.saveToString(false));
        Lizzie.frame.copySgf();
        Lizzie.frame.dispose();
        return null;
      });
      blocked.release();
      queue.pending().get(20, TimeUnit.SECONDS);
      assertEquals(expected, clipboard(), "a closed frame cannot publish its pending copy");
    }
    Files.writeString(result, "PASS");
  }

  private static void assertTextCopyWins(
      SgfSaveQueue queue, Executor worker, String expected, Runnable copyText) throws Exception {
    try (var blocked = new WorkerBlock(worker)) {
      blocked.awaitStarted();
      edt(() -> {
        // Even recopying identical text must supersede the pending SGF request.
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(expected), null);
        Lizzie.frame.copySgf();
        copyText.run();
        return null;
      });
      assertEquals(expected, clipboard());
      blocked.release();
      queue.pending().get(20, TimeUnit.SECONDS);
      assertEquals(expected, clipboard(), "an older SGF copy cannot replace later text");
    }
  }

  private static void assertNativeCopyWins(SgfSaveQueue queue, Executor worker, Path result)
      throws Exception {
    Process writer = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Djava.awt.headless=false", "-cp", System.getProperty("java.class.path"),
        SgfCopyDesktopTest.class.getName(), "native-clipboard-writer")
        .redirectError(result.resolveSibling("native-writer-errors.log").toFile()).start();
    PrintWriter input = new PrintWriter(writer.getOutputStream(), true);
    BufferedReader output = new BufferedReader(new InputStreamReader(writer.getInputStream()));
    try {
      String text = "native clipboard text";
      writeNative(input, output, text);
      // Repeated identical copies by the same OS owner must also retire SGF publication.
      for (int attempt = 0; attempt < 2; attempt++) {
        try (var blocked = new WorkerBlock(worker)) {
          blocked.awaitStarted();
          edt(() -> { Lizzie.frame.copySgf(); return null; });
          writeNative(input, output, text);
          assertEquals(text, clipboard());
          blocked.release();
          queue.pending().get(20, TimeUnit.SECONDS);
          assertEquals(text, clipboard(), "a native copy outside this JVM must win");
        }
      }
    } finally {
      writer.destroyForcibly();
      assertTrue(writer.waitFor(5, TimeUnit.SECONDS), "native clipboard writer did not exit");
      input.close();
      output.close();
    }
  }

  private static void writeNative(PrintWriter input, BufferedReader output, String text)
      throws Exception {
    input.println(text);
    assertEquals("written", CompletableFuture.supplyAsync(() -> {
      try { return output.readLine(); }
      catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }).get(10, TimeUnit.SECONDS));
  }

  private static JMenuItem findMenuItem(Component[] components, String label) {
    for (Component component : components) {
      if (component instanceof JMenuItem item && label.equals(item.getText())) return item;
      if (component instanceof JMenu menu) {
        JMenuItem found = findMenuItem(menu.getMenuComponents(), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void assertImageCopyWins(SgfSaveQueue queue, Executor worker, Runnable copyImage)
      throws Exception {
    try (var blocked = new WorkerBlock(worker)) {
      blocked.awaitStarted();
      BufferedImage copied = edt(() -> {
        // A sentinel ensures a no-op image action cannot satisfy the assertions.
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection("before image"), null);
        Lizzie.frame.copySgf();
        copyImage.run();
        return clipboardImage();
      });
      blocked.release();
      queue.pending().get(20, TimeUnit.SECONDS);
      BufferedImage retained = edt(SgfCopyDesktopTest::clipboardImage);
      assertEquals(copied.getWidth(), retained.getWidth());
      assertEquals(copied.getHeight(), retained.getHeight());
      assertArrayEquals(
          copied.getRGB(0, 0, copied.getWidth(), copied.getHeight(), null, 0, copied.getWidth()),
          retained.getRGB(0, 0, retained.getWidth(), retained.getHeight(), null, 0, retained.getWidth()),
          "an older SGF copy cannot replace a later board image");
    }
  }

  private static BufferedImage clipboardImage() throws Exception {
    var contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
    assertTrue(contents.isDataFlavorSupported(DataFlavor.imageFlavor),
        "the later board image must remain on the clipboard");
    Image image = (Image) contents.getTransferData(DataFlavor.imageFlavor);
    BufferedImage snapshot = new BufferedImage(
        image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = snapshot.createGraphics();
    try { graphics.drawImage(image, 0, 0, null); }
    finally { graphics.dispose(); }
    return snapshot;
  }

  private static String clipboard() throws Exception {
    return edt(() -> (String) Toolkit.getDefaultToolkit().getSystemClipboard()
        .getData(DataFlavor.stringFlavor));
  }

  private static Field field(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(20, TimeUnit.SECONDS);
  }

  private static final class WorkerBlock implements AutoCloseable {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);

    WorkerBlock(Executor worker) {
      worker.execute(() -> {
        started.countDown();
        try {
          assertTrue(release.await(20, TimeUnit.SECONDS), "copy worker was not released");
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failure);
        } finally { finished.countDown(); }
      });
    }

    void awaitStarted() throws Exception { assertTrue(started.await(20, TimeUnit.SECONDS)); }
    void release() { release.countDown(); }
    @Override public void close() throws Exception {
      release();
      assertTrue(finished.await(20, TimeUnit.SECONDS));
    }
  }
}
