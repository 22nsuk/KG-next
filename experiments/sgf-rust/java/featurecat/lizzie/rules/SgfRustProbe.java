package featurecat.lizzie.rules;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.gui.SgfPerformanceProbe;
import featurecat.lizzie.util.SgfFileReader;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in experiment, compiled separately from both application and automatic tests. */
public final class SgfRustProbe {
  private static final SGFParser.ParseOptions OPTIONS =
      new SGFParser.ParseOptions(true, false, false, true, 0, 361, 7.5);
  private static final int MAX_FRAME = 64 * 1024 * 1024;
  private static volatile Object consumed;

  public static void main(String[] args) throws Exception {
    Path binary = Path.of(args[0]).toAbsolutePath();
    Path root = Path.of(args[1]).toAbsolutePath();
    Path output = Path.of(args[2]).toAbsolutePath();
    int samples = Integer.parseInt(args[3]);
    int warmups = Integer.parseInt(args[4]);
    Files.createDirectories(output);
    Path fixtures = Files.createDirectory(output.resolve("fixtures"));
    List<Path> inputs = new ArrayList<>();
    for (String name : List.of(
        "gui/d4-real-cpu-snapshot.sgf", "gui/d3-sgf-ui-roundtrip-中文.sgf",
        "rules/issue223-snapshot-removed-stone.sgf", "rules/issue223-reporter-205-moves.sgf")) {
      Path source = root.resolve("src/test/resources/featurecat/lizzie/" + name);
      Path copy = fixtures.resolve(source.getFileName());
      Files.copy(source, copy);
      inputs.add(copy);
    }
    inputs.addAll(SgfPerformanceProbe.writeFixtures(fixtures));
    List<Path> performance = List.copyOf(inputs);
    diagnostics(fixtures, inputs);
    JSONObject report = new JSONObject()
        .put("started", Instant.now().toString())
        .put("java", System.getProperty("java.runtime.version"))
        .put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        .put("logicalProcessors", Runtime.getRuntime().availableProcessors())
        .put("maxHeapBytes", Runtime.getRuntime().maxMemory())
        .put("jvmArguments", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments())
        .put("samples", samples).put("warmupsExcluded", warmups)
        .put("scope", "Warm in-memory input; no UI, engine, disk read, JNI or OS cache eviction")
        .put("comparison", "Ordered tree/property values after the KG legacy value adapter, plus history digests")
        .put("provenanceControls", "Java fixtures retain valid primary/secondary sources and invalidation; "
            + "a one-character payload change rejects the stale source")
        .put("timing", "Java syntax uses decoded String; Rust parse includes byte decoding. "
            + "Rust transport includes UTF-8 encoding, pipes, complete events, value adaptation and Java document allocation. "
            + "End-to-end additionally builds the same KG history. Cold measurements include child startup.")
        .put("results", new JSONArray());
    Path stderr = output.resolve("rust-stderr.log");
    try (Bridge bridge = new Bridge(binary, stderr)) {
      // Exercise both comparator outcomes; no correctness claim may rely on a count-only response.
      SgfDocument control = SgfDocument.parse("(;SZ[19]C[control];B[aa](;W[bb])(;W[cc]))");
      SgfDocument nativeControl = bridge.parse("(;SZ[19]C[control];B[aa](;W[bb])(;W[cc]))"
          .getBytes(StandardCharsets.UTF_8)).document;
      require(difference(control, nativeControl) == null, "Positive comparison control failed");
      require(historyDigest(SGFParser.buildHistory(control, true, OPTIONS)).equals(
          historyDigest(SGFParser.buildHistory(nativeControl, true, OPTIONS))),
          "Positive history comparison control failed");
      require(difference(control, bridge.parse("(;SZ[19]C[changed];B[aa](;W[bb])(;W[cc]))"
          .getBytes(StandardCharsets.UTF_8)).document) != null, "Changed-property control was not detected");
      require(difference(control, bridge.parse("(;SZ[19]C[control];B[aa](;W[bb]))"
          .getBytes(StandardCharsets.UTF_8)).document) != null, "Missing-branch control was not detected");
      report.put("comparisonControls", "identical document and history pass; changed value and missing branch detected");
      for (Path path : inputs) {
        JSONObject result = compare(path, bridge, performance.contains(path));
        if (performance.contains(path) && result.optBoolean("compatible")) {
          String text = SgfFileReader.read(path);
          result.put("measurements", measure(text, bridge, binary, stderr, samples, warmups));
        }
        report.getJSONArray("results").put(result);
        Files.writeString(output.resolve("summary.json"), report.toString(2) + "\n");
        System.out.println(path.getFileName() + ": " + result.getString("status"));
      }
    }
    report.put("finished", Instant.now().toString());
    Files.writeString(output.resolve("summary.json"), report.toString(2) + "\n");
  }

