package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameSnapshotFixtures;
import featurecat.lizzie.enginegame.GameActivity;
import featurecat.lizzie.enginegame.RunState;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LeelazAnalysisParameterAdmissionTest {
  private static final String REQUESTED = "1.25";
  private static final double FIRST_PDA = 0.5;
  private static final double FIRST_BEFORE_GAME = -0.5;
  private static final double SECOND_PDA = -0.75;
  private static final double SECOND_BEFORE_GAME = 0.75;

  @TempDir Path root;

  @ParameterizedTest(name = "{0}, occupied endpoint: {1}")
  @CsvSource({
    "PDA,PRIMARY",
    "WRN,PRIMARY",
    "PDA,MIRRORED_PRIMARY",
    "WRN,MIRRORED_PRIMARY",
    "PDA,MIRROR",
    "WRN,MIRROR"
  })
  void rejectedParameterPreservesBothEndpointsAndTheSameValueCanBeRetried(
      Parameter parameter, OccupiedEndpoint occupiedEndpoint) throws Exception {
    try (Environment env = new Environment()) {
      boolean mirrored = occupiedEndpoint != OccupiedEndpoint.PRIMARY;
      if (mirrored) env.enableComparison();
      Leelaz occupied = occupiedEndpoint == OccupiedEndpoint.MIRROR ? env.second : env.first;

      try (var reservation = occupied.beginExclusiveGtpLifecycleReservation()) {
        assertNotNull(reservation, "the test must own a real lifecycle reservation");
        assertFalse(parameter.apply(env.first, REQUESTED));
        env.assertOriginalPda();
        env.assertCommands(List.of(), List.of());
      }

      // A rejected command must not remain queued until the reservation is released.
      env.assertCommands(List.of(), List.of());
      env.assertOriginalPda();
      assertTrue(parameter.apply(env.first, REQUESTED));
      List<String> expected = List.of(parameter.command(REQUESTED));
      env.assertCommands(expected, mirrored ? expected : List.of());
      if (parameter == Parameter.PDA) {
        assertPda(env.first, 1.25, 1.25);
      } else {
        env.assertOriginalPda();
      }
    }
  }

  @ParameterizedTest(name = "GENMOVE starting: {0}")
  @ValueSource(booleans = {false, true})
  void occupiedSecondGameParticipantRejectsBothChangesAndAllowsTheSameRetry(boolean starting)
      throws Exception {
    try (Environment env = new Environment()) {
      env.publishGenmove(starting);

      try (var reservation = env.second.beginExclusiveGtpLifecycleReservation()) {
        assertNotNull(reservation, "the second participant must own the reservation");
        assertFalse(env.first.trySetPdaForGame(REQUESTED, env.second));
        env.assertOriginalPda();
        env.assertCommands(List.of(), List.of());
      }

      env.assertCommands(List.of(), List.of());
      env.assertOriginalPda();
      assertTrue(env.first.trySetPdaForGame(REQUESTED, env.second));
      List<String> expected = List.of(Parameter.PDA.command(REQUESTED));
      env.assertCommands(expected, expected);
      assertPda(env.first, 1.25, 1.25);
      assertPda(env.second, 1.25, 1.25);
    }
  }

  private enum Parameter {
    PDA("playoutDoublingAdvantage"),
    WRN("analysisWideRootNoise");

    private final String name;

    Parameter(String name) {
      this.name = name;
    }

    boolean apply(Leelaz engine, String value) {
      return this == PDA ? engine.trySetPda(value) : engine.trySetAnalysisWideRootNoise(value);
    }

    String command(String value) {
      return "kata-set-param " + name + " " + value;
    }
  }

  private enum OccupiedEndpoint {
    PRIMARY,
    MIRRORED_PRIMARY,
    MIRROR
  }

  private static void assertPda(Leelaz engine, double current, double beforeGame) throws Exception {
    assertEquals(current, engine.pda);
    Field remembered = Leelaz.class.getDeclaredField("pdaBeforeGame");
    remembered.setAccessible(true);
    assertEquals(beforeGame, remembered.getDouble(engine));
  }

  private static void seedPda(Leelaz engine, double current, double beforeGame) throws Exception {
    engine.pda = current;
    Field remembered = Leelaz.class.getDeclaredField("pdaBeforeGame");
    remembered.setAccessible(true);
    remembered.setDouble(engine, beforeGame);
  }

  private final class Environment implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final Leelaz previousPrimary = Lizzie.leelaz;
    private final Leelaz previousSecondary = Lizzie.leelaz2;
    private final EngineManager previousManager = Lizzie.engineManager;
    private final EngineGameSnapshot previousGame = Lizzie.engineGame.current();
    private final boolean previousEmpty = EngineManager.isEmpty;
    private final GtpConsolePane previousConsole = Lizzie.gtpConsole;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final Menu previousMenu = LizzieFrame.menu;
    final Leelaz first;
    final Leelaz second;
    final ExactSnapshotRestoreProtocolFixture.Transport firstOutput;
    final ExactSnapshotRestoreProtocolFixture.Transport secondOutput;

    Environment() throws Exception {
      Lizzie.config = ConfigTestHelper.createForTests(root);
      Lizzie.config.uiConfig = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.config = new JSONObject();
      Lizzie.config.extraMode = ExtraMode.Normal;
      Lizzie.frame = null;
      LizzieFrame.menu = null;
      Lizzie.gtpConsole = allocate(QuietConsole.class);
      Lizzie.engineManager = null;
      Lizzie.leelaz2 = null;
      EngineGameSnapshotFixtures.publishIdle();

      first = new Leelaz("first-cmd");
      second = new Leelaz("second-cmd");
      firstOutput = ExactSnapshotRestoreProtocolFixture.install(
          first, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      secondOutput = ExactSnapshotRestoreProtocolFixture.install(
          second, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      first.started = first.isLoaded = first.isKatago = true;
      second.started = second.isLoaded = second.isKatago = true;
      seedPda(first, FIRST_PDA, FIRST_BEFORE_GAME);
      seedPda(second, SECOND_PDA, SECOND_BEFORE_GAME);
      Lizzie.setPrimaryEngine(first);
      EngineManager.isEmpty = false;
    }

    void enableComparison() {
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      Lizzie.leelaz2 = second;
    }

    void publishGenmove(boolean starting) {
      Lizzie.engineManager = new EngineManager(List.of(first, second));
      var playing = (EngineGameSnapshot.BatchActive)
          EngineGameSnapshotFixtures.playing(EngineGamePlayMode.GENMOVE, RunState.PAUSED);
      Lizzie.engineGame.publishSnapshotForTest(starting
          ? new EngineGameSnapshot.BatchActive(
              playing.batch(), new GameActivity.Starting(playing.view()))
          : playing);
    }

    void assertOriginalPda() throws Exception {
      assertPda(first, FIRST_PDA, FIRST_BEFORE_GAME);
      assertPda(second, SECOND_PDA, SECOND_BEFORE_GAME);
    }

    void assertCommands(List<String> expectedFirst, List<String> expectedSecond) {
      assertEquals(expectedFirst, firstOutput.commands());
      assertEquals(expectedSecond, secondOutput.commands());
    }

    @Override
    public void close() throws Exception {
      try {
        firstOutput.close();
        secondOutput.close();
      } finally {
        // Drain the reservation's deferred toolbar refresh before restoring shared test state.
        SwingUtilities.invokeAndWait(() -> {
          Lizzie.config = previousConfig;
          Lizzie.engineManager = previousManager;
          Lizzie.leelaz2 = previousSecondary;
          Lizzie.setPrimaryEngine(previousPrimary);
          Lizzie.engineGame.publishSnapshotForTest(previousGame);
          EngineManager.isEmpty = previousEmpty;
          Lizzie.gtpConsole = previousConsole;
          Lizzie.frame = previousFrame;
          LizzieFrame.menu = previousMenu;
        });
      }
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private static final class QuietConsole extends GtpConsolePane {
    private QuietConsole() {
      super(null);
    }

    @Override
    public void addLine(String line) {}
  }
}
