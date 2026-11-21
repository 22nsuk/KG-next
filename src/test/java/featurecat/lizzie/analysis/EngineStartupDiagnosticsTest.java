package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EngineStartupDiagnosticsTest {
  @Test
  void basicFailureRetainsCredentialArgumentBoundaries() {
    for (String secret : List.of("CANARY space&suffix;end", "CANARY\"quote suffix", "CANARY\\path suffix")) {
      var command = List.of("plink", "-pw", secret, "remote-engine");
      for (String missingCommand : new String[] {null, "", "   "}) {
        String shared = EngineStartupDiagnostic.basic(command, missingCommand, "fixture failure").shareText();
        assertFalse(shared.contains("CANARY"), shared);
        assertFalse(shared.contains("suffix"), shared);
        assertTrue(shared.contains("remote-engine"), shared);
        assertEquals(secret, command.get(2));
      }
    }
  }

  @Test
  void sharedFailureRetainsCredentialArgumentBoundaries() {
    for (String secret : List.of("CANARY space&suffix;end", "CANARY\"quote suffix", "CANARY\\path suffix")) {
      try (var service =
          new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
        var command = List.of("plink", "-pw", secret, "remote-engine");
        var builder = new ProcessBuilder(command);
        var attempt = service.begin("eng-secret", "MAIN_BOARD", command, true);
        attempt.capture(builder);
        String shared = attempt.fail("startup-exit", "fixture failure").shareText();
        assertFalse(shared.contains("CANARY"), shared);
        assertFalse(shared.contains("suffix"), shared);
        assertTrue(shared.contains("remote-engine"), shared);
        assertEquals(command, builder.command());
      }
    }
  }

  @Test
  void decodesWindowsBitPatternsWithoutGuessingUnknownCodes() {
    assertEquals("STATUS_DLL_NOT_FOUND", EngineStartupDiagnostic.windowsStatus(-1073741515));
    assertEquals("STATUS_INVALID_IMAGE_FORMAT", EngineStartupDiagnostic.windowsStatus(0xC000007B));
    assertEquals("STATUS_ENTRYPOINT_NOT_FOUND", EngineStartupDiagnostic.windowsStatus(0xC0000139));
    assertEquals("STATUS_DLL_INIT_FAILED", EngineStartupDiagnostic.windowsStatus(0xC0000142));
    assertEquals("STATUS_ILLEGAL_INSTRUCTION", EngineStartupDiagnostic.windowsStatus(0xC000001D));
    assertEquals("STATUS_ACCESS_VIOLATION", EngineStartupDiagnostic.windowsStatus(0xC0000005));
    assertEquals("unknown", EngineStartupDiagnostic.windowsStatus(-1));
    assertEquals("unknown", EngineStartupDiagnostic.windowsStatus(0));
  }

  @Test
  void preflightAndOversizedTailsRetainIdentityWithoutInventingProcess() {
    var policy = new EngineStartupDiagnostics.Policy(1, 150, 2, 128, 4096);
    try (EngineStartupDiagnostics service = new EngineStartupDiagnostics(policy, null)) {
      var attempt =
          service.begin("eng-bounded", "MAIN_BOARD", List.of("engine", "x".repeat(50000)), true);
      attempt.output("stderr", "old line");
      attempt.output("stderr", "long".repeat(50000));
      attempt.output("stdout", "\u001b".repeat(50000));
      var failure = attempt.fail("runtime-preflight", "error".repeat(50000));
      var json = failure.toJson();
      assertEquals("eng-bounded", failure.engineId());
      assertTrue(failure.sizeBytes() <= 4096);
      assertTrue(json.getBoolean("truncated"));
      assertTrue(json.getBoolean("stderrTruncated"));
      assertTrue(json.isNull("exitCode"));
      assertFalse(json.getJSONObject("launch").has("pid"));
      assertEquals("not-formed", json.getJSONObject("launch").getString("environmentState"));
      assertEquals("no-specific-dll-identified", json.getString("outcome"));
      assertTrue(
          json.getString("stderr").getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 128);
    }
  }

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void originalProcessTailCompletesFailureWithoutChangingCapturedCommand() throws Exception {
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      ProcessBuilder builder =
          new ProcessBuilder(
              "/bin/sh",
              "-c",
              "exec 1>&-; sleep 0.2; printf 'late original output\\n' >&2; exit 17");
      var attempt = service.begin("eng-original", "MAIN_BOARD", builder.command(), true);
      attempt.capture(builder);
      Process process = builder.start();
      builder.command("replacement-engine");
      attempt.attach(process, 7);
      assertNull(
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))
              .readLine());
      attempt.streamEnded("stdout", null);
      var first = attempt.fail("startup-exit", "EOF before ready");
      assertTrue(first.toJson().isNull("exitCode"));
      var stderr =
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream()));
      attempt.output("stderr", stderr.readLine());
      assertNull(stderr.readLine());
      attempt.streamEnded("stderr", null);
      var last = awaitTerminal(attempt);
      assertEquals(17, last.toJson().getInt("exitCode"));
      assertEquals("late original output", last.toJson().getString("stderr"));
      assertEquals("/bin/sh", last.toJson().getJSONObject("launch").getString("executable"));
      assertEquals(first.attemptId(), last.attemptId());
      assertEquals("collecting", first.toJson().getString("collectionState"));
      assertEquals("completed", last.toJson().getString("collectionState"));
      assertTrue(first.toJson().isNull("exitCode"));
      assertEquals("no-specific-dll-identified", last.toJson().getString("outcome"));
    }
  }

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void cancelledProcessDoesNotBecomeLoaderEvidence() throws Exception {
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", "sleep 5");
      var attempt = service.begin("eng-cancel", "MAIN_BOARD", builder.command(), true);
      attempt.capture(builder);
      Process process = builder.start();
      attempt.attach(process, 8);
      attempt.fail("startup-exit", "EOF");
      attempt.cancel();
      process.destroyForcibly();
      process.waitFor();
      var diagnostic = attempt.snapshot().toJson();
      assertTrue(diagnostic.isNull("exitCode"));
      assertEquals(
          "cancelled",
          diagnostic
              .getJSONObject("sources")
              .getJSONObject("process-tail")
              .getString("terminalReason"));
    }
  }

  @Test
  void runtimeInformationDoesNotInventSpecificDll() {
    try (var service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      var attempt = service.begin("custom", "MAIN_BOARD", List.of("custom.exe"), true);
      attempt.runtimePreflight(
          featurecat.lizzie.util.KataGoRuntimeHelper.inspectNvidiaRuntime(
              java.nio.file.Path.of("custom.exe")));
      var failure = attempt.fail("process-create", "Cannot create process").toJson();
      assertEquals("no-specific-dll-identified", failure.getString("outcome"));
      assertTrue(failure.getJSONArray("findings").getJSONObject(0).isNull("dll"));
      assertEquals("Cannot create process", failure.getString("originalError"));
    }
  }

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void stalledTailSettlesWithoutInventingExitOrAcceptingLaterOutput() throws Exception {
    try (var service =
        new EngineStartupDiagnostics(
            new EngineStartupDiagnostics.Policy(1, 100, 2, 128, 4096), null)) {
      Process process = new ProcessBuilder("/bin/sh", "-c", "exec sleep 5").start();
      try {
        var attempt = service.begin("stalled", "MAIN_BOARD", List.of("engine"), true);
        attempt.attachProcess(process);
        attempt.fail("startup-exit", "stdout EOF before ready");
        var failure = awaitTerminal(attempt);
        assertEquals("timeout", reason(attempt, "process-tail"));
        assertEquals("partial", failure.toJson().getString("collectionState"));
        assertTrue(failure.toJson().isNull("exitCode"));
        attempt.output("stderr", "late.dll was not found");
        assertSame(failure, attempt.snapshot());
        assertEquals("", failure.toJson().getString("stderr"));
      } finally {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }

  @Test
  void concurrentAttemptsMaintainStrictOutputIsolation() {
    var policy = new EngineStartupDiagnostics.Policy(4, 1000, 5, 512, 4096);
    try (EngineStartupDiagnostics service = new EngineStartupDiagnostics(policy, null)) {
      var attemptA = service.begin("eng-A", "MAIN_BOARD", List.of("engine-a"), true);
      var attemptB = service.begin("eng-B", "COMPARE", List.of("engine-b"), true);

      attemptA.output("stdout", "stdout-line-A1");
      attemptA.output("stderr", "stderr-line-A1");
      attemptB.output("stdout", "stdout-line-B1");
      attemptB.output("stderr", "stderr-line-B1");
      attemptA.output("stdout", "stdout-line-A2");
      attemptB.output("stderr", "stderr-line-B2");

      var failureA = attemptA.fail("startup-exit", "failure in A");
      var failureB = attemptB.fail("startup-exit", "failure in B");

      var jsonA = failureA.toJson();
      var jsonB = failureB.toJson();

      assertEquals("eng-A", failureA.engineId());
      assertEquals("stdout-line-A1\nstdout-line-A2", jsonA.getString("stdout"));
      assertEquals("stderr-line-A1", jsonA.getString("stderr"));
      assertFalse(jsonA.getString("stdout").contains("B1"));
      assertFalse(jsonA.getString("stderr").contains("B1"));

      assertEquals("eng-B", failureB.engineId());
      assertEquals("stdout-line-B1", jsonB.getString("stdout"));
      assertEquals("stderr-line-B1\nstderr-line-B2", jsonB.getString("stderr"));
      assertFalse(jsonB.getString("stdout").contains("A1"));
      assertFalse(jsonB.getString("stderr").contains("A1"));
    }
  }

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void mergedProcessOutputDoesNotClaimStderrWasStdout() throws Exception {
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      ProcessBuilder builder =
          new ProcessBuilder("/bin/sh", "-c", "printf 'missing x.dll\\n' >&2; exit 17");
      builder.redirectErrorStream(true);
      var attempt = service.begin("eng-merged", "PROBE", builder.command(), true);
      attempt.capture(builder);
      Process process = builder.start();
      attempt.attachProcess(process);
      String line =
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))
              .readLine();
      attempt.output("merged", line);
      assertEquals(17, process.waitFor());
      attempt.streamEnded("stdout", null);
      attempt.streamEnded("stderr", null);
      var result = attempt.fail("startup-exit", "probe failed").toJson();
      assertEquals("merged", result.getString("stdoutOrigin"));
      assertEquals(line, result.getString("stdout"));
      assertEquals("", result.getString("stderr"));
      assertEquals(
          "engine-merged", result.getJSONArray("findings").getJSONObject(0).getString("evidence"));
    }
  }

  @Test
  void hiddenAttemptKeepsNoCommandFragmentInSnapshotShareOrPublishedRecord() {
    List<EngineStartupDiagnostic> published = new java.util.ArrayList<>();
    var policy = EngineStartupDiagnostics.Policy.production();
    try (var service = new EngineStartupDiagnostics(policy, published::add)) {
      var attempt =
          service.begin(
              "eng-hidden",
              "MAIN_BOARD",
              HiddenCommandFixture.ARGV,
              true,
              EngineCommandVisibility.HIDDEN);
      var builder =
          new ProcessBuilder(HiddenCommandFixture.ARGV)
              .directory(new java.io.File(HiddenCommandFixture.WORKING_DIRECTORY));
      attempt.capture(builder);
      attempt.output("stderr", HiddenCommandFixture.DLL_FAILURE_LINE);
      var failure = attempt.fail("process-create", HiddenCommandFixture.processCreateFailure());

      var json = failure.toJson();
      var launch = json.getJSONObject("launch");
      assertEquals(EngineCommandVisibility.HIDDEN_VALUE, launch.getString("configuredCommand"));
      assertEquals(EngineCommandVisibility.HIDDEN_VALUE, launch.getString("executable"));
      assertEquals(0, launch.getJSONArray("arguments").length());
      assertEquals(EngineCommandVisibility.HIDDEN_VALUE, launch.getString("cwd"));
      assertTrue(launch.getBoolean("commandHidden"));
      assertFalse(published.isEmpty());
      HiddenCommandFixture.assertNoCanary(json.toString());
      HiddenCommandFixture.assertNoCanary(failure.shareText());
      for (var record : published) HiddenCommandFixture.assertNoCanary(record.toJson().toString());
      String originalError = json.getString("originalError");
      assertTrue(
          EngineCommandVisibilityTest.namesSystemError(originalError, 2)
              // Windows reports the missing working directory itself.
              || EngineCommandVisibilityTest.namesSystemError(originalError, 267),
          originalError);
      assertEquals(EngineCommandVisibility.HIDDEN_VALUE, json.getString("stderr"));
      var finding = json.getJSONArray("findings").getJSONObject(0);
      assertEquals("dll-not-found", finding.getString("outcome"));
      assertEquals("cudnn64_9.dll", finding.getString("dll"));
      assertEquals(EngineCommandVisibility.HIDDEN_VALUE, finding.getString("detail"));
      assertEquals("process-create", json.getString("phase"));
      assertEquals(HiddenCommandFixture.ARGV, builder.command());
    }
  }

  /** A shell reports a word it split off an argument, never the argument as it was saved. */
  @Test
  void hiddenShellCommandKeepsNoWordTheShellEchoed() {
    List<String> argv = List.of("sh", "-c", "exec PrivateEngineCanary");
    try (var service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      var attempt =
          service.begin("eng-shell", "MAIN_BOARD", argv, true, EngineCommandVisibility.HIDDEN);
      attempt.capture(new ProcessBuilder(argv));
      attempt.output("stderr", "sh: 1: exec: PrivateEngineCanary: not found");
      attempt.output("stdout", "PrivateEngineCanary starting");
      var failure = attempt.fail("startup-exit", "stdout EOF before ready");

      String text = failure.toJson().toString() + failure.shareText();
      HiddenCommandFixture.assertNoFragment(text, List.of("PrivateEngineCanary", "exec:"));
      assertEquals(EngineCommandVisibility.HIDDEN_VALUE, failure.toJson().getString("stderr"));
      assertEquals(EngineCommandVisibility.HIDDEN_VALUE, failure.toJson().getString("stdout"));
      assertEquals("stdout EOF before ready", failure.toJson().getString("originalError"));
    }
  }

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void realShellOutputOfAHiddenCommandStaysOutOfTheRecord() throws Exception {
    List<String> argv = List.of("/bin/sh", "-c", "exec PrivateEngineCanary");
    for (var visibility : EngineCommandVisibility.values()) {
      try (var service =
          new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
        var attempt = service.begin("eng-shell", "MAIN_BOARD", argv, true, visibility);
        ProcessBuilder builder = new ProcessBuilder(argv);
        attempt.capture(builder);
        Process process = builder.start();
        attempt.attach(process, 1);
        try (var stderr =
            new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream()))) {
          for (String line = stderr.readLine(); line != null; line = stderr.readLine())
            attempt.output("stderr", line);
        }
        process.getInputStream().close();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        attempt.streamEnded("stdout", null);
        attempt.streamEnded("stderr", null);
        attempt.fail("startup-exit", "stdout EOF before ready");
        var record = awaitTerminal(attempt).toJson();

        assertEquals(127, record.getInt("exitCode"));
        if (visibility.isHidden()) {
          HiddenCommandFixture.assertNoFragment(
              record.toString(), List.of("PrivateEngineCanary", "/bin/sh"));
        } else {
          // The shell's own report names the word, so the hidden case above is a real check.
          assertTrue(record.getString("stderr").contains("PrivateEngineCanary"), record.toString());
        }
      }
    }
  }

  @Test
  void hiddenExecutableIsAbsentFromAProcessCreateFailureWhateverItsLength() throws Exception {
    List<String> argv = List.of("u7x");
    try (var service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      var attempt =
          service.begin("eng-short", "MAIN_BOARD", argv, true, EngineCommandVisibility.HIDDEN);
      ProcessBuilder builder = new ProcessBuilder(argv);
      attempt.capture(builder);
      var failure =
          attempt.fail("process-create", HiddenCommandFixture.launchFailure(argv, null));

      String text = failure.toJson().toString() + failure.shareText();
      HiddenCommandFixture.assertNoFragment(text, List.of("u7x"));
      assertTrue(
          EngineCommandVisibilityTest.namesSystemError(
              failure.toJson().getString("originalError"), 2),
          text);
    }
  }

  /** Bounding first used to keep the start of a value that the reduction then failed to find. */
  @Test
  void hiddenValuesCutByALengthLimitLeaveNoPrefixBehind() {
    String secret = "PrivateRunProfile-57B";
    String prefix = "PrivateRunProfile-57";
    var policy = new EngineStartupDiagnostics.Policy(4, 1000, 40, 1024, 256 * 1024);
    List<String> argv = List.of("x".repeat(16378) + secret, "gtp");
    try (var service = new EngineStartupDiagnostics(policy, null)) {
      var attempt =
          service.begin("eng-long", "MAIN_BOARD", argv, true, EngineCommandVisibility.HIDDEN);
      attempt.capture(new ProcessBuilder(argv));
      attempt.output("stderr", "y".repeat(1010) + " " + secret + " loaded");
      attempt.output(
          "stderr", "Could not find " + "z".repeat(480) + "\\" + secret + "\\cudnn64_9.dll");
      var failure = attempt.fail("process-create", HiddenCommandFixture.launchFailure(argv, null));

      String text = failure.toJson().toString() + failure.shareText();
      HiddenCommandFixture.assertNoFragment(text, List.of(prefix, "PrivateRun"));
      assertTrue(failure.toJson().getString("originalError").startsWith("java.io.IOException"));
    }

    String composed =
        "Engine failed: "
            + EngineCommandVisibility.HIDDEN.errorMessage(
                HiddenCommandFixture.launchFailure(argv, null));
    var basic =
        EngineStartupDiagnostic.basic(
            argv, String.join(" ", argv), composed, EngineCommandVisibility.HIDDEN);
    HiddenCommandFixture.assertNoFragment(
        basic.toJson().toString() + basic.shareText(), List.of(prefix));
  }

  /**
   * Only values that came from the command or the engine are withheld. A hidden argument spelled
   * like a stage, a state or the platform leaves the application's own fields as they are.
   */
  @Test
  void hiddenArgumentsSpelledLikeRecordVocabularyLeaveTheRecordIntact() {
    List<String> argv =
        List.of(
            "katago",
            "gtp",
            "-config",
            "windows",
            "non-windows",
            "final",
            "completed",
            "process-create",
            "unavailable",
            "MAIN_BOARD",
            "eng-vocab",
            "dll-not-found",
            "engine-stderr");
    var visible = vocabularyFailure(argv, EngineCommandVisibility.VISIBLE).toJson();
    var hidden = vocabularyFailure(argv, EngineCommandVisibility.HIDDEN).toJson();

    for (String field :
        List.of(
            "engineId",
            "launchPurpose",
            "phase",
            "statusName",
            "errorDomain",
            "exitObservation",
            "stdoutOrigin",
            "collectionState",
            "outcome")) {
      assertEquals(visible.get(field), hidden.get(field), field);
    }
    for (String field :
        List.of(
            "engineId", "launchPurpose", "environmentState", "local", "platform", "cwdSource")) {
      assertEquals(
          visible.getJSONObject("launch").get(field),
          hidden.getJSONObject("launch").get(field),
          "launch." + field);
    }
    assertEquals(
        visible.getJSONObject("sources").toString(), hidden.getJSONObject("sources").toString());
    var visibleFinding = visible.getJSONArray("findings").getJSONObject(0);
    var hiddenFinding = hidden.getJSONArray("findings").getJSONObject(0);
    assertEquals(visibleFinding.get("outcome"), hiddenFinding.get("outcome"));
    assertEquals(visibleFinding.get("evidence"), hiddenFinding.get("evidence"));
    assertEquals(visibleFinding.get("dll"), hiddenFinding.get("dll"));

    var basic =
        EngineStartupDiagnostic.basic(
            argv, String.join(" ", argv), "Engine failed", EngineCommandVisibility.HIDDEN);
    var basicVisible =
        EngineStartupDiagnostic.basic(argv, String.join(" ", argv), "Engine failed");
    for (String field : List.of("platform", "environmentState", "engineId")) {
      assertEquals(
          basicVisible.toJson().getJSONObject("launch").get(field),
          basic.toJson().getJSONObject("launch").get(field),
          "basic launch." + field);
    }
    assertEquals("Engine failed", basic.toJson().getString("originalError"));
  }

  private static EngineStartupDiagnostic vocabularyFailure(
      List<String> argv, EngineCommandVisibility visibility) {
    try (var service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      var attempt = service.begin("eng-vocab", "MAIN_BOARD", argv, true, visibility);
      attempt.capture(new ProcessBuilder(argv));
      attempt.output("stderr", "Could not find cudnn64_9.dll");
      return attempt.fail("process-create", "Engine failed");
    }
  }

  @Test
  void hiddenBasicFailureKeepsOnlyTheCommandMarker() {
    String composed =
        "Engine failed: "
            + EngineCommandVisibility.HIDDEN.errorMessage(
                HiddenCommandFixture.processCreateFailure());
    var basic =
        EngineStartupDiagnostic.basic(
            HiddenCommandFixture.ARGV,
            HiddenCommandFixture.COMMAND,
            composed,
            EngineCommandVisibility.HIDDEN);
    var json = basic.toJson();
    assertEquals(
        EngineCommandVisibility.HIDDEN_VALUE,
        json.getJSONObject("launch").getString("configuredCommand"));
    assertTrue(json.getJSONObject("launch").getBoolean("commandHidden"));
    assertEquals(composed, json.getString("originalError"));
    HiddenCommandFixture.assertNoCanary(json.toString());
    HiddenCommandFixture.assertNoCanary(basic.shareText());

    var visible =
        EngineStartupDiagnostic.basic(
            HiddenCommandFixture.ARGV, HiddenCommandFixture.COMMAND, "Engine failed");
    assertEquals(
        HiddenCommandFixture.COMMAND,
        visible.toJson().getJSONObject("launch").getString("configuredCommand"));
    assertFalse(visible.toJson().getJSONObject("launch").has("commandHidden"));
  }

  private static String reason(EngineStartupDiagnostics.Attempt attempt, String source) {
    return attempt
        .snapshot()
        .toJson()
        .getJSONObject("sources")
        .getJSONObject(source)
        .getString("terminalReason");
  }

  private static EngineStartupDiagnostic awaitTerminal(EngineStartupDiagnostics.Attempt attempt)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      var value = attempt.snapshot();
      if (!value.toJson().getString("outcome").equals("collecting")) return value;
      Thread.sleep(5);
    }
    fail("Diagnostic did not settle");
    return null;
  }
}