  private static JSONObject compare(Path path, Bridge bridge, boolean baselineRequired) throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    SgfDocument java = SgfDocument.parse(SgfFileReader.read(path));
    Reply rust = bridge.parse(bytes);
    JSONObject row = new JSONObject().put("fixture", path.getFileName().toString())
        .put("bytes", bytes.length).put("sha256", hash(bytes))
        .put("javaEvents", java == null ? 0 : java.events().size())
        .put("rustEvents", rust.document == null ? 0 : rust.document.events().size())
        .put("rustEncoding", rust.encoding).put("rustWarnings", rust.warnings)
        .put("rustError", rust.error == null ? JSONObject.NULL : rust.error);
    String mismatch = difference(java, rust.document);
    row.put("documentDifference", mismatch == null ? JSONObject.NULL : mismatch);
    boolean compatible = java != null && rust.document != null && mismatch == null;
    HistoryResult expected = java != null && gameCount(java) == 1 ? build(java) : null;
    if (baselineRequired && (expected == null || expected.error != null)) {
      throw new IllegalStateException("Java baseline failed for " + path.getFileName(),
          expected == null ? null : expected.error);
    }
    if (expected != null) {
      row.put("javaHistoryError", expected.error == null ? JSONObject.NULL : expected.error.toString())
          .put("javaHistorySha256", expected.digest == null ? JSONObject.NULL : expected.digest);
      if (expected.root != null) row.put("javaRootAnalysis", analysisSummary(expected.root));
    }
    // Collections remain distinct syntax; choosing a game is deliberately not a bridge policy.
    if (java != null && rust.document != null && gameCount(java) == 1 && gameCount(rust.document) == 1) {
      HistoryResult actual = build(rust.document);
      row.put("rustHistoryError", actual.error == null ? JSONObject.NULL : actual.error.toString())
          .put("rustHistorySha256", actual.digest == null ? JSONObject.NULL : actual.digest);
      boolean historyEqual = expected.digest != null && expected.digest.equals(actual.digest);
      row.put("historyEqual", historyEqual);
      if (actual.root != null) row.put("rustRootAnalysis", analysisSummary(actual.root));
      compatible &= historyEqual;
    } else {
      row.put("historyComparison", "not built: rejected syntax or collection needs an explicit game choice");
      compatible = false;
    }
    return row.put("compatible", compatible).put("status", compatible ? "match"
        : rust.document == null && java == null ? "both rejected"
        : rust.document == null ? "Rust rejected" : java == null ? "Java rejected" : "different");
  }

  private record HistoryResult(String digest, BoardData root, RuntimeException error) {}

  private static HistoryResult build(SgfDocument document) throws Exception {
    try {
      var history = SGFParser.buildHistory(document, true, OPTIONS);
      return new HistoryResult(historyDigest(history), history.getStart().getData(), null);
    } catch (RuntimeException failure) {
      return new HistoryResult(null, null, failure);
    }
  }

  private static JSONObject analysisSummary(BoardData data) {
    return new JSONObject().put("primaryCandidates", data.bestMoves.size())
        .put("secondaryCandidates", data.bestMoves2.size())
        .put("primarySource", data.primaryAnalysisProvenance() != null)
        .put("secondarySource", data.secondaryAnalysisProvenance() != null)
        .put("ownershipSource", data.primaryOwnershipProvenance() != null)
        .put("invalidated", data.isChanged);
  }

  private static JSONObject measure(String text, Bridge bridge, Path binary, Path stderr,
      int samples, int warmups) throws Exception {
    JSONObject result = new JSONObject();
    Map<String, List<Double>> times = new LinkedHashMap<>();
    for (String metric : List.of("javaSyntaxMs", "rustParseMs", "rustTransportDocumentMs",
        "javaHistoryMs", "javaEndToEndMs", "rustEndToEndMs", "rustColdTransportDocumentMs")) {
      times.put(metric, new ArrayList<>());
    }
    SgfDocument parsed = SgfDocument.parse(text);
    for (int i = -warmups; i < samples; i++) {
      // Alternate path order to reduce a fixed-order warm-up/GC bias. No timing thresholds.
      if ((i & 1) == 0) javaTimes(text, parsed, times, i >= 0);
      long start = System.nanoTime();
      Reply reply = bridge.parse(text.getBytes(StandardCharsets.UTF_8));
      long documentDone = System.nanoTime();
      consumed = SGFParser.buildHistory(reply.document, true, OPTIONS);
      long done = System.nanoTime();
      if (i >= 0) {
        times.get("rustParseMs").add(reply.parseNanos / 1e6);
        times.get("rustTransportDocumentMs").add((documentDone - start) / 1e6);
        times.get("rustEndToEndMs").add((done - start) / 1e6);
      }
      if ((i & 1) != 0) javaTimes(text, parsed, times, i >= 0);
      if (i >= 0) {
        start = System.nanoTime();
        try (Bridge cold = new Bridge(binary, stderr)) {
          consumed = cold.parse(text.getBytes(StandardCharsets.UTF_8)).document;
          times.get("rustColdTransportDocumentMs").add((System.nanoTime() - start) / 1e6);
        }
      }
    }
    for (var entry : times.entrySet()) {
      List<Double> sorted = entry.getValue().stream().sorted().toList();
      int middle = sorted.size() / 2;
      double median = sorted.size() % 2 == 0
          ? (sorted.get(middle - 1) + sorted.get(middle)) / 2 : sorted.get(middle);
      result.put(entry.getKey(), new JSONObject().put("samples", entry.getValue())
          .put("median", median).put("min", sorted.get(0)).put("max", sorted.get(sorted.size() - 1)));
    }
    return result;
  }

  private static void javaTimes(String text, SgfDocument parsed,
      Map<String, List<Double>> times, boolean record) {
    long start = System.nanoTime();
    SgfDocument document = SgfDocument.parse(text);
    long documentDone = System.nanoTime();
    consumed = SGFParser.buildHistory(document, true, OPTIONS);
    long done = System.nanoTime();
    long buildStart = System.nanoTime();
    consumed = SGFParser.buildHistory(parsed, true, OPTIONS);
    long buildDone = System.nanoTime();
    if (record) {
      times.get("javaSyntaxMs").add((documentDone - start) / 1e6);
      times.get("javaEndToEndMs").add((done - start) / 1e6);
      times.get("javaHistoryMs").add((buildDone - buildStart) / 1e6);
    }
  }

  private static String difference(SgfDocument left, SgfDocument right) {
    if (left == null || right == null) return "one or both parsers rejected the input";
    if (left.width() != right.width() || left.height() != right.height()) return "board size differs";
    List<SgfDocument.Event> a = left.events(), b = right.events();
    for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
      var x = a.get(i);
      var y = b.get(i);
      if (x.kind() != y.kind() || !x.name().equals(y.name()) || !x.legacyValue().equals(y.legacyValue())) {
        return "event " + i + ": Java " + x.kind() + "/" + x.name() + "=" + excerpt(x.legacyValue())
            + "; Rust " + y.kind() + "/" + y.name() + "=" + excerpt(y.legacyValue());
      }
    }
    return a.size() == b.size() ? null : "event count: Java " + a.size() + ", Rust " + b.size();
  }

  private static String excerpt(String value) {
    String printable = value.replace("\r", "\\r").replace("\n", "\\n");
    return printable.length() <= 100 ? printable : printable.substring(0, 100) + "…";
  }

  private static int gameCount(SgfDocument document) {
    int depth = 0, games = 0;
    for (var event : document.events()) {
      if (event.kind() == '(' && depth++ == 0) games++;
      else if (event.kind() == ')') depth--;
    }
    return games;
  }

  private static String historyDigest(BoardHistoryList history) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    var info = history.getGameInfo();
    add(digest, info.getPlayerBlack(), info.getPlayerWhite(), info.getDate(), info.getResult(),
        info.getKomi(), info.getHandicap());
    ArrayDeque<BoardHistoryNode> pending = new ArrayDeque<>();
    pending.push(history.getStart());
    while (!pending.isEmpty()) {
      var node = pending.pop();
      var d = node.getData();
      add(digest, node.numberOfChildren(), d.getNodeKind(), d.moveNumber, d.moveMNNumber,
          d.blackToPlay, d.lastMoveColor, Arrays.toString(d.lastMove.orElse(null)),
          Arrays.toString(d.stones), Arrays.toString(d.moveNumberList), d.comment, d.getProperties(),
          d.komi, d.blackCaptures, d.whiteCaptures, d.pda, d.pda2, d.isChanged, d.isChanged2,
          d.winrate, d.winrate2, d.getPlayouts(), d.getPlayouts2(),
          d.scoreMean, d.scoreMean2, d.scoreStdev, d.scoreStdev2, d.rootVisits, d.rootVisits2,
          d.estimateArray, d.estimateArray2, d.primaryAnalysisProvenance(), d.secondaryAnalysisProvenance(),
          d.primaryOwnershipProvenance(), d.secondaryOwnershipProvenance(),
          d.importedAnalysisDigest(false), d.importedAnalysisDigest(true),
          d.importedAnalysisWrn(false), d.importedAnalysisWrn(true));
      for (List<MoveData> moves : Arrays.asList(d.bestMoves, d.bestMovesOutOfRange,
          d.bestMoves2, d.bestMoves2OutOfRange)) {
        add(digest, moves == null ? -1 : moves.size());
        if (moves != null) for (var move : moves) {
          add(digest, move.coordinate, move.playouts, move.edgeVisits, move.winrate, move.lcb,
              move.policy, move.scoreMean, move.scoreStdev, move.order, move.variation,
              move.pvVisits, move.pvEdgeVisits, move.movesEstimateArray);
        }
      }
      var children = node.getVariations();
      for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void add(MessageDigest digest, Object... values) {
    for (Object value : values) {
      byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
      digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
      digest.update(bytes);
    }
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void diagnostics(Path dir, List<Path> paths) throws Exception {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("lz2-first", "(;LZ2[x])");
    cases.put("lz2-after-size", "(;SZ[19]LZ2[x]LZOP2[0.1 0.2])");
    cases.put("escape-text", "(;SZ[19]PB[a\\n b]C[a\\n b\\\n c\r\nd]XX[a\\x b\\]c])");
    cases.put("setup-range", "(;SZ[5:7]AB[aa:bb]AE[aa];W[eg])");
    cases.put("large-board-tt", "(;SZ[20:21];B[tt];W[])");
    cases.put("repeated-values", "(;SZ[19]XX[a][b]C[first]XX[c]LB[aa:a\\:b][bb:c])");
    cases.put("smart-go", "(;Size[19]AddBlack[aa]PlayerBlack[흑];Black[bb])");
    cases.put("lowercase", "(;sz[19];b[aa])");
    cases.put("collection", "(;SZ[9];B[aa])(;SZ[13];B[bb])");
    cases.put("surrounding-text", "prefix (;SZ[19]\\n;B[aa]) suffix");
    cases.put("missing-close", "(;SZ[19];B[aa]");
    cases.put("unescaped-close-bracket", "(;SZ[19]C[a]b])");
    cases.put("depth-260", "(;SZ[19]" + "(;B[]".repeat(260) + ")".repeat(261));
    provenanceCases(cases);
    for (var entry : cases.entrySet()) {
      write(dir, paths, entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
    }
    for (String encoding : List.of("GB18030", "Big5", "Shift_JIS", "EUC-KR", "UTF-16")) {
      String comment = encoding.equals("GB18030") || encoding.equals("Big5") ? "棋譜測試 黑白"
          : encoding.equals("Shift_JIS") ? "棋譜テスト 黒白" : "기보 검사 흑백";
      for (boolean declared : List.of(false, true)) {
        String text = "(;SZ[19]" + (declared ? "CA[" + encoding + "]" : "") + "C[" + comment + "];B[aa])";
        write(dir, paths, "encoding-" + encoding + (declared ? "-ca" : "-no-ca"),
            text.getBytes(Charset.forName(encoding)));
      }
    }
    byte[] malformed = "(;CA[UTF-8]C[bad_])".getBytes(StandardCharsets.UTF_8);
    malformed[15] = (byte) 0xff;
    write(dir, paths, "encoding-malformed", malformed);
  }

  private static void provenanceCases(Map<String, String> cases) throws Exception {
    // Same payload/schema contract as AnalysisProvenanceSgfTest, with independent small inputs.
    String payload = "KataGo 45 120 2.5 1.2 0 rootVisits=120\n"
        + "move C3 visits 120 winrate 5500 prior 1000 scoreMean 2.5 order 0 pv C3 D4 ownership 0.2 -0.2";
    JSONObject source = new JSONObject().put("engineName", "KataGo").put("engineVersion", "1.16.0")
        .put("modelName", "모델]\\net.bin.gz").put("modelSha256", "a".repeat(64))
        .put("rules", "chinese").put("komi", 7.5).put("wrn", 0.75);
    JSONObject slot = new JSONObject().put("payloadSha256", hash(payload.getBytes(StandardCharsets.UTF_8)))
        .put("analysis", source).put("ownership", source).put("invalidated", true);
    JSONObject metadata = new JSONObject().put("app", "KG-next").put("version", 2).put("primary", slot);
    String kgp = "KGP[" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(metadata.toString().getBytes(StandardCharsets.UTF_8)) + "]";
    String escaped = SGFParser.Escaping(payload);
    String valid = "(;SZ[5]LZOP[" + escaped + "]" + kgp + ")";
    cases.put("provenance-valid", valid);
    cases.put("provenance-literal-newline", "(;SZ[5]LZOP[" + escaped.replace("\n", "\\n") + "]" + kgp + ")");
    cases.put("provenance-escaped-linebreak", "(;SZ[5]LZOP[" + escaped.replace("\n", "\\\n") + "]" + kgp + ")");
    String stale = valid.replace("rootVisits=120", "rootVisits=121");
    cases.put("provenance-stale-digest", stale);
    metadata.put("secondary", slot);
    String bothKgp = "KGP[" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(metadata.toString().getBytes(StandardCharsets.UTF_8)) + "]";
    String both = "(;SZ[5]LZOP[" + escaped + "]LZOP2[" + escaped + "]" + bothKgp + ")";
    cases.put("provenance-secondary", both);
    BoardData good = SGFParser.parseSgf(valid, true, OPTIONS).getData();
    BoardData changed = SGFParser.parseSgf(stale, true, OPTIONS).getData();
    BoardData secondary = SGFParser.parseSgf(both, true, OPTIONS).getData();
    require(good.primaryAnalysisProvenance() != null && good.primaryOwnershipProvenance() != null
        && good.isChanged && good.bestMoves.size() == 1, "Valid provenance control was not imported");
    require(changed.primaryAnalysisProvenance() == null && changed.primaryOwnershipProvenance() == null,
        "Stale provenance digest was not rejected");
    require(secondary.secondaryAnalysisProvenance() != null && secondary.isChanged2
        && secondary.bestMoves2.size() == 1, "Valid secondary provenance control was not imported");
  }

  private static void write(Path dir, List<Path> paths, String name, byte[] bytes) throws IOException {
    Path path = dir.resolve(name + ".sgf");
    Files.write(path, bytes);
    paths.add(path);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private record Reply(long parseNanos, String encoding, List<String> warnings, String error,
      SgfDocument document) {}

  private static final class Bridge implements AutoCloseable {
    private final Process process;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "rust-probe-timeout");
      thread.setDaemon(true);
      return thread;
    });

    Bridge(Path binary, Path stderr) throws IOException {
      process = new ProcessBuilder(binary.toString())
          .redirectError(ProcessBuilder.Redirect.appendTo(stderr.toFile())).start();
      input = new DataInputStream(new BufferedInputStream(process.getInputStream()));
      output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
    }

    Reply parse(byte[] bytes) throws Exception {
      if (bytes.length > MAX_FRAME) throw new IOException("Probe input exceeds 64 MiB");
      var timeout = watchdog.schedule(() -> { process.destroyForcibly(); }, 30, TimeUnit.SECONDS);
      try {
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
        int size = input.readInt();
        if (size < 9 || size > MAX_FRAME) throw new IOException("Invalid response size: " + size);
        byte[] response = input.readNBytes(size);
        if (response.length != size) throw new EOFException("Incomplete response");
        var frame = new DataInputStream(new ByteArrayInputStream(response));
        long nanos = frame.readLong();
        int status = frame.readUnsignedByte();
        if (status == 1) return new Reply(nanos, "", List.of(), text(frame), null);
        if (status != 0) throw new IOException("Invalid response status: " + status);
        String encoding = text(frame);
        int count = frame.readInt();
        if (count < 0 || count > size / 4) throw new IOException("Invalid warning count");
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < count; i++) warnings.add(text(frame));
        List<SgfDocument.Event> events = new ArrayList<>();
        int[] dimensions = {19, 19};
        boolean hasSize = false;
        while (frame.available() > 0) {
          char kind = (char) frame.readUnsignedByte();
          String name = "", value = "";
          if (kind == 'P') {
            name = text(frame);
            value = text(frame);
            if (name.equals("SZ") && !hasSize) {
              int[] parsed = SgfDocument.parseBoardSizeTagValue(value);
              if (parsed != null) { dimensions = parsed; hasSize = true; }
            }
            // Rust has already unescaped all values. Re-escape exactly once for KG's per-tag policy.
            value = SGFParser.Escaping(value);
          } else if (kind != '(' && kind != ')' && kind != ';') {
            throw new IOException("Unknown event: " + kind);
          }
          events.add(new SgfDocument.Event(kind, name, value));
        }
        boolean multiGo = events.size() > 1 && events.get(events.size() - 1).kind() == ')'
            && events.get(events.size() - 2).kind() == ')';
        return new Reply(nanos, encoding, warnings, null,
            new SgfDocument(events, dimensions[0], dimensions[1], multiGo));
      } finally {
        timeout.cancel(false);
      }
    }

    private static String text(DataInputStream input) throws IOException {
      int length = input.readInt();
      if (length < 0 || length > input.available()) throw new IOException("Invalid text length");
      return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
      try {
        output.close();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Rust child did not terminate");
        }
        if (process.exitValue() != 0) throw new IOException("Rust child exit: " + process.exitValue());
      } finally {
        process.destroyForcibly();
        input.close();
        watchdog.shutdownNow();
      }
    }
  }
}
