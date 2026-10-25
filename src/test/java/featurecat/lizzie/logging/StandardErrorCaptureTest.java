package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class StandardErrorCaptureTest {
  @TempDir Path tempDir;
  private PrintStream previousStderr;
  private String previousEncoding;
  private ByteArrayOutputStream consoleBytes;
  private PrintStream console;

  @BeforeEach
  void setUp() {
    LoggingRuntime.resetForTests();
    previousStderr = System.err;
    previousEncoding = System.getProperty("sun.stderr.encoding");
    System.setProperty("sun.stderr.encoding", StandardCharsets.UTF_8.name());
    consoleBytes = new ByteArrayOutputStream();
    console = new PrintStream(consoleBytes, true, StandardCharsets.UTF_8);
    System.setErr(console);
  }

  @AfterEach
  void tearDown() {
    try {
      LoggingRuntime.resetForTests();
    } finally {
      System.setErr(previousStderr);
      if (previousEncoding == null) {
        System.clearProperty("sun.stderr.encoding");
      } else {
        System.setProperty("sun.stderr.encoding", previousEncoding);
      }
    }
  }

  @Test
  void preservesConsoleBytesAcrossUnicodeFragmentsAndCrLf() throws Exception {
    LoggingRuntime runtime = start(tempDir);
    assertSame(console, System.err, "initialization alone must not replace stderr");
    runtime.captureStandardError();
    byte[] bytes = "한글 café🙂\r\nfragmented 바둑\n\n  \r\n".getBytes(StandardCharsets.UTF_8);

    for (int i = 0; i < bytes.length; i++) {
      if (i % 2 == 0) {
        System.err.write(bytes[i]);
      } else {
        System.err.write(bytes, i, 1);
      }
      System.err.flush();
    }
    awaitIdle(runtime);

    assertArrayEquals(bytes, consoleBytes.toByteArray());
    List<String> lines = stderrLines(runtime);
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).endsWith("stderr: 한글 café🙂"));
    assertTrue(lines.get(1).endsWith("stderr: fragmented 바둑"));
  }

  @Test
  void boundsCompleteLinesAndLeavesUnfinishedTailOnConsole() throws Exception {
    LoggingRuntime runtime = start(tempDir);
    runtime.captureStandardError();
    String prefix = "x".repeat(16 * 1024);
    String input = prefix + "\n" + prefix + "discarded-overflow\nshort-line\nunfinished-tail";
    System.err.print(input);
    System.err.flush();
    awaitIdle(runtime);

    assertEquals(input, consoleBytes.toString(StandardCharsets.UTF_8));
    List<String> lines = stderrLines(runtime);
    assertEquals(3, lines.size());
    assertTrue(lines.get(0).endsWith("stderr: " + prefix));
    assertTrue(lines.get(1).endsWith("stderr: [line omitted: exceeds 16384 bytes]"));
    assertTrue(lines.get(2).endsWith("stderr: short-line"));

    runtime.shutdown();
    assertSame(console, System.err);
    assertFalse(readApp(runtime).contains("unfinished-tail"));
  }

  @Test
  void overflowingLineCannotExposeCredentialsCutBeforeTheirRedactionBoundary() throws Exception {
    LoggingRuntime runtime = start(tempDir);
    runtime.captureStandardError();
    String secret = "CANARY_STDERR_BOUNDARY";
    String userInfo = " https://user:" + secret;
    String input = "x".repeat(16 * 1024 - userInfo.length()) + userInfo + "@example.com\n";
    System.err.print(input);
    awaitIdle(runtime);

    assertArrayEquals(input.getBytes(StandardCharsets.UTF_8), consoleBytes.toByteArray());
    assertFalse(readApp(runtime).contains(secret), "overflow exposed URI credentials in app.log");
  }

  @Test
  void stackTraceUsesAppPersistenceRedactionCorrelationAndDiagnosticExport() throws Exception {
    LoggingRuntime runtime = start(tempDir);
    runtime.captureStandardError();
    try (var scope = CorrelationContext.openScope().installEngine("stderr-engine")) {
      new IllegalStateException("stderr-stack-canary password=CANARY_STDERR_SECRET stage=load")
          .printStackTrace(System.err);
    }
    awaitIdle(runtime);

    List<String> lines = stderrLines(runtime);
    assertTrue(
        lines.stream()
            .anyMatch(line -> line.contains("IllegalStateException: stderr-stack-canary")));
    assertTrue(lines.stream().anyMatch(line -> line.contains("StandardErrorCaptureTest.java:")));
    assertTrue(lines.stream().allMatch(line -> line.contains("WARN")));
    assertTrue(
        lines.stream()
            .allMatch(line -> line.contains("session=" + runtime.applicationLogSessionId())));
    assertTrue(lines.stream().allMatch(line -> line.contains("engine=stderr-engine")));
    assertFalse(readApp(runtime).contains("CANARY_STDERR_SECRET"));
    assertTrue(consoleBytes.toString(StandardCharsets.UTF_8).contains("CANARY_STDERR_SECRET"));

    Path zip =
        new DiagnosticBundleExporter(tempDir.resolve("diagnostics"))
            .export(
                new DiagnosticBundleRequest(
                    runtime, EnumSet.noneOf(TraceScope.class), new JSONObject(), null, "test"));
    try (ZipFile bundle = new ZipFile(zip.toFile());
        var input = bundle.getInputStream(bundle.getEntry("logs/lizzie/app.log"))) {
      String exported = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(exported.contains("stderr-stack-canary"));
      assertFalse(exported.contains("CANARY_STDERR_SECRET"));
    }
  }

  @Test
  void suppressesSynchronousAndActualWorkerEchoBeforeBuffering() throws Exception {
    start(tempDir);
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    CountDownLatch workerEntered = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    CountDownLatch workerEchoed = new CountDownLatch(1);
    List<String> messages = new CopyOnWriteArrayList<>();
    AtomicInteger calls = new AtomicInteger();
    AppenderBase<ILoggingEvent> nested =
        new AppenderBase<>() {
          @Override
          protected void append(ILoggingEvent event) {
            messages.add(event.getFormattedMessage());
            int call = calls.incrementAndGet();
            if (call == 1) {
              workerEntered.countDown();
              try {
                if (!releaseWorker.await(5, TimeUnit.SECONDS)) {
                  return;
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
            }
            // Bound a failing implementation's echo loop while retaining the real worker path.
            if (call <= 3) {
              System.err.println("worker-echo");
            }
            workerEchoed.countDown();
          }
        };
    nested.setContext(context);
    BoundedAsyncAppender appender = new BoundedAsyncAppender(LogStream.APP, 16, true);
    appender.setContext(context);
    appender.setNested(nested);
    appender.addFilter(
        new Filter<>() {
          @Override
          public FilterReply decide(ILoggingEvent event) {
            System.err.print("synchronous-echo");
            return FilterReply.NEUTRAL;
          }
        });
    appender.start();
    StandardErrorCapture capture =
        StandardErrorCapture.install(context.getLogger(StandardErrorCapture.LOGGER_NAME), appender);
    try {
      System.err.println("seed");
      assertTrue(workerEntered.await(3, TimeUnit.SECONDS));
      System.err.print("partial-");
      releaseWorker.countDown();
      assertTrue(workerEchoed.await(3, TimeUnit.SECONDS));
      System.err.println("complete");
      assertTrue(appender.awaitSubmitted(appender.submittedCount(), deadline()));
      appender.stopAccepting();
      appender.awaitWorkerUntil(deadline());

      assertFalse(appender.isWorkerAliveForTests());
      assertEquals(List.of("stderr: seed", "stderr: partial-complete"), messages);
      assertEquals(2, appender.submittedCount());
      String output = consoleBytes.toString(StandardCharsets.UTF_8);
      assertTrue(output.contains("worker-echo"));
      assertTrue(output.contains("synchronous-echo"));
    } finally {
      releaseWorker.countDown();
      capture.retire();
      appender.stop();
      appender.awaitWorkerUntil(deadline());
      appender.stopNested();
    }
  }

  @Test
  void captureIsIdempotentAndRetirementRespectsReplacementAndStaleReferences() throws Exception {
    LoggingRuntime runtime = start(tempDir);
    runtime.captureStandardError();
    PrintStream stale = System.err;
    runtime.captureStandardError();
    assertSame(stale, System.err);

    PrintStream replacement =
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    System.setErr(replacement);
    runtime.captureStandardError();
    assertSame(replacement, System.err);
    runtime.shutdown();
    runtime.shutdown();
    runtime.captureStandardError();
    assertSame(replacement, System.err);

    LoggingRuntime next = start(tempDir.resolve("next"));
    next.captureStandardError();
    assertNotSame(replacement, System.err);
    stale.println("stale-reference-canary");
    System.err.println("current-reference-canary");
    awaitIdle(next);

    assertFalse(readApp(runtime).contains("stale-reference-canary"));
    assertFalse(readApp(next).contains("stale-reference-canary"));
    assertTrue(readApp(next).contains("current-reference-canary"));
    assertTrue(consoleBytes.toString(StandardCharsets.UTF_8).contains("stale-reference-canary"));
    LoggingRuntime.resetForTests();
    assertSame(replacement, System.err);
  }

  @Test
  void pausedBeforeAdmissionCannotRouteIntoReplacementRuntime() throws Exception {
    LoggingRuntime runtime = start(tempDir);
    awaitIdle(runtime);
    runtime.captureStandardError();
    PrintStream stale = System.err;
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch hold = new CountDownLatch(1);
    runtime.pauseBeforeAcceptForTests(LogStream.APP, entered, hold);
    Thread producer = new Thread(() -> stale.println("paused-stderr-canary"), "stderr-producer");
    producer.setDaemon(true);
    producer.start();
    LoggingRuntime next;
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      runtime.shutdown();
      assertSame(console, System.err);
      next = start(tempDir.resolve("next"));
      next.captureStandardError();
    } finally {
      hold.countDown();
      producer.join(4_000);
    }

    assertFalse(producer.isAlive());
    System.err.println("replacement-after-pause");
    awaitIdle(next);
    assertFalse(readApp(runtime).contains("paused-stderr-canary"));
    assertFalse(readApp(next).contains("paused-stderr-canary"));
    assertFalse(readApp(next).contains("queue saturation"));
    assertTrue(readApp(next).contains("replacement-after-pause"));
    assertTrue(runtime.droppedCountForTests(LogStream.APP) > 0);
  }

  @Test
  void blockedConsoleCannotBlockRetirementOrForwardAfterRestart() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger flushes = new AtomicInteger();
    AtomicInteger closes = new AtomicInteger();
    OutputStream blocked =
        new OutputStream() {
          @Override
          public void write(int value) {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            consoleBytes.write(value);
          }

          @Override
          public void flush() {
            flushes.incrementAndGet();
          }

          @Override
          public void close() {
            closes.incrementAndGet();
          }
        };
    PrintStream original = new PrintStream(blocked, true, StandardCharsets.UTF_8);
    System.setErr(original);
    LoggingRuntime runtime = start(tempDir);
    awaitIdle(runtime);
    runtime.captureStandardError();
    PrintStream stale = System.err;
    Thread producer = new Thread(() -> stale.println("blocked-console-canary"), "blocked-stderr");
    producer.setDaemon(true);
    CountDownLatch shutdownDone = new CountDownLatch(1);
    AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
    Thread shutdown =
        new Thread(
            () -> {
              try {
                runtime.shutdown();
              } catch (Throwable failure) {
                shutdownFailure.set(failure);
              } finally {
                shutdownDone.countDown();
              }
            },
            "stderr-shutdown");
    shutdown.setDaemon(true);
    producer.start();
    LoggingRuntime next;
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      int priorFlushes = flushes.get();
      shutdown.start();
      assertTrue(
          shutdownDone.await(
              LoggingLimits.SHUTDOWN_BUDGET_NANOS + TimeUnit.MILLISECONDS.toNanos(500),
              TimeUnit.NANOSECONDS),
          "shutdown waited on the blocked stderr writer");
      assertNull(shutdownFailure.get());
      assertSame(original, System.err);
      assertEquals(priorFlushes, flushes.get());
      assertEquals(0, closes.get());
      next = start(tempDir.resolve("next"));
      next.captureStandardError();
    } finally {
      release.countDown();
      producer.join(4_000);
      shutdown.join(4_000);
    }

    assertFalse(producer.isAlive());
    assertFalse(shutdown.isAlive());
    System.err.println("after-console-release");
    awaitIdle(next);
    assertTrue(consoleBytes.toString(StandardCharsets.UTF_8).contains("blocked-console-canary"));
    assertFalse(readApp(runtime).contains("blocked-console-canary"));
    assertFalse(readApp(next).contains("blocked-console-canary"));
    assertTrue(readApp(next).contains("after-console-release"));
  }

  private LoggingRuntime start(Path directory) {
    return LoggingRuntime.initialize(
        new WorkDirectoryResolution(directory, List.of()),
        new LoggingLimits(256, 32, 32, 32, 7, 1_000_000, 256_000));
  }

  private void awaitIdle(LoggingRuntime runtime) {
    assertTrue(runtime.awaitIdle(10, TimeUnit.SECONDS));
  }

  private String readApp(LoggingRuntime runtime) throws Exception {
    return Files.readString(runtime.logsDirectory().resolve("app.log"));
  }

  private List<String> stderrLines(LoggingRuntime runtime) throws Exception {
    return readApp(runtime)
        .lines()
        .filter(line -> line.contains("[" + StandardErrorCapture.LOGGER_NAME + "]"))
        .toList();
  }

  private static long deadline() {
    return System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
  }
}
