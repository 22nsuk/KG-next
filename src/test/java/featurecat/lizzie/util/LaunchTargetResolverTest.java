package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.LaunchTargetResolver.Target;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LaunchTargetResolverTest {
  private static final Target LOCAL = Target.LOCAL;
  private static final Target REMOTE = Target.REMOTE;
  private static final Target UNKNOWN = Target.UNDETERMINED;

  /** Configured command strings, split the way the engine launcher splits them. */
  @Test
  void configuredCommandsResolveAtTheProgramPosition() {
    assertTargets(
        Utils::splitCommand,
        // Programs and split Windows paths that reach a remote shell client.
        "ssh host.invalid OPAQUE", REMOTE,
        "C:\\Program Files\\PuTTY\\PLINK.EXE host.invalid OPAQUE", REMOTE,
        "\"C:\\Program Files\\OpenSSH\\SSH.EXE\" host.invalid", REMOTE,
        "C:\\My -Tools\\ssh.exe host.invalid", REMOTE,
        "C:\\OpenSSH\\ssh.exe. host.invalid", REMOTE,
        "C:ssh.exe host.invalid", REMOTE,
        "autossh -M 0 host.invalid", REMOTE,
        "sshpass -p value ssh host.invalid", REMOTE,
        "remote-compute://custom-websocket", REMOTE,
        // Launchers whose grammar names the program.
        "wsl.exe --exec ssh host.invalid", REMOTE,
        "wsl.exe -d Ubuntu -u me --cd /home/me -e /usr/bin/ssh host.invalid", REMOTE,
        "wsl.exe ssh host.invalid", REMOTE,
        "wsl.exe -- ssh host.invalid", REMOTE,
        "wsl.exe --shell-type login ssh host.invalid", REMOTE,
        "wsl.exe --exec sh -c 'ssh host.invalid'", REMOTE,
        "bash -lc 'ssh host.invalid'", REMOTE,
        "sh -c 'FOO=1 exec -a name ssh host.invalid'", REMOTE,
        "sh -c 'command -p ssh host.invalid'", REMOTE,
        "env -i -u HOME FOO=1 ssh host.invalid", REMOTE,
        "nohup ssh host.invalid", REMOTE,
        "cmd /c ssh host.invalid", REMOTE,
        "cmd.exe /d /s /c plink host.invalid", REMOTE,
        "C:\\Program Files\\Git\\bin\\bash.exe -c 'ssh host.invalid'", REMOTE,
        // Ordinary engines: data positions are never read as programs.
        "katago gtp -model models/ssh", LOCAL,
        "katago gtp -config /home/ssh/gtp.cfg", LOCAL,
        "katago gtp -override-config homeDataDir=C:\\Users\\ssh", LOCAL,
        "katago gtp C:\\Users\\ssh", LOCAL,
        "leelaz.exe -g -w ssh", LOCAL,
        "\"C:\\Program Files\\KataGo\\katago.exe\" gtp", LOCAL,
        "C:\\Program Files\\KataGo\\katago.exe gtp", LOCAL,
        "wsl.exe --cd /home/ssh --exec katago gtp", LOCAL,
        "wsl.exe -d ssh -u ssh --cd /home/ssh -e ./katago gtp", LOCAL,
        "wsl.exe --cd /home/ssh katago gtp -model /home/ssh/model.bin.gz", LOCAL,
        "wsl.exe ~/katago/katago gtp -model ~/models/model.bin.gz", LOCAL,
        "wsl.exe --shell-type none katago gtp", LOCAL,
        "wsl.exe --exec sh -c 'katago gtp -model /home/ssh/model.bin.gz'", LOCAL,
        "bash -lc 'OMP_NUM_THREADS=4 exec ./katago gtp'", LOCAL,
        "env CUDA_VISIBLE_DEVICES=0 katago gtp", LOCAL,
        "nohup katago gtp", LOCAL,
        "cmd /c katago gtp", LOCAL,
        "cmd /c run_katago.bat", LOCAL,
        "run_katago.bat -model C:\\Go\\model.bin.gz", LOCAL,
        "java -Xmx2g -jar engine.jar gtp", LOCAL,
        "python -u gtp_bridge.py --host localhost", LOCAL,
        "py -3.11 -X utf8 bot.py", LOCAL,
        // Anything outside the modeled grammars fails closed.
        "sh -c 'katago; ssh host.invalid'", UNKNOWN,
        "sh -c 'katago && ssh host.invalid'", UNKNOWN,
        "sh -c '$(printf ssh) host.invalid'", UNKNOWN,
        "sh -c 's\\sh host.invalid'", UNKNOWN,
        "sh -c 'ss* host.invalid'", UNKNOWN,
        "zsh -c '=ssh host.invalid'", UNKNOWN,
        "sh -c 'eval ssh host.invalid'", UNKNOWN,
        "bash -o pipefail -c 'katago gtp'", UNKNOWN,
        "sh -c -e 'ssh host.invalid'", UNKNOWN,
        "bash", UNKNOWN,
        "wsl.exe", UNKNOWN,
        "wsl.exe sh -c 'ssh host.invalid'", UNKNOWN,
        "wsl.exe --install", UNKNOWN,
        "env -S 'ssh host.invalid'", UNKNOWN,
        "cmd /c \"ssh host.invalid\"", UNKNOWN,
        "cmd /c start ssh host.invalid", UNKNOWN,
        "cmd /c katago^&ssh", UNKNOWN,
        "cmd", UNKNOWN,
        "run_katago.bat a&ssh host.invalid", UNKNOWN,
        "powershell -Command ssh host.invalid", UNKNOWN,
        "sudo ssh host.invalid", UNKNOWN,
        "wsl.exe --exec timeout 60 ssh host.invalid", UNKNOWN,
        "winpty ssh host.invalid", UNKNOWN,
        "ubuntu2204.exe run ssh host.invalid", UNKNOWN,
        "docker run image ssh host.invalid", UNKNOWN,
        "python -c 'import os'", UNKNOWN,
        "python", UNKNOWN,
        "node -e 'require(1)'", UNKNOWN,
        "C:\\OpenSSH\\ssh.exe:stream host.invalid", UNKNOWN);
  }

  /** Prepared vectors keep whitespace that a configured string cannot express. */
  @Test
  void whitespaceInsideShellTextNeverJoinsOrSplitsWordsDifferently() {
    assertTargets(
        Function.identity(),
        List.of("sh", "-c", "ssh\thost.invalid OPAQUE"), UNKNOWN,
        List.of("wsl.exe", "--exec", "sh", "-c", "ssh\thost.invalid OPAQUE"), UNKNOWN,
        List.of("wsl.exe", "--", "sh", "-c", "ssh\thost.invalid"), UNKNOWN,
        List.of("bash", "-lc", "katago gtp\nssh host.invalid"), UNKNOWN,
        List.of("sh", "-c", "katago\u00a0gtp"), UNKNOWN,
        List.of("wsl.exe", "ssh\thost.invalid"), UNKNOWN,
        List.of("ssh\thost.invalid"), UNKNOWN,
        List.of("sh", "-c", "  katago   gtp  "), LOCAL,
        List.of("sh", "-c", "ssh  host.invalid"), REMOTE);
  }

  @Test
  void emptyAndNullVectorsAreUndetermined() {
    assertEquals(UNKNOWN, LaunchTargetResolver.resolveWindowsLaunch(null));
    assertEquals(UNKNOWN, LaunchTargetResolver.resolveWindowsLaunch(List.of()));
    assertEquals(UNKNOWN, LaunchTargetResolver.resolveWindowsLaunch(Arrays.asList((String) null)));
    assertEquals(
        UNKNOWN, LaunchTargetResolver.resolveWindowsLaunch(Arrays.asList("env", null, "katago")));
  }

  @Test
  void cmdInternalCommandsAreRecognizedAtCmdWordBoundaries() {
    for (String word : List.of("echo", "ECHO.", "echo.exe", "cd..", "start", "call", "rem")) {
      assertTrue(LaunchTargetResolver.isCmdInternalCommand(word), word);
    }
    for (String word :
        List.of("katago", "set-engine", "dir2", "pathfinder.exe", "C:\\Tools\\echo.exe")) {
      assertFalse(LaunchTargetResolver.isCmdInternalCommand(word), word);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> void assertTargets(Function<T, List<String>> argv, Object... cases) {
    assertAll(
        Stream.iterate(0, i -> i < cases.length, i -> i + 2)
            .map(
                i -> () -> {
                  T command = (T) cases[i];
                  assertEquals(
                      cases[i + 1],
                      LaunchTargetResolver.resolveWindowsLaunch(argv.apply(command)),
                      String.valueOf(command));
                }));
  }
}
