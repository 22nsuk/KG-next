package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Real contribution constructors/readers and child processes in an isolated application. */
public class ContributeStartupDesktopTest {
  static final String REJECTION =
      "This version of KataGo is not enabled for distributed. If this exact version was working previously, then changes in the run require a newer version - please update KataGo to the latest version or release. But if this is already the official newest version of KataGo, or you think that not enabling this version is an oversight, please ask server admins to enable the following version hash: 4b8de63bea2bd8790db96cd6f8daf86dc87be6f7-eigen";

  @Test
  void serverRejectionUsesOneLaunchAndRetainsFailure() throws Exception {
    probe("permanent");
  }

  @Test
  void outputOrderingReadinessAndRetryBudget() throws Exception {
    probe("lifecycle");
  }

  @Test
  void stopRestartAndValidGameDisplay() throws Exception {
    probe("display");
  }

  @Test
  void permanentOutputSurvivesPausedEdt() throws Exception {
    probe("paused-edt");
  }

  @Test
  void modalNotificationsDoNotBlockDrainOrStopReplacement() throws Exception {
    probe("modal");
  }

  private void probe(String suite) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(getClass(), "contribute-" + suite, List.of(), List.of(suite), 120);
    assertEquals("passed", new JSONObject(Files.readString(result)).getString("result"));
  }

  public static void main(String[] args) throws Exception {
    if (args[0].equals("engine-child")) {
      child(args);
      return;
    }
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":2}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
      if (args[0].equals("permanent")) {
        for (String mode :
            List.of(
                "reject-json", "reject-text", "unsupported-out", "unsupported-err", "duplicate")) {
          Path run = start(work, mode, mode.equals("duplicate"));
          verifyFailure(
              run,
              1,
              mode.startsWith("unsupported")
                  ? "Contribute.tips.useOfficialEngineSupportDistributedTraining"
                  : "Contribute.tips.buildRejected");
          assertFalse(launches(run).get(0).contains("-cacerts"));
        }
      } else if (args[0].equals("lifecycle")) {
        for (String mode : List.of("stdout-first", "stderr-first")) {
          Path run = start(work, mode, true);
          await(() -> Files.exists(run.resolve("closed")));
          assertEquals(1, launches(run).size());
          Files.writeString(run.resolve("release"), "release");
          verifyFailure(run, 1, "Contribute.tips.buildRejected");
        }
        for (String mode : List.of("invalid-json", "retry", "ca", "pure-ca", "truncated", "tail")) {
          long started = System.nanoTime();
          Path run = start(work, mode, mode.equals("pure-ca"));
          verifyFailure(run, 4, null);
          List<String> commands = launches(run);
          assertFalse(commands.get(0).contains("-cacerts"));
          for (int i = 1; i < commands.size(); i++) {
            if (mode.equals("ca"))
              assertEquals(1, commands.get(i).split("-cacerts", -1).length - 1);
            else assertFalse(commands.get(i).contains("-cacerts"));
          }
          assertEquals(
              command(run, mode),
              Lizzie.config.contributeCommand,
              "Saved command remains user-owned");
          if (mode.equals("pure-ca"))
            for (String received : commands) {
              assertEquals(
                  List.of("engine-child", run.toString(), mode),
                  new org.json.JSONArray(received).toList());
            }
          if (mode.equals("truncated"))
            assertTrue(displayed(failureWindow()).toJson().getBoolean("stdoutTruncated"));
          if (mode.equals("tail")) {
            assertTrue(
                System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20),
                "Four launches must respect bounded tail collection");
            JSONObject diagnostic = displayed(failureWindow()).toJson();
            assertEquals("partial", diagnostic.getString("collectionState"));
            assertEquals("unavailable", diagnostic.getString("exitObservation"));
          }
        }
        for (String mode : List.of("starting-log", "game-ready", "starting-exit", "game-exit")) {
          Path ready = start(work, mode, true);
          if (mode.equals("game-ready")) {
            await(() -> Lizzie.frame.contributeEngine.watchingGameIndex == 0);
            Files.writeString(ready.resolve("release"), "release");
          }
          await(() -> failureWindow() != null);
          assertNull(
              bound(failureWindow()).snapshot(),
              "Genuine readiness retains generic ready/fail semantics");
          assertEquals(mode.endsWith("-exit") ? 4 : 1, launches(ready).size());
          if (mode.startsWith("game-"))
            assertEquals(0, Lizzie.frame.contributeEngine.watchingGameIndex);
        }
      } else if (args[0].equals("modal")) {
        Path rejected = start(work, "modal-reject", true);
        await(() -> notification() != null);
        JDialog oldVersion = notification();
        Files.writeString(rejected.resolve("release"), "release");
        verifyFailure(rejected, 1, "Contribute.tips.buildRejected");
        assertTrue(oldVersion.isShowing(), "Failure drains while actual modal stays open");
        SwingUtilities.invokeAndWait(oldVersion::dispose);

        Path clean = start(work, "modal-clean", true);
        await(() -> notification() != null);
        JDialog cleanNotice = notification();
        Files.writeString(clean.resolve("release"), "release");
        await(() -> !Lizzie.frame.isContributing);
        assertEquals(1, launches(clean).size());
        assertTrue(cleanNotice.isShowing(), "EOF must finish without modal dismissal");
        assertNull(failureWindow());
        SwingUtilities.invokeAndWait(cleanNotice::dispose);

        Path replaced = start(work, "modal-replace", true);
        await(() -> notification() != null);
        JDialog stale = notification();
        Path successor = Files.createTempDirectory(work, "modal-successor-");
        SwingUtilities.invokeAndWait(
            () -> {
              Lizzie.frame.closeContributeEngine();
              Lizzie.config.contributeCommand = command(successor, "game");
              Lizzie.frame.startContributeEngine();
            });
        var successorEngine = Lizzie.frame.contributeEngine;
        await(() -> successorEngine.watchingGameIndex == 0);
        SwingUtilities.invokeAndWait(stale::dispose);
        SwingUtilities.invokeAndWait(() -> {});
        assertSame(successorEngine, Lizzie.frame.contributeEngine);
        assertTrue(Lizzie.frame.isContributing);
        assertTrue(Lizzie.frame.contributeView.isShowing());
        assertEquals(1, launches(replaced).size());
        assertEquals(1, launches(successor).size());
      } else if (args[0].equals("paused-edt")) {
        for (String mode : List.of("paused-reject-json", "paused-unsupported-err")) {
          Path run = start(work, mode, true);
          var entered = new java.util.concurrent.CountDownLatch(1);
          var release = new java.util.concurrent.CountDownLatch(1);
          SwingUtilities.invokeLater(
              () -> {
                entered.countDown();
                try {
                  assertTrue(release.await(20, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                  throw new AssertionError(error);
                }
              });
          assertTrue(entered.await(10, TimeUnit.SECONDS));
          try {
            Files.writeString(run.resolve("release"), "release");
            await(() -> Files.exists(run.resolve("emitted")));
            // Deliberately keep UI dispatch paused beyond the production tail deadline.
            assertFalse(
                release.await(
                    featurecat.lizzie.analysis.EngineStartupDiagnostics.Policy.production()
                            .deadlineMillis()
                        + 1000,
                    TimeUnit.MILLISECONDS));
            assertEquals(
                1, launches(run).size(), "Permanent output cannot retry behind a busy EDT");
          } finally {
            release.countDown();
          }
          verifyFailure(
              run,
              1,
              mode.endsWith("unsupported-err")
                  ? "Contribute.tips.useOfficialEngineSupportDistributedTraining"
                  : "Contribute.tips.buildRejected");
        }
      } else {
        Path stopped = start(work, "stop", true);
        await(() -> Files.exists(stopped.resolve("closed")));
        SwingUtilities.invokeAndWait(() -> Lizzie.frame.closeContributeEngine());
        assertFalse(Lizzie.frame.isContributing);
        Path restarted = start(work, "retry", true);
        verifyFailure(restarted, 4, null);
        assertEquals(1, launches(stopped).size(), "Stopped attempt cannot schedule a successor");
        Path game = start(work, "game", true);
        await(
            () ->
                Lizzie.frame.contributeEngine.watchingGameIndex == 0
                    && Lizzie.board.getHistory().getMoveNumber() == 1);
        assertTrue(Lizzie.frame.isContributing);
        SwingUtilities.invokeAndWait(
            () -> {
              assertEquals("game", Lizzie.board.getHistory().getGameInfo().getPlayerBlack());
              assertTrue(Lizzie.frame.contributeView.isShowing());
            });
        Path screenshot = result.resolveSibling("valid-game.png");
        javax.imageio.ImageIO.write(
            new java.awt.Robot().createScreenCapture(Lizzie.frame.getBounds()),
            "png",
            screenshot.toFile());
        // Keep the old terminal UI callback queued across a public stop/restart.
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(
            () -> {
              entered.countDown();
              try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
              } catch (InterruptedException error) {
                throw new AssertionError(error);
              }
              Lizzie.frame.closeContributeEngine();
              Lizzie.config.contributeCommand = command(game, "retry");
              Lizzie.frame.startContributeEngine();
            });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        Files.writeString(game.resolve("release"), "release");
        try {
          await(() -> Files.exists(game.resolve("emitted")));
          assertFalse(
              release.await(
                  featurecat.lizzie.analysis.EngineStartupDiagnostics.Policy.production()
                          .deadlineMillis()
                      + 1000,
                  TimeUnit.MILLISECONDS));
        } finally {
          release.countDown();
        }
        verifyFailure(game, 5, null);
      }
      SwingUtilities.invokeAndWait(
          () -> {
            Lizzie.frame.closeContributeEngine();
            for (Window window : Window.getWindows()) window.dispose();
          });
      Files.writeString(
          result, new JSONObject().put("result", "passed").put("suite", args[0]).toString());
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(
          result,
          new JSONObject().put("result", "failed").put("error", failure.toString()).toString());
      System.exit(1);
    }
  }

  private static void child(String[] args) throws Exception {
    Path run = Path.of(args[1]);
    String mode = args[2];
    Files.writeString(
        run.resolve("launches.txt"),
        new org.json.JSONArray(List.of(args)).toString() + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    switch (mode) {
      case "modal-reject", "modal-replace", "modal-clean" -> {
        System.out.println(mode.equals("modal-clean")
            ? "All cleaned up, quitting"
            : "2026-10-15 12:00:00+0000: KataGo v1.10.0");
        System.out.flush();
        waitFile(run.resolve("release"));
        if (mode.equals("modal-reject")) System.err.println(REJECTION);
      }
      case "paused-reject-json", "paused-unsupported-err" -> {
        waitFile(run.resolve("release"));
        Files.writeString(run.resolve("emitted"), "emitted");
        System.out.println("Contribution startup fixture");
        if (mode.endsWith("unsupported-err"))
          System.err.println("Compile with -DBUILD_DISTRIBUTED to enable contribution");
        else System.out.println(new JSONObject().put("error", REJECTION));
        System.out.flush();
        System.err.flush();
      }
      case "reject-json" -> System.out.println(new JSONObject().put("error", REJECTION));
      case "reject-text" ->
          System.err.println(
              "ERROR: task loop loop thread failed: Server returned error 400: " + REJECTION);
      case "unsupported-out" ->
          System.out.println("Compile with -DBUILD_DISTRIBUTED to enable contribution");
      case "unsupported-err" ->
          System.err.println("Compile with -DBUILD_DISTRIBUTED to enable contribution");
      case "duplicate" -> {
        System.out.println(new JSONObject().put("error", REJECTION));
        System.err.println(REJECTION);
        System.out.println(REJECTION);
      }
      case "stdout-first", "stderr-first" -> {
        if (mode.equals("stdout-first")) System.out.close();
        else System.err.close();
        Files.writeString(run.resolve("closed"), "closed");
        waitFile(run.resolve("release"));
        if (mode.equals("stdout-first")) System.err.println(REJECTION);
        else System.out.println(REJECTION);
      }
      case "invalid-json" -> {
        System.out.println("{broken");
        System.out.println("{}");
        System.out.println("{\"gameId\":\"missing-fields\"}");
        System.out.println(
            "{\"gameId\":\"missing-players\",\"initialStones\":[],\"moves\":[],\"move\":[\"B\",\"D4\"],\"moveInfos\":[]}");
        System.out.println("{\"error\":\"Starting game 7 (training)\"}");
        System.out.println("2026-10-14 12:00:00+0000: KataGo v1.16.4");
        System.err.println(
            "HTTP 400: unknown failure password=fixture-password token=fixture-token username=fixture-user");
      }
      case "truncated" -> {
        for (int i = 0; i < 120; i++)
          System.out.println("bounded-tail-fixture " + i + " x".repeat(500));
        System.err.println("ERROR controlled truncation failure");
      }
      case "ca", "pure-ca" -> System.err.println("Could not find CA certs");
      case "tail", "stop" -> {
        System.out.close();
        Files.writeString(run.resolve("closed"), "closed");
        System.in.read();
      }
      case "starting-log", "starting-exit" -> {
        System.out.println("2026-10-14 12:00:00+0000: Starting game 7 (training)");
        if (mode.equals("starting-log"))
          System.out.println(new JSONObject().put("error", REJECTION));
      }
      case "game", "game-ready", "game-exit" -> {
        System.out.println(
            new JSONObject()
                .put("gameId", "fixture-" + ProcessHandle.current().pid())
                .put("blackPlayer", "fixture-game")
                .put("whitePlayer", "fixture-game")
                .put("boardXSize", 19)
                .put("boardYSize", 19)
                .put(
                    "rules",
                    new JSONObject()
                        .put("komi", 7.5)
                        .put("ko", "SIMPLE")
                        .put("scoring", "AREA")
                        .put("tax", "NONE")
                        .put("suicide", false))
                .put("initialStones", new org.json.JSONArray())
                .put(
                    "moves",
                    new org.json.JSONArray().put(new org.json.JSONArray(List.of("B", "D4"))))
                .put("move", new org.json.JSONArray(List.of("W", "Q16")))
                .put("moveInfos", new org.json.JSONArray()));
        if (!mode.equals("game-exit")) {
          waitFile(run.resolve("release"));
          Files.writeString(run.resolve("emitted"), "emitted");
          System.out.println(new JSONObject().put("error", REJECTION));
        }
      }
      default ->
          System.err.println(
              "ERROR controlled failure password=fixture-password token=fixture-token username=fixture-user");
    }
    System.exit(1);
  }

  private static Path start(Path work, String mode, boolean pure) throws Exception {
    Path run = Files.createTempDirectory(work, mode + "-");
    SwingUtilities.invokeAndWait(
        () -> {
          Lizzie.frame.closeContributeEngine();
          for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
          Lizzie.config.contributeUseCommand = true;
          Lizzie.config.contributeUsePureCommand = pure;
          Lizzie.config.contributeUserName = "fixture-user";
          Lizzie.config.contributePassword = "fixture-password";
          Lizzie.config.contributeCommand = command(run, mode);
          Lizzie.frame.startContributeEngine();
        });
    return run;
  }

  private static String command(Path run, String mode) {
    return quote(
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    "java" + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""))
                .toString())
        + " -cp "
        + quote(System.getProperty("java.class.path"))
        + " "
        + ContributeStartupDesktopTest.class.getName()
        + " engine-child "
        + quote(run.toString())
        + " "
        + mode;
  }

  private static void verifyFailure(Path run, int count, String key) throws Exception {
    await(() -> failureWindow() != null);
    EngineFailedMessage dialog = failureWindow();
    await(
        () ->
            bound(dialog) != null
                && bound(dialog).snapshot() != null
                && !bound(dialog)
                    .snapshot()
                    .toJson()
                    .getString("collectionState")
                    .equals("collecting"));
    await(() -> displayed(dialog) == bound(dialog).snapshot());
    assertEquals(count, launches(run).size(), "Observed child launches");
    assertFalse(Lizzie.frame.isContributing);
    assertEquals(Lizzie.resourceBundle.getString("Menu.noEngine"), Menu.engineMenu.getText());
    assertEquals(
        1,
        java.util.Arrays.stream(Window.getWindows())
            .filter(w -> w instanceof EngineFailedMessage && w.isShowing())
            .count());
    featurecat.lizzie.analysis.EngineStartupDiagnostic diagnostic = displayed(dialog);
    JSONObject json = diagnostic.toJson();
    if (key != null)
      assertEquals(Lizzie.resourceBundle.getString(key), json.getString("originalError"));
    String shared = diagnostic.shareText();
    assertFalse(shared.contains("fixture-password"));
    assertFalse(shared.contains("fixture-token"));
    assertFalse(shared.contains("fixture-user"));
    SwingUtilities.invokeAndWait(
        () -> {
          String visible = text(dialog);
          if (key != null) assertTrue(visible.contains(Lizzie.resourceBundle.getString(key)));
          assertFalse(visible.contains("fixture-password"));
          assertFalse(visible.contains("fixture-token"));
          assertFalse(visible.contains("fixture-user"));
          assertFalse(button(Lizzie.frame.contributeView, "ContributeView.btnPause").isEnabled());
          assertFalse(
              button(Lizzie.frame.contributeView, "ContributeView.btnSlowShutdown").isVisible());
          assertFalse(
              button(Lizzie.frame.contributeView, "ContributeView.btnForceShutdown").isVisible());
          assertTrue(
              button(Lizzie.frame.contributeView, "ContributeView.btnCloseView").isVisible());
        });
    var runtime = featurecat.lizzie.logging.LoggingRuntime.current().orElseThrow();
    var request =
        new featurecat.lizzie.logging.DiagnosticBundleRequest(
            runtime,
            java.util.Set.of(),
            false,
            false,
            new JSONObject(),
            null,
            null,
            "test",
            "unknown",
            diagnostic);
    Path zip =
        new featurecat.lizzie.logging.DiagnosticBundleExporter(run.resolve("exports"))
            .export(request);
    boolean event = false;
    try (var archive = new java.util.zip.ZipFile(zip.toFile())) {
      JSONObject exported =
          new JSONObject(
              new String(
                  archive
                      .getInputStream(archive.getEntry("snapshots/engine-startup-failure.json"))
                      .readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
      assertEquals("available", exported.getString("status"));
      assertEquals(
          diagnostic.attemptId(), exported.getJSONObject("failure").getString("attemptId"));
      if (key != null)
        assertEquals(
            json.getString("originalError"),
            exported.getJSONObject("failure").getString("originalError"));
      else assertFalse(exported.getJSONObject("failure").getString("originalError").isBlank());
      var entries = archive.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        String content =
            new String(
                archive.getInputStream(entry).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(content.contains("fixture-password"), entry.getName());
        assertFalse(content.contains("fixture-token"), entry.getName());
        assertFalse(content.contains("fixture-user"), entry.getName());
        event |=
            entry.getName().startsWith("logs/")
                && content.contains("startup-failed")
                && content.contains(diagnostic.attemptId());
      }
    }
    assertTrue(event, "Exported startup-failed event must identify the displayed attempt");
    Files.writeString(run.resolve("diagnostic.json"), shared);
  }

  private static javax.swing.JButton button(java.awt.Container root, String key) {
    String label = Lizzie.resourceBundle.getString(key);
    var pending = new java.util.ArrayDeque<java.awt.Component>();
    pending.add(root);
    while (!pending.isEmpty()) {
      var component = pending.removeFirst();
      if (component instanceof javax.swing.JButton button && label.equals(button.getText()))
        return button;
      if (component instanceof java.awt.Container container)
        pending.addAll(List.of(container.getComponents()));
    }
    throw new AssertionError("Missing button " + key);
  }

  private static String text(java.awt.Component component) {
    String value = component instanceof javax.swing.text.JTextComponent t ? t.getText() : "";
    if (component instanceof java.awt.Container container)
      for (var child : container.getComponents()) value += text(child);
    return value;
  }

  private static featurecat.lizzie.analysis.EngineStartupDiagnostics.Attempt bound(
      EngineFailedMessage dialog) {
    return (featurecat.lizzie.analysis.EngineStartupDiagnostics.Attempt)
        field(dialog, "boundAttempt");
  }

  private static featurecat.lizzie.analysis.EngineStartupDiagnostic displayed(
      EngineFailedMessage dialog) {
    return (featurecat.lizzie.analysis.EngineStartupDiagnostic)
        field(dialog, "displayedDiagnostic");
  }

  private static Object field(EngineFailedMessage dialog, String name) {
    try {
      var field = EngineFailedMessage.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(dialog);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static List<String> launches(Path run) {
    try {
      return Files.readAllLines(run.resolve("launches.txt"));
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static JDialog notification() {
    for (Window window : Window.getWindows())
      if (window instanceof HtmlMessage dialog && dialog.isShowing()) return dialog;
    return null;
  }

  static EngineFailedMessage failureWindow() {
    for (Window window : Window.getWindows())
      if (window instanceof EngineFailedMessage dialog && dialog.isShowing()) return dialog;
    return null;
  }

  static String quote(String value) {
    return "\"" + value + "\"";
  }

  static void waitFile(Path file) throws Exception {
    await(() -> Files.exists(file));
  }

  static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
    assertTrue(condition.getAsBoolean(), "Timed out awaiting production behavior");
  }
}
