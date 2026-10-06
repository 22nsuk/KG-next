package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
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
