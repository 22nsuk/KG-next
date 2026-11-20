package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Real child processes exercise the same selection/stop boundary used by engine menus. */
@EnabledOnOs(OS.LINUX)
class EngineManagerBenchmarkTest {
  @TempDir Path directory;

  @Test
  void engineGameRejectsBenchmarkBeforeStartingEitherCatalogParticipant() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture benchmark = env.fixture("game benchmark");
      Fixture peer = new Fixture(directory.resolve("game peer"), false);
      env.engines.add(peer.engine);
      java.util.concurrent.atomic.AtomicBoolean nonGtpNotice =
          new java.util.concurrent.atomic.AtomicBoolean();
      EngineManager manager =
          new EngineManager(List.of(benchmark.engine, peer.engine)) {
            @Override
            protected void showBenchmarkGtpUnavailable() {
              nonGtpNotice.set(true);
            }
          };
      Lizzie.setEngineManager(manager);
      var plan = featurecat.lizzie.enginegame.EngineGamePlans.harness(0, 1, false);
      assertFalse(manager.startEngineGame(plan));
      assertTrue(nonGtpNotice.get(), "the user must receive the non-GTP participant reason");
      assertFalse(Files.exists(benchmark.path.resolve("pid")));
      assertFalse(Files.exists(peer.path.resolve("pid")));
      assertSame(benchmark.engine, manager.engineList.get(plan.blackIndex()));
      assertSame(peer.engine, manager.engineList.get(plan.whiteIndex()));
    }
  }

  @Test
  void stoppingQueuedSelectionNeverCreatesProcess() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture target = env.fixture("queued");
      EngineManager manager = env.manager(target.engine);
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(manager.switchEngineIfAvailable(0, true));
            manager.killThisEngines();
          });
      SwingUtilities.invokeAndWait(() -> {});
      SwingUtilities.invokeAndWait(() -> {});
      assertFalse(Files.exists(target.path.resolve("pid")));
      assertEquals(
          Lizzie.resourceBundle.getString("Benchmark.cancelled"),
          manager.engineSwitchUiSnapshot(true).failureDetail());
    }
  }

  @Test
  void selectedDefaultRunsButUnselectedPreloadDoesNot() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture unused = env.fixture("unused preload");
      Fixture selected = env.fixture("selected default");
      EngineData preload = new EngineData();
      preload.commands = unused.engine.engineCommand;
      preload.name = "Unused";
      preload.preload = true;
      preload.width = preload.height = 19;
      EngineData defaults = new EngineData();
      defaults.commands = selected.engine.engineCommand;
      defaults.name = "Selected";
      defaults.isDefault = true;
      defaults.width = defaults.height = 13;
      EngineManager manager =
          new EngineManager(
              Lizzie.config,
              0,
              true,
              new ArrayList<>(List.of(preload, defaults)),
              command -> {
                Leelaz engine = new NamedEngine(command);
                env.engines.add(engine);
                return engine;
              });
      selected.awaitReady();
      assertEquals(1, EngineManager.currentEngineNo);
      assertEquals(19, Board.boardWidth);
      assertFalse(Files.exists(unused.path.resolve("pid")));
      manager.forceKillAllEngines();
    }
  }

  @Test
  void modeReservationRejectsToolBeforeCreatingProcess() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture target = env.fixture("reserved");
      EngineManager manager = env.manager(target.engine);
      try (var reservation = target.engine.beginExclusiveGtpLifecycleReservation(new Object())) {
        assertNotNull(reservation);
        assertFalse(manager.switchEngineIfAvailable(0, true));
        assertFalse(Files.exists(target.path.resolve("pid")));
      }
      assertTrue(manager.switchEngineIfAvailable(0, true));
      target.awaitReady();
    }
  }

  @Test
  void selectedBenchmarkPreservesArgumentsAndBoardWithoutProtocolOrReadinessBudget()
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture fixture = env.fixture("engine with spaces");
      Leelaz engine = fixture.engine;
      engine.initialCommand = "play B D4";
      engine.oriWidth = 13;
      engine.orikomi = 2.5f;
      EngineManager manager = env.manager(engine);
      BoardHistoryList history = Lizzie.board.getHistory();
      var node = history.getCurrentHistoryNode();
      double komi = history.getGameInfo().getKomi();

      assertTrue(manager.switchEngineIfAvailable(0, true));
      fixture.awaitReady();
      engine.togglePonder();
      engine.ponder();
      assertFalse(engine.isPondering(), "a benchmark cannot enter analysis mode");
      engine.sendCommand("name");
      engine.sendCommandNoLeelaz2("play B D4");
      fixture.command("checkpoint");
      fixture.awaitFile("checkpoint");
      assertEquals("", Files.readString(fixture.path.resolve("stdin")));
      assertFalse(engine.isLoaded());
      assertFalse(manager.isEngineSwitchActive(0, true));
      assertSame(history, Lizzie.board.getHistory());
      assertSame(node, history.getCurrentHistoryNode());
      assertEquals(komi, history.getGameInfo().getKomi());
      assertEquals(19, Board.boardWidth);
      await(
          () ->
              env.console.output.toString().contains("raw stdout")
                  && env.console.output.toString().contains("raw stderr"));
      assertTrue(
          Files.readString(fixture.path.resolve("argv"))
              .contains("numSearchThreads=3,logDir=benchmark/value"));

      fixture.command("exit 0");
      BenchmarkExecution.Snapshot result =
          engine.benchmarkExecution().completion().get(5, TimeUnit.SECONDS);
      assertEquals(BenchmarkExecution.State.SUCCEEDED, result.state());
      engine.cancelBenchmark();
      assertEquals(
          BenchmarkExecution.State.SUCCEEDED, engine.benchmarkExecution().snapshot().state());
      assertEquals(0, result.exitCode());
      assertTrue(result.outputTail().contains("final stdout"));
      assertTrue(result.outputTail().contains("final stderr"));
      assertFalse(engine.isLoaded());
      assertFalse(fixture.alive());
    }
  }

  @Test
  void nonzeroAndCreationFailureRetainDiagnosticsWithoutGtpFailureRecovery() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture fixture = env.fixture("failure");
      EngineManager manager = env.manager(fixture.engine);
      assertTrue(manager.switchEngineIfAvailable(0, true));
      fixture.awaitReady();
      fixture.command("exit 7");
      var failed = fixture.engine.benchmarkExecution().completion().get(5, TimeUnit.SECONDS);
      assertEquals(BenchmarkExecution.State.FAILED, failed.state());
      assertEquals(7, failed.exitCode());
      assertTrue(failed.outputTail().contains("final stderr"));
      assertFalse(manager.isEngineSwitchActive(0, true));

      Leelaz missing = new NamedEngine("\"" + directory.resolve("missing/katago") + "\" benchmark");
      manager = env.manager(missing);
      assertTrue(manager.switchEngineIfAvailable(0, true));
      var notStarted = missing.benchmarkExecution().completion().get(5, TimeUnit.SECONDS);
      assertEquals(BenchmarkExecution.State.FAILED, notStarted.state());
      assertTrue(notStarted.detail().contains("katago"));
      assertNull(notStarted.exitCode());
    }
  }

  @Test
  void closeCurrentCancelsEvenAfterOutputEofAndExplicitSelectionCreatesFreshInvocation()
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture fixture = env.fixture("eof");
      EngineManager manager = env.manager(fixture.engine);
      assertTrue(manager.switchEngineIfAvailable(0, true));
      fixture.awaitReady();
      BenchmarkExecution first = fixture.engine.benchmarkExecution();
      fixture.command("eof");
      fixture.awaitFile("eof");
      assertTrue(fixture.alive());
      assertFalse(first.completion().isDone());
      manager.killThisEngines();
      assertEquals(
          BenchmarkExecution.State.CANCELLED, first.completion().get(5, TimeUnit.SECONDS).state());
      assertFalse(fixture.alive());

      fixture.reset();
      assertTrue(manager.switchEngineIfAvailable(0, true));
      fixture.awaitReady();
      BenchmarkExecution second = fixture.engine.benchmarkExecution();
      assertNotSame(first, second);
      assertEquals(BenchmarkExecution.State.CANCELLED, first.snapshot().state());
      manager.forceKillAllEngines();
      assertEquals(
          BenchmarkExecution.State.CANCELLED, second.completion().get(5, TimeUnit.SECONDS).state());
      assertFalse(fixture.alive());
    }
  }

  @Test
  void eachSlotOwnsItsOutputAndReplacingOneDoesNotStopTheOther() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture primary = env.fixture("primary");
      Fixture secondary = env.fixture("secondary");
      Fixture replacement = env.fixture("replacement");
      EngineManager manager = env.manager(primary.engine, secondary.engine, replacement.engine);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      primary.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(1, false));
      secondary.awaitReady();
      BenchmarkExecution oldPrimary = primary.engine.benchmarkExecution();
      assertTrue(manager.switchEngineIfAvailable(2, true));
      replacement.awaitReady();
      assertEquals(
          BenchmarkExecution.State.CANCELLED,
          oldPrimary.completion().get(5, TimeUnit.SECONDS).state());
      assertTrue(secondary.alive());
      assertSame(replacement.engine, Lizzie.leelaz);
      assertSame(secondary.engine, Lizzie.leelaz2);
      assertEquals(2, EngineManager.currentEngineNo);
      assertEquals(1, EngineManager.currentEngineNo2);
      assertEquals("", Files.readString(secondary.path.resolve("stdin")));
      assertTrue(manager.killAllEngines());
      assertEquals(
          BenchmarkExecution.State.CANCELLED,
          secondary.engine.benchmarkExecution().completion().get(5, TimeUnit.SECONDS).state());
      assertEquals(
          BenchmarkExecution.State.CANCELLED,
          replacement.engine.benchmarkExecution().completion().get(5, TimeUnit.SECONDS).state());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void switchingBackToGtpReapsToolAndRestoresBoard(boolean fastChange) throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture tool = env.fixture("tool");
      Fixture gtp = new Fixture(directory.resolve("benchmark gtp"), false);
      EngineManager manager = env.manager(tool.engine, gtp.engine);
      Lizzie.config.fastChange = fastChange;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      tool.awaitReady();
      BenchmarkExecution old = tool.engine.benchmarkExecution();
      assertTrue(manager.switchEngineIfAvailable(1, true));
      try {
        await(() -> manager.isEngineSwitchActive(1, true));
      } catch (AssertionError failure) {
        throw new AssertionError(
            Files.readString(gtp.path.resolve("stdin"))
                + " failure="
                + manager.engineSwitchUiSnapshot(true).failureDetail(),
            failure);
      }
      assertFalse(tool.alive());
      assertEquals(BenchmarkExecution.State.CANCELLED, old.snapshot().state());
      assertTrue(Files.readString(gtp.path.resolve("stdin")).contains("clear_board"));
      assertSame(gtp.engine, Lizzie.leelaz);
      assertTrue(gtp.engine.isLoaded());
    }
  }

  @Test
  void secondaryGtpRestoreDoesNotMirrorIntoPrimaryTool() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture tool = env.fixture("primary tool");
      Fixture gtp = new Fixture(directory.resolve("secondary gtp"), false);
      EngineManager manager = env.manager(tool.engine, gtp.engine);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      tool.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(1, false));
      await(() -> manager.isEngineSwitchActive(1, false));
      tool.command("checkpoint");
      tool.awaitFile("checkpoint");
      assertEquals("", Files.readString(tool.path.resolve("stdin")));
      assertTrue(tool.alive());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void reorderedToolRunsReportsAndCompletesAtTheSlotItHoldsNow(boolean reorderWhilePending)
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = env.fixture("A");
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(a, b, c);
      if (reorderWhilePending) {
        // The selection worker starts after this EDT turn, so the refresh lands before it.
        SwingUtilities.invokeAndWait(
            () -> {
              assertTrue(manager.switchEngineIfAvailable(1, true));
              env.saveCatalog("A", "C", "B");
              refresh(manager);
            });
        b.awaitReady();
      } else {
        assertTrue(manager.switchEngineIfAvailable(1, true));
        b.awaitReady();
        env.saveCatalog("A", "C", "B");
        SwingUtilities.invokeAndWait(() -> refresh(manager));
      }

      BenchmarkExecution execution = b.engine.benchmarkExecution();
      assertSame(b.engine, manager.engineList.get(2));
      assertSame(b.engine, Lizzie.leelaz);
      assertEquals(2, EngineManager.currentEngineNo, "the selection names B's current slot");
      assertEquals(2, b.engine.currentEngineN());
      assertEquals(2, execution.engineIndex(), "the running tool follows its entry");
      assertEquals("B", b.engine.oriEnginename);
      var shown = manager.engineSwitchUiSnapshot(true);
      assertEquals(EngineManager.EngineSwitchUiPhase.TOOL, shown.phase());
      assertEquals(2, shown.targetIndex());
      assertEquals("B", shown.targetName());

      b.command("exit 0");
      assertEquals(
          BenchmarkExecution.State.SUCCEEDED,
          execution.completion().get(5, TimeUnit.SECONDS).state());
      String succeeded = Lizzie.resourceBundle.getString("Benchmark.succeeded");
      await(() -> succeeded.equals(manager.engineSwitchUiSnapshot(true).failureDetail()));
      assertEquals(2, manager.engineSwitchUiSnapshot(true).targetIndex());
      String prefix = "benchmark[main:3#" + execution.invocationId() + "] ";
      await(() -> env.console.output.toString().contains(prefix + "final stdout"));
      assertTrue(env.console.output.toString().contains(prefix + succeeded));
      assertFalse(Files.exists(a.path.resolve("pid")));
      assertFalse(Files.exists(c.path.resolve("pid")));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void removingToolEntryCancelsItsSelectionOrRunningExecution(boolean removeWhilePending)
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = env.fixture("A");
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(a, b, c);
      String cancelled = Lizzie.resourceBundle.getString("Benchmark.cancelled");
      if (removeWhilePending) {
        SwingUtilities.invokeAndWait(
            () -> {
              assertTrue(manager.switchEngineIfAvailable(1, true));
              env.saveCatalog("A", "C");
              refresh(manager);
            });
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
        assertFalse(Files.exists(b.path.resolve("pid")), "a removed entry never starts");
        assertNull(b.engine.benchmarkExecution());
        assertNull(Lizzie.leelaz);
        assertEquals(-1, EngineManager.currentEngineNo);
        var shown = manager.engineSwitchUiSnapshot(true);
        assertEquals(cancelled, shown.failureDetail());
        assertEquals(-1, shown.targetIndex(), "a removed entry has no slot to show");
        assertEquals("B", shown.targetName());
      } else {
        Lizzie.config.extraMode = ExtraMode.Double_Engine;
        assertTrue(manager.switchEngineIfAvailable(0, true));
        a.awaitReady();
        assertTrue(manager.switchEngineIfAvailable(1, false));
        b.awaitReady();
        BenchmarkExecution running = b.engine.benchmarkExecution();
        env.saveCatalog("A", "C");
        SwingUtilities.invokeAndWait(() -> refresh(manager));
        assertEquals(
            BenchmarkExecution.State.CANCELLED,
            running.completion().get(5, TimeUnit.SECONDS).state());
        assertFalse(b.alive());
        assertEquals(-1, EngineManager.currentEngineNo2);
        // The cancelled run reports its own result once it is reaped.
        await(() -> cancelled.equals(manager.engineSwitchUiSnapshot(false).failureDetail()));
        var shown = manager.engineSwitchUiSnapshot(false);
        assertEquals(-1, shown.targetIndex());
        assertEquals("B", shown.targetName());
        assertTrue(a.alive(), "the retained primary tool keeps running");
        assertSame(a.engine, Lizzie.leelaz);
        assertEquals(0, EngineManager.currentEngineNo);
      }
      assertFalse(Files.exists(c.path.resolve("pid")));
    }
  }

  @Test
  void toolSelectedWhileTheCatalogLoadsKeepsItsSelectionAcrossTheReorder() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = env.fixture("A");
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(a, b, c);
      assertTrue(manager.switchEngineIfAvailable(0, true));
      a.awaitReady();

      // The refresh loads [A, C, B] and is held while it names A, after it could read A selected.
      ((NamedEngine) a.engine).name.arm();
      env.saveCatalog("A", "C", "B");
      Background refresh = background(() -> refresh(manager));
      ((NamedEngine) a.engine).name.awaitEntered();
      SwingUtilities.invokeAndWait(() -> assertTrue(manager.switchEngineIfAvailable(1, true)));
      b.awaitReady();
      await(() -> Lizzie.leelaz == b.engine);
      ((NamedEngine) a.engine).name.release();
      refresh.finish();

      BenchmarkExecution execution = b.engine.benchmarkExecution();
      assertSame(b.engine, manager.engineList.get(2));
      assertSame(b.engine, Lizzie.leelaz);
      assertEquals(2, EngineManager.currentEngineNo, "the selection is B's slot, not A's");
      assertEquals(2, b.engine.currentEngineN());
      assertEquals(2, execution.engineIndex());
      var shown = manager.engineSwitchUiSnapshot(true);
      assertEquals(EngineManager.EngineSwitchUiPhase.TOOL, shown.phase());
      assertEquals(2, shown.targetIndex());
      assertEquals("B", shown.targetName());

      b.command("exit 0");
      String succeeded = Lizzie.resourceBundle.getString("Benchmark.succeeded");
      await(() -> succeeded.equals(manager.engineSwitchUiSnapshot(true).failureDetail()));
      assertEquals(2, manager.engineSwitchUiSnapshot(true).targetIndex());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void removingTheToolTargetWhileThePreviousEngineStopsRestoresThePreviousSelection(
      boolean previousIsTool) throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = previousIsTool ? env.fixture("A") : new Fixture(directory.resolve("A"), false);
      if (!previousIsTool) env.engines.add(a.engine);
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(a, b, c);
      NamedEngine previous = (NamedEngine) a.engine;
      String startedPid = null;
      if (previousIsTool) {
        assertTrue(manager.switchEngineIfAvailable(0, true));
        a.awaitReady();
        previous.terminal.arm();
      } else {
        // A GTP engine is quit for a tool when fast switching is off. Its start looks up its saved
        // thread policy by id, so give that lookup an empty saved list.
        Lizzie.config.fastChange = false;
        Lizzie.config.leelazConfig =
            new org.json.JSONObject().put("engine-settings-list", new org.json.JSONArray());
        // This frame has no board renderer for a first activation; arrive from a tool instead.
        assertTrue(manager.switchEngineIfAvailable(2, true));
        c.awaitReady();
        assertTrue(manager.switchEngineIfAvailable(0, true));
        try {
          await(() -> manager.isEngineSwitchActive(0, true));
        } catch (AssertionError failure) {
          var shown = manager.engineSwitchUiSnapshot(true);
          throw new AssertionError(
              shown.phase()
                  + " failure="
                  + shown.failureDetail()
                  + " stdin="
                  + (Files.exists(a.path.resolve("stdin"))
                      ? Files.readString(a.path.resolve("stdin"))
                      : "<none>"),
              failure);
        }
        startedPid = Files.readString(a.path.resolve("pid"));
        // A is analysing. The quit for the tool clears that state on the engine itself.
        a.engine.Pondering();
        previous.analysisResumed = false;
        previous.startedOnEventThread = false;
        previous.quit.arm();
      }

      SwingUtilities.invokeAndWait(() -> assertTrue(manager.switchEngineIfAvailable(1, true)));
      (previousIsTool ? previous.terminal : previous.quit).awaitEntered();
      env.saveCatalog("A", "C");
      SwingUtilities.invokeAndWait(() -> refresh(manager));
      (previousIsTool ? previous.terminal : previous.quit).release();

      if (previousIsTool) {
        String cancelled = Lizzie.resourceBundle.getString("Benchmark.cancelled");
        await(() -> "A".equals(manager.engineSwitchUiSnapshot(true).targetName()));
        var shown = manager.engineSwitchUiSnapshot(true);
        assertEquals(EngineManager.EngineSwitchUiPhase.TOOL, shown.phase());
        assertEquals(0, shown.targetIndex(), "A is shown at the slot it still holds");
        assertEquals(cancelled, shown.failureDetail(), "with the result of its stopped run");
      } else {
        String firstPid = startedPid;
        await(
            () -> {
              try {
                return aliveQuietly(a)
                    && !firstPid.equals(Files.readString(a.path.resolve("pid")))
                    && a.engine.isLoaded();
              } catch (java.io.IOException failure) {
                return false;
              }
            });
        assertTrue(a.engine.isLoaded(), "the quit GTP engine is restarted and synchronized");
        await(() -> previous.analysisResumed);
        assertFalse(
            previous.startedOnEventThread,
            "starting the engine again blocks, so it must not hold the event thread");
      }
      assertSame(a.engine, Lizzie.leelaz);
      assertEquals(0, EngineManager.currentEngineNo);
      assertFalse(Files.exists(b.path.resolve("pid")), "the removed target never starts");
      assertNull(b.engine.benchmarkExecution());
    }
  }

  @Test
  void removedToolReportsItsOwnFinalResultWithoutAReusedSlotNumber() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = env.fixture("A");
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(a, b, c);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      a.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(1, false));
      b.awaitReady();
      BenchmarkExecution running = b.engine.benchmarkExecution();

      // B's process has exited, but a child still holds its output: cancel cannot change it now.
      b.command("detach");
      await(() -> !aliveQuietly(b));
      assertFalse(running.completion().isDone());
      env.saveCatalog("A", "C");
      SwingUtilities.invokeAndWait(() -> refresh(manager));
      String runningText = Lizzie.resourceBundle.getString("Benchmark.running");
      assertEquals(runningText, manager.engineSwitchUiSnapshot(false).failureDetail());
      Files.writeString(b.path.resolve("release"), "");

      assertEquals(
          BenchmarkExecution.State.SUCCEEDED,
          running.completion().get(5, TimeUnit.SECONDS).state());
      String succeeded = Lizzie.resourceBundle.getString("Benchmark.succeeded");
      await(() -> succeeded.equals(manager.engineSwitchUiSnapshot(false).failureDetail()));
      var shown = manager.engineSwitchUiSnapshot(false);
      assertEquals(-1, shown.targetIndex());
      assertEquals("B", shown.targetName());
      // Slot 2 belongs to C now; B's remaining output and result name no slot.
      String prefix = "benchmark[secondary#" + running.invocationId() + "] ";
      await(() -> env.console.output.toString().contains(prefix + succeeded));
      String console = env.console.output.toString();
      assertTrue(console.contains(prefix + "detached stdout"));
      assertFalse(console.contains("benchmark[secondary:2#" + running.invocationId() + "] detached"));
      assertTrue(a.alive());
    }
  }

  @Test
  void removingAToolWhileItsResultWaitsToBePublishedKeepsThatResult() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = env.fixture("A");
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(a, b, c);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      a.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(1, false));
      b.awaitReady();
      BenchmarkExecution running = b.engine.benchmarkExecution();

      // Hold the selection lock so B's settled result waits to publish while B is removed.
      synchronized (engineSelectionStateLock()) {
        b.command("exit 0");
        await(() -> running.completion().isDone());
        env.saveCatalog("A", "C");
        refresh(manager);
      }

      String succeeded = Lizzie.resourceBundle.getString("Benchmark.succeeded");
      await(() -> succeeded.equals(manager.engineSwitchUiSnapshot(false).failureDetail()));
      var shown = manager.engineSwitchUiSnapshot(false);
      assertEquals(-1, shown.targetIndex());
      assertEquals("B", shown.targetName());
      assertEquals(-1, EngineManager.currentEngineNo2);
    }
  }

  @Test
  void aRefreshOnTheEventThreadDuringAnotherRefreshReturnsAndIsPublishedAfterIt()
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = env.fixture("A");
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(a, b, c);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      a.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(1, false));
      b.awaitReady();
      BenchmarkExecution running = b.engine.benchmarkExecution();
      List<Leelaz> published = manager.engineList;

      // The first refresh reads [A, C, B] and is held while it names A.
      ((NamedEngine) a.engine).name.arm();
      env.saveCatalog("A", "C", "B");
      Background older = background(() -> refresh(manager));
      ((NamedEngine) a.engine).name.awaitEntered();
      // The settings screen saves [C, A, B]. Its refresh must neither wait on the event thread nor
      // publish before the first one has.
      env.saveCatalog("C", "A", "B");
      SwingUtilities.invokeAndWait(() -> refresh(manager));
      assertTrue(older.isAlive(), "the event thread returned while the first refresh is held");
      assertSame(published, manager.engineList, "nothing is published in the middle of a refresh");
      ((NamedEngine) a.engine).name.release();
      older.finish();
      SwingUtilities.invokeAndWait(() -> {});

      assertSame(c.engine, manager.engineList.get(0), "the catalog saved last is published last");
      assertSame(a.engine, manager.engineList.get(1));
      assertSame(b.engine, manager.engineList.get(2));
      assertEquals(1, EngineManager.currentEngineNo);
      assertEquals(2, EngineManager.currentEngineNo2);
      assertFalse(running.completion().isDone(), "both catalogs keep B, so B keeps running");
      assertTrue(b.alive());
      var shown = manager.engineSwitchUiSnapshot(false);
      assertEquals(2, shown.targetIndex());
      assertEquals(Lizzie.resourceBundle.getString("Benchmark.running"), shown.failureDetail());
    }
  }

  @Test
  void aReorderJustBeforeTheRestoredEngineRestartsRestartsThatEngineAtItsNewSlot()
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = new Fixture(directory.resolve("A"), false);
      env.engines.add(a.engine);
      Fixture b = env.fixture("B");
      Fixture c = new Fixture(directory.resolve("C"), false);
      env.engines.add(c.engine);
      Fixture d = env.fixture("D");
      EngineManager manager = env.catalogManager(a, b, c, d);
      NamedEngine previous = (NamedEngine) a.engine;
      Lizzie.config.fastChange = false;
      Lizzie.config.leelazConfig =
          new org.json.JSONObject().put("engine-settings-list", new org.json.JSONArray());
      // This frame has no board renderer for a first activation; arrive from a tool instead.
      assertTrue(manager.switchEngineIfAvailable(3, true));
      d.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(0, true));
      await(() -> manager.isEngineSwitchActive(0, true));
      String firstPid = Files.readString(a.path.resolve("pid"));
      a.engine.Pondering();
      previous.analysisResumed = false;
      previous.quit.arm();

      // B is removed while A is quit for it, so A is restored and restarted.
      SwingUtilities.invokeAndWait(() -> assertTrue(manager.switchEngineIfAvailable(1, true)));
      previous.quit.awaitEntered();
      env.saveCatalog("A", "C", "D");
      SwingUtilities.invokeAndWait(() -> refresh(manager));
      // The restore has found A selected at slot 0; C takes that slot before A restarts.
      env.board.setupCheck.arm();
      previous.quit.release();
      env.board.setupCheck.awaitEntered();
      env.saveCatalog("C", "A", "D");
      SwingUtilities.invokeAndWait(() -> refresh(manager));
      assertEquals(1, EngineManager.currentEngineNo);
      env.board.setupCheck.release();

      await(
          () ->
              aliveQuietly(a)
                  && !firstPid.equals(readQuietly(a.path.resolve("pid")))
                  && a.engine.isLoaded());
      await(() -> previous.analysisResumed);
      assertSame(a.engine, Lizzie.leelaz, "the restore restarts the engine it found selected");
      assertEquals(1, EngineManager.currentEngineNo);
      assertFalse(Files.exists(c.path.resolve("pid")), "the entry now in A's old slot stays off");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void removingTheSecondaryAndItsToolTargetTogetherRestoresNothing(boolean fastChange)
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture p = env.fixture("P");
      Fixture a = new Fixture(directory.resolve("A"), false);
      env.engines.add(a.engine);
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(p, a, b, c);
      NamedEngine previous = (NamedEngine) a.engine;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config.fastChange = fastChange;
      Lizzie.config.leelazConfig =
          new org.json.JSONObject().put("engine-settings-list", new org.json.JSONArray());
      assertTrue(manager.switchEngineIfAvailable(0, true));
      p.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(1, false));
      await(() -> manager.isEngineSwitchActive(1, false));
      a.engine.Pondering();
      previous.ponderRequested = false;
      Gate stop = fastChange ? previous.pause : previous.quit;
      stop.arm();

      SwingUtilities.invokeAndWait(() -> assertTrue(manager.switchEngineIfAvailable(2, false)));
      stop.awaitEntered();
      // A and its target B are removed together while A is stopped for B.
      env.saveCatalog("P", "C");
      SwingUtilities.invokeAndWait(() -> refresh(manager));
      assertEquals(-1, EngineManager.currentEngineNo2);
      stop.release();

      await(() -> !selectionWorkerRunning(false));
      SwingUtilities.invokeAndWait(() -> {});
      SwingUtilities.invokeAndWait(() -> {});
      assertFalse(previous.ponderRequested, "a removed engine is not asked to analyse again");
      assertEquals(List.of(), env.synchronizationFailures, "nor restarted, which would fail");
      assertFalse(Files.exists(b.path.resolve("pid")), "the removed target never starts");
    }
  }

  @Test
  void aToolTargetRemovedWhileItsSwitchIsFirstShownHandsTheSideBackToTheRunningTool()
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = env.fixture("A");
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(a, b, c);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      a.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(2, false));
      c.awaitReady();
      BenchmarkExecution running = c.engine.benchmarkExecution();

      // The switch to B is registered, and is held while its SWITCHING state is first drawn.
      env.switchingShown.arm();
      SwingUtilities.invokeLater(() -> manager.switchEngineIfAvailable(1, false));
      env.switchingShown.awaitEntered();
      env.saveCatalog("A", "C");
      refresh(manager);
      env.switchingShown.release();

      String runningText = Lizzie.resourceBundle.getString("Benchmark.running");
      await(
          () -> {
            var shown = manager.engineSwitchUiSnapshot(false);
            return shown.phase() == EngineManager.EngineSwitchUiPhase.TOOL
                && "C".equals(shown.targetName())
                && runningText.equals(shown.failureDetail());
          });
      assertEquals(1, manager.engineSwitchUiSnapshot(false).targetIndex());
      assertSame(c.engine, Lizzie.leelaz2);
      assertTrue(c.alive(), "C was never stopped for the removed target");

      c.command("exit 0");
      assertEquals(
          BenchmarkExecution.State.SUCCEEDED,
          running.completion().get(5, TimeUnit.SECONDS).state());
      String succeeded = Lizzie.resourceBundle.getString("Benchmark.succeeded");
      await(() -> succeeded.equals(manager.engineSwitchUiSnapshot(false).failureDetail()));
      assertEquals("C", manager.engineSwitchUiSnapshot(false).targetName());
      assertFalse(Files.exists(b.path.resolve("pid")), "the removed target never starts");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void cancellingAToolSwitchBeforeItStopsTheRunningToolKeepsThatToolsReports(boolean removeTarget)
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = env.fixture("A");
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(a, b, c);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      a.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(2, false));
      c.awaitReady();
      BenchmarkExecution running = c.engine.benchmarkExecution();

      // The selection worker starts after this EDT turn, so C is untouched when B is cancelled,
      // by removing B or by stopping the pending selection.
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(manager.switchEngineIfAvailable(1, false));
            if (removeTarget) {
              env.saveCatalog("A", "C");
              refresh(manager);
            } else {
              manager.killThisEngines2();
            }
          });
      SwingUtilities.invokeAndWait(() -> {});
      SwingUtilities.invokeAndWait(() -> {});
      int slot = removeTarget ? 1 : 2;
      var shown = manager.engineSwitchUiSnapshot(false);
      assertEquals(EngineManager.EngineSwitchUiPhase.TOOL, shown.phase());
      assertEquals("C", shown.targetName(), "C is still the selection and still running");
      assertEquals(slot, shown.targetIndex());
      assertEquals(Lizzie.resourceBundle.getString("Benchmark.running"), shown.failureDetail());
      assertSame(c.engine, Lizzie.leelaz2);
      assertTrue(c.alive());
      assertFalse(Files.exists(b.path.resolve("pid")), "the cancelled target never starts");

      c.command("exit 0");
      assertEquals(
          BenchmarkExecution.State.SUCCEEDED,
          running.completion().get(5, TimeUnit.SECONDS).state());
      String succeeded = Lizzie.resourceBundle.getString("Benchmark.succeeded");
      await(() -> succeeded.equals(manager.engineSwitchUiSnapshot(false).failureDetail()));
      assertEquals("C", manager.engineSwitchUiSnapshot(false).targetName());
      assertEquals(slot, manager.engineSwitchUiSnapshot(false).targetIndex());
    }
  }

  @Test
  void reorderingAfterAToolFinishesKeepsItsResultInTheEngineMenu() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture a = env.fixture("A");
      Fixture b = env.fixture("B");
      Fixture c = env.fixture("C");
      EngineManager manager = env.catalogManager(a, b, c);
      SwingUtilities.invokeAndWait(() -> Menu.engineMenu = new JFontMenu());
      assertTrue(manager.switchEngineIfAvailable(1, true));
      b.awaitReady();
      BenchmarkExecution execution = b.engine.benchmarkExecution();
      b.command("exit 0");
      assertEquals(
          BenchmarkExecution.State.SUCCEEDED,
          execution.completion().get(5, TimeUnit.SECONDS).state());
      String succeeded = Lizzie.resourceBundle.getString("Benchmark.succeeded");
      await(() -> succeeded.equals(manager.engineSwitchUiSnapshot(true).failureDetail()));
      SwingUtilities.invokeAndWait(() -> {});
      assertEquals("2:B:" + succeeded, Menu.engineMenu.getText());

      // The finished run sends no further report, so the refresh alone must keep its result.
      env.saveCatalog("A", "C", "B");
      SwingUtilities.invokeAndWait(() -> refresh(manager));
      SwingUtilities.invokeAndWait(() -> {});
      assertEquals(2, EngineManager.currentEngineNo);
      assertEquals("3:B:" + succeeded, Menu.engineMenu.getText());
    }
  }

  private static Object engineSelectionStateLock() throws Exception {
    Field field = EngineManager.class.getDeclaredField("ENGINE_SELECTION_STATE_LOCK");
    field.setAccessible(true);
    return field.get(null);
  }

  private static boolean aliveQuietly(Fixture fixture) {
    try {
      return fixture.alive();
    } catch (Exception failure) {
      return true;
    }
  }

  private static Background background(Runnable body) {
    return new Background(body);
  }

  /** Runs off the event thread; {@link #finish} fails the test unless it returned normally. */
  private static final class Background {
    private final Thread thread;
    private final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
        new java.util.concurrent.atomic.AtomicReference<>();

    private Background(Runnable body) {
      thread =
          new Thread(
              () -> {
                try {
                  body.run();
                } catch (Throwable thrown) {
                  failure.set(thrown);
                }
              },
              "benchmark-test-refresh");
      thread.setDaemon(true);
      thread.start();
    }

    boolean isAlive() {
      return thread.isAlive();
    }

    void finish() throws InterruptedException {
      thread.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(thread.isAlive(), "the refresh did not return");
      if (failure.get() != null) throw new AssertionError("the refresh failed", failure.get());
    }
  }

  private static String readQuietly(Path file) {
    try {
      return Files.exists(file) ? Files.readString(file) : "";
    } catch (java.io.IOException failure) {
      return "";
    }
  }

  private static boolean selectionWorkerRunning(boolean main) {
    String prefix = "lizzie-engine-switch-" + (main ? "primary-" : "secondary-");
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> thread.getName().startsWith(prefix));
  }

  private static void refresh(EngineManager manager) {
    try {
      manager.refreshEngineCatalog();
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private static final String CHILD =
      """
      #!/usr/bin/python3
      import os, sys, pathlib, select, time
      p = pathlib.Path(__file__).parent
      (p/'pid').write_text(str(os.getpid()))
      (p/'argv').write_text(repr(sys.argv))
      (p/'stdin').write_text('')
      if sys.argv[1] == 'gtp':
          for line in sys.stdin:
              with (p/'stdin').open('a') as f: f.write(line)
              words = line.split()
              ident = words.pop(0) if words and words[0].isdigit() else ''
              cmd = words[0] if words else ''
              answer = 'ControlledGTP' if cmd == 'name' else '1.0' if cmd == 'version' else ''
              if cmd == 'list_commands': answer = 'name\\nversion\\nlist_commands\\nkomi\\nboardsize\\nclear_board\\nplay\\nquit'
              print('=' + ident + ' ' + answer + '\\n', flush=True)
              if cmd == 'quit': break
          sys.exit(0)
      print('raw stdout: unrecognized progress', flush=True)
      print('raw stderr: loading', file=sys.stderr, flush=True)
      (p/'ready').touch()
      eof = False
      while True:
          readable, _, _ = select.select([0], [], [], 0.01)
          if readable:
              data = os.read(0, 4096)
              if data:
                  with (p/'stdin').open('ab') as f: f.write(data)
          c = p/'control'
          if not c.exists(): continue
          command = c.read_text()
          if not command: continue
          c.unlink()
          if command == 'checkpoint': (p/'checkpoint').touch()
          if command == 'eof':
              os.close(1)
              os.close(2)
              eof = True
              (p/'eof').touch()
          if command == 'detach':
              # The process exits while a child keeps its output open until 'release' appears.
              if os.fork() == 0:
                  while not (p/'release').exists(): time.sleep(0.01)
                  print('detached stdout', flush=True)
                  os._exit(0)
              os._exit(0)
          if command.startswith('exit '):
              if not eof:
                  print('final stdout', flush=True)
                  print('final stderr', file=sys.stderr, flush=True)
              sys.exit(int(command.split()[1]))
      """;

  private static final class Fixture {
    final Path path;
    final Leelaz engine;

    Fixture(Path path) throws Exception {
      this(path, true);
    }

    Fixture(Path path, boolean benchmark) throws Exception {
      this.path = Files.createDirectories(path);
      Path executable = Files.writeString(path.resolve("katago"), CHILD);
      assertTrue(executable.toFile().setExecutable(true));
      engine =
          new NamedEngine(
              "\""
                  + executable
                  + "\" "
                  + (benchmark ? "benchmark" : "gtp")
                  + " -model \"model with spaces.bin.gz\""
                  + " -threads 3 -override-config numSearchThreads=3,logDir=benchmark/value");
    }

    void command(String value) throws Exception {
      Files.writeString(path.resolve("control"), value);
    }

    void awaitFile(String name) throws Exception {
      await(() -> Files.exists(path.resolve(name)));
    }

    void awaitReady() throws Exception {
      awaitFile("ready");
    }

    boolean alive() throws Exception {
      Path pid = path.resolve("pid");
      return Files.exists(pid)
          && ProcessHandle.of(Long.parseLong(Files.readString(pid)))
              .map(ProcessHandle::isAlive)
              .orElse(false);
    }

    void reset() throws Exception {
      for (String name : List.of("ready", "pid", "eof", "control", "checkpoint", "release"))
        Files.deleteIfExists(path.resolve(name));
    }
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), "controlled process did not reach the requested boundary");
  }

  /** Holds one thread at a test boundary once; it never blocks longer than five seconds. */
  private static final class Gate {
    private final java.util.concurrent.atomic.AtomicBoolean armed =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.CountDownLatch entered =
        new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch released =
        new java.util.concurrent.CountDownLatch(1);

    void arm() {
      armed.set(true);
    }

    void pass() {
      if (!armed.compareAndSet(true, false)) return;
      entered.countDown();
      try {
        released.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    void awaitEntered() throws InterruptedException {
      assertTrue(entered.await(5, TimeUnit.SECONDS), "the thread did not reach the gate");
    }

    void release() {
      released.countDown();
    }
  }

  private static final class NamedEngine extends Leelaz {
    /** When set, names come from the saved catalog as production resolves them. */
    private volatile List<EngineData> savedCatalog;
    /** Holds the next catalog-name lookup, the tool's terminal callback, a GTP quit or pause. */
    final Gate name = new Gate();
    final Gate terminal = new Gate();
    final Gate quit = new Gate();
    final Gate pause = new Gate();

    NamedEngine(String command) throws java.io.IOException {
      super(command);
    }

    @Override
    public String getEngineName(int index) {
      this.name.pass();
      List<EngineData> catalog = savedCatalog;
      if (catalog == null) return "Controlled KataGo";
      String name = index < 0 ? "" : savedCatalogEntry(catalog, index, savedEntryId).name;
      oriEnginename = name;
      currentEnginename = name;
      return name;
    }

    @Override
    void onBenchmarkTerminal(BenchmarkExecution execution) {
      terminal.pass();
      super.onBenchmarkTerminal(execution);
    }

    @Override
    public void normalQuit() {
      quit.pass();
      super.normalQuit();
    }

    @Override
    public void nameCmdfornoponder() {
      pause.pass();
      super.nameCmdfornoponder();
    }

    /** Set when analysis is asked to start by a direct request. */
    volatile boolean ponderRequested;

    @Override
    public void ponder() {
      ponderRequested = true;
      super.ponder();
    }

    void releaseGates() {
      name.release();
      terminal.release();
      quit.release();
      pause.release();
    }

    /** Set when a start runs on the event thread, and when analysis is asked to resume. */
    volatile boolean startedOnEventThread;
    volatile boolean analysisResumed;

    @Override
    public void startEngine(int index) throws java.io.IOException {
      if (SwingUtilities.isEventDispatchThread()) startedOnEventThread = true;
      super.startEngine(index);
    }

    @Override
    public boolean ponderIfAnalysisControlAllows() {
      analysisResumed = true;
      return super.ponderIfAnalysisControlAllows();
    }

    @Override
    long engineStartupSynchronizationTimeoutMillis() {
      return isBenchmark() ? 1 : 3000;
    }
  }

  private static final class RecordingConsole extends GtpConsolePane {
    StringBuffer output;

    private RecordingConsole() {
      super(null);
    }

    @Override
    public void addLine(String line) {
      output.append(line);
    }
  }

  private static final class QuietFrame extends LizzieFrame {
    @Override
    public void refresh() {}

    @Override
    public void reSetLoc() {}

    @Override
    public void resetTitle() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }
  }

  private static final class Environment implements AutoCloseable {
    final Config oldConfig = Lizzie.config;
    final Leelaz oldPrimary = Lizzie.leelaz, oldSecondary = Lizzie.leelaz2;
    final EngineManager oldManager = Lizzie.engineManager;
    final Board oldBoard = Lizzie.board;
    final LizzieFrame oldFrame = Lizzie.frame;
    final Menu oldMenu = LizzieFrame.menu;
    final JFontMenu oldEngineMenu = Menu.engineMenu;
    final GtpConsolePane oldConsole = Lizzie.gtpConsole;
    final int oldWidth = Board.boardWidth, oldHeight = Board.boardHeight;
    final int oldIndex = EngineManager.currentEngineNo, oldIndex2 = EngineManager.currentEngineNo2;
    final boolean oldEmpty = EngineManager.isEmpty;
    final Path root;
    final RecordingConsole console;
    final List<Leelaz> engines = new ArrayList<>();
    final List<EngineData> savedCatalog = new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.Map<String, EngineData> savedEntries = new java.util.HashMap<>();
    final GatedBoard board;
    /** Holds the event thread while it first draws a secondary SWITCHING state. */
    final Gate switchingShown = new Gate();
    final List<Leelaz> synchronizationFailures = new java.util.concurrent.CopyOnWriteArrayList<>();

    Environment(Path root) throws Exception {
      this.root = root;
      Lizzie.config = ConfigTestHelper.createForTests(root);
      Lizzie.config.extraMode = ExtraMode.Normal;
      Lizzie.config.fastChange = true;
      Lizzie.config.autoCheckEngineAlive = false;
      Lizzie.setPrimaryEngine(null);
      Lizzie.leelaz2 = null;
      Lizzie.frame = null;
      LizzieFrame.menu = null;
      Menu.engineMenu = null;
      Board.boardWidth = Board.boardHeight = 19;
      Zobrist.init();
      board = allocate(GatedBoard.class);
      board.setupCheck = new Gate();
      board.startStonelist = new ArrayList<>();
      board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
      Lizzie.board = board;
      Lizzie.frame = allocate(QuietFrame.class);
      console = allocate(RecordingConsole.class);
      console.output = new StringBuffer();
      Lizzie.gtpConsole = console;
      EngineManager.currentEngineNo = EngineManager.currentEngineNo2 = -1;
      EngineManager.isEmpty = true;
    }

    Fixture fixture(String name) throws Exception {
      Fixture fixture = new Fixture(root.resolve(name));
      engines.add(fixture.engine);
      return fixture;
    }

    EngineManager manager(Leelaz... catalog) {
      for (Leelaz engine : catalog) if (!engines.contains(engine)) engines.add(engine);
      EngineManager manager = new EngineManager(new ArrayList<>(List.of(catalog)));
      Lizzie.setEngineManager(manager);
      return manager;
    }

    /** A manager whose refresh reads saved entries named after each fixture directory. */
    EngineManager catalogManager(Fixture... fixtures) {
      List<Leelaz> catalog = new ArrayList<>();
      for (Fixture fixture : fixtures) {
        NamedEngine engine = (NamedEngine) fixture.engine;
        EngineData data = new EngineData();
        data.id = data.name = fixture.path.getFileName().toString();
        data.commands = engine.oriEngineCommand;
        data.width = engine.oriWidth;
        data.height = engine.oriHeight;
        data.ip = engine.ip;
        data.port = engine.port;
        data.userName = engine.userName;
        engine.savedEntryId = data.id;
        engine.savedCatalog = savedCatalog;
        savedEntries.put(data.id, data);
        savedCatalog.add(data);
        catalog.add(engine);
        // Catalog creation names each entry from its saved slot.
        engine.getEngineName(catalog.size() - 1);
      }
      EngineManager manager =
          new EngineManager(catalog) {
            @Override
            protected ArrayList<EngineData> loadEngineCatalogSnapshot() {
              return new ArrayList<>(savedCatalog);
            }

            @Override
            protected void showEngineSynchronizationFailure(Leelaz engine, Throwable failure) {
              synchronizationFailures.add(engine);
              super.showEngineSynchronizationFailure(engine, failure);
            }

            /** Stands in for the engine menu, which labels the shown slot, name and status. */
            @Override
            protected void renderEngineSwitchUiState(EngineSwitchUiSnapshot snapshot) {
              if (!snapshot.isMain() && snapshot.phase() == EngineSwitchUiPhase.SWITCHING) {
                switchingShown.pass();
              }
              super.renderEngineSwitchUiState(snapshot);
              if (snapshot.isMain() && Menu.engineMenu != null) {
                Menu.engineMenu.setText(
                    (snapshot.targetIndex() + 1)
                        + ":"
                        + snapshot.targetName()
                        + ":"
                        + snapshot.failureDetail());
              }
            }
          };
      Lizzie.setEngineManager(manager);
      return manager;
    }

    void saveCatalog(String... ids) {
      List<EngineData> next = new ArrayList<>();
      for (String id : ids) next.add(savedEntries.get(id));
      savedCatalog.clear();
      savedCatalog.addAll(next);
    }

    @Override
    public void close() throws Exception {
      try {
        for (Leelaz engine : engines) {
          if (engine instanceof NamedEngine named) named.releaseGates();
        }
        board.setupCheck.release();
        switchingShown.release();
        // A detached child of a failed test must not keep a tool's output open.
        try (var files = Files.walk(root)) {
          files
              .filter(path -> path.getFileName().toString().equals("katago"))
              .forEach(path -> touch(path.resolveSibling("release")));
        }
        for (Leelaz engine : engines) {
          engine.cancelBenchmark();
          if (engine.benchmarkExecution() != null)
            engine.benchmarkExecution().completion().get(5, TimeUnit.SECONDS);
          else if (engine.isStarted()) engine.forceQuit();
        }
        SwingUtilities.invokeAndWait(() -> {});
      } finally {
        Lizzie.config = oldConfig;
        Lizzie.setPrimaryEngine(oldPrimary);
        Lizzie.leelaz2 = oldSecondary;
        Lizzie.setEngineManager(oldManager);
        Lizzie.board = oldBoard;
        Lizzie.frame = oldFrame;
        LizzieFrame.menu = oldMenu;
        Menu.engineMenu = oldEngineMenu;
        Lizzie.gtpConsole = oldConsole;
        Board.boardWidth = oldWidth;
        Board.boardHeight = oldHeight;
        Zobrist.init();
        EngineManager.currentEngineNo = oldIndex;
        EngineManager.currentEngineNo2 = oldIndex2;
        EngineManager.isEmpty = oldEmpty;
      }
    }
  }

  /** Holds the selection worker once at its next setup-mode check, which the restore makes. */
  private static final class GatedBoard extends Board {
    volatile Gate setupCheck;

    @Override
    public boolean isSetupMode() {
      Gate gate = setupCheck;
      if (gate != null && Thread.currentThread().getName().startsWith("lizzie-engine-switch-")) {
        gate.pass();
      }
      return super.isSetupMode();
    }
  }

  private static void touch(Path path) {
    try {
      if (Files.isDirectory(path.getParent())) Files.writeString(path, "");
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
