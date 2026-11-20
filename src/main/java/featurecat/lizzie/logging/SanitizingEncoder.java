package featurecat.lizzie.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;

final class SanitizingEncoder extends EncoderBase<ILoggingEvent> {
  static final String TRUNCATION_MARKER = "\n[event-truncated]\n";
  private static final byte[] TRUNCATION_MARKER_BYTES =
      TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8);

  private final PatternLayout layout = new PatternLayout();
  private PersistenceSanitizer sanitizer = new PersistenceSanitizer();
  private LogStream stream = LogStream.APP;
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

  @Override
  public void start() {
    layout.setContext(context);
    layout.getInstanceConverterMap().put("corr", CorrelationConverter::new);
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
      // MINA can log peer-supplied protocol text and exec commands, including in throwables.
      // Keep the transport warning and its correlation without persisting its free-form payload.
      if (stream == LogStream.APP && event.getLoggerName().startsWith("org.apache.sshd.")) {
        LoggingEvent metadata = new LoggingEvent();
        metadata.setTimeStamp(event.getTimeStamp());
        metadata.setLoggerName(event.getLoggerName());
        metadata.setLevel(event.getLevel());
        metadata.setMDCPropertyMap(event.getMDCPropertyMap());
        metadata.setMessage("[SSH transport detail omitted]");
        event = metadata;
      }
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
