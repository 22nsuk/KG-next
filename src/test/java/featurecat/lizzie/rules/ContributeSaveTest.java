package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.ContributeEngine;
import featurecat.lizzie.analysis.ContributeGameInfo;
import featurecat.lizzie.analysis.ContributeMoveInfo;
import featurecat.lizzie.gui.SgfSaveCoordinator;
import featurecat.lizzie.gui.SgfSaveTestSupport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real contribution capture/write/completion; only watched-game rendering is substituted. */
class ContributeSaveTest {
  @TempDir Path directory;
  private RulesLayerTestHarness rules;
  private ProjectionEngine engine;
  private ArrayList<ContributeGameInfo> games;
  private Path outputDirectory;

  @BeforeEach
  void prepare() throws Exception {
    rules = RulesLayerTestHarness.open();
    engine = RulesLayerTestHarness.allocate(ProjectionEngine.class);
    games = new ArrayList<>();
    set(engine, "contributeGames", games);
    String stamp = directory.getFileName().toString();
    set(engine, "timeStamp", stamp);
    outputDirectory = Path.of("").toAbsolutePath().resolve("ContributeGames").resolve(stamp);
    assertFalse(Files.exists(outputDirectory));
    Lizzie.frame.contributeEngine = engine;
    engine.watchingGameIndex = 0;
  }

