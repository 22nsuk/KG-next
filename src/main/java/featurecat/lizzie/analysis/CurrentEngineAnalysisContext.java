package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import java.util.Objects;

/** Conditions shared by a current-engine reevaluation session and its active worker. */
final class CurrentEngineAnalysisContext {
  final Leelaz engine;
  private final Object incarnation;
  private final Object settings;
  private final String command;
  private final String rawRules;
  private final KataGoRules rules;
  private final double pda;
  private final double wrn;
  private final double komi;
  private final boolean dynamicPda;

  CurrentEngineAnalysisContext(Leelaz engine) {
    this.engine = engine;
    incarnation = engine == null ? null : engine.engineIncarnationToken();
    settings = engine == null ? null : engine.analysisSettingsToken();
    command = engine == null ? null : engine.engineCommand();
    rawRules = engine == null ? null : engine.recentRulesLine;
    rules = KataGoRules.parse(rawRules).orElse(null);
    pda = engine == null ? 0 : engine.pda;
    wrn = engine == null ? 0 : engine.wrn;
    komi = engine == null ? 0 : engine.komi;
    dynamicPda = engine != null && engine.isDymPda;
  }

  boolean matchesCurrentEngine() {
    return engine != null
        && engine == Lizzie.leelaz
        && incarnation == engine.engineIncarnationToken()
        && settings == engine.analysisSettingsToken()
        && Objects.equals(command, engine.engineCommand())
        && matchesRules(engine.recentRulesLine)
        && Double.compare(pda, engine.pda) == 0
        && Double.compare(wrn, engine.wrn) == 0
        && Double.compare(komi, engine.komi) == 0
        && dynamicPda == engine.isDymPda;
  }

  private boolean matchesRules(String currentRules) {
    KataGoRules parsed = KataGoRules.parse(currentRules).orElse(null);
    return rules == null
        ? parsed == null && Objects.equals(rawRules, currentRules)
        : rules.semanticallyEquals(parsed);
  }
}
