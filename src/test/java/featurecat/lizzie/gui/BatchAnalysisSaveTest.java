package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Zobrist;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real toolbar completion, SGF persistence failure, and the existing start action used for retry. */
class BatchAnalysisSaveTest {
  @TempDir Path directory;
  private Config previousConfig;
  private Board previousBoard;
  private Leelaz previousEngine;
  private LizzieFrame previousFrame;
  private BottomToolbar previousToolbar;
  private File previousFile;
  private boolean previousEngineEmpty;
  private int previousWidth;
  private int previousHeight;
  private RecordingFrame frame;
  private BottomToolbar toolbar;
  private Path sourceDirectory;

  @BeforeEach
  void prepare() throws Exception {
    previousConfig = Lizzie.config;
    previousBoard = Lizzie.board;
    previousEngine = Lizzie.leelaz;
    previousFrame = Lizzie.frame;
    previousToolbar = LizzieFrame.toolbar;
    previousFile = LizzieFrame.curFile;
    previousEngineEmpty = EngineManager.isEmpty;
    previousWidth = Board.boardWidth;
    previousHeight = Board.boardHeight;
    Board.boardWidth = 5;
    Board.boardHeight = 5;
    Zobrist.init();
    Lizzie.config = ConfigTestHelper.createForTests(directory);
    Lizzie.config.appendWinrateToComment = false;
    Lizzie.frame = null;
    Lizzie.leelaz = new Leelaz("");
    EngineManager.isEmpty = true;
    Lizzie.board = allocate(Board.class);
    Lizzie.board.setHistory(
        SGFParser.parseSgf("(;SZ[5]C[current result];B[aa](;W[bb])(;W[dd]C[branch]))", true));
    frame = allocate(RecordingFrame.class);
    Lizzie.frame = frame;
    toolbar = allocate(BottomToolbar.class);
    toolbar.chkAutoAnalyse = new JCheckBox();
    toolbar.chkAnaAutoSave = new JCheckBox();
    toolbar.start = new JButton();
    toolbar.msg = allocate(VisibleMessage.class);
    set(BottomToolbar.class, toolbar, "resourceBundle", Lizzie.resourceBundle);
    LizzieFrame.toolbar = toolbar;
    sourceDirectory = Files.createDirectory(directory.resolve("source"));
    List<File> files =
        List.of(
            sourceDirectory.resolve("first.sgf").toFile(),
            sourceDirectory.resolve("second.sgf").toFile());
    var batch = frame.beginBatchAutoAnalysis(files);
    assertNotNull(batch);
    set(BottomToolbar.class, toolbar, "autoAnalysisBatch", batch);
  }

  @AfterEach
  void restore() throws Exception {
    awaitSaves();
    SwingUtilities.invokeAndWait(() -> {});
    Lizzie.config = previousConfig;
    Lizzie.board = previousBoard;
    Lizzie.leelaz = previousEngine;
    Lizzie.frame = previousFrame;
    LizzieFrame.toolbar = previousToolbar;
    LizzieFrame.curFile = previousFile;
    EngineManager.isEmpty = previousEngineEmpty;
    Board.boardWidth = previousWidth;
    Board.boardHeight = previousHeight;
    Zobrist.init();
  }