  @AfterEach
  void restore() throws Exception {
    try {
      SgfSaveTestSupport.awaitPending();
    } finally {
      rules.close();
      if (Files.isDirectory(outputDirectory)) {
        try (var files = Files.walk(outputDirectory)) {
          for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
      } else {
        Files.deleteIfExists(outputDirectory);
      }
    }
  }

  @Test
  void largeBatchWaitsForDiskAndRestoresTheWatchedGameWhileSavingFrozenSnapshots() throws Exception {
    for (int i = 0; i < 20; i++) games.add(game(i, true));
    watch(games.get(0));
    SwingUtilities.invokeAndWait(() -> Lizzie.board.goToMoveNumber(1));
    CompletableFuture<Void> batch;
    try (var blocked = SgfSaveTestSupport.blockWorker()) {
      SwingUtilities.invokeAndWait(engine::saveAllGames);
      batch = batchCompletion();
      SwingUtilities.invokeAndWait(() -> {
        engine.saveAllGames();
        assertSame(games.get(0), get(engine, "currentWatchGame"));
        assertEquals(1, Lizzie.board.getCurrentMovenumber());
        Lizzie.board.getHistory().getStart().getData().comment = "edited after capture";
      });
      assertSame(batch, batchCompletion(), "repeated Save All must share its active batch");
      assertFalse(batch.isDone());
      assertFalse(SgfSaveCoordinator.pendingSaves().isDone());
      assertFalse(Files.exists(outputDirectory), "directory creation belongs to the save worker");
      assertTrue(games.stream().noneMatch(game -> game.saved));
      blocked.release();
      batch.get(10, TimeUnit.SECONDS);
    }

    try (var files = Files.list(outputDirectory)) {
      List<Path> saved = files.toList();
      assertEquals(20, saved.size(), "batch size must not overflow the bounded worker queue");
      for (ContributeGameInfo game : games) {
        String sgf = Files.readString(target(game));
        assertTrue(sgf.contains("C[" + game.gameId + "]"), sgf);
        assertFalse(sgf.contains("edited after capture"), sgf);
        assertTrue(game.saved);
      }
    }
  }

  @Test
  void automaticSaveCoalescesPendingWritesAndPublishesSavedOnlyAfterCommit() throws Exception {
    ContributeGameInfo game = game(0, true);
    games.add(game);
    watch(game);
    try (var blocked = SgfSaveTestSupport.blockWorker()) {
      SwingUtilities.invokeAndWait(() -> saveOne(game));
      Object pending = get(engine, "pendingGameSaves");
      SwingUtilities.invokeAndWait(() -> {
        saveOne(game);
        Lizzie.board.getHistory().getStart().getData().comment = "later board";
      });
      assertEquals(1, ((Map<?, ?>) pending).size());
      assertFalse(game.saved);
      assertFalse(Files.exists(outputDirectory));
      blocked.release();
    }
    assertTrue(game.saved);
    String sgf = Files.readString(target(game));
    assertTrue(sgf.contains("C[game-0]"));
    assertFalse(sgf.contains("later board"));
  }

  @Test
  void failedBatchWriteDoesNotMarkTheGameSavedAndCanRetry() throws Exception {
    ContributeGameInfo game = game(0, true);
    games.add(game);
    watch(game);
    Files.createDirectories(outputDirectory.getParent());
    Files.writeString(outputDirectory, "unrelated file");

    SwingUtilities.invokeAndWait(engine::saveAllGames);
    assertThrows(ExecutionException.class, () -> batchCompletion().get(5, TimeUnit.SECONDS));
    SgfSaveTestSupport.awaitPending();
    assertFalse(game.saved);
    assertEquals("unrelated file", Files.readString(outputDirectory));

    Files.delete(outputDirectory);
    SwingUtilities.invokeAndWait(engine::saveAllGames);
    batchCompletion().get(5, TimeUnit.SECONDS);
    assertTrue(game.saved);
    assertTrue(Files.readString(target(game)).contains("C[game-0]"));
  }

  @Test
  void retiredSessionStillWritesItsSnapshotWithoutPublishingItsSavedState() throws Exception {
    ContributeGameInfo game = game(0, true);
    games.add(game);
    watch(game);
    ContributeEngine replacement = RulesLayerTestHarness.allocate(ContributeEngine.class);
    try (var blocked = SgfSaveTestSupport.blockWorker()) {
      SwingUtilities.invokeAndWait(engine::saveAllGames);
      CompletableFuture<Void> batch = batchCompletion();
      SwingUtilities.invokeAndWait(() -> Lizzie.frame.contributeEngine = replacement);
      blocked.release();
      batch.get(5, TimeUnit.SECONDS);
    }
    assertSame(replacement, Lizzie.frame.contributeEngine);
    assertFalse(game.saved);
    assertTrue(Files.readString(target(game)).contains("C[game-0]"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void queuedSaveFromRetiredSessionDoesNotReplayGamesOntoNewBoard(boolean batch) throws Exception {
    games.add(game(0, true));
    games.add(game(1, true));
    watch(games.get(0));
    ContributeEngine replacement = RulesLayerTestHarness.allocate(ContributeEngine.class);
    BoardHistoryList replacementHistory = SGFParser.parseSgf("(;SZ[5]C[new session])", true);

    SwingUtilities.invokeAndWait(() -> {
      FutureTask<Void> request = new FutureTask<>(() -> {
        // The real watch thread can request an automatic save while holding this monitor.
        synchronized (engine) {
          if (batch) engine.saveAllGames();
          else saveOne(games.get(1));
        }
        return null;
      });
      Thread caller = new Thread(request, "contribution-save-caller");
      caller.setDaemon(true);
      caller.start();
      try {
        request.get(5, TimeUnit.SECONDS);
      } catch (Exception failure) {
        throw new AssertionError("background save must not wait for the event thread", failure);
      }
      // Retire the old session before its queued capture gets an opportunity to run.
      Lizzie.frame.contributeEngine = replacement;
      Lizzie.board.setHistory(replacementHistory);
    });
    SgfSaveTestSupport.awaitPending();

    assertSame(replacementHistory, Lizzie.board.getHistory());
    assertSame(replacement, Lizzie.frame.contributeEngine);
    assertNull(get(engine, "allGamesSave"));
    assertNull(get(engine, "pendingGameSaves"));
    assertFalse(Files.exists(outputDirectory));
  }

  @Test
  void gameFinishingDuringPartialSaveGetsAFreshCompletedAutomaticSave() throws Exception {
    ContributeGameInfo game = game(0, false);
    games.add(game);
    watch(game);
    Lizzie.config.contributeAutoSave = true;
    Path partialTarget = target(game);
    try (var blocked = SgfSaveTestSupport.blockWorker()) {
      SwingUtilities.invokeAndWait(engine::saveAllGames);
      CompletableFuture<Void> partial = batchCompletion();
      SwingUtilities.invokeAndWait(() -> {
        // Finished-game output adds the last move and updates the game/view result, without
        // rebuilding the watched board or replacing its previously empty GameInfo result.
        game.complete = true;
        Lizzie.board.getHistory().place(1, 1, Stone.WHITE, false, false, true);
        game.gameResult = "B+R";
        assertEquals("", Lizzie.board.getHistory().getGameInfo().getResult());
      });
      assertFalse(game.saved);
      blocked.release();
      partial.get(5, TimeUnit.SECONDS);
    }
    assertTrue(Files.readString(partialTarget).contains(";B[aa]"));
    String completed = Files.readString(target(game));
    assertTrue(completed.contains(";W[bb]"), completed);
    assertTrue(completed.contains("RE[B+R]"), completed);
    assertTrue(game.saved, "the partial snapshot must not suppress the completed-game save");
  }

  private void watch(ContributeGameInfo game) throws Exception {
    SwingUtilities.invokeAndWait(() -> engine.setGameToBoard(game, 0, true));
  }

  private static ContributeGameInfo game(int index, boolean complete) {
    ContributeGameInfo game = new ContributeGameInfo();
    game.gameId = "game-" + index;
    game.blackPlayer = "black-" + index;
    game.whitePlayer = "white-" + index;
    game.complete = complete;
    game.gameResult = complete ? "W+R" : "";
    game.sizeX = game.sizeY = 5;
    game.rules = new JSONObject();
    game.initMoveList = new ArrayList<>();
    game.moveList = new ArrayList<>(List.of(new ContributeMoveInfo(), new ContributeMoveInfo()));
    return game;
  }

  private Path target(ContributeGameInfo game) {
    return outputDirectory.resolve((games.indexOf(game) + 1) + "_" + game.gameId + "_"
        + game.blackPlayer + "_VS_" + game.whitePlayer + "(" + game.gameResult + ").sgf");
  }

  @SuppressWarnings("unchecked")
  private CompletableFuture<Void> batchCompletion() {
    return (CompletableFuture<Void>) get(engine, "allGamesSave");
  }

  private void saveOne(ContributeGameInfo game) {
    try {
      Method save = ContributeEngine.class.getDeclaredMethod(
          "saveGame", ContributeGameInfo.class, int.class);
      save.setAccessible(true);
      save.invoke(engine, game, games.indexOf(game));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static Object get(Object target, String name) {
    try {
      Field field = ContributeEngine.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void set(Object target, String name, Object value) {
    try {
      Field field = ContributeEngine.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class ProjectionEngine extends ContributeEngine {
    @Override
    public synchronized void setGameToBoard(ContributeGameInfo game, int index, boolean saveGame) {
      assertTrue(SwingUtilities.isEventDispatchThread());
      set(this, "currentWatchGame", game);
      String sgf = "(;SZ[5]PB[" + game.blackPlayer + "]PW[" + game.whitePlayer + "]RE["
          + game.gameResult + "]C[" + game.gameId + "];B[aa]"
          + (game.complete ? ";W[bb]" : "") + ")";
      Lizzie.board.setHistory(SGFParser.parseSgf(sgf, true));
    }
  }
}
