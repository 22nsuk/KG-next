package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CmdBatchArgumentsTest {
  @Test
  void encodedLinesParseBackToTheSameArgumentVector() {
    List<List<String>> vectors =
        List.of(
            List.of("katago", "gtp"),
            List.of("C:\\Program Files\\KataGo\\katago.exe", "gtp"),
            List.of("C:\\Program Files (x86)\\Go\\engine.exe", "-config", "C:\\Go Engines\\"),
            List.of("katago", "gtp", "&", "echo", "injected"),
            List.of("katago", "-override-config", "maxVisits=100,numSearchThreads=4"),
            List.of("katago", "-model", "100%.bin.gz", "%PATH%"),
            List.of("katago", "a|b", "<in", ">out", "^x", "(y)", "z;w"),
            List.of("katago", "tab\tvalue", " padded ", ""),
            List.of("@ssh", "host.invalid"),
            List.of(";katago", "gtp"),
            List.of("C:/KataGo/katago.exe", "gtp"),
            List.of("wsl.exe", "--cd", "/home/ssh", "--exec", "katago", "gtp"),
            List.of("엔진.exe", "-model", "C:\\바둑\\모델.bin.gz"));
    for (List<String> vector : vectors) {
      String line = CmdBatchArguments.encode(vector);
      assertNotNull(line, vector.toString());
      assertEquals(vector, parseAsBatchLine(line), line);
    }
  }

  @Test
  void ordinaryCommandsKeepTheirReadableForm() {
    assertEquals(
        "katago gtp -model models/ssh",
        CmdBatchArguments.encode(List.of("katago", "gtp", "-model", "models/ssh")));
    assertEquals(
        "\"C:\\Program Files\\KataGo\\katago.exe\" gtp",
        CmdBatchArguments.encode(List.of("C:\\Program Files\\KataGo\\katago.exe", "gtp")));
  }

  @Test
  void argumentsCmdWouldReinterpretRejectTheWholeLine() {
    for (List<String> vector :
        List.of(
            Arrays.asList("katago", "first\nssh host.invalid"),
            Arrays.asList("katago", "first\rsecond"),
            Arrays.asList("katago", "say \"hi\""),
            Arrays.asList("katago", "wow!"),
            Arrays.asList("katago", null),
            Arrays.asList("echo", "ssh"),
            Arrays.asList("start", "ssh", "host.invalid"),
            Arrays.asList("cd..", "x"),
            List.<String>of())) {
      assertNull(CmdBatchArguments.encode(vector), String.valueOf(vector));
    }
    assertNull(CmdBatchArguments.encode(null));
  }

  /**
   * Parses one batch-file line as cmd.exe and then the MSVC runtime would, failing where cmd.exe
   * would run something other than a single program with these arguments.
   */
  private static List<String> parseAsBatchLine(String line) {
    StringBuilder expanded = new StringBuilder();
    for (int i = 0; i < line.length(); i++) {
      char value = line.charAt(i);
      if (value == '%') {
        assertTrue(i + 1 < line.length() && line.charAt(i + 1) == '%', "expands: " + line);
        i++;
      }
      assertFalse(value == '\r' || value == '\n', "line break: " + line);
      expanded.append(value);
    }
    boolean quoted = false;
    for (int i = 0; i < expanded.length(); i++) {
      char value = expanded.charAt(i);
      if (value == '"') {
        quoted = !quoted;
      } else if (!quoted && "&|<>^()".indexOf(value) >= 0) {
        fail("operator outside quotes: " + line);
      }
    }
    assertFalse(quoted, "unterminated quote: " + line);
    assertFalse("@,;= \t".indexOf(expanded.charAt(0)) >= 0, "first word changes: " + line);

    List<String> arguments = new ArrayList<>();
    StringBuilder current = null;
    boolean inQuotes = false;
    for (int i = 0; i < expanded.length(); ) {
      char value = expanded.charAt(i);
      if (!inQuotes && (value == ' ' || value == '\t')) {
        if (current != null) arguments.add(current.toString());
        current = null;
        i++;
        continue;
      }
      if (current == null) current = new StringBuilder();
      if (value == '\\') {
        int count = 0;
        while (i < expanded.length() && expanded.charAt(i) == '\\') {
          count++;
          i++;
        }
        boolean beforeQuote = i < expanded.length() && expanded.charAt(i) == '"';
        current.append("\\".repeat(beforeQuote ? count / 2 : count));
        if (beforeQuote && count % 2 == 1) {
          current.append('"');
          i++;
        }
      } else if (value == '"') {
        inQuotes = !inQuotes;
        i++;
      } else {
        current.append(value);
        i++;
      }
    }
    if (current != null) arguments.add(current.toString());
    return arguments;
  }
}
