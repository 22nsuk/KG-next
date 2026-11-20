package featurecat.lizzie.analysis.gtpconfig;

import static featurecat.lizzie.analysis.HiddenCommandFixture.assertNoFragment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.EngineCommandVisibility;
import featurecat.lizzie.analysis.HiddenCommandFixture;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.ObservationText;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The configuration probe decodes a saved {@code encryption||} command on its own. Its launch
 * failure, the failures quoting the engine's replies (all shown by the engine editor) and its
 * engine-log lines must not carry that command, whatever shape the command's text takes there.
 */
class GtpConfigurationProbeHiddenCommandTest {
  private static final String SHORT_EXECUTABLE = "u7x";
  private static final List<String> LAUNCH_FRAGMENTS =
      List.of(SHORT_EXECUTABLE, "PrivateVault-31", "secret-net-b40", "Q9pass");

  /** A value that a line bound cuts in the middle, leaving only its beginning behind. */
  private static final String CUT_VALUE = "PrivateRunProfile-57" + "Z".repeat(100);

  @TempDir Path tempDir;
  private LoggingRuntime runtime;

  @BeforeEach
  void setUp() {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
  }

  @AfterEach
  void tearDown() {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
  }

  @Test
  void hiddenLaunchFailureKeepsOnlyTheSystemReason() throws Exception {
    String command = missingEngineCommand();

    IOException hidden =
        assertThrows(
            IOException.class,
            () ->
                new GtpConfigurationProbe()
                    .inspect("encryption||" + Utils.doEncrypt2(command), Duration.ofSeconds(2)));
    IOException visible =
        assertThrows(
            IOException.class,
            () -> new GtpConfigurationProbe().inspect(command, Duration.ofSeconds(2)));

    assertNoFragment(hidden.getMessage(), LAUNCH_FRAGMENTS);
    assertTrue(
        Pattern.compile(
                "^Cannot run program <hidden-command>(?: \\(in directory <hidden-command>\\))?: "
                    + "(?!<hidden-command>)\\S")
            .matcher(hidden.getMessage())
            .find(),
        hidden.getMessage());
    assertNull(hidden.getCause(), "the original exception would carry the clear-text message");
    assertTrue(visible.getMessage().contains(SHORT_EXECUTABLE + "\""), visible.getMessage());
  }

  @Test
  void visibleLaunchFailureIsRethrownUnchanged() {
    IOException failure = HiddenCommandFixture.processCreateFailure();

    assertSame(
        failure, GtpConfigurationProbe.launchFailure(EngineCommandVisibility.VISIBLE, failure));
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void hiddenProbeStderrCarriesNoWordTheShellSplitOffAnArgument() throws Exception {
    // The script's own words also spell the probe's log vocabulary.
    String command = "sh -c \"exec PrivateEngineCanary\" probe event=stderr failed";

    assertThrows(
        IOException.class,
        () ->
            new GtpConfigurationProbe()
                .inspect("encryption||" + Utils.doEncrypt2(command), Duration.ofSeconds(5)));
    String app = awaitApp();

    assertNoFragment(app, List.of("PrivateEngineCanary"));
    assertTrue(app.contains("probe event=failed stage="), app);
    assertTrue(app.contains("probe event=stderr facts=<hidden-command>"), app);
    assertTrue(app.contains("model=hidden-command config=hidden-command"), app);
    assertTrue(app.contains("command=<hidden-command>"), app);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void visibleProbeStderrKeepsTheShellReport() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            new GtpConfigurationProbe()
                .inspect("sh -c \"exec PrivateEngineCanary\"", Duration.ofSeconds(5)));

    assertTrue(awaitApp().contains("PrivateEngineCanary: not found"));
  }

  @Test
  void hiddenProbeStderrLeavesNothingOfAValueCutByTheLineBound() throws Exception {
    String hidden = paddedEchoCommand();

    assertThrows(
        IOException.class,
        () ->
            new GtpConfigurationProbe()
                .inspect("encryption||" + Utils.doEncrypt2(hidden), Duration.ofSeconds(5)));
    String app = awaitApp();

    assertNoFragment(app, List.of("PrivateRunProfile", "yyyyyyyy"));
    assertTrue(app.contains("probe event=stderr facts=<hidden-command>"), app);
  }

