package featurecat.lizzie.rules;

import com.sun.management.ThreadMXBean;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.SgfPerformanceProbe;
import featurecat.lizzie.util.SgfFileReader;
import java.io.InterruptedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Real shared-library handoff, contract checks and separately forked timing; never an app loader. */
public final class SgfNativeProbe {
  private static volatile Object consumed;

  public static void main(String[] args) throws Exception {
    Path library = Path.of(args[0]), root = Path.of(args[1]), output = Path.of(args[2]);
    String mode = args[3];
    int samples = Integer.parseInt(args[4]), warmups = Integer.parseInt(args[5]);
    Files.createDirectories(output);
    Path fixtures = Files.createDirectory(output.resolve("fixtures"));
    List<Path> performance = new ArrayList<>(SgfPerformanceProbe.writeFixtures(fixtures));
    List<Path> corpus = new ArrayList<>(performance);
    for (String name : List.of("gui/d4-real-cpu-snapshot.sgf", "gui/d3-sgf-ui-roundtrip-中文.sgf",
        "rules/issue223-snapshot-removed-stone.sgf", "rules/issue223-reporter-205-moves.sgf")) {
      Path copy = fixtures.resolve(Path.of(name).getFileName());
      Files.copy(root.resolve("src/test/resources/featurecat/lizzie/" + name), copy);
      corpus.add(copy);
    }
    SgfRustProbe.diagnostics(fixtures, corpus);
    JSONObject report = new JSONObject().put("mode", mode).put("samples", samples).put("warmups", warmups)
        .put("java", System.getProperty("java.runtime.version"))
        .put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        .put("maxHeapBytes", Runtime.getRuntime().maxMemory())
        .put("decodePolicy", "Both paths use unchanged SgfFileReader; decoded text is normalized in Java")
        .put("scope", "syntax plus private KG history; no disk, UI publication or engine in timings")
        .put("historyDigestVersion", 2).put("results", new JSONArray());
    SgfNativeDocumentParser parser = mode.equals("java") ? null : new SgfNativeDocumentParser(library);
    try (parser) {
      if (mode.equals("compat")) {
        lifecycleControls(parser, report);
        try (var harness = RulesLayerTestHarness.open(19)) {
          Lizzie.config.appendWinrateToComment = false;
          Lizzie.config.limitBranchLength = 0;
          Lizzie.config.limitMaxSuggestion = 0;
          for (Path path : corpus) {
            JSONObject row = compare(path, parser);
            report.getJSONArray("results").put(row);
            Files.writeString(output.resolve("summary.json"), report.toString(2));
            System.out.println(path.getFileName() + ": " + row.getString("route"));
          }
        }
      } else {
        require(mode.equals("java") || mode.equals("native"), "Unknown mode");
        for (Path path : performance) {
          report.getJSONArray("results").put(measure(path, parser, samples, warmups));
        }
      }
      if (parser != null) require(parser.liveResults() == 0, "Native response allocation leaked");
    }
    if (parser != null) {
      parser.close(); // Idempotent close, followed by an attempted use of the closed owner.
      try { parser.parse("(;SZ[19])"); throw new AssertionError("Closed parser accepted work"); }
      catch (IllegalStateException expected) { report.put("closedOwnerRejected", true); }
    }
    report.put("completed", true);
    Files.writeString(output.resolve("summary.json"), report.toString(2));
  }

