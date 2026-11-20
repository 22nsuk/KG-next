package featurecat.lizzie.analysis.gtpconfig;

import static featurecat.lizzie.analysis.HiddenCommandFixture.assertNoCanary;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.EngineCommandVisibility;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The configuration probe decodes a saved {@code encryption||} command on its own. Its launch
 * failure (shown by the engine editor) and its engine-log lines must not carry that command.
 */
class GtpConfigurationProbeHiddenCommandTest {
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
  void hiddenLaunchFailureMessageDropsExecutableAndWorkingDirectory() throws Exception {
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

    assertNoCanary(hidden.getMessage());
    assertTrue(hidden.getMessage().contains("Cannot run program"), hidden.getMessage());
    assertTrue(
        hidden.getMessage().contains(EngineCommandVisibility.HIDDEN_VALUE), hidden.getMessage());
    assertNull(hidden.getCause(), "the original exception would carry the clear-text message");
    assertTrue(visible.getMessage().contains("HiddenVault7"), visible.getMessage());
  }

  @Test
  void visibleLaunchFailureIsRethrownUnchanged() {
    ProcessBuilder builder = new ProcessBuilder(List.of("C:\\HiddenVault7\\katago.exe"));
    IOException failure = new IOException("Cannot run program \"C:\\HiddenVault7\\katago.exe\"");

    assertSame(
        failure,
        new GtpConfigurationProbe.HiddenCommandText(
                EngineCommandVisibility.VISIBLE, "C:\\HiddenVault7\\katago.exe", builder)
            .launchFailure(failure));
  }

  @Test
  void hiddenProbeLogsNoModelConfigOrStderrPathOfTheCommand() throws Exception {
    String command = echoingEngineCommand();

    assertThrows(
        IOException.class,
        () ->
            new GtpConfigurationProbe()
                .inspect("encryption||" + Utils.doEncrypt2(command), Duration.ofSeconds(5)));
    String app = awaitApp();

    assertNoCanary(app);
    assertTrue(app.contains("probe event=failed stage="), app);
    assertTrue(app.contains("probe event=stderr facts="), app);
    assertTrue(app.contains("Loaded model"), app);
    assertTrue(app.contains("model=hidden-command config=hidden-command"), app);
    assertTrue(app.contains("command=<hidden-command>"), app);
  }

  @Test
  void visibleProbeStillLogsItsModelAndStderr() throws Exception {
    assertThrows(
        IOException.class,
        () -> new GtpConfigurationProbe().inspect(echoingEngineCommand(), Duration.ofSeconds(5)));
    String app = awaitApp();

    // The same probe without encryption keeps its facts: the hidden test above is not vacuous.
    assertTrue(app.contains("model=secret-net-b40.bin.gz"), app);
    assertTrue(app.contains("Loaded config " + tempDir.resolve("HiddenVault7")), app);
  }

  private String missingEngineCommand() throws IOException {
    Path engines = Files.createDirectories(tempDir.resolve("HiddenVault7").resolve("engines"));
    return "\""
        + engines.resolve("katago")
        + "\" gtp -model \""
        + tempDir.resolve("models").resolve("secret-net-b40.bin.gz")
        + "\" -override-config serverPass=Q9pass";
  }

  /** A child that echoes its model, config and override to stderr and exits before handshaking. */
  private String echoingEngineCommand() throws IOException {
    Path vault = Files.createDirectories(tempDir.resolve("HiddenVault7"));
    String java =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    return "\""
        + java
        + "\" -cp \""
        + Path.of("target", "test-classes").toAbsolutePath()
        + "\" "
        + EchoArgumentsEngine.class.getName()
        + " -model \""
        + tempDir.resolve("models").resolve("secret-net-b40.bin.gz")
        + "\" -config \""
        + vault.resolve("host-token-q9.cfg")
        + "\" -override-config numSearchThreads=8,serverPass=Q9pass";
  }

  private String awaitApp() throws Exception {
    Method awaitIdle =
        LoggingRuntime.class.getDeclaredMethod("awaitIdle", long.class, TimeUnit.class);
    awaitIdle.setAccessible(true);
    assertTrue((Boolean) awaitIdle.invoke(runtime, 10L, TimeUnit.SECONDS));
    Path file = tempDir.resolve("logs/app.log");
    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  public static final class EchoArgumentsEngine {
    private EchoArgumentsEngine() {}

    public static void main(String[] args) {
      PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
      for (int index = 0; index + 1 < args.length; index++) {
        switch (args[index]) {
          case "-model" -> err.println("Loaded model " + args[index + 1]);
          case "-config" -> err.println("Loaded config " + args[index + 1]);
          case "-override-config" -> err.println("Unknown override " + args[index + 1]);
          default -> {}
        }
      }
      err.flush();
    }
  }
}
