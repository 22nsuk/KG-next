package featurecat.lizzie.util;

import java.util.List;

/**
 * Writes an argument vector as one batch-file line that cmd.exe hands to CreateProcess unchanged.
 *
 * <p>A batch line is parsed by cmd.exe before the program sees it: operators run other commands,
 * percent signs expand, a line break starts a new command and a leading {@code @} or delimiter
 * changes the program name. Every argument is encoded so that parse yields the original vector,
 * and an argument that cannot be encoded that way rejects the whole line rather than approximating
 * it.
 */
public final class CmdBatchArguments {
  // cmd.exe reads these as separators or operators outside quotes.
  private static final String ARGUMENT_QUOTE_TRIGGERS = " \t&|<>^(),;=";
  // The first word also ends at "/" and loses a leading "@" (echo suppression).
  private static final String PROGRAM_QUOTE_TRIGGERS = ARGUMENT_QUOTE_TRIGGERS + "/@";

  private CmdBatchArguments() {}

  /** The encoded line, or null when cmd.exe would not reproduce the vector exactly. */
  public static String encode(List<String> arguments) {
    if (arguments == null
        || arguments.isEmpty()
        || LaunchTargetResolver.isCmdInternalCommand(arguments.get(0))) {
      return null;
    }
    StringBuilder line = new StringBuilder();
    for (int index = 0; index < arguments.size(); index++) {
      String argument = arguments.get(index);
      if (!isRepresentable(argument)) return null;
      if (index > 0) line.append(' ');
      String text = argument.replace("%", "%%"); // Batch files expand % even inside quotes.
      String triggers = index == 0 ? PROGRAM_QUOTE_TRIGGERS : ARGUMENT_QUOTE_TRIGGERS;
      if (!text.isEmpty() && !containsAny(text, triggers)) {
        line.append(text);
        continue;
      }
      line.append('"').append(text);
      // The program's argument parser reads backslashes before a closing quote as escapes.
      for (int k = text.length() - 1; k >= 0 && text.charAt(k) == '\\'; k--) line.append('\\');
      line.append('"');
    }
    return line.toString();
  }

  /**
   * A double quote toggles cmd.exe's quoting even when escaped for the program, "!" expands when
   * delayed expansion is enabled, and control characters can end the line.
   */
  private static boolean isRepresentable(String argument) {
    if (argument == null) return false;
    for (int i = 0; i < argument.length(); i++) {
      char value = argument.charAt(i);
      if (value == '"' || value == '!' || (value != '\t' && Character.isISOControl(value))) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsAny(String text, String characters) {
    for (int i = 0; i < text.length(); i++) {
      if (characters.indexOf(text.charAt(i)) >= 0) return true;
    }
    return false;
  }
}
