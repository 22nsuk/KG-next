package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.EngineStartupDiagnostics;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.logging.DiagnosticBundleExporter;
import java.awt.Component;
import java.awt.Container;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class EngineSynchronizationFailureDesktopTest {
  private static final String COMMAND = "controlled-engine --gtp";
  private static final String DETAIL = "Controlled board synchronization deadline expired";

  @Test
  void basicSynchronizationFailureSurvivesWindowClipboardAndExport() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.run(getClass(), "basic-engine-failure", List.of(), List.of("probe"));
    assertEquals("passed", new JSONObject(Files.readString(result)).getString("result"));
  }

  @Test
  void bundledStartupTimeoutWindowsShareTheCurrentFailurePolicy() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            getClass(), "bundled-timeout-windows", List.of(), List.of("timeout"));
    assertEquals("passed", new JSONObject(Files.readString(result)).getString("result"));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      Files.writeString(work.resolve("config.txt"), "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":2}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
      closeDialogs();
      if ("timeout".equals(args[0])) {
        checkBundledTimeoutWindows();
        Files.writeString(result, new JSONObject().put("result", "passed").toString());
        System.exit(0);
      }
      Field attemptField = Leelaz.class.getDeclaredField("startupDiagnosticAttempt");
      attemptField.setAccessible(true);
      for (boolean ready : List.of(false, true)) {
        Method present = EngineManager.class.getDeclaredMethod(
            "showEngineSynchronizationFailure", Leelaz.class, ready ? String.class : Throwable.class);
        present.setAccessible(true);
        Object reason = ready ? DETAIL : new IllegalStateException(DETAIL);
        Leelaz engine = new Leelaz(COMMAND);
        if (ready) {
          var attempt = EngineStartupDiagnostics.getDefault().begin("controlled", "MAIN_BOARD", List.of("controlled-engine", "--gtp"), true);
          attempt.ready();
          attempt.fail("board-sync", DETAIL);
          assertNull(attempt.snapshot());
          attemptField.set(engine, attempt);
        }
        SwingUtilities.invokeLater(() -> {
          try { present.invoke(Lizzie.engineManager, engine, reason); }
          catch (Exception failure) { throw new RuntimeException(failure); }
        });
        AtomicReference<EngineFailedMessage> shown = new AtomicReference<>();
        await(() -> {
          for (Window window : Window.getWindows())
            if (window instanceof EngineFailedMessage dialog && dialog.isShowing()) shown.set(dialog);
          return shown.get() != null;
        });
        EngineFailedMessage dialog = shown.get();
        new Robot().waitForIdle();
        ImageIO.write(new Robot().createScreenCapture(dialog.getBounds()), "png", result.getParent().resolve("basic-" + ready + ".png").toFile());
        SwingUtilities.invokeAndWait(() -> {
          try { present.invoke(Lizzie.engineManager, engine, reason); }
          catch (Exception failure) { throw new RuntimeException(failure); }
          assertEquals(1, java.util.Arrays.stream(Window.getWindows()).filter(w -> w instanceof EngineFailedMessage && w.isShowing()).count());
          button(dialog, "EngineFailedMessage.details").doClick();
          button(dialog, "EngineFailedMessage.copyError").doClick();
        });
        String copied = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        assertTrue(copied.contains(DETAIL), copied);
        assertTrue(copied.contains("controlled-engine"), copied);
        JSONObject copy = new JSONObject(copied);
        assertTrue(copy.isNull("exitCode"));
        if (ready) {
          SwingUtilities.invokeAndWait(() -> button(dialog, "EngineFailedMessage.exportDiagnostics").doClick());
          SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
              DiagnosticsDialog panel = findPanel(window);
              if (panel != null && window.isShowing()) {
                assertNotNull(panel.currentRequest().startupFailure());
                buttonByText(panel, Lizzie.resourceBundle.getString("DiagnosticsDialog.exportDefault")).doClick();
                return;
              }
            }
            fail("Export dialog missing");
          });
          Path exports = DiagnosticBundleExporter.defaultOutputDirectory(work);
          await(() -> {
            try (var files = Files.list(exports)) {
              Path zip = files.filter(p -> p.toString().endsWith(".zip")).findFirst().orElse(null);
              if (zip == null) return false;
              try (ZipFile archive = new ZipFile(zip.toFile())) {
                var entry = archive.getEntry("snapshots/engine-startup-failure.json");
                if (entry == null) return false;
                JSONObject exported = new JSONObject(new String(archive.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("available", exported.getString("status"));
                assertTrue(exported.toString().contains(DETAIL));
                assertTrue(exported.toString().contains("controlled-engine"));
                return true;
              }
            } catch (java.io.IOException writing) { return false; }
          });
        }
        closeDialogs();
      }
      Files.writeString(result, new JSONObject().put("result", "passed").toString());
      System.exit(0);
    } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
  }

  private static void checkBundledTimeoutWindows() throws Exception {
    PonderProbe engine = new PonderProbe(COMMAND);
    PonderProbe replacement = new PonderProbe("");
    initializeTestStreams(engine);
    Field firstLaunch = Lizzie.class.getDeclaredField("firstLaunchSession");
    firstLaunch.setAccessible(true);
    firstLaunch.setBoolean(null, true);
    Lizzie.setPrimaryEngine(engine);
    dispatchBundledTimeout(engine);
    SwingUtilities.invokeAndWait(
        () -> {
          assertTrue(Lizzie.engineStartupStatus.snapshot().isActionable());
          assertNull(visibleDialog(EngineFailedMessage.class), "first launch diagnostic");
          assertNull(visibleDialog(MoreEngines.class), "first launch engine settings");
        });
    firstLaunch.setBoolean(null, false);

    SwingUtilities.invokeAndWait(
        () -> {
          dispatchBundledTimeout(engine);
          Lizzie.setPrimaryEngine(replacement);
          Lizzie.engineStartupStatus.ready();
        });
    SwingUtilities.invokeAndWait(
        () -> {
          assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
          assertNull(visibleDialog(EngineFailedMessage.class), "queued stale primary diagnostic");
          assertNull(visibleDialog(MoreEngines.class), "queued stale primary engine settings");
        });

    for (boolean replaceSelection : List.of(true, false)) {
      Lizzie.setPrimaryEngine(engine);
      dispatchBundledTimeout(engine);
      SwingUtilities.invokeAndWait(
          () -> {
            JDialog failure = visibleDialog(EngineFailedMessage.class);
            assertNotNull(failure, "current startup timeout diagnostic");
            if (replaceSelection) Lizzie.setPrimaryEngine(replacement);
            else initializeTestStreams(engine);
            failure.dispose();
          });
      SwingUtilities.invokeAndWait(
          () -> assertNull(visibleDialog(MoreEngines.class), "engine settings for stale failure"));
    }

    Lizzie.setPrimaryEngine(engine);
    engine.Pondering();
    dispatchBundledTimeout(engine);
    SwingUtilities.invokeAndWait(
        () -> {
          JDialog failure = visibleDialog(EngineFailedMessage.class);
          assertNotNull(failure);
          dispatchBundledTimeout(engine);
        });
    SwingUtilities.invokeAndWait(
        () -> {
          assertEquals(1, visibleDialogs(EngineFailedMessage.class).size(), "duplicate diagnostic");
          assertNull(visibleDialog(MoreEngines.class), "settings before closing the diagnostic");
          visibleDialog(EngineFailedMessage.class).dispose();
        });
    SwingUtilities.invokeAndWait(
        () -> {
          assertNotNull(visibleDialog(MoreEngines.class), "settings after closing the diagnostic");
          dispatchBundledTimeout(engine);
        });
    SwingUtilities.invokeAndWait(
        () -> {
          assertNull(visibleDialog(EngineFailedMessage.class), "diagnostic reopened from settings");
          assertEquals(1, visibleDialogs(MoreEngines.class).size(), "duplicate engine settings");
          assertFalse(engine.isPondering(), "opening settings pauses the original engine");
          Lizzie.setPrimaryEngine(replacement);
          visibleDialog(MoreEngines.class).dispose();
        });
    SwingUtilities.invokeAndWait(
        () -> {
          assertFalse(engine.isPondering(), "closing settings resumed the superseded engine");
          assertFalse(replacement.isPondering(), "closing settings started the replacement engine");
        });
  }

  private static final class PonderProbe extends Leelaz {
    private PonderProbe(String command) throws Exception {
      super(command);
    }

    @Override
    public void ponder() {
      Pondering();
    }
  }

  private static void initializeTestStreams(Leelaz engine) {
    try {
      Method initialize = Leelaz.class.getDeclaredMethod(
          "initializeStreams", InputStream.class, OutputStream.class, InputStream.class);
      initialize.setAccessible(true);
      initialize.invoke(engine, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
          new ByteArrayInputStream(new byte[0]));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void dispatchBundledTimeout(Leelaz engine) {
    try {
      Method capture = Leelaz.class.getDeclaredMethod("captureEngineIncarnationFence");
      capture.setAccessible(true);
      Method dispatch = Leelaz.class.getDeclaredMethod(
          "dispatchBundledStartupTimeoutPresentationForTest", Object.class, String.class);
      dispatch.setAccessible(true);
      dispatch.invoke(engine, capture.invoke(engine), DETAIL);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static List<JDialog> visibleDialogs(Class<?> type) {
    return java.util.Arrays.stream(Window.getWindows())
        .filter(window -> window instanceof JDialog && window.isShowing())
        .map(JDialog.class::cast)
        .filter(dialog -> type.isInstance(dialog) || type.isInstance(dialog.getContentPane()))
        .toList();
  }

  private static JDialog visibleDialog(Class<?> type) {
    return visibleDialogs(type).stream().findFirst().orElse(null);
  }

  private static JButton button(Container container, String name) {
    JButton found = findButton(container, name, false);
    if (found == null) for (Window window : Window.getWindows()) {
      found = findButton(window, name, false);
      if (found != null) break;
    }
    assertNotNull(found, name);
    return found;
  }

  private static JButton buttonByText(Container container, String text) {
    JButton found = findButton(container, text, true);
    assertNotNull(found, text);
    return found;
  }

  private static JButton findButton(Container container, String key, boolean text) {
    for (Component component : container.getComponents()) {
      if (component instanceof JButton b && key.equals(text ? b.getText() : b.getName())) return b;
      if (component instanceof Container child) {
        JButton found = findButton(child, key, text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static DiagnosticsDialog findPanel(Container container) {
    if (container instanceof DiagnosticsDialog panel) return panel;
    for (Component component : container.getComponents()) if (component instanceof Container child) {
      DiagnosticsDialog found = findPanel(child);
      if (found != null) return found;
    }
    return null;
  }

  private static void closeDialogs() throws Exception {
    SwingUtilities.invokeAndWait(() -> { for (Window w : Window.getWindows()) if (w instanceof JDialog) w.dispose(); });
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Desktop condition timed out");
      Thread.sleep(50);
    }
  }
}
