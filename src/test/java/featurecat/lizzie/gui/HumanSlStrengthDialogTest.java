package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisResourceCoordinator;
import featurecat.lizzie.analysis.HumanSlStrengthGuiFixture;
import featurecat.lizzie.analysis.HumanSlStrengthPreparation;
import featurecat.lizzie.analysis.HumanSlStrengthReuseSession;
import featurecat.lizzie.analysis.PlayerStrengthEstimator;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Real production windows in owned JVMs; the manual worker is a controlled local computation. */
public final class HumanSlStrengthDialogTest {
  @Test
  void modelSelectionIsManualAndBackgroundPreferenceSurvivesRestart() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path first = probe("selection", List.of());
    assertTrue(Files.readString(first).contains("selection-manual=true"));
    Path reopened = probe("reopen", List.of(first.getParent().resolve("work").toString()));
    assertTrue(Files.readString(reopened).contains("preference-reopened=true"));
  }

  @Test
  void explicitSupplementReturnsFrozenAnalysisResult() throws Exception {
    DesktopProbeProcess.requireDisplay();
    assertTrue(Files.readString(probe("complete", List.of())).contains("completed=true"));
  }

  @Test
  void cancelEscapeAndCloseRetireLateManualCompletion() throws Exception {
    DesktopProbeProcess.requireDisplay();
    assertTrue(Files.readString(probe("cancel", List.of())).contains("retired=3"));
  }

  @Test
  void changedGameRejectsLateManualResult() throws Exception {
    DesktopProbeProcess.requireDisplay();
    assertTrue(Files.readString(probe("change", List.of())).contains("stale-rejected=true"));
  }

  private static Path probe(String scenario, List<String> extra) throws Exception {
    var args = new java.util.ArrayList<String>();
    args.add(scenario);
    args.addAll(extra);
    return DesktopProbeProcess.run(
        HumanSlStrengthDialogTest.class,
        "humansl40-" + scenario,
        List.of("-Duser.language=ko", "-Duser.country=KR"),
        args);
  }

  public static void main(String[] args) throws Exception {
    String scenario = args[0];
    Path work = Path.of(args[args.length - 2]);
    Path result = Path.of(args[args.length - 1]);
    Path profile = scenario.equals("reopen") ? Path.of(args[1]) : work;
    int exit = 1;
    try {
      DesktopProbeProcess.phase(result, "production-startup");
      System.setProperty("user.home", work.toAbsolutePath().toString());
      if (!scenario.equals("reopen")) {
        Files.writeString(
            profile.resolve("config.txt"),
            "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{"
                + "\"autoload-empty\":true,\"first-time-load\":false,"
                + "\"auto-quick-analyze-on-load\":false}}",
            StandardCharsets.UTF_8);
      }
      System.setProperty("lizzie.work.dir", profile.toAbsolutePath().toString());
      Lizzie.main(new String[0]);
      onEdt(HumanSlStrengthGuiFixture::loadSyntheticGame);
      assertTrue(onEdtValue(() -> Lizzie.frame != null && Lizzie.frame.isDisplayable()));
      DesktopProbeProcess.phase(result, "scenario-" + scenario);
      String evidence = switch (scenario) {
        case "selection" -> selectAndSave(result);
        case "reopen" -> reopenPreference();
        case "complete" -> manualComplete(work, result);
        case "cancel" -> manualCancel(work, result);
        case "change" -> manualGameChange(work, result);
        default -> throw new IllegalArgumentException("Unknown scenario " + scenario);
      };
      var shutdown = onEdtValue(() -> Lizzie.frame.shutdownHumanSlPreparation());
      shutdown.get(5, TimeUnit.SECONDS);
      onEdt(() -> {
        for (Window window : Window.getWindows()) window.dispose();
      });
      assertFalse(AnalysisResourceCoordinator.hasActiveLocalComputeProcess());
      Files.writeString(result, evidence + "\nowned-worker-cleanup=true\n");
      exit = 0;
    } catch (Throwable failure) {
      failure.printStackTrace(System.err);
      Files.writeString(result, "failure=" + failure);
    } finally {
      System.exit(exit);
    }
  }

  private static String selectAndSave(Path result) throws Exception {
    assertEquals(PlayerStrengthEstimator.StrengthModel.XGBOOST20TUN, PlayerStrengthEstimator.activeModel());
    assertFalse(HumanSlStrengthPreparation.backgroundEnabled());
    JDialog original = openDashboard();
    AtomicReference<JComboBox<?>> selector = new AtomicReference<>();
    onEdt(() -> {
      selector.set(find(original, JComboBox.class,
          c -> c.getItemCount() > 0 && c.getItemAt(0) instanceof PlayerStrengthEstimator.StrengthModel));
      assertNotNull(selector.get());
      boolean previous = false;
      for (int i = 0; i < selector.get().getItemCount(); i++)
        previous |= PlayerStrengthEstimator.StrengthModel.XGBOOST20TUN_PREVIOUS.equals(selector.get().getItemAt(i));
      assertTrue(previous, "The previous 20TUN model must remain available");
      selector.get().setSelectedItem(PlayerStrengthEstimator.StrengthModel.HUMANSL40);
    });
    awaitEdt(() -> dashboard() != null && dashboard() != original);
    JDialog selected = onEdtValue(HumanSlStrengthDialogTest::dashboard);
    onEdt(() -> {
      assertNull(progressDialog());
      assertNotNull(find(selected, AbstractButton.class, b -> "strength40-run".equals(b.getName())));
      JCheckBox background = find(selected, JCheckBox.class, c -> "strength40-background".equals(c.getName()));
      assertNotNull(background);
      assertFalse(background.isSelected());
      Lizzie.frame.pollHumanSlPreparation();
      assertFalse(Lizzie.frame.humanSlStrengthPreparation().hasWork());
      assertFalse(AnalysisResourceCoordinator.hasActiveLocalComputeProcess());
    });
    screenshot(selected, result.resolveSibling("dashboard-manual-pending.png"));
    for (String tab : List.of("match", "performance")) {
      Component oldPanel = onEdtValue(() -> selected.getContentPane().getComponent(0));
      onEdt(() -> {
        find(selected, AbstractButton.class,
            b -> Lizzie.resourceBundle.getString("PlayerStrengthEstimate.tab." + tab).equals(b.getText()))
            .doClick();
        var data = Lizzie.board.getHistory().getStart().getData();
        synchronized (data) {
          data.setPlayouts(data.getPlayouts() + 1);
        }
      });
      awaitEdt(() -> selected.getContentPane().getComponent(0) != oldPanel);
      assertTrue(onEdtValue(() -> find(selected, AbstractButton.class,
          b -> Lizzie.resourceBundle.getString("PlayerStrengthEstimate.tab." + tab).equals(b.getText()))
          .isSelected()), "A live analysis refresh must retain the selected dashboard tab");
      screenshot(selected, result.resolveSibling("dashboard-" + tab + ".png"));
    }
    onEdt(() -> {
      find(selected, JCheckBox.class, c -> "strength40-background".equals(c.getName())).doClick();
      assertTrue(HumanSlStrengthPreparation.backgroundEnabled());
      selected.dispose();
    });
    return "selection-manual=true\nbackground-default-off=true\nprevious-model-preserved=true\nbackground-saved=true";
  }

  private static String reopenPreference() throws Exception {
    assertTrue(HumanSlStrengthPreparation.backgroundEnabled());
    assertEquals(PlayerStrengthEstimator.StrengthModel.XGBOOST20TUN, PlayerStrengthEstimator.activeModel());
    JDialog dashboard = openDashboard();
    onEdt(() -> {
      JCheckBox background = find(dashboard, JCheckBox.class, c -> "strength40-background".equals(c.getName()));
      assertTrue(background.isSelected());
      background.doClick();
      assertFalse(HumanSlStrengthPreparation.backgroundEnabled());
      dashboard.dispose();
    });
    return "preference-reopened=true\nbackground-off-saved=true";
  }

  private static String manualComplete(Path work, Path result) throws Exception {
    try (var manual = new HumanSlStrengthGuiFixture.Manual()) {
      var before = onEdtValue(HumanSlStrengthReuseSession::capture);
      var pending = beginManual(work, manual);
      manual.awaitStarted();
      screenshot(onEdtValue(HumanSlStrengthDialogTest::progressDialog), result.resolveSibling("manual-progress.png"));
      manual.complete();
      awaitEdt(pending.returned()::get);
      manual.awaitClosed();
      var report = pending.result().get();
      assertNotNull(report);
      assertTrue(report.model.isHumanSl40());
      assertEquals(6, report.black.sampleCount);
      assertEquals(6, report.white.sampleCount);
      assertTrue(Double.isFinite(report.black.rankValue));
      assertTrue(Double.isNaN(report.black.predictionSigma));
      assertEquals(before.base().toString(), onEdtValue(HumanSlStrengthReuseSession::capture).base().toString());
      assertNull(onEdtValue(HumanSlStrengthDialogTest::progressDialog));
      return "completed=true\nordinary-analysis-unchanged=true\nlegacy-sigma-absent=true";
    }
  }

  private static String manualCancel(Path work, Path result) throws Exception {
    for (String action : List.of("button", "escape", "window")) {
      try (var manual = new HumanSlStrengthGuiFixture.Manual()) {
        var pending = beginManual(work, manual);
        manual.awaitStarted();
        onEdt(() -> {
          HumanSlStrengthDialog dialog = progressDialog();
          assertNotNull(dialog);
          switch (action) {
            case "button" -> find(dialog, AbstractButton.class, b -> "strength40-cancel".equals(b.getName())).doClick();
            case "escape" -> {
              Object key = dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                  .get(KeyStroke.getKeyStroke("ESCAPE"));
              assertNotNull(key);
              dialog.getRootPane().getActionMap().get(key).actionPerformed(new ActionEvent(dialog, 0, "escape"));
            }
            case "window" -> dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
            default -> throw new AssertionError(action);
          }
        });
        manual.awaitCancelled();
        manual.complete();
        manual.awaitClosed();
        awaitEdt(pending.returned()::get);
        assertNull(pending.result().get());
        assertNull(manual.preparation().readyReport(onEdtValue(HumanSlStrengthReuseSession::capture)));
        onEdt(() -> {
          assertNull(progressDialog());
          assertNull(errorDialog());
        });
      }
    }
    return "retired=3\nlate-completion-rejected=true";
  }

  private static String manualGameChange(Path work, Path result) throws Exception {
    try (var manual = new HumanSlStrengthGuiFixture.Manual()) {
      var pending = beginManual(work, manual);
      manual.awaitStarted();
      onEdt(HumanSlStrengthGuiFixture::loadSyntheticGame);
      manual.complete();
      manual.awaitClosed();
      awaitEdt(() -> errorDialog() != null);
      screenshot(onEdtValue(HumanSlStrengthDialogTest::errorDialog), result.resolveSibling("changed-game-rejected.png"));
      onEdt(() -> errorDialog().dispose());
      awaitEdt(pending.returned()::get);
      assertNull(pending.result().get());
      assertNull(manual.preparation().readyReport(onEdtValue(HumanSlStrengthReuseSession::capture)));
      return "stale-rejected=true";
    }
  }

  private record Pending(AtomicBoolean returned, AtomicReference<PlayerStrengthEstimator.Report> result) {}

  private static Pending beginManual(Path work, HumanSlStrengthGuiFixture.Manual manual) throws Exception {
    Path installed = Files.writeString(work.resolve("controlled-human-model"), "controlled worker; no engine inference");
    var captured = onEdtValue(HumanSlStrengthReuseSession::preparationSnapshot);
    var pending = new Pending(new AtomicBoolean(), new AtomicReference<>());
    SwingUtilities.invokeLater(() -> {
      pending.result().set(HumanSlStrengthDialog.prepare(Lizzie.frame, captured, installed, manual.preparation()));
      pending.returned().set(true);
    });
    awaitEdt(() -> progressDialog() != null);
    return pending;
  }

  private static JDialog openDashboard() throws Exception {
    SwingUtilities.invokeLater(() -> Lizzie.frame.showPlayerStrengthEstimate());
    awaitEdt(() -> dashboard() != null);
    return onEdtValue(HumanSlStrengthDialogTest::dashboard);
  }

  private static JDialog dashboard() {
    for (Window window : Window.getWindows()) {
      if (window instanceof JDialog dialog && window.isVisible()
          && find(window, JComboBox.class, c -> c.getItemCount() > 0
              && c.getItemAt(0) instanceof PlayerStrengthEstimator.StrengthModel) != null) return dialog;
    }
    return null;
  }

  private static HumanSlStrengthDialog progressDialog() {
    for (Window window : Window.getWindows())
      if (window instanceof HumanSlStrengthDialog dialog && dialog.isVisible()) return dialog;
    return null;
  }

  private static JDialog errorDialog() {
    for (Window window : Window.getWindows())
      if (window instanceof JDialog dialog && dialog.isVisible()
          && dialog.getOwner() instanceof HumanSlStrengthDialog) return dialog;
    return null;
  }

  private static <T> T find(Component root, Class<T> type, java.util.function.Predicate<T> predicate) {
    if (type.isInstance(root) && predicate.test(type.cast(root))) return type.cast(root);
    if (root instanceof Container container)
      for (Component child : container.getComponents()) {
        T found = find(child, type, predicate);
        if (found != null) return found;
      }
    return null;
  }

  private static void screenshot(Window window, Path path) throws Exception {
    BufferedImage image = onEdtValue(() -> {
      assertNotNull(window);
      Component root = window instanceof JDialog d ? d.getRootPane() : window;
      var captured = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
      var graphics = captured.createGraphics();
      root.printAll(graphics);
      graphics.dispose();
      return captured;
    });
    ImageIO.write(image, "png", path.toFile());
  }

  private static void awaitEdt(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!onEdtValue(condition::getAsBoolean)) {
      if (System.nanoTime() >= deadline) throw new AssertionError("GUI condition did not become true");
      Thread.sleep(10);
    }
  }

  private static void onEdt(Runnable action) throws Exception {
    SwingUtilities.invokeAndWait(action);
  }

  private static <T> T onEdtValue(java.util.function.Supplier<T> action) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    onEdt(() -> value.set(action.get()));
    return value.get();
  }
}
