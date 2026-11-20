package featurecat.lizzie.logging;

import featurecat.lizzie.util.NetworkProxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ConfigExportProjection {
  private static final Set<String> UI_BOOLEAN_KEYS =
      Set.of(
          "show-coordinates",
          "show-winrate-overview",
          "is-apple-style",
          "autoload-default");
  private static final Set<String> UI_INTEGER_KEYS =
      Set.of("board-size", "analysis-max-visits", "max-game-thinking-time-seconds");
  private static final Set<Integer> EXTRA_MODE_VALUES = Set.of(0, 1, 2, 3, 7, 8);
  private static final Set<String> NETWORK_PROXY_MODES =
      Set.of(NetworkProxy.MODE_DIRECT, NetworkProxy.MODE_SYSTEM, NetworkProxy.MODE_MANUAL);
  private static final int MAX_UI_TEXT_LENGTH = 255;
  private static final int MAX_ENGINE_NAME_LENGTH = 255;
  private static final int MAX_EXECUTABLE_BASENAME_LENGTH = 255;

  private ConfigExportProjection() {}

  public static JSONObject project(JSONObject config) {
    JSONObject exported = new JSONObject();
    if (config == null) {
      return exported;
    }
    JSONObject ui = config.optJSONObject("ui");
    if (ui != null) {
      exported.put("ui", projectUi(ui));
    }
    JSONObject leelaz = config.optJSONObject("leelaz");
    if (leelaz != null) {
      exported.put("leelaz", projectLeelaz(leelaz));
    }
    JSONObject logging = config.optJSONObject(LoggingSettings.CONFIG_KEY);
    if (logging != null) {
      exported.put(LoggingSettings.CONFIG_KEY, projectLogging(logging));
    }
    return exported;
  }

  private static JSONObject projectUi(JSONObject ui) {
    JSONObject projected = new JSONObject();
    for (String key : UI_BOOLEAN_KEYS) {
      Object value = ui.opt(key);
      if (value instanceof Boolean) {
        projected.put(key, value);
      }
    }
    for (String key : UI_INTEGER_KEYS) {
      Integer value = integerValue(ui.opt(key));
      if (value != null) {
        projected.put(key, value);
      }
    }

    String theme = boundedText(ui.opt("theme"), MAX_UI_TEXT_LENGTH);
    if (theme != null) {
      projected.put("theme", theme);
    }
    Integer extraMode = integerValue(ui.opt("extra-mode"));
    if (extraMode != null && EXTRA_MODE_VALUES.contains(extraMode)) {
      projected.put("extra-mode", extraMode);
    }
    String proxyMode = boundedText(ui.opt(NetworkProxy.KEY_PROXY_MODE), MAX_UI_TEXT_LENGTH);
    if (proxyMode != null && NETWORK_PROXY_MODES.contains(proxyMode)) {
      projected.put(NetworkProxy.KEY_PROXY_MODE, proxyMode);
    }
    return projected;
  }

  private static JSONObject projectLogging(JSONObject logging) {
    JSONObject projected = new JSONObject();
    Object diagnosticsEnabled = logging.opt(LoggingSettings.DIAGNOSTICS_ENABLED_KEY);
    if (diagnosticsEnabled instanceof Boolean) {
      projected.put(LoggingSettings.DIAGNOSTICS_ENABLED_KEY, diagnosticsEnabled);
    }

    JSONArray moduleNames = logging.optJSONArray(LoggingSettings.DIAGNOSTIC_MODULES_KEY);
    if (moduleNames != null) {
      JSONArray modules = new JSONArray();
      for (int i = 0; i < moduleNames.length(); i++) {
        Object value = moduleNames.opt(i);
        if (!(value instanceof String)) {
          continue;
        }
        try {
          modules.put(DiagnosticModule.fromWireName((String) value).wireName());
        } catch (IllegalArgumentException ignored) {
          // Unknown or future values are omitted from support exports.
        }
      }
      projected.put(LoggingSettings.DIAGNOSTIC_MODULES_KEY, modules);
    }

    JSONArray scopeNames =
        logging.optJSONArray(LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY);
    if (scopeNames != null) {
      JSONArray scopes = new JSONArray();
      for (int i = 0; i < scopeNames.length(); i++) {
        Object value = scopeNames.opt(i);
        if (!(value instanceof String)) {
          continue;
        }
        try {
          scopes.put(TraceScope.fromWireName((String) value).wireName());
        } catch (IllegalArgumentException ignored) {
          // Unknown or future values are omitted from support exports.
        }
      }
      projected.put(LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY, scopes);
    }
    return projected;
  }

  private static JSONObject projectLeelaz(JSONObject leelaz) {
    JSONObject projected = new JSONObject();
    Object legacyCommand = leelaz.opt("command");
    if (legacyCommand instanceof String) {
      putEngineSummary(projected, (String) legacyCommand, false);
    }
    JSONArray settings = leelaz.optJSONArray("engine-settings-list");
    if (settings != null) {
      JSONArray engines = new JSONArray();
      for (int i = 0; i < settings.length(); i++) {
        JSONObject engine = settings.optJSONObject(i);
        if (engine == null) {
          continue;
        }
        JSONObject summary = new JSONObject();
        String name = boundedText(engine.opt("name"), MAX_ENGINE_NAME_LENGTH);
        if (name != null) {
          summary.put("name", name);
        }
        Object command = engine.opt("command");
        if (command instanceof String) {
          putEngineSummary(summary, (String) command, engine.optBoolean("useJavaSSH", false));
        } else {
          putEngineSummary(summary, "", false);
        }
        engines.put(summary);
      }
      projected.put("engine-settings-list", engines);
    }
    return projected;
  }

  private static void putEngineSummary(JSONObject target, String command, boolean useJavaSsh) {
    target.put("kind", engineKind(command));
    // A remote shell command may start with a secret-bearing assignment instead of an executable.
    target.put("executable", useJavaSsh ? "unknown" : executableBasename(command));
  }

  static String engineKind(String command) {
    String lower = command == null ? "" : command.toLowerCase(Locale.ROOT);
    if (lower.contains("katago")) {
      return "katago";
    }
    if (lower.contains("leelaz")) {
      return "leelaz";
    }
    if (lower.contains("zen")) {
      return "zen";
    }
    return "other";
  }

  static String executableBasename(String command) {
    if (command == null || command.isBlank()) {
      return "unknown";
    }
    List<String> tokens = tokenize(command.trim());
    if (tokens.isEmpty()) {
      return "unknown";
    }
    String executable = tokens.get(0);
    int separator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
    String basename = separator >= 0 ? executable.substring(separator + 1) : executable;
    return isSafeExecutableBasename(basename) ? basename : "unknown";
  }

  private static Integer integerValue(Object value) {
    if (!(value instanceof Number)) {
      return null;
    }
    try {
      return new BigDecimal(value.toString()).intValueExact();
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static String boundedText(Object value, int maximumLength) {
    if (!(value instanceof String)) {
      return null;
    }
    String text = (String) value;
    if (text.length() > maximumLength) {
      return null;
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.isISOControl(text.charAt(i))) {
        return null;
      }
    }
    return text;
  }

  private static boolean isSafeExecutableBasename(String basename) {
    if (basename.isEmpty()
        || basename.length() > MAX_EXECUTABLE_BASENAME_LENGTH
        || !basename.equals(basename.trim())
        || ".".equals(basename)
        || "..".equals(basename)) {
      return false;
    }
    for (int offset = 0; offset < basename.length(); ) {
      int codePoint = basename.codePointAt(offset);
      if (!Character.isLetterOrDigit(codePoint)
          && codePoint != ' '
          && codePoint != '.'
          && codePoint != '_'
          && codePoint != '-'
          && codePoint != '+'
          && codePoint != '('
          && codePoint != ')'
          && codePoint != '['
          && codePoint != ']') {
        return false;
      }
      offset += Character.charCount(codePoint);
    }
    return true;
  }

  private static List<String> tokenize(String command) {
    java.util.ArrayList<String> tokens = new java.util.ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < command.length(); i++) {
      char ch = command.charAt(i);
      if (ch == '"') {
        quoted = !quoted;
        continue;
      }
      if (!quoted && Character.isWhitespace(ch)) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        continue;
      }
      current.append(ch);
    }
    if (quoted) {
      return List.of();
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }
}
