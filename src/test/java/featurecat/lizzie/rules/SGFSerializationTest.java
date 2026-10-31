package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SGFSerializationTest {
  @ParameterizedTest
  @CsvSource({"19,19,18,18,ss", "27,31,26,30,AE", "51,51,50,50,YY",
      "52,5,51,4,51_4", "5,52,4,51,4_51", "100,60,99,59,99_59"})
  void coordinatesKeepAlphabetAndLargeBoardEncoding(
      int width, int height, int x, int y, String expected) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Board.boardWidth = width;
      Board.boardHeight = height;
      assertEquals(expected, SGFParser.asCoord(new int[] {x, y}));
    }
  }

  @ParameterizedTest
  @CsvSource({"19:13,am,sm,jg", "27:31,aE,AE,Az", "52:5,0_4,51_4,26_2",
      "5:52,0_51,4_51,2_26"})
  void saveUsesHistoryDimensionsForSetupMovesAndPass(
      String size, String black, String white, String move) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[" + size + "]AB[" + black
          + "]AW[" + white + "];B[" + move + "];W[])", true);
      assertEquals(5, Board.boardWidth, "detached history differs from the active board");
      String saved = SGFParser.captureSave(Lizzie.board, history, false);
      assertTrue(saved.contains("SZ[" + size + "]"), saved);
      assertTrue(saved.contains("AB[" + black + "]"), saved);
      assertTrue(saved.contains("AW[" + white + "]"), saved);
      assertTrue(saved.contains(";B[" + move + "]"), saved);
      assertTrue(saved.contains(";W[]"), saved);
      BoardHistoryList reopened = SGFParser.parseSgf(saved, true);
      assertEquals(2, reopened.getEnd().getData().moveNumber);
      assertTrue(reopened.getEnd().getData().isPassNode());
      assertArrayEquals(history.getStart().getData().stones,
          reopened.getStart().getData().stones);
      assertArrayEquals(history.getStart().next().orElseThrow().getData().lastMove.orElseThrow(),
          reopened.getStart().next().orElseThrow().getData().lastMove.orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void analysisOutputKeepsBothEngineFormatsAndLongTokenLists(boolean kata) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardData data = Lizzie.board.getData();
      MoveData move = move();
      List<String> pv = new ArrayList<>();
      List<String> visits = new ArrayList<>();
      for (int i = 0; i < 1024; i++) {
        pv.add(i % 2 == 0 ? "C3" : "pass");
        visits.add(Integer.toString(2048 - i));
      }
      move.variation = pv;
      move.pvVisits = visits;
      MoveData other = move();
      other.coordinate = "D4";
      other.edgeVisits = -1;
      other.variation = List.of("D4");
      data.bestMoves = List.of(move, other);
      data.bestMoves2 = List.of(move, other);
      data.isKataData = kata;
      data.isKataData2 = kata;
      String common = "move C3 visits 100 winrate 5500 prior 1250";
      String suffix = " order 3 edgeVisits 7 pv " + String.join(" ", pv);
      String second = "move D4 visits 100 winrate 5500 prior 1250";
      assertEquals(common + (kata ? " scoreMean 1.23" : "") + suffix
          + (kata ? " pvVisits " + String.join(" ", visits) : "")
          + " info " + second + (kata ? " scoreMean 1.23" : "") + " order 3 pv D4",
          data.bestMovesToString());
      assertEquals(common + (kata ? " scoreMean 1.234" : "") + suffix + " info "
          + second + (kata ? " scoreMean 1.234" : "") + " order 3 pv D4 info ",
          data.bestMovesToString2());
      assertEquals(pv, move.variation);
      assertEquals(visits, move.pvVisits);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP"})
  void importedEmptyVisitsCanBeSavedAndReopened(String tag) throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5]" + tag
          + "[KataGo 45 100 2.5 1.2 0\nmove C3 visits 100 pv C3 pvVisits])", true);
      assertEquals(List.of(), history.getStart().getData().bestMoves.get(0).pvVisits);
      Lizzie.board.setHistory(history);
      for (int round = 0; round < 2; round++) {
        String saved = SGFParser.saveToString(false);
        assertTrue(saved.contains("pv C3 pvVisits "), "empty visits must not drop the analysis tag");
        history = SGFParser.parseSgf(saved, true);
        MoveData move = history.getStart().getData().bestMoves.get(0);
        assertEquals(List.of("C3"), move.variation);
        assertEquals(List.of(), move.pvVisits);
        assertEquals(100, move.playouts);
        Lizzie.board.setHistory(history);
      }
    }
  }

  @Test
  void emptyEnginePvDoesNotPreventSerialization() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardData data = Lizzie.board.getData();
      MoveData move = MoveData.fromInfoKatago(
          "move C3 visits 100 winrate 0.55 prior 0.125 pv pvVisits");
      assertEquals(List.of(), move.variation);
      assertEquals(List.of(), move.pvVisits);
      data.bestMoves = List.of(move);
      data.bestMoves2 = List.of(move);
      data.isKataData = true;
      data.isKataData2 = true;
      assertTrue(data.bestMovesToString().endsWith(" pv  pvVisits "));
      assertTrue(data.bestMovesToString2().endsWith(" pv  info "));
      move.variation = null;
      move.pvVisits = null;
      assertTrue(data.bestMovesToString().endsWith(" pv "));
    }
  }

  @ParameterizedTest
  @CsvSource({"0.0,0.00", "-0.0,-0.00", "0.0049,0.00", "-0.0049,-0.00",
      "0.005,0.01", "-0.005,-0.01", "1.125,1.13", "-1.125,-1.13",
      "2.675,2.68", "-2.675,-2.68", "NaN,NaN", "Infinity,Infinity", "-Infinity,-Infinity"})
  void candidateScoreKeepsDecimalRoundingAndSpecialText(double score, String expected)
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardData data = Lizzie.board.getData();
      MoveData candidate = move();
      candidate.scoreMean = score;
      candidate.variation = List.of("C3");
      data.isKataData = true;
      data.bestMoves = List.of(candidate);
      assertEquals("move C3 visits 100 winrate 5500 prior 1250 scoreMean " + expected
          + " order 3 edgeVisits 7 pv C3", data.bestMovesToString());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"en-US", "de-DE", "ar-EG"})
  void candidateListsMatchLegacyFormattingAcrossCallsAndDefaultLocales(String language)
      throws Exception {
    Locale previous = Locale.getDefault(Locale.Category.FORMAT);
    try (var ignored = RulesLayerTestHarness.open()) {
      Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(language));
      BoardData data = Lizzie.board.getData();
      List<Double> scores = new ArrayList<>(List.of(
          0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
          Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL, -Double.MIN_NORMAL,
          Double.MAX_VALUE, -Double.MAX_VALUE, 1e20, -1e-20));
      for (double boundary : new double[] {0.005, -0.005, 1.125, -1.125, 2.675, -2.675}) {
        scores.add(Math.nextDown(boundary));
        scores.add(boundary);
        scores.add(Math.nextUp(boundary));
      }
      Random random = new Random(0x53434f52454cL);
      for (int i = 0; i < 256; i++) scores.add(Double.longBitsToDouble(random.nextLong()));
      List<MoveData> moves = new ArrayList<>();
      StringJoiner expected = new StringJoiner(" info ");
      for (double score : scores) {
        MoveData candidate = move();
        candidate.scoreMean = score;
        candidate.variation = List.of("C3");
        moves.add(candidate);
        // The old public output uses String.format for every candidate independently.
        expected.add("move C3 visits 100 winrate 5500 prior 1250 scoreMean "
            + String.format(Locale.ENGLISH, "%.2f", score) + " order 3 edgeVisits 7 pv C3");
      }
      data.isKataData = true;
      data.bestMoves = moves;
      assertEquals(expected.toString(), data.bestMovesToString());
      assertEquals(expected.toString(), data.bestMovesToString(), "no text leaks between calls");
      data.bestMoves = List.of();
      assertEquals("", data.bestMovesToString());
      data.bestMoves = List.of(moves.get(1));
      data.isKataData = false;
      assertEquals("move C3 visits 100 winrate 5500 prior 1250 order 3 edgeVisits 7 pv C3",
          data.bestMovesToString());
      data.isKataData = true;
      assertEquals("move C3 visits 100 winrate 5500 prior 1250 scoreMean -0.00"
          + " order 3 edgeVisits 7 pv C3", data.bestMovesToString());
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, previous);
    }
  }

  private static MoveData move() {
    MoveData move = new MoveData();
    move.coordinate = "C3";
    move.playouts = 100;
    move.winrate = 55;
    move.policy = 12.5;
    move.scoreMean = 1.234;
    move.order = 3;
    move.edgeVisits = 7;
    return move;
  }
}
