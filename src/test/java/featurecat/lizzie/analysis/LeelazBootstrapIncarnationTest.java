package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.gtpconfig.GtpConfigurationProbe;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LeelazBootstrapIncarnationTest {
  @TempDir Path tempDir;

  private final Config previousConfig = Lizzie.config;
  private final LizzieFrame previousFrame = Lizzie.frame;
  private final Board previousBoard = Lizzie.board;
  private final double previousDefaultKomi = GameInfo.DEFAULT_KOMI;

  @BeforeEach
  void installGlobals() throws Exception {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir);
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    Lizzie.frame =
        (LizzieFrame) ((sun.misc.Unsafe) field.get(null)).allocateInstance(LizzieFrame.class);
    Lizzie.board = null;
    GameInfo.DEFAULT_KOMI = 6.5;
  }

  @AfterEach
  void restoreGlobals() {
    Lizzie.config = previousConfig;
    Lizzie.frame = previousFrame;
    Lizzie.board = previousBoard;
    GameInfo.DEFAULT_KOMI = previousDefaultKomi;
  }

  @ParameterizedTest
  @CsvSource({
    "false,",
    "false,zengtp_config_set {}",
    "false,rectangular_boardsize 9 13",
    "true,time_settings 0 1 1"
  })
  void retiredPreloadCannotSendOrSettleAgainstReplacement(boolean ssh, String pausedCommand)
      throws Exception {
    BootstrapEngine engine = new BootstrapEngine(ssh);
    ByteArrayOutputStream oldOutput = new ByteArrayOutputStream();
    ByteArrayOutputStream replacement = new ByteArrayOutputStream();
    engine.installFreshCommandOutputForTest(oldOutput);
    engine.pausedCommand = pausedCommand;
    engine.pauseAtEntry = pausedCommand == null;
    engine.dispatchEngineGameBootstrapCommandsForTest(null);
    int commandNumber;
    try {
      assertTrue(engine.paused.await(5, TimeUnit.SECONDS), "old bootstrap did not reach the barrier");
      List<String> expected = bootstrapCommands(ssh);
      int sentBeforeRebind = pausedCommand == null ? 0 : expected.indexOf(pausedCommand);
      assertEquals(expected.subList(0, sentBeforeRebind), lines(oldOutput));
      engine.installFreshCommandOutputForTest(replacement);
      // Leave one real request outstanding. An SSH bootstrap may acknowledge only its own queue.
      engine.requireResponseBeforeSend = ssh;
      engine.sendCommand("protocol_version");
      commandNumber = engine.commandNumberSnapshot();
      assertFalse(engine.isResponseUpToDate());
      engine.width = 13;
      engine.height = 9;
      engine.firstLoad = true;
      GameInfo.DEFAULT_KOMI = 6.5;
    } finally {
      engine.resume.countDown();
      engine.awaitFinished();
    }

    assertEquals(List.of("protocol_version"), lines(replacement));
    assertEquals(commandNumber, engine.commandNumberSnapshot(), "stale commands entered the queue");
    assertFalse(engine.isResponseUpToDate(), "old SSH bootstrap acknowledged the new request");
    assertTrue(engine.firstLoad, "the replacement still owns its one-time board setup");
    assertEquals(13, engine.width);
    assertEquals(9, engine.height);
    assertEquals(6.5, GameInfo.DEFAULT_KOMI);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void currentPreloadCompletesItsOwnBootstrapInOrder(boolean ssh) throws Exception {
    BootstrapEngine engine = new BootstrapEngine(ssh);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    engine.installFreshCommandOutputForTest(output);
    engine.dispatchEngineGameBootstrapCommandsForTest(null);
    engine.awaitFinished();

    assertEquals(bootstrapCommands(ssh), lines(output));
    assertFalse(engine.firstLoad);
    assertEquals(ssh, engine.isResponseUpToDate());
    assertEquals(6.5, GameInfo.DEFAULT_KOMI, "a preload cannot change the main game's default");
  }

  @Test
  void newReservationRetiresQueuedBootstrapBeforeReplacementHasStreams() throws Exception {
    BootstrapEngine engine = new BootstrapEngine(false);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    engine.installFreshCommandOutputForTest(output);
    engine.requireResponseBeforeSend = true;
    engine.initialCommand = "fixed_handicap 2";
    engine.sendCommand("protocol_version");
    engine.dispatchEngineGameBootstrapCommandsForTest(null);
    engine.awaitFinished();
    assertEquals(List.of("protocol_version"), lines(output));

    Leelaz.UpdateEngineStartAttempt replacement = engine.beginUpdateEngineStartAttempt();
    try {
      engine.processCommandResponseLineForTest("= 2");
      engine.sendCommand("get_komi");
      assertEquals(List.of("protocol_version", "get_komi"), lines(output));
      assertFalse(engine.isSettingHandicap, "a retired handicap command cannot claim the next reply");
    } finally {
      replacement.failClose(new IllegalStateException("test finished"));
    }
  }

  @Test
  void comparisonModeIsolatesStartupCommandsAndStillMirrorsOrdinaryCommands() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    BootstrapEngine primary = new BootstrapEngine(false);
    primary.preload = false;
    Leelaz secondary = new Leelaz("");
    ByteArrayOutputStream primaryOutput = new ByteArrayOutputStream();
    ByteArrayOutputStream secondaryOutput = new ByteArrayOutputStream();
    primary.installFreshCommandOutputForTest(primaryOutput);
    secondary.installFreshCommandOutputForTest(secondaryOutput);
    try {
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      Lizzie.setPrimaryEngine(primary);
      Lizzie.leelaz2 = secondary;

      primary.dispatchEngineGameBootstrapCommandsForTest(null);
      primary.awaitFinished();
      assertEquals(bootstrapCommands(false), lines(primaryOutput));
      assertEquals(List.of(), lines(secondaryOutput));
      assertEquals(0.5, GameInfo.DEFAULT_KOMI);

      primary.sendCommand("play B D4");
      List<String> expected = new ArrayList<>(bootstrapCommands(false));
      expected.add("play B D4");
      assertEquals(expected, lines(primaryOutput));
      assertEquals(List.of("play B D4"), lines(secondaryOutput));
    } finally {
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.setPrimaryEngine(previousPrimary);
    }
  }

  private static List<String> bootstrapCommands(boolean ssh) {
    List<String> expected = new ArrayList<>();
    if (ssh) expected.addAll(List.of("name", "name"));
    expected.addAll(List.of("name", "version", "list_commands"));
    expected.add("zengtp_config_set {}");
    if (!ssh) expected.add("komi 0.5");
    expected.add("rectangular_boardsize 9 13");
    if (ssh) expected.add("komi 0.5");
    expected.addAll(List.of("clear_board", "time_settings 0 1 1"));
    return expected;
  }

  private static List<String> lines(ByteArrayOutputStream output) {
    return output.toString(StandardCharsets.UTF_8).lines()
        .map(command -> GtpCommandText.parse(command).payload()).toList();
  }

  private static final class BootstrapEngine extends Leelaz {
    final CountDownLatch paused = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
    final CountDownLatch finished = new CountDownLatch(1);
    boolean pauseAtEntry;
    String pausedCommand;
    Thread worker;
    Throwable workerFailure;

    BootstrapEngine(boolean ssh) throws IOException {
      super("");
      preload = true;
      started = true;
      isSSH = ssh;
      komi = 0.5f;
      width = 9;
      height = 13;
      firstLoad = true;
      initialCommand = "clear_board;time_settings 0 1 1";
      gtpConfigurationProtocol = GtpConfigurationProbe.ZENGTP_PROTOCOL;
      gtpConfigurationProfile = new JSONObject();
    }

    @Override
    void beforeEngineGameBootstrapCommandsForTest(
        EngineManager.EngineGameOwnerTransaction transaction) {
      worker = Thread.currentThread();
      worker.setUncaughtExceptionHandler((thread, failure) -> workerFailure = failure);
      if (pauseAtEntry) pause();
    }

    // Hold the command after its payload is selected and before the queue takes ownership.
    @Override
    String adaptRootTreeReuseForReceiver(String command) {
      if (command.equals(pausedCommand)) pause();
      return super.adaptRootTreeReuseForReceiver(command);
    }

    void pause() {
      paused.countDown();
      try {
        assertTrue(resume.await(10, TimeUnit.SECONDS), "timed out waiting for rebind");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }

    @Override
    void afterEngineGameBootstrapWorkerForTest(
        EngineManager.EngineGameOwnerTransaction transaction) {
      finished.countDown();
    }

    void awaitFinished() throws InterruptedException {
      assertTrue(finished.await(5, TimeUnit.SECONDS), "bootstrap worker did not finish");
      worker.join(5000);
      assertFalse(worker.isAlive(), "bootstrap worker did not exit");
      assertNull(workerFailure, () -> "uncaught bootstrap failure: " + workerFailure);
    }
  }
}
