package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.SgfSaveCoordinator;
import featurecat.lizzie.gui.SidebarPanel;
import featurecat.lizzie.gui.TempGameData;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Real file/saved-list entrypoints and SGF I/O; only native painting/analysis are stubbed. */
@Isolated("Uses shared Lizzie state and observes the queued open-failure dialog")
class SavedGameLoadSafetyTest {
  private static final String ORIGINAL_SGF =
      "(;SZ[5]KM[6.5]PB[original black]PW[original white]RE[B+2.5]C[original root]"
          + ";B[aa](;W[bb]C[original selected];B[cc]C[original leaf])"
          + "(;W[ee]C[original branch]))";
  private static final String SAVED_SGF =
      "(;SZ[5]KM[7.5]PB[saved black]PW[saved white]C[saved root]"
          + ";B[dd](;W[cc];B[ee])(;W[ba]C[saved branch];B[ab]))";

  @TempDir Path directory;
  private RulesLayerTestHarness rules;
  private SavedListFrame frame;
  private Config config;
  private JPanel list;
  private JLayeredPane listContainer;
  private JPopupMenu preview;
  private File originalFile;
  private String originalPlayers;
  private String originalResult;
  private ResourceBundle previousResources;
  private EngineManager previousManager;
  private Leelaz previousSecondary;
  private File previousFile;
  private Thread.UncaughtExceptionHandler previousExceptionHandler;
  private final List<Throwable> asynchronousFailures = new CopyOnWriteArrayList<>();
  private final AtomicInteger errorDialogs = new AtomicInteger();
  private final CountDownLatch errorDialogAttempted = new CountDownLatch(1);
  private AWTEventListener dialogResponder;
  private ExactSnapshotRestoreProtocolFixture.Transport transport;
  private final List<String> synchronizedSnapshots = new CopyOnWriteArrayList<>();

  enum EntryPoint { BUTTON, THUMBNAIL, PREVIEW }

  enum FileOpenEntry { DROP, STARTUP }

  enum SavedPosition {
    BRANCH(2, "1,0,w_3,3,b"),
    MOVE_NUMBER(1, "");

    final int moveNumber;
    final String moves;

    SavedPosition(int moveNumber, String moves) {
      this.moveNumber = moveNumber;
      this.moves = moves;
    }
  }

  enum Slot {
    MANUAL(false, "game1"),
    AUTO(true, "autoGame1");

    final boolean automatic;
    final String filename;

    Slot(boolean automatic, String filename) {
      this.automatic = automatic;
      this.filename = filename;
    }
  }

  static Stream<Arguments> failedOpens() {
    return Arrays.stream(EntryPoint.values()).flatMap(entry ->
        Arrays.stream(SavedPosition.values()).flatMap(position ->
            Arrays.stream(Slot.values()).flatMap(slot ->
                Stream.of("missing", "malformed")
                    .map(input -> Arguments.of(entry, position, slot, input)))));
  }

  static Stream<Arguments> successfulOpens() {
    return Arrays.stream(EntryPoint.values()).flatMap(entry ->
        Arrays.stream(SavedPosition.values()).flatMap(position ->
            Arrays.stream(Slot.values()).flatMap(slot ->
                Stream.of(false, true)
                    .map(loadLast -> Arguments.of(entry, position, slot, loadLast)))));
  }

  static Stream<Arguments> failedExternalOpens() {
    return Arrays.stream(FileOpenEntry.values()).flatMap(entry ->
        Stream.of("missing", "malformed").map(input -> Arguments.of(entry, input)));
  }

