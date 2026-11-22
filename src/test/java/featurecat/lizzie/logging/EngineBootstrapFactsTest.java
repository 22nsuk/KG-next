package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.analysis.EngineCommandVisibility;
import featurecat.lizzie.util.OsPlatform;
import java.io.File;
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

class EngineBootstrapFactsTest {
  @TempDir Path tempDir;

  @Test
  void hiddenBootstrapUsesSameMarkerForPathAndAbsoluteCommands() throws Exception {
    Path bin = Files.createDirectory(tempDir.resolve("engine bin"));
    String name = "katago-path-probe";
    Path engine = bin.resolve(name + (OsPlatform.isWindows() ? ".exe" : ""));
    String path = bin.toString();
    if (OsPlatform.isWindows()) {
      Files.copy(Path.of(System.getenv("SystemRoot"), "System32", "where.exe"), engine);
    } else {
      Files.writeString(engine, "#!/bin/sh\nexit 0\n");
      assertTrue(engine.toFile().setExecutable(true));
      Path shadow = Files.createDirectory(tempDir.resolve("not executable"));
      Files.createFile(shadow.resolve(name));
      Files.writeString(shadow.resolve("lizzieyzy-next-engine-backend.txt"), "openvino\n");
      path = shadow + File.pathSeparator + path;
    }
    String pathKey =
        System.getenv().keySet().stream()
            .filter("PATH"::equalsIgnoreCase)
            .findFirst()
            .orElse("PATH");
    LoggingChildProcess.Result result =
        LoggingChildProcess.runWithTimeout(
            tempDir,
            30_000,
            tempDir,
            Map.of(pathKey, path),
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
          // PATH has a different program (where.exe) under the same name.
          Path local = Path.of(args[0]).resolve(args[1]);
          Files.copy(Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe"), local);
          Files.writeString(local.resolveSibling("lizzieyzy-next-engine-backend.txt"), "cpu");
          String bareName = args[1].substring(0, args[1].length() - ".exe".length());
          Process process = new ProcessBuilder(bareName, "/d", "/c", "exit", "17").start();
          try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(17, process.exitValue(), "Windows should start the local cmd.exe");
          } finally {
            if (process.isAlive()) process.destroyForcibly();
          }
          assertEquals(
              "cpu", EngineBootstrapFacts.fromCommand(bareName + " gtp", "MAIN_BOARD").backend());
        }
      } finally {
        runtime.shutdown();
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
