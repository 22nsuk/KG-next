package featurecat.lizzie.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;

final class SanitizingEncoder extends EncoderBase<ILoggingEvent> {
  static final String TRUNCATION_MARKER = "\n[event-truncated]\n";
  private static final byte[] TRUNCATION_MARKER_BYTES =
      TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8);

  private final PatternLayout layout = new PatternLayout();
  private PersistenceSanitizer sanitizer = new PersistenceSanitizer();
  private LogStream stream = LogStream.APP;
  private String writerSession;
  private String pattern =
      "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%logger] %corr %msg%n%ex";

  void setPattern(String pattern) {
    this.pattern = pattern;
  }

  void setSanitizer(PersistenceSanitizer sanitizer) {
    this.sanitizer = sanitizer == null ? new PersistenceSanitizer() : sanitizer;
  }

  void setLogStream(LogStream stream) {
    this.stream = stream == null ? LogStream.APP : stream;
  }

  LogStream logStream() {
    return stream;
  }

  /**
   * The application log session of the runtime this encoder writes for. Every record it writes is
   * attributed to that session, whichever thread logged it: the MDC that otherwise carries the
   * session is per thread, and only the thread that started logging installs it.
   */
  void setWriterSession(String session) {
    this.writerSession = session == null || session.isEmpty() ? null : session;
  }

  @Override
  public void start() {
    layout.setContext(context);
    String session = writerSession;
    layout.getInstanceConverterMap().put("corr", () -> new CorrelationConverter(session));
    layout.setPattern(pattern);
    layout.start();
    super.start();
  }

  @Override
  public void stop() {
    super.stop();
    layout.stop();
  }

  @Override
  public byte[] headerBytes() {
    return new byte[0];
  }

  @Override
  public byte[] footerBytes() {
    return new byte[0];
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    try {
      String formatted = layout.doLayout(event);
      byte[] encoded = sanitizer.sanitize(formatted).getBytes(StandardCharsets.UTF_8);
      if (encoded.length <= LoggingLimits.MAX_PERSISTED_EVENT_BYTES) {
        return encoded;
      }
      int prefixLength =
          LoggingLimits.MAX_PERSISTED_EVENT_BYTES - TRUNCATION_MARKER_BYTES.length;
      while (prefixLength > 0 && (encoded[prefixLength] & 0xc0) == 0x80) {
        prefixLength--;
      }
      byte[] bounded = new byte[prefixLength + TRUNCATION_MARKER_BYTES.length];
      System.arraycopy(encoded, 0, bounded, 0, prefixLength);
      System.arraycopy(
          TRUNCATION_MARKER_BYTES,
          0,
          bounded,
          prefixLength,
          TRUNCATION_MARKER_BYTES.length);
      return bounded;
    } catch (RuntimeException e) {
      addError("encoder redaction failed", e);
      return (PersistenceSanitizer.FAILURE_MARKER + System.lineSeparator())
          .getBytes(StandardCharsets.UTF_8);
    }
  }
}
