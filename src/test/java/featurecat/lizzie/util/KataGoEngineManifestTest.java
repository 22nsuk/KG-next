package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class KataGoEngineManifestTest {
  private static final String DIGEST = "ab".repeat(32);
  private static final String HISTORICAL_COMMIT = "1234567890ab" + "1".repeat(28);
  private static final String FORK_REPOSITORY = "https://github.com/22nsuk/KataGo";

  @TempDir Path folder;

  @Test
  void strictReaderPreservesForkProvenanceAndPairedCompanionWithCrLf() throws Exception {
    Map<String, String> manifest =
        read(
            (historicalManifest("windows-nvidia", true)
                    + "HumanSL companion: katago-human-sl-cuda.exe\n"
                    + "HumanSL companion SHA-256: "
                    + DIGEST
                    + "\n")
                .replace("\n", "\r\n"));

    assertEquals(HISTORICAL_COMMIT, manifest.get("Source commit"));
    assertEquals(FORK_REPOSITORY, manifest.get("Source repository"));
    assertEquals("22nsuk/KataGo", manifest.get("Engine release repository"));
    assertEquals("kg-next-1234567890ab", manifest.get("Engine release tag"));
    assertEquals("katago-human-sl-cuda.exe", manifest.get("HumanSL companion"));
    assertEquals(DIGEST, manifest.get("HumanSL companion SHA-256"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedManifests")
  void strictReaderRejectsMalformedMetadata(String reason, String text) throws Exception {
    assertNull(read(text), reason);
  }

  private static Stream<Arguments> malformedManifests() {
    String valid = historicalManifest("windows-nvidia", true);
    return Stream.of(
        Arguments.of("duplicate key", valid + "Origin: project-source-build\n"),
        Arguments.of("unknown key", valid + "Signed by: someone\n"),
        Arguments.of("missing separator", valid + "Manifest schema 2\n"),
        Arguments.of("empty key", valid + ": value\n"),
        Arguments.of("empty value", valid + "HumanSL companion: \n"),
        Arguments.of("interior blank line", valid.replace("Origin: ", "\nOrigin: ")),
        Arguments.of("unpaired companion", valid + "HumanSL companion: katago-human-sl-cuda.exe\n"),
        Arguments.of("unpaired companion digest", valid + "HumanSL companion SHA-256: " + DIGEST));
  }

  @ParameterizedTest
  @CsvSource({
    "windows-nvidia, true",
    "windows-nvidia-cuda13, true",
    "windows-nvidia, false",
    "windows-tensorrt, false"
  })
  void earlierCoherentProjectBuildsKeepTheirStaticZlibRecognition(
      String assetId, boolean forkSource) throws Exception {
    assertTrue(
        KataGoEngineManifest.describesProjectStaticZlibBuild(
            read(historicalManifest(assetId, forkSource)),
            asset(assetId),
            DIGEST.toUpperCase(Locale.ROOT)));
  }

  @ParameterizedTest
  @CsvSource({"windows-nvidia, windows-nvidia-cuda13", "windows-nvidia-cuda13, windows-nvidia"})
  void cudaProfilesCannotBorrowEachOthersManifest(String recordedAssetId, String selectedAssetId)
      throws Exception {
    assertFalse(
        KataGoEngineManifest.describesProjectStaticZlibBuild(
            read(historicalManifest(recordedAssetId, true)), asset(selectedAssetId), DIGEST));
  }

  @ParameterizedTest(name = "reject {0} = {1}")
  @MethodSource("invalidBuildFields")
  void historicalRecognitionRejectsChangedBuildContract(String field, String value)
      throws Exception {
    Map<String, String> manifest = read(historicalManifest("windows-nvidia", true));
    setOrRemove(manifest, field, value);

    assertFalse(
        KataGoEngineManifest.describesProjectStaticZlibBuild(
            manifest, asset("windows-nvidia"), DIGEST));
  }

  private static Stream<Arguments> invalidBuildFields() {
    return Stream.of(
        Arguments.of("Manifest schema", "1"),
        Arguments.of("Manifest schema", null),
        Arguments.of("Origin", "official-release"),
        Arguments.of("Zlib linkage", "dynamic"),
        Arguments.of("Backend", "tensorrt"),
        Arguments.of("Executable SHA-256", "cd".repeat(32)),
        Arguments.of("Executable SHA-256", "ab".repeat(31)),
        Arguments.of("Executable SHA-256", "zz".repeat(32)),
        Arguments.of("Executable SHA-256", null));
  }

  @ParameterizedTest(name = "reject {0} = {1}")
  @MethodSource("invalidForkSourceFields")
  void historicalRecognitionRejectsPartialOrIncoherentForkSource(String field, String value)
      throws Exception {
    Map<String, String> manifest = read(historicalManifest("windows-nvidia", true));
    setOrRemove(manifest, field, value);

    assertFalse(
        KataGoEngineManifest.describesProjectStaticZlibBuild(
            manifest, asset("windows-nvidia"), DIGEST));
  }

  private static Stream<Arguments> invalidForkSourceFields() {
    return Stream.of(
        Arguments.of("Source repository", null),
        Arguments.of("Engine release repository", null),
        Arguments.of("Engine release tag", null),
        Arguments.of("Source repository", "https://github.com/lightvector/KataGo"),
        Arguments.of("Engine release repository", "other/KataGo"),
        Arguments.of("Engine release tag", "latest"),
        Arguments.of("Engine release tag", "kg-next-000000000000"),
        Arguments.of("Source commit", "main"),
        Arguments.of("Source commit", "1".repeat(39)),
        Arguments.of("Source commit", "2".repeat(40)),
        Arguments.of("Source commit", null));
  }

  @Test
  void cuda13RequiresForkIdentityAndTensorRtCannotClaimThatIdentity() throws Exception {
    assertFalse(
        KataGoEngineManifest.describesProjectStaticZlibBuild(
            read(historicalManifest("windows-nvidia-cuda13", false)),
            asset("windows-nvidia-cuda13"),
            DIGEST));
    assertFalse(
        KataGoEngineManifest.describesProjectStaticZlibBuild(
            read(historicalManifest("windows-tensorrt", true)), asset("windows-tensorrt"), DIGEST));
  }

  @Test
  void historicalRecognitionRequiresAManifestSelectedAssetAndMatchingExecutableDigest()
      throws Exception {
    Map<String, String> manifest = read(historicalManifest("windows-nvidia", true));
    assertFalse(
        KataGoEngineManifest.describesProjectStaticZlibBuild(
            null, asset("windows-nvidia"), DIGEST));
    assertFalse(KataGoEngineManifest.describesProjectStaticZlibBuild(manifest, null, DIGEST));
    assertFalse(
        KataGoEngineManifest.describesProjectStaticZlibBuild(
            manifest, asset("windows-nvidia"), null));
  }

  private static void setOrRemove(Map<String, String> manifest, String field, String value) {
    if (value == null) manifest.remove(field);
    else manifest.put(field, value);
  }

  private static KataGoAssetCatalog.Asset asset(String assetId) {
    return KataGoAssetCatalog.get().asset(assetId);
  }

  private static String historicalManifest(String assetId, boolean forkSource) {
    String source =
        forkSource
            ? "Source repository: "
                + FORK_REPOSITORY
                + "\nEngine release repository: 22nsuk/KataGo\n"
                + "Engine release tag: kg-next-1234567890ab\n"
            : "";
    return "Manifest schema: 2\nKataGo release: v1.18.1\nAsset ID: "
        + assetId
        + "\nAsset: katago-source-1234567890ab-"
        + assetId
        + ".zip\nAsset SHA-256: "
        + "0".repeat(64)
        + "\nExecutable SHA-256: "
        + DIGEST
        + "\nBackend: "
        + (assetId.equals("windows-tensorrt") ? "tensorrt" : "cuda")
        + "\nOrigin: project-source-build\nSource commit: "
        + HISTORICAL_COMMIT
        + "\n"
        + source
        + "Zlib linkage: static\n";
  }

  private Map<String, String> read(String text) throws Exception {
    Path manifest = folder.resolve("lizzieyzy-next-katago-engine-manifest.txt");
    Files.writeString(manifest, text);
    return KataGoEngineManifest.readStrict(manifest);
  }
}
