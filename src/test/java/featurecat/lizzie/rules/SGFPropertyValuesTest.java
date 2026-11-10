package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SGFPropertyValuesTest {
  private static final List<String> PROPERTIES =
      List.of(
          "LB[aa:A,B:C][bb:한국어\\]표식\\\\끝][cc:]",
          "AR[aa:bb][cc:dd]",
          "LN[aa:cc][bb:dd]",
          "TB[aa][bb]",
          "TW[cc][dd]",
          "VW[aa][ee]",
          "DD[aa][bb]",
          "XX[first,one][second:two][]");
  private static final String MARKS = String.join("", PROPERTIES);

  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"detached", "string", "edit", "file"})
  void smartGoNamesKeepMetadataSetupMovesAndValuesAcrossImportPaths(String entrypoint)
      throws Exception {
    String standard =
        "(;SZ[5]PB[Alice lowerCase]PW[Bob]AB[aa][ab]PL[W]"
            + "C[PlayerBlack is value text]XX[first,one][second:two][]Xé_2[mixedCase]"
            + ";W[ee]LB[ee:A,z];B[])";
    String smartGo =
        "(;SZ[5]PlayerBlack[Alice lowerCase]PlayerWhite[Bob]AddBlack[aa:ab]PL[W]"
            + "Comment[PlayerBlack is value text]eXtraX[first,one][second:two][]"
            + "preXéz_2tail[mixedCase];White[ee]LaBel[ee:A,z];Black[])";
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList expected = SGFParser.parseSgf(standard, true);
      BoardHistoryList actual = read(smartGo, entrypoint);
      assertNotNull(actual);
      assertEquals("Alice lowerCase", actual.getGameInfo().getPlayerBlack());
      assertEquals("Bob", actual.getGameInfo().getPlayerWhite());
      assertEquals(
          List.of("first,one", "second:two", ""),
          actual.getStart().getData().getPropertyValues("XX"));
      assertEquals("mixedCase", actual.getStart().getData().getProperty("Xé_2"));
      assertEquals(
          List.of("ee:A,z"),
          actual.getStart().next().orElseThrow().getData().getPropertyValues("LB"));
      SGFParserSemanticRoundTripTest.assertTreeSemanticsEqual(expected, actual);
    }
  }

  @Test
  void propertyStringNamesStripOnlyAsciiLowercaseAndDistinguishEmptyNamesFromRepeatedValues() {
    Map<String, List<String>> properties = new LinkedHashMap<>();
    SGFParser.addProperties(
        properties,
        "PlayerBlack[Alice a-z]preXéz_2tail[first,one][second:two][]LZOP2[digits]"
            + "XX[before]note[separate][continued]XX[after]`az{[boundaries]");
    assertEquals(
        Map.of(
            "PB", List.of("Alice a-z"),
            "Xé_2", List.of("first,one", "second:two", ""),
            "LZOP2", List.of("digits"),
            "XX", List.of("before", "after"),
            "", List.of("separate", "continued"),
            "`{", List.of("boundaries")),
        properties);
  }

  static Stream<Arguments> firstNodeCases() {
    return Stream.of("detached", "string", "edit", "file")
        .flatMap(
            entry ->
                Stream.of("", "AB[bb]PL[W]", "B[bb]", "W[]", "DD[true]", "DD[true]B[bb]")
                    .map(action -> Arguments.of(entry, action)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"detached", "string", "edit", "file"})
  void firstNodeGameInformationStaysOnRootRegardlessOfMoveOrder(String entrypoint)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      String info = "RU[Chinese]DT[2026-11-09]EV[event]GC[game comment]";
      for (boolean before : List.of(true, false)) {
        BoardHistoryList history =
            read(
                "(;SZ[5]"
                    + (before ? info : "")
                    + "LB[aa:A]B[bb]LB[cc:B]"
                    + (before ? "" : info)
                    + ";W[dd])",
                entrypoint);
        for (int round = 0; round < 2; round++) {
          BoardData root = history.getStart().getData();
          BoardData move = history.getStart().next().orElseThrow().getData();
          assertEquals("Chinese", root.getProperty("RU"));
          assertEquals("2026-11-09", history.getGameInfo().getDate());
          assertEquals("event", root.getProperty("EV"));
          assertEquals("game comment", root.getProperty("GC"));
          for (String key : List.of("RU", "DT", "EV", "GC"))
            assertTrue(move.getPropertyValues(key).isEmpty(), key);
          assertEquals(List.of("aa:A", "cc:B"), move.getPropertyValues("LB"));
          assertTrue(root.getPropertyValues("LB").isEmpty());
          Lizzie.board.setHistory(history);
          history = SGFParser.parseSgf(SGFParser.saveToString(false), true);
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("firstNodeCases")
  void firstNodeMetadataKeepsItsOwner(String entrypoint, String action) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      BoardHistoryList history =
          read(
              "(;SZ[5]RU[Chinese]DT[2026-11-09]PB[Black]PW[White]KM[6.5]"
                  + "C[note;B[ee\\]]N[name]MN[7]LB[aa:A]XX[first]"
                  + "LZ[Main 45 100 2.5 1.2 0]"
                  + action
                  + "LB[cc:B]XX[second][]LZ2[Sub 41 150 2.1 0.5 0.8])",
              entrypoint);
      boolean rootOwnsMetadata = action.isEmpty() || action.startsWith("AB");
      for (int round = 0; round < 2; round++) {
        BoardData root = history.getStart().getData();
        BoardData owner =
            rootOwnsMetadata
                ? root
                : history.getStart().getVariations().stream()
                    .map(BoardHistoryNode::getData)
                    .filter(data -> data.dummy == action.equals("DD[true]"))
                    .findFirst()
                    .orElseThrow();
        assertEquals(List.of("aa:A", "cc:B"), owner.getPropertyValues("LB"));
        assertEquals(List.of("first", "second", ""), owner.getPropertyValues("XX"));
        assertEquals("note;B[ee]", owner.comment);
        assertEquals("name", owner.getProperty("N"));
        assertEquals("7", owner.getProperty("MN"));
        assertEquals(100, owner.getPlayouts());
        assertEquals(150, owner.getPlayouts2());
        assertEquals("Chinese", root.getProperty("RU"));
        assertEquals("2026-11-09", history.getGameInfo().getDate());
        assertEquals("Black", history.getGameInfo().getPlayerBlack());
        if (!rootOwnsMetadata) {
          for (String key : List.of("LB", "XX", "N", "MN"))
            assertTrue(root.getPropertyValues(key).isEmpty(), key);
          assertTrue(root.comment.isEmpty());
          assertEquals(0, root.getPlayouts());
          assertEquals(0, root.getPlayouts2());
        }
        Lizzie.board.setHistory(history);
        history = SGFParser.parseSgf(SGFParser.saveToString(false), true);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "detached,root",
    "string,root",
    "edit,root",
    "file,root",
    "detached,move",
    "string,move",
    "edit,move",
    "file,move",
    "detached,setup",
    "string,setup",
    "edit,setup",
    "file,setup"
  })
  void allImportPathsKeepSeparateRawValuesThroughRepeatedSave(String entrypoint, String owner)
      throws Exception {
    String source =
        switch (owner) {
          case "root" -> "(;SZ[5]" + MARKS + ";B[ee])";
          case "move" -> "(;SZ[5];B[ee]" + MARKS + ";W[dd])";
          default -> "(;SZ[5];B[ee];AB[aa:ab]PL[W]" + MARKS + ";W[dd])";
        };
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = read(source, entrypoint);
      for (int round = 0; round < 2; round++) {
        Lizzie.board.setHistory(history);
        String saved = SGFParser.saveToString(false);
        for (String property : PROPERTIES) assertTrue(saved.contains(property), saved);
        history = SGFParser.parseSgf(saved, true);
        assertNotNull(history);
        BoardHistoryNode node = history.getStart();
        if (!owner.equals("root")) node = node.next().orElseThrow();
        if (owner.equals("setup")) node = node.next().orElseThrow();
        assertEquals(
            List.of("first,one", "second:two", ""), node.getData().getProperties().get("XX"));
        assertFalse(node.getData().dummy);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"before,false", "after,false", "before,true", "after,true"})
  void branchSetupRetainsValuesOnItsOwnNodeAndLeavesExistingBranchAlone(
      String order, boolean smartGoNames)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[ee]XX[original])", true);
      BoardHistoryNode original = history.getStart().next().orElseThrow();
      String marks =
          smartGoNames ? MARKS.replace("LB[", "LaBel[").replace("XX[", "eXtraX[") : MARKS;
      String setup = (smartGoNames ? "AddBlack" : "AB") + "[aa:ab]PL[W]";
      String branch =
          ";"
              + (order.equals("before") ? marks + setup : setup + marks)
              + ";"
              + (smartGoNames ? "White" : "W")
              + "[dd]";
      assertTrue(SGFParser.parseBranch(history, branch) >= 0);
      assertEquals("original", original.getData().getProperty("XX"));
      BoardHistoryNode added = history.getStart().getVariation(1).orElseThrow();
      assertEquals(
          List.of("first,one", "second:two", ""), added.getData().getProperties().get("XX"));
      assertFalse(history.getStart().getData().getProperties().containsKey("XX"));
      Lizzie.board.setHistory(history);
      String saved = SGFParser.saveToString(false);
      for (String property : PROPERTIES) assertTrue(saved.contains(property), saved);
      BoardHistoryList reopened = SGFParser.parseSgf(saved, true);
      assertEquals(
          List.of("first,one", "second:two", ""),
          reopened.getStart().getVariation(1).orElseThrow().getData().getProperties().get("XX"));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "detached,AB[aa:ab]PL[W]",
    "string,AB[aa:ab]PL[W]",
    "edit,AB[aa:ab]PL[W]",
    "file,AB[aa:ab]PL[W]",
    "detached,B[dd]",
    "string,B[dd]",
    "edit,B[dd]",
    "file,B[dd]"
  })
  void valuesBeforeAndAfterTheNodeActionKeepTheirOwnerAndOrder(String entrypoint, String action)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history =
          read(
              "(;SZ[5];B[ee]XX[parent];XX[first]" + action + "XX[second][third];W[cc])",
              entrypoint);
      BoardHistoryNode parent = history.getStart().next().orElseThrow();
      BoardHistoryNode owner = parent.next().orElseThrow();
      assertEquals(List.of("parent"), parent.getData().getPropertyValues("XX"));
      assertEquals(List.of("first", "second", "third"), owner.getData().getPropertyValues("XX"));
      Lizzie.board.setHistory(history);
      assertTrue(SGFParser.saveToString(false).contains("XX[first][second][third]"));
    }
  }

  @Test
  void branchValuesBeforeAndAfterTheMoveKeepTheirOrder() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[ee]XX[original])", true);
      assertTrue(SGFParser.parseBranch(history, ";XX[first]B[dd]XX[second][third]") >= 0);
      assertEquals(
          List.of("first", "second", "third"),
          history.getStart().getVariation(1).orElseThrow().getData().getPropertyValues("XX"));
      assertFalse(history.getStart().getData().getProperties().containsKey("XX"));
    }
  }

  @Test
  void editingAndClearingMarksKeepsOtherValuesAndSetupMetadata() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardData data =
          SGFParser.parseSgf("(;SZ[5]AB[aa][bb]" + MARKS + ")", true).getStart().getData();
      data.removeMarkupAt("aa");
      assertEquals(List.of("bb:한국어\\]표식\\\\끝", "cc:"), data.getPropertyValues("LB"));
      data.addProperty("LB", "dd:more,words:here");
      assertEquals(
          List.of("bb:한국어\\]표식\\\\끝", "cc:", "dd:more,words:here"), data.getPropertyValues("LB"));
      data.clearMarkup();
      assertEquals(List.of(), data.getPropertyValues("LB"));
      assertEquals(List.of("aa", "bb"), data.getPropertyValues("AB"));
      assertEquals(List.of("aa:bb", "cc:dd"), data.getPropertyValues("AR"));
      assertEquals(List.of("aa", "bb"), data.getPropertyValues("TB"));
      assertEquals(List.of("first,one", "second:two", ""), data.getPropertyValues("XX"));
    }
  }

  @Test
  void rootMetadataIsReplacedAndEmptyValuesRemainExplicit() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]KM[6.5]PB[old]VW[]DD[]XX[])", true);
      history.getGameInfo().setPlayerBlack("new,name");
      history.getGameInfo().setKomiNoMenu(7.5);
      Lizzie.board.setHistory(history);
      for (int round = 0; round < 2; round++) {
        String saved = SGFParser.saveToString(false);
        assertTrue(saved.contains("PB[new,name]"), saved);
        assertTrue(saved.contains("KM[7.5]"), saved);
        assertFalse(saved.contains("[old]"), saved);
        for (String key : List.of("SZ", "KM", "PB", "CA", "AP", "VW", "DD", "XX")) {
          assertEquals(1, saved.split(key + "\\[", -1).length - 1, key + ": " + saved);
        }
        assertTrue(saved.contains("VW[]"), saved);
        assertTrue(saved.contains("DD[]"), saved);
        assertTrue(saved.contains("XX[]"), saved);
        history = SGFParser.parseSgf(saved, true);
        Lizzie.board.setHistory(history);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"19, aa, 'aa ', bb", "52, 1_2, 01_02, 3_4"})
  void removingMarksUsesDecodedCoordinatesAndPreservesOtherRawValues(
      int size, String point, String alias, String other) throws Exception {
    try (var ignored = RulesLayerTestHarness.open(size)) {
      for (String key : List.of("LB", "CR", "SQ", "MA", "TR")) {
        String suffix = key.equals("LB") ? ":A,B:C" : "";
        String retained = other + suffix;
        BoardHistoryList history =
            SGFParser.parseSgf(
                "(;SZ["
                    + size
                    + "]"
                    + key
                    + "["
                    + alias
                    + suffix
                    + "]["
                    + point
                    + suffix
                    + "]["
                    + retained
                    + "][][a]XX[untouched,raw:value])",
                true);
        BoardData data = history.getStart().getData();
        data.removeMarkupAt(point);
        assertEquals(List.of(retained, "", "a"), data.getPropertyValues(key), key);
        assertEquals(List.of("untouched,raw:value"), data.getPropertyValues("XX"));

        Lizzie.board.setHistory(history);
        BoardData reopened =
            SGFParser.parseSgf(SGFParser.saveToString(false), true).getStart().getData();
        assertEquals(data.getPropertyValues(key), reopened.getPropertyValues(key), key);
        assertEquals(data.getPropertyValues("XX"), reopened.getPropertyValues("XX"));
      }
    }
  }

  @Test
  void propertyCopiesAndCapturedSaveDoNotFollowLaterAppends() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + MARKS + ")", true);
      Lizzie.board.setHistory(history);
      BoardData root = history.getStart().getData();
      BoardData clone = root.clone();
      String saved = SGFParser.captureSave(Lizzie.board, history, false);
      root.addProperty("XX", "later");
      root.addProperty("LB", "dd:later,mark");
      assertEquals(List.of("first,one", "second:two", ""), clone.getProperties().get("XX"));
      assertFalse(saved.contains("later"), saved);
      assertEquals(
          List.of("first,one", "second:two", ""),
          SGFParser.parseSgf(saved, true).getStart().getData().getProperties().get("XX"));
    }
  }

  private BoardHistoryList read(String source, String entrypoint) throws Exception {
    switch (entrypoint) {
      case "detached":
        return SGFParser.parseSgf(source, true);
      case "string":
        assertTrue(SGFParser.loadFromString(source, false));
        break;
      case "edit":
        assertTrue(SGFParser.loadFromStringforedit(source));
        break;
      default:
        Path input = directory.resolve("properties.sgf");
        Files.writeString(input, source);
        assertTrue(SGFParser.load(input.toString(), false, false));
        SwingUtilities.invokeAndWait(() -> {});
    }
    return Lizzie.board.getHistory();
  }
}
