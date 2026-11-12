package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.SgfSaveCoordinator;
import featurecat.lizzie.gui.SgfSaveTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SGFDeferredSaveTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"normal", "raw", "comments", "branch", "game", "ordinary", "automatic", "compact-ownership"})
  void queuedSaveRetainsTreeCandidatesAndSettingsAfterLiveOwnersAreReplaced(String mode)
      throws Exception {
    var originalBundle = Lizzie.resourceBundle;
    boolean originalComments = LizzieFrame.isSavingRawComment;
    String originalVersion = Lizzie.nextVersion;
    try (var ignored = RulesLayerTestHarness.open()) {
      var board = Lizzie.board;
      var config = Lizzie.config;
      var engine = Lizzie.leelaz;
      AtomicReference<String> expected = new AtomicReference<>();
      AtomicReference<SGFParser.SaveSnapshot> snapshot = new AtomicReference<>();
      AtomicReference<CompletableFuture<Path>> saved = new AtomicReference<>();
      Path target = directory.resolve(mode + ".sgf");
      try (var blocked = SgfSaveTestSupport.blockWorker()) {
        SwingUtilities.invokeAndWait(() -> {
          try {
            Lizzie.resourceBundle = AppLocale.KOREAN.loadBundle();
            Lizzie.nextVersion = "captured-version";
            config.appendWinrateToComment = true;
            config.extraMode = ExtraMode.Double_Engine;
            config.winrateAlwaysBlack = true;
            config.showKataGoScoreLeadWithKomi = true;
            engine.isKatago = true;
            engine.rulesSummary = KataGoRules.Summary.CHINESE;
            var history = SGFParser.parseSgf(
                "(;SZ[5:7]KM[6.5]PB[한국 흑]DT[2026-11-12]AB[aa]AW[bg]PL[B]C[root]"
                    + ";B[bc]C[first](;W[]C[pass])(;AE[aa]AB[cd];W[eg]C[side]))", true);
            board.setHistory(history);
            board.isGameBoard = mode.equals("game");
            var first = history.getStart().next().orElseThrow();
            var side = first.getVariation(1).orElseThrow();
            history.setHead(side);
            for (var node : List.of(history.getStart(), first, side)) installAnalysis(node.getData());
            if (mode.equals("compact-ownership")) {
              for (var node : List.of(history.getStart(), first, side)) {
                var data = node.getData();
                data.estimateArray = BoardData.compactEstimateArray(data.estimateArray);
                data.estimateArray2 = BoardData.compactEstimateArray(data.estimateArray2);
              }
            }
            boolean raw = List.of("raw", "comments", "branch").contains(mode);
            boolean comments = mode.equals("comments");
            boolean branch = mode.equals("branch");
            if (mode.equals("ordinary") || mode.equals("automatic")) {
              expected.set(SGFParser.captureSave(board, history, mode.equals("automatic")));
              snapshot.set(SGFParser.captureSaveSnapshot(board, history, mode.equals("automatic")));
            } else {
              expected.set(SGFParser.saveSnapshot(board, raw, comments, branch));
              snapshot.set(SGFParser.captureSnapshot(board, raw, comments, branch));
            }
            saved.set(SgfSaveCoordinator.saveCaptured(target, snapshot.get(), () -> true,
                path -> assertTrue(SwingUtilities.isEventDispatchThread()), error -> fail(error)));

            // Renderers can edit candidate fields in place even without replacing their lists.
            for (var node : List.of(history.getStart(), first, side)) {
              BoardData data = node.getData();
              data.comment = "edited after capture";
              for (MoveData candidate : List.of(data.bestMoves.get(0), data.bestMoves2.get(0))) {
                candidate.coordinate = "A1";
                candidate.playouts = 9999;
                candidate.edgeVisits = 9998;
                candidate.winrate = 1;
                candidate.policy = 0.99;
                candidate.scoreMean = 50;
                candidate.order = 99;
                candidate.variation.set(0, "A1");
                candidate.pvVisits.set(0, "9999");
              }
              if (mode.equals("compact-ownership")) {
                data.estimateArray = BoardData.compactEstimateArray(List.of(1.0));
                data.estimateArray2 = null;
              } else {
                data.estimateArray.set(0, 1.0);
                data.estimateArray2.clear();
              }
              java.util.Arrays.fill(data.stones, Stone.EMPTY);
              data.lastMove.ifPresent(move -> move[0] = 0);
              data.setProperty("XX", "changed");
            }
            history.getGameInfo().setPlayerBlack("replacement player");
            history.getGameInfo().setKomiNoMenu(0.5);
            first.getVariations().clear();
            config.appendWinrateToComment = false;
            config.extraMode = ExtraMode.Normal;
            config.winrateAlwaysBlack = false;
            config.showKataGoScoreLeadWithKomi = false;
            engine.rulesSummary = KataGoRules.Summary.JAPANESE;
            LizzieFrame.isSavingRaw = !raw;
            LizzieFrame.isSavingRawComment = !comments;
            Board.boardWidth = 19;
            Board.boardHeight = 19;
            Lizzie.nextVersion = "replacement-version";
            // Any hidden global read in the worker must fail this exact-output assertion.
            Lizzie.board = null;
            Lizzie.leelaz = null;
            Lizzie.config = null;
            Lizzie.resourceBundle = null;
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
        assertFalse(saved.get().isDone());
        blocked.release();
        assertEquals(target, saved.get().get(5, TimeUnit.SECONDS));
        String actual = Files.readString(target);
        assertEquals(expected.get(), actual);
        assertEquals(actual, snapshot.get().serialize());
        assertSame(snapshot.get().serialize(), snapshot.get().serialize(), "retry reuses its text");
        assertFalse(actual.contains("edited after capture"));
        assertFalse(actual.contains("replacement-version"));
      } finally {
        SwingUtilities.invokeAndWait(() -> {
          Lizzie.board = board;
          Lizzie.config = config;
          Lizzie.leelaz = engine;
          Lizzie.resourceBundle = originalBundle;
          Lizzie.nextVersion = originalVersion;
          LizzieFrame.isSavingRawComment = originalComments;
        });
      }
      var reopened = SGFParser.parseSgf(Files.readString(target), true);
      assertEquals("한국 흑", reopened.getGameInfo().getPlayerBlack());
      assertEquals(35, reopened.getStart().getData().stones.length);
      if (!mode.equals("raw") && !mode.equals("branch")) {
        var data = reopened.getStart().next().orElseThrow().getData();
        assertTrue(data.comment.contains("first"));
      }
    } finally {
      Lizzie.resourceBundle = originalBundle;
      Lizzie.nextVersion = originalVersion;
      LizzieFrame.isSavingRawComment = originalComments;
    }
  }

  @Test
  void serializationFailurePreservesOldFileAndDoesNotBlockTheNextSave() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Path failedTarget = directory.resolve("previous.sgf");
      String previous = "(;SZ[5]C[previous complete file])";
      Files.writeString(failedTarget, previous);
      AtomicReference<SGFParser.SaveSnapshot> broken = new AtomicReference<>();
      AtomicReference<CompletableFuture<Path>> failed = new AtomicReference<>();
      AtomicReference<CompletableFuture<Path>> next = new AtomicReference<>();
      AtomicReference<Throwable> reported = new AtomicReference<>();
      SwingUtilities.invokeAndWait(() -> {
        try {
          Lizzie.board.getHistory().getStart().getData().comment = null;
          broken.set(SGFParser.captureSnapshot(Lizzie.board, false, false, false));
          failed.set(SgfSaveCoordinator.saveCaptured(failedTarget, broken.get(), () -> true,
              path -> fail("incomplete serialization must not publish success"), error -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                reported.set(error);
              }));
          Lizzie.board.getHistory().getStart().getData().comment = "next valid record";
          var good = SGFParser.captureSnapshot(Lizzie.board, false, false, false);
          next.set(SgfSaveCoordinator.saveCaptured(directory.resolve("next.sgf"), good,
              () -> true, path -> {}, error -> fail(error)));
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      });
      try {
        var error = assertThrows(ExecutionException.class,
            () -> failed.get().get(5, TimeUnit.SECONDS));
        assertInstanceOf(NullPointerException.class, error.getCause());
        assertSame(error.getCause(), reported.get());
        assertSame(error.getCause(), assertThrows(NullPointerException.class, broken.get()::serialize),
            "a partially formatted tree must never be rendered again");
        assertEquals(previous, Files.readString(failedTarget));
        assertTrue(Files.readString(next.get().get(5, TimeUnit.SECONDS)).contains("next valid record"));
      } finally {
        SgfSaveTestSupport.awaitPending();
      }
    }
  }

  private static void installAnalysis(BoardData data) {
    MoveData primary = candidate();
    MoveData secondary = candidate();
    secondary.scoreMean = -1.25;
    data.bestMoves = new ArrayList<>(List.of(primary));
    data.bestMoves2 = new ArrayList<>(List.of(secondary));
    data.setPlayouts(120);
    data.setPlayouts2(150);
    data.winrate = 62.5;
    data.winrate2 = 57.5;
    data.engineName = "원본 엔진";
    data.engineName2 = "두번째 엔진";
    data.isKataData = true;
    data.isKataData2 = true;
    data.scoreMean = 2.5;
    data.scoreMean2 = -1.25;
    data.scoreStdev = 1.5;
    data.scoreStdev2 = 2.5;
    data.estimateArray = new ArrayList<>(List.of(-0.0, 0.25, -0.5));
    data.estimateArray2 = new ArrayList<>(List.of(0.5, -0.25, 0.0));
    var provenance = new AnalysisProvenance(
        "원본 엔진", "1.0", "model", "", "", "chinese", 6.5, 0.0, 0.7, "");
    data.setPrimaryAnalysisProvenance(provenance);
    data.setPrimaryOwnershipProvenance(provenance);
    data.setSecondaryAnalysisProvenance(provenance);
    data.setSecondaryOwnershipProvenance(provenance);
  }

  private static MoveData candidate() {
    MoveData move = new MoveData();
    move.coordinate = "C3";
    move.playouts = 80;
    move.edgeVisits = 70;
    move.winrate = 62.5;
    move.policy = 0.125;
    move.scoreMean = 2.5;
    move.variation = new ArrayList<>(List.of("C3", "D4"));
    move.pvVisits = new ArrayList<>(List.of("80", "40"));
    return move;
  }
}
