package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.EngineStartupProgress;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.Utils;
import java.awt.Dialog;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Real process -> shared startup budget -> manager selection -> actual setup window. */
public class KataGoSetupReadinessDesktopTest {
  @Test
  void setupWaitsBeyondThirtyFiveSecondsThenDisplaysTheCommittedEngine() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.run(KataGoSetupReadinessDesktopTest.class,
        "setup-readiness", List.of(), List.of("probe"), 120);
    JSONObject evidence = new JSONObject(Files.readString(result));
    assertEquals("passed", evidence.getString("result"));
    assertTrue(evidence.getLong("waitingMillis") >= 36_000L);
    assertTrue(Files.isRegularFile(Path.of(evidence.getString("waitingScreenshot"))));
    assertTrue(Files.isRegularFile(Path.of(evidence.getString("activeScreenshot"))));
  }

  public static void main(String[] args) throws Exception {
    if (args[0].equals("engine")) {
      Path peer = Path.of(args[1]);
      Files.createDirectories(peer);
      Files.writeString(peer.resolve("warming.pid"), Long.toString(ProcessHandle.current().pid()));
      System.err.println("2026-11-16 12:34:56+0900: Creating new plan cache for controlled model");
      System.err.flush();
      await(() -> Files.exists(peer.resolve("release")), 80);
      ControlledGtpPeer.main(new String[] {peer.toString()});
      return;
    }
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      probe(work, result);
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(result,
          new JSONObject().put("result", "failed").put("error", failure.toString()).toString(2));
      System.exit(1);
    }
  }

  private static void probe(Path work, Path result) throws Exception {
    Files.writeString(work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":3}}");
    System.setProperty("lizzie.work.dir", work.toString());
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing(), 30);
    SwingUtilities.invokeAndWait(() -> {
      for (Window window : Window.getWindows()) if (window instanceof Dialog) window.dispose();
    });

    Path peer = Files.createDirectories(work.resolve("peer"));
    Path java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    EngineData entry = new EngineData();
    entry.index = 0;
    entry.name = "Controlled KataGo startup";
    entry.commands = quote(java.toString()) + " -cp " + quote(System.getProperty("java.class.path"))
        + " " + KataGoSetupReadinessDesktopTest.class.getName() + " engine " + quote(peer.toString());
    entry.preload = false;
    entry.width = entry.height = 19;
    entry.komi = 7.5f;
    Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
    EngineManager manager = Lizzie.engineManager;
    manager.refreshEngineCatalog();
    SetupSnapshot setup = KataGoAutoSetupHelper.inspectSavedEngine(entry);
    var constructor = SetupResult.class.getDeclaredConstructor(
        SetupSnapshot.class, int.class, String.class, boolean.class);
    constructor.setAccessible(true);
    SetupResult applied = constructor.newInstance(setup, 0, entry.name, false);
    AtomicReference<KataGoAutoSetupDialog> shown = new AtomicReference<>();
    AtomicReference<Timer> timer = new AtomicReference<>();
    String successMessage = "선택한 엔진 적용 완료";
    SwingUtilities.invokeAndWait(() -> {
      try {
        KataGoAutoSetupDialog dialog = new KataGoAutoSetupDialog(Lizzie.frame);
        shown.set(dialog);
        Method begin = KataGoAutoSetupDialog.class.getDeclaredMethod(
            "beginWeightSwitchDisplay", SetupSnapshot.class);
        begin.setAccessible(true);
        long displayToken = (long) begin.invoke(dialog, setup);
        var selection = manager.switchEngineTrackedIfAvailable(0, true).orElseThrow();
        Method wait = KataGoAutoSetupDialog.class.getDeclaredMethod("waitForAppliedEngine",
            SetupResult.class, String.class, boolean.class, boolean.class, long.class,
            EngineManager.class, long.class);
        wait.setAccessible(true);
        wait.invoke(dialog, applied, successMessage, false, false, displayToken, manager, selection.token());
        timer.set((Timer) field(dialog, "pendingWeightSwitchTimer"));
        dialog.setVisible(true);
      } catch (ReflectiveOperationException error) {
        throw new AssertionError(error);
      }
    });
    KataGoAutoSetupDialog dialog = shown.get();
    Leelaz engine = manager.engineList.get(0);
    try {
      await(() -> {
        var progress = manager.engineSwitchStartupProgress(manager.engineSwitchUiSnapshot(true));
        return progress != null && progress.stage() == EngineStartupProgress.Stage.TENSORRT_PLAN;
      }, 15);
      DesktopProbeProcess.phase(result, "waiting-past-old-ui-timeout");
      long started = System.nanoTime();
      // This wall-clock interval exercises the removed UI-only 35-second failure. The child is
      // held by an explicit release file; elapsed time never supplies the engine's Ready signal.
      while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(36)) {
        SwingUtilities.invokeAndWait(() -> {
          assertEquals(EngineManager.EngineSwitchUiPhase.SWITCHING,
              manager.engineSwitchUiSnapshot(true).phase());
          assertTrue(timer.get().isRunning(), "setup must keep observing its pending engine");
          assertFalse(engine.isLoaded());
        });
        Thread.sleep(100L);
      }
      long waitingMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      SwingUtilities.invokeAndWait(() -> {
        try {
          JLabel status = (JLabel) field(dialog, "progressStatusLabel");
          assertTrue(status.isShowing(), "startup progress must be visible in the setup window");
          assertTrue(status.getText().contains(Lizzie.resourceBundle.getString("AutoSetup.weightSwitchPlan")));
          assertTrue(status.getFontMetrics(status.getFont()).stringWidth(status.getText())
              <= status.getWidth(), "startup budget must fit the visible footer");
        } catch (ReflectiveOperationException error) {
          throw new AssertionError(error);
        }
      });
      Path waitingScreenshot = result.getParent().resolve("setup-waiting.png");
      capture(dialog, waitingScreenshot);
      Files.writeString(peer.resolve("release"), "ready\n");
      await(() -> manager.isEngineSwitchActive(0, true), 20);
      await(() -> !timer.get().isRunning(), 10);
      SwingUtilities.invokeAndWait(() -> {
        try {
          assertTrue(engine.isLoaded());
          JLabel status = (JLabel) field(dialog, "lblStatus");
          assertEquals(successMessage, status.getText());
          assertTrue(status.isShowing(), "the committed result must remain visible after progress ends");
          assertTrue(dialog.getBounds().contains(
              new Rectangle(status.getLocationOnScreen(), status.getSize())),
              "the completed status must remain inside the setup window");
          assertNull(field(dialog, "pendingWeightSwitchTimer"));
        } catch (ReflectiveOperationException error) {
          throw new AssertionError(error);
        }
      });
      Path activeScreenshot = result.getParent().resolve("setup-active.png");
      capture(dialog, activeScreenshot);
      Files.writeString(result, new JSONObject().put("result", "passed")
          .put("waitingMillis", waitingMillis).put("controlledEngine", true)
          .put("waitingScreenshot", waitingScreenshot.toString())
          .put("activeScreenshot", activeScreenshot.toString()).toString(2));
    } finally {
      SwingUtilities.invokeAndWait(dialog::dispose);
      engine.normalQuit();
    }
  }

  private static void capture(KataGoAutoSetupDialog dialog, Path path) throws Exception {
    new Robot().waitForIdle();
    ImageIO.write(new Robot().createScreenCapture(dialog.getBounds()), "png", path.toFile());
  }

  private static Object field(KataGoAutoSetupDialog dialog, String name)
      throws ReflectiveOperationException {
    Field field = KataGoAutoSetupDialog.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(dialog);
  }

  private static String quote(String value) { return "\"" + value + "\""; }

  private static void await(BooleanSupplier ready, int seconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (!ready.getAsBoolean()) {
      if (System.nanoTime() - deadline >= 0L) throw new AssertionError("Readiness probe timed out");
      Thread.sleep(25L);
    }
  }
}
