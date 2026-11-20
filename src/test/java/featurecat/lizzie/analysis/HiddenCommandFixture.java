package featurecat.lizzie.analysis;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** One user-hidden engine command and the failures a launch of it produces. */
public final class HiddenCommandFixture {
  private HiddenCommandFixture() {}

  public static final List<String> ARGV =
      List.of(
          "C:\\HiddenVault7\\engines\\katago.exe",
          "gtp",
          "-model",
          "D:\\models\\secret-net-b40.bin.gz",
          "-config",
          "D:\\HiddenVault7\\host-token-q9.cfg",
          "-override-config",
          "numSearchThreads=8,serverPass=Q9pass");

  public static final String COMMAND = String.join(" ", ARGV);

  /** Text that must never survive anywhere a hidden command is presented or persisted. */
  public static final List<String> CANARIES =
      List.of("HiddenVault7", "secret-net-b40", "host-token-q9", "Q9pass");

  public static final String WORKING_DIRECTORY = "C:\\HiddenVault7";

  /** Engine output that names a runtime library under the hidden engine's directory. */
  public static final String DLL_FAILURE_LINE =
      "Could not find C:\\HiddenVault7\\engines\\cudnn64_9.dll";

  /** The JDK's own failure for starting {@link #ARGV} in {@link #WORKING_DIRECTORY}. */
  public static IOException processCreateFailure() {
    return launchFailure(ARGV, WORKING_DIRECTORY);
  }

  /** Starts a program that cannot exist and returns the exception the JDK raised for it. */
  public static IOException launchFailure(List<String> argv, String directory) {
    ProcessBuilder builder = new ProcessBuilder(argv);
    if (directory != null) builder.directory(new File(directory));
    try {
      Process process = builder.start();
      process.destroyForcibly();
      throw new AssertionError("Fixture program unexpectedly started: " + argv.get(0));
    } catch (IOException expected) {
      return expected;
    }
  }

  public static void assertNoCanary(String text) {
    assertNoFragment(text, CANARIES);
  }

  public static void assertNoFragment(String text, List<String> fragments) {
    for (String fragment : fragments) {
      if (text != null && text.contains(fragment)) {
        throw new AssertionError("Hidden command fragment '" + fragment + "' leaked into: " + text);
      }
    }
  }
}
