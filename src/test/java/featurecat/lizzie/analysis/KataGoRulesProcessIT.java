package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** Actual packaged-engine protocol acceptance; never substitutes a fake process. */
@EnabledIfSystemProperty(named = "lizzie.acceptance.engine", matches = ".+")
class KataGoRulesProcessIT {
  @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path directory;

  @Test
  void everyPresetMatchesItsEngineNameAndJsonRoundTripsThroughRealKataGo() throws Exception {
    Path config = directory.resolve("gtp.cfg");
    Files.writeString(config, "rules = chinese\nnumSearchThreads = 1\nmaxVisits = 16\n"
        + "logAllGTPCommunication = true\nlogSearchInfo = true\n"
        + "logSearchInfoForChosenMove = false\nponderingEnabled = false\n"
        + "allowResignation = true\nresignThreshold = -0.90\nresignConsecTurns = 3\n");
    Process process = new ProcessBuilder(System.getProperty("lizzie.acceptance.engine"), "gtp",
        "-model", System.getProperty("lizzie.acceptance.model"), "-config", config.toString())
        .directory(directory.toFile()).redirectError(directory.resolve("engine.log").toFile()).start();
    var reader = Executors.newSingleThreadExecutor();
    try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        var output = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
      int id = 1;
      for (String preset : KataGoRules.officialPresetNames()) {
        KataGoRules expected = KataGoRules.parse(preset).orElseThrow();
        for (String argument : java.util.List.of(preset, expected.toGtpArgument())) {
          output.write(id + " kata-set-rules " + argument + "\n");
          output.flush();
          String applied = reader.submit(() -> response(input)).get(90, TimeUnit.SECONDS);
          assertTrue(applied.startsWith("=" + id), preset + ": " + applied);
          id++;
          output.write(id + " kata-get-rules\n");
          output.flush();
          String observed = reader.submit(() -> response(input)).get(10, TimeUnit.SECONDS);
          assertTrue(observed.startsWith("=" + id), preset + ": " + observed);
          JSONObject actual = new JSONObject(observed.substring(observed.indexOf('{')));
          for (String key : expected.toJson().keySet()) {
            assertEquals(expected.toJson().get(key), actual.get(key), preset + "/" + key);
          }
          id++;
        }
      }
      output.write(id + " quit\n");
      output.flush();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
    } catch (Exception failure) {
      throw new AssertionError("KataGo protocol acceptance failed: "
          + Files.readString(directory.resolve("engine.log")), failure);
    } finally {
      process.destroyForcibly();
      reader.shutdownNow();
    }
  }

  private static String response(BufferedReader input) throws Exception {
    StringBuilder text = new StringBuilder();
    String line;
    while ((line = input.readLine()) != null) {
      if (line.isBlank()) {
        if (!text.isEmpty()) return text.toString();
      } else text.append(line).append('\n');
    }
    throw new IllegalStateException("Engine closed without a complete GTP response: " + text);
  }
}
