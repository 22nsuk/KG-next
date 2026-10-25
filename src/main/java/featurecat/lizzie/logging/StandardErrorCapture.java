package featurecat.lizzie.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

/** Copies complete stderr lines to the owning runtime without taking ownership of its console. */
final class StandardErrorCapture extends OutputStream {
  static final String LOGGER_NAME = LogCategories.APP + ".stderr";
  private static final int MAX_LINE_BYTES = 16 * 1024;
  private static final String OVERFLOW_MESSAGE =
      "[line omitted: exceeds " + MAX_LINE_BYTES + " bytes]";
  private static final ThreadLocal<Boolean> FORWARDING = new ThreadLocal<>();

  private final PrintStream original;
  private final PrintStream installed;
  private final Logger logger;
  private final BoundedAsyncAppender appender;
  private final Charset charset;
  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  private boolean overflowed;
  private volatile boolean active = true;

  private StandardErrorCapture(
      PrintStream original, Logger logger, BoundedAsyncAppender appender, Charset charset) {
    this.original = original;
    this.logger = logger;
    this.appender = appender;
    this.charset = charset;
    this.installed = new PrintStream(this, true, charset);
  }

  static StandardErrorCapture install(Logger logger, BoundedAsyncAppender appender) {
    StandardErrorCapture capture =
        new StandardErrorCapture(System.err, logger, appender, stderrCharset());
    System.setErr(capture.installed);
    return capture;
  }

  private static Charset stderrCharset() {
    // A Windows standard stream can use a native encoding even when the default charset is UTF-8.
    String encoding = System.getProperty("stderr.encoding");
    if (encoding == null || encoding.isBlank()) {
      encoding = System.getProperty("sun.stderr.encoding");
    }
    if (encoding != null && !encoding.isBlank()) {
      try {
        return Charset.forName(encoding);
      } catch (IllegalArgumentException ignored) {
      }
    }
    return Charset.defaultCharset();
  }

  void retire() {
    active = false;
    if (System.err == installed) {
      System.setErr(original);
    }
    // Never flush, close or acquire a writer lock here: the console may be blocked.
    // An unfinished line remains console-only, just as it does on an ordinary flush.
  }

  @Override
  public synchronized void write(int value) {
    original.write(value);
    if (shouldCapture()) {
      append(value & 0xff);
    }
  }

  @Override
  public synchronized void write(byte[] bytes, int offset, int length) {
    original.write(bytes, offset, length);
    if (!shouldCapture()) {
      return;
    }
    for (int i = offset; i < offset + length && active; i++) {
      append(bytes[i] & 0xff);
    }
  }

  @Override
  public void flush() {
    original.flush();
  }

  private boolean shouldCapture() {
    if (!active || Boolean.TRUE.equals(FORWARDING.get())) {
      return false;
    }
    String thread = Thread.currentThread().getName();
    return !thread.startsWith("lizzie-log-")
        && !thread.startsWith("AsyncAppender-")
        && !thread.startsWith("logback-");
  }

  private void append(int value) {
    if (value == '\n') {
      forwardLine();
    } else if (value != '\r') {
      if (line.size() < MAX_LINE_BYTES) {
        line.write(value);
      } else {
        overflowed = true;
      }
    }
  }

  private void forwardLine() {
    // A raw prefix can lose a redaction delimiter, such as '@' after URI credentials.
    String text = overflowed ? OVERFLOW_MESSAGE : line.toString(charset).stripTrailing();
    line.reset();
    overflowed = false;
    if (!active || text.isEmpty()) {
      return;
    }
    FORWARDING.set(true);
    try {
      // LoggerContext is reused after restart. Bind admission to this runtime's existing queue
      // so a writer already in progress cannot send an old line into a replacement runtime.
      appender.doAppend(
          new LoggingEvent(
              StandardErrorCapture.class.getName(),
              logger,
              Level.WARN,
              "stderr: {}",
              null,
              new Object[] {text}));
    } catch (RuntimeException ignored) {
      // The original console already received the bytes; logging must remain best effort.
    } finally {
      FORWARDING.remove();
    }
  }
}
