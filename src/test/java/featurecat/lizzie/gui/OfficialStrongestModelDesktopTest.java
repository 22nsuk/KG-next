package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.RemoteWeightInfo;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.Utils;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Real catalog refresh and selection, with local API responses and no model download. */
class OfficialStrongestModelDesktopTest {
  private static final String DYNAMIC_MODEL = "kata1-tf3-b11c768-s12002M-d6304M";

  @Test
  void refreshedStrongestIsOptionalAndNeverReplacesTheActiveUserProfile() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(getClass(), "official-strongest", List.of(), List.of("probe"));
    assertEquals("PASS", new JSONObject(Files.readString(result)).getString("status"));
  }

  public static void main(String[] args) {
    try {
      runProbe(Path.of(args[1]), Path.of(args[2]));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void runProbe(Path work, Path result) throws Exception {
    AtomicReference<String> response = new AtomicReference<>(strongestJson(DYNAMIC_MODEL));
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/strongest/", exchange -> {
      assertEquals("application/json", exchange.getRequestHeaders().getFirst("Accept"));
      byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream body = exchange.getResponseBody()) { body.write(bytes); }
    });
    server.createContext("/networks/", exchange -> {
      byte[] bytes = "<table class=\"table mt-3\"></table>".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream body = exchange.getResponseBody()) { body.write(bytes); }
    });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    System.setProperty("lizzie.katago.strongest.url", url + "/strongest/");
    System.setProperty("lizzie.katago.networks.url", url + "/networks/");
    System.setProperty("lizzie.work.dir", work.toString());
    try {
      Files.writeString(work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
              + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":3}}");
      Lizzie.main(new String[0]);
      awaitEdt(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
      SwingUtilities.invokeAndWait(() -> {
        for (Window window : Window.getWindows()) {
          if (window instanceof JDialog) window.dispose();
        }
      });
      Path models = Files.createDirectories(work.resolve("weights"));
      Path customModel = Files.writeString(models.resolve("my-model.bin"), "my_custom_network\n15\n22\n19\n");
      Path executable = Files.writeString(work.resolve("katago"), "metadata-only fixture");
      Path engineConfig = Files.writeString(work.resolve("gtp.cfg"), "numSearchThreads = 7\n");
      EngineData profile = new EngineData();
      profile.id = "preserve-custom-profile";
      profile.name = "내 엔진과 모델";
      profile.commands = "\"" + executable + "\" gtp -model \"" + customModel
          + "\" -config \"" + engineConfig + "\"";
      profile.width = 13;
      profile.height = 9;
      profile.komi = 6.5f;
      profile.initialCommand = "kata-set-rules japanese";
      Utils.saveEngineSettings(new ArrayList<>(List.of(profile)));
      SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectSavedEngine(profile).scanWeightCatalog();
      assertEquals(customModel, snapshot.activeWeightPath);
      byte[] savedConfig = Files.readAllBytes(work.resolve("config.txt"));
      byte[] savedModel = Files.readAllBytes(customModel);
      byte[] savedEngineConfig = Files.readAllBytes(engineConfig);
      String memoryConfig = Lizzie.config.config.toString();

      KataGoAutoSetupDialog dialog = onEdt(() -> {
        KataGoAutoSetupDialog value = new KataGoAutoSetupDialog(Lizzie.frame);
        // Use a known local metadata snapshot; the catalog refresh below remains the production
        // asynchronous HTTP → EDT path and never starts the metadata-only engine fixture.
        invoke(value, "cancelStateRefresh");
        set(value, "stateRefreshRequestId", (Long) field(value, "stateRefreshRequestId") + 1L);
        set(value, "snapshot", snapshot);
        set(value, "weightSwitchDisplayState", KataGoAutoSetupDialog.WeightSwitchDisplayState.active(snapshot));
        invoke(value, "setStateRefreshPending", false);
        invoke(value, "renderSnapshot");
        invoke(value, "refreshIdleControls");
        value.showWeightsSection();
        value.setVisible(true);
        return value;
      });
      JButton refresh = (JButton) field(dialog, "btnReloadRemoteWeights");
      SwingUtilities.invokeAndWait(refresh::doClick);
      awaitEdt(() -> refresh.isEnabled() && remoteWeights(dialog).size() == 4);
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(DYNAMIC_MODEL, remoteWeights(dialog).get(0).modelName);
        assertTrue(remoteWeights(dialog).get(0).officialStrongest);
        assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL, selectedWeight(dialog).modelName);
        RemoteWeightInfo recommended = (RemoteWeightInfo) field(field(dialog, "strongestRecommendation"), "weight");
        assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL, recommended.modelName);
        assertSame(snapshot, field(dialog, "snapshot"));

        ((JList<?>) field(dialog, "weightCatalogList")).setSelectedIndex(0);
        assertEquals(DYNAMIC_MODEL, selectedWeight(dialog).modelName);
        assertTrue(((JButton) field(dialog, "btnDownloadWeight")).isEnabled());
        assertFalse(((JButton) field(dialog, "btnUseWeight")).isVisible());
        assertTrue(((JLabel) field(dialog, "lblSelectedWeightName")).getText()
            .contains(Lizzie.resourceBundle.getString("AutoSetup.officialStrongestBadge")));
      });
      capture(dialog, result.resolveSibling("official-strongest-ko.png"));
      assertUnchanged(work, customModel, engineConfig, savedConfig, savedModel, savedEngineConfig, memoryConfig);

      // Preserve an explicit row selection across a successful refresh.
      SwingUtilities.invokeAndWait(refresh::doClick);
      awaitEdt(() -> refresh.isEnabled());
      assertEquals(DYNAMIC_MODEL, onEdt(() -> selectedWeight(dialog).modelName));

      // An invalid next response removes the online row and restores the pinned default choice.
      response.set("<html>API unavailable</html>");
      SwingUtilities.invokeAndWait(refresh::doClick);
      awaitEdt(() -> refresh.isEnabled() && remoteWeights(dialog).size() == 3);
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(remoteWeights(dialog).stream().noneMatch(info -> info.officialStrongest));
        assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL, selectedWeight(dialog).modelName);
        assertSame(snapshot, field(dialog, "snapshot"));
      });

      // When the API identifies the pinned default, there is one row with pinned integrity.
      response.set(strongestJson(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL));
      SwingUtilities.invokeAndWait(refresh::doClick);
      awaitEdt(() -> refresh.isEnabled() && remoteWeights(dialog).get(0).officialStrongest);
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(3, remoteWeights(dialog).size());
        RemoteWeightInfo pinned = remoteWeights(dialog).get(0);
        assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_SHA256, pinned.sha256);
        assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_SIZE_BYTES, pinned.sizeBytes);
        assertEquals(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL, selectedWeight(dialog).modelName);
      });
      capture(dialog, result.resolveSibling("official-strongest-pinned-ko.png"));
      assertUnchanged(work, customModel, engineConfig, savedConfig, savedModel, savedEngineConfig, memoryConfig);
      assertFalse(Files.exists(models.resolve(DYNAMIC_MODEL + ".bin.gz")));
      assertFalse(Files.exists(models.resolve(KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL + ".bin.gz")));
      Files.writeString(result, new JSONObject().put("status", "PASS")
          .put("source", "local strongest API fixture; no model download or engine execution")
          .put("activeModel", customModel.toString()).toString());
    } finally {
      server.stop(0);
      SwingUtilities.invokeAndWait(() -> {
        for (Window window : Window.getWindows()) window.dispose();
      });
    }
  }

  private static void assertUnchanged(Path work, Path model, Path engineConfig, byte[] config,
      byte[] modelBytes, byte[] engineConfigBytes, String memoryConfig) throws Exception {
    assertArrayEquals(config, Files.readAllBytes(work.resolve("config.txt")));
    assertArrayEquals(modelBytes, Files.readAllBytes(model));
    assertArrayEquals(engineConfigBytes, Files.readAllBytes(engineConfig));
    assertEquals(memoryConfig, Lizzie.config.config.toString());
  }

  private static String strongestJson(String model) {
    return new JSONObject().put("name", model)
        .put("created_at", "2026-09-25T02:04:37.536189Z")
        .put("model_file", "https://media.katagotraining.org/uploaded/networks/models/kata1/" + model + ".bin.gz")
        .put("model_file_bytes", 1000L).put("model_file_sha256", "0".repeat(64)).toString();
  }

  @SuppressWarnings("unchecked")
  private static List<RemoteWeightInfo> remoteWeights(KataGoAutoSetupDialog dialog) {
    return (List<RemoteWeightInfo>) field(dialog, "remoteWeightInfos");
  }

  private static RemoteWeightInfo selectedWeight(KataGoAutoSetupDialog dialog) {
    RemoteWeightInfo selected = (RemoteWeightInfo) invoke(dialog, "getSelectedRemoteWeight");
    assertNotNull(selected);
    return selected;
  }

  private static void capture(KataGoAutoSetupDialog dialog, Path output) throws Exception {
    new Robot().waitForIdle();
    Rectangle bounds = onEdt(dialog::getBounds);
    ImageIO.write(new Robot().createScreenCapture(bounds), "png", output.toFile());
  }

  private static Object field(Object target, String name) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

  private static void set(Object target, String name, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

  private static Object invoke(Object target, String name, Object... arguments) {
    try {
      for (Method method : target.getClass().getDeclaredMethods()) {
        if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
          method.setAccessible(true);
          return method.invoke(target, arguments);
        }
      }
      throw new NoSuchMethodException(name);
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

  private static <T> T onEdt(Supplier<T> action) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> result.set(action.get()));
    return result.get();
  }

  private static void awaitEdt(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (!onEdt(condition::getAsBoolean)) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Catalog UI condition timed out");
      Thread.sleep(25);
    }
  }
}
