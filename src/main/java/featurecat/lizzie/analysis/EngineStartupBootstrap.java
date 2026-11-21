package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.EngineBootstrapFacts;

/** Builds engine bootstrap facts using bundled/backend helpers already used at launch. */
final class EngineStartupBootstrap {
  private EngineStartupBootstrap() {}

  static EngineBootstrapFacts factsFor(String command, String purpose) {
    return factsFor(command, purpose, EngineCommandVisibility.VISIBLE);
  }

  /**
   * A hidden command shares only {@link EngineBootstrapFacts#forHiddenCommand}: KG-next's own names
   * for the engine type, source, backend and ONNX provider, and a marker for everything else.
   */
  static EngineBootstrapFacts factsFor(
      String command, String purpose, EngineCommandVisibility visibility) {
    return EngineBootstrapFacts.fromCommand(command, purpose, visibility);
  }
}
