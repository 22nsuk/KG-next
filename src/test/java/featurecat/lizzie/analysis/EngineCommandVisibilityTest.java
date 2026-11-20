package featurecat.lizzie.analysis;

import static featurecat.lizzie.analysis.EngineCommandVisibility.HIDDEN;
import static featurecat.lizzie.analysis.EngineCommandVisibility.HIDDEN_VALUE;
import static featurecat.lizzie.analysis.EngineCommandVisibility.VISIBLE;
import static featurecat.lizzie.analysis.HiddenCommandFixture.COMMAND;
import static featurecat.lizzie.analysis.HiddenCommandFixture.assertNoCanary;
import static featurecat.lizzie.analysis.HiddenCommandFixture.assertNoFragment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineCommandVisibilityTest {
  /** The OS reason in each JDK wording: Windows, older POSIX JDKs, current POSIX JDKs. */
  private static final String SYSTEM_REASON =
      "(?:CreateProcess )?error=\\d+, .+|[A-Za-z ]+ failed, error: \\d+ \\(.+\\)\\s*";

  static boolean namesSystemError(String text, int code) {
    return java.util.regex.Pattern.compile("error(?:=|: )" + code + "\\b").matcher(text).find();
  }

  @Test
  void savedEncryptedCommandIsDecodedForLaunchButReachesPresentationAsHidden() throws Exception {
    Leelaz hidden = new Leelaz("encryption||" + Utils.doEncrypt2(COMMAND));
    assertEquals(HIDDEN, hidden.engineCommandVisibility());
    assertEquals(COMMAND, hidden.engineCommand);
    assertEquals(
        Lizzie.resourceBundle.getString("Leelaz.encryption"), hidden.getEngineCommand());

    Leelaz plain = new Leelaz(COMMAND);
    assertEquals(VISIBLE, plain.engineCommandVisibility());
    assertEquals(COMMAND, plain.getEngineCommand());

    hidden.setEngineCommand(COMMAND);
    assertEquals(VISIBLE, hidden.engineCommandVisibility());
  }

  @Test
  void processStartFailureKeepsTheTypeAndTheSystemReasonOnly() {
    IOException failure = HiddenCommandFixture.processCreateFailure();
    assertTrue(failure.getMessage().contains("HiddenVault7"), failure.getMessage());

    String text = HIDDEN.errorText(failure);
    String message = HIDDEN.errorMessage(failure);

    assertNoCanary(text);
    String prefix = "Cannot run program " + HIDDEN_VALUE + " (in directory " + HIDDEN_VALUE + "): ";
    assertTrue(message.startsWith(prefix), message);
    assertTrue(message.substring(prefix.length()).matches(SYSTEM_REASON), message);
    assertEquals("java.io.IOException: " + message, text);
    assertEquals(failure.toString(), VISIBLE.errorText(failure));
    assertEquals(failure.getLocalizedMessage(), VISIBLE.errorMessage(failure));
  }

  @Test
  void anExecutableNameIsHiddenWhateverItsLength() {
    for (String program : List.of("u7x", "z9", "PrivateEngineCanary")) {
      IOException failure = HiddenCommandFixture.launchFailure(List.of(program), null);
      assertTrue(failure.getMessage().contains("\"" + program + "\""), failure.getMessage());

      String message = HIDDEN.errorMessage(failure);

      assertTrue(message.startsWith("Cannot run program " + HIDDEN_VALUE + ": "), message);
      assertTrue(namesSystemError(message, 2), message);
      assertNoFragment(message.replace(HIDDEN_VALUE, ""), List.of("\"", program));
    }
  }

  @Test
  void otherErrorsAndUnrecognisedReasonsShareOnlyTheirType() {
    IOException remote = new IOException("ssh PrivateHost-77: connection refused by u7x");
    assertEquals("java.io.IOException: " + HIDDEN_VALUE, HIDDEN.errorText(remote));
    assertEquals(HIDDEN_VALUE, HIDDEN.errorMessage(remote));

    String unknownReason = "Cannot run program " + HIDDEN_VALUE + ": " + HIDDEN_VALUE;
    IOException notTheJdkShape =
        new IOException("Cannot run program \"u7x\": see u7x", new IOException("error=2, u7x"));
    assertEquals(unknownReason, HIDDEN.errorMessage(notTheJdkShape));
    IOException noSystemError =
        new IOException(
            "Cannot run program \"u7x\": PrivateHost-77 refused",
            new IOException("PrivateHost-77 refused"));
    assertEquals(unknownReason, HIDDEN.errorMessage(noSystemError));
    IOException uncaused = new IOException("Cannot run program \"u7x\" (in directory \"/srv/q\")");
    assertEquals(
        "Cannot run program "
            + HIDDEN_VALUE
            + " (in directory "
            + HIDDEN_VALUE
            + "): "
            + HIDDEN_VALUE,
        HIDDEN.errorMessage(uncaused));
    String reason = "error=2, No such file or directory\nPrivateHost-77";
    IOException multiLine =
        new IOException("Cannot run program \"u7x\": " + reason, new IOException(reason));
    assertEquals(
        "Cannot run program " + HIDDEN_VALUE + ": error=2, No such file or directory",
        HIDDEN.errorMessage(multiLine));
    assertNull(HIDDEN.errorMessage(null));
  }

  @Test
  void engineOutputIsSharedOnlyAsTheFactThatThereWasSome() {
    String shellEcho = "sh: 1: exec: PrivateEngineCanary: not found";
    assertEquals(HIDDEN_VALUE, HIDDEN.engineOutput(shellEcho));
    assertEquals("", HIDDEN.engineOutput(""));
    assertNull(HIDDEN.engineOutput(null));
    assertEquals(shellEcho, VISIBLE.engineOutput(shellEcho));
  }

  @Test
  void onlyRuntimeLibrariesKeepTheirNameForAHiddenCommand() {
    for (String runtime :
        List.of(
            "cudnn64_9.dll",
            "cudnn_ops64_9.dll",
            "cudnn_cnn_infer64_8.dll",
            "cublasLt64_12.dll",
            "nvrtc-builtins64_128.dll",
            "nvJitLink_130_0.dll",
            "nvinfer_plugin_10.dll",
            "zlibwapi.dll",
            "VCRUNTIME140_1.dll",
            "msvcp140_atomic_wait.dll",
            "api-ms-win-crt-runtime-l1-1-0.dll",
            "OpenCL.dll")) {
      assertEquals(runtime, HIDDEN.libraryName(runtime));
    }
    for (String engineOwn :
        List.of("PrivateEngineCore.dll", "zPrivate.dll", "cudnn_private.dll", "u7x.dll")) {
      assertEquals(HIDDEN_VALUE, HIDDEN.libraryName(engineOwn));
      assertEquals(engineOwn, VISIBLE.libraryName(engineOwn));
    }
    assertNull(HIDDEN.libraryName(null));
  }

  @Test
  void dialogTextForAHiddenEngineNeverUsesTheSwitchFailureDetail() {
    String detail = "Cannot run program \"u7x\": error=2";
    assertEquals("fallback", Leelaz.startupFailureMessage(HIDDEN, null, detail, "fallback"));
    assertEquals(detail, Leelaz.startupFailureMessage(VISIBLE, null, detail, "fallback"));
    EngineStartupDiagnostic recorded =
        EngineStartupDiagnostic.basic(List.of("u7x"), "u7x", "recorded", HIDDEN);
    assertEquals("recorded", Leelaz.startupFailureMessage(HIDDEN, recorded, detail, "fallback"));
  }

  @Test
  void exitTextWithoutARecordKeepsNoEngineLineOfAHiddenCommand() throws Exception {
    List<String> lines = List.of("sh: 1: exec: PrivateEngineCanary: not found");
    String shellCommand = "sh -c 'exec PrivateEngineCanary'";
    Leelaz hidden = new Leelaz("encryption||" + Utils.doEncrypt2(shellCommand));
    String text = hidden.engineExitDiagnosticForTest("Engine ended", lines);
    assertNoFragment(text, List.of("PrivateEngineCanary", "exec"));
    assertTrue(text.startsWith("Engine ended"), text);
    assertTrue(text.contains(HIDDEN_VALUE), text);

    Leelaz visible = new Leelaz(shellCommand);
    assertTrue(
        visible.engineExitDiagnosticForTest("Engine ended", lines).contains("PrivateEngineCanary"));
  }
}
