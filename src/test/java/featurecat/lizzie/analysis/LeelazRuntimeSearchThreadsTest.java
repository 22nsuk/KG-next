package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeelazRuntimeSearchThreadsTest {
  @TempDir Path root;

  @Test
  void confirmsOnlyNumberedValidResponsesAndNeverMirrors() throws Exception {
    try (Environment env = new Environment()) {
      Leelaz.RuntimeSearchThreads target = env.engine.captureRuntimeSearchThreads();
      assertNull(target.lastConfirmedValue());
      CompletableFuture<Integer> query = target.query();
      int get = env.awaitCommand("kata-get-param numSearchThreads");
      assertFalse(query.isDone());
      env.engine.processCommandResponseLineForTest("=" + get + " 6");
      assertEquals(6, query.get(2, TimeUnit.SECONDS));
      assertEquals(6, target.lastConfirmedValue());

      CompletableFuture<Integer> set = target.apply(10);
      int id = env.awaitCommand("kata-set-param numSearchThreads 10");
      assertFalse(set.isDone());
      assertEquals(6, target.lastConfirmedValue());
      assertEquals("", env.otherOutput.toString(StandardCharsets.UTF_8));
      env.engine.processCommandResponseLineForTest("=" + id);
      assertEquals(10, set.get(2, TimeUnit.SECONDS));
      assertEquals(10, target.lastConfirmedValue());
      assertEquals("", env.otherOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void invalidAndRejectedResponsesRetainConfirmedValue() throws Exception {
    try (Environment env = new Environment()) {
      Leelaz.RuntimeSearchThreads target = env.engine.captureRuntimeSearchThreads();
      CompletableFuture<Integer> first = target.apply(8);
      env.engine.processCommandResponseLineForTest("=" + env.awaitCommand("kata-set-param numSearchThreads 8"));
      assertEquals(8, first.get(2, TimeUnit.SECONDS));
      CompletableFuture<Integer> malformed = target.query();
      env.engine.processCommandResponseLineForTest("=" + env.awaitCommand("kata-get-param numSearchThreads") + " 8.0");
      assertThrows(ExecutionException.class, () -> malformed.get(2, TimeUnit.SECONDS));
      CompletableFuture<Integer> rejected = target.apply(9);
      env.engine.processCommandResponseLineForTest("?" + env.awaitCommand("kata-set-param numSearchThreads 9") + " unknown parameter");
      assertThrows(ExecutionException.class, () -> rejected.get(2, TimeUnit.SECONDS));
      assertEquals(8, target.lastConfirmedValue());
      assertThrows(ExecutionException.class, () -> target.apply(0).get(2, TimeUnit.SECONDS));
      assertThrows(ExecutionException.class, () -> target.apply(1025).get(2, TimeUnit.SECONDS));
      assertFalse(env.commands().contains("numSearchThreads 0"));
    }
  }

  @Test
  void explicitRemoteThreadSettingConfirmsOrReportsRemoteRejection() throws Exception {
    try (Environment env = new Environment("ssh host katago gtp")) {
      Leelaz.RuntimeSearchThreads target = env.engine.captureRuntimeSearchThreads();
      CompletableFuture<Integer> accepted = target.apply(5);
      env.engine.processCommandResponseLineForTest(
          "=" + env.awaitCommand("kata-set-param numSearchThreads 5"));
      assertEquals(5, accepted.get(2, TimeUnit.SECONDS));
      CompletableFuture<Integer> rejected = target.apply(6);
      env.engine.processCommandResponseLineForTest(
          "?" + env.awaitCommand("kata-set-param numSearchThreads 6") + " unknown parameter");
      assertThrows(ExecutionException.class, () -> rejected.get(2, TimeUnit.SECONDS));
      assertEquals(5, target.lastConfirmedValue());
    }
  }

  @Test
  void timedOutCommandRetiresLateAckWithoutConsumingNextResponse() throws Exception {
    try (Environment env = new Environment()) {
      env.engine.setRuntimeSearchThreadsTimeoutForTest(200);
      Leelaz.RuntimeSearchThreads target = env.engine.captureRuntimeSearchThreads();
      CompletableFuture<Integer> old = target.apply(12);
      int oldId = env.awaitCommand("kata-set-param numSearchThreads 12");
      assertThrows(ExecutionException.class, () -> old.get(2, TimeUnit.SECONDS));
      assertNull(target.lastConfirmedValue());
      env.engine.setRuntimeSearchThreadsTimeoutForTest(3000);
      CompletableFuture<Integer> next = target.query();
      int nextId = env.awaitCommand("kata-get-param numSearchThreads");
      env.engine.processCommandResponseLineForTest("=" + oldId);
      assertFalse(next.isDone());
      env.engine.processCommandResponseLineForTest("=" + nextId + " 7");
      assertEquals(7, next.get(2, TimeUnit.SECONDS));
      assertEquals(7, target.lastConfirmedValue());
    }
  }

  @Test
  void selectionAndReaderReplacementCannotReceiveOrPublishOldCommands() throws Exception {
    try (Environment env = new Environment()) {
      Leelaz.RuntimeSearchThreads old = env.engine.captureRuntimeSearchThreads();
      CompletableFuture<Integer> pending = old.query();
      int queryId = env.awaitCommand("kata-get-param numSearchThreads");
      env.engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      assertFalse(old.isCurrent());
      assertNull(old.lastConfirmedValue());
      env.engine.processCommandResponseLineForTest("=" + queryId + " 13");
      assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
      assertThrows(ExecutionException.class, () -> old.apply(14).get(2, TimeUnit.SECONDS));
      Leelaz.RuntimeSearchThreads fresh = env.engine.captureRuntimeSearchThreads();
      assertNull(fresh.lastConfirmedValue());
      Lizzie.setPrimaryEngine(env.other);
      assertFalse(fresh.isCurrent());
      assertThrows(ExecutionException.class, () -> fresh.apply(14).get(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void queuedOldReaderOperationDoesNotWriteAfterRebind() throws Exception {
    try (Environment env = new Environment()) {
      env.engine.requireResponseBeforeSend = true;
      env.engine.setRuntimeSearchThreadsTimeoutForTest(500);
      env.engine.sendCommandWithResponseForTest("name", () -> {});
      Leelaz.RuntimeSearchThreads old = env.engine.captureRuntimeSearchThreads();
      CompletableFuture<Integer> waiting = old.apply(9);
      ByteArrayOutputStream replacement = new ByteArrayOutputStream();
      env.engine.installFreshCommandOutputForTest(replacement);
      assertThrows(ExecutionException.class, () -> waiting.get(2, TimeUnit.SECONDS));
      assertFalse(env.commands().contains("kata-set-param numSearchThreads 9"));
      assertFalse(replacement.toString(StandardCharsets.UTF_8).contains("numSearchThreads"));
      assertNull(env.engine.captureRuntimeSearchThreads().lastConfirmedValue());
    }
  }

  @Test
  void lateQueryCannotReplaceNewerConfirmedSet() throws Exception {
    try (Environment env = new Environment()) {
      Leelaz.RuntimeSearchThreads target = env.engine.captureRuntimeSearchThreads();
      CompletableFuture<Integer> query = target.query();
      int queryId = env.awaitCommand("kata-get-param numSearchThreads");
      CompletableFuture<Integer> set = target.apply(10);
      int setId = env.awaitCommand("kata-set-param numSearchThreads 10");
      env.engine.processCommandResponseLineForTest("=" + setId);
      assertEquals(10, set.get(2, TimeUnit.SECONDS));
      env.engine.processCommandResponseLineForTest("=" + queryId + " 6");
      assertEquals(10, query.get(2, TimeUnit.SECONDS));
      assertEquals(10, target.lastConfirmedValue());
    }
  }

  @Test
  void blockedTransportDoesNotBlockTimeoutUiOrPrimarySelection() throws Exception {
    for (boolean setting : new boolean[] {false, true}) {
      try (Environment env = new Environment()) {
        var flushing = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        env.engine.installFreshCommandOutputForTest(new java.io.OutputStream() {
          @Override public void write(int value) {}
          @Override public void flush() throws java.io.IOException {
            flushing.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("test flush deadline");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new java.io.IOException(interrupted);
            }
          }
        });
        env.engine.setRuntimeSearchThreadsTimeoutForTest(100);
        Leelaz.RuntimeSearchThreads target = env.engine.captureRuntimeSearchThreads();
        CompletableFuture<Integer> operation = setting ? target.apply(10) : target.query();
        CompletableFuture<Boolean> ui = new CompletableFuture<>();
        try {
          assertTrue(flushing.await(2, TimeUnit.SECONDS));
          assertThrows(ExecutionException.class, () -> operation.get(2, TimeUnit.SECONDS));
          javax.swing.SwingUtilities.invokeLater(() -> {
            boolean current = target.isCurrent();
            Lizzie.setPrimaryEngine(env.other);
            ui.complete(current && !target.isCurrent());
          });
          assertTrue(ui.get(1, TimeUnit.SECONDS));
          assertNull(target.lastConfirmedValue());
          assertEquals("", env.otherOutput.toString(StandardCharsets.UTF_8));
        } finally {
          release.countDown();
          ui.get(2, TimeUnit.SECONDS);
        }
      }
    }
  }

  private final class Environment implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final Leelaz previousSecond = Lizzie.leelaz2;
    private final GtpConsolePane previousConsole = Lizzie.gtpConsole;
    private final featurecat.lizzie.gui.LizzieFrame previousFrame = Lizzie.frame;
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final ByteArrayOutputStream otherOutput = new ByteArrayOutputStream();
    final Leelaz engine;
    final Leelaz other;

    Environment() throws Exception {
      this("katago gtp");
    }

    Environment(String command) throws Exception {
      Lizzie.config = ConfigTestHelper.createForTests(root);
      Lizzie.config.uiConfig = new org.json.JSONObject();
      Lizzie.config.leelazConfig = new org.json.JSONObject();
      Lizzie.config.config = new org.json.JSONObject();
      var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      Lizzie.gtpConsole = (QuietConsole) unsafe.allocateInstance(QuietConsole.class);
      Lizzie.frame = (QuietFrame) unsafe.allocateInstance(QuietFrame.class);
      engine = new Leelaz(command);
      other = new Leelaz("katago gtp");
      engine.installFreshCommandOutputForTest(output);
      other.installFreshCommandOutputForTest(otherOutput);
      engine.started = other.started = true;
      engine.isLoaded = other.isLoaded = true;
      engine.isKatago = other.isKatago = true;
      Lizzie.setPrimaryEngine(engine);
      Lizzie.leelaz2 = other;
    }

    String commands() {
      return output.toString(StandardCharsets.UTF_8);
    }

    int awaitCommand(String suffix) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      do {
        for (String line : commands().split("\\R")) {
          if (line.endsWith(suffix)) return Integer.parseInt(line.substring(0, line.indexOf(' ')));
        }
        Thread.sleep(5);
      } while (System.nanoTime() < deadline);
      fail("No command ending in " + suffix + ": " + commands());
      return -1;
    }

    @Override
    public void close() {
      engine.isNormalEnd = other.isNormalEnd = true;
      engine.forceQuit();
      other.forceQuit();
      Lizzie.config = previousConfig;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.leelaz2 = previousSecond;
      Lizzie.gtpConsole = previousConsole;
      Lizzie.frame = previousFrame;
    }
  }

  private static final class QuietConsole extends GtpConsolePane {
    private QuietConsole() { super(null); }
    @Override public void addLine(String line) {}
  }

  private static final class QuietFrame extends featurecat.lizzie.gui.LizzieFrame {
    @Override public void refresh() {}
    @Override public void resetTitle() {}
  }
}
