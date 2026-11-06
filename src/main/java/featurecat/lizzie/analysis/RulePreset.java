package featurecat.lizzie.analysis;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Explicit choices for the current game's KataGo rules and komi, never startup defaults. */
public enum RulePreset {
  CHINESE("chinese", 7.5, "RulesQuickMenu.chinese"),
  CHINESE_7("chinese", 7.0, "RulesQuickMenu.chinese7"),
  JAPANESE("japanese", 6.5, "RulesQuickMenu.japanese"),
  CHINESE_ANCIENT("stone-scoring", 7.5, "RulesQuickMenu.ancient"),
  TROMP_TAYLOR("tromp-taylor", 7.5, "RulesQuickMenu.trompTaylor"),
  GOE("goe", 7.5, "RulesQuickMenu.goe");

  private final KataGoRules rules;
  private final double komi;
  private final String labelKey;

  RulePreset(String name, double komi, String labelKey) {
    this.rules = KataGoRules.parse(name).orElseThrow();
    this.komi = komi;
    this.labelKey = labelKey;
  }

  public KataGoRules rules() {
    return rules;
  }

  public double komi() {
    return komi;
  }

  public String labelKey() {
    return labelKey;
  }

  /** Handicap choices change rules only, so offer the shared Chinese rules just once. */
  public static List<RulePreset> choices(boolean handicap) {
    return Arrays.stream(values()).filter(preset -> !handicap || preset != CHINESE_7).toList();
  }

  /** A display-family match alone must not label custom rules or a different komi as a preset. */
  public static Optional<RulePreset> current(KataGoRules rules, double komi, boolean handicap) {
    return choices(handicap).stream()
        .filter(preset -> preset.rules.semanticallyEquals(rules)
            && (handicap || Double.compare(preset.komi, komi) == 0))
        .findFirst();
  }
}
