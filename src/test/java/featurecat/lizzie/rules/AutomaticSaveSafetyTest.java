package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AutomaticSaveSafetyTest {
  @TempDir Path directory;
  private RulesLayerTestHarness rules;
  private PreviewFrame frame;
  private Config config;
  private String oldMetadata;

  enum SaveKind {
    EXIT("autoGame1", 1),
    PERIODIC("autoGame2", 2),
    REPLACE("game1", 1),
    ADD("game2", 2);
    final String name;
    final int index;

    SaveKind(String name, int index) {
      this.name = name;
      this.index = index;
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    rules = RulesLayerTestHarness.open();
    Lizzie.board.setHistory(
        SGFParser.parseSgf("(;SZ[5]C[original];B[aa](;W[bb])(;W[dd]C[branch]))", true));
    Lizzie.board.getHistory().setHead(Lizzie.board.getHistory().getEnd());
    config = ConfigTestHelper.createForTests(directory);
    config.saveBoardConfig =
        new JSONObject()
            .put("save-game-index", new JSONArray(List.of(1)))
            .put("save-game-name", new JSONArray(List.of("old name")))
            .put("save-game-time", new JSONArray(List.of("old time")))
            .put("save-game-move-number", new JSONArray(List.of(1)))
            .put("save-game-move-list", new JSONArray(List.of("old moves")))
            .put("save-auto-game-index1", 1)
            .put("save-auto-game-index2", -5)
            .put("save-auto-game-time1", "old exit")
            .put("save-auto-game-time2", "old periodic");
    config.saveBoard = new JSONObject().put("save", config.saveBoardConfig).put("other", "keep");
    Files.createDirectories(directory.resolve("save"));
    Lizzie.config = config;
    config.saveTempBoard();
    oldMetadata = Files.readString(metadataFile());
    frame = RulesLayerTestHarness.allocate(PreviewFrame.class);
    Lizzie.frame = frame;
  }

  @AfterEach
  void restore() {
    if (rules != null) rules.close();
  }

  @Test
  void closingOverwriteDialogPreservesSavedGame() throws Exception {
    assertOverwriteConfirmation(JOptionPane.CLOSED_OPTION);
  }

  @Test
  void decliningOverwritePreservesSavedGame() throws Exception {
    assertOverwriteConfirmation(JOptionPane.NO_OPTION);
  }

  @Test
  void confirmingOverwriteReplacesSavedGame() throws Exception {
    assertOverwriteConfirmation(JOptionPane.YES_OPTION);
  }

  private void assertOverwriteConfirmation(int answer) throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Path sgf = slot(SaveKind.REPLACE, "sgf");
    Path preview = slot(SaveKind.REPLACE, "bmp");
    String oldSgf = "(;SZ[5]C[previous saved game])";
    Files.writeString(sgf, oldSgf);
    assertTrue(
        ImageIO.write(
            new BufferedImage(7, 7, BufferedImage.TYPE_INT_RGB), "bmp", preview.toFile()));
    byte[] oldPreview = Files.readAllBytes(preview);
    String oldInMemoryMetadata = config.saveBoardConfig.toString();
    config.showListPane = false;
    config.showVariationGraph = false;
    String expectedName = Lizzie.board.getHistory().getGameInfo().getSaveFileName();
    JPanel panel = new JPanel();
    Field panelField = LizzieFrame.class.getDeclaredField("tempGamePanel");
    panelField.setAccessible(true);
    panelField.set(frame, panel);
    Field showingPreview = LizzieFrame.class.getDeclaredField("isShowingBigBoardPanel");
    showingPreview.setAccessible(true);
    AtomicBoolean answered = new AtomicBoolean();
    AtomicBoolean timedOut = new AtomicBoolean();

    SwingUtilities.invokeAndWait(
        () -> {
          Set<Window> existing = Set.of(Window.getWindows());
          // The rules fixture allocates the frame without constructing a native window.
          // Only the modal owner is absent; the real entry button and save path still run.
          Lizzie.frame = null;
          AWTEventListener responder =
              event -> {
                if (event.getID() != WindowEvent.WINDOW_OPENED
                    || !(event.getSource() instanceof JDialog)) return;
                JDialog dialog = (JDialog) event.getSource();
                if (existing.contains(dialog)
                    || !Lizzie.resourceBundle
                        .getString("LizzieFrame.warning")
                        .equals(dialog.getTitle())) return;
                JOptionPane pane = findOptionPane(dialog);
                if (pane == null || pane.getOptionType() != JOptionPane.YES_NO_OPTION) return;
                answered.set(true);
                if (answer == JOptionPane.CLOSED_OPTION) {
                  dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                } else {
                  pane.setValue(answer);
                }
              };
          Toolkit toolkit = Toolkit.getDefaultToolkit();
          Timer deadline =
              new Timer(
                  5000,
                  event -> {
                    timedOut.set(true);
                    for (Window window : Window.getWindows()) {
                      if (!existing.contains(window)) window.dispose();
                    }
                  });
          deadline.setRepeats(false);
          toolkit.addAWTEventListener(responder, AWTEvent.WINDOW_EVENT_MASK);
          try {
            frame.addTempGameOne(1, 0, 0, "old name", "old time", false, 1, "", true, true);
            JButton save =
                Arrays.stream(panel.getComponents())
                    .filter(JButton.class::isInstance)
                    .map(JButton.class::cast)
                    .filter(
                        button ->
                            Lizzie.resourceBundle
                                .getString("LizzieFrame.saveAndLoad.save")
                                .equals(button.getText()))
                    .findFirst()
                    .orElseThrow();
            deadline.start();
            save.doClick(0);
          } finally {
            deadline.stop();
            toolkit.removeAWTEventListener(responder);
            for (Window window : Window.getWindows()) {
              if (!existing.contains(window)) window.dispose();
            }
            Lizzie.frame = frame;
          }
        });

    assertFalse(timedOut.get(), "overwrite dialog was not answered before the deadline");
    assertTrue(answered.get(), "the actual save button must open the confirmation dialog");
    assertFalse(showingPreview.getBoolean(frame), "all decisions must release the preview guard");
    boolean confirmed = answer == JOptionPane.YES_OPTION;
    assertEquals(confirmed, config.showListPane);
    assertEquals(confirmed, config.showVariationGraph);
    assertEquals(confirmed ? 1 : 0, frame.panelRefreshes);
    assertEquals(confirmed ? 1 : 0, frame.previewCalls);
    if (confirmed) {
      assertTrue(Files.readString(sgf).contains(";W[dd]"));
      JSONObject saved = new JSONObject(Files.readString(metadataFile())).getJSONObject("save");
      assertEquals(expectedName, saved.getJSONArray("save-game-name").getString(0));
      assertEquals(2, saved.getJSONArray("save-game-move-number").getInt(0));
      assertTrue(saved.similar(config.saveBoardConfig));
      assertEquals(8, ImageIO.read(preview.toFile()).getWidth());
    } else {
      assertEquals(oldSgf, Files.readString(sgf));
      assertEquals(oldMetadata, Files.readString(metadataFile()));
      assertEquals(oldInMemoryMetadata, config.saveBoardConfig.toString());
      assertArrayEquals(oldPreview, Files.readAllBytes(preview));
    }
    assertNoStagingFiles();
  }

  private static JOptionPane findOptionPane(Container container) {
    if (container instanceof JOptionPane) return (JOptionPane) container;
    for (Component child : container.getComponents()) {
      if (child instanceof Container) {
        JOptionPane pane = findOptionPane((Container) child);
        if (pane != null) return pane;
      }
    }
    return null;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void serializerFailureNeverOpensOrTruncatesThePreviousSgf(boolean autoSave) throws Exception {
    Path target = directory.resolve("previous.sgf");
    String original = "(;SZ[5]C[이전 기보])";
    Files.writeString(target, original);
    Board broken = RulesLayerTestHarness.allocate(BrokenBoard.class);
    assertThrows(
        IllegalStateException.class, () -> SGFParser.save(broken, target.toString(), autoSave));
    assertEquals(original, Files.readString(target));
    assertNoStagingFiles();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void fileSaveUsesDetachedHistoryAndPreservesAutomaticSaveOptions(boolean autoSave)
      throws Exception {
    Lizzie.leelaz.isKatago = true;
    Lizzie.leelaz.usingSpecificRules = 1;
    BoardHistoryList history = Lizzie.board.getHistory();
    BoardHistoryNode current = history.getCurrentHistoryNode();
    Path target = directory.resolve("대국.sgf");
    SGFParser.save(Lizzie.board, target.toString(), autoSave);
    String saved = Files.readString(target);
    assertTrue(saved.contains(";W[bb]"));
    assertTrue(saved.contains(";W[dd]"));
    assertTrue(saved.contains("branch"));
    assertEquals(!autoSave, saved.contains(Lizzie.resourceBundle.getString("SGFParse.rules")));
    assertEquals("original", history.getStart().getData().comment);
    assertSame(current, history.getCurrentHistoryNode());
    assertNoStagingFiles();
  }

  @ParameterizedTest
  @EnumSource(SaveKind.class)
  void failedSgfReplacementPreservesMetadataAndDoesNotTouchPreview(SaveKind kind) throws Exception {
    JSONObject original = config.saveBoardConfig;
    Path target = slot(kind, "sgf");
    Files.createDirectory(target);
    Files.writeString(target.resolve("keep"), "unreplaceable destination");
    Files.writeString(slot(kind, "bmp"), "old preview");
    save(kind);
    assertSame(original, config.saveBoardConfig);
    assertEquals(oldMetadata, Files.readString(metadataFile()));
    assertEquals("old preview", Files.readString(slot(kind, "bmp")));
    assertEquals("unreplaceable destination", Files.readString(target.resolve("keep")));
    assertEquals(0, frame.previewCalls);
    assertNoStagingFiles();
  }

  @ParameterizedTest
  @EnumSource(SaveKind.class)
  void successfulSavePublishesCompleteSgfAndItsRecordInTheConfiguredDirectory(SaveKind kind)
      throws Exception {
    save(kind);
    String saved = Files.readString(slot(kind, "sgf"));
    assertTrue(saved.contains(";B[aa]"));
    assertTrue(saved.contains(";W[bb]"));
    assertTrue(saved.contains(";W[dd]"));
    assertRecordPublished(kind);
    assertNotNull(ImageIO.read(slot(kind, "bmp").toFile()));
    assertEquals(1, frame.previewCalls);
    // Every panel reader uses the same path resolver as the writer (also when user.dir differs).
    var resolver =
        LizzieFrame.class.getDeclaredMethod(
            "resolveSavedGameFile", boolean.class, int.class, String.class);
    resolver.setAccessible(true);
    assertEquals(
        slot(kind, "sgf").toFile(),
        resolver.invoke(
            frame, kind == SaveKind.EXIT || kind == SaveKind.PERIODIC, kind.index, "sgf"));
    assertNoStagingFiles();
  }

  @ParameterizedTest
  @EnumSource(SaveKind.class)
  void metadataFailureDoesNotPublishButCompleteSgfRemainsRecoverable(SaveKind kind)
      throws Exception {
    JSONObject original = config.saveBoardConfig;
    Path blocked = directory.resolve("blocked-metadata");
    Files.createDirectory(blocked);
    Files.writeString(blocked.resolve("keep"), "keep");
    Field filename = Config.class.getDeclaredField("saveBoardFilename");
    filename.setAccessible(true);
    filename.set(config, blocked.toString());
    save(kind);
    assertSame(original, config.saveBoardConfig);
    assertEquals(oldMetadata, Files.readString(metadataFile()));
    assertEquals("keep", Files.readString(blocked.resolve("keep")));
    assertTrue(Files.readString(slot(kind, "sgf")).contains(";W[dd]"));
    assertEquals(0, frame.previewCalls);
    assertNoStagingFiles();
    // A failed metadata attempt neither poisons the next save nor loses the recovered SGF.
    filename.set(config, metadataFile().toString());
    save(kind);
    assertRecordPublished(kind);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void unavailableOrUnencodablePreviewDoesNotHideTheSavedGame(int failureMode) throws Exception {
    frame.failureMode = failureMode;
    Files.writeString(slot(SaveKind.EXIT, "bmp"), "stale preview");
    save(SaveKind.EXIT);
    assertRecordPublished(SaveKind.EXIT);
    assertTrue(Files.readString(slot(SaveKind.EXIT, "sgf")).contains(";W[dd]"));
    assertFalse(
        Files.exists(slot(SaveKind.EXIT, "bmp")), "do not show a stale board as the new record");
    assertNoStagingFiles();
  }

  @Test
  void candidateMetadataIsDetachedAndPublishedOnlyAfterPersistence() throws Exception {
    JSONObject beforeRoot = config.saveBoard;
    JSONObject beforeSave = config.saveBoardConfig;
    JSONObject candidate = new JSONObject(beforeSave.toString()).put("marker", "published");
    config.saveTempBoard(candidate);
    assertNotSame(candidate, config.saveBoardConfig);
    assertNotSame(beforeRoot, config.saveBoard);
    assertFalse(beforeSave.has("marker"));
    candidate.put("marker", "modified later");
    assertEquals("published", config.saveBoardConfig.getString("marker"));
    JSONObject persisted = new JSONObject(Files.readString(metadataFile()));
    assertEquals("published", persisted.getJSONObject("save").getString("marker"));
    assertEquals("keep", persisted.getString("other"));
  }

  @Test
  void backgroundAutosaveWaitsForUiSaveAndCannotOverwriteItsMetadata() throws Exception {
    frame.previewEntered = new CountDownLatch(1);
    frame.releasePreview = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var temporary = workers.submit(() -> frame.addTempGame(2, "새 기록"));
      assertTrue(frame.previewEntered.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> temporary.get(100, TimeUnit.MILLISECONDS));
      var automatic = workers.submit(() -> frame.saveAutoGame(1));
      assertThrows(TimeoutException.class, () -> automatic.get(100, TimeUnit.MILLISECONDS));
      frame.releasePreview.countDown();
      temporary.get(5, TimeUnit.SECONDS);
      automatic.get(5, TimeUnit.SECONDS);
      assertRecordPublished(SaveKind.ADD);
      assertRecordPublished(SaveKind.EXIT);
      assertEquals(2, frame.previewCalls);
    } finally {
      frame.releasePreview.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void interruptedCallerStillWaitsForFinalSaveAndRetainsInterruptFlag() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    try {
      var saved =
          worker.submit(
              () -> {
                Thread.currentThread().interrupt();
                frame.saveAutoGame(1);
                return Thread.interrupted();
              });
      assertTrue(saved.get(5, TimeUnit.SECONDS));
      assertRecordPublished(SaveKind.EXIT);
      assertTrue(Files.isRegularFile(slot(SaveKind.EXIT, "sgf")));
    } finally {
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private void save(SaveKind kind) {
    switch (kind) {
      case EXIT:
        frame.saveAutoGame(1);
        break;
      case PERIODIC:
        frame.saveAutoGame(2);
        break;
      case REPLACE:
        frame.saveTempGame(1, "새 기록");
        break;
      case ADD:
        frame.addTempGame(2, "새 기록");
        break;
    }
  }

  private void assertRecordPublished(SaveKind kind) throws IOException {
    JSONObject saved = new JSONObject(Files.readString(metadataFile())).getJSONObject("save");
    assertTrue(saved.similar(config.saveBoardConfig));
    if (kind == SaveKind.EXIT || kind == SaveKind.PERIODIC) {
      assertEquals(
          kind == SaveKind.EXIT ? 1 : -5, saved.getInt("save-auto-game-index" + kind.index));
      assertEquals(2, saved.getInt("save-auto-game-move-number" + kind.index));
      assertNotEquals("old exit", saved.getString("save-auto-game-time" + kind.index));
      assertNotEquals("old periodic", saved.getString("save-auto-game-time" + kind.index));
      assertEquals(kind == SaveKind.EXIT ? -1 : -5, saved.getInt("save-auto-game-index2"));
    } else {
      assertEquals("새 기록", saved.getJSONArray("save-game-name").getString(kind.index - 1));
      assertEquals(2, saved.getJSONArray("save-game-move-number").getInt(kind.index - 1));
      assertEquals(kind.index, saved.getJSONArray("save-game-index").length());
      if (kind == SaveKind.ADD)
        assertEquals("old name", saved.getJSONArray("save-game-name").getString(0));
    }
  }

  private Path metadataFile() {
    return directory.resolve("save/save");
  }

  private Path slot(SaveKind kind, String extension) {
    return directory.resolve("save/" + kind.name + "." + extension);
  }

  private void assertNoStagingFiles() throws IOException {
    try (var files = Files.walk(directory)) {
      assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
    }
  }

  static class BrokenBoard extends Board {
    @Override
    public BoardHistoryList getHistory() {
      throw new IllegalStateException("serialization failed");
    }
  }

  static class PreviewFrame extends LizzieFrame {
    int panelRefreshes;

    @Override
    public void showTempGamePanel() {
      panelRefreshes++;
    }

    int previewCalls;
    int failureMode;
    CountDownLatch previewEntered;
    CountDownLatch releasePreview;

    @Override
    public Image saveMainBoardToImageOri() {
      assertTrue(SwingUtilities.isEventDispatchThread());
      previewCalls++;
      if (previewEntered != null) {
        previewEntered.countDown();
        try {
          assertTrue(releasePreview.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failure);
        }
      }
      if (failureMode == 1) throw new IllegalStateException("board not painted yet");
      return new BufferedImage(
          8, 8, failureMode == 2 ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    }
  }
}