  @ParameterizedTest
  @CsvSource({"true,0", "true,1", "false,0", "false,1"})
  void failedOrdinaryBatchSaveRetainsResultUntilStartRetriesIt(boolean completed, int index)
      throws Exception {
    frame.BatchAnaNum = index;
    LizzieFrame.curFile = frame.Batchfiles.get(index);
    var files = frame.Batchfiles;
    Board board = Lizzie.board;
    BoardHistoryList history = board.getHistory();
    blockOutputDirectory();
    Lizzie.config.isAutoAna = true;

    SwingUtilities.invokeAndWait(() -> toolbar.stopAutoAna(false, !completed));
    awaitSaves();

    assertEquals(0, frame.nextLoads, "failed save must not advance to the next game");
    assertEquals(0, frame.finishes, "failed save must not report batch completion or discard it");
    assertEquals(1, frame.saveFailures);
    assertFalse(Lizzie.config.isAutoAna);
    assertTrue(frame.isBatchAna);
    assertSame(files, frame.Batchfiles);
    assertEquals(index, frame.BatchAnaNum);
    assertSame(board, Lizzie.board);
    assertSame(history, board.getHistory());
    assertEquals(files.get(index), LizzieFrame.curFile);
    assertEquals("unrelated file", Files.readString(sourceDirectory));

    // The normal start control retries the save before trying to reload or reanalyze the game.
    SwingUtilities.invokeAndWait(toolbar::startAutoAna);
    awaitSaves();
    assertEquals(2, frame.saveFailures);
    assertEquals(0, frame.nextLoads);
    assertEquals(0, frame.finishes);
    restoreOutputDirectory();
    SwingUtilities.invokeAndWait(toolbar::startAutoAna);
    awaitSaves();

    boolean advance = completed && index == 0;
    assertEquals(advance ? 1 : 0, frame.nextLoads);
    assertEquals(advance ? 0 : 1, frame.finishes);
    assertEquals(2, frame.saveFailures);
    assertFalse(Lizzie.config.isAutoAna, "retry must save the result without starting analysis");
    assertSame(history, board.getHistory());
    assertFalse(frame.hasPendingBatchAnalysisSave());
    try (var outputs = Files.list(sourceDirectory)) {
      List<Path> saved = outputs.toList();
      assertEquals(1, saved.size());
      String sgf = Files.readString(saved.get(0));
      assertTrue(sgf.contains("current result"), sgf);
      assertTrue(sgf.contains(";B[aa]"), sgf);
      assertTrue(sgf.contains(";W[bb]"), sgf);
      assertTrue(sgf.contains(";W[dd]"), sgf);
      assertTrue(saved.get(0).getFileName().toString().startsWith(index == 0 ? "first_" : "second_"));
    }
  }

  @Test
  void quickBatchRetryRestoresModeOnlyAfterSaving() throws Exception {
    frame.isBatchAnalysisMode = true;
    AtomicInteger saved = new AtomicInteger();
    blockOutputDirectory();

    SwingUtilities.invokeAndWait(
        () -> frame.saveBatchAnalysisResult(() -> {
          assertTrue(frame.isBatchAnalysisMode);
          saved.incrementAndGet();
        }));
    awaitSaves();
    assertFalse(frame.isBatchAnalysisMode);
    assertEquals(0, saved.get());
    assertTrue(frame.hasPendingBatchAnalysisSave());

    restoreOutputDirectory();
    SwingUtilities.invokeAndWait(() -> assertTrue(frame.retryBatchAnalysisSave()));
    awaitSaves();
    assertEquals(1, saved.get());
    SwingUtilities.invokeAndWait(() -> assertFalse(frame.retryBatchAnalysisSave()));
    assertEquals(1, saved.get());
  }

