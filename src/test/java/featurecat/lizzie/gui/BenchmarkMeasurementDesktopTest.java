package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.EngineThreadPolicy;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.LocaleFontSupport;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.katago.tuning.KataGoBenchmarkSettings;
import java.awt.Robot;
import java.awt.Window;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Production result cards with controlled measurements; no GPU performance claim. */
class BenchmarkMeasurementDesktopTest {
  @ParameterizedTest
  @ValueSource(strings = {"1", "1.5"})
  void resultCardsKeepMeasuredSettingsAndRecommendationStatusVisible(String scale)
      throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.runWithStartupTimeout(
            getClass(),
            "benchmark-settings-" + scale,
            List.of("-Dsun.java2d.uiScale=" + scale),
            List.of("probe"),
            120,
            90);
    assertEquals("PASS", new JSONObject(Files.readString(result)).getString("status"));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
              + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":1}}");
      System.setProperty("lizzie.work.dir", work.toString());
      DesktopProbeProcess.phase(result, "application-starting");
      Lizzie.main(new String[0]);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      while ((Lizzie.frame == null || !Lizzie.frame.isShowing()) && System.nanoTime() < deadline)
        Thread.sleep(50);
      assertNotNull(Lizzie.frame);
      SwingUtilities.invokeAndWait(
          () -> {
            for (Window window : Window.getWindows())
              if (window instanceof JDialog) window.dispose();
          });
      DesktopProbeProcess.applicationReady(result);
      Path engine = Files.writeString(work.resolve("katago"), "UI fixture, never executed");
      Path model = Files.writeString(work.resolve("model.bin.gz"), "UI fixture model");
      Path config = Files.writeString(work.resolve("gtp.cfg"), "numSearchThreads=3\n");
      EngineData entry = new EngineData();
      entry.id = "benchmark-measurement-fixture";
      entry.name = "Benchmark display fixture";
      entry.commands = quote(engine) + " gtp -model " + quote(model) + " -config " + quote(config);
      entry.width = entry.height = 19;
      var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(entry);
      entry.threadPolicy =
          new JSONObject()
              .put("source", "CFG")
              .put("katago-benchmark-threads", 10)
              .put("katago-benchmark-nn-server-threads", 2)
              .put("katago-benchmark-batch-size", 5)
              .put("katago-benchmark-visits-per-second", 420.0)
              .put("katago-benchmark-nn-evals-per-second", 350.0)
              .put("katago-benchmark-backend", "CUDA")
              .put("environment", EngineThreadPolicy.environment(snapshot));
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));

      for (String language : List.of("ko", "en-US", "zh-CN")) {
        Locale locale = Locale.forLanguageTag(language);
        Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
        Lizzie.config.useLanguage = AppLocale.fromSystemLocale(locale).configValue();
        Lizzie.config.uiFontName =
            LocaleFontSupport.resolveConfiguredFontName(null, locale, Config.sysDefaultFontName);
        AtomicReference<KataGoAutoSetupDialog> shown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
            () -> {
              KataGoAutoSetupDialog dialog = new KataGoAutoSetupDialog(Lizzie.frame);
              invoke(dialog, "cancelStateRefresh");
              set(
                  dialog,
                  "stateRefreshRequestId",
                  ((Long) field(dialog, "stateRefreshRequestId")) + 1L);
              set(dialog, "snapshot", snapshot);
              ((JList<?>) field(dialog, "sectionNav")).setSelectedIndex(2);
              invoke(dialog, "updateBenchmarkInfo");
              dialog.setVisible(true);
              shown.set(dialog);
            });
        KataGoAutoSetupDialog dialog = shown.get();
        try {
          for (String state : List.of("measured", "unknown", "unmatched")) {
            DesktopProbeProcess.phase(result, language + "-" + state);
            SwingUtilities.invokeAndWait(
                () -> {
                  entry.threadPolicy.remove("katago-benchmark-measured-settings");
                  if (!state.equals("unknown")) {
                    KataGoBenchmarkSettings settings =
                        state.equals("measured")
                            ? new KataGoBenchmarkSettings(
                                10, 2, 5, KataGoBenchmarkSettings.Source.ADDITIONAL_BATCH_SIZE)
                            : KataGoBenchmarkSettings.threadSweep(10);
                    entry.threadPolicy.put("katago-benchmark-measured-settings", settings.toJson());
                  }
                  entry.threadPolicy.put(
                      "katago-benchmark-visits-per-second",
                      state.equals("measured") ? 420.0 : 386.0);
                  entry.threadPolicy.put(
                      "katago-benchmark-nn-evals-per-second",
                      state.equals("measured") ? 350.0 : 320.0);
                  Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
                  // A successful process callback must classify incomplete evidence immediately
                  // too.
                  setComplete(dialog);
                  invoke(dialog, "updateBenchmarkInfo");
                  dialog.validate();
                  JLabel status = (JLabel) field(dialog, "lblBenchmarkReportStatus");
                  String key =
                      switch (state) {
                        case "measured" -> "AutoSetup.benchmarkDone";
                        case "unknown" -> "AutoSetup.benchmarkMeasurementUnverified";
                        default -> "AutoSetup.benchmarkRecommendationUnmeasured";
                      };
                  assertEquals(Lizzie.resourceBundle.getString(key), status.getText());
                  assertEquals(
                      KataGoAutoSetupDialog.formatBenchmarkSpeed(
                          state.equals("measured") ? 420.0 : 386.0, Locale.getDefault()),
                      ((JLabel) field(dialog, "lblBenchmarkVisitsValue")).getText());
                  JTextArea details = (JTextArea) field(dialog, "benchmarkPolicyDetails");
                  var stored = KataGoRuntimeHelper.getStoredBenchmarkResult(entry);
                  assertTrue(
                      details
                          .getText()
                          .contains(KataGoRuntimeHelper.formatBenchmarkMeasuredSettings(stored)));
                  assertTrue(
                      details
                          .getText()
                          .contains(
                              Lizzie.resourceBundle.getString(
                                  "AutoSetup.benchmarkMeasurementScope")));
                  assertTrue(
                      status
                          .getAccessibleContext()
                          .getAccessibleName()
                          .contains(KataGoRuntimeHelper.formatBenchmarkMeasuredSettings(stored)));
                  assertTrue(details.getLineWrap());
                  assertTrue(
                      details.getWidth() > 0
                          && details.getHeight() >= details.getPreferredSize().height);
                  assertEquals(
                      -1,
                      details.getFont().canDisplayUpTo(details.getText()),
                      "Missing measurement glyphs");
                  if (state.equals("measured")) {
                    assertTrue(details.getText().contains("numSearchThreads=10"));
                    assertTrue(details.getText().contains("nnMaxBatchSize=5"));
                    assertTrue(details.getText().contains("numNNServerThreadsPerModel=2"));
                  }
                  details.scrollRectToVisible(
                      new java.awt.Rectangle(0, 0, details.getWidth(), details.getHeight()));
                });
            new Robot().waitForIdle();
            ImageIO.write(
                new Robot().createScreenCapture(dialog.getBounds()),
                "png",
                result.getParent().resolve(language + "-" + state + ".png").toFile());
          }
        } finally {
          SwingUtilities.invokeAndWait(dialog::dispose);
        }
      }
      Files.writeString(
          result,
          new JSONObject()
              .put("status", "PASS")
              .put("data", "controlled UI measurements; no performance claim")
              .toString());
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static String quote(Path path) {
    return "\"" + path + "\"";
  }

  private static Object field(Object target, String name) {
    try {
      Field field = KataGoAutoSetupDialog.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void set(Object target, String name, Object value) {
    try {
      Field field = KataGoAutoSetupDialog.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void setComplete(Object target) {
    Object state = field(target, "benchmarkDisplayState");
    set(target, "benchmarkDisplayState", Enum.valueOf((Class) state.getClass(), "COMPLETE"));
  }

  private static void invoke(Object target, String name) {
    try {
      var method = KataGoAutoSetupDialog.class.getDeclaredMethod(name);
      method.setAccessible(true);
      method.invoke(target);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }
}
