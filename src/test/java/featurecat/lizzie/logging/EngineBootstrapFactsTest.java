package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.analysis.EngineCommandVisibility;
import featurecat.lizzie.analysis.gtpconfig.GtpConfigurationProbe;
import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.OsPlatform;
import featurecat.lizzie.util.Utils;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EngineBootstrapFactsTest {
  @TempDir Path tempDir;

  @Test
  void hiddenBootstrapUsesSameMarkerForPathAndAbsoluteCommands() throws Exception {
    Path bin = Files.createDirectory(tempDir.resolve("engine bin"));
    String name = "katago-path-probe";
    Path engine = bin.resolve(name + (OsPlatform.isWindows() ? ".exe" : ""));
    String searchPath;
    if (OsPlatform.isWindows()) {
      Files.copy(Path.of(System.getenv("SystemRoot"), "System32", "where.exe"), engine);
      Path scripts = Files.createDirectory(tempDir.resolve("scripts"));
      Files.writeString(scripts.resolve(name + ".cmd"), "@exit /b 23");
      Files.writeString(scripts.resolve("lizzieyzy-next-engine-backend.txt"), "opencl");
      Files.copy(engine, scripts.resolve("cmd.exe")); // PATH shadow of the system command interpreter
      searchPath = scripts + File.pathSeparator + bin;
    } else {
      Files.writeString(engine, "#!/bin/sh\nexit 0\n");
      assertTrue(engine.toFile().setExecutable(true));
      Path shadow = Files.createDirectory(tempDir.resolve("not executable"));
      Files.createFile(shadow.resolve(name));
      Files.writeString(shadow.resolve("lizzieyzy-next-engine-backend.txt"), "openvino\n");
      searchPath = shadow + File.pathSeparator + bin;
    }
    LoggingChildProcess.Result result =
        LoggingChildProcess.runWithTimeout(
            tempDir,
            30_000,
            tempDir,
            env -> setPath(env, searchPath),
            PathMarkerProbe.class,
            engine.getFileName().toString(),
            engine.toString());
    assertEquals(0, result.exitCode(), result.output());
    List<String> lines =
        LoggingChildProcess.readLog(tempDir, "app.log").lines()
            .filter(line -> line.contains("engine event=bootstrap "))
            .toList();
    List<String> expected =
        List.of(
            "cpu", "cpu", "rocm-gfx110x", "rocm-gfx110x",
            "hidden-command", "hidden-command", "unknown");
    assertEquals(expected.size(), lines.size(), lines::toString);
    for (int i = 0; i < expected.size(); i++) {
      String line = lines.get(i);
      assertTrue(line.contains(" backend=" + expected.get(i) + " onnxProvider=unknown "), line);
      assertTrue(line.contains("model=hidden-command config=hidden-command"), line);
    }
  }

  private static void setPath(Map<String, String> env, String path) {
    if (OsPlatform.isWindows()) env.keySet().removeIf("PATH"::equalsIgnoreCase);
    env.put("PATH", path);
  }

  @ParameterizedTest
  @EnabledOnOs(OS.WINDOWS)
  @CsvSource({
    ",  bin,   17, cpu",
    "0, bin,    2, opencl",
    "1, .;bin, 17, cpu",
    "1, bin;.,  2, opencl",
    "1, ;bin,  17, cpu",
    "'', bin,   2, opencl",
    "1, missing;, absent, unknown"
  })
  void windowsBareNameHonorsCurrentDirectoryPolicy(
      String policy, String path, String expectedExit, String expectedBackend) throws Exception {
    Path system = Path.of(System.getenv("SystemRoot"), "System32");
    Path bin = Files.createDirectory(tempDir.resolve("bin"));
    Files.copy(system.resolve("cmd.exe"), tempDir.resolve("katago-probe.exe"));
    Files.copy(system.resolve("where.exe"), bin.resolve("katago-probe.exe"));
    Files.writeString(tempDir.resolve("lizzieyzy-next-engine-backend.txt"), "cpu");
    Files.writeString(bin.resolve("lizzieyzy-next-engine-backend.txt"), "opencl");
    LoggingChildProcess.Result result =
        LoggingChildProcess.runWithTimeout(
            tempDir, 30_000, tempDir,
            env -> {
              setPath(env, path);
              String key = "NoDefaultCurrentDirectoryInExePath";
              env.keySet().removeIf(key::equalsIgnoreCase);
              if (policy != null) env.put(key, policy);
            },
            WindowsSearchProbe.class, expectedExit, expectedBackend);
    assertEquals(0, result.exitCode(), result.output());
  }

  public static final class WindowsSearchProbe {
    public static void main(String[] args) throws Exception {
      PathMarkerProbe.assertNativeBackend(
          List.of("katago-probe", "/d", "/c", "exit", "17"),
          "absent".equals(args[1]) ? null : Integer.valueOf(args[1]), args[2]);
      PathMarkerProbe.assertNativeBackend(
          List.of("missing\\..\\katago-probe", "/d", "/c", "exit", "17"), 17, "cpu");
    }
  }

  @ParameterizedTest
  @EnabledOnOs({OS.LINUX, OS.MAC})
  @CsvSource({
    "bin:,         katago-path-probe,                   opencl,       unknown",
    ":bin,         katago-path-probe,                   cpu,          unknown",
    "missing:,     katago-path-probe,                   cpu,          cpu",
    "'',           katago-path-probe,                   cpu,          cpu",
    "bin:bin:alias, katago-path-probe,                  opencl,       opencl",
    "link/../bin,  katago-path-probe,                   rocm-gfx110x, rocm-gfx110x",
    "link/../bin:, katago-path-probe,                   rocm-gfx110x, unknown",
    "bin,          child/link/../bin/katago-path-probe, rocm-gfx110x, rocm-gfx110x",
    "stale:bin,    katago-path-probe,                   opencl,       unknown",
    "denied:bin,   katago-path-probe,                   opencl,       unknown",
    "working:bin,  katago-path-probe,                   cpu,          unknown"
  })
  void unixBootstrapReportsOnlyIdentifiableInstallations(
      String path, String program, String expectedEngine, String expectedBackend) throws Exception {
    Path child = Files.createDirectory(tempDir.resolve("child"));
    Files.createFile(child.resolve("gtp.cfg"));
    writeProbeEngine(child, "cpu", "/bin/sh");
    writeProbeEngine(Files.createDirectory(child.resolve("bin")), "opencl", "/bin/sh");
    Files.createSymbolicLink(child.resolve("alias"), Path.of("bin"));
    Path nested = Files.createDirectory(child.resolve("nested"));
    Files.createSymbolicLink(child.resolve("link"), Files.createDirectory(nested.resolve("lib")));
    writeProbeEngine(Files.createDirectory(nested.resolve("bin")), "rocm-gfx110x", "/bin/sh");

    writeProbeEngine(Files.createDirectory(child.resolve("stale")), "cpu", "./missing-sh -e");
    Path deniedInterpreter = Files.createFile(child.resolve("denied-sh"));
    assertFalse(Files.isExecutable(deniedInterpreter));
    writeProbeEngine(Files.createDirectory(child.resolve("denied")), "cpu", "./denied-sh");
    Files.createSymbolicLink(child.resolve("local-sh"), Path.of("/bin/sh"));
    writeProbeEngine(Files.createDirectory(child.resolve("working")), "cpu", "./local-sh -e");

    String executable = program.contains("/") ? tempDir.resolve(program).toString() : program;
    LoggingChildProcess.Result result =
        LoggingChildProcess.runWithTimeout(
            tempDir, 30_000, tempDir, env -> env.put("PATH", path),
            RelativePathProbe.class, executable);
    assertEquals(0, result.exitCode(), result.output());
    assertEquals(
        List.of(expectedEngine, expectedEngine, expectedEngine),
        Files.readAllLines(child.resolve("gtp.cfg.started")));
    List<String> lines =
        LoggingChildProcess.readLog(tempDir, "app.log").lines()
            .filter(entry -> entry.contains("engine event=bootstrap "))
            .toList();
    assertEquals(3, lines.size(), lines::toString);
    for (String line : lines) assertTrue(line.contains(" backend=" + expectedBackend + " "), line);
    assertTrue(lines.get(2).contains("onnxProvider=openvino"), lines.get(2));
    assertTrue(lines.get(1).contains("config=hidden-command"), lines.get(1));
  }

  private static void writeProbeEngine(Path directory, String backend, String interpreter)
      throws Exception {
    Path engine = directory.resolve("katago-path-probe");
    Files.writeString(
        engine,
        "#!" + interpreter + "\nprintf '%s\\n' " + backend + " >> \"$3.started\"\n"
            + "read -r id command\nprintf '=%s false\\n\\n' \"$id\"\n");
    assertTrue(engine.toFile().setExecutable(true));
    Files.writeString(directory.resolve("lizzieyzy-next-engine-backend.txt"), backend);
  }

  public static final class RelativePathProbe {
    public static void main(String[] args) throws Exception {
      Path root = Path.of(args[0]);
      LoggingRuntime runtime =
          LoggingRuntime.initialize(new WorkDirectoryResolution(root, List.of()));
      try {
        String command = "\"" + args[1] + "\" gtp -config " + quote(root.resolve("child/gtp.cfg"));
        GtpConfigurationProbe probe = new GtpConfigurationProbe();
        probe.inspect(command);
        probe.inspect("encryption||" + Utils.doEncrypt2(command));
        probe.inspect(command + " -override-config onnxProvider=openvino");
      } finally {
        runtime.shutdown();
      }
    }
  }

  public static final class PathMarkerProbe {
    public static void main(String[] args) throws Exception {
      LoggingRuntime runtime =
          LoggingRuntime.initialize(new WorkDirectoryResolution(Path.of(args[0]), List.of()));
      try {
        Path engine = Path.of(args[2]);
        for (String marker : List.of("cpu", "rocm-gfx110x", "rocm-gfx110x-private")) {
          Files.writeString(
              engine.resolveSibling("lizzieyzy-next-engine-backend.txt"), marker + "\n");
          for (String executable : List.of(quote(engine), args[1])) {
            recordBootstrap(executable);
          }
        }
        recordBootstrap("./" + args[1]);
        if (OsPlatform.isWindows()) {
          String bareName = args[1].substring(0, args[1].length() - ".exe".length());
          Files.writeString(engine.resolveSibling("lizzieyzy-next-engine-backend.txt"), "rocm-gfx110x");
          assertNativeBackend(List.of(bareName, args[1]), 0, "rocm-gfx110x");
          Files.writeString(Path.of("lizzieyzy-next-engine-backend.txt"), "cpu");
          for (String token : List.of("missing\\..\\" + args[1], args[1] + "\\.")) {
            assertNativeBackend(List.of(token, args[1]), 0, "rocm-gfx110x");
          }
          // A leading ".\" pins a missing executable to the cwd, even with a copy on PATH.
          assertNativeBackend(List.of(".\\" + args[1], args[1]), null, "cpu");
          Path nested = Files.createDirectory(engine.getParent().resolve("nested"));
          Files.copy(engine, nested.resolve(args[1]));
          Files.writeString(nested.resolve("lizzieyzy-next-engine-backend.txt"), "opencl");
          assertNativeBackend(List.of("nested\\" + bareName, args[1]), 0, "opencl");
          assertNativeBackend(List.of("cmd", "/d", "/c", "exit", "17"), 17, "unknown");
          checkQualifiedWindowsCompanion(Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe"));
        } else {
          // Both the cwd and PATH have a marker. Directory operands cannot launch either engine.
          Files.writeString(Path.of(args[0], "lizzieyzy-next-engine-backend.txt"), "cpu");
          for (String token : List.of(args[1] + "/", args[2] + "/", ".", "..", args[2] + "/.")) {
            assertThrows(IOException.class, () -> new ProcessBuilder(token).start(), token);
            assertEquals(
                "unknown",
                EngineBootstrapFacts.fromCommand("\"" + token + "\"", "MAIN_BOARD").backend(),
                token);
          }
        }
      } finally {
        runtime.shutdown();
      }
    }

    private static void checkQualifiedWindowsCompanion(Path commandInterpreter) throws Exception {
      Path installation = Path.of("bundle.v1 with spaces", "windows-x64-nvidia-tensorrt");
      Files.createDirectories(installation);
      Path stem = installation.resolve("katago-human-sl-cuda");
      Path exe = Files.copy(commandInterpreter, installation.resolve("katago-human-sl-cuda.exe"));
      for (String operand :
          List.of(stem.toAbsolutePath().toString(), stem.toString(), exe.toString(), exe + ".")) {
        assertNativeBackend(List.of(operand, "/d", "/c", "exit", "37"), 37, "nvidia50-cuda");
      }

      // A qualified name can instead select an exact extensionless file, even beside the .exe.
      Files.copy(Path.of(System.getenv("SystemRoot"), "System32", "where.exe"), stem);
      assertNativeBackend(
          List.of(stem.toString(), "/q", "absent-pr213-probe-file"), 1, "nvidia-tensorrt");
    }

    private static void assertNativeBackend(
        List<String> arguments, Integer expectedExit, String expectedBackend) throws Exception {
      CommandLaunchHelper.LaunchSpec launch = CommandLaunchHelper.prepare(arguments);
      ProcessBuilder builder = new ProcessBuilder(launch.getCommandParts());
      CommandLaunchHelper.configureProcessBuilder(builder, launch);
      if (expectedExit == null) {
        assertThrows(IOException.class, builder::start);
      } else {
        Process process = builder.start();
        try {
          assertTrue(process.waitFor(5, TimeUnit.SECONDS));
          assertEquals(expectedExit.intValue(), process.exitValue(), "unexpected native executable");
        } finally {
          if (process.isAlive()) process.destroyForcibly();
        }
      }
      String command =
          "\"" + arguments.get(0) + "\" " + String.join(" ", arguments.subList(1, arguments.size()));
      for (EngineCommandVisibility visibility : EngineCommandVisibility.values()) {
        assertEquals(
            expectedBackend,
            EngineBootstrapFacts.fromLaunch(command, "MAIN_BOARD", visibility, true, launch).backend(),
            command);
      }
    }

    private static void recordBootstrap(String executable) {
      EngineObservation.restartInstance(
          new Object(),
          "MAIN_BOARD",
          EngineBootstrapFacts.fromCommand(
              executable + " gtp -model private.bin.gz -config private.cfg",
              "MAIN_BOARD",
              EngineCommandVisibility.HIDDEN));
    }
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void unsetPathIdentifiesOnlyAnUnambiguousSystemInstallation() throws Exception {
    LoggingChildProcess.Result result =
        LoggingChildProcess.runWithTimeout(
            tempDir, 30_000, tempDir, env -> env.remove("PATH"), UnsetPathProbe.class);
    assertEquals(0, result.exitCode(), result.output());
  }

  public static final class UnsetPathProbe {
    public static void main(String[] args) throws Exception {
      assertNull(System.getenv("PATH"));
      // "true" is available on the system default path on Linux and macOS.
      Process system = new ProcessBuilder("true").start();
      assertTrue(system.waitFor(5, TimeUnit.SECONDS));
      assertEquals(0, system.exitValue());
      String command = "true gtp -override-config onnxProvider=openvino";
      for (EngineCommandVisibility visibility : EngineCommandVisibility.values()) {
        assertEquals(
            "onnx", EngineBootstrapFacts.fromCommand(command, "OTHER", visibility).backend());
      }

      Path local = Files.writeString(Path.of(args[0], "true"), "#!/bin/sh\nexit 23\n");
      assertTrue(local.toFile().setExecutable(true));
      Files.writeString(local.resolveSibling("lizzieyzy-next-engine-backend.txt"), "cpu");
      for (EngineCommandVisibility visibility : EngineCommandVisibility.values()) {
        EngineBootstrapFacts ambiguous =
            EngineBootstrapFacts.fromCommand(command, "OTHER", visibility);
        assertEquals("unknown", ambiguous.backend());
        assertEquals("openvino", ambiguous.onnxProvider());
      }
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void invalidExecutablePathPreservesIndependentCommandFacts() {
    String command = "C:\\eng?nes\\katago.exe gtp -model net.bin.gz -config gtp.cfg";
    EngineBootstrapFacts visible = EngineBootstrapFacts.fromCommand(command, "MAIN_BOARD");
    assertEquals("unknown", visible.backend());
    assertEquals("katago", visible.engineType());
    assertEquals("net.bin.gz", visible.model());
    assertEquals("gtp.cfg", visible.config());

    EngineBootstrapFacts hidden =
        EngineBootstrapFacts.fromCommand(command, "MAIN_BOARD", EngineCommandVisibility.HIDDEN);
    assertEquals("katago", hidden.engineType());
    assertEquals("hidden-command", hidden.model());
    assertEquals("hidden-command", hidden.config());
  }

  @Test
  void externalSshLauncherMarkerDoesNotIdentifyTheRemoteEngine() throws Exception {
    Path ssh = Files.createFile(tempDir.resolve("ssh"));
    Files.writeString(ssh.resolveSibling("lizzieyzy-next-engine-backend.txt"), "cpu");
    EngineBootstrapFacts facts =
        EngineBootstrapFacts.fromCommand(
            quote(ssh) + " host katago gtp -override-config onnxProvider=openvino",
            "MAIN_BOARD",
            EngineCommandVisibility.HIDDEN);
    assertEquals("onnx", facts.backend());
    assertEquals("openvino", facts.onnxProvider());
  }

  @Test
  void explicitJavaExecutableUsesItsOwnInstallationMarker() throws Exception {
    Path java = Files.createFile(tempDir.resolve("java.exe"));
    Files.writeString(java.resolveSibling("lizzieyzy-next-engine-backend.txt"), "cpu");
    for (EngineCommandVisibility visibility : EngineCommandVisibility.values()) {
      EngineBootstrapFacts facts =
          EngineBootstrapFacts.fromCommand(quote(java) + " -jar engine.jar", "OTHER", visibility);
      assertEquals("cpu", facts.backend());
    }
  }

  @Test
  void userConfiguredCommandSanitizesPathsAndKeepsUnknownBackend() {
    String command =
        "\"C:\\\\Users\\\\Player\\\\secret\\\\katago.exe\" gtp "
            + "-model \"C:\\\\Users\\\\Player\\\\secret\\\\weights\\\\b18c384nbt.bin.gz\" "
            + "-config \"C:\\\\Users\\\\Player\\\\secret\\\\configs\\\\gtp.cfg\"";

    EngineBootstrapFacts facts = EngineBootstrapFacts.fromCommand(command, "MAIN_BOARD");

    assertEquals("katago", facts.engineType());
    assertEquals("unknown", facts.version());
    assertEquals("MAIN_BOARD", facts.purpose());
    assertEquals(EngineBootstrapFacts.SOURCE_USER_CONFIGURED, facts.source());
    assertEquals("unknown", facts.backend());
    assertEquals("unknown", facts.onnxProvider());
    assertEquals("b18c384nbt.bin.gz", facts.model());
    assertEquals("gtp.cfg", facts.config());
    String line = facts.formatLogLine("");
    assertTrue(line.startsWith("engine event=bootstrap"), line);
    assertFalse(line.contains("Player"), line);
    assertFalse(line.contains("Users"), line);
    assertFalse(line.contains("secret"), line);
    assertFalse(line.contains("C:"), line);
    assertFalse(line.contains("\\\\"), line);
  }

  @Test
  void bundledCommandRecordsSourceAndNvidiaBackendFromPath() throws Exception {
    Path engine =
        touch(
            tempDir
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-nvidia")
                .resolve("katago.exe"));
    Path model = touch(tempDir.resolve("weights").resolve("kata1.bin.gz"));
    Path config =
        touch(tempDir.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg"));
    String command = quote(engine) + " gtp -model " + quote(model) + " -config " + quote(config);

    EngineBootstrapFacts facts = EngineBootstrapFacts.fromCommand(command, "MAIN_BOARD");

    assertEquals("katago", facts.engineType());
    assertEquals(EngineBootstrapFacts.SOURCE_BUNDLED, facts.source());
    assertEquals("nvidia", facts.backend());
    assertTrue(Config.isBundledKataGoCommand(command));
    assertEquals("unknown", facts.onnxProvider());
    assertEquals("kata1.bin.gz", facts.model());
    assertEquals("gtp.cfg", facts.config());
    assertFalse(facts.formatLogLine("").contains(tempDir.toString()), facts.formatLogLine(""));
  }

  @Test
  void onnxProviderFromOverrideConfigSetsBackendWithoutInventingAValue() throws Exception {
    Path engine =
        touch(
            tempDir
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64")
                .resolve("katago.exe"));
    Files.writeString(
        engine.getParent().resolve("lizzieyzy-next-engine-backend.txt"),
        "directml\n",
        StandardCharsets.UTF_8);
    String command =
        quote(engine)
            + " gtp -model model.bin.gz -config gtp.cfg "
            + "-override-config \"onnxProvider=directml,onnxOpenVINOCacheDir=C:\\\\Users\\\\Player\\\\cache\"";

    EngineBootstrapFacts facts = EngineBootstrapFacts.fromCommand(command, "MAIN_BOARD");

    assertEquals("onnx", facts.backend());
    assertEquals("directml", facts.onnxProvider());
    assertEquals(EngineBootstrapFacts.SOURCE_BUNDLED, facts.source());
    String line = facts.formatLogLine("");
    assertFalse(line.contains("Player"), line);
    assertFalse(line.contains("cache"), line);
  }

  @Test
  void missingBackendAndProviderStayExplicitlyUnknown() {
    EngineBootstrapFacts facts =
        EngineBootstrapFacts.fromCommand(
            quote(tempDir.resolve("katago")) + " gtp -model model.bin.gz", "MAIN_BOARD");

    assertEquals("katago", facts.engineType());
    assertEquals(EngineBootstrapFacts.SOURCE_USER_CONFIGURED, facts.source());
    assertEquals("unknown", facts.backend());
    assertEquals("unknown", facts.onnxProvider());
    assertEquals("model.bin.gz", facts.model());
    assertEquals("unknown", facts.config());
  }

  @Test
  void openClMarkerIsUsedWhenNvidiaBackendIsAbsent() throws Exception {
    Path engine =
        touch(
            tempDir
                .resolve("app")
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64")
                .resolve("katago.exe"));
    Files.writeString(
        engine.getParent().resolve("lizzieyzy-next-engine-backend.txt"),
        "opencl\n",
        StandardCharsets.UTF_8);

    EngineBootstrapFacts facts =
        EngineBootstrapFacts.fromCommand(quote(engine) + " gtp -model net.bin.gz", "MAIN_BOARD");

    assertEquals("opencl", facts.backend());
    assertEquals("unknown", facts.onnxProvider());
  }

  @Test
  void emptyOrNullCommandDoesNotInventTypeOrPaths() {
    EngineBootstrapFacts empty = EngineBootstrapFacts.fromCommand("", "MAIN_BOARD");
    assertEquals("unknown", empty.engineType());
    assertEquals("unknown", empty.backend());
    assertEquals("unknown", empty.model());
    assertEquals(EngineBootstrapFacts.SOURCE_USER_CONFIGURED, empty.source());
    EngineBootstrapFacts missing = EngineBootstrapFacts.fromCommand(null, "MAIN_BOARD");
    assertEquals("unknown", missing.engineType());
    assertEquals("unknown", missing.backend());
  }

  @Test
  void hiddenFactsKeepOnlyApplicationNamedClassifications() {
    EngineBootstrapFacts facts =
        EngineBootstrapFacts.forHiddenCommand(
            "KaTaGo", "PrivateVersion", "MAIN_BOARD", "BUNDLED", "NVIDIA", "DirectML");

    assertEquals("katago", facts.engineType());
    assertEquals("bundled", facts.source());
    assertEquals("nvidia", facts.backend());
    assertEquals("directml", facts.onnxProvider());
    assertEquals("MAIN_BOARD", facts.purpose());
    assertEquals("hidden-command", facts.version());
    assertEquals("hidden-command", facts.model());
    assertEquals("hidden-command", facts.config());
    assertFalse(facts.formatLogLine("").contains("Private"));
  }

  @Test
  void hiddenFactsRejectCustomClassificationsAndKeepUnknownExplicit() {
    EngineBootstrapFacts facts =
        EngineBootstrapFacts.forHiddenCommand(
            "_katago_", "unknown", "MAIN_BOARD", "_bundled_", "nvidia-private", "PrivateProvider");

    assertEquals("hidden-command", facts.engineType());
    assertEquals("hidden-command", facts.source());
    assertEquals("hidden-command", facts.backend());
    assertEquals("hidden-command", facts.onnxProvider());
    assertEquals("unknown", facts.version());
    assertEquals("MAIN_BOARD", facts.purpose());
    assertFalse(facts.formatLogLine("").contains("Private"));
    EngineBootstrapFacts unknown =
        EngineBootstrapFacts.fromCommand("", "MAIN_BOARD", EngineCommandVisibility.HIDDEN);
    assertEquals("unknown", unknown.backend());
    assertEquals("unknown", unknown.onnxProvider());
  }

  private static Path touch(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    if (!Files.exists(path)) {
      Files.writeString(path, "", StandardCharsets.UTF_8);
    }
    return path;
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath().normalize() + "\"";
  }
}
