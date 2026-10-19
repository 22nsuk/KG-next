package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.ForegroundRestoreResult;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.NodeInfo;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Zobrist;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Quick-batch caller ownership, actual save retry, and controlled load/synchronization boundaries. */
class QuickBatchFlowTest {
  @TempDir Path directory;
  private Config previousConfig;
  private Board previousBoard;
  private Leelaz previousEngine;
  private Leelaz previousSecondary;
  private EngineManager previousManager;
  private LizzieFrame previousFrame;
  private BottomToolbar previousToolbar;
  private File previousFile;
  private boolean previousEmpty;
  private boolean previousCanGoAfterLoad;
  private int previousWidth;
  private int previousHeight;
  private RecordingFrame frame;
  private Path sourceDirectory;

  @BeforeEach
  void prepare() throws Exception {
    previousConfig = Lizzie.config;
    previousBoard = Lizzie.board;
    previousEngine = Lizzie.leelaz;
    previousSecondary = Lizzie.leelaz2;
    previousManager = Lizzie.engineManager;
    previousFrame = Lizzie.frame;
    previousToolbar = LizzieFrame.toolbar;
    previousFile = LizzieFrame.curFile;
    previousEmpty = EngineManager.isEmpty;
    previousCanGoAfterLoad = LizzieFrame.canGoAfterload;
    previousWidth = Board.boardWidth;
    previousHeight = Board.boardHeight;
    Board.boardWidth = Board.boardHeight = 5;
    Zobrist.init();
    Lizzie.config = ConfigTestHelper.createForTests(directory);
    Lizzie.config.appendWinrateToComment = false;
    Lizzie.frame = null;
    Lizzie.leelaz = new Leelaz("");
    Lizzie.leelaz2 = null;
    Lizzie.engineManager = null;
    EngineManager.isEmpty = true;
    Lizzie.board = allocate(Board.class);
    Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5]C[current result];B[aa];W[bb])", true));
    frame = allocate(RecordingFrame.class);
    frame.acceptLoad = true;
    frame.resumeScheduled = new CountDownLatch(1);
    frame.rulesFailurePrompt = new CountDownLatch(1);
    Lizzie.frame = frame;
    BottomToolbar toolbar = allocate(BottomToolbar.class);
    toolbar.chkAnaAutoSave = new JCheckBox();
    LizzieFrame.toolbar = toolbar;
    sourceDirectory = Files.createDirectory(directory.resolve("source"));
    frame.Batchfiles = new ArrayList<>(List.of(
        sourceDirectory.resolve("first.sgf").toFile(),
        sourceDirectory.resolve("second.sgf").toFile()));
    frame.isBatchAna = true;
    LizzieFrame.curFile = frame.Batchfiles.get(0);
  }

  @AfterEach
  void restore() throws Exception {
    SwingUtilities.invokeAndWait(frame::shutdownKifuEngineSyncCoordinator);
    SwingUtilities.invokeAndWait(() -> {});
    Lizzie.config = previousConfig;
    Lizzie.board = previousBoard;
    Lizzie.leelaz = previousEngine;
    Lizzie.leelaz2 = previousSecondary;
    Lizzie.engineManager = previousManager;
    Lizzie.frame = previousFrame;
    LizzieFrame.toolbar = previousToolbar;
    LizzieFrame.curFile = previousFile;
    EngineManager.isEmpty = previousEmpty;
    LizzieFrame.canGoAfterload = previousCanGoAfterLoad;
    Board.boardWidth = previousWidth;
    Board.boardHeight = previousHeight;
    Zobrist.init();
  }

  @Test
  void acceptedLoadPublishesItsIndexOnlyOnSuccessAndStartsOnlyAfterEngineSynchronization()
      throws Exception {
    AtomicInteger starts = new AtomicInteger();
    var oldHistory = Lizzie.board.getHistory();
    SwingUtilities.invokeAndWait(() -> frame.loadFlashBatchFile(1, starts::incrementAndGet));
    assertEquals(frame.Batchfiles.get(1), frame.attempted);
    assertEquals(0, frame.BatchAnaNum);
    assertSame(oldHistory, Lizzie.board.getHistory());
    assertEquals(0, starts.get());

    completeLoad();
    assertEquals(1, frame.BatchAnaNum);
    assertEquals(frame.Batchfiles.get(1), LizzieFrame.curFile);
    assertEquals(0, starts.get(), "parsed games must still wait for foreground synchronization");
    SwingUtilities.invokeAndWait(frame.engineReady);
    SwingUtilities.invokeAndWait(frame.engineReady);
    assertEquals(1, starts.get());
  }

  @Test
  void failedLoadPreservesCurrentResultAndQueuePosition() throws Exception {
    frame.acceptLoad = false;
    frame.isBatchAnalysisMode = true;
    var files = frame.Batchfiles;
    var history = Lizzie.board.getHistory();
    AtomicInteger starts = new AtomicInteger();

    SwingUtilities.invokeAndWait(() -> frame.loadFlashBatchFile(1, starts::incrementAndGet));

    assertEquals(0, starts.get());
    assertSame(files, frame.Batchfiles);
    assertEquals(0, frame.BatchAnaNum);
    assertSame(history, Lizzie.board.getHistory());
    assertEquals(files.get(0), LizzieFrame.curFile);
    assertFalse(frame.isBatchAnalysisMode);
    assertFalse(frame.isBatchAna);
  }

  @Test
  void cancelledLoadDiscardsItsLateEngineReadySignal() throws Exception {
    frame.isBatchAnalysisMode = true;
    AtomicInteger starts = new AtomicInteger();
    SwingUtilities.invokeAndWait(() -> frame.loadFlashBatchFile(1, starts::incrementAndGet));
    completeLoad();
    SwingUtilities.invokeAndWait(frame::stopFlashBatchAnalysis);
    SwingUtilities.invokeAndWait(frame.engineReady);

    assertEquals(0, starts.get());
    assertEquals(1, frame.BatchAnaNum);
    assertFalse(frame.isBatchAnalysisMode);
  }

  @Test
  void replacedQueueRejectsALateSuccessfulLoadPublication() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    SwingUtilities.invokeAndWait(() -> frame.loadFlashBatchFile(1, starts::incrementAndGet));
    var replacement = new ArrayList<>(frame.Batchfiles);
    frame.Batchfiles = replacement;
    completeLoad();
    SwingUtilities.invokeAndWait(frame.engineReady);

    assertSame(replacement, frame.Batchfiles);
    assertEquals(0, frame.BatchAnaNum);
    assertEquals(0, starts.get());
  }

  @Test
  void engineSynchronizationFailurePausesOnlyItsLoadedBatch() throws Exception {
    frame.isBatchAnalysisMode = true;
    AtomicInteger starts = new AtomicInteger();
    SwingUtilities.invokeAndWait(() -> frame.loadFlashBatchFile(1, starts::incrementAndGet));
    completeLoad();
    invokeFrame("failBatchKifuLoad", new Class<?>[] {BoardHistoryNode.class},
        Lizzie.board.getHistory().getStart());
    SwingUtilities.invokeAndWait(frame.engineReady);

    assertEquals(0, starts.get());
    assertEquals(1, frame.BatchAnaNum);
    assertEquals(2, frame.Batchfiles.size());
    assertFalse(frame.isBatchAnalysisMode);
    assertFalse(frame.isBatchAna);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void loadedBatchBecomesReadyWhenForegroundSynchronizationIsNotRequired(boolean primaryAbsent)
      throws Exception {
    if (primaryAbsent) Lizzie.leelaz = null;
    EngineManager.isEmpty = !primaryAbsent;
    frame.isBatchAnalysisMode = true;
    AtomicInteger starts = new AtomicInteger();
    SwingUtilities.invokeAndWait(() -> frame.loadFlashBatchFile(1, () -> {
      assertTrue(SwingUtilities.isEventDispatchThread());
      starts.incrementAndGet();
    }));
    completeLoad();

    // Exercise the real coordinator's COMPLETE path and the load-ready action's final guard.
    invokeFrame("scheduleKifuEngineReadyAfterLoad", new Class<?>[] {Runnable.class}, frame.engineReady);
    assertTrue(frame.resumeScheduled.await(2, TimeUnit.SECONDS));

    assertEquals(1, starts.get());
    assertEquals(1, frame.BatchAnaNum);
    assertTrue(frame.isBatchAnalysisMode);
  }

  @Test
  void ordinaryResumeActionStillRequiresAConfirmedForegroundPosition() throws Exception {
    AtomicInteger resumes = new AtomicInteger();
    try (var transport = ExactSnapshotRestoreProtocolFixture.install(
        Lizzie.leelaz, command -> ExactSnapshotRestoreProtocolFixture.Response.success())) {
      invokeFrame("scheduleEngineSyncAndResumeAfterKifuLoad",
          new Class<?>[] {int.class, Runnable.class}, 0, (Runnable) resumes::incrementAndGet);
      assertTrue(frame.resumeScheduled.await(2, TimeUnit.SECONDS));
      assertTrue(transport.commands().isEmpty());
    }

    assertEquals(0, resumes.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void changedEmptyPrimaryDiscardsItsAlreadyQueuedReadiness(boolean installIncarnation)
      throws Exception {
    AtomicInteger starts = new AtomicInteger();
    SwingUtilities.invokeAndWait(() -> frame.loadFlashBatchFile(1, starts::incrementAndGet));
    completeLoad();
    frame.holdResume = true;
    invokeFrame("scheduleKifuEngineReadyAfterLoad", new Class<?>[] {Runnable.class}, frame.engineReady);
    assertTrue(frame.resumeScheduled.await(2, TimeUnit.SECONDS));

    try (var transport = installIncarnation
        ? ExactSnapshotRestoreProtocolFixture.install(
            Lizzie.leelaz, command -> ExactSnapshotRestoreProtocolFixture.Response.success())
        : null) {
      if (!installIncarnation) Lizzie.leelaz = new Leelaz("");
      SwingUtilities.invokeAndWait(frame.scheduledResume);
      assertEquals(0, starts.get());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedRulesSynchronizationNeverSignalsBatchReady(boolean invalidWithoutEngine)
      throws Exception {
    frame.isBatchAnalysisMode = true;
    AtomicInteger starts = new AtomicInteger();
    SwingUtilities.invokeAndWait(() -> frame.loadFlashBatchFile(1, starts::incrementAndGet));
    completeLoad();
    EngineManager.isEmpty = invalidWithoutEngine;
    Lizzie.leelaz.isDownWithError = true;
    Lizzie.board.getHistory().publishExternalRules(invalidWithoutEngine ? "invalid-rules" : "Chinese");

    try (var transport = invalidWithoutEngine ? null
        : ExactSnapshotRestoreProtocolFixture.install(
            Lizzie.leelaz, command -> ExactSnapshotRestoreProtocolFixture.Response.error("unavailable"))) {
      invokeFrame("scheduleKifuEngineReadyAfterLoad", new Class<?>[] {Runnable.class}, frame.engineReady);
      assertTrue(frame.rulesFailurePrompt.await(2, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {});
      if (transport != null) assertTrue(transport.commands().isEmpty());
    }

    assertEquals(0, starts.get());
    assertEquals(1, frame.BatchAnaNum);
    assertFalse(frame.isBatchAnalysisMode);
    assertFalse(frame.isBatchAna);
  }

  @Test
  void completedResultCanBeSavedAndAdvancedAfterItsWorkerIsReplaced() throws Exception {
    RecordingEngine completed = startRequest();
    Files.delete(sourceDirectory);
    Files.writeString(sourceDirectory, "blocking file");
    SwingUtilities.invokeAndWait(() -> completed.completion.accept(ForegroundRestoreResult.SUCCEEDED));

    assertTrue(frame.hasPendingBatchAnalysisSave());
    assertFalse(frame.isBatchAnalysisMode);
    assertFalse(((RecordingWait) completed.waitFrame).visible,
        "a failed save must leave the batch retry controls reachable");
    assertNull(frame.attempted);
    RecordingEngine replacement = allocate(RecordingEngine.class);
    frame.analysisEngine = replacement;
    Files.delete(sourceDirectory);
    Files.createDirectory(sourceDirectory);

    SwingUtilities.invokeAndWait(frame::openFlashBatchAnalysisSettings);

    assertFalse(frame.hasPendingBatchAnalysisSave());
    assertTrue(frame.isBatchAnalysisMode);
    assertSame(replacement, frame.analysisEngine);
    assertEquals(frame.Batchfiles.get(1), frame.attempted);
    assertEquals(0, frame.BatchAnaNum, "next index awaits actual load success");
    try (var outputs = Files.list(sourceDirectory)) {
      List<Path> saved = outputs.toList();
      assertEquals(1, saved.size());
      assertTrue(Files.readString(saved.get(0)).contains("current result"));
    }
  }

  @Test
  void cancelledRequestCannotSaveOrAdvanceFromItsAlreadyQueuedCompletion() throws Exception {
    RecordingEngine engine = startRequest();
    Consumer<ForegroundRestoreResult> queued = engine.completion;
    SwingUtilities.invokeAndWait(frame::stopFlashBatchAnalysis);
    SwingUtilities.invokeAndWait(() -> queued.accept(ForegroundRestoreResult.SUCCEEDED));

    assertNull(frame.attempted);
    assertFalse(frame.hasPendingBatchAnalysisSave());
    assertEquals(0, frame.BatchAnaNum);
    try (var outputs = Files.list(sourceDirectory)) {
      assertEquals(0, outputs.count());
    }
  }

  @Test
  void replacedCurrentSourceRejectsItsAlreadyQueuedCompletion() throws Exception {
    RecordingEngine engine = startRequest();
    File replacement = sourceDirectory.resolve("replacement.sgf").toFile();
    frame.Batchfiles.set(0, replacement);

    SwingUtilities.invokeAndWait(() -> engine.completion.accept(ForegroundRestoreResult.SUCCEEDED));

    assertEquals(replacement, frame.Batchfiles.get(0));
    assertNoSavedResultOrAdvance();
  }

  @Test
  void clearedQueueRejectsItsAlreadyQueuedCompletionWithoutAnIndexFailure() throws Exception {
    RecordingEngine engine = startRequest();
    frame.Batchfiles.clear();

    SwingUtilities.invokeAndWait(() -> engine.completion.accept(ForegroundRestoreResult.SUCCEEDED));

    assertTrue(frame.Batchfiles.isEmpty());
    assertNoSavedResultOrAdvance();
  }

  @Test
  void stoppedBatchCanRetryItsFailedSaveWithoutResumingOrAdvancing() throws Exception {
    RecordingEngine engine = startRequest();
    blockOutputDirectory();
    SwingUtilities.invokeAndWait(() -> engine.completion.accept(ForegroundRestoreResult.SUCCEEDED));
    assertTrue(frame.hasPendingBatchAnalysisSave());

    SwingUtilities.invokeAndWait(frame::stopFlashBatchAnalysis);
    assertTrue(frame.hasPendingBatchAnalysisSave(), "stopping must retain the unsaved result");
    restoreOutputDirectory();
    SwingUtilities.invokeAndWait(frame::openFlashBatchAnalysisSettings);

    assertFalse(frame.hasPendingBatchAnalysisSave());
    assertFalse(frame.isBatchAnalysisMode);
    assertNull(frame.attempted);
    assertEquals(0, frame.BatchAnaNum);
    assertEquals(2, frame.Batchfiles.size());
    assertTrue(savedPayload().contains("current result"));
  }

  @Test
  void quickBatchSaveIncludesAiScoreAndBlunderSummaryOnlyOnceAcrossRetries() throws Exception {
    Lizzie.board.setHistory(SGFParser.parseSgf(
        "(;SZ[5]C[current result]" + ";B[];W[]".repeat(11) + ")", true));
    BoardHistoryNode node = Lizzie.board.getHistory().getEnd();
    while (node.previous().isPresent()) {
      node = node.previous().orElseThrow();
      if (node.getData().moveNumber == 0) continue;
      NodeInfo info = node.nodeInfoMain;
      info.analyzed = true;
      info.moveNum = node.getData().moveNumber;
      info.isBlack = info.moveNum % 2 == 1;
      info.percentsMatch = 0.75;
      info.diffWinrate = info.moveNum / 100.0;
    }
    RecordingEngine engine = startRequest();
    Lizzie.leelaz.autoAnalysed = true;
    blockOutputDirectory();

    SwingUtilities.invokeAndWait(() -> engine.completion.accept(ForegroundRestoreResult.SUCCEEDED));

    String summary = Lizzie.board.getHistory().getStart().getData().comment;
    for (String key : List.of("SGFParse.blackAiScore", "SGFParse.whiteAiScore",
        "SGFParse.blackTop10", "SGFParse.whiteTop10")) {
      assertTrue(summary.contains(Lizzie.resourceBundle.getString(key)), summary);
    }
    assertTrue(frame.hasPendingBatchAnalysisSave());
    SwingUtilities.invokeAndWait(frame::openFlashBatchAnalysisSettings);
    restoreOutputDirectory();
    SwingUtilities.invokeAndWait(frame::openFlashBatchAnalysisSettings);

    assertEquals(summary, Lizzie.board.getHistory().getStart().getData().comment);
    assertTrue(savedPayload().contains(summary), savedPayload());
    assertEquals(frame.Batchfiles.get(1), frame.attempted);
  }

  private void assertNoSavedResultOrAdvance() throws IOException {
    assertNull(frame.attempted);
    assertFalse(frame.hasPendingBatchAnalysisSave());
    assertEquals(0, frame.BatchAnaNum);
    try (var outputs = Files.list(sourceDirectory)) {
      assertEquals(0, outputs.count());
    }
  }

  private void blockOutputDirectory() throws IOException {
    Files.delete(sourceDirectory);
    Files.writeString(sourceDirectory, "blocking file");
  }

  private void restoreOutputDirectory() throws IOException {
    Files.delete(sourceDirectory);
    Files.createDirectory(sourceDirectory);
  }

  private String savedPayload() throws IOException {
    try (var outputs = Files.list(sourceDirectory)) {
      List<Path> saved = outputs.toList();
      assertEquals(1, saved.size());
      return Files.readString(saved.get(0));
    }
  }

  private void completeLoad() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5]C[next game];B[ee])", true));
      frame.loaded.run();
    });
  }

  private RecordingEngine startRequest() throws Exception {
    RecordingEngine engine = allocate(RecordingEngine.class);
    engine.started = new CountDownLatch(1);
    RecordingWait wait = allocate(RecordingWait.class);
    wait.visible = true;
    engine.waitFrame = wait;
    frame.analysisEngine = engine;
    SwingUtilities.invokeAndWait(() -> frame.flashAnalyzeGameBatch(1, -1, false));
    invokeFrame("startFlashAnalyzeRequestsInBackground",
        new Class<?>[] {AnalysisEngine.class, boolean.class, boolean.class}, engine, false, false);
    assertTrue(engine.started.await(2, TimeUnit.SECONDS));
    return engine;
  }

  private void invokeFrame(String name, Class<?>[] types, Object... args) throws Exception {
    Method method = LizzieFrame.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    SwingUtilities.invokeAndWait(() -> {
      try {
        method.invoke(frame, args);
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    });
  }

  private static final class RecordingFrame extends LizzieFrame {
    private boolean acceptLoad;
    private File attempted;
    private Runnable loaded;
    private Runnable engineReady;
    private CountDownLatch resumeScheduled;
    private CountDownLatch rulesFailurePrompt;
    private boolean holdResume;
    private Runnable scheduledResume;

    @Override
    boolean loadFile(File file, boolean fromTemp, boolean showHint,
        Runnable afterSuccessfulLoad, Runnable afterEngineReady) {
      attempted = file;
      loaded = afterSuccessfulLoad;
      engineReady = afterEngineReady;
      return acceptLoad;
    }

    @Override
    public void flashAnalyzeGame(boolean allGame, boolean allBranches) {}

    @Override
    void showBatchAnalysisSaveFailure(File source, Exception failure) {}

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      assertTrue(SwingUtilities.isEventDispatchThread());
      scheduledResume = action;
      if (!holdResume && action != null) action.run();
      resumeScheduled.countDown();
    }

    @Override
    protected int showImportedRulesFailurePrompt(String message) {
      rulesFailurePrompt.countDown();
      return JOptionPane.NO_OPTION;
    }
  }

  private static final class RecordingEngine extends AnalysisEngine {
    private Consumer<ForegroundRestoreResult> completion;
    private CountDownLatch started;

    private RecordingEngine() throws IOException {
      super(false);
    }

    @Override
    public void setCompletionCallback(Consumer<ForegroundRestoreResult> callback) {
      completion = callback;
    }

    @Override
    public void startRequest(int first, int last, boolean showProgress) {
      started.countDown();
    }

    @Override
    public void normalQuit() {}
  }

  private static final class RecordingWait extends WaitForAnalysis {
    private boolean visible;

    @Override
    public void setVisible(boolean visible) {
      this.visible = visible;
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
