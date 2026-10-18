package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.ResourceBundle;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class TeacherPromptBuilderTest {
  @Test
  void promptContainsEvidenceAndExplicitAntiHallucinationContract() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(
            42,
            "B",
            1600,
            "D4",
            OptionalDouble.of(2.5),
            List.of(
                new TeacherEvidence.Candidate(1, "Q16", 62.4, 3.1, 1000, List.of("Q16", "D4")),
                new TeacherEvidence.Candidate(2, "D4", 59.9, 1.4, 600, List.of("D4", "Q16"))));

    List<TeacherLlmClient.Message> messages =
        TeacherPromptBuilder.forPosition(position, Locale.SIMPLIFIED_CHINESE, null);
    String system = messages.get(0).content;
    String evidence = messages.get(1).content;

    assertTrue(system.contains("简体中文"));
    assertTrue(system.contains("禁止编造"));
    assertTrue(system.contains("世界顶尖围棋职业棋手"));
    assertTrue(system.contains("不是用个人偏好替代 KataGo"));
    assertTrue(system.contains("0.5 目以内视为基本等价"));
    assertTrue(system.contains("超过 1.5 目再重点解释"));
    assertTrue(system.contains("胜率仅作辅助"));
    assertTrue(system.contains("教学性解读"));
    assertTrue(evidence.contains("第 42 手之后的局面"));
    assertTrue(system.contains("不得进行作弊指控"));
    assertTrue(system.contains("不得声称用户具有任何官方段位"));
    assertTrue(system.contains("### 正确思路"));
    assertTrue(system.contains("### 练习建议"));
    assertTrue(evidence.contains("D4"));
    assertTrue(evidence.contains("Q16"));
    assertTrue(evidence.contains("pv=Q16"));
    assertFalse(evidence.contains("pv=Q16(B) Q16(W)"));
    assertTrue(evidence.contains("2.5"));
    assertFalse(evidence.contains("user comment"));
  }

  @Test
  void playedMoveWarningUsesARealLineBreak() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(
            9,
            "W",
            800,
            "C3",
            OptionalDouble.empty(),
            List.of(new TeacherEvidence.Candidate(1, "Q16", 60, 2, 800, List.of("Q16"))));

    String evidence =
        TeacherPromptBuilder.forPosition(position, Locale.ENGLISH, null).get(1).content;

    assertTrue(evidence.contains("Position after move 9"));
    assertTrue(evidence.contains("Actual next move: C3"));
    assertTrue(evidence.contains("Candidate #1: move=Q16"));
    assertTrue(evidence.contains("C3"));
    assertTrue(evidence.contains("\n"));
    assertFalse(evidence.contains("list.\\\\n"));
  }

  @Test
  void followUpKeepsTheOriginalRangeEvidenceInsteadOfOnlyTheLastPosition() {
    List<TeacherLlmClient.Message> evidenceContext =
        List.of(
            new TeacherLlmClient.Message("system", "evidence-only"),
            new TeacherLlmClient.Message("user", "move 12 evidence\nmove 38 evidence"));

    List<TeacherLlmClient.Message> followUp =
        TeacherPromptBuilder.forFollowUp(
            evidenceContext, "previous answer", "Why was move 12 important?", Locale.ENGLISH, null);

    assertEquals(4, followUp.size());
    assertEquals("move 12 evidence\nmove 38 evidence", followUp.get(1).content);
    assertEquals("previous answer", followUp.get(2).content);
    assertTrue(followUp.get(3).content.contains("Why was move 12 important?"));
  }

  @Test
  void nonChinesePromptsRemainLocalizedAndKeepSafetyBoundaries() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(
            9,
            "W",
            800,
            "C3",
            OptionalDouble.empty(),
            List.of(new TeacherEvidence.Candidate(1, "Q16", 60, 2, 800, List.of("Q16"))));

    List<TeacherLlmClient.Message> messages =
        TeacherPromptBuilder.forPosition(position, Locale.ENGLISH, snapshot("d", 3));

    assertTrue(messages.get(0).content.contains("Reply in English"));
    assertTrue(messages.get(0).content.contains("world-class professional Go player"));
    assertTrue(messages.get(0).content.contains("not to replace KataGo"));
    assertTrue(messages.get(0).content.contains("within 0.5 points"));
    assertTrue(messages.get(0).content.contains("more than 1.5"));
    assertTrue(messages.get(0).content.contains("Never invent"));
    assertTrue(messages.get(0).content.contains("Do not make cheating accusations"));
    assertTrue(messages.get(0).content.contains("final values normalized by the application"));
    assertTrue(messages.get(0).content.contains("do not infer another perspective"));
    assertFalse(messages.get(0).content.contains("black-positive convention"));
    assertFalse(messages.get(0).content.contains("围棋 AI 讲棋老师"));
    assertTrue(messages.get(1).content.startsWith("Explain the actual next move"));
    assertFalse(messages.get(1).content.contains("讲解实战下一手"));
  }

  @Test
  void chinesePersonaMapsEveryRankBandWithoutDowngradingDanPlayers() {
    String oneDan = systemPrompt(snapshot("d", 1));
    String threeKyu = systemPrompt(snapshot("k", 3));
    String sevenKyu = systemPrompt(snapshot("k", 7));
    String twelveKyu = systemPrompt(snapshot("k", 12));

    assertTrue(oneDan.contains("当前用户是段位水平"));
    assertTrue(oneDan.contains("当前用户段位：1d"));
    assertTrue(threeKyu.contains("当前用户是高级水平"));
    assertTrue(sevenKyu.contains("当前用户是级位/中级水平"));
    assertTrue(twelveKyu.contains("当前用户是入门水平"));
  }

  @Test
  void traditionalChineseRequestsTraditionalOutputWithoutDroppingPersona() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(1, "B", 100, "", OptionalDouble.empty(), List.of());

    String system =
        TeacherPromptBuilder.forPosition(position, Locale.TRADITIONAL_CHINESE, snapshot("k", 5))
            .get(0)
            .content;

    assertTrue(system.contains("繁體中文"));
    assertTrue(system.contains("【风格设置】"));
    assertTrue(system.contains("禁止编造"));
    assertTrue(system.contains("应用已经处理好的最终值"));
    assertTrue(system.contains("不得自行推测其他视角"));
  }

  @Test
  void everySupportedLocaleProvidesValidPromptTemplates() {
    List<Locale> locales =
        List.of(
            Locale.ENGLISH,
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.JAPANESE,
            Locale.KOREAN,
            Locale.forLanguageTag("th-TH"));

    for (Locale locale : locales) {
      ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      assertTrue(bundle.containsKey("Teacher.status.evidenceReady"), locale.toLanguageTag());
      assertTrue(bundle.containsKey("Teacher.progress.accessible"), locale.toLanguageTag());
      assertTrue(
          java.text.MessageFormat.format(bundle.getString("Teacher.prompt.positionAfter"), 42)
              .contains("42"),
          locale.toLanguageTag());
      assertTrue(
          java.text.MessageFormat.format(bundle.getString("Teacher.prompt.winrateLoss"), "2.5")
              .contains("2.5"),
          locale.toLanguageTag());
    }
  }

  @Test
  void preservesPointAndPercentagePointUnitsForAnEvaluatedRecordedMove() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(
            12,
            "W",
            1000,
            "D4",
            OptionalDouble.of(40),
            List.of(
                new TeacherEvidence.Candidate(1, "Q16", 60, 3, 800, List.of("Q16")),
                new TeacherEvidence.Candidate(4, "D4", 20, 2.5, 200, List.of("D4", "Q4"))));

    List<TeacherLlmClient.Message> messages =
        TeacherPromptBuilder.forPosition(position, Locale.KOREAN, null);
    String prompt = messages.get(1).content;

    assertTrue(prompt.contains("score loss (points, NOT percentage points): 0.5"));
    assertTrue(prompt.contains("Candidate winrate and scoreLead perspective: side to play (W)"));
    assertTrue(prompt.contains("retained outside the top three"));
    assertTrue(messages.get(0).content.contains("loss is unknown, not zero"));
    assertTrue(messages.get(0).content.contains("Never reconstruct an omitted suffix"));
  }

  @ParameterizedTest
  @EnumSource(
      value = TeacherPromptBuilder.Mode.class,
      names = {"RANGE", "WHOLE_GAME"})
  void ordinaryRangeKeepsEveryPositionAndItsOriginalMessages(TeacherPromptBuilder.Mode mode) {
    TeacherEvidence.Range range =
        new TeacherEvidence.Range(List.of(position(12, "D4"), position(38, "Q16")), 2, 0);
    List<TeacherLlmClient.Message> original =
        TeacherPromptBuilder.forRange(range, mode, Locale.KOREAN, snapshot("d", 3));

    var prepared =
        TeacherPromptBuilder.prepareRange(range, mode, Locale.KOREAN, snapshot("d", 3));

    assertSame(range, prepared.evidence());
    assertEquals(0, prepared.evidence().omittedPositions);
    assertEquals(
        original.stream().map(message -> message.role + "\n" + message.content).toList(),
        prepared.messages().stream().map(message -> message.role + "\n" + message.content).toList());
  }

  @ParameterizedTest
  @EnumSource(
      value = TeacherPromptBuilder.Mode.class,
      names = {"RANGE", "WHOLE_GAME"})
  void denseRealGameKeepsCompleteEvidenceWithinTheRequestBudget(TeacherPromptBuilder.Mode mode)
      throws Exception {
    TeacherEvidence.Range range = analyzedRealGame(mode);
    List<TeacherLlmClient.Message> oversized =
        TeacherPromptBuilder.forRange(range, mode, Locale.ENGLISH, snapshot("d", 3));
    assertEquals(40, range.positions.size());
    assertTrue(contentCharacters(oversized) > 250_000, "The old request must exceed the real cap.");
    TeacherLlmClient transport =
        new TeacherLlmClient(
            HttpClient.newHttpClient(), "http://127.0.0.1:1/v1", "test-key", "test-model");
    assertEquals(
        "AI commentary context is too large.",
        assertThrows(
                IllegalArgumentException.class,
                () -> transport.stream(oversized, new TeacherLlmClient.Cancellation(), ignored -> {}))
            .getMessage());

    var prepared =
        TeacherPromptBuilder.prepareRange(range, mode, Locale.ENGLISH, snapshot("d", 3));
    TeacherEvidence.Range selected = prepared.evidence();
    assertTrue(selected.positions.size() >= 2 && selected.positions.size() < 40);
    assertEquals(range.analyzedPositions, selected.analyzedPositions);
    assertEquals(selected.analyzedPositions - selected.positions.size(), selected.omittedPositions);
    assertSame(range.positions.get(0), selected.positions.get(0));
    assertSame(range.positions.get(39), selected.positions.get(selected.positions.size() - 1));
    List<Integer> expectedMoves = new ArrayList<>();
    expectedMoves.add(range.positions.get(0).moveNumber);
    // This fixture's actual-move loss increases on every move, so later moves take priority.
    for (int move = 206 - selected.positions.size(); move < 205; move++) {
      expectedMoves.add(move);
    }
    assertEquals(
        expectedMoves, selected.positions.stream().map(position -> position.moveNumber).toList());

    String userMessage = prepared.messages().get(1).content;
    assertEquals(
        expectedMoves,
        Pattern.compile("Position after move (\\d+)\\n")
            .matcher(userMessage)
            .results()
            .map(match -> Integer.parseInt(match.group(1)))
            .toList());
    assertTrue(userMessage.contains("selected key positions: " + selected.positions.size()));
    assertTrue(userMessage.contains("omitted lower-priority positions: " + selected.omittedPositions));
    for (TeacherEvidence.Position position : selected.positions) {
      assertTrue(userMessage.contains(TeacherPromptBuilder.formatPosition(position, Locale.ENGLISH)));
    }
    assertTrue(contentCharacters(prepared.messages()) <= 200_000);

    // Exercise ordinary-size follow-up plus the real one-correction wrapper, including its
    // instructions. This does not promise room for arbitrarily large questions or replies.
    List<TeacherLlmClient.Message> followUp =
        TeacherPromptBuilder.forFollowUp(
            prepared.messages(), "a".repeat(14_000), "q".repeat(12_000), Locale.ENGLISH, null);
    List<List<TeacherLlmClient.Message>> attempts = new ArrayList<>();
    String corrected = "Review each retained position using its shown liberties.";
    CommentaryClient replies =
        (messages, cancellation, onText) -> {
          attempts.add(messages);
          assertTrue(contentCharacters(messages) <= 250_000);
          return attempts.size() == 1 ? "Z99 " + "x".repeat(23_996) : corrected;
        };
    assertEquals(
        corrected,
        new GroundedCommentaryClient(replies, selected.positions)
            .stream(followUp, new TeacherLlmClient.Cancellation(), ignored -> {}));
    assertEquals(2, attempts.size());
    assertEquals(userMessage, attempts.get(1).get(1).content);
  }

  @Test
  void rangeBudgetCanRetainOnlyBothEnds() {
    TeacherEvidence.Position first = position(0, "a".repeat(70_000));
    TeacherEvidence.Position middle = position(1, "b".repeat(70_000));
    TeacherEvidence.Position last = position(2, "c".repeat(70_000));
    TeacherEvidence.Range range = new TeacherEvidence.Range(List.of(first, middle, last), 8, 5);

    var prepared =
        TeacherPromptBuilder.prepareRange(
            range, TeacherPromptBuilder.Mode.RANGE, Locale.ENGLISH, null);

    assertEquals(List.of(first, last), prepared.evidence().positions);
    assertEquals(8, prepared.evidence().analyzedPositions);
    assertEquals(6, prepared.evidence().omittedPositions);
    assertFalse(prepared.messages().get(1).content.contains("Position after move 1\n"));
    assertTrue(contentCharacters(prepared.messages()) <= 200_000);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void oversizedEndsFailWithoutTruncatingTheirEvidence(int count) {
    List<TeacherEvidence.Position> positions = new ArrayList<>();
    for (int move = 0; move < count; move++) {
      positions.add(position(move, "a".repeat(200_000)));
    }
    TeacherEvidence.Range range = new TeacherEvidence.Range(positions, count, 0);

    assertEquals(
        "AI commentary context is too large.",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    TeacherPromptBuilder.prepareRange(
                        range, TeacherPromptBuilder.Mode.RANGE, Locale.ENGLISH, null))
            .getMessage());
    assertEquals(count, range.positions.size());
  }

  private static long contentCharacters(List<TeacherLlmClient.Message> messages) {
    return messages.stream().mapToLong(message -> message.content.length()).sum();
  }

  private static TeacherEvidence.Position position(int move, String coordinate) {
    return new TeacherEvidence.Position(
        move,
        "B",
        100,
        "",
        OptionalDouble.empty(),
        List.of(new TeacherEvidence.Candidate(1, coordinate, 50, 0, 100, List.of())));
  }

  private static TeacherEvidence.Range analyzedRealGame(TeacherPromptBuilder.Mode mode)
      throws Exception {
    int previousWidth = Board.boardWidth;
    int previousHeight = Board.boardHeight;
    Method captureTables = Zobrist.class.getDeclaredMethod("captureTables");
    captureTables.setAccessible(true);
    Object previousTables = captureTables.invoke(null);
    try {
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      Zobrist.init();
      BoardHistoryList history =
          new BoardHistoryList(BoardData.empty(19, 19)) {
            @Override
            public void addOrGoto(BoardData data, boolean branch, boolean change) {
              add(data);
            }

            @Override
            public void addOrGoto(BoardData data, boolean branch) {
              add(data);
            }
          };
      String sgf;
      try (var input =
          TeacherPromptBuilderTest.class.getResourceAsStream(
              "/featurecat/lizzie/rules/issue223-reporter-205-moves.sgf")) {
        sgf = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
      // The fixture is a single 19x19 main line of 205 explicit B/W moves. Replay those
      // moves with production capture/ko rules, without foreground SGF/UI callbacks.
      var moves = Pattern.compile(";([BW])\\[([a-s])([a-s])\\]").matcher(sgf);
      int count = 0;
      while (moves.find()) {
        int x = moves.group(2).charAt(0) - 'a';
        int y = moves.group(3).charAt(0) - 'a';
        BoardData before = history.getData();
        before.bestMoves =
            List.of(
                candidate("A19", 60),
                candidate("A18", 59),
                candidate("A17", 58),
                candidate(Board.convertCoordinatesToName(x, y), 60 - (count + 1) / 10.0));
        history.place(
            x,
            y,
            "B".equals(moves.group(1)) ? Stone.BLACK : Stone.WHITE,
            false,
            false,
            false,
            false,
            false);
        assertEquals(++count, history.getData().moveNumber, "The recorded move must replay.");
      }
      assertEquals(205, count);
      return mode == TeacherPromptBuilder.Mode.WHOLE_GAME
          ? TeacherEvidence.wholeGame(history.getStart())
          : TeacherEvidence.mainLine(history.getStart(), 120, 205);
    } finally {
      Board.boardWidth = previousWidth;
      Board.boardHeight = previousHeight;
      Method restoreTables =
          Zobrist.class.getDeclaredMethod("restoreTables", previousTables.getClass());
      restoreTables.setAccessible(true);
      restoreTables.invoke(null, previousTables);
    }
  }

  private static MoveData candidate(String coordinate, double winrate) {
    MoveData candidate = new MoveData();
    candidate.coordinate = coordinate;
    candidate.winrate = winrate;
    candidate.scoreMean = 3;
    candidate.playouts = 1000;
    candidate.variation = List.of(coordinate);
    return candidate;
  }

  private static String systemPrompt(TeacherSettings.Snapshot snapshot) {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(1, "B", 100, "", OptionalDouble.empty(), List.of());
    return TeacherPromptBuilder.forPosition(position, Locale.SIMPLIFIED_CHINESE, snapshot)
        .get(0)
        .content;
  }

  private static TeacherSettings.Snapshot snapshot(String rankMode, int rankNum) {
    return new TeacherSettings.Snapshot(
        "https://example.com/v1",
        "test-model",
        false,
        false,
        false,
        "none",
        rankMode,
        rankNum,
        0,
        1,
        1,
        1);
  }
}
