package featurecat.lizzie.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable SGF syntax, independent of the displayed board, engines and import preferences.
 * Values retain their SGF escapes: C/LZ, GameInfo and opaque properties have different legacy
 * decoding contracts. No move legality, analysis payload or UI installation happens here.
 */
public final class SgfDocument {
  private static final int DEFAULT_BOARD_SIZE = 19;
  private static final Pattern BOARD_SIZE_VALUE_PATTERN = Pattern.compile("[\\d:]+");
  private static final Pattern RECT_BOARD_SIZE_PATTERN = Pattern.compile("([\\d]+):([\\d]+)");
  private static final String SGF_WHITESPACE = " \t\n\r\f\u000B";
  private static final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final Pattern TREE = Pattern.compile("(?s).*?(\\(\\s*;{0,1}.*\\))(?s).*?");

  /** Structural events have an empty name/value; P events retain each ordered property value. */
  public record Event(char kind, String name, String rawValue) {
    String legacyValue() {
      if (rawValue.indexOf('\\') < 0) return rawValue;
      StringBuilder value = new StringBuilder(rawValue.length());
      boolean text = name.equals("PB") || name.equals("PW") || name.equals("RE") || name.equals("DT");
      boolean decoded = name.equals("C") || name.equals("LZ") || name.equals("LZOP")
          || name.equals("LZ2") || name.equals("LZOP2");
      for (int i = 0; i < rawValue.length(); i++) {
        char c = rawValue.charAt(i);
        if (c == '\\' && i + 1 < rawValue.length()) {
          if (!decoded) value.append(c);
          c = rawValue.charAt(++i);
          if (c == 'n' && !text) c = '\n';
        }
        value.append(c);
      }
      return value.toString();
    }
  }

  private static final Event OPEN = new Event('(', "", "");
  private static final Event CLOSE = new Event(')', "", "");
  private static final Event NODE = new Event(';', "", "");
  private final List<Event> events;
  private final int width;
  private final int height;
  private final boolean multiGo;

  SgfDocument(List<Event> events, int width, int height, boolean multiGo) {
    this.events = List.copyOf(events);
    this.width = width;
    this.height = height;
    this.multiGo = multiGo;
  }

  public List<Event> events() { return events; }
  public int width() { return width; }
  public int height() { return height; }
  boolean multiGo() { return multiGo; }

  /** Applies the existing KG input tolerance and preserves ordered syntax without global state. */
  public static SgfDocument parse(String input) {
    return parseNormalized(normalizeInput(input));
  }

