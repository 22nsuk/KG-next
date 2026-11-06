package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.RulePreset;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Real main-window popup, manual rules protocol and acknowledged position convergence. */
class RulesQuickSwitchDesktopTest {
  @Test
  void captionPresetsConvergeTheCurrentGameAndKeepHandicapDefaultsAndStaleMenusSafe()
      throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.run(getClass(), "quick-rules", List.of(), List.of("probe"));
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
    System.setProperty("lizzie.work.dir", work.toString());
    Files.writeString(work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":3}}");
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
    edt(() -> {
      for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
      return null;
    });
    AtomicReference<KataGoRules> rules = new AtomicReference<>(RulePreset.CHINESE.rules());
    AtomicBoolean holdReadback = new AtomicBoolean();
    AtomicBoolean holdRestore = new AtomicBoolean();
    List<String> restored = java.util.Collections.synchronizedList(new ArrayList<>());
    Leelaz engine = new Leelaz("");
    engine.isKatago = true;
    engine.isLoaded = true;
    Field started = Leelaz.class.getDeclaredField("started");
    started.setAccessible(true);
    started.setBoolean(engine, true);
    var advertise = Leelaz.class.getDeclaredMethod("advertiseCommandsForTest", List.class);
    advertise.setAccessible(true);
    advertise.invoke(engine, List.of("kata-set-rules", "kata-get-rules", "loadsgf", "kata-analyze"));
    var transport = ExactSnapshotRestoreProtocolFixture.installAsync(engine, command -> {
      if (command.startsWith("kata-set-rules ")) {
        rules.set(KataGoRules.parse(command.substring("kata-set-rules ".length())).orElseThrow());
      }
      if (command.equals("kata-get-rules")) {
        if (holdReadback.get()) return null;
        return ExactSnapshotRestoreProtocolFixture.Response.success(rules.get().toGtpArgument());
      }
      if (command.startsWith("loadsgf ")) {
        restored.add(Files.readString(Path.of(command.substring("loadsgf ".length()))));
        if (holdRestore.get()) return null;
      }
      return ExactSnapshotRestoreProtocolFixture.Response.success();
    });
    edt(() -> {
      Lizzie.setPrimaryEngine(engine);
      EngineManager.isEmpty = false;
      Lizzie.board.getHistory().getGameInfo().setKomi(7.5);
      Lizzie.config.autoLoadKataRules = true;
      Lizzie.config.kataRules = RulePreset.GOE.rules().toGtpArgument();
      return null;
    });
    engine.queryEngineRulesOperation().await(2_000L);
    byte[] config = Files.readAllBytes(work.resolve("config.txt"));
    JPopupMenu menu = openFromCaption();
    capture(result.resolveSibling("quick-rules-ko-before.png"));
    edt(() -> { ((JMenuItem) menu.getComponent(2)).doClick(0); return null; });
    await(() -> Lizzie.board.getHistory().getGameInfo().getKomi() == 7.0
        && !Lizzie.board.getHistory().hasPendingManualRulesIntent()
        && !Lizzie.board.isEngineAlignmentPending() && !restored.isEmpty());
    assertTrue(restored.get(0).contains("KM[7.0]"), restored.toString());
    assertArrayEquals(config, Files.readAllBytes(work.resolve("config.txt")));
    assertEquals(RulePreset.GOE.rules().toGtpArgument(), Lizzie.config.kataRules);
    assertTrue(Lizzie.config.autoLoadKataRules);
    JPopupMenu selected = openFromCaption();
    assertTrue(edt(() -> ((JCheckBoxMenuItem) selected.getComponent(2)).isSelected()));
    capture(result.resolveSibling("quick-rules-ko.png"));
    // Let the engine change rules, then edit komi through the real toolbar before its readback.
    long queriesBefore = count(transport, "kata-get-rules");
    long analysisBefore = count(transport, "kata-analyze");
    int restoresBeforeEdit = restored.size();
    holdReadback.set(true);
    holdRestore.set(true);
    edt(() -> { ((JMenuItem) selected.getComponent(6)).doClick(0); return null; });
    await(() -> count(transport, "kata-get-rules") > queriesBefore);
    edt(() -> { LizzieFrame.menu.btnKomiDown.doClick(0); return null; });
    assertEquals(6.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    holdReadback.set(false);
    reply(transport, "kata-get-rules", rules.get().toGtpArgument());
    await(() -> restored.size() > restoresBeforeEdit);
    assertTrue(rules.get().semanticallyEquals(RulePreset.GOE.rules()));
    assertTrue(Lizzie.board.isEngineAlignmentPending());
    assertTrue(restored.get(restored.size() - 1).contains("KM[6.5]"));
    engine.sendCommand("kata-analyze 10");
    assertEquals(analysisBefore, count(transport, "kata-analyze"));
    holdRestore.set(false);
    reply(transport, "loadsgf ", "");
    await(() -> !Lizzie.board.getHistory().hasPendingManualRulesIntent()
        && !Lizzie.board.isEngineAlignmentPending()
        && count(transport, "kata-analyze") > analysisBefore);
    assertEquals(6.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertTrue(Lizzie.board.getHistory().captureSessionRules().parsedRules().orElseThrow()
        .semanticallyEquals(RulePreset.GOE.rules()));
    assertArrayEquals(config, Files.readAllBytes(work.resolve("config.txt")));
    edt(() -> {
      selected.setVisible(false);
      Lizzie.board.getHistory().getGameInfo().setHandicap(2);
      Lizzie.board.getHistory().getGameInfo().setKomi(0.5);
      return null;
    });
    JPopupMenu handicap = openFromCaption();
    assertEquals(9, handicap.getComponentCount());
    capture(result.resolveSibling("quick-rules-handicap-ko.png"));
    int restoredBefore = restored.size();
    edt(() -> { ((JMenuItem) handicap.getComponent(2)).doClick(0); return null; });
    await(() -> rules.get().semanticallyEquals(RulePreset.JAPANESE.rules())
        && !Lizzie.board.getHistory().hasPendingManualRulesIntent()
        && !Lizzie.board.isEngineAlignmentPending()
        && restored.size() > restoredBefore);
    assertEquals(0.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertTrue(restored.get(restored.size() - 1).contains("KM[0.5]"));

    // A menu displayed as rules-only must not start applying an even-game komi after an edit.
    List<String> messages = new ArrayList<>();
    RulesQuickSwitch edited = edt(() -> new RulesQuickSwitch(
        engine, Lizzie.board, Lizzie.frame, Lizzie.resourceBundle, messages::add));
    List<String> beforeEdited = transport.commands();
    edt(() -> {
      Lizzie.board.getHistory().getGameInfo().setHandicap(0);
      edited.apply(RulePreset.GOE);
      return null;
    });
    assertEquals(1, messages.size());
    assertEquals(0.5, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertEquals(beforeEdited, transport.commands());
    messages.clear();
    // An already-open popup cannot redirect an action into another history or primary.
    RulesQuickSwitch stale = edt(() -> new RulesQuickSwitch(
        engine, Lizzie.board, Lizzie.frame, Lizzie.resourceBundle, messages::add));
    List<String> beforeStale = transport.commands();
    edt(() -> {
      Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
      stale.apply(RulePreset.GOE);
      stale.openRulesDialog();
      return null;
    });
    assertEquals(2, messages.size());
    assertEquals(beforeStale, transport.commands());
    assertArrayEquals(config, Files.readAllBytes(work.resolve("config.txt")));
    Files.writeString(result, new JSONObject().put("status", "PASS")
        .put("transport", "deterministic GTP fixture")
        .put("restoredPositions", restored.size()).toString(2));
    edt(() -> { for (Window window : Window.getWindows()) window.dispose(); return null; });
  }

  private static JPopupMenu openFromCaption() throws Exception {
    edt(() -> { Lizzie.frame.refresh(); return null; });
    Field field = LizzieFrame.class.getDeclaredField("matchRulesCaptionBounds");
    field.setAccessible(true);
    await(() -> {
      try { return field.get(Lizzie.frame) != null; }
      catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    });
    return edt(() -> {
      try {
        Rectangle bounds = (Rectangle) field.get(Lizzie.frame);
        assertTrue(Lizzie.frame.tryInspectMatchRulesAt(bounds.x + bounds.width / 2,
            bounds.y + bounds.height / 2));
        for (var element : MenuSelectionManager.defaultManager().getSelectedPath()) {
          if (element instanceof JPopupMenu popup && popup.isVisible()) return popup;
        }
        throw new AssertionError("Rules caption did not open its popup");
      } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    });
  }

  private static long count(ExactSnapshotRestoreProtocolFixture.Transport transport, String prefix) {
    return transport.commands().stream().filter(command -> command.startsWith(prefix)).count();
  }

  private static void reply(
      ExactSnapshotRestoreProtocolFixture.Transport transport, String prefix, String payload) {
    List<String> commands = transport.rawCommands();
    for (int index = commands.size() - 1; index >= 0; index--) {
      String line = commands.get(index);
      int split = line.indexOf(' ');
      if (split > 0 && line.substring(split + 1).startsWith(prefix)) {
        transport.respond("=" + line.substring(0, split) + " " + payload);
        return;
      }
    }
    throw new AssertionError("Missing response target: " + prefix);
  }

  private static void capture(Path output) throws Exception {
    Robot robot = new Robot();
    robot.waitForIdle();
    Rectangle bounds = edt(() -> Lizzie.frame.getBounds());
    ImageIO.write(robot.createScreenCapture(bounds), "png", output.toFile());
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
    while (System.nanoTime() < deadline) {
      if (edt(condition::getAsBoolean)) return;
      Thread.sleep(10L);
    }
    assertTrue(edt(condition::getAsBoolean), "Desktop rules transition did not settle");
  }

  private static <T> T edt(Supplier<T> action) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> value.set(action.get()));
    return value.get();
  }
}
