package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.HumanSlAnalysisRunner.LaunchProfile;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import featurecat.lizzie.util.TensorRtCompanionFixture;
import featurecat.lizzie.util.TensorRtCompanionFixture.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** AI Coach on a TensorRT package must launch the verified CUDA companion without a zlib DLL. */
public class HumanSlTensorRtCompanionLaunchTest {
  private TensorRtCompanionFixture.Isolation isolation;

  @BeforeEach
  void isolateEnvironment() {
    isolation = TensorRtCompanionFixture.isolateEnvironment();
  }

  @AfterEach
  void restoreEnvironment() {
    isolation.close();
  }

  @ParameterizedTest
  @MethodSource("launchProfiles")
  void startHandsTheVerifiedCompanionToProcessCreationWithoutZlibDll(
      Layout layout, LaunchProfile profile) throws Exception {
    TensorRtCompanionFixture fixture = TensorRtCompanionFixture.install(layout);
    Path model = Files.writeString(fixture.root.resolve("human model.bin.gz"), "model");
    String command = fixture.humanSlCommand(model);
    AtomicReference<ProcessBuilder> launched = new AtomicReference<>();

    HumanSlAnalysisRunner runner = runner(command, model, launched, profile);

    assertNull(
        KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command),
        layout + ": component checks must agree with the companion launch preflight");
    assertFalse(runner.start(), layout.toString());
    assertNotNull(launched.get(), layout + ": " + runner.getUnavailableReason());
    assertEquals(fixture.companion.toString(), launched.get().command().get(0), layout.toString());
    assertNull(runner.getTensorRtRepairContext());
  }

  @ParameterizedTest
  @EnumSource(LaunchProfile.class)
  void missingCompanionRuntimeStopsBeforeProcessCreationWithRuntimeRepair(LaunchProfile profile)
      throws Exception {
    TensorRtCompanionFixture fixture =
        TensorRtCompanionFixture.install(Layout.ONLINE_INSTALL_WITH_SHARED_RUNTIME);
    Files.delete(fixture.runtimeDir.resolve("cudnn64_9.dll"));
    Path model = Files.writeString(fixture.root.resolve("human.bin.gz"), "model");
    AtomicReference<ProcessBuilder> launched = new AtomicReference<>();

    HumanSlAnalysisRunner runner = runner(fixture.humanSlCommand(model), model, launched, profile);

    assertFalse(runner.start());
    assertNull(launched.get(), "No process may be created while the companion runtime is missing.");
    TensorRtRepairContext context = runner.getTensorRtRepairContext();
    assertNotNull(context);
    assertEquals(List.of(TensorRtInstallStatus.MISSING_RUNTIME), context.missingItems);
    assertTrue(context.repairable);
  }

  @ParameterizedTest
  @EnumSource(Layout.class)
  void policyCacheFollowsCompanionUpdatesAndEffectiveLaunchSettings(Layout layout) throws Exception {
    TensorRtCompanionFixture fixture = TensorRtCompanionFixture.install(layout);
    Path model = Files.writeString(fixture.root.resolve("human model.bin.gz"), "model");
    Path cache = fixture.root.resolve("policy-cache");
    String command = HumanSlStrengthReuseSession.policyCommand(fixture.tensorRtEngine, model, cache);
    String originalEngineHash = TensorRtCompanionFixture.sha256(fixture.tensorRtEngine);
    String first = launchedPolicyFingerprint(command, model, fixture.companion);
    AtomicInteger queries = new AtomicInteger();
    HumanSlStrengthReuseSession.Query query =
        request -> {
          queries.incrementAndGet();
          return HumanSlStrength40Test.response(request);
        };
    for (int replay = 0; replay < 2; replay++) {
      HumanSlStrengthReuseSession.policies(
          HumanSlStrength40Test.game(),
          HumanSlStrengthReuseSessionTest.base(13),
          query,
          cache,
          first,
          () -> false,
          n -> {});
      assertEquals(36, queries.get(), "An unchanged effective launch reuses all cached policies");
    }

    fixture.updateHumanSlCompanion("updated valid cuda companion");
    assertEquals(originalEngineHash, TensorRtCompanionFixture.sha256(fixture.tensorRtEngine));
    String updated = launchedPolicyFingerprint(command, model, fixture.companion);
    assertNotEquals(first, updated, "The companion, rather than the original TensorRT file, owns the key");
    HumanSlStrengthReuseSession.policies(
        HumanSlStrength40Test.game(),
        HumanSlStrengthReuseSessionTest.base(13),
        query,
        cache,
        updated,
        () -> false,
        n -> {});
    assertEquals(72, queries.get(), "A valid companion update must query all positions again");

    String changedOverrides =
        launchedPolicyFingerprint(command + " -override-config nnRandomize=true", model, fixture.companion);
    assertNotEquals(updated, changedOverrides);
    Files.writeString(
        cache.resolve("policy-analysis.cfg"),
        "nnRandomize = false\n",
        java.nio.file.StandardOpenOption.APPEND);
    assertNotEquals(updated, launchedPolicyFingerprint(command, model, fixture.companion));
  }

  @ParameterizedTest
  @EnumSource(Layout.class)
  void preparedPolicyLaunchRetainsRuntimeRepairBeforeProcessCreation(Layout layout) throws Exception {
    TensorRtCompanionFixture fixture = TensorRtCompanionFixture.install(layout);
    Path model = Files.writeString(fixture.root.resolve("human.bin.gz"), "model");
    String command =
        HumanSlStrengthReuseSession.policyCommand(
            fixture.tensorRtEngine, model, fixture.root.resolve("policy-cache"));
    AtomicReference<ProcessBuilder> launched = new AtomicReference<>();
    try (HumanSlAnalysisRunner runner = runner(command, model, launched, LaunchProfile.POLICY_SUPPLEMENT)) {
      assertNotNull(runner.preparePolicyLaunch(), runner.getUnavailableReason());
      Files.delete(fixture.runtimeDir.resolve("cudnn64_9.dll"));
      assertFalse(runner.start());
      assertNull(launched.get());
      TensorRtRepairContext context = runner.getTensorRtRepairContext();
      assertNotNull(context);
      assertEquals(List.of(TensorRtInstallStatus.MISSING_RUNTIME), context.missingItems);
      // A full-package fixture has no managed repair target, unlike an in-app installation.
      // Pinning a launch must preserve the existing default runner's repair decision in both.
      AtomicReference<ProcessBuilder> normalLaunch = new AtomicReference<>();
      try (HumanSlAnalysisRunner normal = runner(command, model, normalLaunch, LaunchProfile.DEFAULT)) {
        assertFalse(normal.start());
        assertNull(normalLaunch.get());
        TensorRtRepairContext expected = normal.getTensorRtRepairContext();
        assertNotNull(expected);
        assertEquals(expected.missingItems, context.missingItems);
        assertEquals(expected.failureKind, context.failureKind);
        assertEquals(expected.repairable, context.repairable);
      }
    }
  }

  private static String launchedPolicyFingerprint(String command, Path model, Path companion)
      throws Exception {
    AtomicReference<ProcessBuilder> launched = new AtomicReference<>();
    try (HumanSlAnalysisRunner runner = runner(command, model, launched, LaunchProfile.POLICY_SUPPLEMENT)) {
      var prepared = runner.preparePolicyLaunch();
      assertNotNull(prepared, runner.getUnavailableReason());
      assertNull(launched.get(), "Computing a cache identity must not start a process");
      String fingerprint = HumanSlStrengthReuseSession.fingerprint(prepared);
      assertEquals(
          TensorRtCompanionFixture.sha256(companion),
          new JSONObject(fingerprint).getString("executableSha256"));
      assertFalse(runner.start());
      assertNotNull(launched.get(), runner.getUnavailableReason());
      assertEquals(companion.toString(), launched.get().command().get(0));
      assertEquals(prepared.commands(), launched.get().command());
      return fingerprint;
    }
  }

  static Stream<Arguments> launchProfiles() {
    return Stream.of(Layout.values())
        .flatMap(layout -> Stream.of(LaunchProfile.values()).map(p -> Arguments.of(layout, p)));
  }

  /** The starter records the final launch and stops it, so no fixture bytes are ever executed. */
  private static HumanSlAnalysisRunner runner(
      String analysisCommand,
      Path model,
      AtomicReference<ProcessBuilder> launched,
      LaunchProfile profile) {
    return new HumanSlAnalysisRunner(
        HumanSlAnalysisRunner.buildHumanSlCommand(analysisCommand, model),
        builder -> {
          launched.set(builder);
          throw new IOException("controlled launch stop");
        },
        profile);
  }
}
