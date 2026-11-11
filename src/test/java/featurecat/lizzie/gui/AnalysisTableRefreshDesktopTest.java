package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameSnapshotFixtures;
import featurecat.lizzie.enginegame.RunState;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.NodeInfo;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.awt.Color;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.JTableHeader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real table models, controls, timers and exports share the same prepared rows. */
class AnalysisTableRefreshDesktopTest {
  @ParameterizedTest
  @ValueSource(strings = {"analysis", "moves"})
  void preparedRowsStayConsistentAndRefreshFromRealEvents(String family) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(getClass(), "table-refresh-" + family, List.of(), List.of(family));
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) {
    try {
      runProbe(args[0], Path.of(args[1]), Path.of(args[2]));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void runProbe(String family, Path work, Path result) throws Exception {
    System.setProperty("lizzie.work.dir", work.toString());
    Files.writeString(
        work.resolve("config.txt"),
        "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
            + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":3,"
            + "\"auto-check-engine-alive\":false,\"auto-quick-analyze-on-load\":false,"
            + "\"analysis-engine-preload\":false,\"play-sound\":false,"
            + "\"show-suggestions-frame\":false,\"show-badmoves-frame\":false}}");
    DesktopProbeProcess.phase(result, "application-starting");
    Lizzie.main(new String[0]);
    Leelaz primary = new Leelaz("");
    Leelaz secondary = new Leelaz("");
    edt(
        () -> {
          for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
          Lizzie.engineManager.autoCheckEngineAlive(false);
          Lizzie.setPrimaryEngine(primary);
          Lizzie.leelaz2 = secondary;
          EngineManager.isEmpty = true;
          Lizzie.config.moveListFilterCurrent = false;
          Lizzie.frame.setBounds(30, 30, 1180, 820);
          return null;
        });
    DesktopProbeProcess.applicationReady(result);
    for (boolean kata : new boolean[] {false, true}) {
      for (int engine : new int[] {1, 2}) {
        String name = family + "-" + (kata ? "kata" : "standard") + "-" + engine;
        DesktopProbeProcess.phase(result, name);
        JFrame frame =
            edt(
                () -> {
                  primary.isKatago = kata;
                  secondary.isKatago = kata;
                  return family.equals("analysis")
                      ? checkCandidates(engine, kata, result.resolveSibling(name + ".xls"))
                      : checkMoves(engine, kata, result.resolveSibling(name + ".xls"));
                });
        if (kata) {
          Rectangle screen = edt(() -> frame.getGraphicsConfiguration().getBounds());
          ImageIO.write(
              new Robot().createScreenCapture(screen),
              "png",
              result.resolveSibling(name + ".png").toFile());
        }
        edt(
            () -> {
              frame.dispose();
              return null;
            });
      }
    }
    edt(
        () -> {
          Lizzie.frame.dispose();
          return null;
        });
    Files.writeString(result, "PASS");
  }

  private static AnalysisFrame checkCandidates(int engine, boolean kata, Path export)
      throws Exception {
    CountingHistory history = new CountingHistory(BoardData.empty(19, 19));
    BoardHistoryNode root = history.getCurrentHistoryNode();
    List<MoveData> primary = candidates(0);
    List<MoveData> secondary = candidates(10);
    root.getData().bestMoves = primary;
    root.getData().bestMoves2 = secondary;
    BoardHistoryNode next = append(history, 1, "D4");
    next.getData().bestMoves = List.of(candidate("K10", 0, 300, 100, 45, 0));
    next.getData().bestMoves2 = List.of(candidate("L10", 0, 400, 100, 35, 0));
    next.getData().setPlayoutsForce(2000);
    next.getData().setPlayouts2(3000);
    next.getData().winrate = 45;
    next.getData().winrate2 = 35;
    history.setHead(root);
    history.getGameInfo().setKomi(6.5);
    Lizzie.board.setHistory(history);
    Lizzie.config.anaFrameShowNext = true;
    Lizzie.config.showPreviousBestmovesInEngineGame = false;
    Lizzie.config.showKataGoScoreLeadWithKomi = false;
    AnalysisFrame frame = new AnalysisFrame(engine);
    frame.timer.stop();
    frame.setBounds(80, 80, 780, 420);
    JTable table = frame.table;
    List<MoveData> source = engine == 1 ? primary : secondary;
    int offset = engine == 1 ? 0 : 10;
    assertEquals(kata ? 9 : 7, table.getModel().getColumnCount());
    assertEquals(4, table.getRowCount());
    assertEquals("66.7", table.getValueAt(0, 5), "edge visits and symmetry own allocation");
    assertFalse(source.get(0).isNextMove, "actual-move display must not mutate engine data");
    pureReadsAndExport(history, table, export);
    assertEquals(
        0,
        tableLayoutRequests(table, () -> {
          for (int repeat = 0; repeat < 10; repeat++) tick(frame.timer);
        }),
        "unchanged table dimensions must not request layout on every timer tick");
    Leelaz displayedEngine = engine == 1 ? Lizzie.leelaz : Lizzie.leelaz2;
    displayedEngine.isKatago = !kata;
    assertTrue(tableLayoutRequests(table, () -> tick(frame.timer)) > 0);
    assertEquals(kata ? 7 : 9, table.getModel().getColumnCount());
    displayedEngine.isKatago = kata;
    assertTrue(tableLayoutRequests(table, () -> tick(frame.timer)) > 0);
    assertEquals(kata ? 9 : 7, table.getModel().getColumnCount());

    header(table, 3);
    assertEquals("C3", normalCoordinate(table, 0, 1));
    MoveData first = source.get(0);
    first.winrate = 90;
    first.edgeVisits = 0;
    assertEquals("C3", normalCoordinate(table, 0, 1), "reads must not re-sort live data");
    int actualRow = rowFor(table, 1, Board.maybeConvertNormalCoordsToOther("D4"));
    assertEquals(
        String.format(Locale.ENGLISH, "%.1f", 60.0 + offset), table.getValueAt(actualRow, 3));
    assertEquals("66.7", table.getValueAt(actualRow, 5));
    assertEquals(0, tableLayoutRequests(table, () -> tick(frame.timer)));
    assertEquals("D4", normalCoordinate(table, 0, 1));
    assertEquals("90.0", table.getValueAt(0, 3));
    assertEquals("0.0", table.getValueAt(0, 5));
    first.winrate = 60 + offset;
    first.edgeVisits = 100;
    for (int column : new int[] {0, 1}) {
      header(table, 3);
      assertEquals("C3", normalCoordinate(table, 0, 1));
      assertEquals(0, tableLayoutRequests(table, () -> header(table, column)));
      for (int row = 0; row < source.size(); row++) {
        assertEquals(
            source.get(row).coordinate,
            normalCoordinate(table, row, 1),
            "order and coordinate headers must restore source order without changing layout");
      }
    }
    frame.sortnum = -1;
    tick(frame.timer);

    if (kata) {
      Lizzie.config.showKataGoScoreLeadWithKomi = true;
      tick(frame.timer);
      assertEquals("9.5", table.getValueAt(0, 7));
      Lizzie.engineGame.publishSnapshotForTest(
          EngineGameSnapshotFixtures.playing(EngineGamePlayMode.GENMOVE, RunState.RUNNING));
      tick(frame.timer);
      assertEquals("-3.5", table.getValueAt(0, 7));
      EngineGameSnapshotFixtures.publishIdle();
      Lizzie.config.showKataGoScoreLeadWithKomi = false;
    }

    EngineGameSnapshotFixtures.publishPlaying();
    Lizzie.config.showPreviousBestmovesInEngineGame = true;
    tick(frame.timer);
    assertEquals(
        engine == 1 ? 0 : 4, table.getRowCount(), "root has no previous primary candidates");
    history.setHead(next);
    tick(frame.timer);
    assertEquals(engine == 1 ? 4 : 1, table.getRowCount());
    assertEquals(engine == 1 ? "D4" : "L10", normalCoordinate(table, 0, 1));
    EngineGameSnapshotFixtures.publishIdle();
    Lizzie.config.showPreviousBestmovesInEngineGame = false;
    history.setHead(root);

    // A deeper actual move keeps the legacy visits denominator, even with edgeVisits=0.
    BoardHistoryNode deeper = replaceNext(root, position(1, "Q16"));
    deeper.getData().bestMoves = next.getData().bestMoves;
    deeper.getData().bestMoves2 = next.getData().bestMoves2;
    deeper.getData().setPlayoutsForce(2000);
    deeper.getData().setPlayouts2(3000);
    deeper.getData().winrate = 45;
    deeper.getData().winrate2 = 35;
    assertTrue(tableLayoutRequests(table, () -> tick(frame.timer)) > 0);
    assertEquals(5, table.getRowCount());
    assertEquals("Q16", normalCoordinate(table, 0, 1));
    int visits = engine == 1 ? 2000 : 3000;
    assertEquals(Utils.getPlayoutsString(visits), table.getValueAt(0, 4));
    assertEquals(
        String.format(Locale.ENGLISH, "%.1f", visits * 100.0 / (visits + 150)),
        table.getValueAt(0, 5));
    assertEquals("--", table.getValueAt(0, 2));

    if (kata) {
      for (MoveData move : source) move.scoreMean = +0.0;
      deeper.getData().scoreMean = +0.0;
      deeper.getData().scoreMean2 = +0.0;
      tick(frame.timer);
      header(table, 7);
      assertEquals("Q16", normalCoordinate(table, 0, 1), "signed zero remains a stable tie");
      assertEquals("--", table.getValueAt(0, 2), "the synthetic actual row stays first");
    }

    Lizzie.frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
    click(table, 0, 1);
    assertArrayEquals(Board.convertNameToCoordinates("Q16"), Lizzie.frame.suggestionclick);
    Lizzie.frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
    replaceNext(root, position(1, "R17"));
    click(table, 0, 1);
    assertSame(
        LizzieFrame.outOfBoundCoordinate,
        Lizzie.frame.suggestionclick,
        "changing the next branch also invalidates the displayed candidates");
    assertEquals(4, table.getRowCount());
    root.variations.set(0, deeper);
    root.setPreviousForChild(deeper);
    tick(frame.timer);
    history.setHead(deeper);
    assertTrue(
        tableLayoutRequests(table, () -> click(table, 0, 1)) > 0,
        "a stale click that refreshes fewer rows must still request layout");
    assertSame(
        LizzieFrame.outOfBoundCoordinate,
        Lizzie.frame.suggestionclick,
        "old-position candidates must not act on the newly selected node");
    assertEquals(1, table.getRowCount(), "stale input refreshes the current position");
    Lizzie.config.showBestMovesList = false;
    AnalysisFrame graphOnly = new AnalysisFrame(engine);
    graphOnly.timer.stop();
    history.edtReads = 0;
    tick(graphOnly.timer);
    assertEquals(0, history.edtReads, "graph-only ticks do not prepare hidden candidates");
    graphOnly.dispose();
    Lizzie.config.showBestMovesList = true;
    return frame;
  }

  private static MoveListFrame checkMoves(int engine, boolean kata, Path export) throws Exception {
    MoveFixture fixture = moveFixture();
    CountingHistory history = fixture.history;
    Lizzie.board.setHistory(history);
    Lizzie.config.moveListSelectedBranch = 0;
    Lizzie.config.movelistSelectedIndexTop = 2;
    Lizzie.config.isShowingMoveList = true;
    Lizzie.config.saveThreshold(0, 0, 0, false);
    MoveListFrame frame = new MoveListFrame(engine);
    frame.timer.stop();
    frame.setBounds(80, 80, 1050, 650);
    frame.setVisible(true);
    JTable table = frame.table;
    JTable black = (JTable) frame.minScrollpane1.getViewport().getView();
    JTable white = (JTable) frame.minScrollpane2.getViewport().getView();
    assertEquals(kata ? 12 : 9, table.getColumnCount());
    assertEquals(engine == 1 ? 4 : 5, table.getRowCount());
    assertEquals(3, black.getRowCount());
    assertEquals(engine == 1 ? 1 : 2, white.getRowCount());
    assertEquals(
        engine == 1 ? "-8.00↓" : "-18.00↓",
        white.getValueAt(0, 2),
        "the white mini table must use its own engine slot");
    pureReadsAndExport(history, table, export);
    pureReadsAndExport(history, black, export.resolveSibling("black-" + export.getFileName()));
    pureReadsAndExport(history, white, export.resolveSibling("white-" + export.getFileName()));

    NodeInfo changed =
        engine == 1 ? fixture.main.get(4).nodeInfoMain : fixture.main.get(4).nodeInfoMain2;
    int oldCoordinate = changed.coords[0];
    Object oldDiff = table.getValueAt(0, 3);
    String oldCoords = table.getValueAt(0, 2).toString();
    changed.diffWinrate = -0.5;
    changed.coords[0] = 18;
    assertEquals(oldDiff, table.getValueAt(0, 3));
    assertEquals(oldCoords, table.getValueAt(0, 2), "prepared coordinates are detached too");
    tick(frame.timer);
    assertNotEquals(5, table.getValueAt(0, 1), "the timer re-sorts changed analysis");
    changed.coords[0] = oldCoordinate;
    changed.diffWinrate = -(10 * (engine - 1) + 10);
    tick(frame.timer);

    frame.checkBlack.doClick(0);
    assertEquals(engine == 1 ? 1 : 2, table.getRowCount());
    assertEquals(3, black.getRowCount(), "full-table color filter does not hide the mini table");
    frame.checkBlack.doClick(0);
    frame.showBranch.setSelectedIndex(1);
    assertEquals(engine == 1 ? 3 : 4, table.getRowCount());
    assertEquals(engine == 1 ? "-28.00↓" : "-38.00↓", table.getValueAt(0, 3));
    frame.showBranch.setSelectedIndex(0);

    // The existing three-state loss ordering and shared mini-header ordering remain live.
    header(table, 3);
    assertEquals(1, table.getValueAt(0, 1));
    header(table, 3);
    assertEquals(5, table.getValueAt(0, 1));
    header(table, 3);
    assertEquals(5, table.getValueAt(0, 1));
    if (kata) {
      // Use mixed signed values so the assertion cannot accidentally pass on move order.
      List<NodeInfo> scoreRows =
          fixture.main.stream()
              .map(node -> engine == 1 ? node.nodeInfoMain : node.nodeInfoMain2)
              .toList();
      double[] leads = {0, -7, 10, 3, -5};
      double[] losses = {-5, -3, -2, -10, -4};
      for (int i = 0; i < scoreRows.size(); i++) {
        scoreRows.get(i).scoreLead = leads[i];
        scoreRows.get(i).scoreMeanDiff = losses[i];
      }
      tick(frame.timer);
      header(table, 8);
      assertNumericOrder(table, 8, true);
      header(table, 8);
      assertNumericOrder(table, 8, false);
      // Keep the later filter/threshold scenarios on their original fixture.
      for (BoardHistoryNode node : fixture.main) fillNode(node, node.getData().moveNumber != 2);
      tick(frame.timer);
    }
    header(table, 0);
    header(table, 0);
    assertEquals(
        List.of(5, 3, 1),
        moveNumbers(table, 1).stream().filter(number -> number % 2 == 1).toList(),
        "color ties keep the source order");
    int sort = frame.sortnum;
    frame.topPanel.setSelectedIndex(0);
    history.edtReads = 0;
    tick(frame.timer);
    assertEquals(0, history.edtReads, "statistics-only ticks do not prepare hidden tables");
    frame.topPanel.setSelectedIndex(2);
    assertEquals(sort, frame.sortnum, "statistics round trips retain the selected sort");
    Object beforeHide = table.getValueAt(rowFor(table, 1, 5), 3);
    frame.hideMove.doClick(0);
    assertFalse(frame.topPanel.isVisible());
    changed.diffWinrate = -0.5;
    history.edtReads = 0;
    tick(frame.timer);
    assertEquals(0, history.edtReads, "collapsed-list ticks do not prepare hidden rows");
    assertEquals(beforeHide, table.getValueAt(rowFor(table, 1, 5), 3));
    frame.hideMove.doClick(0);
    assertTrue(frame.topPanel.isVisible());
    assertEquals(
        "-0.50↓",
        table.getValueAt(rowFor(table, 1, 5), 3),
        "expanding the list refreshes before the next timer tick");
    changed.diffWinrate = -(10 * (engine - 1) + 10);
    tick(frame.timer);
    frame.topPanel.setSelectedIndex(1);
    header(black, 0);
    List<Integer> blackOrder = moveNumbers(black, 0);
    List<Integer> whiteOrder = moveNumbers(white, 0);
    header(white, 0);
    assertEquals(reversed(blackOrder), moveNumbers(black, 0));
    assertEquals(reversed(whiteOrder), moveNumbers(white, 0));
    frame.topPanel.setSelectedIndex(2);

    Lizzie.config.saveThreshold(99, 0, 0, false);
    assertTrue(table.getRowCount() > 0, "threshold changes use the existing timer boundary");
    tick(frame.timer);
    assertEquals(0, table.getRowCount());
    assertEquals(0, white.getRowCount());
    Lizzie.config.saveThreshold(0, 0, 2000, false);
    tick(frame.timer);
    assertEquals(0, table.getRowCount());
    Lizzie.config.saveThreshold(0, 99, 0, false);
    tick(frame.timer);
    assertEquals(kata ? 0 : engine == 1 ? 4 : 5, table.getRowCount());
    Lizzie.config.saveThreshold(0, 0, 0, false);
    tick(frame.timer);

    if (engine == 2) {
      frame.showBranch.setSelectedIndex(1);
      BoardHistoryNode previous = history.getCurrentHistoryNode().previous().orElseThrow();
      previous.nodeInfo.analyzed = false;
      previous.nodeInfo2.analyzed = true;
      Lizzie.config.saveThreshold(99, 99, 9999, true);
      tick(frame.timer);
      assertEquals(1, table.getRowCount(), "pinned row count uses the comparison engine");
      assertEquals(3, table.getValueAt(0, 1));
      Component renderer = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
      assertEquals(new Color(220, 220, 220), renderer.getBackground());
      Lizzie.config.saveThreshold(0, 0, 0, false);
      frame.showBranch.setSelectedIndex(0);
    }

    // A branch promotion changes the main path without changing the current node.
    history.setHead(fixture.branch.get(1));
    tick(frame.timer);
    BoardHistoryNode before = history.getCurrentHistoryNode();
    fixture.branch.get(0).moveUp();
    click(table, 0, 1);
    assertSame(
        before,
        history.getCurrentHistoryNode(),
        "an old main-path row must not navigate the new main path");
    fixture.branch.get(0).moveDown();
    tick(frame.timer);
    int move = (int) table.getValueAt(0, 1);
    click(table, 0, 1);
    assertEquals(move - 1, history.getData().moveNumber, "click follows the displayed sorted row");
    tick(frame.timer);
    history.setHead(fixture.branch.get(1));
    click(table, 0, 1);
    assertSame(
        fixture.branch.get(1),
        history.getCurrentHistoryNode(),
        "stale node context must not navigate");
    frame.topPanel.setSelectedIndex(1);
    for (JTable mini : List.of(black, white)) {
      tick(frame.timer);
      int displayedMove = (int) mini.getValueAt(0, 0);
      click(mini, 0, 0);
      assertEquals(
          displayedMove - 1,
          history.getData().moveNumber,
          "mini-table clicks follow their own prepared sorted rows");
      tick(frame.timer);
      history.setHead(fixture.branch.get(1));
      click(mini, 0, 0);
      assertSame(
          fixture.branch.get(1),
          history.getCurrentHistoryNode(),
          "mini-table clicks also reject a stale node context");
    }
    return frame;
  }

  private static void pureReadsAndExport(CountingHistory history, JTable table, Path file)
      throws Exception {
    assertTrue(SwingUtilities.isEventDispatchThread(), "table reads are measured on the EDT");
    history.edtReads = 0;
    for (int repeat = 0; repeat < 3; repeat++) {
      for (int row = 0; row < table.getRowCount(); row++) {
        for (int column = 0; column < table.getModel().getColumnCount(); column++) {
          assertNotNull(table.getModel().getValueAt(row, column));
        }
      }
    }
    Utils.exportTable(table, file.toString());
    assertEquals(0, history.edtReads, "table reads re-traversed history outside a refresh");
    assertEquals(
        table.getRowCount() + 1, Files.readString(file, StandardCharsets.UTF_16).lines().count());
  }

  private static void assertNumericOrder(JTable table, int column, boolean ascending) {
    List<Double> displayed = new ArrayList<>();
    for (int row = 0; row < table.getRowCount(); row++) {
      displayed.add(Double.parseDouble(table.getValueAt(row, column).toString()));
    }
    List<Double> expected = new ArrayList<>(displayed);
    expected.sort(ascending ? Double::compare : java.util.Comparator.reverseOrder());
    assertEquals(expected, displayed, "header must sort the displayed score in column " + column);
  }

  private static void tick(Timer timer) {
    for (var listener : timer.getActionListeners()) {
      listener.actionPerformed(new ActionEvent(timer, ActionEvent.ACTION_PERFORMED, "refresh"));
    }
  }

  private static int tableLayoutRequests(JTable table, Runnable operation) {
    assertTrue(SwingUtilities.isEventDispatchThread());
    RepaintManager previous = RepaintManager.currentManager(table);
    int[] requests = {0};
    RepaintManager.setCurrentManager(new RepaintManager() {
      @Override
      public synchronized void addInvalidComponent(JComponent component) {
        if (component == table) requests[0]++;
        super.addInvalidComponent(component);
      }
    });
    try {
      operation.run();
      return requests[0];
    } finally {
      RepaintManager.setCurrentManager(previous);
    }
  }

  private static void header(JTable table, int column) {
    JTableHeader header = table.getTableHeader();
    Rectangle cell = header.getHeaderRect(column);
    header.dispatchEvent(
        new MouseEvent(
            header,
            MouseEvent.MOUSE_RELEASED,
            0,
            0,
            cell.x + cell.width / 2,
            5,
            1,
            false,
            MouseEvent.BUTTON1));
  }

  private static void click(JTable table, int row, int column) {
    Rectangle cell = table.getCellRect(row, column, true);
    table.dispatchEvent(
        new MouseEvent(
            table,
            MouseEvent.MOUSE_CLICKED,
            0,
            0,
            cell.x + cell.width / 2,
            cell.y + cell.height / 2,
            1,
            false,
            MouseEvent.BUTTON1));
  }

  private static int rowFor(JTable table, int column, Object value) {
    for (int row = 0; row < table.getRowCount(); row++) {
      if (value.equals(table.getValueAt(row, column))) return row;
    }
    throw new AssertionError("Missing row " + value);
  }

  private static String normalCoordinate(JTable table, int row, int column) {
    return Board.maybeConvertOtherCoordsToNormal(table.getValueAt(row, column).toString());
  }

  private static List<Integer> moveNumbers(JTable table, int column) {
    List<Integer> result = new ArrayList<>();
    for (int row = 0; row < table.getRowCount(); row++)
      result.add((Integer) table.getValueAt(row, column));
    return result;
  }

  private static List<Integer> reversed(List<Integer> values) {
    List<Integer> copy = new ArrayList<>(values);
    java.util.Collections.reverse(copy);
    return copy;
  }

  private static List<MoveData> candidates(int offset) {
    MoveData symmetry = candidate("E5", 3, 200, 50, 54 + offset, 1);
    symmetry.isSymmetry = true;
    return List.of(
        candidate("D4", 0, 400, 100, 60 + offset, 3),
        candidate("Q16", 1, 800, 0, 55 + offset, 2),
        candidate("C3", 2, 200, 50, 65 + offset, 4),
        symmetry);
  }

  private static MoveData candidate(
      String coordinate, int order, int visits, int edgeVisits, double winrate, double score) {
    MoveData move = new MoveData();
    move.coordinate = coordinate;
    move.order = order;
    move.playouts = visits;
    move.edgeVisits = edgeVisits;
    move.winrate = winrate;
    move.lcb = winrate - 1;
    move.scoreMean = score;
    move.policy = 10;
    move.variation = List.of(coordinate);
    return move;
  }

  private record MoveFixture(
      CountingHistory history, List<BoardHistoryNode> main, List<BoardHistoryNode> branch) {}

  private static MoveFixture moveFixture() {
    CountingHistory history = new CountingHistory(BoardData.empty(19, 19));
    List<BoardHistoryNode> main = new ArrayList<>();
    for (int i = 1; i <= 5; i++)
      main.add(append(history, i, Board.convertCoordinatesToName(i + 2, 15)));
    BoardHistoryNode fork = main.get(1);
    BoardHistoryNode first = new BoardHistoryNode(position(3, "F5"));
    fork.variations.add(first);
    fork.setPreviousForChild(first);
    history.setHead(first);
    BoardHistoryNode second = append(history, 4, "G5");
    for (BoardHistoryNode node : main) fillNode(node, node.getData().moveNumber != 2);
    fillNode(first, true);
    fillNode(second, true);
    return new MoveFixture(history, main, List.of(first, second));
  }

  private static void fillNode(BoardHistoryNode node, boolean primaryAnalyzed) {
    fill(node.nodeInfoMain, node, 0, primaryAnalyzed);
    fill(node.nodeInfoMain2, node, 10, true);
    fill(node.nodeInfo, node, 20, primaryAnalyzed);
    fill(node.nodeInfo2, node, 30, true);
  }

  private static void fill(NodeInfo info, BoardHistoryNode node, int offset, boolean analyzed) {
    int number = node.getData().moveNumber;
    info.analyzed = analyzed;
    info.coords = node.getData().lastMove.orElseThrow().clone();
    info.moveNum = number;
    info.isBlack = number % 2 == 1;
    info.winrate = 60 + offset + number;
    info.diffWinrate = -(offset + number * 2);
    info.scoreMeanDiff = -(offset + number);
    info.scoreLead = number * 3;
    info.playouts = 1000;
    info.previousPlayouts = 900;
    info.percentsMatch = number / 10.0;
  }

  private static BoardHistoryNode append(BoardHistoryList history, int number, String coordinate) {
    history.add(position(number, coordinate));
    return history.getCurrentHistoryNode();
  }

  private static BoardHistoryNode replaceNext(BoardHistoryNode root, BoardData data) {
    BoardHistoryNode next = new BoardHistoryNode(data);
    root.variations.clear();
    root.variations.add(next);
    root.setPreviousForChild(next);
    return next;
  }

  private static BoardData position(int number, String coordinate) {
    BoardData empty = BoardData.empty(19, 19);
    int[] coords = Board.convertNameToCoordinates(coordinate);
    Stone stone = number % 2 == 1 ? Stone.BLACK : Stone.WHITE;
    empty.stones[Board.getIndex(coords[0], coords[1])] = stone;
    return BoardData.move(
        empty.stones,
        coords,
        stone,
        number % 2 == 0,
        empty.zobrist,
        number,
        empty.moveNumberList,
        0,
        0,
        50,
        100);
  }

  private static final class CountingHistory extends BoardHistoryList {
    int edtReads;

    CountingHistory(BoardData data) {
      super(data);
    }

    private void recordRead() {
      // The real application's scheduled title/move-list updater also reads this history.
      // Only EDT reads belong to the table operations and timer callbacks under test.
      if (SwingUtilities.isEventDispatchThread()) edtReads++;
    }

    @Override
    public BoardHistoryNode getCurrentHistoryNode() {
      recordRead();
      return super.getCurrentHistoryNode();
    }

    @Override
    public BoardHistoryNode getMainEnd() {
      recordRead();
      return super.getMainEnd();
    }

    @Override
    public BoardHistoryNode getEnd() {
      recordRead();
      return super.getEnd();
    }

    @Override
    public BoardHistoryNode getStart() {
      recordRead();
      return super.getStart();
    }

    @Override
    public BoardData getData() {
      recordRead();
      return super.getData();
    }
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(20, TimeUnit.SECONDS);
  }
}
