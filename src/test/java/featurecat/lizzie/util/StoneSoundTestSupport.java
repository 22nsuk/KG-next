package featurecat.lizzie.util;

import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.function.Consumer;

/** Test-only access to the shared stone-sound observer without a public production hook. */
public final class StoneSoundTestSupport {
  private StoneSoundTestSupport() {}

  public static void setStoneSound(Consumer<BoardHistoryNode> sound) {
    Utils.setStoneSoundForTest(sound);
  }
}
