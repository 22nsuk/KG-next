package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class KGNextUpdateIdentityTest {
  @Test void forkReleasesNeverResolveToTheUpstreamInstallerFeed() {
    assertEquals("https://github.com/22nsuk/KG-next/releases", WindowsUpdateController.forkReleasesUri().toString());
  }
}
