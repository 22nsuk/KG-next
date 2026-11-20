package featurecat.lizzie.analysis;

import static featurecat.lizzie.analysis.HiddenCommandFixture.ARGV;
import static featurecat.lizzie.analysis.HiddenCommandFixture.COMMAND;
import static featurecat.lizzie.analysis.HiddenCommandFixture.DLL_FAILURE_LINE;
import static featurecat.lizzie.analysis.HiddenCommandFixture.PROCESS_CREATE_FAILURE;
import static featurecat.lizzie.analysis.HiddenCommandFixture.assertNoCanary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import org.junit.jupiter.api.Test;

class EngineCommandVisibilityTest {
  @Test
  void savedEncryptedCommandIsDecodedForLaunchButReachesPresentationAsHidden() throws Exception {
    Leelaz hidden = new Leelaz("encryption||" + Utils.doEncrypt2(COMMAND));
    assertEquals(EngineCommandVisibility.HIDDEN, hidden.engineCommandVisibility());
    assertEquals(COMMAND, hidden.engineCommand);
    assertEquals(
        Lizzie.resourceBundle.getString("Leelaz.encryption"), hidden.getEngineCommand());

    Leelaz plain = new Leelaz(COMMAND);
    assertEquals(EngineCommandVisibility.VISIBLE, plain.engineCommandVisibility());
    assertEquals(COMMAND, plain.getEngineCommand());

    hidden.setEngineCommand(COMMAND);
    assertEquals(EngineCommandVisibility.VISIBLE, hidden.engineCommandVisibility());
  }

  @Test
  void hiddenScrubRemovesCommandFragmentsButKeepsFailureEvidence() {
    String failure =
        PROCESS_CREATE_FAILURE
            + "\n"
            + DLL_FAILURE_LINE
            + "\nOpening D:\\models\\secret-net-b40.bin.gz for -model; serverPass=Q9pass rejected";

    String scrubbed = EngineCommandVisibility.HIDDEN.scrub(failure, ARGV);

    assertNoCanary(scrubbed);
    assertTrue(scrubbed.contains("Cannot run program"), scrubbed);
    assertTrue(scrubbed.contains("CreateProcess error=2"), scrubbed);
    assertTrue(scrubbed.contains("cudnn64_9.dll"), scrubbed);
    assertTrue(scrubbed.contains("for -model;"), scrubbed);
    assertTrue(scrubbed.contains(EngineCommandVisibility.HIDDEN_VALUE), scrubbed);
    assertSame(failure, EngineCommandVisibility.VISIBLE.scrub(failure, ARGV));
  }

  @Test
  void anInsertedMarkerIsNeverRewrittenByAShorterFragment() {
    String scrubbed =
        EngineCommandVisibility.HIDDEN.scrub(
            "run C:\\tools\\hidden-command\\engine.exe now",
            java.util.List.of("C:\\tools\\hidden-command\\engine.exe", "command", "hidden"));
    assertEquals("run " + EngineCommandVisibility.HIDDEN_VALUE + " now", scrubbed);
  }
}