  /** Live loaders validate/normalize before clearing state; do not repeat that work here. */
  static SgfDocument parseNormalized(String value) {
    if (value == null) return null;
    Matcher matcher = TREE.matcher(value);
    if (!matcher.matches()) return null;
    value = matcher.group(1);
    int[] size = parseBoardSize(value);
    List<Event> events = new ArrayList<>();
    StringBuilder name = new StringBuilder();
    String tag = "";
    int start = -1;
    boolean escaped = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (start >= 0) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == ']') {
          events.add(new Event('P', tag, value.substring(start, i)));
          start = -1;
          name.setLength(0);
        }
      } else if (c == '[') {
        if (!name.isEmpty()) tag = normalizePropertyName(name.toString());
        name.setLength(0);
        start = i + 1;
      } else if (c == ']') {
        // Tolerate stray closing brackets without making them part of the next property name.
        name.setLength(0);
      } else if (c == '(' || c == ')' || c == ';') {
        name.setLength(0);
        if (c == '(') events.add(OPEN);
        else if (c == ')') events.add(CLOSE);
        else events.add(NODE);
      } else if (c != '\n' && c != '\r' && c != '\t' && c != ' ') name.append(c);
    }
    return new SgfDocument(events, size[0], size[1], hasMultiGoBranchSuffix(value));
  }

  static boolean hasMultiGoBranchSuffix(String value) {
    int last = value.length() - 1;
    if (last < 1 || value.charAt(last) != ')') return false;
    int previous = last - 1;
    while (previous >= 0 && SGF_WHITESPACE.indexOf(value.charAt(previous)) >= 0) previous--;
    return previous >= 0 && value.charAt(previous) == ')';
  }

  private static boolean validBoardSize(int width, int height) {
    return width >= 2 && height >= 2 && (long) width * height <= Integer.MAX_VALUE - 8L;
  }

  private static boolean isSetupStoneProperty(String name) {
    return name.equals("AB") || name.equals("AW") || name.equals("AE");
  }

  private static String asCoord(int x, int y, int width, int height) {
    return width >= 52 || height >= 52 ? x + "_" + y
        : new String(new char[] {alphabet.charAt(x), alphabet.charAt(y)});
  }

  static String normalizeInput(String value) {
    value = normalizeStructuralNewlines(value);
    if (value == null) return null;
    // Match the parsers' surrounding-text tolerance before reading SZ or setup values.
    int start = value.indexOf('(');
    int end = value.lastIndexOf(')');
    if (start >= 0 && end > start) value = value.substring(start, end + 1);
    int[] size = parseBoardSize(value);
    return normalizeSetupRanges(value, size[0], size[1]);
  }

  /** Smart-Go names such as PlayerBlack become PB by removing only ASCII lowercase letters. */
  static String normalizePropertyName(String name) {
    StringBuilder normalized = null;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c >= 'a' && c <= 'z') {
        if (normalized == null) {
          normalized = new StringBuilder(name.length());
          normalized.append(name, 0, i);
        }
      } else if (normalized != null) {
        normalized.append(c);
      }
    }
    return normalized == null ? name : normalized.toString();
  }

  /**
   * Expand ranges before the parsers apply setup points. Keeping properties as individual points lets
   * handicap recognition and edited-snapshot serialization use their existing contracts.
   */
  static String normalizeSetupRanges(String value, int width, int height) {
    if (value.indexOf(':') < 0) return value;
    StringBuilder normalized = null;
    StringBuilder tagBuilder = new StringBuilder();
    String tag = "";
    int valueStart = -1;
    int copied = 0;
    boolean escaped = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (valueStart >= 0) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == ']') {
          if (isSetupStoneProperty(tag)) {
            String points = value.substring(valueStart, i);
            if (points.indexOf(':') >= 0) {
              String expanded = expandSetupRange(points, width, height);
              if (expanded == null) return null;
              if (normalized == null) normalized = new StringBuilder(value.length());
              normalized.append(value, copied, valueStart - 1).append(expanded);
              copied = i + 1;
            }
          }
          valueStart = -1;
        }
      } else if (c == '[') {
        if (tagBuilder.length() > 0) {
          // Match the legacy parsers' Smart-Go name handling, including an empty resulting tag.
          tag = normalizePropertyName(tagBuilder.toString());
          tagBuilder.setLength(0);
        }
        valueStart = i + 1;
      } else if (c == '(' || c == ')' || c == ';') {
        tag = "";
        tagBuilder.setLength(0);
      } else if (SGF_WHITESPACE.indexOf(c) < 0) {
        tagBuilder.append(c);
      }
    }
    if (valueStart >= 0 && isSetupStoneProperty(tag) && value.indexOf(':', valueStart) >= 0) {
      return null;
    }
    return normalized == null ? value : normalized.append(value, copied, value.length()).toString();
  }

  private static String expandSetupRange(String value, int width, int height) {
    // Use reopen's dimension contract before a short range can expand into billions of points.
    // Preserve its legacy fallback for dimensions below two; this is not a new heap/engine limit.
    if (!validBoardSize(width < 2 ? DEFAULT_BOARD_SIZE : width, height < 2 ? DEFAULT_BOARD_SIZE : height)) {
      return null;
    }
    String[] ends = value.split(":", -1);
    if (ends.length != 2) return null;
    int[] first = setupRangeEndpoint(ends[0], width, height);
    int[] last = setupRangeEndpoint(ends[1], width, height);
    if (first == null || last == null || first[0] > last[0] || first[1] > last[1]) {
      return null;
    }
    StringBuilder points = new StringBuilder();
    for (int x = first[0]; x <= last[0]; x++) {
      for (int y = first[1]; y <= last[1]; y++) {
        points.append('[').append(asCoord(x, y, width, height)).append(']');
      }
    }
    return points.toString();
  }

  private static int[] setupRangeEndpoint(String value, int width, int height) {
    int[] point;
    if (width >= 52 || height >= 52) {
      String[] axes = value.trim().split("_", -1);
      if (axes.length != 2) return null;
      try {
        point = new int[] {Integer.parseInt(axes[0]), Integer.parseInt(axes[1])};
      } catch (NumberFormatException invalidCoordinate) {
        return null;
      }
    } else {
      if (value.length() != 2) return null;
      point = convertSgfPosToCoord(value, width, height);
    }
    return point != null && point[0] >= 0 && point[0] < width && point[1] >= 0 && point[1] < height
        ? point
        : null;
  }

  /** Accept escaped LF/CRLF only between SGF tokens, never inside property values. */
  private static String normalizeStructuralNewlines(String value) {
    int start = value.indexOf('(');
    if (start < 0) {
      return value;
    }
    StringBuilder normalized = null;
    int copied = 0;
    int depth = 0;
    boolean inValue = false;
    boolean escaped = false;
    char previous = 0;
    for (int i = start; i < value.length(); i++) {
      char c = value.charAt(i);
      // Keep legacy surrounding-text tolerance, but inspect every adjacent collection tree.
      if (depth == 0 && c != '(' && c != '\\' && !Character.isWhitespace(c)) {
        break;
      }
      if (inValue) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == ']') {
          inValue = false;
          previous = c;
        }
        continue;
      }
      if (c == '\\') {
        int end;
        if (value.startsWith("\\r\\n", i)) {
          end = i + 4;
        } else if (value.startsWith("\\n", i)) {
          end = i + 2;
        } else {
          return null;
        }
        int next = end;
        while (next < value.length() && Character.isWhitespace(value.charAt(next))) {
          next++;
        }
        if (Character.isLetter(previous)
            && next < value.length() && Character.isLetter(value.charAt(next))) {
          return null;
        }
        if (normalized == null) {
          normalized = new StringBuilder(value.length());
        }
        normalized.append(value, copied, i).append('\n');
        copied = end;
        i = end - 1;
        continue;
      }
      if (c == '[') {
        inValue = true;
      } else if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      }
      if (!Character.isWhitespace(c)) {
        previous = c;
      }
    }
    if (normalized == null) {
      return value;
    }
    // Compatibility must not turn a truncated escaped tree into a successful partial import.
    if (depth != 0 || inValue) {
      return null;
    }
    return normalized.append(value, copied, value.length()).toString();
  }

  static int[] parseBoardSize(String value) {
    // Size is needed before creating the history. Scan property boundaries so a name such
    // as PW[SZ[3] cannot resize the board; '[' is legal unescaped text inside a value.
    StringBuilder tag = new StringBuilder();
    int valueStart = -1;
    boolean escaped = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (valueStart >= 0) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == ']') {
          if ("SZ".contentEquals(tag)) {
            String size = value.substring(valueStart, i);
            if (BOARD_SIZE_VALUE_PATTERN.matcher(size).matches()) {
              int[] parsedBoardSize = parseBoardSizeTagValue(size);
              return parsedBoardSize != null
                  ? parsedBoardSize
                  : new int[] {DEFAULT_BOARD_SIZE, DEFAULT_BOARD_SIZE};
            }
          }
          valueStart = -1;
          tag.setLength(0);
        }
      } else if (c == '[') {
        valueStart = i + 1;
      } else if (c == '(' || c == ')' || c == ';') {
        tag.setLength(0);
      } else if (SGF_WHITESPACE.indexOf(c) < 0) {
        tag.append(c);
      }
    }
    return new int[] {DEFAULT_BOARD_SIZE, DEFAULT_BOARD_SIZE};
  }

  static int[] parseBoardSizeTagValue(String rawSzValue) {
    if (rawSzValue == null) {
      return null;
    }
    String normalizedValue = rawSzValue.split(",")[0].trim();
    if (normalizedValue.isEmpty()) {
      return null;
    }
    Matcher rectSizeMatcher = RECT_BOARD_SIZE_PATTERN.matcher(normalizedValue);
    try {
      if (rectSizeMatcher.matches()) {
        int boardWidth = Integer.parseInt(rectSizeMatcher.group(1));
        int boardHeight = Integer.parseInt(rectSizeMatcher.group(2));
        if (boardWidth > 0 && boardHeight > 0) {
          return new int[] {boardWidth, boardHeight};
        }
        return null;
      }
      int squareBoardSize = Integer.parseInt(normalizedValue);
      if (squareBoardSize <= 0) {
        return null;
      }
      return new int[] {squareBoardSize, squareBoardSize};
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  static int[] convertSgfPosToCoord(String pos, int boardWidth, int boardHeight) {
    if (pos.length() < 2) return null;
    if (boardHeight >= 52 || boardWidth >= 52) {
      String[] params = pos.trim().split("_");
      if (params.length == 2) {
        try {
          int[] ret = new int[2];
          ret[0] = Integer.parseInt(params[0]);
          ret[1] = Integer.parseInt(params[1]);
          return ret;
        } catch (NumberFormatException e) {
          e.printStackTrace();
          return null;
        }
      } else {
        return null;
      }
    } else {
      int[] ret = new int[2];
      ret[0] = alphabet.indexOf(pos.charAt(0));
      ret[1] = alphabet.indexOf(pos.charAt(1));
      return ret;
    }
  }

}