  @ParameterizedTest
  @CsvSource({"board", "history", "files", "index"})
  void staleRetryNeverSavesOrAdvancesAReplacement(String changed) throws Exception {
    AtomicInteger saved = new AtomicInteger();
    blockOutputDirectory();
    SwingUtilities.invokeAndWait(() -> frame.saveBatchAnalysisResult(saved::incrementAndGet));
    awaitSaves();
    assertEquals(1, frame.saveFailures);
    restoreOutputDirectory();
    switch (changed) {
      case "board":
        Board replacement = allocate(Board.class);
        replacement.setHistory(Lizzie.board.getHistory());
        Lizzie.board = replacement;
        break;
      case "history":
        Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5]C[replacement];B[ee])", true));
        break;
      case "files":
        frame.Batchfiles = new ArrayList<>(frame.Batchfiles);
        break;
      case "index":
        frame.BatchAnaNum++;
        break;
      default:
        fail("unknown replacement");
    }

    SwingUtilities.invokeAndWait(() -> assertFalse(frame.retryBatchAnalysisSave()));
    assertEquals(0, saved.get());
    assertFalse(frame.hasPendingBatchAnalysisSave());
    try (var outputs = Files.list(sourceDirectory)) {
      assertEquals(0, outputs.count());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void pendingBatchSaveLeavesEdtResponsiveAndAdvancesOnlyAfterItsFrozenResultCommits(
      boolean completed) throws Exception {
    LizzieFrame.curFile = frame.Batchfiles.get(0);
    try (BlockedSave ignored = new BlockedSave()) {
      Lizzie.config.isAutoAna = true;
      SwingUtilities.invokeAndWait(() -> toolbar.stopAutoAna(false, !completed));
      // This second EDT dispatch must remain available while disk work is deliberately blocked.
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(frame.hasPendingBatchAnalysisSave());
        assertEquals(0, frame.nextLoads);
        assertEquals(0, frame.finishes);
        Lizzie.board.getHistory().getStart().getData().comment = "edited after capture";
        toolbar.startAutoAna();
        toolbar.startAutoAna();
      });
      try (var files = Files.list(sourceDirectory)) {
        assertEquals(0, files.count(), "accepted saves must wait for the serial writer");
      }
      assertEquals(0, frame.nextLoads);
      assertEquals(0, frame.finishes);
    }

    assertEquals(completed ? 1 : 0, frame.nextLoads);
    assertEquals(completed ? 0 : 1, frame.finishes);
    assertEquals(0, frame.saveFailures);
    assertFalse(frame.hasPendingBatchAnalysisSave());
    try (var files = Files.list(sourceDirectory)) {
      List<Path> saved = files.toList();
      assertEquals(1, saved.size());
      String sgf = Files.readString(saved.get(0));
      assertTrue(sgf.contains("current result"));
      assertFalse(sgf.contains("edited after capture"));
    }
  }

  @ParameterizedTest
  @CsvSource({
      "board,false", "history,false", "files,false", "index,false", "source,false", "frame,false",
      "board,true", "history,true", "files,true", "index,true", "source,true", "frame,true"
  })
  void lateBatchSaveCannotAffectAReplacement(String changed, boolean failWrite) throws Exception {
    AtomicInteger saved = new AtomicInteger();
    try (BlockedSave ignored = new BlockedSave()) {
      SwingUtilities.invokeAndWait(() -> frame.saveBatchAnalysisResult(saved::incrementAndGet));
      if (failWrite) blockOutputDirectory();
      SwingUtilities.invokeAndWait(() -> {
        switch (changed) {
          case "board":
            try {
              Board replacement = allocate(Board.class);
              replacement.setHistory(Lizzie.board.getHistory());
              Lizzie.board = replacement;
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
            break;
          case "history":
            Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5]C[replacement];B[ee])", true));
            break;
          case "files":
            frame.Batchfiles = new ArrayList<>(frame.Batchfiles);
            break;
          case "index":
            frame.BatchAnaNum++;
            break;
          case "source":
            frame.Batchfiles.set(0, sourceDirectory.resolve("replacement.sgf").toFile());
            break;
          case "frame":
            try {
              Lizzie.frame = allocate(RecordingFrame.class);
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
            break;
          default:
            fail("unknown replacement");
        }
        frame.isBatchAnalysisMode = true;
      });
    }

    assertEquals(0, saved.get());
    assertEquals(0, frame.saveFailures, "an obsolete failure cannot pause the replacement");
    assertTrue(frame.isBatchAnalysisMode);
    assertFalse(frame.hasPendingBatchAnalysisSave());
    if (failWrite) {
      assertEquals("unrelated file", Files.readString(sourceDirectory));
    } else {
      try (var files = Files.list(sourceDirectory)) {
        List<Path> output = files.toList();
        assertEquals(1, output.size());
        assertTrue(Files.readString(output.get(0)).contains("current result"));
      }
    }
  }

  @Test
  void discardedInFlightSaveCannotCompleteANewRequestWithTheSameGameIdentity() throws Exception {
    AtomicInteger oldSaved = new AtomicInteger();
    AtomicInteger newSaved = new AtomicInteger();
    try (BlockedSave ignored = new BlockedSave()) {
      SwingUtilities.invokeAndWait(() -> {
        frame.isBatchAnalysisMode = true;
        frame.saveBatchAnalysisResult(oldSaved::incrementAndGet);
        frame.discardPendingBatchAnalysisSave();
        frame.isBatchAnalysisMode = false;
        Lizzie.board.getHistory().getStart().getData().comment = "new request";
        frame.saveBatchAnalysisResult(newSaved::incrementAndGet);
      });
    }
    assertEquals(0, oldSaved.get());
    assertEquals(1, newSaved.get());
    assertFalse(frame.isBatchAnalysisMode);
    assertFalse(frame.hasPendingBatchAnalysisSave());
    try (var files = Files.list(sourceDirectory)) {
      List<Path> output = files.toList();
      assertFalse(output.isEmpty());
      assertTrue(output.stream().anyMatch(path -> {
        try {
          return Files.readString(path).contains("new request");
        } catch (IOException failure) {
          throw new AssertionError(failure);
        }
      }));
    }
  }

  @Test
  void failedWriteRetryUsesTheOriginalSnapshotAfterTheLiveGameChanges() throws Exception {
    AtomicInteger saved = new AtomicInteger();
    blockOutputDirectory();
    SwingUtilities.invokeAndWait(() -> frame.saveBatchAnalysisResult(saved::incrementAndGet));
    awaitSaves();
    assertEquals(1, frame.saveFailures);
    SwingUtilities.invokeAndWait(
        () -> Lizzie.board.getHistory().getStart().getData().comment = "edited after failure");
    restoreOutputDirectory();
    SwingUtilities.invokeAndWait(() -> assertTrue(frame.retryBatchAnalysisSave()));
    awaitSaves();
    assertEquals(1, saved.get());
    try (var files = Files.list(sourceDirectory)) {
      List<Path> output = files.toList();
      assertEquals(1, output.size());
      String sgf = Files.readString(output.get(0));
      assertTrue(sgf.contains("current result"));
      assertFalse(sgf.contains("edited after failure"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void singleAutoAnalysisQueuesAFrozenResultAndSuppressesAReplacedGamesNotice(boolean replaceGame)
      throws Exception {
    frame.isBatchAna = false;
    set(BottomToolbar.class, toolbar, "autoAnalysisBatch", null);
    toolbar.chkAnaAutoSave.setSelected(true);
    Lizzie.leelaz.autoAnalysed = true;
    LizzieFrame.curFile = sourceDirectory.resolve("single.sgf").toFile();
    VisibleMessage message = (VisibleMessage) toolbar.msg;
    try (BlockedSave ignored = new BlockedSave()) {
      Lizzie.config.isAutoAna = true;
      SwingUtilities.invokeAndWait(() -> toolbar.stopAutoAna(false, false));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(0, message.visibilityChecks, "success notice must wait for the actual write");
        Lizzie.board.getHistory().getStart().getData().comment = "edited after capture";
        if (replaceGame) {
          Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5]C[replacement])", true));
        }
      });
      try (var files = Files.list(sourceDirectory)) {
        assertEquals(0, files.count());
      }
    }
    assertEquals(replaceGame ? 0 : 1, message.visibilityChecks);
    try (var files = Files.list(sourceDirectory)) {
      List<Path> output = files.toList();
      assertEquals(1, output.size());
      assertTrue(output.get(0).getFileName().toString().startsWith("single_"));
      String sgf = Files.readString(output.get(0));
      assertTrue(sgf.contains("current result"));
      assertFalse(sgf.contains("edited after capture"));
      assertFalse(sgf.contains("replacement"));
    }
  }

  @Test
  void allSupportedLocalesOfferAnExplicitSaveRetry() {
    for (String suffix : List.of("", "en_US", "ko", "zh_CN", "zh_TW", "zh_HK", "ja_JP", "th_TH")) {
      ResourceBundle resources =
          ResourceBundle.getBundle("l10n.DisplayStrings",
              java.util.Locale.forLanguageTag(suffix.replace('_', '-')));
      assertFalse(resources.getString("BatchAnalysis.retrySave").isBlank());
      assertFalse(resources.getString("BatchAnalysis.saveFailed").isBlank());
    }
  }

  private void blockOutputDirectory() throws Exception {
    Files.delete(sourceDirectory);
    Files.writeString(sourceDirectory, "unrelated file");
  }

  private void restoreOutputDirectory() throws Exception {
    Files.delete(sourceDirectory);
    Files.createDirectory(sourceDirectory);
  }

  private static void awaitSaves() throws Exception {
    SgfSaveCoordinator.pendingSaves().handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
    SwingUtilities.invokeAndWait(() -> {});
  }

  private static final class BlockedSave implements AutoCloseable {
    private final CountDownLatch release = new CountDownLatch(1);
    private final CompletableFuture<Void> completion;

    private BlockedSave() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      completion = SgfSaveCoordinator.submitSave(
          () -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            started.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Blocked writer timed out");
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new IOException(failure);
            }
            return null;
          }, () -> true, ignored -> {}, failure -> fail(failure));
      assertTrue(started.await(5, TimeUnit.SECONDS));
    }

    @Override
    public void close() throws Exception {
      release.countDown();
      completion.get(5, TimeUnit.SECONDS);
      awaitSaves();
    }
  }

  private static final class RecordingFrame extends LizzieFrame {
    private int nextLoads;
    private int finishes;
    private int saveFailures;

    @Override
    void loadNextBatchAutoAnalysis(BatchAutoAnalysis batch, Runnable loaded) {
      nextLoads++;
    }

    @Override
    void endBatchAutoAnalysis(BatchAutoAnalysis batch) {
      finishes++;
      super.endBatchAutoAnalysis(batch);
    }

    @Override
    void showBatchAnalysisSaveFailure(File source, Exception failure) {
      saveFailures++;
    }

    @Override
    public void addInput(boolean alsoIndependent) {}
  }

  private static final class VisibleMessage extends Message {
    private int visibilityChecks;

    @Override
    public boolean isVisible() {
      visibilityChecks++;
      return true;
    }
  }

  private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
