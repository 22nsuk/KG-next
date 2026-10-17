package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.SavedEnginePathResolver.Candidate;
import featurecat.lizzie.util.SavedEnginePathResolver.Kind;
import featurecat.lizzie.util.SavedEnginePathResolver.Reason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedEnginePathResolverTest {
  @TempDir Path tempDir;

  @BeforeAll
  static void loadRuntimeClasses() throws ClassNotFoundException {
    Class.forName(Lizzie.class.getName());
  }

  @Test
  void quotedPathWithSpacesAndChineseBecomesOneExecutableArgumentWithoutSourceArguments()
      throws Exception {
    Path executable = file(tempDir.resolve("引擎 目录").resolve("katago bin"));
    EngineData engine =
        engine(
            "KataGo",
            quote(executable)
                + " gtp -model "
                + quote(tempDir.resolve("model.bin.gz"))
                + " -config "
                + quote(tempDir.resolve("gtp.cfg"))
                + " -override-config \"maxVisits=10\"");

    Candidate candidate = SavedEnginePathResolver.resolve(engine, 1);

    assertTrue(candidate.isAvailable());
    assertEquals(Kind.LOCAL, candidate.kind());
    assertEquals(executable.toAbsolutePath().normalize(), candidate.executable());
    // The contribution command is built as "<path field> contribute ..." and split again.
    assertEquals(
        List.of(executable.toString(), "contribute"),
        Utils.splitCommand(candidate.fieldText() + " contribute"));
  }

  @Test
  void pathWithQuoteCharactersStaysOneExecutableArgument() throws Exception {
    List<String> names =
        System.getProperty("os.name").startsWith("Windows")
            ? List.of("O'Neil")
            : List.of("O'Neil", "say\"hi", "both'\" mixed");
    for (String name : names) {
      Path executable = file(tempDir.resolve(name).resolve("katago"));
      String source = "\"" + executable.toString().replace("\"", "\"'\"'\"") + "\" gtp";

      Candidate candidate = SavedEnginePathResolver.resolve(engine(name, source), 1);

      assertEquals(executable.toAbsolutePath().normalize(), candidate.executable(), name);
      assertEquals(
          List.of(executable.toString(), "contribute"),
          Utils.splitCommand(candidate.fieldText() + " contribute"),
          name);
    }
  }

  @Test
  void unquotedPathWithoutSpacesIsFilledAsIs() throws Exception {
    Path executable = file(tempDir.resolve("katago"));

    Candidate candidate = SavedEnginePathResolver.resolve(engine("KataGo", executable + " gtp"), 1);

    assertEquals(executable.toString(), candidate.fieldText());
  }

  @Test
  void relativeExecutableResolvesToNormalizedAbsolutePath() throws Exception {
    Path root = Path.of("target", "saved-engine-resolver-" + UUID.randomUUID());
    try {
      Path executable = file(root.resolve("相对 路径").resolve("katago"));
      Candidate candidate =
          SavedEnginePathResolver.resolve(
              engine("Relative", quote(executable) + " gtp -config gtp.cfg"), 1);

      assertTrue(candidate.isAvailable());
      assertTrue(candidate.executable().isAbsolute());
      assertEquals(executable.toAbsolutePath().normalize(), candidate.executable());
    } finally {
      delete(root);
    }
  }

  @Test
  void entriesThatCannotSupplyALocalFileAreRejectedWithReasons() throws Exception {
    EngineData remote = engine("Remote", "/opt/katago gtp");
    remote.useJavaSSH = true;
    remote.password = "synthetic-ssh-password";
    Path wrapper = file(tempDir.resolve("run katago.bat"));

    assertRejected(remote, Kind.REMOTE, Reason.REMOTE_TRANSPORT);
    assertRejected(
        engine("ssh", "ssh -i key.pem user@host \"/opt/katago gtp -config gtp.cfg\""),
        Kind.INDIRECT,
        Reason.INDIRECT_LAUNCHER);
    assertRejected(engine("bat", quote(wrapper) + " gtp"), Kind.INDIRECT, Reason.INDIRECT_LAUNCHER);
    assertRejected(engine("Empty", "   "), Kind.UNKNOWN, Reason.EMPTY_COMMAND);
    assertRejected(engine("Null", null), Kind.UNKNOWN, Reason.EMPTY_COMMAND);
    assertRejected(engine("Quotes", "\"  \" gtp"), Kind.UNKNOWN, Reason.UNPARSEABLE_COMMAND);
    assertRejected(
        engine("Missing", quote(tempDir.resolve("absent").resolve("katago")) + " gtp"),
        Kind.LOCAL,
        Reason.MISSING_FILE);
    assertRejected(engine("Directory", quote(tempDir) + " gtp"), Kind.LOCAL, Reason.MISSING_FILE);
  }

  @Test
  void duplicateEntriesKeepTheirOwnObjectsAndDisplayOrder() throws Exception {
    Path first = file(tempDir.resolve("a").resolve("katago"));
    Path second = file(tempDir.resolve("b").resolve("katago"));
    EngineData a = engine("KataGo", quote(first) + " gtp");
    EngineData b = engine("KataGo", quote(second) + " gtp");
    EngineData sameAsB = engine("KataGo", quote(second) + " gtp");

    List<Candidate> candidates = SavedEnginePathResolver.resolveAll(List.of(a, b, sameAsB));

    assertSame(a, candidates.get(0).engine());
    assertSame(b, candidates.get(1).engine());
    assertSame(sameAsB, candidates.get(2).engine());
    assertEquals(List.of(1, 2, 3), candidates.stream().map(Candidate::displayIndex).toList());
    assertEquals(second.toAbsolutePath().normalize(), candidates.get(1).executable());
  }

  private static void assertRejected(EngineData engine, Kind kind, Reason reason) {
    Candidate candidate = SavedEnginePathResolver.resolve(engine, 1);
    assertFalse(candidate.isAvailable(), engine.name);
    assertNull(candidate.executable(), engine.name);
    assertEquals(kind, candidate.kind(), engine.name);
    assertEquals(reason, candidate.reason(), engine.name);
  }

  private static Path file(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "fixture");
    return path;
  }

  private static EngineData engine(String name, String commands) {
    EngineData engine = new EngineData();
    engine.name = name;
    engine.commands = commands;
    return engine;
  }

  private static String quote(Path path) {
    return "\"" + path + "\"";
  }

  private static void delete(Path root) throws Exception {
    if (!Files.exists(root)) return;
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
}
