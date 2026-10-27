package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisModelIdentityTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({
    "-model,-config,false",
    "--model,--config,true",
    "-weights,-config,false",
    "--weights,--config,true"
  })
  void directLocalFilesSupplyTheirHeaderAndCompleteStoredByteDigests(
      String modelFlag, String configFlag, boolean compressed) throws Exception {
    Path weights = Files.createDirectories(directory.resolve("weights with spaces"));
    Path model =
        writeModel(
            weights.resolve(compressed ? "renamed.bin.gz" : "renamed.bin"),
            "fixture-primary-network",
            "weights beyond the header\n".repeat(1000));
    Path config = Files.writeString(directory.resolve("gtp.cfg"), "numSearchThreads = 4\n");
    List<String> command =
        List.of(
            "katago", "gtp", modelFlag + "=" + directory.relativize(model), configFlag, "gtp.cfg");

    AnalysisModelIdentity.FilesIdentity identity = AnalysisModelIdentity.readLocal(command, directory);

    assertEquals("fixture-primary-network", identity.modelName());
    assertEquals(sha256(model), identity.modelSha256());
    assertEquals(sha256(config), identity.configSha256());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void multipleInputsLeaveOnlyTheAmbiguousIdentityUnknown(boolean multipleModels) throws Exception {
    Path model = writeModel(directory.resolve("primary.bin"), "primary-network", "weights");
    Path otherModel = writeModel(directory.resolve("other.bin"), "other-network", "other weights");
    Path config = Files.writeString(directory.resolve("gtp.cfg"), "numSearchThreads = 4\n");
    Path otherConfig = Files.writeString(directory.resolve("other.cfg"), "numSearchThreads = 8\n");
    List<String> command =
        List.of(
            "katago",
            "gtp",
            "-model",
            model.toString(),
            "-config",
            config.toString(),
            multipleModels ? "--weights" : "--config",
            (multipleModels ? otherModel : otherConfig).toString());

    AnalysisModelIdentity.FilesIdentity identity = AnalysisModelIdentity.readLocal(command, directory);

    assertEquals(multipleModels ? "" : "primary-network", identity.modelName());
    assertEquals(multipleModels ? "" : sha256(model), identity.modelSha256());
    assertEquals(multipleModels ? sha256(config) : "", identity.configSha256());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ssh", "/usr/bin/ssh", "C:\\Tools\\plink.exe", "bash", "python3", "docker", "wrapper.sh"
      })
  void indirectOrRemoteCommandsCannotBorrowMatchingLocalFiles(String launcher) throws Exception {
    Path model = writeModel(directory.resolve("primary.bin"), "local-network", "local weights");
    Path config = Files.writeString(directory.resolve("gtp.cfg"), "numSearchThreads = 4\n");
    List<String> command =
        List.of(launcher, "katago", "gtp", "-model", model.toString(), "-config", config.toString());

    assertUnknown(AnalysisModelIdentity.readLocal(command, directory));
    AnalysisModelIdentity.Lookup lookup = AnalysisModelIdentity.local(command, directory);
    try {
      assertUnknown(lookup.snapshot());
    } finally {
      lookup.close();
    }
  }

  @Test
  void missingFilesAndInvalidPathsDoNotBecomeModelNames() throws Exception {
    Files.createDirectory(directory.resolve("folder.bin"));
    for (String argument : List.of("missing.bin", "folder.bin", "invalid\0path", "")) {
      assertUnknown(
          AnalysisModelIdentity.readLocal(List.of("katago", "gtp", "-model", argument), directory));
    }
    assertUnknown(AnalysisModelIdentity.readLocal(List.of("katago", "gtp", "-model"), directory));
  }

  @Test
  void anUnrecognizedHeaderStillHasAByteIdentityWithoutGuessingFromTheFileName() throws Exception {
    Path model = Files.writeString(directory.resolve("plausible-network.bin"), "unrecognized data\n");

    AnalysisModelIdentity.FilesIdentity identity =
        AnalysisModelIdentity.readLocal(List.of("katago", "gtp", "-model", model.toString()), directory);

    assertEquals("", identity.modelName());
    assertEquals(sha256(model), identity.modelSha256());
    assertEquals("", identity.configSha256());
  }

  @Test
  void aNewLaunchRehashesChangedBytesEvenWhenPathSizeAndModificationTimeMatch() throws Exception {
    Path model = writeModel(directory.resolve("primary.bin"), "same-network", "weights-v1");
    Path config = Files.writeString(directory.resolve("gtp.cfg"), "numSearchThreads = 4\n");
    FileTime modelTime = Files.getLastModifiedTime(model);
    FileTime configTime = Files.getLastModifiedTime(config);
    long modelSize = Files.size(model);
    long configSize = Files.size(config);
    List<String> command =
        List.of("katago", "gtp", "-model", model.toString(), "-config", config.toString());
    AnalysisModelIdentity.Lookup original = AnalysisModelIdentity.local(command, directory);
    AnalysisModelIdentity.Lookup replacement = null;
    try {
      AnalysisModelIdentity.FilesIdentity originalIdentity = awaitModelIdentity(original);
      writeModel(model, "same-network", "weights-v2");
      Files.writeString(config, "numSearchThreads = 8\n");
      Files.setLastModifiedTime(model, modelTime);
      Files.setLastModifiedTime(config, configTime);
      assertEquals(modelSize, Files.size(model));
      assertEquals(configSize, Files.size(config));
      assertEquals(modelTime, Files.getLastModifiedTime(model));
      assertEquals(configTime, Files.getLastModifiedTime(config));

      replacement = AnalysisModelIdentity.local(command, directory);
      AnalysisModelIdentity.FilesIdentity replacementIdentity = awaitModelIdentity(replacement);

      assertEquals("same-network", replacementIdentity.modelName());
      assertEquals(sha256(model), replacementIdentity.modelSha256());
      assertEquals(sha256(config), replacementIdentity.configSha256());
      assertNotEquals(originalIdentity.modelSha256(), replacementIdentity.modelSha256());
      assertNotEquals(originalIdentity.configSha256(), replacementIdentity.configSha256());
      assertEquals(originalIdentity, original.snapshot(), "An earlier launch keeps its observation");
    } finally {
      original.close();
      if (replacement != null) replacement.close();
    }
  }

  @Test
  void snapshotDoesNotWaitForFileIoAndPublishesOnlyAfterCompletion() {
    CompletableFuture<AnalysisModelIdentity.FilesIdentity> result = new CompletableFuture<>();
    AnalysisModelIdentity.Lookup lookup = new AnalysisModelIdentity.Lookup(result);
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertUnknown(lookup.snapshot()));
      assertFalse(result.isDone());
      AnalysisModelIdentity.FilesIdentity observed =
          new AnalysisModelIdentity.FilesIdentity("observed-network", "model-sha", "config-sha");

      result.complete(observed);

      assertEquals(observed, lookup.snapshot());
    } finally {
      lookup.close();
    }
  }

  @Test
  void closingAPendingLookupCancelsItsWorkAndRejectsLaterIdentity() {
    CompletableFuture<AnalysisModelIdentity.FilesIdentity> result = new CompletableFuture<>();
    AnalysisModelIdentity.Lookup lookup = new AnalysisModelIdentity.Lookup(result);

    lookup.close();

    assertTrue(result.isCancelled());
    assertFalse(result.complete(new AnalysisModelIdentity.FilesIdentity("late-network", "", "")));
    assertUnknown(lookup.snapshot());
  }

  @Test
  void failedFileObservationRemainsUnknown() {
    CompletableFuture<AnalysisModelIdentity.FilesIdentity> result = new CompletableFuture<>();
    AnalysisModelIdentity.Lookup lookup = new AnalysisModelIdentity.Lookup(result);
    result.completeExceptionally(new IOException("Model is no longer readable"));

    assertUnknown(lookup.snapshot());
  }

  @Test
  void anEngineReportedNameDoesNotClaimLocalModelOrConfigurationDigests() {
    assertEquals(
        new AnalysisModelIdentity.FilesIdentity("remote-network", "", ""),
        AnalysisModelIdentity.knownModel("remote-network").snapshot());
    assertUnknown(AnalysisModelIdentity.knownModel(null).snapshot());
    assertUnknown(AnalysisModelIdentity.unknown().snapshot());
  }

  private static void assertUnknown(AnalysisModelIdentity.FilesIdentity identity) {
    assertEquals(new AnalysisModelIdentity.FilesIdentity("", "", ""), identity);
  }

  private static AnalysisModelIdentity.FilesIdentity awaitModelIdentity(
      AnalysisModelIdentity.Lookup lookup) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    AnalysisModelIdentity.FilesIdentity identity;
    while ((identity = lookup.snapshot()).modelSha256().isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(identity.modelSha256().isEmpty(), "The local file observation should finish");
    return identity;
  }

  private static Path writeModel(Path path, String modelName, String weights) throws Exception {
    byte[] bytes = (modelName + "\n17\n22\n19\n" + weights).getBytes(StandardCharsets.US_ASCII);
    if (path.getFileName().toString().endsWith(".gz")) {
      try (var stream = new GZIPOutputStream(Files.newOutputStream(path))) {
        stream.write(bytes);
      }
    } else {
      Files.write(path, bytes);
    }
    return path;
  }

  private static String sha256(Path path) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
}
