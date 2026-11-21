package featurecat.lizzie.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Which records of KG-next's own logs a diagnostic bundle shares from sessions other than the
 * running one.
 *
 * <p>Engine observations, captured standard error and uncaught exceptions carry text whose origin
 * only the build that wrote it knew. Such a record can quote a user-hidden engine command or that
 * engine's output, and nothing in the record says whether its writer kept that text out. So the
 * records of those loggers are shared only from the running session, whose build composes them by
 * provenance. A record of those loggers from another session, or without a session, is left out
 * and counted by reason. Every record this build writes carries its writer's session ({@link
 * SanitizingEncoder}), whichever thread logged it, so leaving out records without one loses none of
 * the running session's. Records of the other loggers are application text and are shared from
 * every session.
 *
 * <p>Sharing a protected earlier session would need each session to record the protection its
 * writer applied. A date, or a file naming when protection first ran, cannot stand in for that: an
 * older build can run again after it.
 */
final class RunningSessionRecords {
  /**
   * {@code <date> <time> <level> [<logger>] session=<id> ...}: the writer's session leads {@code
   * %corr}, right after the logger.
   */
  private static final Pattern HEADER =
      Pattern.compile("^\\S+\\s+\\S+\\s+\\S+\\s+\\[([^\\]\\s]+)\\](?:\\s+session=(\\S+))?");

  private final String runningSession;
  private int otherSession;
  private int noSession;

  RunningSessionRecords(String runningSession) {
    this.runningSession = runningSession == null || runningSession.isEmpty() ? null : runningSession;
  }

  /** Loggers whose records can carry text from an engine command or its engine. */
  static boolean carriesEngineText(String logger) {
    return logger.equals(LogCategories.ENGINE)
        || logger.startsWith(LogCategories.ENGINE + ".")
        || logger.equals(StandardErrorCapture.LOGGER_NAME)
        || logger.equals(LogCategories.CRASH);
  }

  /**
   * Whether the record whose first line is {@code header} is left out, counting it if so. A header
   * that does not name its logger is not one this application wrote, so its text is not known to
   * be application text either.
   */
  boolean omits(String header) {
    Matcher matcher = HEADER.matcher(header);
    boolean named = matcher.find();
    if (named && !carriesEngineText(matcher.group(1))) {
      return false;
    }
    String session = named ? matcher.group(2) : null;
    if (session == null) {
      noSession++;
      return true;
    }
    if (!session.equals(runningSession)) {
      otherSession++;
      return true;
    }
    return false;
  }

  /** The manifest's account of the records left out, by reason. */
  JSONObject excluded() {
    return new JSONObject().put("otherSession", otherSession).put("noSession", noSession);
  }
}
