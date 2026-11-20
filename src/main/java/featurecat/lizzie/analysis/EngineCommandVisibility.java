package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether an engine's launch command may be shown to the user.
 *
 * <p>The engine editor saves a command the user chose to hide as {@code encryption||<blob>}. The
 * owner that decodes the blob records {@link #HIDDEN} at that point and hands it to diagnostics
 * and the failure dialog, which never try to recognise a hidden command from its text.
 */
public enum EngineCommandVisibility {
  VISIBLE,
  HIDDEN;

  /** Replaces every command-derived value of a hidden command in diagnostics and failure text. */
  public static final String HIDDEN_VALUE = "<hidden-command>";

  /** Shorter fragments are option vocabulary, drive letters or numbers, not the hidden command. */
  private static final int MIN_FRAGMENT_LENGTH = 4;

  public boolean isHidden() {
    return this == HIDDEN;
  }

  /**
   * Removes the command, its arguments and their directories from free text such as an exception
   * ({@code Cannot run program "<argv0>" (in directory "<cwd>")}) or engine output that echoes a
   * path. A visible command's text is returned unchanged.
   */
  public String scrub(String text, Collection<String> commandFragments) {
    if (!isHidden() || text == null || text.isEmpty() || commandFragments == null) return text;
    if (text.equals(HIDDEN_VALUE)) return text;
    List<String> fragments = fragments(commandFragments);
    if (fragments.isEmpty()) return text;
    StringBuilder alternatives = new StringBuilder();
    for (String fragment : fragments) {
      if (alternatives.length() > 0) alternatives.append('|');
      alternatives.append(Pattern.quote(fragment));
    }
    // One pass, longest fragment first: an inserted marker is never matched again.
    return Pattern.compile(alternatives.toString())
        .matcher(text)
        .replaceAll(Matcher.quoteReplacement(HIDDEN_VALUE));
  }

  static List<String> fragments(Collection<String> commandFragments) {
    Set<String> unique = new LinkedHashSet<>();
    for (String raw : commandFragments) {
      if (raw == null) continue;
      String fragment = raw.trim();
      addWithDirectories(unique, fragment);
      // KataGo's "-override-config a=b,c=d" carries several key=value settings in one argument.
      for (String setting : fragment.split(",")) {
        addWithDirectories(unique, setting);
        int equals = setting.indexOf('=');
        if (equals >= 0) addWithDirectories(unique, setting.substring(equals + 1));
      }
    }
    List<String> ordered = new ArrayList<>(unique);
    ordered.sort(Comparator.comparingInt(String::length).reversed());
    return ordered;
  }

  private static void addWithDirectories(Set<String> fragments, String fragment) {
    add(fragments, fragment);
    for (String directory = parentDirectory(fragment);
        directory != null;
        directory = parentDirectory(directory)) {
      add(fragments, directory);
    }
  }

  private static void add(Set<String> fragments, String candidate) {
    if (candidate == null) return;
    String fragment = candidate.trim();
    if (fragment.length() < MIN_FRAGMENT_LENGTH) return;
    // A bare option name ("-model", "--config") is public vocabulary; its value is scrubbed.
    if (fragment.startsWith("-") && fragment.indexOf('=') < 0) return;
    fragments.add(fragment);
  }

  /**
   * The launch helper picks the working directory among the resolved paths' ancestors, so each
   * ancestor is part of the hidden command. Split textually so a Windows path keeps its
   * directories on every host that renders the failure.
   */
  private static String parentDirectory(String fragment) {
    int separator = Math.max(fragment.lastIndexOf('/'), fragment.lastIndexOf('\\'));
    return separator > 0 ? fragment.substring(0, separator) : null;
  }
}
