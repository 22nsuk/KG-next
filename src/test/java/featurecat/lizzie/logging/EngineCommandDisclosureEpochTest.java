package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineCommandDisclosureEpochTest {
  private static final Instant EPOCH = Instant.parse("2026-11-20T12:00:00Z");
  private static final Instant BEFORE = EPOCH.minusSeconds(60);

  @TempDir Path tempDir;

  @Test
  void theFirstContractStartKeepsItsEpoch() throws Exception {
    EngineCommandDisclosureEpoch.ensure(tempDir);
    Instant first = EngineCommandDisclosureEpoch.read(tempDir);
    Thread.sleep(5);
    EngineCommandDisclosureEpoch.ensure(tempDir);

    assertEquals(first, EngineCommandDisclosureEpoch.read(tempDir));
    assertTrue(Files.isRegularFile(tempDir.resolve(EngineCommandDisclosureEpoch.FILE_NAME)));
    assertNull(EngineCommandDisclosureEpoch.read(tempDir.resolve("missing")));
  }

  @Test
  void onlyEngineAndCapturedStderrRecordsBeforeTheEpochAreOmitted() {
    EngineCommandDisclosureEpoch.Filter filter =
        new EngineCommandDisclosureEpoch.Filter(EPOCH, "running");

    assertTrue(filter.omits(header("WARN ", "lizzie.engine", "old"), BEFORE));
    assertTrue(filter.omits(header("DEBUG", "lizzie.engine.trace", "old"), BEFORE));
    assertTrue(filter.omits(header("ERROR", "lizzie.app.stderr", "old"), BEFORE));
    assertFalse(filter.omits(header("INFO ", "lizzie.app", "old"), BEFORE));
    assertFalse(filter.omits(header("INFO ", "lizzie.engineering", "old"), BEFORE));
    assertFalse(filter.omits(header("INFO ", "lizzie.gtp", "old"), BEFORE));
    assertFalse(filter.omits(header("WARN ", "lizzie.engine", "running"), BEFORE));
    assertFalse(filter.omits(header("WARN ", "lizzie.engine", "old"), EPOCH));
    assertEquals(3, filter.omitted());
  }

  @Test
  void withoutAnEpochOnlyTheRunningSessionKeepsItsEngineRecords() {
    EngineCommandDisclosureEpoch.Filter filter =
        new EngineCommandDisclosureEpoch.Filter(null, "running");

    assertTrue(filter.omits(header("WARN ", "lizzie.engine", "old"), Instant.now()));
    assertFalse(filter.omits(header("WARN ", "lizzie.engine", "running"), Instant.now()));
  }

  private static String header(String level, String logger, String session) {
    return "2026-11-20 20:59:00.000 " + level + " [" + logger + "] session=" + session + " event";
  }
}
