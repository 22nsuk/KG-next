package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameSnapshotFixtures;
import featurecat.lizzie.enginegame.RunState;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SetKataEnginesHitTest {
  @TempDir Path root;

  @Test
  void threadCheckboxReceivesMouseAtEverySupportedFontSize() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    int previousSize = Config.frameFontSize;
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      Lizzie.frame =
          (QuietFrame) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(QuietFrame.class);
      Lizzie.setPrimaryEngine(null);
      SwingUtilities.invokeAndWait(
          () -> {
            for (int size : new int[] {12, 16, 20}) {
              Config.frameFontSize = size;
              SetKataEngines dialog = new SetKataEngines();
              try {
                dialog.setVisible(true);
                Field checkboxField = SetKataEngines.class.getDeclaredField("chkEditThreads");
                checkboxField.setAccessible(true);
                JCheckBox checkbox = (JCheckBox) checkboxField.get(dialog);
                Point center = SwingUtilities.convertPoint(
                    checkbox, checkbox.getWidth() / 2, checkbox.getHeight() / 2,
                    dialog.getContentPane());
                assertSame(
                    checkbox,
                    SwingUtilities.getDeepestComponentAt(
                        dialog.getContentPane(), center.x, center.y),
                    "Thread checkbox must receive mouse input at font size " + size);
              } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
              } finally {
                dialog.dispose();
              }
            }
          });
    } finally {
      Config.frameFontSize = previousSize;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.resourceBundle = previousBundle;
    }
  }

  @Test
  void acknowledgedOverrideReopensCheckedAndLateQueryKeepsDraft() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousConsole = Lizzie.gtpConsole;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    Leelaz engine = null;
    SetKataEngines[] dialogs = new SetKataEngines[3];
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      Lizzie.frame = (QuietFrame) unsafe.allocateInstance(QuietFrame.class);
      Lizzie.gtpConsole = (QuietConsole) unsafe.allocateInstance(QuietConsole.class);
      engine = new Leelaz("katago gtp");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      Lizzie.setPrimaryEngine(engine);
      var set = engine.captureRuntimeSearchThreads().apply(10);
      engineHook(engine, "processCommandResponseLineForTest", String.class,
          "=" + commandId(output, "kata-set-param numSearchThreads 10"));
      assertEquals(10, set.get(2, TimeUnit.SECONDS));

      SwingUtilities.invokeAndWait(() -> {
        dialogs[0] = new SetKataEngines();
        dialogs[0].setVisible(true);
        assertTrue(field(dialogs[0], "chkEditThreads", JCheckBox.class).isSelected());
        assertEquals("10", field(dialogs[0], "txtThreads", JTextField.class).getText(),
            "confirmed value must be visible before the query responds");
        field(dialogs[0], "txtThreads", JTextField.class).setText("12");
      });
      int queryId = commandId(output, "kata-get-param numSearchThreads");
      engineHook(engine, "processCommandResponseLineForTest", String.class,
          "=" + queryId + " 10");
      output.reset();
      SwingUtilities.invokeAndWait(
          () -> {
            assertEquals("12", field(dialogs[0], "txtThreads", JTextField.class).getText());
            JSONObject before = new JSONObject(Lizzie.config.uiConfig.toString());
            field(dialogs[0], "chkEditWRN", JCheckBox.class).doClick();
            field(dialogs[0], "txtWRN", JTextField.class).setText("1e");
            field(dialogs[0], "btnApply", JButton.class).doClick();
            assertEquals("1e", field(dialogs[0], "txtWRN", JTextField.class).getText());
            assertEquals(
                AppleStyleSupport.errorFieldBackground(),
                field(dialogs[0], "txtWRN", JTextField.class).getBackground());
            assertTrue(
                field(dialogs[0], "btnApply", JButton.class).isEnabled(),
                "invalid WRN must not begin the pending thread edit");
            assertTrue(dialogs[0].isVisible());
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertTrue(before.similar(Lizzie.config.uiConfig));
            field(dialogs[0], "btnCancel", JButton.class).doClick();
            dialogs[1] = new SetKataEngines();
            dialogs[1].setVisible(true);
            assertTrue(field(dialogs[1], "chkEditThreads", JCheckBox.class).isSelected());
            assertEquals("10", field(dialogs[1], "txtThreads", JTextField.class).getText());
          });
      assertEquals(Leelaz.RuntimeThreadOverrideState.ON,
          engine.captureRuntimeSearchThreads().overrideState());
      engineHook(engine, "processCommandResponseLineForTest", String.class,
          "?" + commandId(output, "kata-get-param numSearchThreads") + " unavailable");
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("10", field(dialogs[1], "txtThreads", JTextField.class).getText());
        assertEquals(Lizzie.resourceBundle.getString("SetKataEngines.threadReadFailed"),
            field(dialogs[1], "lblThreadReadStatus", javax.swing.JLabel.class).getText());
        dialogs[1].setVisible(false);
      });
      output.reset();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      SwingUtilities.invokeAndWait(() -> {
        dialogs[2] = new SetKataEngines();
        dialogs[2].setVisible(true);
        assertEquals("", field(dialogs[2], "txtThreads", JTextField.class).getText(),
            "replacement process must not inherit the old confirmed value");
        assertEquals(Lizzie.resourceBundle.getString("SetKataEngines.threadReading"),
            field(dialogs[2], "lblThreadReadStatus", javax.swing.JLabel.class).getText());
      });
      engineHook(engine, "processCommandResponseLineForTest", String.class,
          "=" + commandId(output, "kata-get-param numSearchThreads") + " 6");
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("6", field(dialogs[2], "txtThreads", JTextField.class).getText());
        assertEquals("", field(dialogs[2], "lblThreadReadStatus", javax.swing.JLabel.class).getText());
      });
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        for (SetKataEngines dialog : dialogs) if (dialog != null) dialog.dispose();
      });
      if (engine != null) {
        engine.isNormalEnd = true;
        engine.forceQuit();
      }
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.gtpConsole = previousConsole;
      Lizzie.resourceBundle = previousBundle;
    }
  }

  @Test
  void autoloadSelectedDraftsStayUneditedWhenOnlyThreadsChange() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousConsole = Lizzie.gtpConsole;
    var previousBoard = Lizzie.board;
    var previousMenu = LizzieFrame.menu;
    var previousWebBoardManager = Lizzie.webBoardManager;
    var previousGame = Lizzie.engineGame.current();
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    int previousSize = Config.frameFontSize;
    RecordingEngine engine = null;
    SetKataEngines[] dialog = new SetKataEngines[1];
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Lizzie.config.chkKataEngineWRN = Lizzie.config.chkKataEnginePDA = false;
      Lizzie.config.autoLoadKataEngineWRN = Lizzie.config.autoLoadKataEnginePDA = true;
      Lizzie.config.txtKataEngineWRN = "0.50";
      Lizzie.config.txtKataEnginePDA = "-0.50";
      Lizzie.webBoardManager = null;
      Lizzie.frame = allocate(QuietFrame.class);
      Lizzie.gtpConsole = allocate(QuietConsole.class);
      engine = new RecordingEngine();
      RecordingEngine activeEngine = engine;
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      engine.started = engine.isLoaded = engine.isKatago = true;
      BoardData initial = BoardData.empty(Board.boardWidth, Board.boardHeight);
      BoardData current = BoardData.empty(Board.boardWidth, Board.boardHeight);
      BoardHistoryList history = new BoardHistoryList(initial);
      history.add(current);
      Lizzie.board = allocate(Board.class);
      Lizzie.board.setHistory(history);
      LizzieFrame.menu = allocate(QuietMenu.class);
      Field bundle = Menu.class.getDeclaredField("resourceBundle");
      bundle.setAccessible(true);
      bundle.set(LizzieFrame.menu, Lizzie.resourceBundle);
      var initialize = Menu.class.getDeclaredMethod("initializeKataAnalysisFields");
      initialize.setAccessible(true);
      SwingUtilities.invokeAndWait(
          () -> {
            EngineGameSnapshotFixtures.publishIdle();
            Lizzie.setPrimaryEngine(activeEngine);
            try {
              initialize.invoke(LizzieFrame.menu);
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
            LizzieFrame.menu.setUseWrn(false, "0.50");
            LizzieFrame.menu.setUseGfPda(false, "-0.50");
            dialog[0] = new SetKataEngines();
            dialog[0].setVisible(true);
            assertTrue(field(dialog[0], "chkEditWRN", JCheckBox.class).isSelected());
            assertTrue(field(dialog[0], "chkEditPDA", JCheckBox.class).isSelected());
            assertEquals("0.50", field(dialog[0], "txtWRN", JTextField.class).getText());
            assertEquals("-0.50", field(dialog[0], "txtPDA", JTextField.class).getText());
          });
      engineHook(
          engine,
          "processCommandResponseLineForTest",
          String.class,
          "=" + commandId(output, "kata-get-param numSearchThreads") + " 8");
      SwingUtilities.invokeAndWait(
          () -> {
            field(dialog[0], "chkEditThreads", JCheckBox.class).doClick(0);
            field(dialog[0], "txtThreads", JTextField.class).setText("10");
            field(dialog[0], "btnApply", JButton.class).doClick(0);
            assertFalse(field(dialog[0], "btnApply", JButton.class).isEnabled());
            assertTrue(activeEngine.commands.isEmpty());
            LizzieFrame.menu.txtWRN.setText("0.9");
            LizzieFrame.menu.chkWRN.doClick(0);
            LizzieFrame.menu.txtGfPDA.setText("0.75");
            LizzieFrame.menu.chkPDA.doClick(0);
            assertEquals(
                List.of(
                    "kata-set-param analysisWideRootNoise 0.9",
                    "kata-set-param playoutDoublingAdvantage 0.75"),
                activeEngine.commands);
            activeEngine.commands.clear();
            initial.setPlayouts(500);
            current.setPlayouts(1000);
            initial.isChanged = current.isChanged = false;
          });
      engineHook(
          engine,
          "processCommandResponseLineForTest",
          String.class,
          "=" + commandId(output, "kata-set-param numSearchThreads 10"));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      boolean[] visible = new boolean[] {true};
      do {
        SwingUtilities.invokeAndWait(() -> visible[0] = dialog[0].isVisible());
        if (!visible[0]) break;
        Thread.sleep(5);
      } while (System.nanoTime() < deadline);
      SwingUtilities.invokeAndWait(
          () -> {
            assertFalse(dialog[0].isVisible(), "thread completion must reach the dialog");
            assertTrue(activeEngine.commands.isEmpty(), activeEngine.commands.toString());
            assertEquals("0.9", Lizzie.config.txtKataEngineWRN);
            assertEquals("0.75", Lizzie.config.txtKataEnginePDA);
            assertEquals("0.9", LizzieFrame.menu.txtWRN.getText());
            assertEquals("0.75", LizzieFrame.menu.txtGfPDA.getText());
            assertTrue(Lizzie.config.chkKataEngineWRN && Lizzie.config.chkKataEnginePDA);
            assertEquals("0.5", Lizzie.config.autoLoadTxtKataEngineWRN);
            assertEquals("-0.5", Lizzie.config.autoLoadTxtKataEnginePDA);
            assertFalse(initial.isChanged || current.isChanged);
            assertEquals(500, initial.getPlayouts());
            assertEquals(1000, current.getPlayouts());
          });
    } finally {
      try {
        SwingUtilities.invokeAndWait(
            () -> {
              if (dialog[0] != null) dialog[0].dispose();
            });
        if (engine != null) {
          engine.isNormalEnd = true;
          engine.forceQuit();
        }
      } finally {
        SwingUtilities.invokeAndWait(
            () -> {
              Config.frameFontSize = previousSize;
              Lizzie.config = previousConfig;
              Lizzie.frame = previousFrame;
              Lizzie.gtpConsole = previousConsole;
              Lizzie.board = previousBoard;
              LizzieFrame.menu = previousMenu;
              Lizzie.webBoardManager = previousWebBoardManager;
              Lizzie.setPrimaryEngine(previousEngine);
              Lizzie.engineGame.publishSnapshotForTest(previousGame);
              Lizzie.resourceBundle = previousBundle;
            });
      }
    }
  }

  @Test
  void runningGameLocksPreopenedDraftsAndAllowsPreferencesAfterThreadHandleRetires()
      throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousConsole = Lizzie.gtpConsole;
    var previousMenu = LizzieFrame.menu;
    var previousGame = Lizzie.engineGame.current();
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    RecordingEngine engine = null;
    SetKataEngines[] dialog = new SetKataEngines[1];
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Lizzie.config.chkKataEngineWRN = Lizzie.config.chkKataEnginePDA = true;
      Lizzie.config.autoLoadKataEngineWRN = Lizzie.config.autoLoadKataEnginePDA = false;
      Lizzie.config.txtKataEngineWRN = "0.5";
      Lizzie.config.txtKataEnginePDA = "-0.5";
      Lizzie.frame = allocate(QuietFrame.class);
      Lizzie.gtpConsole = allocate(QuietConsole.class);
      LizzieFrame.menu = allocate(QuietMenu.class);
      engine = new RecordingEngine();
      RecordingEngine activeEngine = engine;
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      engine.started = engine.isLoaded = engine.isKatago = true;
      SwingUtilities.invokeAndWait(
          () -> {
            EngineGameSnapshotFixtures.publishIdle();
            Lizzie.setPrimaryEngine(activeEngine);
            dialog[0] = new SetKataEngines();
            dialog[0].setVisible(true);
            field(dialog[0], "txtWRN", JTextField.class).setText("1e");
            field(dialog[0], "txtPDA", JTextField.class).setText("-");
            assertTrue(field(dialog[0], "txtWRN", JTextField.class).isEnabled());
            assertTrue(field(dialog[0], "txtPDA", JTextField.class).isEnabled());
          });
      engineHook(
          engine,
          "processCommandResponseLineForTest",
          String.class,
          "=" + commandId(output, "kata-get-param numSearchThreads") + " 8");
      // A later game owner/process retires the dialog's captured thread handle. Preferences have
      // no runtime dependency on that handle and must remain saveable without a stale warning.
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      output.reset();
      SwingUtilities.invokeAndWait(
          () -> {
            EngineGameSnapshotFixtures.publishPlaying();
            SetKataEngines.refreshOpenDialogs();
            for (String name : new String[] {"chkEditWRN", "chkEditPDA", "txtWRN", "txtPDA"}) {
              assertFalse(field(dialog[0], name, javax.swing.JComponent.class).isEnabled(), name);
              assertEquals(
                  Lizzie.resourceBundle.getString("EngineGameParameters.useGamePanel"),
                  field(dialog[0], name, javax.swing.JComponent.class).getToolTipText());
            }
            field(dialog[0], "chkWRNInMenu", JCheckBox.class).setSelected(true);
            field(dialog[0], "chkPDAInMenu", JCheckBox.class).setSelected(true);
            field(dialog[0], "chkAutoLoadWRN", JCheckBox.class).doClick(0);
            field(dialog[0], "chkAutoLoadPDA", JCheckBox.class).doClick(0);
            field(dialog[0], "btnApply", JButton.class).doClick(0);
            assertFalse(dialog[0].isVisible(), "preference-only save must complete");
            assertTrue(activeEngine.commands.isEmpty(), activeEngine.commands.toString());
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertTrue(Lizzie.config.showWRNInMenu && Lizzie.config.showPDAInMenu);
            assertTrue(Lizzie.config.autoLoadKataEngineWRN && Lizzie.config.autoLoadKataEnginePDA);
            assertEquals("0.5", Lizzie.config.txtKataEngineWRN);
            assertEquals("-0.5", Lizzie.config.txtKataEnginePDA);
            assertEquals("0.5", Lizzie.config.autoLoadTxtKataEngineWRN);
            assertEquals("-0.5", Lizzie.config.autoLoadTxtKataEnginePDA);
          });
    } finally {
      try {
        SwingUtilities.invokeAndWait(
            () -> {
              if (dialog[0] != null) dialog[0].dispose();
            });
        if (engine != null) {
          engine.isNormalEnd = true;
          engine.forceQuit();
        }
      } finally {
        SwingUtilities.invokeAndWait(
            () -> {
              Lizzie.config = previousConfig;
              Lizzie.frame = previousFrame;
              Lizzie.gtpConsole = previousConsole;
              LizzieFrame.menu = previousMenu;
              Lizzie.setPrimaryEngine(previousEngine);
              Lizzie.engineGame.publishSnapshotForTest(previousGame);
              Lizzie.resourceBundle = previousBundle;
            });
      }
    }
  }

  @Test
  void pausedGenmoveKeepsOrdinaryPdaEditingWhileWrnRemainsLocked() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousManager = Lizzie.engineManager;
    var previousConsole = Lizzie.gtpConsole;
    var previousMenu = LizzieFrame.menu;
    var previousWebBoardManager = Lizzie.webBoardManager;
    var previousGame = Lizzie.engineGame.current();
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    RecordingEngine[] engines = new RecordingEngine[2];
    SetKataEngines[] dialog = new SetKataEngines[1];
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Lizzie.config.chkKataEngineWRN = false;
      Lizzie.config.chkKataEnginePDA = true;
      Lizzie.config.autoLoadKataEngineWRN = Lizzie.config.autoLoadKataEnginePDA = false;
      Lizzie.config.txtKataEngineWRN = "0.5";
      Lizzie.config.txtKataEnginePDA = "-0.5";
      Lizzie.webBoardManager = null;
      Lizzie.frame = allocate(QuietFrame.class);
      Lizzie.gtpConsole = allocate(QuietConsole.class);
      LizzieFrame.menu = allocate(QuietMenu.class);
      for (int i = 0; i < engines.length; i++) {
        engines[i] = new RecordingEngine();
        engines[i].started = engines[i].isLoaded = engines[i].isKatago = true;
      }
      Lizzie.engineManager = new EngineManager(List.of(engines[0], engines[1]));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engineHook(engines[0], "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      Field bundle = Menu.class.getDeclaredField("resourceBundle");
      bundle.setAccessible(true);
      bundle.set(LizzieFrame.menu, Lizzie.resourceBundle);
      var initialize = Menu.class.getDeclaredMethod("initializeKataAnalysisFields");
      initialize.setAccessible(true);
      SwingUtilities.invokeAndWait(
          () -> {
            Lizzie.engineGame.publishSnapshotForTest(
                EngineGameSnapshotFixtures.playing(EngineGamePlayMode.GENMOVE, RunState.PAUSED));
            Lizzie.setPrimaryEngine(engines[0]);
            try {
              initialize.invoke(LizzieFrame.menu);
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
            LizzieFrame.menu.setUseWrn(false, "0.5");
            LizzieFrame.menu.setUseGfPda(true, "-0.5");
            dialog[0] = new SetKataEngines();
            dialog[0].setVisible(true);
            assertFalse(field(dialog[0], "chkEditWRN", JCheckBox.class).isEnabled());
            assertFalse(field(dialog[0], "txtWRN", JTextField.class).isEnabled());
            assertTrue(field(dialog[0], "chkEditPDA", JCheckBox.class).isEnabled());
            assertTrue(field(dialog[0], "txtPDA", JTextField.class).isEnabled());
          });
      engineHook(
          engines[0],
          "processCommandResponseLineForTest",
          String.class,
          "=" + commandId(output, "kata-get-param numSearchThreads") + " 8");
      SwingUtilities.invokeAndWait(
          () -> {
            field(dialog[0], "txtPDA", JTextField.class).setText("0.75");
            field(dialog[0], "btnApply", JButton.class).doClick(0);
            assertFalse(dialog[0].isVisible());
            assertTrue(engines[0].commands.contains("kata-set-param playoutDoublingAdvantage 0.75"));
            assertTrue(engines[1].commands.isEmpty());
            assertEquals("0.75", Lizzie.config.txtKataEnginePDA);
            assertFalse(LizzieFrame.menu.chkWRN.isEnabled());

            engines[0].commands.clear();
            LizzieFrame.menu.txtWRN.setText("0.9");
            LizzieFrame.menu.txtGfPDA.setText("-0.75");
            for (RecordingEngine engine : engines) {
              assertEquals(
                  List.of("kata-set-param playoutDoublingAdvantage -0.75"), engine.commands);
              engine.commands.clear();
            }
            EngineGameSnapshotFixtures.publishPlaying();
            LizzieFrame.menu.refreshBreadthAdmission();
            assertFalse(LizzieFrame.menu.chkPDA.isEnabled());
            assertFalse(LizzieFrame.menu.txtGfPDA.isEnabled());
            // Queued editor events must not send through the ordinary path once analysis owns it.
            LizzieFrame.menu.txtGfPDA.setText("1.25");
            assertTrue(engines[0].commands.isEmpty() && engines[1].commands.isEmpty());
            assertEquals("-0.75", Lizzie.config.txtKataEnginePDA);
          });
    } finally {
      try {
        SwingUtilities.invokeAndWait(
            () -> {
              if (dialog[0] != null) dialog[0].dispose();
            });
        for (RecordingEngine engine : engines) {
          if (engine != null) {
            engine.isNormalEnd = true;
            engine.forceQuit();
          }
        }
      } finally {
        SwingUtilities.invokeAndWait(
            () -> {
              Lizzie.config = previousConfig;
              Lizzie.frame = previousFrame;
              Lizzie.gtpConsole = previousConsole;
              LizzieFrame.menu = previousMenu;
              Lizzie.webBoardManager = previousWebBoardManager;
              Lizzie.engineManager = previousManager;
              Lizzie.setPrimaryEngine(previousEngine);
              Lizzie.engineGame.publishSnapshotForTest(previousGame);
              Lizzie.resourceBundle = previousBundle;
            });
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void dedicatedPdaTargetsGenmoveParticipantButAnalysisOnlySavesPreferences(int participant)
      throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousManager = Lizzie.engineManager;
    var previousMenu = LizzieFrame.menu;
    var previousGame = Lizzie.engineGame.current();
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    RecordingEngine[] engines = new RecordingEngine[2];
    SetKataPDA[] dialogs = new SetKataPDA[2];
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Lizzie.config.AutoPDA = "dympdacap 0.4";
      Lizzie.frame = allocate(QuietFrame.class);
      LizzieFrame.menu = allocate(QuietMenu.class);
      for (int i = 0; i < engines.length; i++) {
        engines[i] = new RecordingEngine();
        engines[i].setEngineCommand(i == 0 ? "first-cmd" : "second-cmd");
      }
      engines[participant].isKataGoPda = true;
      Lizzie.engineManager = new EngineManager(List.of(engines[0], engines[1]));
      SwingUtilities.invokeAndWait(
          () -> {
            LizzieFrame.menu.txtPDA = new JFontTextField();
            Lizzie.setPrimaryEngine(engines[1 - participant]);
            Lizzie.engineGame.publishSnapshotForTest(
                EngineGameSnapshotFixtures.playing(EngineGamePlayMode.GENMOVE, RunState.PAUSED));
            dialogs[0] = new SetKataPDA();
            dialogs[0].setVisible(true);
            dialogs[0].chkStaticPda.doClick(0);
            field(dialogs[0], "txtStaticCur", JTextField.class).setText("1.25");
            dialogs[0].chkAutoPDA.setSelected(false);
            button(dialogs[0], "SetKataPDA.btnApply").doClick(0);
            assertFalse(dialogs[0].isVisible());
            assertEquals(List.of("pda 1.25"), engines[participant].commands);
            assertTrue(engines[1 - participant].commands.isEmpty());
            assertEquals(1.25, engines[participant].pda);
            assertEquals("dympdacap 0.4", Lizzie.config.AutoPDA);

            engines[participant].commands.clear();
            EngineGameSnapshotFixtures.publishPlaying();
            dialogs[1] = new SetKataPDA();
            dialogs[1].setVisible(true);
            dialogs[1].chkDymPda.setSelected(true);
            dialogs[1].txtDymCap.setText("0.8");
            dialogs[1].chkAutoPDA.setSelected(true);
            button(dialogs[1], "SetKataPDA.btnApply").doClick(0);
            assertFalse(dialogs[1].isVisible());
            assertTrue(engines[0].commands.isEmpty() && engines[1].commands.isEmpty());
            assertEquals(1.25, engines[participant].pda);
            assertEquals("dympdacap 0.8", Lizzie.config.AutoPDA);
            assertEquals("dympdacap 0.8", Lizzie.config.uiConfig.getString("auto-pda"));
          });
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            for (SetKataPDA dialog : dialogs) if (dialog != null) dialog.dispose();
            Lizzie.config = previousConfig;
            Lizzie.frame = previousFrame;
            LizzieFrame.menu = previousMenu;
            Lizzie.engineManager = previousManager;
            Lizzie.setPrimaryEngine(previousEngine);
            Lizzie.engineGame.publishSnapshotForTest(previousGame);
            Lizzie.resourceBundle = previousBundle;
          });
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private static <T> T field(JDialog dialog, String name, Class<T> type) {
    try {
      Field field = dialog.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(dialog));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static JButton button(JDialog dialog, String labelKey) {
    String label = Lizzie.resourceBundle.getString(labelKey);
    for (var component : dialog.getContentPane().getComponents()) {
      if (component instanceof JButton button && label.equals(button.getText())) return button;
    }
    throw new AssertionError("Missing button " + label);
  }

  private static void engineHook(Leelaz engine, String name, Class<?> argumentType,
      Object argument) throws Exception {
    var hook = Leelaz.class.getDeclaredMethod(name, argumentType);
    hook.setAccessible(true);
    hook.invoke(engine, argument);
  }

  private static int commandId(ByteArrayOutputStream output, String suffix) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    do {
      for (String line : output.toString(StandardCharsets.UTF_8).split("\\R")) {
        if (line.endsWith(suffix)) return Integer.parseInt(line.substring(0, line.indexOf(' ')));
      }
      Thread.sleep(5);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Missing command " + suffix);
  }

  private static final class QuietConsole extends GtpConsolePane {
    private QuietConsole() { super(null); }
    @Override public void addLine(String line) {}
  }

  private static final class RecordingEngine extends Leelaz {
    final List<String> commands = new ArrayList<>();

    RecordingEngine() throws Exception {
      super("katago gtp");
    }

    @Override
    public void sendCommand(String command) {
      commands.add(command);
    }
  }

  private static final class QuietMenu extends Menu {
    @Override
    public void updateMenuStatusForEngine() {}

    // Parameter-editor tests do not construct the engine catalog's menu items.
    @Override
    public void changeEngineIcon(int index, int mode) {}
  }

  private static final class QuietFrame extends LizzieFrame {
    @Override
    public void refresh() {}

    @Override
    public void resetTitle() {}

    @Override
    public GraphicsConfiguration getGraphicsConfiguration() {
      return GraphicsEnvironment.getLocalGraphicsEnvironment()
          .getDefaultScreenDevice().getDefaultConfiguration();
    }
  }
}
