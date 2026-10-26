package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OsPlatformTest {
  @TempDir Path tempDir;

  static Stream<Arguments> platforms() {
    return Stream.of(
        Arguments.of("Windows 11", "en-US", "Linux", true),
        Arguments.of("Windows Server 2025", "en-US", "Mac OS X", true),
        Arguments.of("Microsoft Windows", "en-US", "Linux", true),
        Arguments.of("WINDOWS 11", "tr-TR", "Linux", true),
        Arguments.of("Mac OS X", "en-US", "Windows 11", false),
        Arguments.of("Darwin", "en-US", "Windows 11", false),
        Arguments.of("Linux", "en-US", "Windows 11", false),
        Arguments.of("FreeBSD", "en-US", "Windows 11", false),
        Arguments.of("", "en-US", "Windows 11", false),
        Arguments.of(null, "en-US", "Windows 11", false));
  }

  @ParameterizedTest(name = "os.name={0}, locale={1}, later os.name={2}, windows={3}")
  @MethodSource("platforms")
  @SuppressWarnings("try")
  void detectsWindowsOnceWithoutDependingOnTheDefaultLocale(
      String initialName, String localeTag, String laterName, boolean expected) throws Exception {
    String javaExecutable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    String classPath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path", ""));
    Path output = Files.createTempFile(tempDir, "os-platform-", ".log");
    Process child =
        new ProcessBuilder(
                javaExecutable,
                "-cp",
                classPath,
                Probe.class.getName(),
                localeTag,
                initialName == null ? "clear" : "set",
                initialName == null ? "" : initialName,
                laterName)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try (AutoCloseable cleanup = () -> terminate(child)) {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS), "OS platform probe timed out");
      String result = Files.readString(output, StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
      assertEquals(0, child.exitValue(), result);
      assertEquals(
          List.of("os-platform-initial=" + expected, "os-platform-cached=" + expected),
          result.lines().filter(line -> line.startsWith("os-platform-")).toList(),
          result);
    }
  }

  private static void terminate(Process child) throws InterruptedException {
    if (!child.isAlive()) return;
    child.destroyForcibly();
    InterruptedException interrupted = null;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (child.isAlive() && System.nanoTime() < deadline) {
      try {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) child.waitFor(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException failure) {
        if (interrupted == null) interrupted = failure;
        else interrupted.addSuppressed(failure);
      }
    }
    if (interrupted != null) Thread.currentThread().interrupt();
    assertFalse(child.isAlive(), "OS platform probe survived forced termination");
    if (interrupted != null) throw interrupted;
  }

  public static final class Probe {
    public static void main(String[] args) {
      // Fake platform properties stay in this process and cannot poison JDK/JNA in other tests.
      Locale.setDefault(Locale.forLanguageTag(args[0]));
      if ("clear".equals(args[1])) System.clearProperty("os.name");
      else System.setProperty("os.name", args[2]);
      boolean initial = OsPlatform.isWindows();
      System.setProperty("os.name", args[3]);
      System.out.println(
          "os-platform-initial=" + initial + "\nos-platform-cached=" + OsPlatform.isWindows());
    }
  }
}
