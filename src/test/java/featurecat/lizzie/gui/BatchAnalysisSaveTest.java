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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
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
    assertEquals(2, frame.saveFailures);
    assertEquals(0, frame.nextLoads);
    assertEquals(0, frame.finishes);
    restoreOutputDirectory();
    SwingUtilities.invokeAndWait(toolbar::startAutoAna);

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
    assertFalse(frame.isBatchAnalysisMode);
    assertEquals(0, saved.get());
    assertTrue(frame.hasPendingBatchAnalysisSave());

    restoreOutputDirectory();
    SwingUtilities.invokeAndWait(() -> assertTrue(frame.retryBatchAnalysisSave()));
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
    @Override
    public boolean isVisible() {
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
