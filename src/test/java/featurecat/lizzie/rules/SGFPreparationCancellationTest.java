package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SGFPreparationCancellationTest {
  private static final SGFParser.ParseOptions OPTIONS =
      new SGFParser.ParseOptions(true, false, false, true, 3, 2, 7.5);

  @ParameterizedTest
  @ValueSource(strings = {"", "(;SZ[9];B[aa])", "(;AB[aa:])"})
  void cancellationIsDistinctFromAnEmptyOrInvalidSgf(String input) {
    assertThrows(CancellationException.class, () -> SGFParser.prepareFileContent(input, () -> true));
    assertThrows(CancellationException.class, () -> SGFParser.prepareFileGame(null, OPTIONS, () -> true));
  }

  static Stream<Arguments> syntaxWork() {
    return Stream.of(
        Arguments.of("valueEnd", "(;SZ[9]C[" + "x".repeat(32 * 1024) + "])"),
        Arguments.of("normalizeStructuralNewlines", "(;SZ[9]" + "\\nC[note]".repeat(2048) + ")"),
        Arguments.of("normalizeStructuralNewlines", "(" + "\\n".repeat(16 * 1024) + ";SZ[9])"),
        Arguments.of("normalizeStructuralNewlines", "(" + "\\r\\n".repeat(16 * 1024) + ";SZ[9])"),
        Arguments.of("expandSetupRange", "(;SZ[100]AB[0_0:99_99])"),
        Arguments.of("parseNormalized", "(;SZ[9]" + ";B[];W[]".repeat(2048) + ")"));
  }

  @ParameterizedTest
  @MethodSource("syntaxWork")
  void longSyntaxAndSetupExpansionCanStopBeforeProducingADocument(String method, String input) {
    BooleanSupplier cancelled = cancelDuring(SgfDocument.class.getName(), method, 8);
    assertThrows(CancellationException.class, () -> SGFParser.prepareFileContent(input, cancelled));
    // The same valid input still has the existing syntax when no cancellation is requested.
    var synchronous = SGFParser.prepareFileContent(input);
    var asynchronous = SGFParser.prepareFileContent(input, () -> false);
    assertNotNull(synchronous);
    assertEquals(synchronous.events(), asynchronous.events());
    assertEquals(synchronous.width(), asynchronous.width());
    assertEquals(synchronous.height(), asynchronous.height());
  }

  static Stream<Arguments> treeWork() {
    String header = "LZ[KataGo 45 100 2.5 1.2 0\nmove C3 visits 100 winrate 5500 pv C3 ";
    return Stream.of(
        Arguments.of("featurecat.lizzie.rules.SgfDocument$Event", "legacyValue",
            "(;SZ[9]C[" + "escaped\\n".repeat(4096) + "];B[aa])"),
        Arguments.of(SGFParser.class.getName(), "decodeGameInfoText",
            "(;SZ[9]PB[x" + "\\n".repeat(16 * 1024) + "];B[aa])"),
        Arguments.of(SGFParser.class.getName(), "applyAnalysisHeader",
            "(;SZ[9]LZ[KataGo 45 0 2.5 1.2 0 " + "extension ".repeat(20 * 1024) + "])"),
        Arguments.of(SGFParser.class.getName(), "parseHeaderWrn",
            "(;SZ[9]LZ[KataGo 45 0 2.5 1.2 0 " + "extension ".repeat(20 * 1024) + "])"),
        Arguments.of(SGFParser.class.getName(), "parseAnalysisPlayouts",
            "(;SZ[9]LZ[KataGo 45 " + "0".repeat(20 * 1024) + "])"),
        Arguments.of(SGFParser.class.getName(), "captureVariationTokens",
            "(;SZ[9];B[aa]" + header + "D4 ".repeat(4096) + "])"),
        Arguments.of(SGFParser.class.getName(), "captureVariationTokens",
            "(;SZ[9];B[aa]" + header + "pvVisits " + "100 ".repeat(4096) + "])"),
        Arguments.of(SGFParser.class.getName(), "parseOwnershipValues",
            "(;SZ[9];B[aa]" + header + "ownership " + "0.25 ".repeat(4096) + "])"));
  }

  @ParameterizedTest
  @MethodSource("treeWork")
  void longSinglePropertiesStopInsideThePrivateBuildAndLeaveLiveOwnersUntouched(
      String parserClass, String method, String input) throws Exception {
    var document = SGFParser.prepareFileContent(input);
    assertNotNull(document);
    try (var harness = RulesLayerTestHarness.open(19)) {
      var liveHistory = Lizzie.board.getHistory();
      var liveNode = liveHistory.getCurrentHistoryNode();
      var tables = Zobrist.captureTables();
      var engine = Lizzie.leelaz;
      assertThrows(CancellationException.class, () -> SGFParser.prepareFileGame(
          document, OPTIONS, cancelDuring(parserClass, method, 16)));
      assertSame(liveHistory, Lizzie.board.getHistory());
      assertSame(liveNode, liveHistory.getCurrentHistoryNode());
      assertSame(tables, Zobrist.captureTables());
      assertSame(engine, Lizzie.leelaz);
      assertEquals(19, Board.boardWidth);
      assertEquals(19, Board.boardHeight);
      assertNotNull(SGFParser.prepareFileGame(document, OPTIONS, () -> false),
          "a cancelled build must not poison the next preparation on the same thread");
      assertSame(liveHistory, Lizzie.board.getHistory());
    }
  }

  @Test
  void synchronousCompatibilityApisDoNotInterpretThreadInterruptsAsRequestCancellation() {
    boolean interrupted = Thread.currentThread().isInterrupted();
    Thread.currentThread().interrupt();
    try {
      var document = SGFParser.prepareFileContent("(;SZ[9]C[kept];B[aa])");
      assertNotNull(document);
      assertNotNull(SGFParser.prepareFileGame(document, OPTIONS));
      assertNull(SGFParser.prepareFileContent("(;AB[aa:])"));
      assertNull(SGFParser.prepareFileContent(""));
      assertNull(SGFParser.prepareFileGame(null, OPTIONS));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      if (!interrupted) Thread.interrupted();
    }
  }

  private static BooleanSupplier cancelDuring(String parserClass, String method, int afterPolls) {
    AtomicInteger polls = new AtomicInteger();
    return () -> StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
        frame.getClassName().equals(parserClass) && frame.getMethodName().equals(method)))
        && polls.incrementAndGet() >= afterPolls;
  }
}
