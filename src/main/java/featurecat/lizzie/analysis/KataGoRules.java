package featurecat.lizzie.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable KataGo rules value used for parse, semantic comparison, display summary, and editing.
 * JSON key order is not identity. Display summaries ignore the area-only friendly-pass setting for
 * territory rules; protocol comparison and serialization preserve every parameter.
 */
public final class KataGoRules {
  public static final String KO = "ko";
  public static final String SCORING = "scoring";
  public static final String TAX = "tax";
  public static final String SUICIDE = "suicide";
  public static final String HAS_BUTTON = "hasButton";
  public static final String WHITE_HANDICAP_BONUS = "whiteHandicapBonus";
  public static final String FRIENDLY_PASS_OK = "friendlyPassOk";

  private static final List<String> REQUIRED_FIELDS =
      List.of(KO, SCORING, TAX, SUICIDE, HAS_BUTTON, WHITE_HANDICAP_BONUS, FRIENDLY_PASS_OK);

  /** Named preset family, ignoring only a boolean friendlyPassOk value under territory scoring. */
  public enum Summary {
    CHINESE("LizzieFrame.currentRules.chinese"),
    CHINESE_ANCIENT("LizzieFrame.currentRules.chn-ancient"),
    JAPANESE("LizzieFrame.currentRules.japanese"),
    TROMP_TAYLOR("LizzieFrame.currentRules.tromp-taylor"),
    GOE("LizzieFrame.currentRules.goe"),
    OTHER("LizzieFrame.currentRules.others");

    private final String labelKey;

    Summary(String labelKey) {
      this.labelKey = labelKey;
    }

    /** Localized display-string key for this summary. */
    public String labelKey() {
      return labelKey;
    }
  }

  private static final Map<String, KataGoRules> PRESETS = officialPresets();

  /**
   * Declaration spellings that name a preset under another normalized name: KataGo's own shorthand
   * spellings plus short country codes seen in SGF RU properties.
   */
  private static final Map<String, String> ALIASES =
      Map.ofEntries(
          Map.entry("cn", "chinese"),
          Map.entry("jp", "japanese"),
          Map.entry("kr", "korean"),
          Map.entry("chineseogs", "chinese-ogs"),
          Map.entry("chinesekgs", "chinese-kgs"),
          Map.entry("stonescoring", "stone-scoring"),
          Map.entry("ancientarea", "stone-scoring"),
          Map.entry("ancient-area", "stone-scoring"),
          Map.entry("ancientterritory", "ancient-territory"),
          Map.entry("agabutton", "aga-button"),
          Map.entry("french", "aga"),
          Map.entry("nz", "new-zealand"),
          Map.entry("newzealand", "new-zealand"),
          Map.entry("tromptaylor", "tromp-taylor"),
          Map.entry("ing", "goe"));

  private final Map<String, Object> fields;

  private KataGoRules(Map<String, Object> fields) {
    this.fields = Collections.unmodifiableMap(new TreeMap<>(fields));
  }

  public static List<String> officialPresetNames() {
    return List.copyOf(PRESETS.keySet());
  }

  /** Exact preset parameter match, so parsing the returned name preserves this value. */
  public static Optional<String> officialNameOf(KataGoRules rules) {
    if (rules == null) {
      return Optional.empty();
    }
    for (Map.Entry<String, KataGoRules> entry : PRESETS.entrySet()) {
      if (entry.getValue().semanticallyEquals(rules)) {
        return Optional.of(entry.getKey());
      }
    }
    return Optional.empty();
  }

