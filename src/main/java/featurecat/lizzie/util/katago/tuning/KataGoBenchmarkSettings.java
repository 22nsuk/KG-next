package featurecat.lizzie.util.katago.tuning;

import org.json.JSONObject;

/** The settings evidenced by a measured row, independently of the settings later recommended. */
public record KataGoBenchmarkSettings(
    int searchThreads,
    int nnServerThreadsPerModel,
    int maxBatchSize,
    Source source,
    boolean requiresDeviceMapping) {
  public enum Source {
    THREAD_SWEEP,
    ADDITIONAL_SERVER_THREADS,
    ADDITIONAL_BATCH_SIZE,
    EXPERIMENTAL_PROFILE
  }

  public KataGoBenchmarkSettings {
    if (searchThreads <= 0 || nnServerThreadsPerModel < 0 || maxBatchSize < 0 || source == null) {
      throw new IllegalArgumentException("Invalid benchmark measurement settings");
    }
    boolean validSource =
        switch (source) {
          case THREAD_SWEEP -> nnServerThreadsPerModel == 0 && maxBatchSize == 0;
          case ADDITIONAL_SERVER_THREADS -> nnServerThreadsPerModel > 0 && maxBatchSize == 0;
          case ADDITIONAL_BATCH_SIZE -> maxBatchSize > 0;
          case EXPERIMENTAL_PROFILE -> nnServerThreadsPerModel > 0 && maxBatchSize > 0;
        };
    if (!validSource)
      throw new IllegalArgumentException("Settings contradict their measurement source");
    if (requiresDeviceMapping
        && (nnServerThreadsPerModel == 0
            || source != Source.ADDITIONAL_SERVER_THREADS
                && source != Source.ADDITIONAL_BATCH_SIZE))
      throw new IllegalArgumentException("Device advice requires an additional NN-server trial");
  }

  public KataGoBenchmarkSettings(
      int searchThreads, int nnServerThreadsPerModel, int maxBatchSize, Source source) {
    this(searchThreads, nnServerThreadsPerModel, maxBatchSize, source, false);
  }

  /**
   * Zero NN threads means unreported; zero batch means benchmark-controlled, never config-derived.
   */
  public static KataGoBenchmarkSettings threadSweep(int threads) {
    return new KataGoBenchmarkSettings(threads, 0, 0, Source.THREAD_SWEEP);
  }

  public boolean matchesRecommendation(int threads, int nnThreads, int batch) {
    return searchThreads == threads
        && (source == Source.EXPERIMENTAL_PROFILE && nnThreads == 0
            || nnServerThreadsPerModel == nnThreads)
        && maxBatchSize == batch;
  }

  public JSONObject toJson() {
    return new JSONObject()
        .put("searchThreads", searchThreads)
        .put("nnServerThreadsPerModel", nnServerThreadsPerModel)
        .put("maxBatchSize", maxBatchSize)
        .put("source", source.name())
        .put("requiresDeviceMapping", requiresDeviceMapping);
  }

  /** Old records remain readable without inventing their measurement conditions. */
  public static KataGoBenchmarkSettings fromJson(JSONObject json) {
    if (json == null) return null;
    try {
      Object deviceMapping = json.get("requiresDeviceMapping");
      if (!(deviceMapping instanceof Boolean)) return null;
      return new KataGoBenchmarkSettings(
          json.getInt("searchThreads"),
          json.getInt("nnServerThreadsPerModel"),
          json.getInt("maxBatchSize"),
          Source.valueOf(json.getString("source")),
          (Boolean) deviceMapping);
    } catch (IllegalArgumentException | org.json.JSONException invalidMetadata) {
      return null;
    }
  }
}
