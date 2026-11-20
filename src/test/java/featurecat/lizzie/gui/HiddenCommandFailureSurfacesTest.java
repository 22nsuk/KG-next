package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import featurecat.lizzie.analysis.EngineCommandVisibility;
import featurecat.lizzie.analysis.EngineStartupDiagnostic;
import featurecat.lizzie.analysis.EngineStartupDiagnostics;
import featurecat.lizzie.analysis.HiddenCommandFixture;
import featurecat.lizzie.analysis.ReadBoardLoggingSnapshot;
import featurecat.lizzie.analysis.SyncDiagnosticsExportSnapshot;
import featurecat.lizzie.logging.DiagnosticBundleExporter;
import featurecat.lizzie.logging.DiagnosticBundleRequest;
import featurecat.lizzie.logging.EngineObservation;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * One failed start of an engine command, followed from {@code Attempt.fail} to every place the
 * failure is shown or kept: the dialog summary, the Details pane and Copy button, the diagnostics
 * export and the startup-failure WARN record.
 */
class HiddenCommandFailureSurfacesTest {
  private static final String EXPORTED_FAILURE = "snapshots/engine-startup-failure.json";
  private static final String REQUIRED_LIBRARY = "cudnn64_9.dll";

  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
  }

  @Test
  void everySurfaceOfAHiddenCommandFailureKeepsOnlyWhatThePolicyShares() throws Exception {
    LoggingRuntime runtime = startLogging();

    Failure hidden = failOnce(runtime, EngineCommandVisibility.HIDDEN);
    assertEquals(
        List.of(
            REQUIRED_LIBRARY,
            EngineCommandVisibility.HIDDEN_VALUE,
            EngineCommandVisibility.HIDDEN_VALUE),
        hidden.libraries());
    List<String> fragments = new ArrayList<>(HiddenCommandFixture.CANARIES);
    fragments.addAll(HiddenCommandFixture.UNCONFIRMED_LIBRARY_FRAGMENTS);
    for (Map.Entry<String, String> surface : hidden.surfaces().entrySet()) {
      HiddenCommandFixture.assertNoFragment(surface.getValue(), fragments);
      assertTrue(surface.getValue().contains(REQUIRED_LIBRARY), surface.getKey());
      assertTrue(
          surface.getValue().contains(EngineCommandVisibility.HIDDEN_VALUE), surface.getKey());
    }

    // Control: every surface carries the findings, so the same failure names each library when the
    // command is visible.
    Failure visible = failOnce(runtime, EngineCommandVisibility.VISIBLE);
    List<String> named = new ArrayList<>(List.of(REQUIRED_LIBRARY));
    named.addAll(HiddenCommandFixture.UNCONFIRMED_LIBRARIES);
    assertEquals(named, visible.libraries());
    for (Map.Entry<String, String> surface : visible.surfaces().entrySet()) {
      for (String library : named) {
        assertTrue(surface.getValue().contains(library), surface.getKey() + ": " + library);
      }
    }
  }

  /** What one failed start leaves on each surface, keyed by surface. */
  private record Failure(List<String> libraries, Map<String, String> surfaces) {}

  private Failure failOnce(LoggingRuntime runtime, EngineCommandVisibility visibility)
      throws IOException {
    Logger engineLog = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    engineLog.addAppender(events);
    try (EngineStartupDiagnostics diagnostics =
        new EngineStartupDiagnostics(
            EngineStartupDiagnostics.Policy.production(),
            EngineObservation::recordStartupDiagnostic)) {
      List<String> argv = HiddenCommandFixture.ARGV;
      EngineStartupDiagnostics.Attempt attempt =
          diagnostics.begin("eng-" + visibility, "MAIN_BOARD", argv, true, visibility);
      attempt.capture(
          new ProcessBuilder(argv).directory(new File(HiddenCommandFixture.WORKING_DIRECTORY)));
      attempt.output("stderr", HiddenCommandFixture.DLL_FAILURE_LINE);
      for (String line : HiddenCommandFixture.UNCONFIRMED_LIBRARY_LINES)
        attempt.output("stderr", line);
      attempt.fail("process-create", HiddenCommandFixture.processCreateFailure());

      // The dialog shows, copies and exports the attempt's snapshot.
      EngineStartupDiagnostic shown = attempt.snapshot();
      Map<String, String> surfaces = new LinkedHashMap<>();
      surfaces.put("dialog summary", EngineFailedMessage.formatSummaryText(shown));
      surfaces.put("details and copy", shown.shareText());
      surfaces.put("export", exportedFailure(runtime, shown, visibility));
      surfaces.put("WARN record", startupFailedRecord(events, attempt.id()));

      List<String> libraries = new ArrayList<>();
      JSONArray findings = shown.toJson().getJSONArray("findings");
      for (int index = 0; index < findings.length(); index++)
        libraries.add(findings.getJSONObject(index).optString("dll"));
      return new Failure(libraries, surfaces);
    } finally {
      engineLog.detachAppender(events);
    }
  }

  private LoggingRuntime startLogging() {
    LoggingRuntime.resetForTests();
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir.resolve("work"), List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    return runtime;
  }

  private String exportedFailure(
      LoggingRuntime runtime, EngineStartupDiagnostic failure, EngineCommandVisibility visibility)
      throws IOException {
    DiagnosticBundleRequest request =
        new DiagnosticBundleRequest(
            runtime,
            Set.of(),
            false,
            false,
            new JSONObject(),
            new SyncDiagnosticsExportSnapshot(1L, null, List.of(), List.of(), List.of(), null),
            ReadBoardLoggingSnapshot.detached(),
            "next-dev",
            "unknown",
            failure);
    Path zip =
        new DiagnosticBundleExporter(tempDir.resolve("export-" + visibility)).export(request);
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
        if (entry.getName().equals(EXPORTED_FAILURE))
          return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    throw new AssertionError("Export has no " + EXPORTED_FAILURE);
  }

  private static String startupFailedRecord(ListAppender<ILoggingEvent> events, String attemptId) {
    String record = null;
    for (ILoggingEvent event : events.list) {
      String message = event.getFormattedMessage();
      if (message.contains("event=startup-failed") && message.contains(attemptId))
        record = message;
    }
    assertNotNull(record, "No startup-failed WARN record for " + attemptId);
    return record;
  }
}