  @Test
  void visibleProbeStderrShowsTheLineBoundCuttingTheValue() throws Exception {
    assertThrows(
        IOException.class,
        () -> new GtpConfigurationProbe().inspect(paddedEchoCommand(), Duration.ofSeconds(5)));
    String stderr =
        awaitApp()
            .lines()
            .filter(line -> line.contains("probe event=stderr facts="))
            .findFirst()
            .orElse("");

    // The bound keeps the beginning of the value and drops the rest: no whole fragment remains.
    assertTrue(stderr.endsWith("PrivateRunProfile-57ZZZZZZZZ [truncated]"), stderr);
    assertTrue(!stderr.contains(CUT_VALUE), stderr);
  }

  @Test
  void hiddenFailuresQuotingTheEngineReplyKeepOnlyTheApplicationText() {
    String reply = "{\"error\":{\"code\":\"io\",\"message\":\"cannot write D:\\\\PrivateVault-31\"}}";

    IOException rejected =
        assertThrows(
            IOException.class,
            () ->
                new GtpConfigurationProbe(scripted(EngineCommandVisibility.HIDDEN, false, reply))
                    .inspect("ignored", Duration.ofSeconds(1)));
    IOException malformed =
        assertThrows(
            IOException.class,
            () ->
                new GtpConfigurationProbe(
                        scripted(EngineCommandVisibility.HIDDEN, true, "PrivateVault-31 {"))
                    .inspect("ignored", Duration.ofSeconds(1)));
    IOException visible =
        assertThrows(
            IOException.class,
            () ->
                new GtpConfigurationProbe(scripted(EngineCommandVisibility.VISIBLE, false, reply))
                    .inspect("ignored", Duration.ofSeconds(1)));

    assertEquals("Engine rejected the configuration: <hidden-command>", rejected.getMessage());
    assertEquals("Invalid GTP configuration schema: <hidden-command>", malformed.getMessage());
    assertNull(malformed.getCause());
    assertTrue(visible.getMessage().contains("PrivateVault-31"), visible.getMessage());
  }

  private String missingEngineCommand() throws IOException {
    Path engines = Files.createDirectories(tempDir.resolve("PrivateVault-31").resolve("engines"));
    return "\""
        + engines.resolve(SHORT_EXECUTABLE)
        + "\" gtp -model \""
        + tempDir.resolve("models").resolve("secret-net-b40.bin.gz")
        + "\" -override-config serverPass=Q9pass";
  }

  /** A child whose one stderr line puts {@link #CUT_VALUE} across the probe's line bound. */
  private static String paddedEchoCommand() {
    String java =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    int padding = ObservationText.RAW_EVENT_MAX_UTF8_BYTES - 40;
    return "\""
        + java
        + "\" -cp \""
        + Path.of("target", "test-classes").toAbsolutePath()
        + "\" "
        + PaddedStderrEngine.class.getName()
        + " "
        + padding
        + " "
        + CUT_VALUE;
  }

  /** A session over a scripted engine that supports the schema command and then replies once. */
  private static GtpConfigurationProbe.SessionFactory scripted(
      EngineCommandVisibility visibility, boolean schemaSucceeds, String schemaReply) {
    return (command, timeout) ->
        new GtpConfigurationProbe.Session() {
          private int requests;

          @Override
          public GtpConfigurationProbe.Response request(String request) {
            return requests++ == 0
                ? new GtpConfigurationProbe.Response(true, "true")
                : new GtpConfigurationProbe.Response(schemaSucceeds, schemaReply);
          }

          @Override
          public EngineCommandVisibility visibility() {
            return visibility;
          }

          @Override
          public void close() {}
        };
  }

  private String awaitApp() throws Exception {
    Method awaitIdle =
        LoggingRuntime.class.getDeclaredMethod("awaitIdle", long.class, TimeUnit.class);
    awaitIdle.setAccessible(true);
    assertTrue((Boolean) awaitIdle.invoke(runtime, 10L, TimeUnit.SECONDS));
    Path file = tempDir.resolve("logs/app.log");
    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  /** Prints {@code args[0]} padding characters followed by {@code args[1]}, then exits. */
  public static final class PaddedStderrEngine {
    private PaddedStderrEngine() {}

    public static void main(String[] args) {
      PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
      err.println("y".repeat(Integer.parseInt(args[0])) + args[1]);
      err.flush();
    }
  }
}
