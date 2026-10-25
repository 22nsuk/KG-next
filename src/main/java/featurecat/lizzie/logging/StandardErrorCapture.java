package featurecat.lizzie.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

/**
 * Copies safely framed stderr records without taking ownership of the runtime's console. Records
 * above 16 KiB are omitted. If a boundary cannot be established within 64 KiB or 64 physical lines,
 * persistence is suspended until runtime replacement; console bytes are unchanged.
 */
final class StandardErrorCapture extends OutputStream {
  static final String LOGGER_NAME = LogCategories.APP + ".stderr";
  private static final int MAX_LINE_BYTES = 16 * 1024;
  private static final String OVERFLOW_MESSAGE =
      "[line omitted: exceeds " + MAX_LINE_BYTES + " bytes]";
  private static final int MAX_RECORD_BYTES = 64 * 1024;
  private static final int MAX_RECORD_LINES = 64;
  private static final String SUSPENDED_MESSAGE =
      "[stderr capture suspended: cannot safely frame bounded record; console unchanged]";
  private static final ThreadLocal<Boolean> FORWARDING = new ThreadLocal<>();

  private final PrintStream original;
  private final PrintStream installed;
  private final Logger logger;
  private final BoundedAsyncAppender appender;
  private final Charset charset;
  private final PersistenceSanitizer sanitizer = new PersistenceSanitizer();
  private final ByteArrayOutputStream record = new ByteArrayOutputStream();
  private int recordLines;
  private boolean suspended;
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
    for (int i = offset; i < offset + length && active && !suspended; i++) {
      append(bytes[i] & 0xff);
    }
  }

  @Override
  public void flush() {
    original.flush();
  }

  private boolean shouldCapture() {
    if (!active || suspended || Boolean.TRUE.equals(FORWARDING.get())) {
      return false;
    }
    String thread = Thread.currentThread().getName();
    return !thread.startsWith("lizzie-log-")
        && !thread.startsWith("AsyncAppender-")
        && !thread.startsWith("logback-");
  }

  private void append(int value) {
    if (record.size() >= MAX_RECORD_BYTES) {
      suspend();
      return;
    }
    record.write(value);
    if (value == '\n') {
      recordLines++;
      String raw = record.toString(charset);
      String safe;
      try {
        safe = sanitizer.sanitizeCompleteRecord(raw);
      } catch (RuntimeException failure) {
        suspend();
        return;
      }
      if (safe == null) {
        // Bound both retained bytes and repeated scans of a multiline credential. Once raw
        // context is discarded, even a later newline cannot prove that a value has ended.
        if (recordLines >= MAX_RECORD_LINES) {
          suspend();
        }
        return;
      }
      int terminatorBytes = raw.endsWith("\r\n") ? 2 : 1;
      boolean oversized = record.size() - terminatorBytes > MAX_LINE_BYTES;
      record.reset();
      recordLines = 0;
      if (oversized) {
        forwardLine(OVERFLOW_MESSAGE);
      } else {
        // Only split or trim after the shared policy has seen every original separator.
        safe.lines().map(String::stripTrailing).forEach(this::forwardLine);
      }
    }
  }

  private void suspend() {
    suspended = true;
    record.reset();
    forwardLine(SUSPENDED_MESSAGE);
  }

  private void forwardLine(String text) {
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
