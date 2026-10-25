package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Zobrist;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

/** Import completion owns navigation; an offline completion never confirms an engine position. */
class KifuOfflineIdentityTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void offlineCompletionUnlocksNavigationWithoutResumingAnalysis(boolean paused) throws Exception {
    try (Fixture f = new Fixture(directory)) {
      assertNull(f.engine.engineIncarnationToken());
      assertFalse(f.engine.isStarted());
      onEdt(
          () -> {
            set(f.frame, "userAnalysisPaused", paused);
            f.submit(f.generation);
          });
      f.completePending();

      f.assertOfflineCompletion();
      assertEquals(
          paused, f.frame.isUserAnalysisPaused(), "completion must preserve the pause state");
    }
  }

  @Test
  void ordinaryOfflineImportPreservesUnsupportedRulesWithoutPrompting() throws Exception {
    try (Fixture f = new Fixture(directory)) {
      onEdt(
          () -> {
            f.rules = Lizzie.board.getHistory().publishExternalRules("unsupported-test-rules");
            assertEquals(BoardHistoryList.SessionRulesKind.INVALID, f.rules.kind());
            f.submit(f.generation);
          });
      f.completePending();

      assertSame(f.rules, Lizzie.board.getHistory().captureSessionRules());
      assertEquals("unsupported-test-rules", f.rules.rawDeclaration());
      f.assertOfflineCompletion();
    }
  }

  @Test
  void missingPrimaryGenerationNeverEntersTheSyncQueue() throws Exception {
    try (Fixture f = new Fixture(directory)) {
      onEdt(
          () -> {
            Lizzie.setPrimaryEngine(new Leelaz(""));
            long missingGeneration = Lizzie.capturePrimaryEngineGeneration(f.engine);
            assertEquals(-1L, missingGeneration);
            f.submit(missingGeneration);
          });

      assertNull(f.executor.pending, "two missing generations must not admit an obsolete primary");
      f.assertRetiredCompletion();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void replacingOrReselectingPrimaryRetiresItsQueuedOfflineCompletion(boolean sameObject)
      throws Exception {
    try (Fixture f = new Fixture(directory)) {
      onEdt(() -> f.submit(f.generation));
      onEdt(
          () -> {
            f.executor.runPending();
            // Retire authority after worker success, before its queued EDT completion.
            Lizzie.setPrimaryEngine(sameObject ? f.engine : new Leelaz(""));
          });
      onEdt(() -> {});

      f.assertRetiredCompletion();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void changedGameOrRulesCannotBeUnlockedByQueuedOfflineCompletion(boolean replaceGame)
      throws Exception {
    try (Fixture f = new Fixture(directory)) {
      onEdt(() -> f.submit(f.generation));
      onEdt(
          () -> {
            f.executor.runPending();
            if (replaceGame) Lizzie.board = new Board();
            else Lizzie.board.getHistory().publishExternalRules("Japanese");
            set(f.frame, "pendingKifuEngineSyncRoot", Lizzie.board.getHistory().getStart());
          });
      onEdt(() -> {});

      f.assertRetiredCompletion();
    }
  }

  @Test
  void bindingTheFirstReaderRetiresAnAlreadyQueuedOfflineCompletion() throws Exception {
    try (Fixture f = new Fixture(directory)) {
      onEdt(() -> f.submit(f.generation));
      onEdt(
          () -> {
            f.executor.runPending();
            try (var transport =
                ExactSnapshotRestoreProtocolFixture.install(
                    f.engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success())) {
              assertNotNull(f.engine.engineIncarnationToken());
              assertTrue(transport.commands().isEmpty());
            }
          });
      onEdt(() -> {});

      f.assertRetiredCompletion();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Config previousConfig = Lizzie.config;
    final Board previousBoard = Lizzie.board;
    final Leelaz previousPrimary = Lizzie.leelaz;
    final Leelaz previousSecondary = Lizzie.leelaz2;
    final LizzieFrame previousFrame = Lizzie.frame;
    final EngineManager previousManager = Lizzie.engineManager;
    final GtpConsolePane previousConsole = Lizzie.gtpConsole;
    final int previousWidth = Board.boardWidth;
    final int previousHeight = Board.boardHeight;
    final boolean previousEmpty = EngineManager.isEmpty;
    final boolean previousCanGo = LizzieFrame.canGoAfterload;
    final CapturingExecutor executor = new CapturingExecutor();
    final RecordingFrame frame = allocate(RecordingFrame.class);
    EmptyPlaceholder engine;
    BoardHistoryNode root;
    BoardHistoryList.SessionRulesTarget rules;
    long generation;
    int analysisResumes;

    Fixture(Path directory) throws Exception {
      onEdt(
          () -> {
            Lizzie.config = ConfigTestHelper.createForTests(directory);
            Lizzie.frame = null;
            Lizzie.engineManager = null;
            Lizzie.gtpConsole = null;
            Lizzie.setPrimaryEngine(null);
            Lizzie.leelaz2 = null;
            Board.boardWidth = Board.boardHeight = 19;
            Zobrist.init();
            Lizzie.board = new Board();
            root = Lizzie.board.getHistory().getStart();
            rules = Lizzie.board.getHistory().publishExternalRules("Chinese");
            Lizzie.board.requireEngineAlignment();
            engine = new EmptyPlaceholder();
            engine.isKatago = true;
            engine.isLoaded = true;
            Lizzie.setPrimaryEngine(engine);
            generation = Lizzie.capturePrimaryEngineGeneration(engine);
            assertTrue(generation >= 0);
            EngineManager.isEmpty = true;
            Lizzie.frame = frame;
            set(frame, "kifuEngineSyncCoordinator", new KifuEngineSyncCoordinator(executor));
            set(frame, "pendingKifuEngineSyncRoot", root);
            LizzieFrame.canGoAfterload = false;
          });
    }

    void submit(long capturedGeneration) throws Exception {
      Method submit =
          LizzieFrame.class.getDeclaredMethod(
              "submitKifuEngineSync",
              BoardHistoryNode.class,
              BoardHistoryList.SessionRulesTarget.class,
              Leelaz.class,
              long.class,
              Leelaz.class,
              int.class,
              Runnable.class);
      submit.setAccessible(true);
      submit.invoke(
          frame,
          root,
          rules,
          engine,
          capturedGeneration,
          null,
          0,
          (Runnable) () -> analysisResumes++);
    }

    void completePending() throws Exception {
      onEdt(executor::runPending);
      onEdt(() -> {});
    }

    void assertOfflineCompletion() throws Exception {
      assertNull(field(frame, "pendingKifuEngineSyncRoot"));
      assertTrue(LizzieFrame.canGoAfterload);
      assertTrue(
          Lizzie.board.isEngineAlignmentPending(), "offline browsing is not a confirmed restore");
      assertEquals(1, frame.scheduledResumes);
      assertEquals(0, analysisResumes);
      assertEquals(0, frame.rulesPrompts);
      assertNoEngineWork();
    }

    void assertRetiredCompletion() throws Exception {
      assertSame(Lizzie.board.getHistory().getStart(), field(frame, "pendingKifuEngineSyncRoot"));
      assertFalse(LizzieFrame.canGoAfterload);
      assertEquals(0, frame.scheduledResumes);
      assertEquals(0, analysisResumes);
      assertEquals(0, frame.rulesPrompts);
      assertNoEngineWork();
    }

    void assertNoEngineWork() {
      assertEquals(0, engine.commands);
      assertEquals(0, engine.analysisStarts);
      assertEquals(0, engine.engineStarts);
      assertFalse(engine.isStarted());
      assertEquals(0, executor.retries);
    }

    @Override
    public void close() throws Exception {
      onEdt(frame::shutdownKifuEngineSyncCoordinator);
      onEdt(
          () -> {
            Lizzie.config = previousConfig;
            Lizzie.board = previousBoard;
            Lizzie.setPrimaryEngine(previousPrimary);
            Lizzie.leelaz2 = previousSecondary;
            Lizzie.frame = previousFrame;
            Lizzie.engineManager = previousManager;
            Lizzie.gtpConsole = previousConsole;
            Board.boardWidth = previousWidth;
            Board.boardHeight = previousHeight;
            Zobrist.init();
            EngineManager.isEmpty = previousEmpty;
            LizzieFrame.canGoAfterload = previousCanGo;
          });
    }
  }

  private static final class EmptyPlaceholder extends Leelaz {
    int commands;
    int analysisStarts;
    int engineStarts;

    EmptyPlaceholder() throws IOException {
      super("");
    }

    @Override
    public void sendCommand(String command) {
      commands++;
    }

    @Override
    public void ponder() {
      analysisStarts++;
    }

    @Override
    public void startEngine(int index) {
      engineStarts++;
    }
  }

  private static final class RecordingFrame extends LizzieFrame {
    int scheduledResumes;
    int rulesPrompts;

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      scheduledResumes++;
      if (action != null) action.run();
    }

    @Override
    protected int showImportedRulesFailurePrompt(String message) {
      rulesPrompts++;
      return JOptionPane.NO_OPTION;
    }
  }

  private static final class CapturingExecutor extends ScheduledThreadPoolExecutor {
    Runnable pending;
    int retries;

    CapturingExecutor() {
      super(1);
    }

    @Override
    public void execute(Runnable command) {
      pending = command;
    }

    void runPending() {
      Runnable command = pending;
      assertNotNull(command, "the current import must submit a synchronization request");
      pending = null;
      command.run();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      retries++;
      throw new AssertionError("An offline import must not wait for an engine retry");
    }
  }

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }

  private static void onEdt(CheckedAction action) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            action.run();
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
