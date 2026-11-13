package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.SGFParser;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/** Real clipboard, replacement dialogs, local HTTP and the production import/apply boundary. */
class SgfStringImportDesktopTest {
  @Test
  void largeFilePasteAndOnlineImportsAgreeAndRespectReplacementConfirmation() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.runWithStartupTimeout(
        getClass(), "sgf-string-import", List.of(), List.of(), 120, 120);
    assertEquals("PASS", Files.readString(result));
    assertNotNull(ImageIO.read(result.resolveSibling("imported-ko.png").toFile()));
  }

  public static void main(String[] args) {
    try {
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
    awaitEdt(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
    var startup = LizzieFrame.class.getDeclaredField("autoSaveStartupPrepared");
    startup.setAccessible(true);
    awaitEdt(() -> startup.getBoolean(Lizzie.frame));
    edt(() -> {
      for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
      Lizzie.setPrimaryEngine(null);
      Lizzie.frame.setBounds(20, 20, 1180, 820);
      Lizzie.config.loadSgfLast = true;
      Lizzie.config.readKomi = true;
      return null;
    });
    DesktopProbeProcess.applicationReady(result);
    Path fixtures = Files.createDirectory(work.resolve("fixtures"));
    var fixture = SgfPerformanceProbe.fixtures(fixtures).get(0);
    assertEquals(21845, fixture.nodes());
    DesktopProbeProcess.phase(result, "file-import");
    assertTrue(edt(() -> Lizzie.frame.openFileAsync(fixture.path().toFile(), true, false))
        .get(20, TimeUnit.SECONDS));
    SgfReadTestSupport.awaitPending();
    String expected = edt(() -> SGFParser.saveToString(false));

    DesktopProbeProcess.phase(result, "paste-confirmation");
    resetGame();
    var original = Lizzie.board.getHistory();
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(fixture.text()), null);
    try (var answer = new DialogAnswer(JOptionPane.CANCEL_OPTION, false)) {
      edt(() -> { Lizzie.frame.pasteSgf(); return null; });
      assertTrue(answer.answered.get(5, TimeUnit.SECONDS));
    }
    assertSame(original, Lizzie.board.getHistory());
    SgfReadTestSupport.awaitPending();

    DesktopProbeProcess.phase(result, "paste-preparation");
    Path recordingFile = result.resolveSibling("paste.jfr");
    try (var recording = new Recording(); var read = SgfReadTestSupport.block()) {
      recording.enable("kg.sgf.Phase").withoutThreshold();
      recording.start();
      var superseded = edt(() -> Lizzie.frame.openFileAsync(fixture.path().toFile(), true, false));
      read.awaitStarted();
      try (var answer = new DialogAnswer(JOptionPane.OK_OPTION, false)) {
        edt(() -> { Lizzie.frame.pasteSgf(); return null; });
        assertTrue(answer.answered.get(5, TimeUnit.SECONDS));
      }
      edt(() -> {
        assertSame(original, Lizzie.board.getHistory(), "the EDT responds while preparation is pending");
        assertFalse(Lizzie.board.isLoadingFile);
        return null;
      });
      read.release();
      assertFalse(superseded.get(20, TimeUnit.SECONDS));
      SgfReadTestSupport.awaitPending();
      recording.stop();
      recording.dump(recordingFile);
    }
    assertEquals(expected, edt(() -> SGFParser.saveToString(false)));
    assertPreparedOffEdt(recordingFile);

    DesktopProbeProcess.phase(result, "comment-editor-ownership");
    var editorField = LizzieFrame.class.getDeclaredField("commentEditTextPane");
    editorField.setAccessible(true);
    for (boolean closeEditor : List.of(false, true)) {
      resetGame();
      var editedHistory = Lizzie.board.getHistory();
      try (var read = SgfReadTestSupport.block()) {
        edt(() -> Lizzie.frame.openFileAsync(fixture.path().toFile(), true, false));
        read.awaitStarted();
        var pending = edt(() -> Lizzie.frame.loadSgfStringAsync(fixture.text(), 0, true, false, null));
        edt(() -> {
          Lizzie.frame.setCommentEditable(true);
          ((javax.swing.text.JTextComponent) editorField.get(Lizzie.frame)).setText("준비 중 작성한 주석");
          if (closeEditor) Lizzie.frame.setCommentEditable(false);
          return null;
        });
        read.release();
        assertFalse(pending.get(20, TimeUnit.SECONDS));
        assertSame(editedHistory, Lizzie.board.getHistory());
        edt(() -> {
          if (!closeEditor) {
            assertTrue(Lizzie.frame.commentEditPane.isVisible());
            assertEquals("준비 중 작성한 주석",
                ((javax.swing.text.JTextComponent) editorField.get(Lizzie.frame)).getText());
            Lizzie.frame.setCommentEditable(false);
          }
          assertEquals("준비 중 작성한 주석", Lizzie.board.getData().comment);
          return null;
        });
      }
    }
    edt(() -> {
      Lizzie.frame.setCommentEditable(true);
      ((javax.swing.text.JTextComponent) editorField.get(Lizzie.frame)).setText("요청 전 작성한 주석");
      return null;
    });
    var draftHistory = Lizzie.board.getHistory();
    assertTrue(edt(() -> Lizzie.frame.loadSgfStringAsync(fixture.text(), 0, true, false, null))
        .get(20, TimeUnit.SECONDS));
    edt(() -> {
      assertFalse(Lizzie.frame.commentEditPane.isVisible());
      assertEquals("요청 전 작성한 주석", draftHistory.getData().comment);
      return null;
    });
    assertEquals(expected, edt(() -> SGFParser.saveToString(false)));

    DesktopProbeProcess.phase(result, "online-import");
    resetGame();
    var beforeOnline = Lizzie.board.getHistory();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] body = fixture.text().getBytes(StandardCharsets.UTF_8);
    server.createContext("/record.sgf", exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "application/x-go-sgf; charset=UTF-8");
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    OnlineDialog online = edt(() -> new OnlineDialog(Lizzie.frame));
    try {
      edt(() -> {
        online.applyChangeWeb("http://127.0.0.1:" + server.getAddress().getPort() + "/record.sgf");
        return null;
      });
      awaitEdt(() -> Lizzie.board.getHistory() != beforeOnline && !Lizzie.board.isLoadingFile);
      SgfReadTestSupport.awaitPending();
      assertEquals(expected, edt(() -> SGFParser.saveToString(false)));
      var beforeInvalidOnline = Lizzie.board.getHistory();
      server.createContext("/invalid.sgf", exchange -> {
        byte[] invalid = "(;AB[aa:])".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, invalid.length);
        try (var output = exchange.getResponseBody()) { output.write(invalid); }
      });
      try (var answer = new DialogAnswer(0, true)) {
        edt(() -> {
          online.applyChangeWeb("http://127.0.0.1:" + server.getAddress().getPort() + "/invalid.sgf");
          assertSame(beforeInvalidOnline, Lizzie.board.getHistory(), "HTTP admission preserves the game");
          return null;
        });
        assertTrue(answer.answered.get(10, TimeUnit.SECONDS));
        SgfReadTestSupport.awaitPending();
      }
      assertSame(beforeInvalidOnline, Lizzie.board.getHistory());
      assertEquals(expected, edt(() -> SGFParser.saveToString(false)));
    } finally {
      edt(() -> { online.stopSync(); online.dispose(); return null; });
      server.stop(0);
    }

    DesktopProbeProcess.phase(result, "rejected-string");
    var beforeFailure = Lizzie.board.getHistory();
    try (var answer = new DialogAnswer(0, true)) {
      var failed = edt(() -> Lizzie.frame.loadSgfStringAsync("(;AB[aa:])", 0, true, false, null));
      assertFalse(failed.get(20, TimeUnit.SECONDS));
      assertTrue(answer.answered.get(5, TimeUnit.SECONDS));
      SgfReadTestSupport.awaitPending();
    }
    assertSame(beforeFailure, Lizzie.board.getHistory());
    assertEquals(expected, edt(() -> SGFParser.saveToString(false)));
    ImageIO.write(new Robot().createScreenCapture(edt(() -> Lizzie.frame.getBounds())),
        "png", result.resolveSibling("imported-ko.png").toFile());
    Files.writeString(result, "PASS");
  }

  private static void resetGame() throws Exception {
    edt(() -> {
      assertTrue(SGFParser.loadFromString("(;SZ[19]C[현재 기보];B[dd])", false));
      return null;
    });
  }

  private static void assertPreparedOffEdt(Path file) throws Exception {
    var phases = RecordingFile.readAllEvents(file).stream()
        .filter(event -> event.getEventType().getName().equals("kg.sgf.Phase")).toList();
    for (String phase : List.of("parse-syntax", "parse-build-tree")) {
      var events = phases.stream().filter(event -> event.getString("phase").equals(phase)).toList();
      assertEquals(1, events.size(), phase);
      assertEquals("lizzie-sgf-read", events.get(0).getThread().getJavaName(), phase);
    }
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(20, TimeUnit.SECONDS);
  }

  private static void awaitEdt(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!edt(condition)) {
      assertTrue(System.nanoTime() < deadline, "application state did not settle");
      Thread.sleep(10);
    }
  }

  private static final class DialogAnswer implements AWTEventListener, AutoCloseable {
    final CompletableFuture<Boolean> answered = new CompletableFuture<>();
    private final int option;
    private final boolean failureNotice;

    DialogAnswer(int option, boolean failureNotice) {
      this.option = option;
      this.failureNotice = failureNotice;
      Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.WINDOW_EVENT_MASK);
    }

    @Override public void eventDispatched(AWTEvent event) {
      if (event.getID() != WindowEvent.WINDOW_OPENED || answered.isDone()) return;
      if (failureNotice && event.getSource() instanceof Message message) {
        message.dispose();
        answered.complete(true);
      } else if (!failureNotice && event.getSource() instanceof JDialog dialog) {
        JOptionPane pane = optionPane(dialog);
        if (pane != null) {
          pane.setValue(option);
          answered.complete(true);
        }
      }
    }

    private static JOptionPane optionPane(Container parent) {
      for (Component child : parent.getComponents()) {
        if (child instanceof JOptionPane pane) return pane;
        if (child instanceof Container container) {
          JOptionPane found = optionPane(container);
          if (found != null) return found;
        }
      }
      return null;
    }

    @Override public void close() {
      Toolkit.getDefaultToolkit().removeAWTEventListener(this);
    }
  }
}
