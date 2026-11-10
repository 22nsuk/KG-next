package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.gui.LizzieFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SGFSaveSnapshotTest {
  private static final String METADATA_TREE =
      "(;SZ[5]KM[0.5]HA[2]AB[aa][ee]PL[W]XX[opaque\\]value\\\\]"
          + "C[root\\]note\\\\];W[bb](;B[]C[pass])(;B[cc]C[branch]))";
  private static final MetadataExpectation EDITED_METADATA =
      new MetadataExpectation(
          "Alice[9d] literal\\n 한국어\\",
          "Bob]7d A]XX[extra",
          "2026-09-12,13]DT[extra\\",
          "W+R]RE[extra\\",
          List.of(
              "PB[Alice[9d\\] literal\\\\n 한국어\\\\]",
              "PW[Bob\\]7d A\\]XX[extra]",
              "DT[2026-09-12,13\\]DT[extra\\\\]",
              "RE[W+R\\]RE[extra\\\\]"));
  private static final MetadataExpectation EMPTY_METADATA =
      new MetadataExpectation("", "", "", "", List.of("PB[]", "PW[]", "DT[]", "RE[]"));

  @TempDir Path tempDirectory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void engineMoveWaitsForTopologyCaptureAndCannotEnterTheSavedTree(boolean currentBranch)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Board board = Lizzie.board;
      BoardHistoryList history = board.getHistory();
      CountDownLatch readingChildren = new CountDownLatch(1);
      CountDownLatch releaseCapture = new CountDownLatch(1);
      AtomicBoolean firstRead = new AtomicBoolean(true);
      BoardHistoryNode root =
          new BoardHistoryNode(history.getData()) {
            private void pauseTopologyRead() {
              if (SwingUtilities.isEventDispatchThread() && firstRead.getAndSet(false)) {
                readingChildren.countDown();
                await(releaseCapture);
              }
            }

            @Override
            public List<BoardHistoryNode> getVariations() {
              pauseTopologyRead();
              return super.getVariations();
            }

            @Override
            public Optional<BoardHistoryNode> next(boolean includeDummy) {
              pauseTopologyRead();
              return super.next(includeDummy);
            }
          };
      history.setHead(root);
      var worker = Executors.newSingleThreadExecutor();
      CompletableFuture<String> saved = captureAsync(board, currentBranch);
      try {
        assertTrue(readingChildren.await(5, TimeUnit.SECONDS));
        CountDownLatch committing = new CountDownLatch(1);
        var move =
            worker.submit(
                () -> {
                  committing.countDown();
                  return board.commitEngineGamePlace(
                      history, root, true, 0, 0, Stone.BLACK, false, false, false);
                });
        assertTrue(committing.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> move.get(100, TimeUnit.MILLISECONDS));
        releaseCapture.countDown();
        String sgf = saved.get(5, TimeUnit.SECONDS);
        assertNotNull(move.get(5, TimeUnit.SECONDS));
        assertEquals(1, history.getMoveNumber());
        assertFalse(
            sgf.contains(";B[aa]"), "the captured topology ends before the concurrent move");
        assertEquals(0, SGFParser.parseSgf(sgf, true).getEnd().getData().moveNumber);
      } finally {
        releaseCapture.countDown();
        saved.handle((value, failure) -> null).get(5, TimeUnit.SECONDS);
        worker.shutdownNow();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void payloadCopyReleasesHistoryLockButKeepsTheFrozenTopology(boolean currentBranch)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Board board = Lizzie.board;
      BoardHistoryList history = board.getHistory();
      CountDownLatch cloningPayload = new CountDownLatch(1);
      CountDownLatch releaseClone = new CountDownLatch(1);
      history.getGameInfo().setDate("2026-09-12,13");
      String originalDate = history.getGameInfo().getDate();
      BoardHistoryNode root =
          new BoardHistoryNode(history.getData()) {
            @Override
            BoardHistoryNode copyForSave() {
              cloningPayload.countDown();
              await(releaseClone);
              return super.copyForSave();
            }
          };
      history.setHead(root);
      var worker = Executors.newSingleThreadExecutor();
      CompletableFuture<String> saved = captureAsync(board, currentBranch);
      try {
        assertTrue(cloningPayload.await(5, TimeUnit.SECONDS));
        var move =
            worker.submit(
                () ->
                    board.commitEngineGamePlace(
                        history, root, true, 0, 0, Stone.BLACK, false, false, false));
        assertNotNull(
            move.get(5, TimeUnit.SECONDS), "payload copies must not hold the history lock");
        history.getGameInfo().setPlayerBlack("changed after topology capture");
        history.getGameInfo().setDate("2026-09-27");
        releaseClone.countDown();
        String sgf = saved.get(5, TimeUnit.SECONDS);
        assertFalse(sgf.contains(";B[aa]"));
        assertFalse(sgf.contains("changed after topology capture"));
        assertTrue(sgf.contains("DT[" + originalDate + "]"));
        assertEquals(1, history.getMoveNumber());
      } finally {
        releaseClone.countDown();
        saved.handle((value, failure) -> null).get(5, TimeUnit.SECONDS);
        worker.shutdownNow();
      }
    }
  }

  @Test
  void normalSnapshotKeepsRulesHeaderOnTheDetachedRoot() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Lizzie.leelaz.isKatago = true;
      Lizzie.leelaz.rulesSummary = KataGoRules.Summary.CHINESE;
      BoardData root = Lizzie.board.getHistory().getData();
      root.comment = "original comment";
      String saved = capture(false, false, false);
      assertTrue(saved.contains(Lizzie.resourceBundle.getString("SGFParse.rules")));
      assertTrue(
          saved.contains(Lizzie.resourceBundle.getString("LizzieFrame.currentRules.chinese")));
      assertTrue(saved.contains("original comment"));
      assertEquals("original comment", root.comment);
    }
  }

  private static CompletableFuture<String> captureAsync(Board board, boolean currentBranch) {
    CompletableFuture<String> result = new CompletableFuture<>();
    SwingUtilities.invokeLater(
        () -> {
          try {
            result.complete(SGFParser.saveSnapshot(board, true, false, currentBranch));
          } catch (Throwable failure) {
            result.completeExceptionally(failure);
          }
        });
    return result;
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void selectedBranchKeepsAncestorPathAndContinuationWithoutMutatingLiveTree(boolean comments)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history =
          SGFParser.parseSgf(
              "(;SZ[5]PB[黑方]C[root];B[aa](;W[bb];B[cc])(;W[dd]C[branch];B[ee]))", true);
      Lizzie.board.setHistory(history);
      var root = history.getStart();
      var fork = root.next().orElseThrow();
      var selected = fork.getVariation(1).orElseThrow();
      history.setHead(selected);
      fork.getData().setPlayouts(100);
      fork.getData().winrate = 67;
      selected.getData().setPlayouts(200);
      selected.getData().winrate = 60;
      selected.getData().engineName = "branch-comment-engine";
      var originalProperties = new LinkedHashMap<>(root.getData().getProperties());
      String expectedComment = "";
      if (comments) {
        var full = SGFParser.parseSgf(capture(true, true, false), true);
        expectedComment =
            full.getStart().next().orElseThrow().getVariation(1).orElseThrow().getData().comment;
        assertTrue(expectedComment.contains("branch-comment-engine"));
        assertTrue(expectedComment.contains("branch"));
      }
      String saved = capture(true, comments, true);
      assertTrue(saved.contains(";B[aa]"));
      assertTrue(saved.contains(";W[dd]"));
      assertTrue(saved.contains(";B[ee]"));
      assertFalse(saved.contains(";W[bb]"));
      assertFalse(saved.contains(";B[cc]"));
      assertFalse(saved.contains("C[root]"));
      var reopened = SGFParser.parseSgf(saved, true);
      assertEquals(
          expectedComment,
          reopened.getStart().next().orElseThrow().next().orElseThrow().getData().comment);
      assertSame(history, Lizzie.board.getHistory());
      assertSame(selected, history.getCurrentHistoryNode());
      assertSame(root, history.getStart());
      assertEquals(2, fork.numberOfChildren());
      assertEquals("root", root.getData().comment);
      assertEquals("", fork.getData().comment);
      assertEquals("branch", selected.getData().comment);
      assertEquals(originalProperties, root.getData().getProperties());
      assertEquals("黑方", reopened.getGameInfo().getPlayerBlack());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rawBranchCaptureDoesNotVisitOrCopyExcludedSubtrees(boolean comments) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      var history =
          SGFParser.parseSgf(
              "(;SZ[5];B[aa](;W[bb];B[cc])(;W[dd](;B[ee])(;B[bc])))", true);
      List<BoardHistoryNode> copied = new ArrayList<>();
      List<BoardHistoryNode> childrenRead = new ArrayList<>();
      BoardHistoryNode root = observeSaveTraversal(history.getStart(), copied, childrenRead);
      var fork = root.next().orElseThrow();
      var main = fork.getVariation(0).orElseThrow();
      var selected = fork.getVariation(1).orElseThrow();
      Set<BoardHistoryNode> retained =
          Set.of(root, fork, selected, selected.next().orElseThrow());
      Set<BoardHistoryNode> excluded =
          Set.of(main, main.next().orElseThrow(), selected.getVariation(1).orElseThrow());
      history.setHead(selected);
      Lizzie.board.setHistory(history);
      childrenRead.clear();

      capture(true, comments, true);

      assertEquals(4, copied.size(), "copy only the exported ancestor path and continuation");
      assertEquals(retained, Set.copyOf(copied));
      assertTrue(
          childrenRead.stream().noneMatch(excluded::contains),
          "excluded subtrees must not be traversed during topology capture");

      copied.clear();
      childrenRead.clear();
      capture(true, comments, false);
      assertEquals(7, copied.size(), "full exports still capture every branch");
      assertTrue(copied.containsAll(retained));
      assertTrue(copied.containsAll(excluded));
      assertTrue(childrenRead.containsAll(excluded));
    }
  }

  private static BoardHistoryNode observeSaveTraversal(
      BoardHistoryNode original, List<BoardHistoryNode> copied, List<BoardHistoryNode> childrenRead) {
    BoardHistoryNode observed =
        new BoardHistoryNode(original.getData()) {
          @Override
          BoardHistoryNode copyForSave() {
            copied.add(this);
            return super.copyForSave();
          }

          @Override
          public List<BoardHistoryNode> getVariations() {
            childrenRead.add(this);
            return super.getVariations();
          }

          @Override
          public Optional<BoardHistoryNode> next(boolean includeDummy) {
            childrenRead.add(this);
            return super.next(includeDummy);
          }
        };
    for (BoardHistoryNode child : original.getVariations()) {
      observeSaveTraversal(child, copied, childrenRead).reparentAsLastVariationOf(observed);
    }
    return observed;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void branchKeepsNonterminalDummyAndExplicitlySelectedTerminalDummy(boolean selectTerminal)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      var history = SGFParser.parseSgf("(;SZ[5];B[aa];DD[true];DD[true])", true);
      var move = history.getStart().next().orElseThrow();
      var nonterminal = move.next(true).orElseThrow();
      var terminal = nonterminal.next(true).orElseThrow();
      assertFalse(nonterminal.isEndDummay());
      assertTrue(terminal.isEndDummay());
      history.setHead(selectTerminal ? terminal : move);
      Lizzie.board.setHistory(history);

      var saved = SGFParser.parseSgf(capture(true, false, true), true);
      var savedDummy = saved.getStart().next(true).orElseThrow().next(true).orElseThrow();
      assertTrue(savedDummy.getData().dummy);
      assertEquals(selectTerminal, savedDummy.next(true).isPresent());
      if (selectTerminal) assertTrue(savedDummy.next(true).orElseThrow().isEndDummay());
      assertSame(selectTerminal ? terminal : move, history.getCurrentHistoryNode());
      assertSame(terminal, nonterminal.next(true).orElseThrow());
    }
  }

  @Test
  void standardBranchSaveDoesNotRedirectGeneratedCommentsToTheSelectedSibling() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      var history =
          SGFParser.parseSgf("(;SZ[5];B[aa](;W[bb]C[main])(;W[dd]C[side]))", true);
      var fork = history.getStart().next().orElseThrow();
      var main = fork.getVariation(0).orElseThrow();
      var selected = fork.getVariation(1).orElseThrow();
      fork.getData().setPlayouts(100);
      fork.getData().winrate = 67;
      fork.getData().engineName = "game-comment-engine";
      history.setHead(selected);
      Lizzie.board.setHistory(history);
      Lizzie.board.isGameBoard = true;

      var full = SGFParser.parseSgf(capture(false, false, false), true);
      assertTrue(
          full.getStart().next().orElseThrow().getVariation(0).orElseThrow().getData().comment
              .contains("game-comment-engine"));
      var branch = SGFParser.parseSgf(capture(false, false, true), true);
      assertEquals(
          "side", branch.getStart().next().orElseThrow().next().orElseThrow().getData().comment);
      assertEquals("main", main.getData().comment);
      assertEquals("side", selected.getData().comment);
      assertSame(selected, history.getCurrentHistoryNode());
    }
  }

  @Test
  void branchRetainsRootSetupPassAndMidgameSetupSemantics() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      var history = SGFParser.parseSgf("(;SZ[5]AB[aa]AW[bb]PL[W];W[];AE[aa]AB[cc];B[dd])", true);
      Lizzie.board.setHistory(history);
      var last = history.getEnd();
      history.setHead(last);
      var replayed = SGFParser.parseSgf(capture(true, false, true), true);
      assertArrayEquals(last.getData().stones, replayed.getEnd().getData().stones);
      assertEquals(last.getData().blackToPlay, replayed.getEnd().getData().blackToPlay);
      assertTrue(replayed.getStart().next().orElseThrow().getData().isPassNode());
    }
  }

  @Test
  void exportModesKeepCommentsAndRestoreFlagsAfterSnapshotAndFailure() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5];B[aa]C[中文注释](;W[bb])(;W[dd]))", true));
      boolean originalComments = LizzieFrame.isSavingRawComment;
      try {
        LizzieFrame.isSavingRaw = true;
        LizzieFrame.isSavingRawComment = true;
        String normal = capture(false, false, false);
        String raw = capture(true, false, false);
        String comments = capture(true, true, false);
        assertTrue(normal.contains("C[中文注释]"));
        assertTrue(normal.contains(";W[bb]"));
        assertTrue(normal.contains(";W[dd]"));
        assertFalse(raw.contains("C[中文注释]"));
        assertTrue(comments.contains("C[中文注释]"));
        assertTrue(LizzieFrame.isSavingRaw);
        assertTrue(LizzieFrame.isSavingRawComment);
        SwingUtilities.invokeAndWait(
            () ->
                assertThrows(
                    NullPointerException.class,
                    () -> SGFParser.saveSnapshot(null, false, false, false)));
        assertTrue(LizzieFrame.isSavingRaw);
        assertTrue(LizzieFrame.isSavingRawComment);
      } finally {
        LizzieFrame.isSavingRawComment = originalComments;
      }
    }
  }

  @Test
  void snapshotIsImmutableWhenUserContinuesEditing() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5];B[aa]C[before])", true));
      var history = Lizzie.board.getHistory();
      String saved = capture(false, false, false);
      history.getEnd().getData().comment = "after";
      assertTrue(saved.contains("C[before]"));
      assertFalse(saved.contains("C[after]"));
      assertThrows(
          IllegalStateException.class,
          () -> SGFParser.saveSnapshot(Lizzie.board, false, false, false));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void savedCommentsPreserveLiteralEscapesOnRootAndBothVariations(boolean raw) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      var history = SGFParser.parseSgf("(;SZ[5];B[aa](;W[])(;W[bb]))", true);
      Lizzie.board.setHistory(history);
      String comment = "한국어 日本語 中文 ] \\ \\] $1 %s\n[끝]\\";
      var root = history.getStart();
      var fork = root.next().orElseThrow();
      root.getData().comment = comment;
      fork.getVariation(0).orElseThrow().getData().comment = comment;
      fork.getVariation(1).orElseThrow().getData().comment = comment;
      String saved = capture(raw, true, false);
      BoardHistoryList reopened = SGFParser.parseSgf(saved, true);
      assertEquals(comment, reopened.getStart().getData().comment);
      var reopenedFork = reopened.getStart().next().orElseThrow();
      assertEquals(2, reopenedFork.numberOfChildren());
      assertTrue(reopenedFork.getVariation(0).orElseThrow().getData().isPassNode());
      for (BoardHistoryNode child : reopenedFork.getVariations()) {
        assertEquals(comment, child.getData().comment);
      }
      assertEquals(comment, root.getData().comment);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void editedGameInfoSurvivesNormalAndRawSnapshotsWithoutChangingLiveState(boolean raw)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      for (MetadataExpectation expected : List.of(EDITED_METADATA, EMPTY_METADATA)) {
        BoardHistoryList original = SGFParser.parseSgf(METADATA_TREE, true);
        Lizzie.board.setHistory(original);
        setMetadata(original, expected);
        for (int round = 0; round < 2; round++) {
          BoardHistoryList live = Lizzie.board.getHistory();
          var current = live.getCurrentHistoryNode();
          var rootProperties = new LinkedHashMap<>(live.getStart().getData().getProperties());

          String saved = capture(raw, true, false);

          assertEncodedMetadata(saved, expected);
          assertMetadata(live, expected);
          assertSame(live, Lizzie.board.getHistory());
          assertSame(current, live.getCurrentHistoryNode());
          assertEquals(rootProperties, live.getStart().getData().getProperties());
          BoardHistoryList reopened = SGFParser.parseSgf(saved, true);
          assertMetadata(reopened, expected);
          SGFParserSemanticRoundTripTest.assertTreeSemanticsEqual(original, reopened);
          Lizzie.board.setHistory(reopened);
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"SZ[3", "XSZ[3"})
  void metadataSizeTextDoesNotResizeANewGameOnReopen(String name) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList original = Lizzie.board.getHistory();
      original.getGameInfo().setPlayerWhite(name);
      original.place(4, 4, Stone.BLACK);
      for (String saved : List.of(
          capture(false, false, false), "(;PW[" + name + "]SZ[5];B[ee])")) {
        BoardHistoryList reopened = SGFParser.parseSgf(saved, true);
        assertEquals(name, reopened.getGameInfo().getPlayerWhite());
        assertEquals(25, reopened.getStart().getData().stones.length);
        SGFParserSemanticRoundTripTest.assertTreeSemanticsEqual(original, reopened);
        openMetadataSgf("live", saved);
        assertEquals(name, Lizzie.board.getHistory().getGameInfo().getPlayerWhite());
        SGFParserSemanticRoundTripTest.assertTreeSemanticsEqual(original, Lizzie.board.getHistory());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"detached", "live", "edit"})
  void importedGameInfoDecodesEscapesAndSimpleTextOnceAcrossOpeners(String opener)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      // Literal SGF input and literal expectations keep the parser and writer from agreeing
      // on the same escaping mistake. CRLF/LFCR are one line break, escaped breaks vanish,
      // and an escaped letter n is the letter n rather than a line break.
      String imported =
          "(;PB[Alice[9d\\] \\n한글]PW[Bob\\\\ end\\\\]"
              + "RE[W+R\\]\r\nline\n\rend\t\f\u000B]"
              + "DT[2026\\\r\n-09\\\n\r-12\\n]"
              + METADATA_TREE.substring(2);
      MetadataExpectation expected =
          new MetadataExpectation(
              "Alice[9d] n한글",
              "Bob\\ end\\",
              "2026-09-12n",
              "W+R] line end   ",
              List.of(
                  "PB[Alice[9d\\] n한글]",
                  "PW[Bob\\\\ end\\\\]",
                  "DT[2026-09-12n]",
                  "RE[W+R\\] line end   ]"));
      BoardHistoryList expectedTree = SGFParser.parseSgf(METADATA_TREE, true);
      openMetadataSgf(opener, imported);
      for (int round = 0; round < 3; round++) {
        BoardHistoryList live = Lizzie.board.getHistory();
        assertMetadata(live, expected);
        SGFParserSemanticRoundTripTest.assertTreeSemanticsEqual(expectedTree, live);
        if (round < 2) {
          var rootProperties = new LinkedHashMap<>(live.getStart().getData().getProperties());
          String saved = capture(false, true, false);
          assertEncodedMetadata(saved, expected);
          assertMetadata(live, expected);
          assertEquals(rootProperties, live.getStart().getData().getProperties());
          openMetadataSgf(opener, saved);
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void manualAndAutomaticFilesKeepGameInfoAcrossRepeatedReopens(boolean automatic)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList original = SGFParser.parseSgf(METADATA_TREE, true);
      Lizzie.board.setHistory(original);
      setMetadata(original, EDITED_METADATA);
      Path target = tempDirectory.resolve(automatic ? "automatic.sgf" : "manual.sgf");
      for (int round = 0; round < 2; round++) {
        BoardHistoryList live = Lizzie.board.getHistory();
        var current = live.getCurrentHistoryNode();
        var rootProperties = new LinkedHashMap<>(live.getStart().getData().getProperties());

        SGFParser.save(Lizzie.board, target.toString(), automatic);

        String saved = Files.readString(target);
        assertEncodedMetadata(saved, EDITED_METADATA);
        assertMetadata(live, EDITED_METADATA);
        assertSame(live, Lizzie.board.getHistory());
        assertSame(current, live.getCurrentHistoryNode());
        assertEquals(rootProperties, live.getStart().getData().getProperties());
        BoardHistoryList reopened = SGFParser.parseSgf(saved, true);
        assertMetadata(reopened, EDITED_METADATA);
        SGFParserSemanticRoundTripTest.assertTreeSemanticsEqual(original, reopened);
        Lizzie.board.setHistory(reopened);
      }
    }
  }

  private static void setMetadata(BoardHistoryList history, MetadataExpectation expected) {
    var gameInfo = history.getGameInfo();
    gameInfo.setPlayerBlack(expected.black());
    gameInfo.setPlayerWhite(expected.white());
    gameInfo.setDate(expected.date());
    gameInfo.setResultNoUI(expected.result());
  }

  private static void assertMetadata(BoardHistoryList history, MetadataExpectation expected) {
    var gameInfo = history.getGameInfo();
    assertEquals(expected.black(), gameInfo.getPlayerBlack());
    assertEquals(expected.white(), gameInfo.getPlayerWhite());
    assertEquals(expected.date(), gameInfo.getDate());
    assertEquals(expected.result(), gameInfo.getResult());
    assertEquals(0.5, gameInfo.getKomi());
    assertEquals(2, gameInfo.getHandicap());
    assertEquals("5", history.getStart().getData().getProperty("SZ"));
    assertEquals(25, history.getStart().getData().stones.length);
    assertEquals("opaque\\]value\\\\", history.getStart().getData().getProperty("XX"));
  }

  private static void assertEncodedMetadata(String saved, MetadataExpectation expected) {
    for (String property : expected.encodedProperties()) {
      assertTrue(saved.contains(property), "Missing literal property " + property + " in " + saved);
    }
    assertTrue(saved.contains("XX[opaque\\]value\\\\]"), saved);
  }

  private static void openMetadataSgf(String opener, String sgf) throws Exception {
    switch (opener) {
      case "detached" -> Lizzie.board.setHistory(SGFParser.parseSgf(sgf, true));
      case "live" -> assertTrue(SGFParser.loadFromString(sgf, false));
      case "edit" -> assertTrue(SGFParser.loadFromStringforedit(sgf));
      default -> throw new IllegalArgumentException(opener);
    }
    SwingUtilities.invokeAndWait(() -> {});
  }

  private record MetadataExpectation(
      String black, String white, String date, String result, List<String> encodedProperties) {}

  private static String capture(boolean raw, boolean comments, boolean branch) throws Exception {
    AtomicReference<String> result = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            result.set(SGFParser.saveSnapshot(Lizzie.board, raw, comments, branch));
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
    return result.get();
  }

  @Test
  void analysisSnapshotWaitsForCompletePayloadInsteadOfMixingVisitsAndCandidates()
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardData data = Lizzie.board.getHistory().getData();
      java.util.concurrent.CountDownLatch halfway = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
      var moves =
          new java.util.ArrayList<featurecat.lizzie.analysis.MoveData>() {
            @Override
            public featurecat.lizzie.analysis.MoveData get(int index) {
              halfway.countDown();
              try {
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
              } catch (InterruptedException failure) {
                throw new AssertionError(failure);
              }
              return super.get(index);
            }
          };
      var move = new featurecat.lizzie.analysis.MoveData();
      move.coordinate = "C3";
      move.playouts = 200;
      move.winrate = 61;
      moves.add(move);
      var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        var update =
            executor.submit(
                () ->
                    data.tryToSetBestMovesWithStatus(
                        moves, "snapshot-test", false, 200, null, true));
        assertTrue(halfway.await(5, java.util.concurrent.TimeUnit.SECONDS));
        java.util.concurrent.CountDownLatch cloning = new java.util.concurrent.CountDownLatch(1);
        var snapshot =
            executor.submit(
                () -> {
                  cloning.countDown();
                  return data.clone();
                });
        assertTrue(cloning.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(snapshot.isDone(), "Partial payload commit must fence a save snapshot");
        release.countDown();
        assertTrue(update.get(5, java.util.concurrent.TimeUnit.SECONDS));
        BoardData copy = snapshot.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, copy.getPlayouts());
        assertEquals(200, copy.bestMoves.get(0).playouts);
        assertEquals(61, copy.winrate);
      } finally {
        release.countDown();
        executor.shutdownNow();
      }
    }
  }
}
