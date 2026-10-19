package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HumanSlStrengthReuseSessionTest {
  @TempDir Path cache;

  @Test
  void handicapMetadataWithoutSetupStonesIsRejectedByBothCapturePaths() throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    int width = Board.boardWidth, height = Board.boardHeight;
    try {
      Lizzie.config = ConfigTestHelper.createForTests(cache);
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      Lizzie.board = new Board();
      Lizzie.board.getHistory().getGameInfo().setHandicap(2);
      assertEquals(
          "HumanSL40.setupUnsupported",
          assertThrows(IllegalArgumentException.class, HumanSlStrengthReuseSession::capture)
              .getMessage());
      assertEquals(
          "HumanSL40.setupUnsupported",
          assertThrows(IllegalArgumentException.class, HumanSlStrengthReuseSession::preparationSnapshot)
              .getMessage());
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Board.boardWidth = width;
      Board.boardHeight = height;
    }
  }

  @Test
  void policyArgumentsRoundTripWindowsAndQuotedPathsWithoutShellEscaping() {
    for (String argument :
        List.of(
            "C:\\Program Files\\KataGo\\katago.exe",
            "\\\\server\\shared models\\human.bin.gz",
            "logDir=C:\\Users\\player\\engine logs",
            "C:\\Users\\棋手\\模型\\human.bin.gz",
            "/tmp/player's \"model\"/katago",
            "")) {
      assertEquals(
          List.of(argument),
          featurecat.lizzie.util.Utils.splitCommand(
              HumanSlStrengthReuseSession.quoteCommandArgument(argument)));
    }
  }

  static JSONArray base(int positions) {
    JSONArray base = new JSONArray();
    for (int i = 0; i < positions; i++)
      base.put(HumanSlStrength40Test.response(new JSONObject().put("id", "base-" + i)));
    return base;
  }

  @Test
  void allProfilesArePolicyOnlyAndNoOrdinaryResponsesAreUsed() throws Exception {
    JSONObject game = HumanSlStrength40Test.game();
    JSONArray base = base(13);
    String original = base.toString();
    AtomicInteger queries = new AtomicInteger();
    JSONObject policies =
        HumanSlStrengthReuseSession.policies(
            game,
            base,
            q -> {
              queries.incrementAndGet();
              assertEquals(1, q.getInt("maxVisits"));
              assertEquals(
                  0,
                  q.getJSONObject("overrideSettings")
                      .getDouble("humanSLRootExploreProbWeightless"));
              assertEquals(
                  2, q.getJSONObject("overrideSettings").getInt("rootNumSymmetriesToSample"));
              // Deliberately omit candidates and scores: the supplement must never require or
              // consume them.
              return new JSONObject()
                  .put("id", q.getString("id"))
                  .put(
                      "humanPolicy", HumanSlStrength40Test.response(q).getJSONArray("humanPolicy"));
            },
            cache,
            "engine",
            () -> false,
            n -> {});
    assertEquals(36, queries.get());
    assertEquals(original, base.toString());
    assertTrue(policies.getJSONArray("rank_5d").isNull(12));
    var moves = HumanSlStrength40.extract(game.getJSONArray("moves"), base, policies, 19, true);
    assertEquals(12, moves.size());
    assertTrue(Double.isFinite(HumanSlStrength40.predict(moves)));
    HumanSlStrengthReuseSession.policies(
        game,
        base,
        q -> {
          fail("Replay should not start any engine");
          return null;
        },
        cache,
        "engine",
        () -> false,
        n -> {});
    game.put("komi", 0);
    AtomicInteger changed = new AtomicInteger();
    HumanSlStrengthReuseSession.policies(
        game,
        base,
        q -> {
          changed.incrementAndGet();
          return HumanSlStrength40Test.response(q);
        },
        cache,
        "engine",
        () -> false,
        n -> {});
    assertEquals(36, changed.get());
  }

  @Test
  void missingOrdinaryPositionsAreSkippedNotReanalyzed() throws Exception {
    JSONObject game = HumanSlStrength40Test.game();
    for (int i = 12; i < 16; i++)
      game.getJSONArray("moves").put(new JSONArray().put(i % 2 == 0 ? "B" : "W").put("pass"));
    JSONArray base = base(17);
    base.put(4, JSONObject.NULL);
    AtomicInteger queries = new AtomicInteger();
    JSONObject policies =
        HumanSlStrengthReuseSession.policies(
            game,
            base,
            q -> {
              int turn = q.getJSONArray("moves").length();
              assertTrue(turn != 3 && turn != 4);
              queries.incrementAndGet();
              return HumanSlStrength40Test.response(q);
            },
            cache,
            "engine",
            () -> false,
            n -> {});
    assertEquals(42, queries.get());
    assertEquals(
        14, HumanSlStrength40.extract(game.getJSONArray("moves"), base, policies, 19, true).size());
    assertTrue(policies.getJSONArray("rank_9d").isNull(3));
  }

  @Test
  void corruptPoliciesFailuresAndCancellationAreNotCached() throws Exception {
    JSONObject game = HumanSlStrength40Test.game();
    assertThrows(
        CancellationException.class,
        () ->
            HumanSlStrengthReuseSession.policies(
                game,
                base(13),
                q -> {
                  fail("Cancelled before engine startup");
                  return null;
                },
                cache,
                "engine",
                () -> true,
                n -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HumanSlStrengthReuseSession.policies(
                game,
                base(13),
                q -> {
                  JSONObject response = HumanSlStrength40Test.response(q);
                  response.getJSONArray("humanPolicy").put(0, .8);
                  return response;
                },
                cache,
                "engine",
                () -> false,
                n -> {}));
    try (var files = Files.list(cache)) {
      assertEquals(0, files.count());
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HumanSlStrengthReuseSession.policies(
                game,
                base(13),
                q -> HumanSlStrength40Test.response(q).put("isDuringSearch", true),
                cache,
                "engine",
                () -> false,
                n -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HumanSlStrengthReuseSession.policies(
                game,
                base(13),
                q -> HumanSlStrength40Test.response(q).put("warning", "unsupported profile"),
                cache,
                "engine",
                () -> false,
                n -> {}));
  }

  @Test
  void boardPerspectiveAndPriorsAreConvertedWithoutChangingAnalysis() {
    BoardData data = BoardData.empty(19, 19);
    data.blackToPlay = false;
    data.isKataData = true;
    data.scoreMeanIsBlackPerspective = true;
    data.scoreMean = 10;
    data.winrate = 25;
    data.setPlayouts(200);
    MoveData move = new MoveData();
    move.isKataData = true;
    move.scoreMeanIsBlackPerspective = true;
    move.coordinate = "D4";
    move.scoreMean = 8;
    move.winrate = 20;
    move.policy = 30;
    data.bestMoves.add(move);
    JSONObject result = (JSONObject) HumanSlStrengthReuseSession.position(data);
    assertEquals(-10, result.getJSONObject("rootInfo").getDouble("scoreMean"));
    assertEquals(.25, result.getJSONObject("rootInfo").getDouble("winrate"));
    assertEquals(-8, result.getJSONArray("moveInfos").getJSONObject(0).getDouble("scoreMean"));
    assertEquals(.2, result.getJSONArray("moveInfos").getJSONObject(0).getDouble("winrate"));
    assertEquals(.3, result.getJSONArray("moveInfos").getJSONObject(0).getDouble("prior"));
    assertEquals(10, data.scoreMean);
    assertEquals(8, move.scoreMean);
    assertEquals(200, data.getPlayouts());
    data.isKataData = false;
    assertSame(JSONObject.NULL, HumanSlStrengthReuseSession.position(data));
  }

  @Test
  void displayFilteringDoesNotChangeCandidatesFeaturesOrPredictions() {
    BoardData data = BoardData.empty(19, 19);
    data.isKataData = true;
    data.winrate = 50;
    data.scoreMean = 2;
    MoveData top = candidate("D4", 0, 2, 60);
    MoveData played = candidate("pass", 1, 1, 10);
    MoveData other = candidate("Q16", 2, -3, 30);
    data.bestMoves = List.of(other, top, played);
    JSONObject unfiltered = (JSONObject) HumanSlStrengthReuseSession.position(data);

    data.bestMoves = List.of(top);
    data.bestMovesOutOfRange = List.of(other, played, candidate("d4", 0, -99, 1));
    JSONObject filtered = (JSONObject) HumanSlStrengthReuseSession.position(data);
    assertEquals(
        HumanSlStrengthReuseSession.canonical(unfiltered),
        HumanSlStrengthReuseSession.canonical(filtered));
    JSONArray candidates = filtered.getJSONArray("moveInfos");
    assertEquals(3, candidates.length());
    assertEquals("D4", candidates.getJSONObject(0).getString("move"));
    assertEquals("pass", candidates.getJSONObject(1).getString("move"));
    assertEquals("Q16", candidates.getJSONObject(2).getString("move"));

    JSONArray policy = new JSONArray();
    for (int i = 0; i < 362; i++) policy.put(0.0);
    policy.put(15 * 19 + 3, .6); // D4
    policy.put(3 * 19 + 15, .3); // Q16
    policy.put(361, .1);
    JSONArray fullBase = new JSONArray(), filteredBase = new JSONArray();
    JSONObject policies = new JSONObject();
    for (String profile : HumanSlStrength40.PROFILES) {
      JSONArray values = new JSONArray();
      for (int i = 0; i < 13; i++) values.put(policy);
      policies.put(profile, values);
    }
    for (int i = 0; i < 13; i++) {
      fullBase.put(unfiltered);
      filteredBase.put(filtered);
    }
    JSONArray moves = HumanSlStrength40Test.game().getJSONArray("moves");
    var full = HumanSlStrength40.evaluate(moves, fullBase, policies, 19);
    var limited = HumanSlStrength40.evaluate(moves, filteredBase, policies, 19);
    for (int i = 0; i < full.size(); i++) {
      assertArrayEquals(full.get(i).features(), limited.get(i).features(), 0.0);
      assertEquals(full.get(i).rank(), limited.get(i).rank());
      assertEquals(1, limited.get(i).features()[38], 1e-12);
    }
    assertSame(top, data.bestMoves.get(0));
    assertEquals(3, data.bestMovesOutOfRange.size());
    assertEquals(-99, data.bestMovesOutOfRange.get(2).scoreMean);
  }

  @Test
  void positionWaitsForPayloadCommitAndFreezesBothCandidateLists() throws Exception {
    BoardData data = BoardData.empty(19, 19);
    data.isKataData = true;
    data.winrate = 50;
    MoveData top = candidate("D4", 0, 0, 60);
    MoveData hidden = candidate("Q16", 1, -1, 40);
    data.bestMoves = List.of(top);
    data.bestMovesOutOfRange = List.of(hidden);
    var reader = Executors.newSingleThreadExecutor();
    CountDownLatch enteringCapture = new CountDownLatch(1);
    try {
      java.util.concurrent.Future<JSONObject> captured;
      synchronized (data) {
        data.scoreMean = 7;
        data.winrate = 75;
        captured =
            reader.submit(
                () -> {
                  enteringCapture.countDown();
                  return (JSONObject) HumanSlStrengthReuseSession.position(data);
                });
        assertTrue(enteringCapture.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> captured.get(100, TimeUnit.MILLISECONDS));
        top.scoreMean = 7;
        top.winrate = 75;
        hidden.scoreMean = 6;
        hidden.winrate = 60;
      }
      JSONObject position = captured.get(5, TimeUnit.SECONDS);
      synchronized (data) {
        data.scoreMean = 99;
        data.winrate = 1;
        top.scoreMean = 99;
        top.winrate = 1;
        hidden.scoreMean = 98;
        hidden.winrate = 0;
      }
      assertEquals(7, position.getJSONObject("rootInfo").getDouble("scoreMean"));
      assertEquals(.75, position.getJSONObject("rootInfo").getDouble("winrate"));
      assertEquals(7, position.getJSONArray("moveInfos").getJSONObject(0).getDouble("scoreMean"));
      assertEquals(6, position.getJSONArray("moveInfos").getJSONObject(1).getDouble("scoreMean"));
      assertEquals(.75, position.getJSONArray("moveInfos").getJSONObject(0).getDouble("winrate"));
      assertEquals(.6, position.getJSONArray("moveInfos").getJSONObject(1).getDouble("winrate"));
    } finally {
      reader.shutdownNow();
      assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static MoveData candidate(String coordinate, int order, double score, double prior) {
    MoveData move = new MoveData();
    move.coordinate = coordinate;
    move.order = order;
    move.isKataData = true;
    move.scoreMean = score;
    move.policy = prior;
    return move;
  }

  @Test
  void dropdownReportAndSegmentsUseFortyFeaturesWithoutLegacySigma() throws Exception {
    Config previous = Lizzie.config;
    int width = Board.boardWidth, height = Board.boardHeight;
    try {
      Lizzie.config = ConfigTestHelper.createForTests(cache);
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      BoardData root = BoardData.empty(19, 19);
      seed(root);
      BoardHistoryList history = new BoardHistoryList(root);
      for (int i = 1; i <= 12; i++) {
        BoardData data =
            BoardData.pass(
                root.stones.clone(),
                i % 2 == 1 ? Stone.BLACK : Stone.WHITE,
                i % 2 == 0,
                new Zobrist(),
                i,
                new int[361],
                0,
                0,
                50,
                100);
        seed(data);
        history.add(data);
      }
      var capture =
          new HumanSlStrengthReuseSession.Capture(
              history.getStart(), HumanSlStrength40Test.game(), base(13), true);
      JSONObject policies =
          HumanSlStrengthReuseSession.policies(
              capture.game(),
              capture.base(),
              HumanSlStrength40Test::response,
              cache.resolve("policies"),
              "engine",
              () -> false,
              n -> {});
      var report = HumanSlStrengthReuseSession.report(capture, policies);
      assertTrue(
          List.of(PlayerStrengthEstimator.selectableModels())
              .contains(PlayerStrengthEstimator.StrengthModel.HUMANSL40));
      assertTrue(report.model.isHumanSl40());
      assertEquals(6, report.black.sampleCount);
      assertEquals(6, report.white.sampleCount);
      assertTrue(Double.isFinite(report.black.rankValue));
      assertTrue(Double.isNaN(report.black.predictionSigma));
      var segment = PlayerStrengthEstimator.summarizeSamples(report.black.samples, report.model);
      assertEquals(report.black.rankValue, segment.rankValue);
      assertEquals(0, report.black.averageDifficulty);
      assertEquals(100, root.getPlayouts());
      JSONArray normalized = base(13);
      for (int i = 0; i < normalized.length(); i++) {
        JSONObject position = normalized.getJSONObject(i);
        JSONObject played = position.getJSONArray("moveInfos").getJSONObject(0).put("order", 1);
        position.put(
            "moveInfos",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("move", "D4")
                        .put("order", 0)
                        .put("scoreMean", 4)
                        .put("winrate", .9)
                        .put("prior", .9))
                .put(played));
      }
      var normalizedCapture =
          new HumanSlStrengthReuseSession.Capture(
              history.getStart(), capture.game(), normalized, true);
      // A later ordinary update and mutation of the caller's JSON cannot alter this report.
      root.winrate = 1;
      root.bestMoves.get(0).winrate = 1;
      root.bestMoves.get(0).coordinate = "Q16";
      normalized.getJSONObject(0).getJSONArray("moveInfos").getJSONObject(0).put("winrate", 0);
      var converted =
          HumanSlStrengthReuseSession.report(normalizedCapture, policies);
      assertEquals(4, converted.black.averageScoreLoss);
      assertEquals(40, converted.black.averageWinrateLoss, 1e-12);
      assertEquals(40, converted.white.averageWinrateLoss, 1e-12);
      assertEquals(0, converted.black.moveRankGoodMoveRate);
      assertEquals(6, converted.black.sampleCount);
      for (int i = 0; i < converted.black.samples.size(); i++) {
        var sample = converted.black.samples.get(i);
        assertEquals(2 * i + 1, sample.moveNumber);
        assertEquals("pass", sample.coordinate);
        assertEquals(40, sample.winrateLoss, 1e-12);
      }
      var convertedSegment =
          PlayerStrengthEstimator.summarizeSamples(converted.black.samples, converted.model);
      assertEquals(converted.black.rankValue, convertedSegment.rankValue);
      assertEquals(converted.black.averageWinrateLoss, convertedSegment.averageWinrateLoss);
      var pending = PlayerStrengthEstimator.estimate(history.getStart(), report.model);
      assertTrue(Double.isNaN(pending.black.rankValue));
      assertEquals("-", pending.black.rankValueText());
      assertTrue(
          Double.isNaN(PlayerStrengthEstimator.estimate(null, report.model).black.rankValue));
    } finally {
      Board.boardWidth = width;
      Board.boardHeight = height;
      Lizzie.config = previous;
    }
  }

  @Test
  void frozenRootWinratesFillMissingCandidateWinratesOrMissingPlayedCandidates() throws Exception {
    JSONObject game = HumanSlStrength40Test.game();
    JSONArray base = base(13);
    JSONObject policies =
        HumanSlStrengthReuseSession.policies(
            game, base, HumanSlStrength40Test::response, cache, "root-fallback", () -> false, n -> {});
    for (boolean absentPlayed : List.of(false, true)) {
      JSONArray capturedBase = base(13);
      for (int i = 0; i < capturedBase.length(); i++) {
        JSONObject position = capturedBase.getJSONObject(i);
        position.getJSONObject("rootInfo").put("winrate", .6);
        JSONObject candidate = position.getJSONArray("moveInfos").getJSONObject(0);
        if (absentPlayed) candidate.put("move", "D4");
        else candidate.remove("winrate");
      }
      var report =
          HumanSlStrengthReuseSession.report(
              new HumanSlStrengthReuseSession.Capture(null, game, capturedBase, true), policies);
      assertEquals(20, report.black.averageWinrateLoss, 1e-12);
      assertEquals(20, report.white.averageWinrateLoss, 1e-12);
      assertEquals(absentPlayed ? 999 : 0, report.black.samples.get(0).aiRank);
      assertEquals("pass", report.black.samples.get(0).coordinate);
    }
  }

  private static void seed(BoardData data) {
    data.winrate = 50;
    data.scoreMean = 0;
    data.isKataData = true;
    data.setPlayouts(100);
    MoveData move = new MoveData();
    move.coordinate = "pass";
    move.scoreMean = 0;
    move.winrate = 50;
    move.isKataData = true;
    move.policy = 100;
    data.bestMoves.add(move);
  }
}
