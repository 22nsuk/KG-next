package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Actual contribution settings, saved-engine chooser, config persistence and contribution launch of
 * a controlled directly launched executable, in two isolated Swing JVMs sharing one work dir.
 */
public class ContributeSavedEngineSelectionDesktopTest {
  private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
  private static final String FIXTURE = WINDOWS ? "katago.exe" : "katago";
  private static final String ORIGINAL_PATH = "\"original contribution katago\"";
  private static final String CUSTOM_COMMAND = "custom-contribution-command --keep";
  // Optional PID-filtered PrintWindow script for runs on a private Windows desktop.
  private static final String NATIVE_CAPTURE = "lizzie.native.capture";
  private static final List<String> SECRETS =
      List.of(
          "fixture-ssh-password",
          "fixture-wrapper-password",
          "fixture-wrapper-user",
          "fixture-key.pem",
          "-model",
          "maxVisits");

  @Test
  void selectSavedEngineSaveReloadAndStartContribution() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path root =
        Path.of(System.getProperty("lizzie.desktop.evidence.dir", "target/desktop-smoke/probes"))
            .toAbsolutePath();
    Files.createDirectories(root);
    // One application work directory shared by both JVMs, like a real restart.
    Path shared = Files.createTempDirectory(root, "saved-engine-shared-").resolve("应用 数据");
    Files.createDirectories(shared);
    for (String phase : List.of("select", "start")) {
      Path result =
          DesktopProbeProcess.run(
              ContributeSavedEngineSelectionDesktopTest.class,
              "saved-engine-" + phase,
              nativeCaptureArgs(),
              List.of(phase, encodePath(shared)),
              120);
      assertEquals("passed", new JSONObject(Files.readString(result)).getString("result"), phase);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args[0].equals("foreground")) {
      foreground(decodePath(args[1]));
      return;
    }
    Path shared = decodePath(args[1]);
    Path work = Path.of(args[2]);
    Path result = Path.of(args[3]);
    int exit = 0;
    try {
      JSONObject evidence =
          args[0].equals("select") ? select(shared, work, result) : start(shared, work, result);
      Files.writeString(result, evidence.put("result", "passed").toString(2));
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(
          result,
          new JSONObject().put("result", "failed").put("error", failure.toString()).toString(2));
      exit = 1;
    } finally {
      if (Lizzie.frame != null && Lizzie.frame.contributeEngine != null)
        Lizzie.frame.contributeEngine.normalQuit();
      if (Lizzie.engineManager != null) Lizzie.engineManager.forceKillAllEngines();
    }
    System.exit(exit);
  }

  /** S1, S3, S4, S5 on the actual dialog while foreground engine A analyzes. */
  private static JSONObject select(Path shared, Path work, Path result) throws Exception {
    Path absoluteDir = shared.resolve("engines").resolve("甲 目录");
    // Relative to this JVM's working directory; the start phase runs from another directory.
    Path relativeSource = Path.of("相对 目录", FIXTURE);
    Path relativeDir = work.resolve("相对 目录");
    installFixture(absoluteDir, shared);
    installFixture(relativeDir, shared);
    Path a1 = absoluteDir.resolve(FIXTURE).toAbsolutePath().normalize();
    Path b = relativeDir.resolve(FIXTURE).toAbsolutePath().normalize();
    Files.writeString(shared.resolve("contribute.cfg"), "# controlled contribution config\n");

    boot(shared);
    Path commands = work.resolve("foreground-commands.txt");
    String foregroundCommand = javaChild("foreground", commands);
    List<EngineData> saved = new ArrayList<>();
    saved.add(engine("Foreground A", foregroundCommand, true));
    saved.add(
        engine(
            "KataGo",
            quote(a1)
                + " gtp -model "
                + quote(shared.resolve("model.bin.gz"))
                + " -config "
                + quote(shared.resolve("gtp.cfg"))
                + " -override-config \"maxVisits=7\"",
            false));
    saved.add(
        engine(
            "KataGo",
            quote(relativeSource)
                + " analysis -model model.bin.gz -config analysis.cfg -override-config"
                + " \"maxVisits=9\"",
            false));
    EngineData ssh = engine("Remote SSH", "/opt/katago gtp -config gtp.cfg", false);
    ssh.useJavaSSH = true;
    ssh.ip = "192.0.2.10";
    ssh.userName = "fixture-ssh-user";
    ssh.password = "fixture-ssh-password";
    saved.add(ssh);
    saved.add(
        engine(
            "Wrapper",
            "ssh -i fixture-key.pem fixture-wrapper-user@192.0.2.11 \"/opt/katago gtp -model"
                + " m.bin.gz\" -pw fixture-wrapper-password",
            false));
    saved.add(engine("Empty", "", false));
    saved.add(engine("Missing", quote(shared.resolve("absent").resolve(FIXTURE)) + " gtp", false));
    Utils.saveEngineSettings(new ArrayList<>(saved));
    Leelaz foregroundA = startForeground(foregroundCommand, saved.get(0).id);
    long foregroundPid = Long.parseLong(Files.readString(Path.of(commands + ".pid")));

    Lizzie.config.contributeEnginePath = ORIGINAL_PATH;
    // The existing config field is appended verbatim, so a spaced path is entered quoted.
    Lizzie.config.contributeConfigPath = quote(shared.resolve("contribute.cfg"));
    Lizzie.config.contributeUserName = "fixture-contrib-user";
    Lizzie.config.contributePassword = "fixture-contrib-password";
    Lizzie.config.contributeBatchGames = "3";
    Lizzie.config.contributeCommand = CUSTOM_COMMAND;
    Lizzie.config.contributeUseCommand = false;
    Lizzie.config.leelazConfig.put(
        "contribute-engine-ssh-info",
        new JSONObject()
            .put("useJavaSSH", false)
            .put("ip", "192.0.2.20")
            .put("userName", "fixture-remote")
            .put("password", "fixture-remote-password"));
    // Persist the user's prior settings once so every contribution key has a baseline.
    ContributeSettings prior = openSettings();
    onEdt(() -> button(prior, "ContributeSettings.btnSave").doClick());
    onEdt(prior::dispose);
    String unchanged = isolationSnapshot();
    Set<Long> processesBefore = descendants();
    String commandsBefore = read(commands);

    ContributeSettings settings = openSettings();
    JButton savedButton = button(settings, "ContributeSettings.btnSavedEngine");
    assertTrue(savedButton.isFocusable(), "Saved engines must remain keyboard accessible");
    JTextComponent path = enginePathField(settings);
    assertEquals(ORIGINAL_PATH, text(path));

    // Cancel: whole list visible, nothing changes.
    SavedEnginePathChooser chooser = openChooser(savedButton);
    JTable table = component(chooser, JTable.class, t -> true);
    JButton fill = button(chooser, "ContributeSettings.savedEngine.fill");
    List<String> shown = new ArrayList<>();
    List<String> rows = new ArrayList<>();
    onEdt(
        () -> {
          assertEquals(saved.size(), table.getRowCount());
          for (int row = 0; row < table.getRowCount(); row++) {
            List<String> cells = new ArrayList<>();
            for (int column = 0; column < table.getColumnCount(); column++)
              cells.add(String.valueOf(table.getValueAt(row, column)));
            rows.add(String.join(" | ", cells));
            shown.addAll(cells);
            table.setRowSelectionInterval(row, row);
            shown.add(detail(chooser).getText());
          }
          shown.add(hint(chooser).getText());
        });
    String displayed = String.join("\n", shown);
    for (String secret : SECRETS)
      assertFalse(displayed.contains(secret), "Chooser leaked " + secret + ":\n" + displayed);
    // Same names are told apart by the full executable path; index is display-only.
    assertEquals("2 | KataGo | " + local() + " | " + a1, rows.get(1));
    assertEquals("3 | KataGo | " + local() + " | " + b, rows.get(2));
    assertReason(chooser, table, fill, 3, "reasonRemote");
    assertReason(chooser, table, fill, 4, "reasonIndirect");
    capture(chooser, result.resolveSibling("chooser-unavailable.png"));
    assertReason(chooser, table, fill, 5, "reasonEmpty");
    assertReason(chooser, table, fill, 6, "reasonMissing");
    onEdt(() -> table.setRowSelectionInterval(2, 2));
    assertTrue(detail(chooser).getText().contains(b.toString()), "Full path not viewable");
    capture(chooser, result.resolveSibling("chooser.png"));
    onEdt(() -> button(chooser, "ContributeSettings.savedEngine.cancel").doClick());
    await(() -> !chooser.isShowing());
    assertEquals(ORIGINAL_PATH, text(path));

    // Unavailable entry cannot be confirmed through the default (Enter) button; closing keeps it.
    SavedEnginePathChooser rejecting = openChooser(savedButton);
    JTable rejectingTable = component(rejecting, JTable.class, t -> true);
    onEdt(
        () -> {
          rejectingTable.setRowSelectionInterval(4, 4);
          rejecting.getRootPane().getDefaultButton().doClick();
        });
    assertTrue(rejecting.isShowing());
    onEdt(rejecting::dispose);
    await(() -> !rejecting.isShowing());
    assertEquals(ORIGINAL_PATH, text(path));

    // Select B (the second "KataGo"): only the path field changes.
    SavedEnginePathChooser choosing = openChooser(savedButton);
    JTable choosingTable = component(choosing, JTable.class, t -> true);
    onEdt(
        () -> {
          choosingTable.setRowSelectionInterval(2, 2);
          button(choosing, "ContributeSettings.savedEngine.fill").doClick();
        });
    await(() -> !choosing.isShowing());
    String bField = fieldText(b);
    assertEquals(bField, text(path));
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals(unchanged, isolationSnapshot(), "Selection changed other configuration");
    assertEquals(processesBefore, descendants(), "Selection started or stopped a process");
    assertSame(foregroundA, Lizzie.leelaz);
    assertTrue(ProcessHandle.of(foregroundPid).orElseThrow().isAlive());
    assertEquals(commandsBefore, read(commands), "Selection sent commands to foreground A");
    assertEquals(ORIGINAL_PATH, Lizzie.config.contributeEnginePath, "Selection saved early");

    // S5: live custom-command toggle disables the new entry together with path/browse.
    JCheckBox useCommand = useCommandBox(settings);
    onEdt(useCommand::doClick);
    assertEnabled(settings, false);
    assertEquals(CUSTOM_COMMAND, text(commandField(settings)));
    onEdt(useCommand::doClick);
    assertEnabled(settings, true);
    assertEquals(bField, text(path));
    capture(settings, result.resolveSibling("settings-selected.png"));

    onEdt(() -> button(settings, "ContributeSettings.btnSave").doClick());
    assertEquals(bField, Lizzie.config.contributeEnginePath);
    assertEquals(unchanged, isolationSnapshot(), "Save changed fields other than the path");
    onEdt(settings::dispose);
    Lizzie.config.save();

    ContributeSettings reopened = openSettings();
    assertEquals(bField, text(enginePathField(reopened)));
    onEdt(reopened::dispose);

    // Source entries reordered, renamed, re-pointed and deleted: the snapshot stays B.
    List<EngineData> edited = new ArrayList<>(Utils.getEngineData());
    EngineData sourceB = edited.remove(2);
    sourceB.name = "Renamed source B";
    sourceB.commands = quote(a1) + " gtp";
    edited.remove(1);
    edited.add(1, sourceB);
    Utils.saveEngineSettings(new ArrayList<>(edited));
    Lizzie.config.save();
    ContributeSettings afterEdit = openSettings();
    assertEquals(bField, text(enginePathField(afterEdit)));
    SavedEnginePathChooser refreshed =
        openChooser(button(afterEdit, "ContributeSettings.btnSavedEngine"));
    JTable refreshedTable = component(refreshed, JTable.class, t -> true);
    List<String> refreshedRows = new ArrayList<>();
    onEdt(
        () -> {
          for (int row = 0; row < refreshedTable.getRowCount(); row++)
            refreshedRows.add(
                refreshedTable.getValueAt(row, 1) + " | " + refreshedTable.getValueAt(row, 3));
        });
    assertEquals(edited.size(), refreshedRows.size());
    assertEquals("Renamed source B | " + a1, refreshedRows.get(1));
    onEdt(refreshed::dispose);
    onEdt(afterEdit::dispose);
    assertEquals(bField, Lizzie.config.contributeEnginePath);
    Lizzie.config.save();

    return new JSONObject()
        .put("phase", "select")
        .put("foregroundPid", foregroundPid)
        .put("selectedExecutable", b.toString())
        .put("fieldText", bField)
        .put("rows", new JSONArray(rows))
        .put("refreshedRows", new JSONArray(refreshedRows))
        .put("surface", "real-swing-controlled-processes");
  }

  /** Reload through normal persistence, S5 initial custom mode, then foreground A to B start. */
  private static JSONObject start(Path shared, Path work, Path result) throws Exception {
    boot(shared);
    String bField = Lizzie.config.contributeEnginePath;
    Path b = Path.of(Utils.splitCommand(bField).get(0));
    assertTrue(b.isAbsolute() && Files.isRegularFile(b), bField);

    ContributeSettings settings = openSettings();
    assertEquals(bField, text(enginePathField(settings)));
    onEdt(settings::dispose);

    // S5 initial custom-command state leaves command and remote settings untouched.
    String remote = Lizzie.config.leelazConfig.get("contribute-engine-ssh-info").toString();
    Lizzie.config.contributeUseCommand = true;
    ContributeSettings custom = openSettings();
    assertEnabled(custom, false);
    capture(custom, result.resolveSibling("settings-custom-mode.png"));
    assertEquals(CUSTOM_COMMAND, text(commandField(custom)));
    onEdt(() -> useCommandBox(custom).doClick());
    assertEnabled(custom, true);
    onEdt(custom::dispose);
    Lizzie.config.contributeUseCommand = false;
    assertEquals(remote, Lizzie.config.leelazConfig.get("contribute-engine-ssh-info").toString());
    assertEquals(CUSTOM_COMMAND, Lizzie.config.contributeCommand);

    // The reloaded saved entry A analyzes again; only the explicit start launches B.
    assertEquals("Foreground A", Utils.getEngineData().get(0).name);
    Lizzie.engineManager.switchEngineIfAvailable(0, true);
    Leelaz foregroundA = Lizzie.engineManager.engineList.get(0);
    awaitAnalyzing(foregroundA);
    Path argv = b.resolveSibling("argv.txt");
    Files.deleteIfExists(argv);
    String conflict =
        Lizzie.resourceBundle.getString("AnalysisSettings.reuseStatus.existing_lease");
    AtomicInteger conflicts = new AtomicInteger();
    Runnable dismissConflicts =
        () -> {
          for (Window window : Window.getWindows())
            if (window.isShowing() && containsText(window, conflict)) {
              conflicts.incrementAndGet();
              window.dispose();
            }
        };
    Timer dialogs = new Timer(20, event -> dismissConflicts.run());
    SwingUtilities.invokeAndWait(dialogs::start);
    List<String> received;
    try {
      ContributeSettings starting = openSettings();
      onEdt(() -> button(starting, "ContributeSettings.btnStart").doClick());
      await(() -> Files.exists(argv));
      received = Files.readAllLines(argv, StandardCharsets.UTF_8);
      await(
          () ->
              Lizzie.frame.contributeEngine != null
                  && Lizzie.frame.contributeEngine.watchingGameIndex == 0
                  && Lizzie.board.getHistory().getMoveNumber() == 1);
      SwingUtilities.invokeAndWait(() -> {});
      SwingUtilities.invokeAndWait(dismissConflicts);
    } finally {
      SwingUtilities.invokeAndWait(dialogs::stop);
    }
    assertTrue(Lizzie.frame.isContributing);
    capture(Lizzie.frame, result.resolveSibling("contribution-display.png"));
    assertEquals(b, Path.of(received.get(0)).toAbsolutePath().normalize(), received.toString());
    List<String> arguments = received.subList(1, received.size());
    assertEquals(
        List.of("contribute", "-config", shared.resolve("contribute.cfg").toString()),
        arguments.subList(0, 3),
        arguments.toString());
    assertEquals("-override-config", arguments.get(3), arguments.toString());
    String overrides = arguments.get(4);
    assertTrue(overrides.contains("username=fixture-contrib-user"), overrides);
    assertTrue(overrides.contains("maxSimultaneousGames=3"), overrides);
    assertTrue(overrides.contains("logGamesAsJson=true"), overrides);
    assertEquals(5, arguments.size(), arguments.toString());
    for (String forbidden : List.of("gtp", "analysis", "-model", "model.bin.gz", "maxVisits"))
      assertTrue(
          arguments.stream().noneMatch(argument -> argument.contains(forbidden)),
          forbidden + " leaked into " + arguments);
    assertEquals(0, conflicts.get(), "Unrelated exclusive-use prompt after contribution start");
    return new JSONObject()
        .put("phase", "start")
        .put("reloadedPath", bField)
        .put("argvFile", argv.toString())
        .put("argvLines", received.size())
        .put("argument0", arguments.get(0))
        .put("conflictDialogs", conflicts.get())
        .put("surface", "real-swing-controlled-processes");
  }

  private static void boot(Path shared) throws Exception {
    Path config = shared.resolve("config.txt");
    if (!Files.exists(config))
      Files.writeString(
          config,
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,"
              + "\"first-time-load\":false,\"use-language\":2}}");
    System.setProperty("lizzie.work.dir", shared.toString());
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
    SwingUtilities.invokeAndWait(
        () -> {
          for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
        });
  }

  private static Leelaz startForeground(String command, String id) throws Exception {
    int index = Lizzie.engineManager.engineList.size();
    Leelaz engine = new Leelaz(command);
    engine.savedEntryId = id;
    engine.width = engine.height = engine.oriWidth = engine.oriHeight = 19;
    engine.komi = engine.orikomi = 7.5f;
    Lizzie.engineManager.engineList.add(engine);
    Lizzie.engineManager.switchEngineIfAvailable(index, true);
    awaitAnalyzing(engine);
    return engine;
  }

  private static void awaitAnalyzing(Leelaz engine) throws Exception {
    await(
        () ->
            Lizzie.leelaz == engine
                && engine.previewExclusiveGtpLeaseAvailability()
                    == Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE);
    engine.ponder();
  }

  /** Everything the selection must leave alone, excluding the engine path itself. */
  private static String isolationSnapshot() {
    JSONObject snapshot = new JSONObject();
    for (String key : new TreeSet<>(Lizzie.config.uiConfig.keySet()))
      if (key.startsWith("contribute-") && !key.equals("contribute-engine-path"))
        snapshot.put(key, Lizzie.config.uiConfig.get(key));
    snapshot.put("default-engine", String.valueOf(Lizzie.config.uiConfig.opt("default-engine")));
    snapshot.put(
        "engine-settings-list",
        String.valueOf(Lizzie.config.leelazConfig.opt("engine-settings-list")));
    snapshot.put(
        "contribute-engine-ssh-info",
        String.valueOf(Lizzie.config.leelazConfig.opt("contribute-engine-ssh-info")));
    snapshot.put(
        "fields",
        new JSONArray(
            List.of(
                Lizzie.config.contributeConfigPath,
                Lizzie.config.contributeUserName,
                Lizzie.config.contributePassword,
                Lizzie.config.contributeBatchGames,
                Lizzie.config.contributeCommand,
                Boolean.toString(Lizzie.config.contributeUseCommand))));
    return snapshot.toString();
  }

  private static ContributeSettings openSettings() throws Exception {
    onEdt(() -> Lizzie.frame.openContributeSettings());
    AtomicReference<ContributeSettings> found = new AtomicReference<>();
    await(
        () -> {
          found.set(showing(ContributeSettings.class));
          return found.get() != null;
        });
    return found.get();
  }

  private static SavedEnginePathChooser openChooser(JButton savedButton) throws Exception {
    // Modal: click from a queued event so this thread keeps driving the nested event loop.
    SwingUtilities.invokeLater(savedButton::doClick);
    AtomicReference<SavedEnginePathChooser> found = new AtomicReference<>();
    await(
        () -> {
          found.set(showing(SavedEnginePathChooser.class));
          return found.get() != null;
        });
    return found.get();
  }

  private static <T extends Window> T showing(Class<T> type) {
    T match = null;
    for (Window window : Window.getWindows())
      if (type.isInstance(window) && window.isShowing()) match = type.cast(window);
    return match;
  }

  private static void assertReason(
      SavedEnginePathChooser chooser, JTable table, JButton fill, int row, String reason)
      throws Exception {
    String expected = Lizzie.resourceBundle.getString("ContributeSettings.savedEngine." + reason);
    onEdt(
        () -> {
          table.setRowSelectionInterval(row, row);
          assertFalse(fill.isEnabled(), "Unavailable row " + row + " can be filled");
          assertEquals(expected, String.valueOf(table.getValueAt(row, 3)));
          assertTrue(detail(chooser).getText().contains(expected));
        });
  }

  private static void assertEnabled(ContributeSettings settings, boolean pathMode)
      throws Exception {
    onEdt(
        () -> {
          assertEquals(pathMode, button(settings, "ContributeSettings.btnSavedEngine").isEnabled());
          assertEquals(pathMode, enginePathField(settings).isEnabled());
          assertEquals(pathMode, button(settings, "ContributeSettings.btnScan").isEnabled());
          assertEquals(!pathMode, commandField(settings).isEnabled());
        });
  }

  private static String local() {
    return Lizzie.resourceBundle.getString("ContributeSettings.savedEngine.typeLocal");
  }

  private static JTextArea hint(SavedEnginePathChooser chooser) {
    String tip = Lizzie.resourceBundle.getString("ContributeSettings.savedEngineTip");
    return component(chooser, JTextArea.class, area -> tip.equals(area.getText()));
  }

  private static JTextArea detail(SavedEnginePathChooser chooser) {
    String tip = Lizzie.resourceBundle.getString("ContributeSettings.savedEngineTip");
    return component(chooser, JTextArea.class, area -> !tip.equals(area.getText()));
  }

  private static JTextComponent enginePathField(Container root) {
    String tip = Lizzie.resourceBundle.getString("ContributeSettings.engineTip");
    return component(root, JTextComponent.class, field -> tip.equals(field.getToolTipText()));
  }

  private static JTextComponent commandField(Container root) {
    String tip = Lizzie.resourceBundle.getString("ContributeSettings.customCommandTip");
    return component(root, JTextComponent.class, field -> tip.equals(field.getToolTipText()));
  }

  private static JCheckBox useCommandBox(Container root) {
    String label = Lizzie.resourceBundle.getString("ContributeSettings.chkUseCommand");
    return component(root, JCheckBox.class, box -> label.equals(box.getText()));
  }

  private static JButton button(Container root, String key) {
    String label = Lizzie.resourceBundle.getString(key);
    return component(root, JButton.class, button -> label.equals(button.getText()));
  }

  private static <T extends Component> T component(
      Container root, Class<T> type, Predicate<? super T> matches) {
    T found = find(root, type, matches);
    assertNotNull(found, "Missing " + type.getSimpleName());
    return found;
  }

  private static <T extends Component> T find(
      Component root, Class<T> type, Predicate<? super T> matches) {
    if (type.isInstance(root) && matches.test(type.cast(root))) return type.cast(root);
    if (root instanceof Container container)
      for (Component child : container.getComponents()) {
        T found = find(child, type, matches);
        if (found != null) return found;
      }
    return null;
  }

  private static boolean containsText(Component component, String text) {
    if (component instanceof JLabel label
        && label.getText() != null
        && label.getText().contains(text)) return true;
    if (component instanceof JTextComponent field
        && field.getText() != null
        && field.getText().contains(text)) return true;
    if (component instanceof Container container)
      for (Component child : container.getComponents()) if (containsText(child, text)) return true;
    return false;
  }

  private static String text(JTextComponent field) throws Exception {
    AtomicReference<String> value = new AtomicReference<>();
    if (SwingUtilities.isEventDispatchThread()) value.set(field.getText());
    else SwingUtilities.invokeAndWait(() -> value.set(field.getText()));
    return value.get();
  }

  private static String fieldText(Path executable) {
    String value = executable.toString();
    return value.indexOf(' ') >= 0 ? "\"" + value + "\"" : value;
  }

  private static EngineData engine(String name, String commands, boolean isDefault) {
    EngineData entry = new EngineData();
    entry.id = UUID.randomUUID().toString();
    entry.name = name;
    entry.commands = commands;
    entry.width = entry.height = 19;
    entry.komi = 7.5f;
    entry.isDefault = isDefault;
    return entry;
  }

  /** A real directly launched executable that records argv, then emits one valid game. */
  private static void installFixture(Path directory, Path shared) throws Exception {
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("game.json"), game() + "\n");
    Path target = directory.resolve(FIXTURE);
    if (WINDOWS) {
      Path compiled = shared.resolve("fixture-build").resolve(FIXTURE);
      if (!Files.exists(compiled)) compileWindowsFixture(compiled);
      Files.copy(compiled, target, StandardCopyOption.REPLACE_EXISTING);
      return;
    }
    Files.writeString(
        target,
        String.join(
            "\n",
            "#!/bin/sh",
            "dir=$(dirname \"$0\")",
            "printf '%s\\n' \"$0\" > \"$dir/argv.tmp\"",
            "for arg in \"$@\"; do printf '%s\\n' \"$arg\" >> \"$dir/argv.tmp\"; done",
            "mv \"$dir/argv.tmp\" \"$dir/argv.txt\"",
            "cat \"$dir/game.json\"",
            "cat > /dev/null",
            ""));
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  private static void compileWindowsFixture(Path exe) throws Exception {
    Files.createDirectories(exe.getParent());
    Path source = exe.resolveSibling("Fixture.cs");
    Files.writeString(
        source,
        String.join(
            "\n",
            "using System; using System.IO; using System.Text;",
            "class Fixture { static void Main(string[] args) {",
            "  string dir = AppDomain.CurrentDomain.BaseDirectory;",
            "  StringBuilder lines = new StringBuilder();",
            "  lines.Append(System.Reflection.Assembly.GetExecutingAssembly().Location);",
            "  lines.Append('\\n');",
            "  foreach (string arg in args) lines.Append(arg).Append('\\n');",
            "  string tmp = Path.Combine(dir, \"argv.tmp\");",
            "  File.WriteAllText(tmp, lines.ToString(), new UTF8Encoding(false));",
            "  File.Move(tmp, Path.Combine(dir, \"argv.txt\"));",
            "  Console.Out.WriteLine(File.ReadAllText(Path.Combine(dir, \"game.json\")).Trim());",
            "  Console.Out.Flush();",
            "  Console.In.ReadToEnd();",
            "} }",
            ""),
        StandardCharsets.UTF_8);
    Path csc =
        Path.of(
            System.getenv().getOrDefault("WINDIR", "C:\\Windows"),
            "Microsoft.NET",
            "Framework64",
            "v4.0.30319",
            "csc.exe");
    Process compile =
        new ProcessBuilder(
                csc.toString(), "/nologo", "/out:" + exe, "/target:exe", source.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(compile.waitFor(60, TimeUnit.SECONDS), "csc timed out");
    assertEquals(0, compile.exitValue(), output);
  }

  private static String game() {
    return new JSONObject()
        .put("gameId", "saved-engine-fixture")
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
        .put("initialStones", new JSONArray())
        .put("moves", new JSONArray().put(new JSONArray(List.of("B", "D4"))))
        .put("move", new JSONArray(List.of("W", "Q16")))
        .put("moveInfos", new JSONArray())
        .toString();
  }

  private static void foreground(Path commands) throws Exception {
    Files.writeString(Path.of(commands + ".pid"), Long.toString(ProcessHandle.current().pid()));
    try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
      String line;
      while ((line = input.readLine()) != null) {
        Files.writeString(
            commands, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String[] parts = line.trim().split("\\s+", 3);
        String id = parts[0].matches("\\d+") ? parts[0] : "";
        String verb = parts[id.isEmpty() ? 0 : 1];
        String answer =
            switch (verb) {
              case "name" -> "KataGo";
              case "version" -> "1.16.4";
              case "protocol_version" -> "2";
              case "list_commands" ->
                  "protocol_version\nname\nversion\nlist_commands\nboardsize\nclear_board\nkomi\nplay\nundo\nstop\nkata-analyze\nkata-get-rules\nkata-set-rules\nset_position";
              case "kata-get-rules" ->
                  "{\"koRule\":\"POSITIONAL\",\"scoringRule\":\"AREA\",\"taxRule\":\"NONE\",\"suicideLegal\":true,\"hasButton\":false,\"whiteHandicapBonus\":\"N\"}";
              case "kata-get-param" -> "0";
              default -> "";
            };
        System.out.println("=" + id + " " + answer + "\n");
        System.out.flush();
        if (verb.equals("quit")) return;
      }
    }
  }

  private static String javaChild(String mode, Path file) {
    return quote(Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java"))
        + " -cp \""
        + System.getProperty("java.class.path")
        + "\" "
        + ContributeSavedEngineSelectionDesktopTest.class.getName()
        + " "
        + mode
        + " "
        + encodePath(file);
  }

  // Test JVM launcher arguments use the Windows system code page on some JDKs. Keep the
  // harness transport ASCII while still exercising real CJK paths inside the application.
  private static String encodePath(Path path) {
    return java.util.Base64.getUrlEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static Path decodePath(String encoded) {
    return Path.of(new String(java.util.Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
  }

  private static Set<Long> descendants() {
    return ProcessHandle.current()
        .descendants()
        .filter(ProcessHandle::isAlive)
        .map(ProcessHandle::pid)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static void capture(Window window, Path file) throws Exception {
    AtomicReference<Rectangle> bounds = new AtomicReference<>();
    onEdt(() -> bounds.set(window.getBounds()));
    ImageIO.write(new Robot().createScreenCapture(bounds.get()), "png", file.toFile());
    String script = System.getProperty(NATIVE_CAPTURE);
    if (script == null) return;
    // Robot cannot see a private Win32 desktop; capture this JVM's windows natively instead.
    String name = file.getFileName().toString().replaceFirst("\\.png$", "");
    Process capture =
        new ProcessBuilder(
                "pwsh.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                script,
                "-OwnerPid",
                Long.toString(ProcessHandle.current().pid()),
                "-OutputDirectory",
                file.resolveSibling(name + "-native").toString())
            .redirectErrorStream(true)
            .redirectOutput(file.resolveSibling(name + "-native.log").toFile())
            .start();
    assertTrue(capture.waitFor(60, TimeUnit.SECONDS), "Native capture timed out");
    assertEquals(0, capture.exitValue(), "Native capture failed for " + name);
  }

  /** Forwards an optional native window capture script to the probe JVMs. */
  private static List<String> nativeCaptureArgs() {
    String script = System.getProperty(NATIVE_CAPTURE);
    return script == null ? List.of() : List.of("-D" + NATIVE_CAPTURE + "=" + script);
  }

  private static String quote(Path value) {
    return "\"" + value + "\"";
  }

  private static String read(Path path) {
    try {
      return Files.exists(path) ? Files.readString(path) : "";
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static void onEdt(ThrowingRunnable action) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() instanceof Exception error) throw error;
    if (failure.get() instanceof Error error) throw error;
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
    assertTrue(condition.getAsBoolean(), "Timed out waiting for controlled UI/process state");
  }
}