  public static Optional<KataGoRules> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = stripGtpPrefix(raw.trim());
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    if (trimmed.startsWith("{")) {
      try {
        return Optional.of(fromJson(new JSONObject(trimmed)));
      } catch (JSONException ignored) {
        return Optional.empty();
      }
    }
    String name = normalizePresetName(trimmed);
    return Optional.ofNullable(PRESETS.get(ALIASES.getOrDefault(name, name)));
  }

  public static KataGoRules fromJson(JSONObject json) {
    Objects.requireNonNull(json, "json");
    Map<String, Object> fields = new TreeMap<>();
    for (String key : json.keySet()) {
      fields.put(key, normalizeValue(json.get(key)));
    }
    return new KataGoRules(fields);
  }

  /**
   * Full parameter identity for protocol confirmation and persisted rules, including extra fields.
   */
  public boolean semanticallyEquals(KataGoRules other) {
    return other != null && fields.equals(other.fields);
  }

  public boolean hasRequiredFields() {
    for (String field : REQUIRED_FIELDS) {
      if (!fields.containsKey(field) || fields.get(field) == null) {
        return false;
      }
    }
    return true;
  }

  public boolean hasField(String name) {
    return fields.containsKey(name);
  }

  public String string(String name) {
    Object value = fields.get(name);
    return value == null ? "" : String.valueOf(value);
  }

  public boolean bool(String name) {
    Object value = fields.get(name);
    return value instanceof Boolean ? (Boolean) value : false;
  }

  public String fieldSummary() {
    StringBuilder text = new StringBuilder();
    for (String key : REQUIRED_FIELDS) {
      Object value = fields.get(key);
      if (value != null) {
        if (text.length() > 0) {
          text.append(", ");
        }
        text.append(key).append('=').append(value);
      }
    }
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      if (REQUIRED_FIELDS.contains(entry.getKey()) || entry.getValue() == null) {
        continue;
      }
      if (text.length() > 0) {
        text.append(", ");
      }
      text.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return text.toString();
  }

  public KataGoRules overlayEditor(
      String scoring,
      String ko,
      boolean suicide,
      String tax,
      String whiteHandicapBonus,
      boolean hasButton,
      boolean friendlyPassOk) {
    Map<String, Object> next = new TreeMap<>(fields);
    next.put(SCORING, scoring);
    next.put(KO, ko);
    next.put(SUICIDE, suicide);
    next.put(TAX, tax);
    next.put(WHITE_HANDICAP_BONUS, whiteHandicapBonus);
    next.put(HAS_BUTTON, hasButton);
    next.put(FRIENDLY_PASS_OK, friendlyPassOk);
    return new KataGoRules(next);
  }

  public Summary summary() {
    if (matchesSummary("chinese") || matchesSummary("chinese-ogs")) {
      return Summary.CHINESE;
    }
    if (matchesSummary("japanese")) {
      return Summary.JAPANESE;
    }
    if (matchesSummary("stone-scoring")) {
      return Summary.CHINESE_ANCIENT;
    }
    if (matchesSummary("tromp-taylor")) {
      return Summary.TROMP_TAYLOR;
    }
    if (matchesSummary("goe")) {
      return Summary.GOE;
    }
    return Summary.OTHER;
  }

  /**
   * KataGo's default komi for these rules, mirroring its rules parser: the New Zealand preset sets
   * 7; otherwise territory scoring uses 6.5, area scoring with a button 7, other area scoring 7.5.
   * Empty when the scoring rule is unknown.
   */
  public OptionalDouble defaultKomi() {
    String scoring = string(SCORING);
    if ("TERRITORY".equals(scoring)) {
      return OptionalDouble.of(6.5);
    }
    if (!"AREA".equals(scoring)) {
      return OptionalDouble.empty();
    }
    if (matchesOfficial("new-zealand") || bool(HAS_BUTTON)) {
      return OptionalDouble.of(7.0);
    }
    return OptionalDouble.of(7.5);
  }

  public JSONObject toJson() {
    JSONObject json = new JSONObject();
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      json.put(entry.getKey(), toJsonValue(entry.getValue()));
    }
    return json;
  }

  public String toGtpArgument() {
    return toJson().toString();
  }

  public String toResponseLine() {
    return "= " + toGtpArgument();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof KataGoRules && semanticallyEquals((KataGoRules) other);
  }

  @Override
  public int hashCode() {
    return fields.hashCode();
  }

  @Override
  public String toString() {
    return toGtpArgument();
  }

  private boolean matchesOfficial(String name) {
    KataGoRules preset = PRESETS.get(name);
    return preset != null && semanticallyEquals(preset);
  }

  private boolean matchesSummary(String name) {
    KataGoRules preset = PRESETS.get(name);
    if (preset == null) {
      return false;
    }
    // KataGo's friendlyPassOk affects passing behavior only for area scoring. Preserve legacy
    // territory values, but require a complete boolean field and exact matches for all other
    // fields.
    if ("TERRITORY".equals(preset.string(SCORING))
        && fields.get(FRIENDLY_PASS_OK) instanceof Boolean) {
      Map<String, Object> summaryFields = new TreeMap<>(fields);
      summaryFields.put(FRIENDLY_PASS_OK, preset.fields.get(FRIENDLY_PASS_OK));
      return summaryFields.equals(preset.fields);
    }
    return semanticallyEquals(preset);
  }

  private static String stripGtpPrefix(String raw) {
    if (raw.isEmpty() || (raw.charAt(0) != '=' && raw.charAt(0) != '?')) {
      return raw;
    }
    int index = 1;
    while (index < raw.length() && Character.isDigit(raw.charAt(index))) {
      index++;
    }
    return raw.substring(index).trim();
  }

  /** Case-insensitive; underscores and spaces are equivalent to hyphens. */
  private static String normalizePresetName(String name) {
    return name.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
  }

  private static Object normalizeValue(Object value) {
    if (value == null || value == JSONObject.NULL) {
      return null;
    }
    if (value instanceof JSONObject) {
      Map<String, Object> nested = new TreeMap<>();
      JSONObject json = (JSONObject) value;
      for (String key : json.keySet()) {
        nested.put(key, normalizeValue(json.get(key)));
      }
      return Collections.unmodifiableMap(nested);
    }
    if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      Object[] items = new Object[array.length()];
      for (int index = 0; index < array.length(); index++) {
        items[index] = normalizeValue(array.get(index));
      }
      return List.of(items);
    }
    if (value instanceof Boolean || value instanceof String) {
      return value;
    }
    if (value instanceof Number) {
      return value;
    }
    return String.valueOf(value);
  }

  private static Object toJsonValue(Object value) {
    if (value == null) {
      return JSONObject.NULL;
    }
    if (value instanceof Map) {
      JSONObject json = new JSONObject();
      @SuppressWarnings("unchecked")
      Map<String, Object> nested = (Map<String, Object>) value;
      for (Map.Entry<String, Object> entry : nested.entrySet()) {
        json.put(entry.getKey(), toJsonValue(entry.getValue()));
      }
      return json;
    }
    if (value instanceof List) {
      JSONArray array = new JSONArray();
      for (Object item : (List<?>) value) {
        array.put(toJsonValue(item));
      }
      return array;
    }
    return value;
  }

  private static Map<String, KataGoRules> officialPresets() {
    Map<String, KataGoRules> presets = new LinkedHashMap<>();
    presets.put(
        "tromp-taylor",
        preset("POSITIONAL", "AREA", "NONE", true, false, "0", false));
    presets.put("chinese", preset("SIMPLE", "AREA", "NONE", false, false, "N", true));
    presets.put("chinese-ogs", preset("POSITIONAL", "AREA", "NONE", false, false, "N", true));
    presets.put("chinese-kgs", preset("POSITIONAL", "AREA", "NONE", false, false, "N", true));
    presets.put("japanese", preset("SIMPLE", "TERRITORY", "SEKI", false, false, "0", false));
    presets.put("korean", preset("SIMPLE", "TERRITORY", "SEKI", false, false, "0", false));
    presets.put("stone-scoring", preset("SIMPLE", "AREA", "ALL", false, false, "0", true));
    presets.put("aga", preset("SITUATIONAL", "AREA", "NONE", false, false, "N-1", true));
    presets.put("bga", preset("SITUATIONAL", "AREA", "NONE", false, false, "N-1", true));
    presets.put("new-zealand", preset("SITUATIONAL", "AREA", "NONE", true, false, "0", true));
    presets.put("aga-button", preset("SITUATIONAL", "AREA", "NONE", false, true, "N-1", true));
    presets.put("goe", preset("POSITIONAL", "AREA", "NONE", true, false, "0", true));
    presets.put(
        "ancient-territory", preset("SIMPLE", "TERRITORY", "ALL", false, false, "0", false));
    return Map.copyOf(presets);
  }

  private static KataGoRules preset(
      String ko,
      String scoring,
      String tax,
      boolean suicide,
      boolean hasButton,
      String whiteHandicapBonus,
      boolean friendlyPassOk) {
    Map<String, Object> fields = new TreeMap<>();
    fields.put(KO, ko);
    fields.put(SCORING, scoring);
    fields.put(TAX, tax);
    fields.put(SUICIDE, suicide);
    fields.put(HAS_BUTTON, hasButton);
    fields.put(WHITE_HANDICAP_BONUS, whiteHandicapBonus);
    fields.put(FRIENDLY_PASS_OK, friendlyPassOk);
    return new KataGoRules(fields);
  }
}
