package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GameEditSnapshotTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ordinaryPublicationAndImportedMetadataCleanupAreNotEdits(boolean secondary) throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardData data = board.getData();
        adopt(data, secondary, 100);
        data.addProperty("KGP", "opaque imported analysis metadata");
        data.addProperty("LZ", "imported primary evaluation");
        data.addProperty("LZ2", "imported secondary evaluation");
        data.addProperty("LZOP", "imported primary ownership");
        data.addProperty("LZOP2", "imported secondary ownership");
        data.addProperty("LB", "aa:review");
        GameEditSnapshot saved = GameEditSnapshot.capture(board);

        adopt(data, secondary, 200);
        assertNull(data.getProperty("KGP"), "normal adoption must exercise imported metadata cleanup");
        for (String property : List.of("LZ", "LZ2", "LZOP", "LZOP2")) {
          data.getProperties().remove(property);
        }
        assertTrue(saved.isCurrent(board));

        data.getProperties().remove("LB");
        assertFalse(saved.isCurrent(board), "authored SGF markup remains part of the saved game");
        return null;
      });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void laterPublicationDoesNotEraseAnExplicitInvalidation(boolean secondary) throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardData data = board.getData();
        adopt(data, secondary, 100);
        GameEditSnapshot saved = GameEditSnapshot.capture(board);

        invalidate(data, secondary);
        assertTrue(secondary ? data.isChanged2 : data.isChanged);
        adopt(data, secondary, 200);
        assertFalse(secondary ? data.isChanged2 : data.isChanged);
        assertFalse(saved.isCurrent(board), "a new result must not hide an intervening explicit edit");
        return null;
      });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preexistingInvalidationCanBecomeFreshWithoutCancellingTheContinuation(boolean secondary)
      throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardData data = board.getData();
        adopt(data, secondary, 100);
        invalidate(data, secondary);
        GameEditSnapshot saved = GameEditSnapshot.capture(board);

        adopt(data, secondary, 200);
        assertFalse(secondary ? data.isChanged2 : data.isChanged);
        assertTrue(saved.isCurrent(board), "clearing an old invalidity flag is ordinary publication");
        return null;
      });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void repeatedInvalidationAndInvalidationBeforeFirstResultRemainExplicitEdits(boolean secondary)
      throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardData data = board.getData();
        GameEditSnapshot empty = GameEditSnapshot.capture(board);
        invalidate(data, secondary);
        assertFalse(empty.isCurrent(board), "pending work can be invalidated before its first result");

        adopt(data, secondary, 100);
        invalidate(data, secondary);
        GameEditSnapshot alreadyInvalid = GameEditSnapshot.capture(board);
        invalidate(data, secondary);
        assertFalse(alreadyInvalid.isCurrent(board), "a repeated invalidation is not a no-op flag write");
        return null;
      });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitInformationClearIsAnEditButAutomaticPayloadCleanupIsNot(boolean secondary)
      throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardHistoryNode node = fixture.current();
        BoardData data = node.getData();
        adopt(data, secondary, 100);
        GameEditSnapshot saved = GameEditSnapshot.capture(board);
        data.tryToClearBestMoves();
        assertTrue(saved.isCurrent(board), "automatic stale-cache cleanup is not an authored edit");

        adopt(data, secondary, 200);
        if (secondary) board.clearbestmovesInfomation2(node);
        else board.clearBestMovesInfomation(node);
        assertFalse(saved.isCurrent(board), "the explicit information-clear command must be retained");
        return null;
      });
    }
  }

  @Test
  void generatedCommentsPreserveExactAuthoredTextAndDoNotHideLaterDirectEdits() throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardData data = board.getData();
        String original = "  winrate: 50%\nscoreMean: 0\nreviewer's note  \n";
        data.comment = original;
        GameEditSnapshot saved = GameEditSnapshot.capture(board);
        data.setGeneratedComment(() -> "analysis 100 visits\n" + original.trim());
        data.setGeneratedComment(() -> "analysis 200 visits\n" + original.trim());
        assertEquals(original, data.authoredComment());
        assertTrue(saved.isCurrent(board), "formatting must not trim the authored-state identity");

        data.comment = original + " ";
        data.setGeneratedComment(() -> "analysis 300 visits\n" + original.trim());
        assertEquals(original + " ", data.authoredComment());
        assertFalse(saved.isCurrent(board), "even a whitespace edit must survive regeneration");

        GameEditSnapshot changed = GameEditSnapshot.capture(board);
        data.comment = "analysis 400 visits";
        assertFalse(changed.isCurrent(board), "analysis-looking authored text is still an edit");
        return null;
      });
    }
  }

  @Test
  void acceptingAnUnchangedDisplayedCommentKeepsItsAuthoredOrigin() throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardHistoryNode node = fixture.current();
        BoardData data = node.getData();
        String original = "annotation before automatic analysis text";
        data.setAuthoredComment(original);
        adopt(data, false, 100);
        data.setGeneratedComment(() -> SGFParser.formatComment(node));
        String displayed = data.comment;
        GameEditSnapshot saved = GameEditSnapshot.capture(board);

        // Opening and accepting the editor supplies equal text, often as a different String.
        data.setAuthoredComment(new String(displayed));
        assertEquals(displayed, data.comment);
        assertEquals(original, data.authoredComment());
        assertTrue(saved.isCurrent(board), "an unchanged editor must not author the generated block");

        adopt(data, false, 200);
        data.setGeneratedComment(() -> SGFParser.formatComment(node));
        assertNotEquals(displayed, data.comment, "the next result must actually refresh the display");
        assertEquals(original, data.authoredComment());
        assertTrue(saved.isCurrent(board), "a no-op editor must not corrupt later comment origins");
        return null;
      });
    }
  }

  @Test
  void boardCommentWaitsForGenerationAndRetainsTheEditInIdentityAndSgf() throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      Board board = fixture.board();
      String original = "original annotation before the save";
      String edited = "authored text accepted while the save is pending";
      BoardHistoryNode node = onEdt(() -> {
        BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[aa])", true);
        board.setHistory(history);
        BoardHistoryNode selected = history.getStart().next().orElseThrow();
        history.setHead(selected);
        board.comment(original);
        adopt(selected.getData(), false, 100);
        selected.getData().setGeneratedComment(() -> SGFParser.formatComment(selected));
        return selected;
      });
      BoardData data = node.getData();
      GameEditSnapshot saved = onEdt(() -> GameEditSnapshot.capture(board));
      String frozenSgf = onEdt(() -> SGFParser.saveSnapshot(board, true, true, false));
      assertFalse(frozenSgf.contains(edited));
      Thread eventThread = onEdt(Thread::currentThread);
      CountDownLatch formatted = new CountDownLatch(1);
      CountDownLatch releaseGeneration = new CountDownLatch(1);
      FutureTask<Void> publication = new FutureTask<>(() -> {
        adopt(data, false, 200);
        data.setGeneratedComment(() -> {
          assertTrue(Thread.holdsLock(data), "formatting and origin publication share one monitor");
          String generated = SGFParser.formatComment(node);
          assertTrue(generated.contains(original));
          assertFalse(generated.contains(edited));
          formatted.countDown();
          await(releaseGeneration);
          return generated;
        });
        return null;
      });
      Thread generationThread = new Thread(publication, "generated-comment-publication-test");
      FutureTask<Void> edit = new FutureTask<>(() -> {
        board.comment(edited);
        // The real automatic formatter also runs after the accepted authored edit.
        SGFParser.appendComment();
        return null;
      });
      boolean editSubmitted = false;
      generationThread.start();
      try {
        assertTrue(formatted.await(5, TimeUnit.SECONDS), "analysis must reach its formatted result");
        SwingUtilities.invokeLater(edit);
        editSubmitted = true;
        awaitAuthoredWriterBlocked(eventThread, data, generationThread);
      } finally {
        releaseGeneration.countDown();
        try {
          try {
            publication.get(5, TimeUnit.SECONDS);
          } finally {
            if (editSubmitted) edit.get(5, TimeUnit.SECONDS);
          }
        } finally {
          generationThread.join(TimeUnit.SECONDS.toMillis(5));
          assertFalse(generationThread.isAlive(), "the controlled publisher must finish");
        }
      }

      onEdt(() -> {
        assertEquals(200, data.getPlayouts());
        assertEquals(edited, data.authoredComment());
        assertTrue(data.comment.contains(edited));
        assertFalse(data.comment.contains(original));
        assertFalse(saved.isCurrent(board), "the pending-save guard must retain the authored edit");
        String currentSgf = SGFParser.saveSnapshot(board, true, true, false);
        assertTrue(currentSgf.contains(edited), "the actual SGF must retain the authored text");
        assertTrue(SGFParser.parseSgf(currentSgf, true).getEnd().getData().comment.contains(edited));
        return null;
      });
    }
  }

  @Test
  void synchronizedAnalysisRegeneratesCommentsWithoutChangingAuthoredState() throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[aa])", true);
        board.setHistory(history);
        BoardHistoryNode node = history.getStart().next().orElseThrow();
        history.setHead(node);
        BoardData data = node.getData();
        data.comment = "original annotation";
        adopt(data, false, 100);
        data.setGeneratedComment(() -> SGFParser.formatComment(node));
        GameEditSnapshot saved = GameEditSnapshot.capture(board);

        BoardData incoming = data.clone();
        adopt(incoming, false, 200);
        incoming.addProperty("LZ", "updated imported analysis");
        node.sync(new BoardHistoryNode(incoming));

        assertEquals(200, data.getPlayouts(), "sync must actually replace the analysis payload");
        assertEquals("original annotation", data.authoredComment());
        assertTrue(saved.isCurrent(board), "sync-generated commentary is not an authored edit");
        return null;
      });
    }
  }

  @Test
  void analysisCopiesCarryCommentOriginOnlyWhenTheyAlsoCopyTheComment() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardData source = BoardData.empty(5, 5);
      source.comment = "  original note\n";
      source.setGeneratedComment(() -> "generated source display");
      source.invalidatePrimaryAnalysis();

      BoardData clone = source.clone();
      BoardData synced = BoardData.empty(5, 5);
      synced.sync(source);
      for (BoardData copy : List.of(clone, synced)) {
        assertEquals("generated source display", copy.comment);
        assertEquals("  original note\n", copy.authoredComment());
        copy.setGeneratedComment(() -> "regenerated copy display");
        assertEquals("  original note\n", copy.authoredComment());
        assertEquals(0, copy.analysisEditRevision(), "an edit generation belongs to its live node");
      }

      BoardData payloadOnly = BoardData.empty(5, 5);
      payloadOnly.comment = "destination note";
      payloadOnly.setGeneratedComment(() -> "destination display");
      payloadOnly.copyAnalysisPayloadFrom(source);
      assertEquals("destination display", payloadOnly.comment);
      assertEquals("destination note", payloadOnly.authoredComment());
      payloadOnly.setGeneratedComment(() -> "new destination display");
      assertEquals("destination note", payloadOnly.authoredComment());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"black", "white", "date", "komi", "handicap", "result", "property",
      "setupStone", "turn", "extraStone", "removedStone", "rules"})
  void authoredMetadataAndSetupChangesAreDetected(String edit) throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardHistoryList history = board.getHistory();
        BoardData data = board.getData();
        GameEditSnapshot saved = GameEditSnapshot.capture(board);
        switch (edit) {
          case "black" -> history.getGameInfo().setPlayerBlack("Black player");
          case "white" -> history.getGameInfo().setPlayerWhite("White player");
          case "date" -> history.getGameInfo().setDate("2026-10-28,29");
          case "komi" -> history.getGameInfo().setKomiNoMenu(1.5);
          case "handicap" -> history.getGameInfo().setHandicap(2);
          case "result" -> history.getGameInfo().setResultNoUI("B+R");
          case "property" -> data.addProperty("TR", "aa");
          case "setupStone" -> data.stones[0] = Stone.BLACK;
          case "turn" -> data.blackToPlay = !data.blackToPlay;
          case "extraStone" -> fixture.current().addExtraStones(0, 0, true);
          case "removedStone" -> fixture.current().setRemovedStone();
          case "rules" -> history.publishExternalRules("Japanese");
          default -> throw new AssertionError(edit);
        }
        assertFalse(saved.isCurrent(board), edit + " must remain protected while saving");
        return null;
      });
    }
  }

  @Test
  void branchesAreCheckedEvenWhenTheCursorAndRootStayTheSame() throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[aa](;W[bb])(;W[cc]))", true);
        board.setHistory(history);
        history.setHead(history.getStart());
        BoardHistoryNode fork = history.getStart().next().orElseThrow();
        GameEditSnapshot saved = GameEditSnapshot.capture(board);
        Collections.swap(fork.getVariations(), 0, 1);
        assertFalse(saved.isCurrent(board), "changing the main continuation changes the saved tree");
        Collections.swap(fork.getVariations(), 0, 1);
        assertTrue(saved.isCurrent(board), "a fully restored authored state is unchanged");

        fork.getVariations().get(1).getData().comment = "variation annotation";
        assertFalse(saved.isCurrent(board));
        GameEditSnapshot withComment = GameEditSnapshot.capture(board);
        fork.getVariations().remove(1);
        assertFalse(withComment.isCurrent(board), "deleting an off-cursor branch must be detected");
        return null;
      });
    }
  }

  @Test
  void cursorHistoryAndBoardReplacementInvalidateTheCapturedContext() throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      onEdt(() -> {
        Board board = fixture.board();
        BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[aa])", true);
        board.setHistory(history);
        history.setHead(history.getStart());
        GameEditSnapshot saved = GameEditSnapshot.capture(board);
        history.next();
        assertFalse(saved.isCurrent(board));
        history.previous();
        assertTrue(saved.isCurrent(board));
        assertFalse(saved.isCurrent(null));

        board.setHistory(new BoardHistoryList(BoardData.empty(5, 5)));
        assertFalse(saved.isCurrent(board));
        return null;
      });
    }
  }

  @Test
  void historyLockIsReleasedBeforeWaitingForAnAnalysisPayload() throws Exception {
    try (var fixture = RulesLayerTestHarness.open()) {
      Board board = fixture.board();
      BoardHistoryList history = board.getHistory();
      BoardData data = history.getData();
      CountDownLatch structureRead = new CountDownLatch(1);
      CountDownLatch payloadHeld = new CountDownLatch(1);
      CountDownLatch releasePayload = new CountDownLatch(1);
      history.setHead(new BoardHistoryNode(data) {
        @Override
        public List<BoardHistoryNode> getVariations() {
          structureRead.countDown();
          return super.getVariations();
        }
      });
      var workers = Executors.newFixedThreadPool(2);
      FutureTask<GameEditSnapshot> capture = new FutureTask<>(() -> GameEditSnapshot.capture(board));
      var payload = workers.submit(() -> {
        synchronized (data) {
          payloadHeld.countDown();
          await(releasePayload);
        }
      });
      try {
        assertTrue(payloadHeld.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeLater(capture);
        assertTrue(structureRead.await(5, TimeUnit.SECONDS));
        var historyAccess = workers.submit(() -> {
          synchronized (history) {
            return history.getCurrentHistoryNode();
          }
        });
        assertSame(history.getCurrentHistoryNode(), historyAccess.get(5, TimeUnit.SECONDS));
      } finally {
        releasePayload.countDown();
        payload.get(5, TimeUnit.SECONDS);
        capture.get(5, TimeUnit.SECONDS);
        workers.shutdownNow();
      }
    }
  }

  private static void adopt(BoardData data, boolean secondary, int visits) {
    MoveData move = new MoveData();
    move.coordinate = "C3";
    move.playouts = visits;
    move.winrate = 55;
    move.variation = new ArrayList<>(List.of("C3", "D4"));
    assertEquals(BoardData.AnalysisAdoption.FULL,
        data.adoptOrdinaryAnalysis(new ArrayList<>(List.of(move)), "engine", Lizzie.leelaz,
            visits, visits, List.of(0.1, -0.1), new Object(), secondary, true));
  }

  private static void invalidate(BoardData data, boolean secondary) {
    if (secondary) data.invalidateSecondaryAnalysis();
    else data.invalidatePrimaryAnalysis();
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(5, TimeUnit.SECONDS);
  }

  private static void awaitAuthoredWriterBlocked(Thread writer, BoardData data, Thread owner) {
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    ThreadInfo observed = null;
    while (System.nanoTime() < deadline) {
      observed = threads.getThreadInfo(writer.getId(), 32);
      if (observed != null && observed.getThreadState() == Thread.State.BLOCKED
          && observed.getLockInfo() != null
          && observed.getLockInfo().getIdentityHashCode() == System.identityHashCode(data)
          && observed.getLockInfo().getClassName().equals(BoardData.class.getName())
          && observed.getLockOwnerId() == owner.getId()
          && Arrays.stream(observed.getStackTrace()).anyMatch(frame ->
              frame.getClassName().equals(BoardData.class.getName())
                  && frame.getMethodName().equals("setAuthoredComment"))) return;
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
    fail("Board.comment must wait for the generator's BoardData monitor; observed: " + observed);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
  }
}
