package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.analysis.EngineCommandVisibility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineCmdDiagnosticWindowsTest {
  @Test
  void generatedBatchPreservesNativeArgumentsWithoutRunningArgumentText(@TempDir Path directory)
      throws Exception {
    assumeFalse(!System.getProperty("os.name").startsWith("Windows"), "requires native cmd.exe");
    Path executable = directory.resolve("engine space 中文").resolve("CmdProbe.exe");
    compileProbe(executable, directory.resolve("compile.log"));
    Path shadowMarker = directory.resolve("shadow-command.txt");
    Files.writeString(
        executable.resolveSibling("CmdProbe.cmd"),
        "@echo shadowed > \"%KG_CMD_SHADOW_OUTPUT%\"\r\n");
    List<String> expected =
        List.of(
            "",
            "plain",
            "white space 中文",
            "tab\tseparated",
            "C:\\model path\\",
            "C:\\plain\\",
            "& echo injected > injected.txt",
            "| echo piped > piped.txt",
            "< missing-input.txt",
            "caret^value",
            "(parentheses)",
            "comma,value;semi=equal",
            "%KG_CMD_EXPAND%",
            "%1",
            "%~dp0",
            "100%",
            "%");
    for (String program : List.of(executable.toString(), "CmdProbe")) {
      String launch = program.equals("CmdProbe") ? "path" : "absolute";
      List<String> command = new ArrayList<>();
      command.add(program);
      command.addAll(expected);
      String line =
          EngineFailedMessage.cmdDiagnosticBatchLine(
              command, "", EngineCommandVisibility.VISIBLE, true);
      assertNotNull(line, "a visible directly launched native executable must support CMD diagnosis");
      Path batch = directory.resolve("diagnostic-" + launch + ".bat");
      // Match the diagnostic's UTF-8 batch encoding and GTP code-page prelude. No CALL or extra
      // parser is involved: the native child reports the arguments received from cmd.exe.
      Files.writeString(batch, "@echo off\r\nchcp 65001 > nul\r\n" + line + "\r\n");
      for (String expansion : List.of("off", "on")) {
        String scenario = launch + "-" + expansion;
        Path captured = directory.resolve("argv-" + scenario + ".txt");
        ProcessBuilder builder =
            new ProcessBuilder("cmd.exe", "/d", "/v:" + expansion, "/c", batch.getFileName().toString())
                .directory(directory.toFile());
        builder.environment().put("KG_CMD_PROBE_OUTPUT", captured.toString());
        builder.environment().put("KG_CMD_SHADOW_OUTPUT", shadowMarker.toString());
        builder.environment().put("KG_CMD_EXPAND", "must-not-expand");
        builder.environment().put(
            "PATH", executable.getParent() + ";" + builder.environment().getOrDefault("PATH", ""));
        // An extensionless batch command would choose the .cmd sibling first. The generated
        // explicit .exe name must preserve ProcessBuilder's native-program selection.
        builder.environment().put("PATHEXT", ".CMD;.EXE");
        if (launch.equals("path") && expansion.equals("off")) {
          builder.command("cmd.exe", "/d", "/v:off", "/c", "CmdProbe");
          run(builder, directory.resolve("shadow-control.log"), 30);
          assertTrue(Files.isRegularFile(shadowMarker), "the fixture must expose CMD script lookup");
          Files.delete(shadowMarker);
          builder.command("cmd.exe", "/d", "/v:off", "/c", batch.getFileName().toString());
        }
        run(builder, directory.resolve("cmd-" + scenario + ".log"), 30);
        assertFalse(Files.exists(shadowMarker), "CMD selected the script sibling: " + scenario);
        assertTrue(Files.isRegularFile(captured), "the native argument probe must run: " + scenario);
        List<String> actual =
            Files.readAllLines(captured, StandardCharsets.UTF_8).stream()
                .map(
                    record ->
                        new String(
                            Base64.getDecoder().decode(record.substring("arg=".length())),
                            StandardCharsets.UTF_8))
                .toList();
        assertEquals(expected, actual, "native argv: " + scenario);
        assertFalse(Files.exists(directory.resolve("injected.txt")), "argument text ran a command");
        assertFalse(Files.exists(directory.resolve("piped.txt")), "argument text created a pipeline");
      }
    }
  }

  private static void compileProbe(Path executable, Path log) throws Exception {
    Files.createDirectories(executable.getParent());
    Path source = executable.resolveSibling("CmdProbe.cs");
    Files.writeString(
        source,
        String.join(
            "\n",
            "using System; using System.IO; using System.Text;",
            "class CmdProbe { static void Main() {",
            "  string[] argv = Environment.GetCommandLineArgs();",
            "  StringBuilder output = new StringBuilder();",
            "  for (int i = 1; i < argv.Length; i++) {",
            "    output.Append(\"arg=\").Append(Convert.ToBase64String(",
            "        Encoding.UTF8.GetBytes(argv[i]))).Append('\\n');",
            "  }",
            "  File.WriteAllText(Environment.GetEnvironmentVariable(\"KG_CMD_PROBE_OUTPUT\"),",
            "      output.ToString(), new UTF8Encoding(false));",
            "} }",
            ""));
    Path compiler =
        Path.of(
            System.getenv().getOrDefault("WINDIR", "C:\\Windows"),
            "Microsoft.NET",
            "Framework64",
            "v4.0.30319",
            "csc.exe");
    run(
        new ProcessBuilder(
            compiler.toString(), "/nologo", "/out:" + executable, "/target:exe", source.toString()),
        log,
        60);
  }

  private static void run(ProcessBuilder builder, Path log, long timeoutSeconds) throws Exception {
    Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS), "process timed out: " + log);
      assertEquals(0, process.exitValue(), () -> readLog(log));
    } finally {
      if (process.isAlive()) {
        // Keep the handles obtained from this still-running child; never look up a recycled PID.
        List<ProcessHandle> children = process.descendants().toList();
        children.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "timed-out process did not terminate");
        for (ProcessHandle child : children) child.onExit().get(10, TimeUnit.SECONDS);
      }
    }
  }

  private static String readLog(Path log) {
    try {
      return Files.readString(log);
    } catch (java.io.IOException failure) {
      return "Cannot read process log " + log + ": " + failure;
    }
  }
}
