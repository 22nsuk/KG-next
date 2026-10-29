package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisWrnSgfTest {
  private RulesLayerTestHarness harness;
  private ResourceBundle previousResources;

  @BeforeEach
  void setUp() throws Exception {
    previousResources = Lizzie.resourceBundle;
    harness = RulesLayerTestHarness.open();
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.ENGLISH);
  }

  @AfterEach
  void tearDown() {
    Lizzie.resourceBundle = previousResources;
    if (harness != null) harness.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"formatComment", "formatCommentForGame", "formatCommentPk", "formatComment2"})
  void everySummaryReadsItsEvaluationSlotRatherThanTheCurrentEngine(String formatter)
      throws Exception {
    BoardHistoryList history = parse(";B[aa]" + tags(false, "", ""));
    Lizzie.board.setHistory(history);
    BoardHistoryNode node = history.getStart().next().orElseThrow();
    BoardData data = node.getData();
    data.setPrimaryAnalysisProvenance(source(0.25));
    data.setSecondaryAnalysisProvenance(source(1.25));
    data.setPrimaryOwnershipProvenance(source(1.5));
    data.setSecondaryOwnershipProvenance(source(1.75));
    Lizzie.leelaz.wrn = 1.9;
    boolean secondary = formatter.equals("formatComment2");
    String expected = Lizzie.resourceBundle.getString("SGFParse.wrn") + (secondary ? "1.25" : "0.25");
    String summary = format(formatter, node);
    assertTrue(summary.contains(expected), summary);
    assertFalse(summary.contains("1.9"), summary);
    if (secondary) data.setSecondaryAnalysisProvenance(source(0.0));
    else data.setPrimaryAnalysisProvenance(source(0.0));
    assertTrue(format(formatter, node).contains(Lizzie.resourceBundle.getString("SGFParse.wrn") + "0.0"));
    if (secondary) data.setSecondaryAnalysisProvenance(source(null));
    else data.setPrimaryAnalysisProvenance(null);
    assertFalse(format(formatter, node).contains(Lizzie.resourceBundle.getString("SGFParse.wrn")));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptedEvaluationKeepsItsWrnAcrossOwnershipBackfillAndEngineChanges(boolean secondary)
      throws Exception {
    Lizzie.config.enableLizzieCache = true;
    BoardData data = Lizzie.board.getData();
    ObservedEngine engine = new ObservedEngine(source(0.25));
    engine.wrn = 1.9;
    Object stream = new Object();
    assertEquals(BoardData.AnalysisAdoption.FULL,
        data.adoptOrdinaryAnalysis(moves(500), "display-only-label", engine, 500, 500,
            List.of(0.1), stream, secondary, false));
    engine.observed = source(1.25);
    assertEquals(BoardData.AnalysisAdoption.OWNERSHIP_ONLY,
        data.adoptOrdinaryAnalysis(moves(50), "new label", engine, 50, 50,
            List.of(0.2), new Object(), secondary, false));
    assertEquals(0.25, evaluation(data, secondary).wrn());
    assertEquals(1.25, ownership(data, secondary).wrn());
    assertEquals(BoardData.AnalysisAdoption.REJECTED,
        data.adoptOrdinaryAnalysis(moves(40), "new label", engine, 40, 40,
            null, new Object(), secondary, false));
    BoardData saved = reopen(Lizzie.board.getHistory()).getStart().getData();
    assertEquals(0.25, evaluation(saved, secondary).wrn());
    assertEquals(1.25, ownership(saved, secondary).wrn());
    assertEquals(BoardData.AnalysisAdoption.FULL,
        data.adoptOrdinaryAnalysis(moves(600), "new label", engine, 600, 600,
            null, stream, secondary, false));
    assertEquals(1.25, evaluation(data, secondary).wrn());
    assertEquals(1.25, ownership(data, secondary).wrn());
    assertNull(evaluation(data, !secondary));
    engine.observed = source(null);
    assertEquals(BoardData.AnalysisAdoption.FULL,
        data.adoptOrdinaryAnalysis(moves(700), "unknown WRN", engine, 700, 700,
            null, new Object(), secondary, false));
    assertNull(evaluation(data, secondary).wrn());
    assertNull(ownership(data, secondary));
  }

  @ParameterizedTest
  @CsvSource({"root,false", "root,true", "move,false", "move,true", "setup,false", "setup,true"})
  void dualSlotWrnAndInvalidationSurviveCopiesAndRepeatedSgfRoundTrips(String kind, boolean invalidated)
      throws Exception {
    String position = kind.equals("root") ? "" : kind.equals("move") ? ";B[aa]" : ";AB[bb]PL[W]";
    BoardHistoryList history = parse(position + tags(kind.equals("root"), " wrn=0", " wrn=2"));
    BoardData data = positionData(history, kind);
    if (invalidated) {
      data.invalidatePrimaryAnalysis();
      data.invalidateSecondaryAnalysis();
    }
    BoardData copied = BoardData.empty(5, 5);
    copied.copyAnalysisPayloadFrom(data);
    BoardData synced = BoardData.empty(5, 5);
    synced.sync(data);
    for (BoardData copy : List.of(data.clone(), copied, synced)) {
      assertHeaderSource(copy.primaryAnalysisProvenance(), 0.0);
      assertHeaderSource(copy.secondaryAnalysisProvenance(), 2.0);
      assertEquals(invalidated, copy.isChanged);
      assertEquals(invalidated, copy.isChanged2);
    }
    for (int round = 0; round < 3; round++) {
      data = positionData(history, kind);
      assertHeaderSource(data.primaryAnalysisProvenance(), 0.0);
      assertHeaderSource(data.secondaryAnalysisProvenance(), 2.0);
      assertNull(data.primaryOwnershipProvenance());
      assertNull(data.secondaryOwnershipProvenance());
      assertEquals(120, data.getPlayouts());
      assertEquals(80, data.getPlayouts2());
      assertEquals("C3", data.bestMoves.get(0).coordinate);
      assertEquals("D4", data.bestMoves2.get(0).coordinate);
      assertEquals(invalidated, data.isChanged);
      assertEquals(invalidated, data.isChanged2);
      history = reopen(history);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"detached", "edit", "branch"})
  void setupTransfersCarryHeaderAndBoundKgpRegardlessOfPropertyOrder(String entrypoint) throws Exception {
    String primary = payload(false, " wrn=0.25");
    String secondary = payload(true, " wrn=0");
    String kgp = "KGP[" + metadata(primary, source(1.5), true) + "]";
    String analysis = "LZ[" + primary + "]LZ2[" + secondary + "]";
    for (String node : List.of("B[aa]" + kgp + analysis + "AB[bb]PL[W]",
        "B[aa]AB[bb]PL[W]" + analysis + kgp, kgp + analysis + "B[aa]AB[bb]PL[W]")) {
      BoardHistoryList history;
      if (entrypoint.equals("edit")) {
        assertTrue(SGFParser.loadFromStringforedit("(;SZ[5];" + node + ";W[cc])"));
        history = Lizzie.board.getHistory();
      } else if (entrypoint.equals("branch")) {
        history = parse("");
        SGFParser.parseBranch(history, ";" + node + ";W[cc]");
      } else history = parse(";" + node + ";W[cc]");
      BoardHistoryNode move = history.getStart().next().orElseThrow();
      BoardData setup = move.next().orElseThrow().getData();
      assertNull(move.getData().primaryAnalysisProvenance());
      assertNull(move.getData().secondaryAnalysisProvenance());
      assertTrue(setup.isSnapshotNode());
      assertEquals(source(1.5), setup.primaryAnalysisProvenance());
      assertHeaderSource(setup.secondaryAnalysisProvenance(), 0.0);
      assertTrue(setup.isChanged);
      assertFalse(setup.isChanged2);
      setup = reopen(history).getStart().next().orElseThrow().next().orElseThrow().getData();
      assertEquals(source(1.5), setup.primaryAnalysisProvenance());
      assertHeaderSource(setup.secondaryAnalysisProvenance(), 0.0);
      assertTrue(setup.isChanged);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {" wrn=NaN", " wrn=Infinity", " wrn=-0.1", " wrn=5.01", " wrn=",
      " wrn=text", " wrn=0.5 wrn=1.0", " wrn=0 wrn=0"})
  void malformedOrDuplicateWrnDoesNotDamageAnalysisOrInventZero(String extension) throws Exception {
    BoardHistoryList history = parse(tags(true, extension, " wrn=0.75"));
    for (int round = 0; round < 2; round++) {
      BoardData data = history.getStart().getData();
      assertNull(data.primaryAnalysisProvenance());
      assertHeaderSource(data.secondaryAnalysisProvenance(), 0.75);
      assertEquals(120, data.rootVisits);
      assertEquals(120, data.getPlayouts());
      assertEquals("C3", data.bestMoves.get(0).coordinate);
      assertEquals(List.of(0.2, -0.2), data.estimateArray);
      history = reopen(history);
    }
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, 0.75, 2, 2.01, 3, 5, 0.12345678912345678})
  void headerOnlyMetadataRoundTripsWithoutKgpOrBorrowedProducerIdentity(double wrn) throws Exception {
    for (boolean secondary : new boolean[] {false, true}) {
      String extension = " future=value wrn=" + wrn;
      BoardHistoryList history = parse(tags(true, secondary ? "" : extension, secondary ? extension : ""));
      for (int round = 0; round < 3; round++) {
        BoardData data = history.getStart().getData();
        assertHeaderSource(evaluation(data, secondary), wrn);
        assertNull(ownership(data, secondary));
        assertNull(evaluation(data, !secondary));
        String saved = save(history);
        assertFalse(saved.contains("KGP["));
        assertTrue(property(saved, secondary ? "LZOP2" : "LZOP").split("\n", 2)[0].contains(" wrn=" + wrn));
        history = SGFParser.parseSgf(saved, true);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(doubles = {2.01, 3, 5})
  void engineRecordedWrnUsesEngineRangeRatherThanTheEditableUiRange(double wrn) throws Exception {
    for (boolean secondary : new boolean[] {false, true}) {
      BoardHistoryList history = parse("");
      Lizzie.board.setHistory(history);
      ObservedEngine engine = new ObservedEngine(source(wrn));
      engine.wrn = 0.5;
      assertEquals(BoardData.AnalysisAdoption.FULL,
          history.getData().adoptOrdinaryAnalysis(moves(500), "display label", engine,
              500, 500, null, new Object(), secondary, true));
      String saved = save(history);
      assertTrue(property(saved, secondary ? "LZOP2" : "LZOP").split("\n", 2)[0].contains(" wrn=" + wrn));
      assertEquals(source(wrn), evaluation(SGFParser.parseSgf(saved, true).getData(), secondary));
    }
  }

  @ParameterizedTest
  @ValueSource(doubles = {-0.1, 5.01})
  void existingKgpFiniteNumberContractDoesNotInheritNamedHeaderLimits(double wrn) throws Exception {
    String payload = payload(false, " wrn=0.25");
    BoardHistoryList history = parse("LZOP[" + payload + "]KGP[" + metadata(payload, source(wrn), false) + "]");
    assertEquals(source(wrn), history.getData().primaryAnalysisProvenance());
    String saved = save(history);
    assertFalse(property(saved, "LZOP").contains("wrn="));
    assertEquals(source(wrn), SGFParser.parseSgf(saved, true).getData().primaryAnalysisProvenance());
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 4, 5, 6})
  void writerPadsPositionalHeaderBeforeNamedWrnWithoutInventingRootVisits(int slots) throws Exception {
    String[] fields = {"OldEngine", "45", "120", "2.5", "1.2", "0"};
    String header = String.join(" ", java.util.Arrays.copyOf(fields, slots));
    BoardHistoryList history = parse("LZOP[" + header + "]");
    history.getData().setPrimaryAnalysisProvenance(source(0.0));
    String saved = save(history);
    String[] written = property(saved, "LZOP").split("\n", 2)[0].split(" ");
    assertEquals(7, written.length);
    for (int index = 1; index < 6; index++) assertTrue(Double.isFinite(Double.parseDouble(written[index])));
    assertEquals("wrn=0.0", written[6]);
    assertFalse(saved.contains("rootVisits="));
    assertEquals(source(0.0), SGFParser.parseSgf(saved, true).getData().primaryAnalysisProvenance());
  }

  @Test
  void matchingKgpWinsIncludingExplicitlyUnknownWrnWhileMismatchesFallBackToHeaderOnly() throws Exception {
    String payload = payload(false, " wrn=0.25");
    for (Double boundWrn : new Double[] {0.0, 1.5, 2.01, 3.0, 5.0, null}) {
      AnalysisProvenance expected = source(boundWrn);
      String kgp = metadata(payload, expected, true);
      for (boolean first : new boolean[] {false, true}) {
        String analysis = "LZOP[" + payload + "]";
        String property = "KGP[" + kgp + "]";
        BoardHistoryList history = parse(first ? property + analysis : analysis + property);
        for (int round = 0; round < 2; round++) {
          assertEquals(expected, history.getData().primaryAnalysisProvenance());
          assertTrue(history.getData().isChanged);
          history = reopen(history);
        }
      }
      BoardHistoryList mismatch = parse("LZOP[" + payload.replace("rootVisits=120", "rootVisits=0120")
          + "]KGP[" + kgp + "]");
      for (int round = 0; round < 2; round++) {
        assertHeaderSource(mismatch.getData().primaryAnalysisProvenance(), 0.25);
        assertFalse(mismatch.getData().isChanged);
        String saved = save(mismatch);
        assertFalse(saved.contains("KGP["), "normalization must not revive stale metadata");
        mismatch = SGFParser.parseSgf(saved, true);
      }
    }
  }

  @Test
  void originalPayloadDigestIsCheckedBeforeCandidateLimits() throws Exception {
    Lizzie.config.limitMaxSuggestion = 1;
    String payload = payload(false, " wrn=0.25").replace(" ownership",
        " info move E5 visits 20 winrate 5000 prior 100 order 1 pv E5 D4 ownership");
    BoardHistoryList history = parse("LZOP[" + payload + "]KGP[" + metadata(payload, source(1.5), false) + "]");
    assertEquals(1, history.getData().bestMoves.size());
    assertEquals(source(1.5), history.getData().primaryAnalysisProvenance());
    assertEquals(source(1.5), reopen(history).getData().primaryAnalysisProvenance());
  }

  @Test
  void opaqueKgpIsPreservedAlongsideHeaderOnlySourcesAndReplacementDropsIt() throws Exception {
    for (String opaque : List.of("not base64!", encode(new JSONObject().put("app", "KG-next").put("version", 99)))) {
      BoardHistoryList history = parse(tags(true, " wrn=0.25", " wrn=0") + "KGP[" + opaque + "]");
      for (int round = 0; round < 2; round++) {
        String saved = save(history);
        assertEquals(opaque, property(saved, "KGP"));
        history = SGFParser.parseSgf(saved, true);
        assertHeaderSource(history.getData().primaryAnalysisProvenance(), 0.25);
        assertHeaderSource(history.getData().secondaryAnalysisProvenance(), 0.0);
      }
      Lizzie.board.setHistory(history);
      history.getData().tryToSetBestMovesFromEngine(moves(500), "replacement", new ObservedEngine(source(1.0)),
          500, null, true);
      assertNull(history.getData().getProperty("KGP"));
      assertEquals(source(1.0), reopen(history).getData().primaryAnalysisProvenance());
    }
  }

  @Test
  void replacedKgpReturnsToOriginalHeaderRatherThanPreviousKgpWrn() throws Exception {
    String payload = payload(false, " wrn=0.25");
    BoardHistoryList history = parse("LZOP[" + payload + "]KGP[" + metadata(payload, source(1.5), false) + "]");
    assertEquals(1.5, history.getData().primaryAnalysisProvenance().wrn());
    SGFParser.parseBranch(history, ";KGP[not base64!]");
    assertHeaderSource(history.getData().primaryAnalysisProvenance(), 0.25);
    assertHeaderSource(reopen(history).getData().primaryAnalysisProvenance(), 0.25);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void singleSlotImportNeverClearsOtherSlotsInvalidationOrWrn(boolean secondary) throws Exception {
    BoardHistoryList history = parse(tags(true, " wrn=0.25", " wrn=0.75"));
    BoardData data = history.getData();
    data.invalidatePrimaryAnalysis();
    data.invalidateSecondaryAnalysis();
    SGFParser.parseBranch(history, ";" + (secondary ? "LZ2[" : "LZ[") + payload(secondary, " wrn=0") + "]");
    assertEquals(secondary, data.isChanged);
    assertEquals(!secondary, data.isChanged2);
    assertHeaderSource(evaluation(data, secondary), 0.0);
    assertHeaderSource(evaluation(data, !secondary), secondary ? 0.25 : 0.75);
    BoardData reopened = reopen(history).getData();
    assertEquals(secondary, reopened.isChanged);
    assertEquals(!secondary, reopened.isChanged2);
    assertHeaderSource(evaluation(reopened, !secondary), secondary ? 0.25 : 0.75);
  }

  @Test
  void clearingAndGenericImportsDoNotReviveHeaderWrnFromAnEarlierPayload() throws Exception {
    BoardHistoryList history = parse(tags(true, " wrn=0.25", " wrn=0.75"));
    Lizzie.board.setHistory(history);
    BoardData data = history.getData();
    data.tryToSetBestMoves(moves(500), "no producer", false, 500, null);
    data.tryToSetBestMoves2(moves(500), "no producer", false, 500, null);
    assertNull(data.primaryAnalysisProvenance());
    assertNull(data.secondaryAnalysisProvenance());
    SGFParser.parseBranch(history, ";KGP[not base64!]");
    assertNull(data.primaryAnalysisProvenance());
    assertNull(data.secondaryAnalysisProvenance());
    data.clearAnalysisPayloadState();
    SGFParser.parseBranch(history, ";LZ[" + payload(false, "") + "]");
    assertNull(data.primaryAnalysisProvenance());
    assertNull(data.secondaryAnalysisProvenance());
    assertFalse(save(history).contains("wrn="));
  }

  @Test
  void generatedWrnSummaryPreservesAuthoredCommentAndDoesNotCreateAnExplicitEdit() throws Exception {
    BoardHistoryList history = parse(";B[aa]" + tags(false, "", ""));
    history.setHead(history.getStart().next().orElseThrow());
    Lizzie.board.setHistory(history);
    BoardData data = history.getData();
    data.setAuthoredComment("Personal note / 사용자 메모");
    AtomicReference<GameEditSnapshot> snapshot = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> {
      snapshot.set(GameEditSnapshot.capture(Lizzie.board));
      data.setPrimaryAnalysisProvenance(source(0.25));
      SGFParser.appendComment();
      data.setPrimaryAnalysisProvenance(source(0.0));
      SGFParser.appendComment();
      assertTrue(snapshot.get().isCurrent(Lizzie.board));
    });
    assertEquals("Personal note / 사용자 메모", data.authoredComment());
    assertTrue(data.comment.contains(data.authoredComment()));
    assertTrue(data.comment.contains(Lizzie.resourceBundle.getString("SGFParse.wrn") + "0.0"));
    assertFalse(data.comment.contains(Lizzie.resourceBundle.getString("SGFParse.wrn") + "0.25"));
    assertTrue(reopen(history).getStart().next().orElseThrow().getData().comment.contains("사용자 메모"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "ko", "zh-CN"})
  void existingSourcePanelRendersImportedKnownZeroWithoutClaimingOwnershipIdentity(String language)
      throws Exception {
    ResourceBundle resources = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(language));
    BoardData data = parse(tags(true, " wrn=0", " wrn=0.75")).getData();
    Method describe = Class.forName("featurecat.lizzie.gui.AnalysisProvenanceDisplay")
        .getDeclaredMethod("describe", BoardData.class, ResourceBundle.class);
    describe.setAccessible(true);
    String text = (String) describe.invoke(null, data, resources);
    String parameters = MessageFormat.format(resources.getString("WholeGameAnalysis.source.parameters"),
        resources.getString("WholeGameAnalysis.source.unknownValue"), "0.0");
    assertTrue(text.contains(parameters), text);
    assertTrue(text.contains(resources.getString("WholeGameAnalysis.source.unknown")), text);
    assertFalse(text.contains(resources.getString("WholeGameAnalysis.source.same")), text);
    assertNull(data.primaryOwnershipProvenance());
  }

  private static BoardHistoryList parse(String nodes) {
    return SGFParser.parseSgf("(;SZ[5]" + nodes + ")", true);
  }

  private static BoardData positionData(BoardHistoryList history, String kind) {
    return kind.equals("root") ? history.getStart().getData() : history.getStart().next().orElseThrow().getData();
  }

  private static String save(BoardHistoryList history) throws Exception {
    Lizzie.board.setHistory(history);
    return SGFParser.saveToString(false);
  }

  private static BoardHistoryList reopen(BoardHistoryList history) throws Exception {
    return SGFParser.parseSgf(save(history), true);
  }

  private static String tags(boolean root, String primary, String secondary) {
    String tag = root ? "LZOP" : "LZ";
    return tag + "[" + payload(false, primary) + "]" + tag + "2[" + payload(true, secondary) + "]";
  }

  private static String payload(boolean secondary, String extension) {
    return (secondary ? "Other 55.0 80 -2.5 1.3 0 rootVisits=80" : "KataGo 45.0 120 2.5 1.2 0 rootVisits=120")
        + extension + "\nmove " + (secondary ? "D4" : "C3")
        + " visits " + (secondary ? "80" : "120")
        + " winrate 5500 prior 1000 scoreMean 2.5 order 0 pv C3 D4 ownership 0.2 -0.2";
  }

  private static String property(String sgf, String name) {
    Matcher matcher = Pattern.compile("(?:^|[^A-Z])" + name + "\\[([^\\]]*)\\]").matcher(sgf);
    assertTrue(matcher.find(), () -> "Missing " + name + " in " + sgf);
    return matcher.group(1);
  }

  private static AnalysisProvenance source(Double wrn) {
    return new AnalysisProvenance("KataGo", "1.18.2", "observed-model", "", "", "japanese", 6.5, 0.0, wrn, "");
  }

  private static void assertHeaderSource(AnalysisProvenance source, double wrn) {
    assertNotNull(source);
    assertEquals(wrn, source.wrn());
    assertEquals(new AnalysisProvenance("", "", "", "", "", "", null, null, wrn, ""), source);
  }

  private static AnalysisProvenance evaluation(BoardData data, boolean secondary) {
    return secondary ? data.secondaryAnalysisProvenance() : data.primaryAnalysisProvenance();
  }

  private static AnalysisProvenance ownership(BoardData data, boolean secondary) {
    return secondary ? data.secondaryOwnershipProvenance() : data.primaryOwnershipProvenance();
  }

  private static String metadata(String payload, AnalysisProvenance source, boolean invalidated) throws Exception {
    JSONObject analysis = new JSONObject().put("engineName", source.engineName())
        .put("engineVersion", source.engineVersion()).put("modelName", source.modelName())
        .put("rules", source.rules()).put("komi", source.komi()).put("pda", source.pda()).put("wrn", source.wrn());
    JSONObject slot = new JSONObject().put("payloadSha256", HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))))
        .put("analysis", analysis).put("invalidated", invalidated);
    return encode(new JSONObject().put("app", "KG-next").put("version", 2).put("primary", slot));
  }

  private static String encode(JSONObject value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String format(String name, BoardHistoryNode node) throws Exception {
    if (name.equals("formatComment")) return SGFParser.formatComment(node);
    if (name.equals("formatCommentPk")) return SGFParser.formatCommentPk(node);
    if (name.equals("formatCommentForGame")) {
      Method method = SGFParser.class.getDeclaredMethod(name, BoardHistoryNode.class);
      method.setAccessible(true);
      return (String) method.invoke(null, node);
    }
    Class<?> context = Class.forName("featurecat.lizzie.rules.SGFParser$SaveContext");
    Method current = context.getDeclaredMethod("current");
    current.setAccessible(true);
    Method method = SGFParser.class.getDeclaredMethod(name, BoardHistoryNode.class, context);
    method.setAccessible(true);
    return (String) method.invoke(null, node, current.invoke(null));
  }

  private static List<MoveData> moves(int visits) {
    MoveData move = new MoveData();
    move.coordinate = "C3";
    move.playouts = visits;
    move.winrate = 55;
    move.scoreMean = 2.5;
    move.isKataData = true;
    move.variation = new ArrayList<>(List.of("C3", "D4"));
    return new ArrayList<>(List.of(move));
  }

  private static final class ObservedEngine extends Leelaz {
    private AnalysisProvenance observed;

    private ObservedEngine(AnalysisProvenance observed) throws IOException {
      super("");
      this.observed = observed;
    }

    @Override
    public AnalysisProvenance captureAnalysisProvenance() {
      return observed;
    }
  }
}
