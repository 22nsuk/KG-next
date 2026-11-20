package featurecat.lizzie.analysis;

import java.util.List;

/** One user-hidden engine command and the failure text a launch of it produces. */
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

  public static final String PROCESS_CREATE_FAILURE =
      "java.io.IOException: Cannot run program \"C:\\HiddenVault7\\engines\\katago.exe\""
          + " (in directory \"C:\\HiddenVault7\"): CreateProcess error=2, file not found";

  public static final String DLL_FAILURE_LINE =
      "Could not find C:\\HiddenVault7\\engines\\cudnn64_9.dll";

  public static void assertNoCanary(String text) {
    for (String canary : CANARIES) {
      if (text != null && text.contains(canary)) {
        throw new AssertionError("Hidden command fragment '" + canary + "' leaked into: " + text);
      }
    }
  }
}
