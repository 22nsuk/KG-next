package featurecat.lizzie.analysis;

import java.util.Objects;

/** Immutable search-depth and saved-result selection for whole-game analysis. */
public final class WholeGameAnalysisOptions {
  public static final int MINIMUM_VISITS = 500;
  public static final int MAXIMUM_VISITS = 1_000_000;
  public static final int DEFAULT_VISITS = 1_000;

  public enum Preset {
    QUICK(500, "WholeGameAnalysis.visits.quick"),
    STANDARD(1_000, "WholeGameAnalysis.visits.standard"),
    DEEP(3_000, "WholeGameAnalysis.visits.deep"),
    PROFESSIONAL(10_000, "WholeGameAnalysis.visits.professional"),
    CUSTOM(-1, "WholeGameAnalysis.visits.custom");

    private final int visits;
    private final String resourceKey;

    Preset(int visits, String resourceKey) {
      this.visits = visits;
      this.resourceKey = resourceKey;
    }

    public int visits() {
      return visits;
    }

    public String resourceKey() {
      return resourceKey;
    }

    public static Preset forVisits(int visits) {
      for (Preset preset : values()) {
        if (preset.visits == visits) {
          return preset;
        }
      }
      return CUSTOM;
    }
  }

  public enum Validation {
    VALID,
    BELOW_MINIMUM,
    ABOVE_MAXIMUM
  }

  public enum ExistingAnalysisPolicy {
    REUSE_SAVED,
    REEVALUATE_CURRENT_ENGINE
  }

  private final int deepVisits;
  private final Preset preset;
  private final Validation validation;
  private final ExistingAnalysisPolicy existingAnalysisPolicy;

  private WholeGameAnalysisOptions(int deepVisits, ExistingAnalysisPolicy existingAnalysisPolicy) {
    this.deepVisits = deepVisits;
    this.existingAnalysisPolicy =
        Objects.requireNonNull(existingAnalysisPolicy, "An existing-analysis policy is required");
    preset = Preset.forVisits(deepVisits);
    validation =
        deepVisits < MINIMUM_VISITS
            ? Validation.BELOW_MINIMUM
            : deepVisits > MAXIMUM_VISITS ? Validation.ABOVE_MAXIMUM : Validation.VALID;
  }

  public static WholeGameAnalysisOptions of(int deepVisits) {
    return of(deepVisits, ExistingAnalysisPolicy.REUSE_SAVED);
  }

  public static WholeGameAnalysisOptions of(
      int deepVisits, ExistingAnalysisPolicy existingAnalysisPolicy) {
    return new WholeGameAnalysisOptions(deepVisits, existingAnalysisPolicy);
  }

  public static WholeGameAnalysisOptions fromStored(int deepVisits) {
    WholeGameAnalysisOptions options = of(deepVisits);
    return options.isValid() ? options : of(DEFAULT_VISITS);
  }

  public int deepVisits() {
    return deepVisits;
  }

  public ExistingAnalysisPolicy existingAnalysisPolicy() {
    return existingAnalysisPolicy;
  }

  public boolean reevaluateCurrentEngine() {
    return existingAnalysisPolicy == ExistingAnalysisPolicy.REEVALUATE_CURRENT_ENGINE;
  }

  public Preset preset() {
    return preset;
  }

  public Validation validation() {
    return validation;
  }

  public boolean isValid() {
    return validation == Validation.VALID;
  }

  public int requireValidVisits() {
    if (!isValid()) {
      throw new IllegalArgumentException(
          "Whole-game analysis visits must be between "
              + MINIMUM_VISITS
              + " and "
              + MAXIMUM_VISITS);
    }
    return deepVisits;
  }
}
