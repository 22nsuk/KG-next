package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameSnapshotFixtures;
import featurecat.lizzie.enginegame.RunState;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SetKataPDAParameterTest {
  @TempDir Path root;

  static Stream<Arguments> staleEditors() {
    return Stream.of("replacement", "reselection", "restart", "unloaded", "unsupported", "missing",
            "mirror-replacement", "mirror-restart")
        .flatMap(change -> Stream.of("dynamic", "static", "off").map(mode -> Arguments.of(change, mode)));
  }

  @ParameterizedTest
  @MethodSource("staleEditors")
  void changedTargetsOnlySavePreferences(String change, String mode) throws Exception {
    try (Fixture f = new Fixture(root)) {
      SwingUtilities.invokeAndWait(() -> {
        if (change.startsWith("mirror")) f.compareWith(f.other);
        f.open(mode);
        switch (change) {
          case "replacement" -> Lizzie.setPrimaryEngine(f.other);
          case "reselection" -> { Lizzie.setPrimaryEngine(f.other); Lizzie.setPrimaryEngine(f.engine); }
          case "restart" -> f.engine.restartReader();
          case "unloaded" -> f.engine.isLoaded = false;
          case "unsupported" -> f.engine.isKataGoPda = false;
          case "missing" -> Lizzie.setPrimaryEngine(null);
          case "mirror-replacement" -> Lizzie.leelaz2 = f.replacement;
          case "mirror-restart" -> f.other.restartReader();
          default -> fail(change);
        }
        f.apply();
        f.assertPreferences(mode);
        f.assertRuntimeUnchanged();
      });
      f.assertNoCommands();
    }
  }

  static Stream<Arguments> liveEditors() {
    return Stream.of(false, true).flatMap(mirror ->
        Stream.of("dynamic", "static", "off").map(mode -> Arguments.of(mirror, mode)));
  }

  @ParameterizedTest
  @MethodSource("liveEditors")
  void liveTargetsKeepTheCustomRangeAndComparisonCommands(boolean mirror, String mode) throws Exception {
    try (Fixture f = new Fixture(root)) {
      SwingUtilities.invokeAndWait(() -> {
        if (mirror) f.compareWith(f.other);
        f.open(mode);
        f.apply();
        assertEquals(mode.equals("static") ? 7 : 0, f.engine.pda);
        assertEquals(mode.equals("dynamic") ? 2 : 0.5, f.engine.pdaCap);
        assertEquals(!mode.equals("dynamic"), f.engine.isStaticPda);
        assertEquals(1, f.engine.ponders);
        f.assertPreferences(mode);
      });
      List<String> commands = expectedCommands(mode);
      f.engine.assertCommands(commands);
      f.other.assertCommands(mirror ? commands : List.of());
      assertEquals(mirror && mode.equals("off") ? 0 : 1, f.other.pda);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void genmoveEditorUsesItsParticipantInsteadOfThePrimary(int participant) throws Exception {
    try (Fixture f = new Fixture(root)) {
      SwingUtilities.invokeAndWait(() -> {
        f.engine.isKataGoPda = participant == 0;
        f.other.isKataGoPda = participant == 1;
        f.engine.setEngineCommand("first-cmd");
        f.other.setEngineCommand("second-cmd");
        Lizzie.engineManager = new EngineManager(List.of(f.engine, f.other));
        TestEngine target = participant == 0 ? f.engine : f.other;
        TestEngine primary = participant == 0 ? f.other : f.engine;
        target.pda = 3;
        target.pdaCap = 1.5;
        Lizzie.setPrimaryEngine(primary);
        Lizzie.engineGame.publishSnapshotForTest(
            EngineGameSnapshotFixtures.playing(EngineGamePlayMode.GENMOVE, RunState.PAUSED));
        f.open("dynamic");
        if (!GraphicsEnvironment.isHeadless()) assertEquals("3.000", f.dialog.curPDA.getText());
        f.dialog.updateEngineValues(primary, primary.engineIncarnationToken(), "9.000", "9.0");
        assertEquals("2.0", f.dialog.txtDymCap.getText());
        f.apply();
        assertEquals(0, target.pda);
        assertEquals(2, target.pdaCap);
        assertEquals(1, primary.pda);
      });
      (participant == 0 ? f.engine : f.other).assertCommands(expectedCommands("dynamic"));
      (participant == 0 ? f.other : f.engine).assertCommands(List.of());
    }
  }

  @Test
  void analysisOwnershipPreservesRuntimeWhilePreferencesRemainEditable() throws Exception {
    try (Fixture f = new Fixture(root)) {
      SwingUtilities.invokeAndWait(() -> {
        f.open("dynamic");
        EngineGameSnapshotFixtures.publishPlaying();
        assertTrue(EngineGameDesktop.parametersOwned());
        f.apply();
        f.assertRuntimeUnchanged();
        f.assertPreferences("dynamic");
      });
      f.assertNoCommands();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"replacement", "reselection", "restart", "mirror-replacement", "mirror-restart",
      "game-complete", "game-replacement"})
  void commandAdmissionRejectsChangesAfterTheEditorsCheck(String change) throws Exception {
    try (Fixture f = new Fixture(root)) {
      if (change.startsWith("mirror")) f.compareWith(f.other);
      boolean participant = change.startsWith("game");
      if (participant) {
        Lizzie.setPrimaryEngine(f.other);
        Lizzie.engineGame.publishSnapshotForTest(
            EngineGameSnapshotFixtures.playing(EngineGamePlayMode.GENMOVE, RunState.RUNNING));
      }
      Leelaz.CustomPdaTarget target = f.engine.captureCustomPdaTarget(!participant);
      assertTrue(target.isCurrent());
      Field selectionField = EngineManager.class.getDeclaredField("ENGINE_SELECTION_STATE_LOCK");
      selectionField.setAccessible(true);
      Object selection = selectionField.get(null);
      var result = new java.util.concurrent.CompletableFuture<Boolean>();
      Thread apply = new Thread(() -> {
        try { result.complete(target.apply(2, true)); }
        catch (Throwable failure) { result.completeExceptionally(failure); }
      }, "stale-pda-admission");
      synchronized (selection) {
        apply.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (apply.isAlive() && apply.getState() != Thread.State.BLOCKED
            && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(Thread.State.BLOCKED, apply.getState(), "apply must serialize with selection");
        switch (change) {
          case "replacement" -> Lizzie.setPrimaryEngine(f.other);
          case "reselection" -> { Lizzie.setPrimaryEngine(f.other); Lizzie.setPrimaryEngine(f.engine); }
          case "restart" -> f.engine.restartReader();
          case "mirror-replacement" -> Lizzie.leelaz2 = f.replacement;
          case "mirror-restart" -> f.other.restartReader();
          case "game-complete" -> {
            var current = (EngineGameSnapshot.BatchActive) Lizzie.engineGame.current();
            Lizzie.engineGame.publishSnapshotForTest(new EngineGameSnapshot.BatchActive(
                current.batch(), new featurecat.lizzie.enginegame.GameActivity.BetweenGames()));
          }
          case "game-replacement" -> Lizzie.engineGame.publishSnapshotForTest(
              EngineGameSnapshotFixtures.playing(EngineGamePlayMode.GENMOVE, RunState.RUNNING));
          default -> fail(change);
        }
      }
      assertFalse(result.get(3, TimeUnit.SECONDS));
      apply.join(3000);
      assertFalse(apply.isAlive());
      f.assertRuntimeUnchanged();
      f.assertNoCommands();
    }
  }

  @Test
  void dynamicOnlyEngineRemainsEligible() throws Exception {
    try (Fixture f = new Fixture(root)) {
      SwingUtilities.invokeAndWait(() -> {
        f.engine.isKataGoPda = false;
        f.engine.isDymPda = true;
        f.open("dynamic");
        f.apply();
        assertEquals(2, f.engine.pdaCap);
      });
      f.engine.assertCommands(expectedCommands("dynamic"));
    }
  }

  @Test
  void readerUpdatesRetainTheirSourceWhenDeliveredOnTheEventThread() throws Exception {
    try (Fixture f = new Fixture(root)) {
      SwingUtilities.invokeAndWait(() -> f.open("dynamic"));
      Object original = f.engine.engineIncarnationToken();
      SwingUtilities.invokeAndWait(() -> {
        f.dialog.updateEngineValues(f.other, f.other.engineIncarnationToken(), "9.000", "9.0");
        assertEquals("2.0", f.dialog.txtDymCap.getText());
        f.dialog.updateEngineValues(f.engine, original, "4.000", "4.0");
        assertEquals("4.000", f.dialog.curPDA.getText());
        assertEquals("4.0", f.dialog.txtDymCap.getText());
        f.engine.restartReader();
      });
      f.dialog.updateEngineValues(f.engine, original, "8.000", "8.0");
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("4.000", f.dialog.curPDA.getText());
        assertEquals("4.0", f.dialog.txtDymCap.getText());
      });
    }
  }

  private static List<String> expectedCommands(String mode) {
    return switch (mode) {
      case "dynamic" -> List.of("pda 0", "dympdacap 2.0");
      case "static" -> List.of("pda 7.0");
      default -> List.of("pda 0.0");
    };
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void field(Object owner, Class<?> type, String name, Object value) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static final class Fixture implements AutoCloseable {
    final Config previousConfig = Lizzie.config;
    final LizzieFrame previousFrame = Lizzie.frame;
    final Leelaz previousPrimary = Lizzie.leelaz;
    final Leelaz previousSecondary = Lizzie.leelaz2;
    final EngineManager previousManager = Lizzie.engineManager;
    final Menu previousMenu = LizzieFrame.menu;
    final GtpConsolePane previousConsole = Lizzie.gtpConsole;
    final ResourceBundle previousBundle = Lizzie.resourceBundle;
    final EngineGameSnapshot previousGame = Lizzie.engineGame.current();
    final TestEngine engine;
    final TestEngine other;
    final TestEngine replacement;
    SetKataPDA dialog;

    Fixture(Path root) throws Exception {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Lizzie.frame = null;
      Lizzie.engineManager = null;
      Lizzie.leelaz2 = null;
      Lizzie.gtpConsole = allocate(QuietConsole.class);
      LizzieFrame.menu = allocate(Menu.class);
      LizzieFrame.menu.txtPDA = new JFontTextField("original toolbar");
      engine = new TestEngine();
      other = new TestEngine();
      replacement = new TestEngine();
      Lizzie.setPrimaryEngine(engine);
      EngineGameSnapshotFixtures.publishIdle();
    }

    void compareWith(TestEngine mirror) {
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      Lizzie.leelaz2 = mirror;
    }

    void open(String mode) {
      try {
        if (GraphicsEnvironment.isHeadless()) {
          dialog = allocate(HeadlessDialog.class);
          field(dialog, SetKataPDA.class, "resourceBundle", Lizzie.resourceBundle);
          dialog.chkDymPda = new JFontCheckBox();
          dialog.chkStaticPda = new JFontCheckBox();
          dialog.chkNoPDA = new JFontCheckBox();
          dialog.chkAutoPDA = new JFontCheckBox();
          dialog.txtDymCap = new JFontTextField();
          dialog.curPDA = new JLabel("1.000");
          field(dialog, SetKataPDA.class, "txtStaticCur", new JFontTextField());
          var capture = SetKataPDA.class.getDeclaredMethod("captureOpeningTarget");
          capture.setAccessible(true);
          capture.invoke(dialog);
        } else dialog = new SetKataPDA(null);
        dialog.chkDymPda.setSelected(mode.equals("dynamic"));
        dialog.chkStaticPda.setSelected(mode.equals("static"));
        dialog.chkNoPDA.setSelected(mode.equals("off"));
        dialog.chkAutoPDA.setSelected(true);
        dialog.txtDymCap.setText("2.0");
        Field staticField = SetKataPDA.class.getDeclaredField("txtStaticCur");
        staticField.setAccessible(true);
        ((JTextField) staticField.get(dialog)).setText("7.0");
      } catch (Exception failure) { throw new AssertionError(failure); }
    }

    void apply() {
      if (GraphicsEnvironment.isHeadless()) dialog.applySettings();
      else {
        String label = Lizzie.resourceBundle.getString("SetKataPDA.btnApply");
        for (Component component : dialog.getContentPane().getComponents()) {
          if (component instanceof JButton button && label.equals(button.getText())) {
            button.doClick(0);
            return;
          }
        }
        fail("Apply button missing");
      }
    }

    void assertPreferences(String mode) {
      assertTrue(Lizzie.config.chkAutoPDA);
      assertEquals("7.0", Lizzie.config.staticPDAcur);
      assertEquals("2.0", Lizzie.config.dymPDACap);
      assertEquals(mode.equals("dynamic"), Lizzie.config.chkDymPDA);
      assertEquals(mode.equals("static"), Lizzie.config.chkStaticPDA);
      assertEquals(mode.equals("dynamic") ? "dympdacap 2.0" : mode.equals("static") ? "pda 7.0" : "pda 0",
          Lizzie.config.uiConfig.getString("auto-pda"));
    }

    void assertRuntimeUnchanged() {
      for (TestEngine value : List.of(engine, other, replacement)) {
        assertEquals(1, value.pda);
        assertEquals(0.5, value.pdaCap);
        assertTrue(value.isStaticPda);
        assertEquals(0, value.ponders);
      }
      assertEquals("original toolbar", LizzieFrame.menu.txtPDA.getText());
    }

    void assertNoCommands() throws Exception {
      for (TestEngine value : List.of(engine, other, replacement)) value.assertCommands(List.of());
    }

    @Override public void close() throws Exception {
      try {
        for (TestEngine value : List.of(engine, other, replacement)) value.close();
      } finally {
        SwingUtilities.invokeAndWait(() -> {
        if (dialog != null) dialog.dispose();
        Lizzie.config = previousConfig;
        Lizzie.frame = previousFrame;
        Lizzie.engineManager = previousManager;
        LizzieFrame.menu = previousMenu;
        Lizzie.gtpConsole = previousConsole;
        Lizzie.resourceBundle = previousBundle;
        Lizzie.leelaz2 = previousSecondary;
        Lizzie.setPrimaryEngine(previousPrimary);
        Lizzie.engineGame.publishSnapshotForTest(previousGame);
        });
      }
    }
  }

  private static final class TestEngine extends Leelaz implements AutoCloseable {
    final List<ExactSnapshotRestoreProtocolFixture.Transport> transports = new ArrayList<>();
    int ponders;
    TestEngine() throws Exception {
      super("controlled-engine");
      restartReader();
      started = isLoaded = isKataGoPda = true;
      pda = 1;
      pdaCap = 0.5;
      isStaticPda = true;
    }
    void restartReader() {
      try {
        var transport = ExactSnapshotRestoreProtocolFixture.installAsync(this,
            command -> ExactSnapshotRestoreProtocolFixture.Response.success());
        var install = Leelaz.class.getDeclaredMethod("installFreshCommandOutputForTest", OutputStream.class);
        install.setAccessible(true);
        install.invoke(this, transport);
        transports.add(transport);
      } catch (Exception failure) { throw new AssertionError(failure); }
    }
    @Override public boolean isPondering() { return true; }
    @Override public void ponder() { ponders++; }
    void awaitIdle() throws Exception {
      var queueMethod = Leelaz.class.getDeclaredMethod("commandQueue");
      queueMethod.setAccessible(true);
      var queue = (java.util.Deque<?>) queueMethod.invoke(this);
      Field writing = Leelaz.class.getDeclaredField("normalCommandSendInProgress");
      writing.setAccessible(true);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (true) {
        synchronized (queue) { if (queue.isEmpty() && !writing.getBoolean(this)) break; }
        if (System.nanoTime() >= deadline) fail("PDA command output did not finish");
        Thread.sleep(5);
      }
      for (var transport : transports) transport.awaitResponses();
    }
    void assertCommands(List<String> expected) throws Exception {
      awaitIdle();
      assertEquals(expected, transports.stream().flatMap(t -> t.commands().stream()).toList());
    }
    @Override public void close() throws Exception {
      awaitIdle();
      for (var transport : transports) transport.close();
    }
  }

  private static final class HeadlessDialog extends SetKataPDA {
    HeadlessDialog() { super(null); }
    @Override public void setVisible(boolean visible) {}
    @Override public void dispose() {}
  }

  private static final class QuietConsole extends GtpConsolePane {
    QuietConsole() { super(null); }
    @Override public void addLine(String line) {}
  }
}
