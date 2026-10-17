package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.Utils;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContributeCertificateRecoveryTest {
  @Test
  void remoteCommandsNeverAcquireClientCertificatePaths() {
    for (String command :
        new String[] {
          "remote/bin/katago contribute",
          "/srv/katago/bin/katago contribute",
          "remote/bin/katago contribute -cacerts /srv/certs/training.pem"
        }) {
      assertEquals(command, recover(command, true, false));
    }
  }

  @Test
  void pureCommandsRemainUserOwned() {
    String command = "katago contribute -cacerts custom.pem";
    assertEquals(command, recover(command, false, true));
  }

  @Test
  void localRecoveryUsesCertificateBesideExecutable() {
    String command = "\"" + Path.of("fixture engine", "katago").toAbsolutePath() + "\" contribute";
    var recovered = Utils.splitCommand(recover(command, false, false));
    assertTrue(recovered.contains("-cacerts"));
    assertEquals(
        Path.of("fixture engine", "cacert.pem").toAbsolutePath().toString(),
        recovered.get(recovered.size() - 1));
  }

  private static String recover(String command, boolean remote, boolean pure) {
    String executable =
        featurecat.lizzie.util.CommandLaunchHelper.prepare(Utils.splitCommand(command))
            .getCommandParts()
            .get(0);
    return ContributeEngine.certificateRecoveryCommand(command, executable, remote, pure);
  }
}
