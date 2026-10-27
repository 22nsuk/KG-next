package featurecat.lizzie.analysis;

import java.util.Locale;

/**
 * Durable information known about the producer of an analysis payload.
 *
 * <p>Empty text and null numbers mean unknown. A model hash identifies a local file observed by the
 * application; it does not prove which model a remote or already-running engine loaded. This value
 * is deliberately independent of the live request/source tokens used to admit engine output.
 */
public record AnalysisProvenance(
    String engineName,
    String engineVersion,
    String modelName,
    String modelSha256,
    String configSha256,
    String rules,
    Double komi,
    Double pda,
    Double wrn,
    String humanSLProfile) {
  public AnalysisProvenance {
    engineName = text(engineName, 1024);
    engineVersion = text(engineVersion, 128);
    modelName = text(modelName, 1024);
    modelSha256 = sha256(modelSha256);
    configSha256 = sha256(configSha256);
    rules = text(rules, 8192);
    komi = finite(komi);
    pda = finite(pda);
    wrn = finite(wrn);
    humanSLProfile = text(humanSLProfile, 256);
  }

  private static String text(String value, int maxLength) {
    if (value == null) return "";
    String normalized = value.strip();
    if (normalized.length() > maxLength
        || normalized.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
      return "";
    }
    return normalized;
  }

  private static String sha256(String value) {
    String normalized = text(value, 64);
    return normalized.matches("[a-fA-F0-9]{64}") ? normalized.toLowerCase(Locale.ROOT) : "";
  }

  private static Double finite(Double value) {
    return value != null && Double.isFinite(value) ? value : null;
  }
}
