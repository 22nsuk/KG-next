package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.KataGoRules;
import java.util.Objects;

/** Frozen participant facts for one exact history; rulesSummary is null when rules were unknown. */
public record EngineGameParticipantDescriptor(
    EngineParticipantIdentity identity,
    String displayName,
    boolean katago,
    boolean sai,
    KataGoRules.Summary rulesSummary) {
  public EngineGameParticipantDescriptor {
    identity = Objects.requireNonNull(identity, "identity");
    displayName = displayName == null ? "" : displayName;
  }
}
