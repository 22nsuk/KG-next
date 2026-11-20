package featurecat.lizzie.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When this installation began writing logs under the hidden-command disclosure contract.
 *
 * <p>Earlier builds wrote a user-hidden ({@code encryption||}) engine command into the engine log
 * as it was: launch failures named the executable and working directory, stderr tails and
 * bootstrap facts named model and config files, and printed stack traces carried the same text.
 * Nothing in those records says which engine was hidden, and their text cannot be searched for the
 * command (a shell splits words off it, short names match ordinary words). So the diagnostic
 * export leaves out engine and captured-stderr records that are older than this epoch and do not
 * belong to the running session, the only rule that needs neither.
 */
final class EngineCommandDisclosureEpoch {
  static final String FILE_NAME = "engine-command-disclosure.since";

  /** Loggers whose records carried hidden-command text before the epoch. */
  private static final Pattern PRE_EPOCH_SENSITIVE_LOGGER =
      Pattern.compile(
          Pattern.quote(LogCategories.ENGINE)
              + "(?:\\..*)?|"
              + Pattern.quote(StandardErrorCapture.LOGGER_NAME));

  /** {@code <date> <time> <level> [<logger>]}, the start of every app.log record. */
  private static final Pattern RECORD_LOGGER =
      Pattern.compile("^\\S+\\s+\\S+\\s+\\S+\\s+\\[([^\\]\\s]+)\\]");

  /** The correlation token the encoder writes after the logger. */
  private static final Pattern RECORD_SESSION = Pattern.compile("(?:^|\\s)session=([^\\s]+)");

  private EngineCommandDisclosureEpoch() {}

  /** Records the epoch the first time a build with the contract starts logging here. */
  static void ensure(Path logsDirectory) {
    try {
      Files.createDirectories(logsDirectory);
      Files.writeString(
          logsDirectory.resolve(FILE_NAME),
          Instant.now().toString() + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
    } catch (FileAlreadyExistsException alreadyRecorded) {
      // The first contract start keeps its epoch.
    } catch (IOException | RuntimeException unavailable) {
      // Without an epoch the export keeps only the running session's sensitive records.
    }
  }

  /** The recorded epoch, or null when it is missing or unreadable. */
  static Instant read(Path logsDirectory) {
    try {
      return Instant.parse(
          Files.readString(logsDirectory.resolve(FILE_NAME), StandardCharsets.UTF_8).strip());
    } catch (IOException | RuntimeException unavailable) {
      // Includes a DateTimeParseException for a damaged file.
      return null;
    }
  }

  /** Decides, per text-log record, whether the export leaves it out as written before the epoch. */
  static final class Filter {
    private final Instant epoch;
    private final String runningSession;
    private int omitted;

    Filter(Instant epoch, String runningSession) {
      this.epoch = epoch;
      this.runningSession = runningSession;
    }

    /** {@code header} is the record's first line and {@code timestamp} its parsed time. */
    boolean omits(String header, Instant timestamp) {
      Matcher logger = RECORD_LOGGER.matcher(header);
      if (!logger.find() || !PRE_EPOCH_SENSITIVE_LOGGER.matcher(logger.group(1)).matches()) {
        return false;
      }
      Matcher session = RECORD_SESSION.matcher(header);
      if (runningSession != null && session.find() && runningSession.equals(session.group(1))) {
        return false;
      }
      if (epoch != null && timestamp != null && !timestamp.isBefore(epoch)) {
        return false;
      }
      omitted++;
      return true;
    }

    int omitted() {
      return omitted;
    }
  }
}
