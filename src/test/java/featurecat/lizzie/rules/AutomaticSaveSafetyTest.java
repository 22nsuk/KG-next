package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.TempGameData;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
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
  @ValueSource(ints = {1, 2, 3})
  void deletionPreservesSurvivingFileIdentities(int deleted) throws Exception {
    seedTemporaryRecords(1, 2, 3);
    Map<Path, byte[]> before = temporaryFiles();
    frame.deleteTempGame(deleted);
    List<Integer> expected = new ArrayList<>(List.of(1, 2, 3));
    expected.remove(Integer.valueOf(deleted));
    assertTemporaryIndices(expected);
    for (int index : expected) {
      assertEquals("record " + index, record(index).name);
      assertEquals("moves " + index, record(index).moves);
      for (String extension : List.of("sgf", "bmp")) {
        Path path = temporarySlot(index, extension);
        assertArrayEquals(before.get(path), Files.readAllBytes(path));
      }
    }
    assertFalse(Files.exists(temporarySlot(deleted, "sgf")));
    assertFalse(Files.exists(temporarySlot(deleted, "bmp")));
    assertEquals(4L, config.saveBoardConfig.getLong("save-game-next-index"));
    assertEquals(0, frame.editFailures);
    assertNoStagingFiles();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deletionMetadataFailurePreservesFilesAndCanRetry(boolean all) throws Exception {
    seedTemporaryRecords(1, 2, 3);
    Map<Path, byte[]> before = temporaryFiles();
    JSONObject original = config.saveBoardConfig;
    Field filename = blockMetadataDestination();
    if (all) frame.deleteAllTempGame();
    else frame.deleteTempGame(2);
    assertEquals(1, frame.editFailures);
    assertSame(original, config.saveBoardConfig);
    assertEquals(oldMetadata, Files.readString(metadataFile()));
    assertFilesUnchanged(before);
    filename.set(config, metadataFile().toString());
    if (all) frame.deleteAllTempGame();
    else frame.deleteTempGame(2);
    assertTemporaryIndices(all ? List.of() : List.of(1, 3));
    assertNoStagingFiles();
  }

  @Test
  void renameButtonPersistsNewNameBeforeRefreshingPanel() throws Exception {
    seedTemporaryRecords(1, 2, 3);
    Map<Path, byte[]> before = temporaryFiles();
    AtomicReference<String> persistedNameAtRefresh = new AtomicReference<>();
    frame.afterPanelRefresh =
        () -> {
          try {
            JSONObject saved =
                new JSONObject(Files.readString(metadataFile())).getJSONObject("save");
            persistedNameAtRefresh.set(saved.getJSONArray("save-game-name").getString(1));
          } catch (IOException failure) {
            throw new AssertionError(failure);
          }
        };
    clickRecordButton(2, "LizzieFrame.saveAndLoad.reName", "새 이름");
    assertEquals("새 이름", persistedNameAtRefresh.get());
    assertEquals("새 이름", record(2).name);
    assertEquals("time 2", record(2).time);
    assertEquals("moves 2", record(2).moves);
    assertEquals(1, frame.panelRefreshes);
    assertEquals(0, frame.previewCalls);
    assertTemporaryIndices(List.of(1, 2, 3));
    assertFilesUnchanged(before);
  }

  @Test
  void deleteButtonCommitsTheListBeforeRefreshingPanel() throws Exception {
    seedTemporaryRecords(1, 2, 3);
    clickRecordButton(2, "LizzieFrame.saveAndLoad.del", "unused");
    assertTemporaryIndices(List.of(1, 3));
    assertEquals(1, frame.panelRefreshes);
    assertFalse(Files.exists(temporarySlot(2, "sgf")));
  }

  @Test
  void failedDeleteButtonKeepsTheExistingListAndFiles() throws Exception {
    seedTemporaryRecords(1, 2, 3);
    Map<Path, byte[]> before = temporaryFiles();
    String original = config.saveBoardConfig.toString();
    blockMetadataDestination();
    clickRecordButton(2, "LizzieFrame.saveAndLoad.del", "unused");
    assertEquals(original, config.saveBoardConfig.toString());
    assertFilesUnchanged(before);
    assertEquals(oldMetadata, Files.readString(metadataFile()));
    assertEquals(1, frame.editFailures);
  }

  @Test
  void sparseRecordRowDisplaysOrdinalButEditsItsFileIdentity() throws Exception {
    seedTemporaryRecords(1, 3);
    SwingUtilities.invokeAndWait(
        () -> {
          JPanel panel = new JPanel();
          try {
            Field panelField = LizzieFrame.class.getDeclaredField("tempGamePanel");
            panelField.setAccessible(true);
            panelField.set(frame, panel);
            var row =
                LizzieFrame.class.getDeclaredMethod(
                    "addTempGameOne",
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    String.class,
                    String.class,
                    boolean.class,
                    int.class,
                    String.class,
                    boolean.class,
                    boolean.class);
            row.setAccessible(true);
            row.invoke(frame, 3, 2, 0, 0, "third file", "old time", false, 1, "", true, true);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
          String label = Lizzie.resourceBundle.getString("LizzieFrame.saveAndLoad.rec") + 2;
          assertTrue(
              Arrays.stream(panel.getComponents())
                  .filter(JLabel.class::isInstance)
                  .map(JLabel.class::cast)
                  .anyMatch(component -> label.equals(component.getText())));
          Arrays.stream(panel.getComponents())
              .filter(JButton.class::isInstance)
              .map(JButton.class::cast)
              .filter(
                  button ->
                      Lizzie.resourceBundle
                          .getString("LizzieFrame.saveAndLoad.reName")
                          .equals(button.getText()))
              .findFirst()
              .orElseThrow()
              .doClick(0);
        });
    assertTemporaryIndices(List.of(1, 3));
    assertEquals("third file", record(3).name);
    assertEquals("record 1", record(1).name);
  }

  @Test
  void renameFailureKeepsMemoryAndDiskNamesAndCanRetry() throws Exception {
    seedTemporaryRecords(1, 2, 3);
    Map<Path, byte[]> before = temporaryFiles();
    JSONObject original = config.saveBoardConfig;
    Field filename = blockMetadataDestination();
    clickRecordButton(2, "LizzieFrame.saveAndLoad.reName", "새 이름");
    assertEquals(1, frame.editFailures);
    assertSame(original, config.saveBoardConfig);
    assertEquals("record 2", record(2).name);
    assertEquals(oldMetadata, Files.readString(metadataFile()));
    assertFilesUnchanged(before);
    filename.set(config, metadataFile().toString());
    frame.renameTempGame(2, "새 이름");
    assertEquals("새 이름", record(2).name);
    assertTemporaryIndices(List.of(1, 2, 3));
    assertFilesUnchanged(before);
    assertNoStagingFiles();
  }

  @Test
  void deleteAllPreservesAutomaticAndUnlistedFilesAndDoesNotRecycleIdentifiers() throws Exception {
    seedTemporaryRecords(1, 2, 3);
    Path unlisted = temporarySlot(9, "sgf");
    Files.writeString(unlisted, "orphan to preserve");
    Files.writeString(slot(SaveKind.EXIT, "sgf"), "automatic recovery");
    frame.deleteAllTempGame();
    assertTemporaryIndices(List.of());
    assertEquals("automatic recovery", Files.readString(slot(SaveKind.EXIT, "sgf")));
    assertEquals(1, config.saveBoardConfig.getInt("save-auto-game-index1"));
    assertEquals("orphan to preserve", Files.readString(unlisted));
    for (int index : List.of(1, 2, 3)) {
      assertFalse(Files.exists(temporarySlot(index, "sgf")));
      assertFalse(Files.exists(temporarySlot(index, "bmp")));
    }
    frame.addTempGame(1, "new record");
    assertTemporaryIndices(List.of(4));
    assertTrue(Files.readString(temporarySlot(4, "sgf")).contains(";W[dd]"));
  }

  @Test
  void cleanupFailureDoesNotRollBackOrDamageSurvivors() throws Exception {
    seedTemporaryRecords(1, 2, 3);
    byte[] survivor = Files.readAllBytes(temporarySlot(3, "sgf"));
    Path blocked = temporarySlot(2, "sgf");
    Files.delete(blocked);
    Files.createDirectory(blocked);
    Files.writeString(blocked.resolve("keep"), "not a regular saved-game file");
    frame.deleteTempGame(2);
    assertTemporaryIndices(List.of(1, 3));
    assertArrayEquals(survivor, Files.readAllBytes(temporarySlot(3, "sgf")));
    assertEquals("not a regular saved-game file", Files.readString(blocked.resolve("keep")));
    assertFalse(Files.exists(temporarySlot(2, "bmp")));
    frame.addTempGame(2, "new record");
    assertTemporaryIndices(List.of(1, 3, 4));
    assertTrue(Files.isDirectory(blocked));
  }

  @Test
  void newSaveSkipsOrphanSgfPreviewAndDirectorySlots() throws Exception {
    seedTemporaryRecords(1);
    Files.writeString(temporarySlot(2, "sgf"), "recoverable orphan");
    Files.writeString(temporarySlot(3, "bmp"), "orphan preview");
    Files.createDirectory(temporarySlot(4, "sgf"));
    frame.addTempGame(2, "new record");
    assertTemporaryIndices(List.of(1, 5));
    assertEquals("recoverable orphan", Files.readString(temporarySlot(2, "sgf")));
    assertEquals("orphan preview", Files.readString(temporarySlot(3, "bmp")));
    assertTrue(Files.isDirectory(temporarySlot(4, "sgf")));
    assertTrue(Files.readString(temporarySlot(5, "sgf")).contains(";W[dd]"));
    assertEquals(0, frame.editFailures);
  }

  @Test
  void unavailableSaveDirectoryRejectsNewRecordWithoutPublishingIt() throws Exception {
    seedTemporaryRecords(1);
    Map<Path, byte[]> before = temporaryFiles();
    Path saveDirectory = directory.resolve("save");
    Path backup = directory.resolve("saved-directory-backup");
    Files.move(saveDirectory, backup);
    Files.writeString(saveDirectory, "not a directory");
    try {
      frame.addTempGame(2, "new record");
      assertEquals(1, frame.editFailures);
      assertEquals(
          List.of(1), frame.getSaveGameList().stream().map(record -> record.index).toList());
      assertEquals("not a directory", Files.readString(saveDirectory));
    } finally {
      Files.delete(saveDirectory);
      Files.move(backup, saveDirectory);
    }
    assertEquals(oldMetadata, Files.readString(metadataFile()));
    assertFilesUnchanged(before);
  }

  @Test
  void overwriteAndRenameUseFileIdentityAfterMiddleDeletion() throws Exception {
    seedTemporaryRecords(1, 2, 3);
    byte[] first = Files.readAllBytes(temporarySlot(1, "sgf"));
    frame.deleteTempGame(2);
    frame.saveTempGame(3, "replaced third");
    frame.renameTempGame(3, "renamed third");
    assertTemporaryIndices(List.of(1, 3));
    assertEquals("renamed third", record(3).name);
    assertTrue(Files.readString(temporarySlot(3, "sgf")).contains(";W[dd]"));
    assertArrayEquals(first, Files.readAllBytes(temporarySlot(1, "sgf")));
    assertFalse(Files.exists(temporarySlot(2, "sgf")));
  }

  @Test
  void staleEditActionsCannotAffectANewerRecord() throws Exception {
    seedTemporaryRecords(1, 2, 3);
    frame.deleteTempGame(3);
    frame.addTempGame(3, "new fourth");
    assertTemporaryIndices(List.of(1, 2, 4));
    String metadata = Files.readString(metadataFile());
    Map<Path, byte[]> before = temporaryFiles();
    frame.deleteTempGame(3);
    frame.renameTempGame(3, "stale rename");
    frame.saveTempGame(3, "stale overwrite");
    assertEquals(metadata, Files.readString(metadataFile()));
    assertFilesUnchanged(before);
    assertEquals("new fourth", record(4).name);
  }

  @Test
  void identifierExhaustionFailsWithoutReusingDeletedRecords() throws Exception {
    seedTemporaryRecords(Integer.MAX_VALUE);
    frame.deleteAllTempGame();
    assertEquals(
        (long) Integer.MAX_VALUE + 1L, config.saveBoardConfig.getLong("save-game-next-index"));
    String metadata = Files.readString(metadataFile());
    frame.addTempGame(1, "cannot allocate");
    assertEquals(1, frame.editFailures);
    assertEquals(metadata, Files.readString(metadataFile()));
    assertTemporaryIndices(List.of());
    assertFalse(Files.exists(temporarySlot(1, "sgf")));
  }

  @Test
  void duplicateRecordIdentitiesAreRejectedBeforeDeletion() throws Exception {
    seedTemporaryRecords(1, 2);
    config.saveBoardConfig.getJSONArray("save-game-index").put(1, 1);
    config.saveTempBoard();
    String metadata = Files.readString(metadataFile());
    Map<Path, byte[]> before = temporaryFiles();
    assertThrows(IllegalStateException.class, () -> frame.deleteTempGame(1));
    assertEquals(metadata, Files.readString(metadataFile()));
    assertFilesUnchanged(before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void editsSerializeWithAnInFlightAutomaticSave(boolean deletion) throws Exception {
    seedTemporaryRecords(1, 2, 3);
    frame.previewEntered = new CountDownLatch(1);
    frame.releasePreview = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    CountDownLatch editStarted = new CountDownLatch(1);
    try {
      var automatic = workers.submit(() -> frame.saveAutoGame(2));
      assertTrue(frame.previewEntered.await(5, TimeUnit.SECONDS));
      var edit =
          workers.submit(
              () -> {
                editStarted.countDown();
                if (deletion) frame.deleteTempGame(2);
                else frame.renameTempGame(2, "queued rename");
              });
      assertTrue(editStarted.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> edit.get(100, TimeUnit.MILLISECONDS));
      frame.releasePreview.countDown();
      automatic.get(5, TimeUnit.SECONDS);
      edit.get(5, TimeUnit.SECONDS);
      assertTemporaryIndices(deletion ? List.of(1, 3) : List.of(1, 2, 3));
      if (!deletion) assertEquals("queued rename", record(2).name);
      assertEquals(2, config.saveBoardConfig.getInt("save-auto-game-move-number2"));
    } finally {
      frame.releasePreview.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private void seedTemporaryRecords(int... indices) throws IOException {
    JSONArray ids = new JSONArray();
    JSONArray names = new JSONArray();
    JSONArray times = new JSONArray();
    JSONArray moves = new JSONArray();
    JSONArray numbers = new JSONArray();
    for (int index : indices) {
      ids.put(index);
      names.put("record " + index);
      times.put("time " + index);
      moves.put("moves " + index);
      numbers.put(1);
      Files.writeString(temporarySlot(index, "sgf"), "(;SZ[5]C[record " + index + "];B[aa])");
      assertTrue(
          ImageIO.write(
              new BufferedImage(7, 7, BufferedImage.TYPE_INT_RGB),
              "bmp",
              temporarySlot(index, "bmp").toFile()));
    }
    config
        .saveBoardConfig
        .put("save-game-index", ids)
        .put("save-game-name", names)
        .put("save-game-time", times)
        .put("save-game-move-list", moves)
        .put("save-game-move-number", numbers);
    config.saveBoardConfig.remove("save-game-next-index"); // Existing, unmigrated metadata.
    config.saveTempBoard();
    oldMetadata = Files.readString(metadataFile());
  }

  private Path temporarySlot(int index, String extension) {
    return directory.resolve("save/game" + index + "." + extension);
  }

  private TempGameData record(int index) {
    return frame.getSaveGameList().stream()
        .filter(record -> record.index == index)
        .findFirst()
        .orElseThrow();
  }

  private void assertTemporaryIndices(List<Integer> expected) throws IOException {
    JSONObject persisted = new JSONObject(Files.readString(metadataFile())).getJSONObject("save");
    assertTrue(persisted.similar(config.saveBoardConfig));
    assertEquals(expected, persisted.getJSONArray("save-game-index").toList());
    assertEquals(expected, frame.getSaveGameList().stream().map(record -> record.index).toList());
    assertEquals(
        expected,
        frame.getTempGameList().stream()
            .filter(record -> !record.isAutoSave)
            .map(record -> record.index)
            .toList());
  }

  private Map<Path, byte[]> temporaryFiles() throws IOException {
    Map<Path, byte[]> contents = new LinkedHashMap<>();
    try (var files = Files.list(directory.resolve("save"))) {
      for (Path file :
          files
              .filter(path -> path.getFileName().toString().startsWith("game"))
              .filter(Files::isRegularFile)
              .toList()) contents.put(file, Files.readAllBytes(file));
    }
    return contents;
  }

  private void assertFilesUnchanged(Map<Path, byte[]> contents) throws IOException {
    for (var entry : contents.entrySet()) {
      assertArrayEquals(
          entry.getValue(), Files.readAllBytes(entry.getKey()), entry.getKey().toString());
    }
  }

  private Field blockMetadataDestination() throws Exception {
    Path blocked = directory.resolve("blocked-metadata");
    Files.createDirectory(blocked);
    Files.writeString(blocked.resolve("keep"), "keep");
    Field filename = Config.class.getDeclaredField("saveBoardFilename");
    filename.setAccessible(true);
    filename.set(config, blocked.toString());
    return filename;
  }

  private void clickRecordButton(int index, String key, String name) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JPanel panel = new JPanel();
          try {
            Field panelField = LizzieFrame.class.getDeclaredField("tempGamePanel");
            panelField.setAccessible(true);
            panelField.set(frame, panel);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
          frame.addTempGameOne(index, 0, 0, "old name", "old time", false, 1, "", true, true);
          Arrays.stream(panel.getComponents())
              .filter(JTextField.class::isInstance)
              .map(JTextField.class::cast)
              .findFirst()
              .orElseThrow()
              .setText(name);
          Arrays.stream(panel.getComponents())
              .filter(JButton.class::isInstance)
              .map(JButton.class::cast)
              .filter(button -> Lizzie.resourceBundle.getString(key).equals(button.getText()))
              .findFirst()
              .orElseThrow()
              .doClick(0);
        });
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
  @EnumSource(value = SaveKind.class, names = "ADD", mode = EnumSource.Mode.EXCLUDE)
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
    int editFailures;
    Runnable afterPanelRefresh;

    @Override
    protected void savedGameEditFailed(IOException failure) {
      editFailures++;
    }

    @Override
    public void showTempGamePanel() {
      panelRefreshes++;
      if (afterPanelRefresh != null) afterPanelRefresh.run();
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