  private static JSONObject compare(Path path, SgfNativeDocumentParser parser) throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    String text = SgfFileReader.read(path);
    SgfDocument expected = SgfDocument.parse(text);
    var actual = parser.parse(text);
    JSONObject row = new JSONObject().put("fixture", path.getFileName().toString())
        .put("bytes", bytes.length).put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        .put("route", actual.route()).put("nativeError", actual.nativeError() == null ? JSONObject.NULL : actual.nativeError());
    if (expected == null) {
      require(actual.document() == null, "Java rejected input became a native success: " + path);
      return row.put("rejected", true);
    }
    String difference = SgfRustProbe.difference(expected, actual.document());
    require(difference == null, path + ": " + difference);
    // Raw values matter even when legacy decoding would hide a difference in retained data.
    require(expected.events().equals(actual.document().events()), "Raw SGF events changed: " + path);
    int games = SgfRustProbe.gameCount(expected);
    row.put("documentEqual", true).put("games", games);
    if (games > 1) row.put("collectionPolicy", "Select the first game explicitly for this experiment; retain all syntax");
    var javaHistory = SGFParser.buildHistory(firstGame(expected), true, SgfRustProbe.OPTIONS);
    var nativeHistory = SGFParser.buildHistory(firstGame(actual.document()), true, SgfRustProbe.OPTIONS);
    String before = SgfRustProbe.historyDigest(javaHistory);
    require(before.equals(SgfRustProbe.historyDigest(nativeHistory)), "History changed: " + path);
    row.put("historyEqual", true).put("historySha256", before);
    String javaSaved = save(javaHistory), nativeSaved = save(nativeHistory);
    require(javaSaved.equals(nativeSaved), "Serialized output changed: " + path);
    var javaReopen = SGFParser.parseSgf(javaSaved, true, SgfRustProbe.OPTIONS);
    var reopened = parser.parse(nativeSaved);
    row.put("reopenRoute", reopened.route()).put("reopenNativeError",
        reopened.nativeError() == null ? JSONObject.NULL : reopened.nativeError());
    var nativeReopen = SGFParser.buildHistory(reopened.document(), true, SgfRustProbe.OPTIONS);
    require(SgfRustProbe.historyDigest(javaReopen).equals(SgfRustProbe.historyDigest(nativeReopen)),
        "Saved/reopened history changed: " + path);
    row.put("saveReopenEqual", true).put("savedSha256", HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(javaSaved.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    String name = path.getFileName().toString();
    if (name.startsWith("provenance-")) {
      require(actual.route().equals("native") && reopened.route().equals("native"),
          "Retained-analysis control must exercise Rust on both reads: " + path);
    }
    if (name.startsWith("provenance-") && !name.contains("stale")) {
      BoardData data = analysisOwner(nativeReopen);
      require(data.primaryAnalysisProvenance() != null && data.primaryOwnershipProvenance() != null
          && data.isChanged && data.bestMoves.size() == 1, "Primary source/invalidation lost: " + path);
      if (name.contains("secondary")) {
        require(data.secondaryAnalysisProvenance() != null && data.secondaryOwnershipProvenance() != null
            && data.isChanged2 && data.bestMoves2.size() == 1, "Secondary source/invalidation lost: " + path);
      }
      row.put("retainedAnalysisControl", true);
    } else if (name.contains("provenance-stale")) {
      BoardData data = analysisOwner(nativeReopen);
      require(data.primaryAnalysisProvenance() == null && data.primaryOwnershipProvenance() == null,
          "Stale digest restored provenance");
      row.put("staleDigestRejected", true);
    }
    require(parser.liveResults() == 0, "Native response leaked: " + path);
    return row;
  }

  private static BoardData analysisOwner(BoardHistoryList history) {
    var nodes = new ArrayDeque<BoardHistoryNode>();
    nodes.add(history.getStart());
    while (!nodes.isEmpty()) {
      var node = nodes.removeFirst();
      if (!node.getData().bestMoves.isEmpty()) return node.getData();
      nodes.addAll(node.getVariations());
    }
    throw new AssertionError("Expected retained analysis");
  }

  private static String save(BoardHistoryList history) throws Exception {
    Lizzie.board.setHistory(history);
    return SGFParser.saveToString(false);
  }

  private static SgfDocument firstGame(SgfDocument document) {
    int depth = 0;
    for (int i = 0; i < document.events().size(); i++) {
      char kind = document.events().get(i).kind();
      if (kind == '(') depth++;
      else if (kind == ')' && --depth == 0) {
        var events = document.events().subList(0, i + 1);
        return new SgfDocument(events, document.width(), document.height(),
            i > 0 && events.get(i - 1).kind() == ')');
      }
    }
    return document;
  }

  private static JSONObject measure(Path path, SgfNativeDocumentParser parser, int samples, int warmups)
      throws Exception {
    String text = SgfFileReader.read(path);
    var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    require(bean.isThreadAllocatedMemorySupported(), "Thread allocation accounting unavailable");
    bean.setThreadAllocatedMemoryEnabled(true);
    JSONArray observations = new JSONArray();
    for (int i = -warmups; i < samples; i++) {
      long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
      long start = System.nanoTime();
      SgfDocument document;
      long internal = 0;
      if (parser == null) document = SgfDocument.parse(text);
      else {
        var result = parser.parse(text);
        require(result.route().equals("native"), "Cannot time fallback as native: " + path);
        document = result.document(); internal = result.parseNanos();
      }
      long syntaxDone = System.nanoTime();
      consumed = SGFParser.buildHistory(document, true, SgfRustProbe.OPTIONS);
      long done = System.nanoTime();
      long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
      if (i >= 0) observations.put(new JSONObject().put("syntaxMs", (syntaxDone - start) / 1e6)
          .put("totalMs", (done - start) / 1e6).put("javaAllocatedBytes", bytes)
          .put("rustInternalMs", internal / 1e6));
    }
    return new JSONObject().put("fixture", path.getFileName().toString()).put("samples", observations)
        .put("historySha256", SgfRustProbe.historyDigest((BoardHistoryList) consumed));
  }

  private static void lifecycleControls(SgfNativeDocumentParser parser, JSONObject report) throws Exception {
    String good = "(;SZ[19]C[control];B[aa](;W[bb])(;W[cc]))";
    var control = SgfDocument.parse(good);
    require(SgfRustProbe.difference(control, parser.parse(good).document()) == null, "Positive control failed");
    require(SgfRustProbe.difference(control, parser.parse(good.replace("control", "changed")).document()) != null,
        "Changed value control not detected");
    require(SgfRustProbe.difference(control, parser.parse("(;SZ[19]C[control];B[aa](;W[bb]))").document()) != null,
        "Missing branch control not detected");
    for (int i = 0; i < 100; i++) {
      require(parser.parse(good).route().equals("native"), "Native parser unexpectedly fell back");
      parser.parse("(;C[unterminated)");
      require(parser.liveResults() == 0, "Success/error result leaked");
    }
    Thread.currentThread().interrupt();
    try {
      parser.parse(good);
      throw new AssertionError("Interrupted call was accepted");
    } catch (InterruptedIOException expected) {
      require(Thread.currentThread().isInterrupted(), "Interrupt flag was lost");
    } finally { Thread.interrupted(); }
    require(parser.liveResults() == 0 && parser.parse(good).route().equals("native"), "Retry after cancellation failed");
    report.put("controls", "changed value/missing branch detected; 100 success/error/free pairs; interrupted request rejected; retry passed")
        .put("cancellationLimit", "An in-flight native call is not forcibly stopped; its result is freed and discarded after return")
        .put("fallbackPolicy", "Explicit Java fallback for native syntax/resource rejection; protocol/load failures fail the experiment");
  }

  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
