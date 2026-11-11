package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.NodeInfo;
import featurecat.lizzie.rules.SGFParser;
import java.awt.Component;
import java.awt.Container;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The current sidebar must load and save profiles without the retired table's widgets. */
class BlunderSidebarPersistenceDesktopTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void savesAndRestartsWithCurrentAndLegacyPreferences(boolean legacy) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path saved =
        DesktopProbeProcess.run(
            getClass(), "blunder-sidebar-save", List.of(), List.of("" + legacy, "save"));
    assertEquals("PASS", Files.readString(saved));
    Path reloaded =
        DesktopProbeProcess.run(
            getClass(),
            "blunder-sidebar-reload",
            List.of(),
            List.of("" + legacy, "reload", saved.getParent().resolve("work").toString()));
    assertEquals("PASS", Files.readString(reloaded));
  }

  public static void main(String[] args) throws Exception {
    boolean legacy = Boolean.parseBoolean(args[0]);
    boolean reload = args[1].equals("reload");
    Path work = Path.of(args[2]);
    Path result = Path.of(args[args.length - 1]);
    try {
      if (!reload) writeProfile(work, legacy);
      System.setProperty("lizzie.work.dir", work.toString());
      DesktopProbeProcess.phase(result, "production-startup");
      Lizzie.main(new String[0]);
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(Lizzie.frame.isDisplayable());
            assertTrue(Lizzie.config.isShowingBlunderTabel);
            assertEquals(12, Lizzie.config.blunderWinThreshold);
            assertEquals(100, Lizzie.config.blunderPlayoutsThreshold);
            assertEquals(10, Lizzie.config.problemListWinrateThreshold);
            assertEquals(reload ? "white" : "black", Lizzie.config.problemListSideFilter);
            assertEquals(
                reload ? ProblemListSort.MOVE_DESC : ProblemListSort.MOVE_ASC,
                component(Lizzie.frame.sidebarPanel, BlunderListPanel.class).sortOrder());
          });
      DesktopProbeProcess.applicationReady(result);
      if (!reload) {
        verifySidebar();
        SwingUtilities.invokeAndWait(
            () -> {
              verifyThresholdDialog();
              verifySettings(result);
              Lizzie.resetAllHints();
              assertDoesNotThrow(() -> Lizzie.config.persist());
              assertDoesNotThrow(() -> Lizzie.config.save());
            });
      }
      verifySavedProfile(legacy);
      Files.writeString(result, "PASS");
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void writeProfile(Path work, boolean legacy) throws Exception {
    JSONObject ui = legacy ? legacyUi() : new JSONObject();
    ui.put("autoload-empty", true)
        .put("first-time-load", false)
        .put("use-language", 3)
        .put("play-sound", false)
        .put("auto-quick-analyze-on-load", false)
        .put("analysis-engine-preload", false)
        .put("show-comment", true)
        .put("is-showing-blunder-table", true)
        .put("blunder-win-threshold", 12)
        .put("blunder-playouts-threshold", 100)
        .put("problem-list-winrate-threshold", 10)
        .put("problem-list-side-filter", "black")
        .put("problem-list-sort", "move-asc")
        .put("show-badmoves-frame", false);
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject()
            .put("leelaz", new JSONObject().put("engine-settings-list", new JSONArray()))
            .put("ui", ui)
            .toString());
    JSONObject persistedUi = new JSONObject();
    if (legacy) persistedUi.put("main-window-blunder", legacyWidths());
    Files.writeString(
        work.resolve("persist"), new JSONObject().put("ui-persist", persistedUi).toString());
  }

  private static JSONObject legacyUi() {
    return new JSONObject()
        .put("blunder-table-only-after", true)
        .put("blunder-sort-num-AF", 3)
        .put("blunder-is-sorted-AF", true)
        .put("blunder-sort-is-origin-order-AF", false)
        .put("blunder-sort-num-NAF", 0)
        .put("blunder-is-sorted-NAF", true)
        .put("blunder-sort-is-origin-order-NAF", false)
        .put("hide-blunder-table-control-pane", true)
        .put("allow-close-comment-control-hint", false)
        .put("blunder-score-threshold", 99);
  }

  private static JSONArray legacyWidths() {
    return new JSONArray().put(41).put(82).put(63);
  }

  private static void verifySidebar() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          assertTrue(SGFParser.loadFromString("(;SZ[19];B[dd];W[pp];B[dp])"));
          BoardHistoryNode first = Lizzie.board.getHistory().getStart().next().orElseThrow();
          first.nodeInfoMain = analysis(2, false, 15, 15, 99, 100);
          first.next().orElseThrow().nodeInfoMain = analysis(3, true, 3, 15, 100, 100);
          Lizzie.frame.refreshProblemListSnapshot();
          ProblemListSnapshot snapshot = Lizzie.frame.getProblemListSnapshot();
          assertEquals(1, snapshot.blackEntries.size(), "visits at the shared threshold pass");
          assertEquals(0, snapshot.whiteEntries.size(), "insufficient visits are excluded");
          first.nodeInfoMain.playouts = 100;
          first.nodeInfoMain.previousPlayouts = 99;
          Lizzie.frame.refreshProblemListSnapshot();
          assertEquals(0, Lizzie.frame.getProblemListSnapshot().whiteEntries.size());
          first.nodeInfoMain.previousPlayouts = 100;
          Lizzie.frame.refreshProblemListSnapshot();
          assertEquals(1, Lizzie.frame.getProblemListSnapshot().whiteEntries.size());
        });
    // Sidebar listeners post their model updates to the EDT.
    SwingUtilities.invokeAndWait(
        () -> {
          BlunderListPanel panel = component(Lizzie.frame.sidebarPanel, BlunderListPanel.class);
          JList<?> list = component(panel, JList.class);
          assertEquals(3, ((ProblemMoveEntry) list.getModel().getElementAt(0)).moveNumber);
          Lizzie.frame.setProblemListSideFilter(ProblemListSideFilter.WHITE);
        });
    SwingUtilities.invokeAndWait(
        () -> {
          BlunderListPanel panel = component(Lizzie.frame.sidebarPanel, BlunderListPanel.class);
          JList<?> list = component(panel, JList.class);
          assertEquals(1, list.getModel().getSize());
          assertEquals(2, ((ProblemMoveEntry) list.getModel().getElementAt(0)).moveNumber);
          list.setSelectedIndex(0);
          list.getActionMap()
              .get("open-problem")
              .actionPerformed(new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "open-problem"));
          assertEquals(1, Lizzie.board.getHistory().getMoveNumber());
          component(panel, JComboBox.class).setSelectedItem(ProblemListSort.MOVE_DESC);
          Lizzie.frame.sidebarPanel.switchTo("COMMENTS");
          assertFalse(Lizzie.config.isShowingBlunderTabel);
          assertFalse(panel.isVisible());
          Lizzie.frame.sidebarPanel.switchTo("BLUNDERS");
          assertTrue(panel.isVisible());
          assertTrue(Lizzie.config.isShowingBlunderTabel);
        });
  }

  private static NodeInfo analysis(
      int move, boolean black, int x, int y, int visits, int previousVisits) {
    NodeInfo info = new NodeInfo();
    info.analyzed = true;
    info.moveNum = move;
    info.isBlack = black;
    info.coords = new int[] {x, y};
    info.diffWinrate = -20;
    info.playouts = visits;
    info.previousPlayouts = previousVisits;
    return info;
  }

  private static void verifyThresholdDialog() {
    Lizzie.config.saveThreshold(7, 2, 300, false);
    SetThreshold dialog = new SetThreshold(Lizzie.frame);
    try {
      List<JSpinner> spinners = components(dialog, JSpinner.class);
      assertEquals(3, spinners.size());
      assertEquals(7, spinners.get(0).getValue());
      assertEquals(2, spinners.get(1).getValue());
      assertEquals(300, spinners.get(2).getValue());
      spinners.get(0).setValue(17);
      spinners.get(1).setValue(5);
      spinners.get(2).setValue(700);
      component(dialog, JCheckBox.class).doClick(0);
      assertEquals(17, Lizzie.config.moveListWinrateThreshold);
      assertEquals(5, Lizzie.config.moveListScoreThreshold);
      assertEquals(700, Lizzie.config.moveListVisitsThreshold);
      assertTrue(Lizzie.config.moveListTopCurNode);
      components(dialog, JButton.class).stream()
          .filter(
              button ->
                  Lizzie.resourceBundle
                      .getString("SetKataEngines.btnCancel")
                      .equals(button.getText()))
          .findFirst()
          .orElseThrow()
          .doClick(0);
      assertEquals(7, Lizzie.config.moveListWinrateThreshold);
      assertEquals(2, Lizzie.config.moveListScoreThreshold);
      assertEquals(300, Lizzie.config.moveListVisitsThreshold);
      assertFalse(Lizzie.config.moveListTopCurNode);
      assertEquals(12, Lizzie.config.blunderWinThreshold);
      assertEquals(100, Lizzie.config.blunderPlayoutsThreshold);
    } finally {
      dialog.dispose();
    }
  }

  private static void verifySettings(Path result) {
    ConfigDialog2 dialog = new ConfigDialog2();
    AtomicReference<Throwable> captureFailure = new AtomicReference<>();
    try {
      assertFalse(dialog.locateSetting("config.play.hide-panel-controls"));
      assertTrue(dialog.locateSetting("config.play.comment-panel"));
      Timer capture =
          new Timer(
              350,
              event -> {
                try {
                  ImageIO.write(
                      new Robot().createScreenCapture(dialog.getBounds()),
                      "png",
                      result.resolveSibling("settings.png").toFile());
                } catch (Throwable failure) {
                  captureFailure.set(failure);
                } finally {
                  dialog.setVisible(false);
                }
              });
      capture.setRepeats(false);
      capture.start();
      dialog.setVisible(true);
      assertNull(captureFailure.get(), "settings screenshot failed");
      assertTrue(Files.isRegularFile(result.resolveSibling("settings.png")));
    } finally {
      dialog.dispose();
    }
  }

  private static void verifySavedProfile(boolean legacy) throws Exception {
    JSONObject ui =
        new JSONObject(Files.readString(Path.of(Lizzie.config.getConfigFilePath())))
            .getJSONObject("ui");
    for (String key : legacyUi().keySet()) {
      if (legacy) assertEquals(legacyUi().get(key), ui.get(key), key);
      else assertFalse(ui.has(key), key);
    }
    assertEquals(12, ui.getInt("blunder-win-threshold"));
    assertEquals(100, ui.getInt("blunder-playouts-threshold"));
    assertEquals("white", ui.getString("problem-list-side-filter"));
    assertEquals("move-desc", ui.getString("problem-list-sort"));
    assertTrue(ui.getBoolean("is-showing-blunder-table"));
    JSONObject persistedUi =
        new JSONObject(Files.readString(Path.of(Lizzie.config.getPersistFilePath())))
            .getJSONObject("ui-persist");
    if (legacy)
      assertEquals(
          legacyWidths().toList(), persistedUi.getJSONArray("main-window-blunder").toList());
    else assertFalse(persistedUi.has("main-window-blunder"));
  }

  private static <T extends Component> T component(Container root, Class<T> type) {
    return components(root, type).stream().findFirst().orElseThrow();
  }

  private static <T extends Component> List<T> components(Container root, Class<T> type) {
    List<T> found = new ArrayList<>();
    for (Component child : root.getComponents()) {
      if (type.isInstance(child)) found.add(type.cast(child));
      if (child instanceof Container container) found.addAll(components(container, type));
    }
    return found;
  }
}
