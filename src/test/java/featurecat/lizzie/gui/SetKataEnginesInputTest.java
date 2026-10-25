package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameSnapshotFixtures;
import featurecat.lizzie.gui.web.WebBoardManager;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SetKataEnginesInputTest {
  @TempDir Path folder;

  static Stream<Arguments> invalidDrafts() {
    return Stream.of(KataAnalysisParameter.values())
        .flatMap(
            parameter ->
                Stream.concat(
                        Stream.of(
                            "",
                            " ",
                            "-",
                            "+",
                            ".",
                            "-.",
                            "1.",
                            "1e",
                            "1e-",
                            "NaN",
                            "Infinity",
                            "-Infinity",
                            "1e999",
                            "invalid"),
                        parameter == KataAnalysisParameter.WRN
                            ? Stream.of("-0.1", "2.1")
                            : Stream.of("-3.1", "3.1"))
                    .flatMap(
                        draft ->
                            Stream.of(false, true)
                                .map(threads -> Arguments.of(parameter, draft, threads))));
  }

  @ParameterizedTest
  @MethodSource("invalidDrafts")
  void invalidSubmissionPreservesEverythingBeforeAnyThreadChange(
      KataAnalysisParameter parameter, String draft, boolean changeThreads) throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            env.text(parameter).setText(draft);
            env.check("chkEditThreads").setSelected(changeThreads);
            env.text("txtThreads").setText("10");
            // Other valid changes cannot be partially committed when either numeric field is
            // invalid.
            env.check("chkWRNInMenu").setSelected(true);
            env.check("chkPDAInMenu").setSelected(true);
            env.check("chkAutoLoadWRN").setSelected(true);
            env.check("chkAutoLoadPDA").setSelected(true);
            env.assertPreserved();
            env.confirm();
            env.assertPreserved();
            assertFalse((boolean) field(env.dialog, "applyingThreads"));
            assertTrue(env.dialog.isVisible());
            assertEquals(draft, env.text(parameter).getText());
            assertEquals(
                AppleStyleSupport.errorFieldBackground(), env.text(parameter).getBackground());
          });
    }
  }

  @Test
  void invalidSubmissionDoesNotPrepareOrPromptForSourceRestore() throws Exception {
    try (Environment env = new Environment()) {
      var applied = env.engine.captureRuntimeSearchThreads().apply(8);
      env.ackThreads(8);
      assertEquals(8, applied.get(2, TimeUnit.SECONDS));
      env.output.reset();
      onEdt(
          () -> {
            assertEquals(
                Leelaz.RuntimeThreadOverrideState.ON,
                env.engine.captureRuntimeSearchThreads().overrideState());
            env.check("chkEditThreads").setSelected(false);
            env.text(KataAnalysisParameter.PDA).setText("-");
            // There is deliberately no source profile: preparing restore or showing its dialog
            // would fail this headless fixture. Invalid input must return before either action.
            env.confirm();
            env.assertPreserved();
            assertFalse((boolean) field(env.dialog, "applyingThreads"));
            assertEquals(8, env.engine.captureRuntimeSearchThreads().lastConfirmedValue());
          });
    }
  }

  @ParameterizedTest
  @CsvSource({"0,-3", "2,3", "1.25,-1.25"})
  void validValuesApplyAndOnlyEnabledWrnInvalidatesAnalysis(String wrn, String pda)
      throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            env.text("txtWRN").setText(wrn);
            env.text("txtPDA").setText(pda);
            env.check("chkAutoLoadWRN").setSelected(true);
            env.check("chkAutoLoadPDA").setSelected(true);
            env.confirm();
            String expectedWrn = String.valueOf(Double.parseDouble(wrn));
            String expectedPda = String.valueOf(Double.parseDouble(pda));
            assertEquals(
                List.of(
                    "kata-set-param playoutDoublingAdvantage " + expectedPda,
                    "kata-set-param analysisWideRootNoise " + expectedWrn),
                env.engine.commands);
            assertEquals(expectedWrn, Lizzie.config.txtKataEngineWRN);
            assertEquals(expectedPda, Lizzie.config.txtKataEnginePDA);
            assertEquals(expectedWrn, Lizzie.config.autoLoadTxtKataEngineWRN);
            assertEquals(expectedPda, Lizzie.config.autoLoadTxtKataEnginePDA);
            assertTrue(env.root.isChanged);
            assertTrue(env.current.isChanged);
            assertFalse(env.dialog.isVisible());
            assertEquals(1, env.engine.ponders);
          });
    }
  }

  @Test
  void disabledDraftsKeepRememberedValuesAndDoNotInvalidateAnalysis() throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            env.text("txtWRN").setText("NaN");
            env.text("txtPDA").setText("");
            env.check("chkEditWRN").setSelected(false);
            env.check("chkEditPDA").setSelected(false);
            env.confirm();
            assertEquals(
                List.of(
                    "kata-set-param playoutDoublingAdvantage 0",
                    "kata-set-param analysisWideRootNoise 0"),
                env.engine.commands);
            assertEquals("0.5", Lizzie.config.txtKataEngineWRN);
            assertEquals("-0.5", Lizzie.config.txtKataEnginePDA);
            assertFalse(Lizzie.config.chkKataEngineWRN);
            assertFalse(Lizzie.config.chkKataEnginePDA);
            env.assertAnalysisPreserved();
          });
    }
  }

  @Test
  void validPdaWithWrnDisabledPreservesAnalysis() throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            env.check("chkEditWRN").setSelected(false);
            env.text("txtPDA").setText("-2.5");
            env.confirm();
            assertEquals(
                List.of(
                    "kata-set-param playoutDoublingAdvantage -2.5",
                    "kata-set-param analysisWideRootNoise 0"),
                env.engine.commands);
            assertEquals("-2.5", Lizzie.config.txtKataEnginePDA);
            env.assertAnalysisPreserved();
          });
    }
  }

  @Test
  void unchangedConfirmedThreadCountDoesNotStartAnotherThreadChange() throws Exception {
    try (Environment env = new Environment()) {
      var applied = env.engine.captureRuntimeSearchThreads().apply(8);
      env.ackThreads(8);
      assertEquals(8, applied.get(2, TimeUnit.SECONDS));
      env.output.reset();
      onEdt(
          () -> {
            env.check("chkEditThreads").setSelected(true);
            env.text("txtThreads").setText("8");
            env.confirm();
            assertEquals("", env.output.toString(StandardCharsets.UTF_8));
            assertFalse((boolean) field(env.dialog, "applyingThreads"));
            assertFalse(env.dialog.isVisible());
            assertEquals(2, env.engine.commands.size());
          });
    }
  }

  @Test
  void disabledEmptyDefaultsAllowUnrelatedThreadChange() throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            Lizzie.config.txtKataEngineWRN = Lizzie.config.txtKataEnginePDA = "";
            env.check("chkEditWRN").setSelected(false);
            env.check("chkEditPDA").setSelected(false);
            env.check("chkEditThreads").setSelected(true);
            env.text("txtWRN").setText("");
            env.text("txtPDA").setText("");
            env.text("txtThreads").setText("10");
            env.confirm();
          });
      env.ackThreads(10);
      env.awaitApplied();
      assertEquals("", Lizzie.config.txtKataEngineWRN);
      assertEquals("", Lizzie.config.txtKataEnginePDA);
      env.assertAnalysisPreserved();
    }
  }

  @Test
  void asynchronousCompletionUsesOnlyTheValidatedSubmission() throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            env.text("txtWRN").setText("1.25");
            env.text("txtPDA").setText("-1.25");
            env.check("chkAutoLoadWRN").setSelected(true);
            env.check("chkAutoLoadPDA").setSelected(true);
            env.check("chkWRNInMenu").setSelected(true);
            env.check("chkPDAInMenu").setSelected(true);
            env.check("chkEditThreads").setSelected(true);
            env.text("txtThreads").setText("10");
            env.confirm();
            assertTrue((boolean) field(env.dialog, "applyingThreads"));
            env.text("txtWRN").setText("NaN");
            env.text("txtPDA").setText("-");
            for (String name :
                List.of(
                    "chkEditWRN",
                    "chkEditPDA",
                    "chkAutoLoadWRN",
                    "chkAutoLoadPDA",
                    "chkWRNInMenu",
                    "chkPDAInMenu")) env.check(name).setSelected(false);
            env
                .confirm(); // An already-running confirmation must not submit a second thread
                            // request.
            assertTrue(env.engine.commands.isEmpty());
            assertTrue(env.originalConfig.similar(Lizzie.config.uiConfig));
            env.assertAnalysisPreserved();
          });
      env.ackThreads(10);
      env.awaitApplied();
      assertEquals(
          List.of(
              "kata-set-param playoutDoublingAdvantage -1.25",
              "kata-set-param analysisWideRootNoise 1.25"),
          env.engine.commands);
      assertEquals(
          1,
          env.output
              .toString(StandardCharsets.UTF_8)
              .lines()
              .filter(line -> line.contains("kata-set-param numSearchThreads"))
              .count());
      assertEquals("1.25", Lizzie.config.txtKataEngineWRN);
      assertEquals("-1.25", Lizzie.config.txtKataEnginePDA);
      assertTrue(Lizzie.config.chkKataEngineWRN && Lizzie.config.chkKataEnginePDA);
      assertTrue(Lizzie.config.autoLoadKataEngineWRN && Lizzie.config.autoLoadKataEnginePDA);
      assertTrue(Lizzie.config.showWRNInMenu && Lizzie.config.showPDAInMenu);
      assertEquals("1.25", Lizzie.config.uiConfig.getString("auto-load-txt-kata-engine-wrn"));
      assertEquals("-1.25", Lizzie.config.uiConfig.getString("auto-load-txt-kata-engine-pda"));
    }
  }

  @Test
  void threadCompletionPreservesNewerToolbarValuesWhenParametersAreUntouched() throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            env.beginThreadChange();
            env.changeToolbarParameters();
          });
      env.ackThreads(10);
      env.awaitApplied();
      onEdt(
          () -> {
            env.assertToolbarValuesPreserved();
            assertTrue(env.engine.commands.isEmpty(), env.engine.commands.toString());
            env.assertAnalysisPreserved();
            assertFalse(env.dialog.isVisible());
          });
    }
  }

  @ParameterizedTest
  @EnumSource(KataAnalysisParameter.class)
  void threadCompletionAppliesOnlyTheExplicitlyEditedParameter(KataAnalysisParameter parameter)
      throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            env.text(parameter).setText("1.25");
            env.beginThreadChange();
            env.changeToolbarParameters();
            env.text("txtWRN").setText("NaN");
            env.text("txtPDA").setText("-");
          });
      env.ackThreads(10);
      env.awaitApplied();
      onEdt(
          () -> {
            assertEquals(List.of(command(parameter, "1.25")), env.engine.commands);
            assertEquals(
                parameter == KataAnalysisParameter.WRN ? "1.25" : "0.9",
                Lizzie.config.txtKataEngineWRN);
            assertEquals(
                parameter == KataAnalysisParameter.PDA ? "1.25" : "0.75",
                Lizzie.config.txtKataEnginePDA);
            assertEquals(
                Lizzie.config.txtKataEngineWRN,
                Lizzie.config.uiConfig.getString("txt-kata-engine-wrn"));
            assertEquals(
                Lizzie.config.txtKataEnginePDA,
                Lizzie.config.uiConfig.getString("txt-kata-engine-pda"));
            assertEquals(Lizzie.config.txtKataEngineWRN, LizzieFrame.menu.txtWRN.getText());
            assertEquals(Lizzie.config.txtKataEnginePDA, LizzieFrame.menu.txtGfPDA.getText());
            assertEquals(parameter == KataAnalysisParameter.WRN, env.root.isChanged);
            assertEquals(parameter == KataAnalysisParameter.WRN, env.current.isChanged);
          });
    }
  }

  @ParameterizedTest
  @EnumSource(KataAnalysisParameter.class)
  void threadCompletionRetainsSubmittedDisableWithoutReapplyingTheOtherParameter(
      KataAnalysisParameter parameter) throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            String checkbox = parameter == KataAnalysisParameter.WRN ? "chkEditWRN" : "chkEditPDA";
            env.check(checkbox).setSelected(false);
            env.beginThreadChange();
            env.changeToolbarParameters();
            env.check(checkbox).setSelected(true);
          });
      env.ackThreads(10);
      env.awaitApplied();
      onEdt(
          () -> {
            assertEquals(List.of(command(parameter, "0")), env.engine.commands);
            assertEquals(parameter != KataAnalysisParameter.WRN, Lizzie.config.chkKataEngineWRN);
            assertEquals(parameter != KataAnalysisParameter.PDA, Lizzie.config.chkKataEnginePDA);
            assertEquals(
                parameter == KataAnalysisParameter.WRN ? "0.5" : "0.9",
                Lizzie.config.txtKataEngineWRN);
            assertEquals(
                parameter == KataAnalysisParameter.PDA ? "-0.5" : "0.75",
                Lizzie.config.txtKataEnginePDA);
            env.assertAnalysisPreserved();
          });
    }
  }

  @Test
  void threadCompletionSavesPreferencesWithoutReapplyingUntouchedParameters() throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            env.check("chkAutoLoadWRN").setSelected(true);
            env.check("chkAutoLoadPDA").setSelected(true);
            env.check("chkWRNInMenu").setSelected(true);
            env.check("chkPDAInMenu").setSelected(true);
            env.beginThreadChange();
            env.changeToolbarParameters();
          });
      env.ackThreads(10);
      env.awaitApplied();
      onEdt(
          () -> {
            env.assertToolbarValuesPreserved();
            assertTrue(env.engine.commands.isEmpty(), env.engine.commands.toString());
            assertTrue(Lizzie.config.autoLoadKataEngineWRN && Lizzie.config.autoLoadKataEnginePDA);
            assertTrue(Lizzie.config.showWRNInMenu && Lizzie.config.showPDAInMenu);
            assertEquals("0.5", Lizzie.config.autoLoadTxtKataEngineWRN);
            assertEquals("-0.5", Lizzie.config.autoLoadTxtKataEnginePDA);
            assertEquals("0.5", Lizzie.config.uiConfig.getString("auto-load-txt-kata-engine-wrn"));
            assertEquals("-0.5", Lizzie.config.uiConfig.getString("auto-load-txt-kata-engine-pda"));
            env.assertAnalysisPreserved();
          });
    }
  }

  @Test
  void threadCompletionDoesNotReenableParametersDisabledFromTheToolbar() throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            env.beginThreadChange();
            LizzieFrame.menu.chkWRN.doClick(0);
            LizzieFrame.menu.chkPDA.doClick(0);
            assertEquals(
                List.of(
                    command(KataAnalysisParameter.WRN, "0.0"),
                    command(KataAnalysisParameter.PDA, "0.0")),
                env.engine.commands);
            env.engine.commands.clear();
            env.resetAnalysis();
          });
      env.ackThreads(10);
      env.awaitApplied();
      onEdt(
          () -> {
            assertTrue(env.engine.commands.isEmpty(), env.engine.commands.toString());
            assertFalse(Lizzie.config.chkKataEngineWRN || Lizzie.config.chkKataEnginePDA);
            assertFalse(Lizzie.config.uiConfig.getBoolean("chk-kata-engine-wrn"));
            assertFalse(Lizzie.config.uiConfig.getBoolean("chk-kata-engine-pda"));
            assertFalse(LizzieFrame.menu.chkWRN.isSelected());
            assertFalse(LizzieFrame.menu.chkPDA.isSelected());
            assertEquals("0.5", Lizzie.config.txtKataEngineWRN);
            assertEquals("-0.5", Lizzie.config.txtKataEnginePDA);
            env.assertAnalysisPreserved();
          });
    }
  }

  @Test
  void canonicalizingUneditedDraftsDoesNotClaimTheNewerToolbarValues() throws Exception {
    try (Environment env = new Environment("0.50", "-0.50", true)) {
      onEdt(
          () -> {
            env.beginThreadChange();
            env.changeToolbarParameters();
          });
      env.ackThreads(10);
      env.awaitApplied();
      onEdt(
          () -> {
            env.assertToolbarValuesPreserved();
            assertTrue(env.engine.commands.isEmpty(), env.engine.commands.toString());
            env.assertAnalysisPreserved();
          });
    }
  }

  @Test
  void disabledDraftTextDoesNotClaimParametersEnabledFromTheToolbar() throws Exception {
    try (Environment env = new Environment("0.5", "-0.5", false)) {
      onEdt(
          () -> {
            env.text("txtWRN").setText("NaN");
            env.text("txtPDA").setText("-");
            env.beginThreadChange();
            env.changeToolbarParameters();
          });
      env.ackThreads(10);
      env.awaitApplied();
      onEdt(
          () -> {
            env.assertToolbarValuesPreserved();
            assertTrue(env.engine.commands.isEmpty(), env.engine.commands.toString());
            env.assertAnalysisPreserved();
          });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void restoredReaderReceivesOnlyExplicitParameterEdits(boolean editPda) throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            if (editPda) env.text("txtPDA").setText("1.25");
            var capture = SetKataEngines.class.getDeclaredMethod("captureAnalysisSettings");
            capture.setAccessible(true);
            Object settings = capture.invoke(env.dialog);
            assertNotNull(settings);
            var originalReader = env.engine.captureRuntimeSearchThreads();
            hook(env.engine, "installFreshCommandOutputForTest", OutputStream.class, env.output);
            env.engine.started = env.engine.isLoaded = true;
            assertFalse(originalReader.isCurrent());
            assertTrue(env.engine.captureRuntimeSearchThreads().isCurrent());
            env.changeToolbarParameters();
            env.text("txtPDA").setText("NaN");
            int previousPonders = env.engine.ponders;
            // The restore callback uses this receiver boundary after installing its new reader.
            var apply =
                SetKataEngines.class.getDeclaredMethod(
                    "applyExistingSettings",
                    Leelaz.class,
                    settings.getClass(),
                    boolean.class,
                    boolean.class);
            apply.setAccessible(true);
            apply.invoke(env.dialog, env.engine, settings, false, true);
            assertEquals(
                editPda ? List.of(command(KataAnalysisParameter.PDA, "1.25")) : List.of(),
                env.engine.commands);
            assertEquals("0.9", Lizzie.config.txtKataEngineWRN);
            assertEquals(editPda ? "1.25" : "0.75", Lizzie.config.txtKataEnginePDA);
            assertEquals(Lizzie.config.txtKataEngineWRN, LizzieFrame.menu.txtWRN.getText());
            assertEquals(Lizzie.config.txtKataEnginePDA, LizzieFrame.menu.txtGfPDA.getText());
            env.assertAnalysisPreserved();
            assertTrue(env.dialog.isVisible());
            assertEquals(previousPonders, env.engine.ponders);
          });
    }
  }

  @Test
  void closedDialogDoesNotApplyAfterThreadResponse() throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            env.check("chkEditThreads").setSelected(true);
            env.text("txtThreads").setText("10");
            env.confirm();
            env.dialog.setVisible(false);
          });
      env.ackThreads(10);
      env.awaitApplied();
      assertTrue(env.engine.commands.isEmpty());
      assertTrue(env.originalConfig.similar(Lizzie.config.uiConfig));
      env.assertAnalysisPreserved();
    }
  }

  @Test
  void hiddenPdaForSpecializedEngineIsNeitherValidatedNorSaved() throws Exception {
    try (Environment env = new Environment()) {
      onEdt(
          () -> {
            setField(env.dialog, "isPdaEngine", true);
            env.text("txtPDA").setText("NaN");
            env.check("chkPDAInMenu").setSelected(true);
            env.check("chkAutoLoadPDA").setSelected(true);
            env.confirm();
            assertEquals(List.of("kata-set-param analysisWideRootNoise 0.5"), env.engine.commands);
            assertEquals("-0.5", Lizzie.config.txtKataEnginePDA);
            assertFalse(Lizzie.config.showPDAInMenu);
            assertFalse(Lizzie.config.autoLoadKataEnginePDA);
            assertEquals("-0.5", Lizzie.config.uiConfig.getString("txt-kata-engine-pda"));
          });
    }
  }

  private final class Environment implements AutoCloseable {
    final Config previousConfig = Lizzie.config;
    final Board previousBoard = Lizzie.board;
    final Leelaz previousEngine = Lizzie.leelaz;
    final LizzieFrame previousFrame = Lizzie.frame;
    final Menu previousMenu = LizzieFrame.menu;
    final GtpConsolePane previousConsole = Lizzie.gtpConsole;
    final EngineGameSnapshot previousGame = Lizzie.engineGame.current();
    final WebBoardManager previousWebBoardManager = Lizzie.webBoardManager;
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final BoardData root = BoardData.empty(Board.boardWidth, Board.boardHeight);
    final BoardData current = BoardData.empty(Board.boardWidth, Board.boardHeight);
    final RecordingEngine engine;
    final TestDialog dialog;
    final JSONObject originalConfig;

    Environment() throws Exception {
      this("0.5", "-0.5", true);
    }

    Environment(String wrn, String pda, boolean enabled) throws Exception {
      Lizzie.config = ConfigTestHelper.createForTests(folder);
      Lizzie.config.config = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.uiConfig =
          new JSONObject()
              .put("unrelated", "preserved")
              .put("txt-kata-engine-wrn", wrn)
              .put("txt-kata-engine-pda", pda);
      Lizzie.config.txtKataEngineWRN = wrn;
      Lizzie.config.txtKataEnginePDA = pda;
      Lizzie.config.chkKataEngineWRN = Lizzie.config.chkKataEnginePDA = enabled;
      Lizzie.config.showWRNInMenu = Lizzie.config.showPDAInMenu = false;
      Lizzie.config.autoLoadKataEngineWRN = Lizzie.config.autoLoadKataEnginePDA = false;
      originalConfig = new JSONObject(Lizzie.config.uiConfig.toString());
      Lizzie.webBoardManager = null;
      Lizzie.frame = allocate(QuietFrame.class);
      Lizzie.gtpConsole = allocate(QuietConsole.class);
      engine = new RecordingEngine();
      hook(engine, "installFreshCommandOutputForTest", OutputStream.class, output);
      engine.started = engine.isLoaded = engine.isKatago = true;
      Lizzie.setPrimaryEngine(engine);
      LizzieFrame.menu = allocate(QuietMenu.class);
      root.setPlayouts(500);
      current.setPlayouts(1000);
      BoardHistoryList history = new BoardHistoryList(root);
      history.add(current);
      Lizzie.board = allocate(Board.class);
      Lizzie.board.setHistory(history);
      dialog = allocate(TestDialog.class);
      dialog.visible = true;
      onEdt(
          () -> {
            EngineGameSnapshotFixtures.publishIdle();
            Field bundle = Menu.class.getDeclaredField("resourceBundle");
            bundle.setAccessible(true);
            bundle.set(LizzieFrame.menu, Lizzie.resourceBundle);
            var initialize = Menu.class.getDeclaredMethod("initializeKataAnalysisFields");
            initialize.setAccessible(true);
            initialize.invoke(LizzieFrame.menu);
            LizzieFrame.menu.setUseWrn(enabled, wrn);
            LizzieFrame.menu.setUseGfPda(enabled, pda);
            setField(dialog, "openingEngine", engine);
            setField(dialog, "runtimeThreads", engine.captureRuntimeSearchThreads());
            for (String name :
                List.of(
                    "chkEditWRN",
                    "chkEditPDA",
                    "chkAutoLoadWRN",
                    "chkAutoLoadPDA",
                    "chkWRNInMenu",
                    "chkPDAInMenu",
                    "chkEditThreads")) setField(dialog, name, new JCheckBox());
            for (String name : List.of("txtWRN", "txtPDA", "txtThreads"))
              setField(dialog, name, new JFontTextField());
            setField(dialog, "btnApply", new JFontButton());
            setField(dialog, "btnCancel", new JFontButton());
            for (KataAnalysisParameter parameter : KataAnalysisParameter.values()) {
              var bind =
                  SetKataEngines.class.getDeclaredMethod(
                      "bindAnalysisField", JFontTextField.class, KataAnalysisParameter.class);
              bind.setAccessible(true);
              bind.invoke(null, text(parameter), parameter);
            }
            text("txtWRN").setText(wrn);
            text("txtPDA").setText(pda);
            check("chkEditWRN").setSelected(enabled);
            check("chkEditPDA").setSelected(enabled);
            // Unsafe allocation skips the constructor's capture of the displayed opening drafts.
            initialDraft("initialWrn", "chkEditWRN", "txtWRN");
            initialDraft("initialPda", "chkEditPDA", "txtPDA");
          });
    }

    void initialDraft(String fieldName, String enabledName, String valueName) throws Exception {
      Field field = SetKataEngines.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      var constructor = field.getType().getDeclaredConstructor(boolean.class, String.class);
      constructor.setAccessible(true);
      field.set(
          dialog,
          constructor.newInstance(check(enabledName).isSelected(), text(valueName).getText()));
    }

    JCheckBox check(String name) throws Exception {
      return (JCheckBox) field(dialog, name);
    }

    JFontTextField text(String name) throws Exception {
      return (JFontTextField) field(dialog, name);
    }

    JFontTextField text(KataAnalysisParameter parameter) throws Exception {
      return text(parameter == KataAnalysisParameter.WRN ? "txtWRN" : "txtPDA");
    }

    void confirm() throws Exception {
      var confirm = SetKataEngines.class.getDeclaredMethod("confirmSettings");
      confirm.setAccessible(true);
      confirm.invoke(dialog);
    }

    void beginThreadChange() throws Exception {
      check("chkEditThreads").setSelected(true);
      text("txtThreads").setText("10");
      confirm();
      assertTrue((boolean) field(dialog, "applyingThreads"));
      assertTrue(engine.commands.isEmpty(), engine.commands.toString());
    }

    void changeToolbarParameters() {
      LizzieFrame.menu.txtWRN.setText("0.9");
      if (!LizzieFrame.menu.chkWRN.isSelected()) LizzieFrame.menu.chkWRN.doClick(0);
      LizzieFrame.menu.txtGfPDA.setText("0.75");
      if (!LizzieFrame.menu.chkPDA.isSelected()) LizzieFrame.menu.chkPDA.doClick(0);
      assertEquals(
          List.of(
              command(KataAnalysisParameter.WRN, "0.9"),
              command(KataAnalysisParameter.PDA, "0.75")),
          engine.commands);
      assertToolbarValuesPreserved();
      engine.commands.clear();
      resetAnalysis();
    }

    void assertToolbarValuesPreserved() {
      assertEquals("0.9", Lizzie.config.txtKataEngineWRN);
      assertEquals("0.75", Lizzie.config.txtKataEnginePDA);
      assertEquals("0.9", Lizzie.config.uiConfig.getString("txt-kata-engine-wrn"));
      assertEquals("0.75", Lizzie.config.uiConfig.getString("txt-kata-engine-pda"));
      assertEquals("0.9", LizzieFrame.menu.txtWRN.getText());
      assertEquals("0.75", LizzieFrame.menu.txtGfPDA.getText());
      assertTrue(Lizzie.config.chkKataEngineWRN && Lizzie.config.chkKataEnginePDA);
    }

    void resetAnalysis() {
      // Represents fresh analysis acquired after the toolbar edit, before the delayed callback.
      root.setPlayouts(500);
      current.setPlayouts(1000);
      root.isChanged = current.isChanged = false;
    }

    void ackThreads(int threads) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      do {
        for (String line : output.toString(StandardCharsets.UTF_8).split("\\R")) {
          if (line.endsWith("kata-set-param numSearchThreads " + threads)) {
            hook(
                engine,
                "processCommandResponseLineForTest",
                String.class,
                "=" + line.substring(0, line.indexOf(' ')));
            return;
          }
        }
        Thread.sleep(5);
      } while (System.nanoTime() < deadline);
      fail("Missing thread command: " + output);
    }

    void awaitApplied() throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      boolean[] pending = new boolean[1];
      do {
        onEdt(() -> pending[0] = (boolean) field(dialog, "applyingThreads"));
        if (!pending[0]) return;
        Thread.sleep(5);
      } while (System.nanoTime() < deadline);
      fail("Thread completion did not reach the dialog");
    }

    void assertAnalysisPreserved() {
      assertFalse(root.isChanged);
      assertFalse(current.isChanged);
      assertEquals(500, root.getPlayouts());
      assertEquals(1000, current.getPlayouts());
    }

    void assertPreserved() {
      assertTrue(engine.commands.isEmpty(), engine.commands.toString());
      assertEquals("", output.toString(StandardCharsets.UTF_8));
      assertEquals(0, engine.ponders);
      assertTrue(originalConfig.similar(Lizzie.config.uiConfig));
      assertEquals("0.5", Lizzie.config.txtKataEngineWRN);
      assertEquals("-0.5", Lizzie.config.txtKataEnginePDA);
      assertTrue(Lizzie.config.chkKataEngineWRN && Lizzie.config.chkKataEnginePDA);
      assertFalse(Lizzie.config.autoLoadKataEngineWRN || Lizzie.config.autoLoadKataEnginePDA);
      assertFalse(Lizzie.config.showWRNInMenu || Lizzie.config.showPDAInMenu);
      assertAnalysisPreserved();
    }

    @Override
    public void close() throws Exception {
      onEdt(() -> dialog.setVisible(false));
      engine.isNormalEnd = true;
      engine.forceQuit();
      onEdt(
          () -> {
            Lizzie.config = previousConfig;
            Lizzie.board = previousBoard;
            Lizzie.frame = previousFrame;
            LizzieFrame.menu = previousMenu;
            Lizzie.gtpConsole = previousConsole;
            Lizzie.webBoardManager = previousWebBoardManager;
            Lizzie.setPrimaryEngine(previousEngine);
            Lizzie.engineGame.publishSnapshotForTest(previousGame);
          });
    }
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = SetKataEngines.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static String command(KataAnalysisParameter parameter, String value) {
    return "kata-set-param "
        + (parameter == KataAnalysisParameter.WRN
            ? "analysisWideRootNoise "
            : "playoutDoublingAdvantage ")
        + value;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = SetKataEngines.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void hook(Leelaz engine, String name, Class<?> type, Object value)
      throws Exception {
    var method = Leelaz.class.getDeclaredMethod(name, type);
    method.setAccessible(true);
    method.invoke(engine, value);
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void onEdt(CheckedRunnable action) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            action.run();
          } catch (Exception failure) {
            throw new RuntimeException(failure);
          }
        });
  }

  private interface CheckedRunnable {
    void run() throws Exception;
  }

  private static final class TestDialog extends SetKataEngines {
    boolean visible;
    int closeOperation;

    @Override
    public boolean isVisible() {
      return visible;
    }

    @Override
    public void setVisible(boolean visible) {
      this.visible = visible;
    }

    @Override
    public int getDefaultCloseOperation() {
      return closeOperation;
    }

    @Override
    public void setDefaultCloseOperation(int operation) {
      closeOperation = operation;
    }
  }

  private static final class RecordingEngine extends Leelaz {
    final List<String> commands = new ArrayList<>();
    int ponders;

    RecordingEngine() throws Exception {
      super("katago gtp");
    }

    @Override
    public void sendCommand(String command) {
      commands.add(command);
    }

    @Override
    public boolean isPondering() {
      return true;
    }

    @Override
    public void ponder() {
      ponders++;
    }
  }

  private static final class QuietMenu extends Menu {
    @Override
    public void updateMenuStatusForEngine() {}
  }

  private static final class QuietFrame extends LizzieFrame {
    @Override
    public void refresh() {}

    @Override
    public void resetTitle() {}
  }

  private static final class QuietConsole extends GtpConsolePane {
    QuietConsole() {
      super(null);
    }

    @Override
    public void addLine(String line) {}
  }
}
