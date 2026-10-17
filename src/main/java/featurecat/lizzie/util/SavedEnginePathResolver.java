package featurecat.lizzie.util;

import featurecat.lizzie.gui.EngineData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts only the directly launched local executable of a saved engine entry, for filling the
 * contribution engine path. Arguments of the source command (gtp/analysis, models, configs and
 * overrides) are ignored; nothing is launched, probed or written.
 */
public final class SavedEnginePathResolver {
  public enum Kind {
    LOCAL,
    REMOTE,
    INDIRECT,
    UNKNOWN
  }

  public enum Reason {
    EMPTY_COMMAND,
    UNPARSEABLE_COMMAND,
    MISSING_FILE,
    REMOTE_TRANSPORT,
    INDIRECT_LAUNCHER
  }

  private SavedEnginePathResolver() {}

  /** One candidate per saved entry, in list order; display indices start at 1. */
  public static List<Candidate> resolveAll(List<EngineData> engines) {
    List<Candidate> candidates = new ArrayList<Candidate>();
    if (engines == null) {
      return candidates;
    }
    for (int i = 0; i < engines.size(); i++) {
      candidates.add(resolve(engines.get(i), i + 1));
    }
    return candidates;
  }

  public static Candidate resolve(EngineData engine, int displayIndex) {
    if (engine.useJavaSSH) {
      return Candidate.unavailable(engine, displayIndex, Kind.REMOTE, Reason.REMOTE_TRANSPORT);
    }
    String command = engine.commands == null ? "" : engine.commands.trim();
    if (command.isEmpty()) {
      return Candidate.unavailable(engine, displayIndex, Kind.UNKNOWN, Reason.EMPTY_COMMAND);
    }
    List<String> tokens = Utils.splitCommand(command);
    if (tokens.isEmpty() || tokens.get(0).trim().isEmpty()) {
      return Candidate.unavailable(engine, displayIndex, Kind.UNKNOWN, Reason.UNPARSEABLE_COMMAND);
    }
    if (CommandLaunchHelper.isIndirectLauncher(tokens.get(0).trim())) {
      return Candidate.unavailable(engine, displayIndex, Kind.INDIRECT, Reason.INDIRECT_LAUNCHER);
    }
    // Same relative-root and PATH resolution the saved command would get at launch.
    List<String> prepared = CommandLaunchHelper.prepare(tokens).getCommandParts();
    Path executable = Utils.resolveExistingExecutable(prepared.get(0));
    if (executable == null || !Files.isRegularFile(executable)) {
      return Candidate.unavailable(engine, displayIndex, Kind.LOCAL, Reason.MISSING_FILE);
    }
    executable = executable.toAbsolutePath().normalize();
    // A bare name can resolve through PATH/PATHEXT to a script wrapper.
    if (CommandLaunchHelper.isIndirectLauncher(executable.toString())) {
      return Candidate.unavailable(engine, displayIndex, Kind.INDIRECT, Reason.INDIRECT_LAUNCHER);
    }
    return new Candidate(engine, displayIndex, Kind.LOCAL, executable, null);
  }

  public static final class Candidate {
    private final EngineData engine;
    private final int displayIndex;
    private final Kind kind;
    private final Path executable;
    private final Reason reason;

    private Candidate(
        EngineData engine, int displayIndex, Kind kind, Path executable, Reason reason) {
      this.engine = engine;
      this.displayIndex = displayIndex;
      this.kind = kind;
      this.executable = executable;
      this.reason = reason;
    }

    private static Candidate unavailable(
        EngineData engine, int displayIndex, Kind kind, Reason reason) {
      return new Candidate(engine, displayIndex, kind, null, reason);
    }

    public EngineData engine() {
      return engine;
    }

    /** Position in the list shown for this selection only; never persisted. */
    public int displayIndex() {
      return displayIndex;
    }

    public String name() {
      return engine.name == null ? "" : engine.name.trim();
    }

    public Kind kind() {
      return kind;
    }

    public boolean isAvailable() {
      return executable != null;
    }

    /** Normalized absolute executable, or null when the entry cannot be filled. */
    public Path executable() {
      return executable;
    }

    /** Why the entry cannot be filled, or null when available. */
    public Reason reason() {
      return reason;
    }

    /**
     * Text for the contribution engine path field, quoted so that {@link Utils#splitCommand} reads
     * it back as exactly one executable argument when the contribution command is built.
     */
    public String fieldText() {
      if (executable == null) {
        throw new IllegalStateException("Unavailable saved engine has no executable path");
      }
      String path = executable.toString();
      if (path.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '\'' || c == '"')) {
        return path;
      }
      // Apostrophes are literal inside double quotes; a double quote is spliced in as '"'.
      return "\"" + path.replace("\"", "\"'\"'\"") + "\"";
    }
  }
}