  @BeforeEach
  void prepare() throws Exception {
    previousResources = Lizzie.resourceBundle;
    previousManager = Lizzie.engineManager;
    previousSecondary = Lizzie.leelaz2;
    previousFile = LizzieFrame.curFile;
    previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
      if (thread.getName().startsWith("AWT-EventQueue")
          && GraphicsEnvironment.isHeadless()
          && failure instanceof HeadlessException
          && Arrays.stream(failure.getStackTrace()).anyMatch(element ->
              element.getClassName().equals(JOptionPane.class.getName())
                  && element.getMethodName().equals("showMessageDialog"))) {
        errorDialogs.incrementAndGet();
        errorDialogAttempted.countDown();
      } else {
        asynchronousFailures.add(failure);
      }
    });
    if (!GraphicsEnvironment.isHeadless()) {
      dialogResponder = event -> {
        if (event.getID() != WindowEvent.WINDOW_OPENED
            || !(event.getSource() instanceof JDialog dialog)) return;
        JOptionPane pane = findOptionPane(dialog);
        if (pane == null || !Lizzie.resourceBundle
            .getString("LizzieFrame.prompt.failedToOpenFile").equals(pane.getMessage())) return;
        errorDialogs.incrementAndGet();
        errorDialogAttempted.countDown();
        pane.setValue(JOptionPane.OK_OPTION);
      };
      Toolkit.getDefaultToolkit().addAWTEventListener(dialogResponder, AWTEvent.WINDOW_EVENT_MASK);
    }
    rules = RulesLayerTestHarness.open();
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.ENGLISH);
    Lizzie.engineManager = null;
    Lizzie.leelaz2 = null;
    config = ConfigTestHelper.createForTests(directory);
    config.persisted = new JSONObject().put("filesystem", new JSONObject());
    config.uiConfig = new JSONObject();
    config.saveBoardConfig = new JSONObject();
    config.playSound = true;
    config.readKomi = true;
    config.showComment = true;
    config.showListPane = true;
    config.showVariationGraph = true;
    config.initialMaxScoreLead = 10;
    config.autoQuickAnalyzeOnLoad = false;
    Lizzie.config = config;
    Files.createDirectories(directory.resolve("save"));
    originalFile = directory.resolve("current.sgf").toFile();
    Files.writeString(originalFile.toPath(), ORIGINAL_SGF);
    frame = RulesLayerTestHarness.allocate(SavedListFrame.class);
    frame.resumeScheduled = new CountDownLatch(1);
    Lizzie.frame = frame;
    SwingUtilities.invokeAndWait(() -> {
      QuietMenu menu = RulesLayerTestHarness.allocate(QuietMenu.class);
      menu.txtKomi = new JTextField();
      LizzieFrame.menu = menu;
      frame.mainPanel = new JPanel();
      frame.mainPanel.setSize(1024, 768);
      frame.commentScrollPane = new JScrollPane();
      frame.commentEditPane = new JScrollPane();
      frame.blunderContentPane = new JPanel();
      frame.sidebarPanel = RulesLayerTestHarness.allocate(QuietSidebar.class);
      setFrameField("varTreeScrollPane", new JScrollPane());
      frame.listScrollpane = new JScrollPane();
      list = new JPanel();
      listContainer = new JLayeredPane();
      listContainer.setVisible(false);
      setFrameField("tempGamePanel", list);
      setFrameField("tempGamePanelAll", listContainer);
      setFrameField("tempGamePanelTop", new JPanel());
      setFrameField("tempGameScrollPanel", new JScrollPane(list));
      Lizzie.board.setHistory(SGFParser.parseSgf(ORIGINAL_SGF, true));
      BoardHistoryList history = Lizzie.board.getHistory();
      history.setHead(history.getStart().next().orElseThrow().next().orElseThrow());
      frame.setPlayers("original white", "original black");
      frame.setResult("B+2.5");
      originalPlayers = frame.playerTitle;
      try {
        originalResult = (String) getFrameField("resultTitle");
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
      LizzieFrame.curFile = originalFile;
      LizzieFrame.fileNameTitle = originalFile.getName();
      LizzieFrame.canGoAfterload = true;
    });
  }

  @AfterEach
  void restore() throws Exception {
    try {
      if (frame != null) {
        SwingUtilities.invokeAndWait(() -> {});
        Object coordinator = getFrameField("kifuEngineSyncCoordinator");
        ExecutorService worker = null;
        if (coordinator != null) {
          Field field = coordinator.getClass().getDeclaredField("executor");
          field.setAccessible(true);
          worker = (ExecutorService) field.get(coordinator);
        }
        SwingUtilities.invokeAndWait(() -> {
          if (preview != null) preview.setVisible(false);
          frame.shutdownKifuEngineSyncCoordinator();
        });
        if (worker != null) assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {});
      }
    } finally {
      try {
        if (transport != null) transport.close();
      } finally {
        if (dialogResponder != null) Toolkit.getDefaultToolkit().removeAWTEventListener(dialogResponder);
        if (rules != null) rules.close();
        Lizzie.resourceBundle = previousResources;
        Lizzie.engineManager = previousManager;
        Lizzie.leelaz2 = previousSecondary;
        LizzieFrame.curFile = previousFile;
        Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler);
      }
    }
    if (!asynchronousFailures.isEmpty()) {
      throw new AssertionError("Unexpected asynchronous saved-list failure", asynchronousFailures.get(0));
    }
  }

  @ParameterizedTest(name = "{0}, {1}, {2}, {3}")
  @MethodSource("failedOpens")
  void failedSavedItemOpenPreservesCurrentGameAndKeepsListOpen(
      EntryPoint entry, SavedPosition position, Slot slot, String input) throws Exception {
    prepareSavedItem(position, slot);
    if (input.equals("malformed")) Files.writeString(savedFile(slot), "not an SGF game tree");
    BoardHistoryList history = Lizzie.board.getHistory();
    BoardHistoryNode selected = history.getCurrentHistoryNode();
    Stone[] stones = history.getStones().clone();
    String originalTree = SGFParser.saveToString(false);
    String originalMetadata = config.saveBoardConfig.toString();

    SwingUtilities.invokeAndWait(() -> {
      click(entry, position, slot);
      // The parser and listener have run. The fixture has no native JFrame owner for the
      // queued failure notice; its real JOptionPane still runs on the next EDT turn.
      Lizzie.frame = null;
    });
    assertTrue(errorDialogAttempted.await(5, TimeUnit.SECONDS), "the load failure must be reported");
    SwingUtilities.invokeAndWait(() -> Lizzie.frame = frame);

    assertAll(
        () -> assertSame(history, Lizzie.board.getHistory()),
        () -> assertSame(selected, Lizzie.board.getHistory().getCurrentHistoryNode()),
        () -> assertArrayEquals(stones, Lizzie.board.getHistory().getStones()),
        () -> assertEquals(originalTree, SGFParser.saveToString(false), "all branches/comments survive"),
        () -> assertEquals("original selected", Lizzie.board.getData().comment),
        () -> assertSame(originalFile, LizzieFrame.curFile),
        () -> assertEquals(originalFile.getName(), LizzieFrame.fileNameTitle),
        () -> assertEquals(originalPlayers, frame.playerTitle),
        () -> assertEquals(originalResult, getFrameField("resultTitle")),
        () -> assertEquals(ORIGINAL_SGF, Files.readString(originalFile.toPath())),
        () -> assertEquals(originalMetadata, config.saveBoardConfig.toString()),
        () -> assertEquals(0, frame.listCloses),
        () -> assertTrue(listContainer.isVisible()),
        () -> assertTrue((Boolean) getFrameField("canShowBigBoardImage")),
        () -> assertFalse(config.showListPane),
        () -> assertFalse(config.showVariationGraph),
        () -> assertTrue(config.playSound),
        () -> assertTrue(LizzieFrame.canGoAfterload),
        () -> assertFalse(Lizzie.board.isLoadingFile),
        () -> assertEquals(1, errorDialogs.get()),
        () -> assertNull(getFrameField("kifuEngineSyncCoordinator")),
        () -> assertEquals(1, frame.resumeScheduled.getCount()));
    if (entry == EntryPoint.PREVIEW) assertTrue(preview.isVisible());
  }

  @ParameterizedTest(name = "{0}, {1}, {2}, loadLast={3}")
  @MethodSource("successfulOpens")
  void successfulSavedItemOpenRestoresSavedPositionBeforeResuming(
      EntryPoint entry, SavedPosition position, Slot slot, boolean loadLast) throws Exception {
    prepareSavedItem(position, slot);
    Files.writeString(savedFile(slot), SAVED_SGF);
    config.loadSgfLast = loadLast;
    installAcknowledgingEngine();
    BoardHistoryList previous = Lizzie.board.getHistory();
    SwingUtilities.invokeAndWait(() -> click(entry, position, slot));
    assertTrue(frame.resumeScheduled.await(5, TimeUnit.SECONDS), "the real load must reach engine sync");
    SwingUtilities.invokeAndWait(() -> {});
    transport.awaitResponses();

    BoardHistoryList loaded = Lizzie.board.getHistory();
    BoardHistoryNode first = loaded.getStart().next().orElseThrow();
    BoardHistoryNode expected = position == SavedPosition.BRANCH
        ? first.getVariation(1).orElseThrow() : first;
    assertAll(
        () -> assertNotSame(previous, loaded),
        () -> assertSame(expected, loaded.getCurrentHistoryNode()),
        () -> assertSame(expected, frame.nodeWhenListClosed),
        () -> assertSame(expected, frame.nodeWhenResumeScheduled),
        () -> assertEquals(position.moveNumber, Lizzie.board.getCurrentMovenumber()),
        () -> assertEquals(2, first.numberOfChildren(), "restoring a branch must not duplicate moves"),
        () -> assertEquals(Stone.BLACK, rules.stoneAt(3, 3)),
        () -> assertEquals(position == SavedPosition.BRANCH ? Stone.WHITE : Stone.EMPTY, rules.stoneAt(1, 0)),
        () -> assertEquals(Stone.EMPTY, rules.stoneAt(0, 0), "the previous game must be replaced"),
        () -> assertEquals("saved root", loaded.getStart().getData().comment),
        () -> assertEquals(slot.filename + ".sgf", LizzieFrame.fileNameTitle),
        () -> assertEquals(1, frame.listCloses),
        () -> assertFalse(listContainer.isVisible()),
        () -> assertFalse((Boolean) getFrameField("canShowBigBoardImage")),
        () -> assertTrue(config.showListPane),
        () -> assertTrue(config.showVariationGraph),
        () -> assertTrue(config.playSound),
        () -> assertFalse(Lizzie.board.isLoadingFile),
        () -> assertEquals(0, errorDialogs.get()));
    assertEquals(1, synchronizedSnapshots.size(), "exact restore must load one history anchor");
    BoardHistoryList engineSnapshot = SGFParser.parseSgf(synchronizedSnapshots.get(0), true);
    assertArrayEquals(loaded.getStart().getData().stones, engineSnapshot.getStart().getData().stones);
    assertEquals(loaded.getStart().getData().blackToPlay, engineSnapshot.getStart().getData().blackToPlay);
    // The acknowledged restore loads its immutable history anchor, then the path to the cursor.
    List<String> commands = transport.commands();
    int anchor = 0;
    while (!commands.get(anchor).startsWith("loadsgf ")) anchor++;
    List<String> restoredMoves = commands.subList(anchor + 1, commands.size()).stream()
        .filter(command -> command.startsWith("play ")).toList();
    assertEquals(position == SavedPosition.BRANCH
        ? List.of("play B D2", "play W B5") : List.of("play B D2"), restoredMoves);
    if (entry == EntryPoint.PREVIEW) assertFalse(preview.isVisible());
  }

  @Test
  void queuedSavedItemCompletionCannotChangeAReplacementGame() throws Exception {
    prepareSavedItem(SavedPosition.BRANCH, Slot.MANUAL);
    Files.writeString(savedFile(Slot.MANUAL), SAVED_SGF);
    BoardHistoryList replacement = SGFParser.parseSgf("(;SZ[5]C[newer game];B[ee];W[de])", true);
    replacement.setHead(replacement.getEnd());
    BoardHistoryNode selected = replacement.getCurrentHistoryNode();
    Stone[] stones = replacement.getStones().clone();

    SwingUtilities.invokeAndWait(() -> {
      click(EntryPoint.BUTTON, SavedPosition.BRANCH, Slot.MANUAL);
      assertNotSame(replacement, Lizzie.board.getHistory(), "the saved file must actually parse first");
      Lizzie.board.setHistory(replacement);
      LizzieFrame.fileNameTitle = "newer.sgf";
    });
    SwingUtilities.invokeAndWait(() -> {});
    SwingUtilities.invokeAndWait(() -> {});

    assertAll(
        () -> assertSame(replacement, Lizzie.board.getHistory()),
        () -> assertSame(selected, replacement.getCurrentHistoryNode()),
        () -> assertArrayEquals(stones, replacement.getStones()),
        () -> assertEquals("newer.sgf", LizzieFrame.fileNameTitle),
        () -> assertEquals(0, frame.listCloses),
        () -> assertTrue(listContainer.isVisible()),
        () -> assertTrue((Boolean) getFrameField("canShowBigBoardImage")),
        () -> assertNull(getFrameField("kifuEngineSyncCoordinator")),
        () -> assertEquals(0, errorDialogs.get()));
  }

  @ParameterizedTest(name = "{0}, {1}")
  @MethodSource("failedExternalOpens")
  void rejectedExternalFileCannotBecomeOriginalSaveTarget(FileOpenEntry entry, String input)
      throws Exception {
    Path rejected = directory.resolve("rejected.sgf");
    String rejectedContent = "not an SGF game tree; preserve this file";
    if (input.equals("malformed")) Files.writeString(rejected, rejectedContent);
    BoardHistoryList current = Lizzie.board.getHistory();
    SwingUtilities.invokeAndWait(() -> {
      Lizzie.board.getData().comment = "unsaved current game";
      openExternalFile(entry, rejected.toFile());
      Lizzie.frame = null;
    });
    assertTrue(errorDialogAttempted.await(5, TimeUnit.SECONDS), "the load failure must be reported");
    SwingUtilities.invokeAndWait(() -> {
      Lizzie.frame = frame;
      assertSame(current, Lizzie.board.getHistory(), "a failed open must keep the current game");
      config.showReplaceFileHint = false;
      frame.saveOriFile();
    });
    SgfSaveCoordinator.pendingSaves().get(5, TimeUnit.SECONDS);

    assertAll(
        () -> assertEquals(originalFile, LizzieFrame.curFile),
        () -> assertTrue(Files.readString(originalFile.toPath()).contains("unsaved current game"),
            "save original must write the retained game to its original file"),
        () -> {
          if (input.equals("malformed")) assertEquals(rejectedContent, Files.readString(rejected));
          else assertFalse(Files.exists(rejected), "a rejected missing file must not be created");
        },
        () -> assertEquals(1, errorDialogs.get()));
  }

  @ParameterizedTest
  @EnumSource(FileOpenEntry.class)
  void successfulExternalFileBecomesOriginalSaveTarget(FileOpenEntry entry) throws Exception {
    File opened = directory.resolve("opened.sgf").toFile();
    Files.writeString(opened.toPath(), SAVED_SGF);
    SwingUtilities.invokeAndWait(() -> openExternalFile(entry, opened));
    SwingUtilities.invokeAndWait(() -> {
      assertEquals(opened, LizzieFrame.curFile);
      assertEquals("saved root", Lizzie.board.getHistory().getStart().getData().comment);
      Lizzie.board.getHistory().getStart().getData().comment = "edited opened game";
      config.showReplaceFileHint = false;
      frame.saveOriFile();
    });
    SgfSaveCoordinator.pendingSaves().get(5, TimeUnit.SECONDS);

    assertAll(
        () -> assertEquals(opened, LizzieFrame.curFile),
        () -> assertTrue(Files.readString(opened.toPath()).contains("edited opened game")),
        () -> assertEquals(ORIGINAL_SGF, Files.readString(originalFile.toPath())),
        () -> assertEquals(0, errorDialogs.get()));
  }

  @Test
  void explicitStartupFileStillWinsAfterRecoveringThePreviousSession() throws Exception {
    String previousExit = "(;SZ[5]C[OLD_EXIT];B[aa])";
    String previousPeriodic = "(;SZ[5]C[NEW_PERIODIC];B[aa];W[bb])";
    Path exitSlot = directory.resolve("save/autoGame1.sgf");
    Path periodicSlot = directory.resolve("save/autoGame2.sgf");
    Files.writeString(exitSlot, previousExit);
    Files.writeString(periodicSlot, previousPeriodic);
    config.autoResume = true;
    config.saveBoardConfig = new JSONObject()
        .put("save-auto-game-index1", 1)
        .put("save-auto-game-index2", -5)
        .put("save-auto-game-time1", "old time")
        .put("save-auto-game-time2", "new time")
        .put("save-auto-game-move-number1", 1)
        .put("save-auto-game-move-number2", 2)
        .put("save-auto-game-move-list1", "old cursor")
        .put("save-auto-game-move-list2", "new cursor");
    config.saveBoard = new JSONObject().put("save", config.saveBoardConfig).put("other", "keep");
    config.saveTempBoard();
    Path requested = directory.resolve("requested.sgf");
    Files.writeString(requested, SAVED_SGF);

    SwingUtilities.invokeAndWait(() -> frame.restoreStartupGame(new String[] {requested.toString()}));
    SwingUtilities.invokeAndWait(() -> {});

    JSONObject persisted = new JSONObject(Files.readString(directory.resolve("save/save")));
    TempGameData recovered = frame.getTempGameList().get(0);
    assertAll(
        () -> assertEquals(requested.toFile(), LizzieFrame.curFile),
        () -> assertEquals("saved root", Lizzie.board.getHistory().getStart().getData().comment),
        () -> assertEquals("saved black", Lizzie.board.getHistory().getGameInfo().getPlayerBlack()),
        () -> assertEquals(SAVED_SGF, Files.readString(requested)),
        () -> assertEquals(previousPeriodic, Files.readString(exitSlot)),
        () -> assertEquals(previousPeriodic, Files.readString(periodicSlot),
            "the previous recovery source must stay intact"),
        () -> assertEquals("keep", persisted.getString("other")),
        () -> assertTrue(persisted.getJSONObject("save").similar(config.saveBoardConfig)),
        () -> assertEquals(1, config.saveBoardConfig.getInt("save-auto-game-index1")),
        () -> assertEquals(-1, config.saveBoardConfig.getInt("save-auto-game-index2")),
        () -> assertEquals(1, recovered.index),
        () -> assertEquals("new time", recovered.time),
        () -> assertEquals(2, recovered.curMoveNumer),
        () -> assertEquals("new cursor", recovered.moves),
        () -> assertEquals(0, errorDialogs.get()));
    try (var files = Files.walk(directory)) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
  }

  @ParameterizedTest
  @EnumSource(FileOpenEntry.class)
  void queuedExternalFileCompletionCannotReplaceANewerSaveTarget(FileOpenEntry entry)
      throws Exception {
    File opened = directory.resolve("opened.sgf").toFile();
    Files.writeString(opened.toPath(), SAVED_SGF);
    File replacementFile = directory.resolve("replacement.sgf").toFile();
    BoardHistoryList replacement = SGFParser.parseSgf("(;SZ[5]C[newer game];B[ee])", true);
    SwingUtilities.invokeAndWait(() -> {
      openExternalFile(entry, opened);
      assertEquals("saved root", Lizzie.board.getHistory().getStart().getData().comment,
          "the file must parse before its queued completion is superseded");
      Lizzie.board.setHistory(replacement);
      LizzieFrame.curFile = replacementFile;
    });
    SwingUtilities.invokeAndWait(() -> {});

    assertAll(
        () -> assertSame(replacement, Lizzie.board.getHistory()),
        () -> assertSame(replacementFile, LizzieFrame.curFile),
        () -> assertEquals(SAVED_SGF, Files.readString(opened.toPath())),
        () -> assertEquals(0, errorDialogs.get()));
  }

  private void openExternalFile(FileOpenEntry entry, File file) {
    assertTrue(SwingUtilities.isEventDispatchThread());
    Lizzie.leelaz.isLoaded = true;
    if (entry == FileOpenEntry.STARTUP) {
      frame.restoreStartupGame(new String[] {file.getPath()});
      return;
    }
    Transferable files = new Transferable() {
      @Override public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[] {DataFlavor.javaFileListFlavor};
      }
      @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
        return DataFlavor.javaFileListFlavor.equals(flavor);
      }
      @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
        return List.of(file);
      }
    };
    try {
      Method drop = LizzieFrame.class.getDeclaredMethod("importDroppedKifuFiles", Transferable.class);
      drop.setAccessible(true);
      drop.invoke(frame, files);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private void installAcknowledgingEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = true;
    engine.isKatago = true;
    engine.commandLists.addAll(List.of("stop", "kata-analyze", "loadsgf"));
    Field commandsKnown = Leelaz.class.getDeclaredField("endGetCommandList");
    commandsKnown.setAccessible(true);
    commandsKnown.setBoolean(engine, true);
    transport = ExactSnapshotRestoreProtocolFixture.installAsync(engine, command -> {
      if (command.startsWith("loadsgf ")) {
        synchronizedSnapshots.add(Files.readString(Path.of(command.substring("loadsgf ".length()))));
      }
      return ExactSnapshotRestoreProtocolFixture.Response.success();
    });
    Lizzie.setPrimaryEngine(engine);
    EngineManager.isEmpty = false;
  }

  private void prepareSavedItem(SavedPosition position, Slot slot) throws Exception {
    config.saveBoardConfig = new JSONObject()
        .put("save-auto-game-index1", slot.automatic ? 1 : -1)
        .put("save-auto-game-move-number1", position.moveNumber)
        .put("save-auto-game-move-list1", position.moves)
        .put("save-game-index", new JSONArray(slot.automatic ? List.of() : List.of(1)))
        .put("save-game-name", new JSONArray(List.of("saved game")))
        .put("save-game-time", new JSONArray(List.of("saved time")))
        .put("save-game-move-number", new JSONArray(List.of(position.moveNumber)))
        .put("save-game-move-list", new JSONArray(List.of(position.moves)));
    assertTrue(ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB),
        "bmp", directory.resolve("save/" + slot.filename + ".bmp").toFile()));
    SwingUtilities.invokeAndWait(frame::showTempGamePanel);
    assertTrue(listContainer.isVisible());
  }

  private Path savedFile(Slot slot) {
    return directory.resolve("save/" + slot.filename + ".sgf");
  }

  private void click(EntryPoint entry, SavedPosition position, Slot slot) {
    assertTrue(SwingUtilities.isEventDispatchThread());
    if (entry == EntryPoint.BUTTON) {
      Arrays.stream(list.getComponents()).filter(JButton.class::isInstance).map(JButton.class::cast)
          .filter(button -> Lizzie.resourceBundle.getString("LizzieFrame.saveAndLoad.load")
              .equals(button.getText())).findFirst().orElseThrow().doClick(0);
      return;
    }
    JPanel target = list;
    int x = 150;
    int y = 150;
    if (entry == EntryPoint.PREVIEW) {
      try {
        Method show = LizzieFrame.class.getDeclaredMethod("showBigBoardImage", boolean.class,
            int.class, JPanel.class, int.class, int.class, int.class, String.class,
            boolean.class, boolean.class);
        show.setAccessible(true);
        show.invoke(frame, slot.automatic, 1, list, x, y,
            position.moveNumber, position.moves, true, true);
        preview = (JPopupMenu) getFrameField("bigBoardPanel");
        preview.setVisible(true);
        assertTrue(preview.isVisible());
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
      MouseEvent click = new MouseEvent(preview, MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, false);
      for (MouseListener listener : preview.getMouseListeners()) listener.mouseClicked(click);
    } else {
      MouseEvent click = new MouseEvent(target, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, 1, false);
      for (MouseListener listener : target.getMouseListeners()) listener.mouseClicked(click);
    }
  }

  private Object getFrameField(String name) throws ReflectiveOperationException {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(frame);
  }

  private void setFrameField(String name, Object value) {
    try {
      Field field = LizzieFrame.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(frame, value);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static JOptionPane findOptionPane(Container container) {
    if (container instanceof JOptionPane pane) return pane;
    for (Component child : container.getComponents()) {
      if (child instanceof Container parent) {
        JOptionPane pane = findOptionPane(parent);
        if (pane != null) return pane;
      }
    }
    return null;
  }

  static final class QuietSidebar extends SidebarPanel {
    private QuietSidebar() { super(null, null, null); }
    @Override public void setVisible(boolean visible) {}
  }

  static final class QuietMenu extends Menu {
    @Override public void toggleEngineMenuStatus(boolean pondering, boolean thinking) {}
  }

  static final class SavedListFrame extends LizzieFrame {
    int listCloses;
    BoardHistoryNode nodeWhenListClosed;
    BoardHistoryNode nodeWhenResumeScheduled;
    CountDownLatch resumeScheduled;

    @Override
    public void hideTempGamePanel(boolean showList, boolean showVariation) {
      assertTrue(SwingUtilities.isEventDispatchThread());
      listCloses++;
      nodeWhenListClosed = Lizzie.board.getHistory().getCurrentHistoryNode();
      super.hideTempGamePanel(showList, showVariation);
    }

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      assertTrue(SwingUtilities.isEventDispatchThread());
      nodeWhenResumeScheduled = Lizzie.board.getHistory().getCurrentHistoryNode();
      resumeScheduled.countDown();
    }

    @Override public int getWidth() { return 1024; }
    @Override public int getHeight() { return 768; }
    @Override public Insets getInsets() { return new Insets(0, 0, 0, 0); }
    @Override public void setVisible(boolean visible) {}
    @Override public void refresh() {}
    @Override public void refresh(int mode) {}
    @Override public void updateTitle() {}
    @Override public void setCommentPaneContent() {}
    @Override public boolean resetMovelistFrameandAnalysisFrame() { return false; }
    @Override protected void scheduleMovelistRefreshAfterKifuLoad() {}
    @Override public void onMainEnginePonder() {}
    @Override public void tryToResetByoTime() {}
    @Override public void clearKataEstimate() {}
    @Override public void requestProblemListRefresh() {}
    @Override public void refreshProblemListSnapshot() {}
  }
}
