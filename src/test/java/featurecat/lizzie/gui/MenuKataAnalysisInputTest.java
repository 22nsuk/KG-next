package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameSnapshotFixtures;
import featurecat.lizzie.enginegame.GameActivity;
import featurecat.lizzie.enginegame.RunState;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.text.AbstractDocument;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MenuKataAnalysisInputTest {
  private Config previousConfig;
  private Board previousBoard;
  private Leelaz previousEngine;
  private EngineManager previousManager;
  private LizzieFrame previousFrame;
  private Menu previousMenu;
  private EngineGameSnapshot previousSnapshot;
  private Menu menu;
  private RecordingEngine engine;
  private BoardData root;
  private BoardData current;

  @BeforeEach
  void setUp() throws Exception {
    onEdt(
        () -> {
          previousConfig = Lizzie.config;
          previousBoard = Lizzie.board;
          previousEngine = Lizzie.leelaz;
          previousManager = Lizzie.engineManager;
          previousFrame = Lizzie.frame;
          previousMenu = LizzieFrame.menu;
          previousSnapshot = Lizzie.engineGame.current();
          Lizzie.config = allocate(Config.class);
          Lizzie.config.uiConfig = new JSONObject();
          Lizzie.config.txtKataEngineWRN = "0.5";
          Lizzie.config.txtKataEnginePDA = "-0.5";
          Lizzie.config.chkKataEngineWRN = true;
          Lizzie.config.chkKataEnginePDA = true;
          Lizzie.config.uiConfig.put("txt-kata-engine-wrn", "0.5");
          Lizzie.config.uiConfig.put("txt-kata-engine-pda", "-0.5");
          Lizzie.config.uiConfig.put("chk-kata-engine-wrn", true);
          Lizzie.config.uiConfig.put("chk-kata-engine-pda", true);
          Lizzie.config.uiConfig.put("unrelated", "preserved");
          root = BoardData.empty(Board.boardWidth, Board.boardHeight);
          current = BoardData.empty(Board.boardWidth, Board.boardHeight);
          root.setPlayouts(500);
          current.setPlayouts(1000);
          BoardHistoryList history = new BoardHistoryList(root);
          history.add(current);
          Lizzie.board = allocate(Board.class);
          Lizzie.board.setHistory(history);
          engine = recordingEngine();
          Lizzie.leelaz = engine;
          Lizzie.engineManager = null;
          Lizzie.frame = allocate(LizzieFrame.class);
          EngineGameSnapshotFixtures.publishIdle();
          menu = allocate(Menu.class);
          Field bundle = Menu.class.getDeclaredField("resourceBundle");
          bundle.setAccessible(true);
          bundle.set(menu, Lizzie.resourceBundle);
          var initialize = Menu.class.getDeclaredMethod("initializeKataAnalysisFields");
          initialize.setAccessible(true);
          initialize.invoke(menu);
          LizzieFrame.menu = menu;
          menu.setPdaAndWrn(-0.5, 0.5);
        });
  }

  @AfterEach
  void tearDown() throws Exception {
    onEdt(
        () -> {
          Lizzie.config = previousConfig;
          Lizzie.board = previousBoard;
          Lizzie.leelaz = previousEngine;
          Lizzie.engineManager = previousManager;
          Lizzie.frame = previousFrame;
          LizzieFrame.menu = previousMenu;
          Lizzie.engineGame.publishSnapshotForTest(previousSnapshot);
        });
  }

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
                            "not a number"),
                        parameter == KataAnalysisParameter.WRN
                            ? Stream.of("-0.1", "2.1")
                            : Stream.of("-3.1", "3.1"))
                    .map(draft -> Arguments.of(parameter, draft)));
  }

  @ParameterizedTest
  @MethodSource("invalidDrafts")
  void invalidDocumentEventsPreserveSettingsEngineAndAnalysis(
      KataAnalysisParameter parameter, String draft) throws Exception {
    onEdt(
        () -> {
          JSONObject before = new JSONObject(Lizzie.config.uiConfig.toString());
          field(parameter).setText(draft); // Actual removeUpdate and insertUpdate wiring.
          AbstractDocument document = (AbstractDocument) field(parameter).getDocument();
          var change =
              document
              .new DefaultDocumentEvent(0, document.getLength(), DocumentEvent.EventType.CHANGE);
          change.end();
          for (var listener : document.getDocumentListeners()) {
            listener.changedUpdate(change);
          }
          assertUnchanged(before);
          assertEquals(draft, field(parameter).getText());
          assertEquals(AppleStyleSupport.errorFieldBackground(), field(parameter).getBackground());
        });
  }

  @ParameterizedTest
  @CsvSource({"WRN,true", "WRN,false", "PDA,true", "PDA,false"})
  void invalidCheckboxActionsRollBackWithoutApplyingADefault(
      KataAnalysisParameter parameter, boolean wasEnabled) throws Exception {
    onEdt(
        () -> {
          configureEnabled(parameter, wasEnabled);
          JSONObject before = new JSONObject(Lizzie.config.uiConfig.toString());
          for (String draft : List.of("", "-", "NaN", "Infinity", "99")) {
            field(parameter).setText(draft);
            checkbox(parameter).doClick(0);
            assertEquals(wasEnabled, checkbox(parameter).isSelected());
            assertTrue(field(parameter).isEnabled(), "A rejected value must remain repairable");
            assertEquals(draft, field(parameter).getText());
            assertUnchanged(before);
          }
        });
  }

  @ParameterizedTest
  @CsvSource({"WRN,0", "WRN,2", "WRN,1.25", "PDA,-3", "PDA,0", "PDA,3", "PDA,-1.25"})
  void validReplacementAppliesOnlyTheFinalValueAndOnlyWrnInvalidatesAnalysis(
      KataAnalysisParameter parameter, String draft) throws Exception {
    onEdt(
        () -> {
          field(parameter).setText(draft);
          String canonical = String.valueOf(Double.parseDouble(draft));
          assertEquals(List.of(command(parameter, canonical)), engine.commands);
          assertEquals(canonical, appliedText(parameter));
          assertEquals(canonical, Lizzie.config.uiConfig.getString(parameter.configKey));
          assertEquals(1, engine.ponders);
          assertEquals(parameter == KataAnalysisParameter.WRN, root.isChanged);
          assertEquals(parameter == KataAnalysisParameter.WRN, current.isChanged);
          assertEquals(AppleStyleSupport.validFieldBackground(), field(parameter).getBackground());
        });
  }

  @ParameterizedTest
  @EnumSource(KataAnalysisParameter.class)
  void validSelectionStillReachesEngineWhenSavedSettingMatches(KataAnalysisParameter parameter)
      throws Exception {
    onEdt(
        () -> {
          // An engine status refresh can differ from the saved auto-load preference.
          if (parameter == KataAnalysisParameter.WRN) menu.setUseWrn(false, "0");
          else menu.setUseGfPda(false, "0");
          field(parameter).setText(appliedText(parameter));
          assertTrue(engine.commands.isEmpty());
          checkbox(parameter).doClick(0);
          assertEquals(List.of(command(parameter, appliedText(parameter))), engine.commands);
        });
  }

  @ParameterizedTest
  @EnumSource(KataAnalysisParameter.class)
  void disabledDraftCanBeRepairedThenEnabledAndDisabled(KataAnalysisParameter parameter)
      throws Exception {
    onEdt(
        () -> {
          configureEnabled(parameter, false);
          JSONObject before = new JSONObject(Lizzie.config.uiConfig.toString());
          field(parameter).setText("");
          checkbox(parameter).doClick(0);
          assertFalse(checkbox(parameter).isSelected());
          field(parameter).setText("1.25");
          assertUnchanged(before);
          checkbox(parameter).doClick(0);
          assertTrue(checkbox(parameter).isSelected());
          assertEquals(List.of(command(parameter, "1.25")), engine.commands);
          assertEquals("1.25", appliedText(parameter));
          checkbox(parameter).doClick(0);
          assertEquals(
              List.of(command(parameter, "1.25"), command(parameter, "0.0")), engine.commands);
          assertEquals("1.25", appliedText(parameter), "Disabling retains the last valid value");
          assertFalse(field(parameter).isEnabled());
        });
  }

  @Test
  void unavailableEngineRejectsAlreadyQueuedValidDraftAndCheckboxEvent() throws Exception {
    onEdt(
        () -> {
          JSONObject before = new JSONObject(Lizzie.config.uiConfig.toString());
          engine.breadthBlocked = true;
          // Admission can change before the deferred enabled-state update reaches the EDT.
          menu.txtWRN.setText("1.25");
          menu.chkWRN.setSelected(false);
          for (var listener : menu.chkWRN.getActionListeners()) {
            listener.actionPerformed(new java.awt.event.ActionEvent(menu.chkWRN, 0, "queued"));
          }
          assertUnchanged(before);
          assertTrue(menu.chkWRN.isSelected());
          assertFalse(menu.chkWRN.isEnabled());
          assertFalse(menu.txtWRN.isEnabled());
          assertEquals("1.25", menu.txtWRN.getText());
          engine.breadthBlocked = false;
          menu.refreshBreadthAdmission();
          assertTrue(menu.chkWRN.isEnabled());
          assertTrue(menu.txtWRN.isEnabled());
          assertUnchanged(before);
        });
  }

  @Test
  void humanGameRejectsValidBreadthWithoutClearingAnalysis() throws Exception {
    onEdt(
        () -> {
          JSONObject before = new JSONObject(Lizzie.config.uiConfig.toString());
          Lizzie.frame.isPlayingAgainstLeelaz = true;
          menu.txtWRN.setText("1.25");
          assertUnchanged(before);
          assertFalse(menu.chkWRN.isEnabled());
        });
  }

  @Test
  void engineDisplayRefreshDoesNotApplyAndClearsObsoleteInputErrors() throws Exception {
    onEdt(
        () -> {
          JSONObject before = new JSONObject(Lizzie.config.uiConfig.toString());
          menu.txtWRN.setText("-");
          menu.txtGfPDA.setText("-");
          menu.setPdaAndWrn(-1, 1);
          assertUnchanged(before);
          assertEquals(AppleStyleSupport.validFieldBackground(), menu.txtWRN.getBackground());
          assertEquals(AppleStyleSupport.validFieldBackground(), menu.txtGfPDA.getBackground());
        });
  }

  @ParameterizedTest
  @CsvSource({"WRN,true", "WRN,false", "PDA,true", "PDA,false"})
  void analysisGamesRejectQueuedToolbarEditsUntilTheirOwnershipEnds(
      KataAnalysisParameter parameter, boolean starting) throws Exception {
    onEdt(
        () -> {
          RecordingEngine second = publishGame(EngineGamePlayMode.ANALYSIS, starting);
          JSONObject before = new JSONObject(Lizzie.config.uiConfig.toString());

          // The game can take ownership before the deferred toolbar refresh reaches the EDT.
          for (String draft : List.of("-", "1.25")) {
            field(parameter).setText(draft);
            queuedCheckboxAction(parameter, false);
            assertUnchanged(before);
            assertTrue(second.commands.isEmpty());
            assertEquals(0, second.ponders);
            assertTrue(checkbox(parameter).isSelected(), "a queued toggle must roll back");
            assertFalse(checkbox(parameter).isEnabled());
            assertFalse(field(parameter).isEnabled());
            assertEquals(draft, field(parameter).getText());
            assertEquals(
                Lizzie.resourceBundle.getString("EngineGameParameters.useGamePanel"),
                field(parameter).getToolTipText());
          }

          EngineGameSnapshotFixtures.publishIdle();
          menu.refreshBreadthAdmission();
          assertTrue(checkbox(parameter).isEnabled());
          assertTrue(field(parameter).isEnabled());
          assertUnchanged(before); // Releasing ownership does not replay the blocked draft.
          field(parameter).setText("1.5");
          assertEquals(List.of(command(parameter, "1.5")), engine.commands);
          assertTrue(second.commands.isEmpty(), "ordinary analysis only changes the primary");
          assertEquals("1.5", appliedText(parameter));
          assertEquals(1, engine.ponders);
          assertEquals(parameter == KataAnalysisParameter.WRN, root.isChanged);
          assertEquals(parameter == KataAnalysisParameter.WRN, current.isChanged);
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void genmoveGamesKeepWrnLockedAndValidatePdaForBothParticipants(boolean starting)
      throws Exception {
    onEdt(
        () -> {
          RecordingEngine second = publishGame(EngineGamePlayMode.GENMOVE, starting);
          JSONObject before = new JSONObject(Lizzie.config.uiConfig.toString());
          menu.txtWRN.setText("1.25");
          queuedCheckboxAction(KataAnalysisParameter.WRN, false);
          assertUnchanged(before);
          assertTrue(second.commands.isEmpty());
          assertTrue(menu.chkWRN.isSelected());
          assertFalse(menu.chkWRN.isEnabled());
          assertFalse(menu.txtWRN.isEnabled());
          assertTrue(menu.chkPDA.isEnabled());
          assertTrue(menu.txtGfPDA.isEnabled());

          for (String draft : List.of("-", "NaN", "-3.1", "3.1")) {
            menu.txtGfPDA.setText(draft);
            menu.chkPDA.doClick(0);
            assertUnchanged(before);
            assertTrue(second.commands.isEmpty());
            assertTrue(menu.chkPDA.isSelected(), "invalid PDA must not apply a default");
            assertTrue(menu.txtGfPDA.isEnabled(), "invalid PDA remains repairable in genmove");
            assertEquals(draft, menu.txtGfPDA.getText());
            assertEquals(
                AppleStyleSupport.errorFieldBackground(), menu.txtGfPDA.getBackground());
          }

          menu.txtGfPDA.setText("1.25");
          assertEquals(List.of(command(KataAnalysisParameter.PDA, "1.25")), engine.commands);
          assertEquals(engine.commands, second.commands);
          assertEquals("1.25", Lizzie.config.txtKataEnginePDA);
          assertEquals("1.25", Lizzie.config.uiConfig.getString("txt-kata-engine-pda"));
          assertEquals(AppleStyleSupport.validFieldBackground(), menu.txtGfPDA.getBackground());
          menu.chkPDA.doClick(0);
          assertEquals(
              List.of(
                  command(KataAnalysisParameter.PDA, "1.25"),
                  command(KataAnalysisParameter.PDA, "0.0")),
              engine.commands);
          assertEquals(engine.commands, second.commands);
          assertEquals("1.25", Lizzie.config.txtKataEnginePDA, "disable retains the applied value");
          assertFalse(Lizzie.config.chkKataEnginePDA);
          assertFalse(menu.chkPDA.isSelected());
          assertFalse(menu.txtGfPDA.isEnabled());
          assertEquals(2, engine.ponders);
          assertEquals(0, second.ponders);
          assertFalse(root.isChanged);
          assertFalse(current.isChanged);
          assertEquals(500, root.getPlayouts());
          assertEquals(1000, current.getPlayouts());
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void gameRestoreUsesAppliedWrnAndPreservesAnInvalidDraft(boolean genmove) throws Exception {
    onEdt(
        () -> {
          menu.txtWRN.setText("99");
          Lizzie.config.disableWRNInGame = true;
          Lizzie.frame.isAnaPlayingAgainstLeelaz = true;
          Lizzie.frame.clearWRNforGame(genmove);
          engine.commands.clear();
          Lizzie.frame.restoreWRN(genmove);
          assertEquals(List.of(command(KataAnalysisParameter.WRN, "0.5")), engine.commands);
          assertEquals("99", menu.txtWRN.getText());
          assertEquals("0.5", Lizzie.config.txtKataEngineWRN);
          assertTrue(Lizzie.config.chkKataEngineWRN);
          assertFalse(root.isChanged);
          assertFalse(current.isChanged);
        });
  }

  @Test
  void disabledWrnRestoresZeroInsteadOfTheRememberedNonzeroValue() throws Exception {
    onEdt(
        () -> {
          configureEnabled(KataAnalysisParameter.WRN, false);
          menu.txtWRN.setText("99");
          Lizzie.frame.clearWRNforGame(true);
          Lizzie.frame.restoreWRN(true);
          assertEquals(List.of(command(KataAnalysisParameter.WRN, "0.0")), engine.commands);
          assertFalse(menu.chkWRN.isSelected());
          assertFalse(Lizzie.config.chkKataEngineWRN);
          assertEquals("0.5", Lizzie.config.txtKataEngineWRN);
          assertEquals("99", menu.txtWRN.getText());
        });
  }

  private RecordingEngine publishGame(EngineGamePlayMode mode, boolean starting) throws Exception {
    RecordingEngine second = recordingEngine();
    EngineGameSnapshot.BatchActive playing =
        (EngineGameSnapshot.BatchActive) EngineGameSnapshotFixtures.playing(mode, RunState.RUNNING);
    engine.oriEngineCommand = playing.batch().first().commands();
    engine.oriEnginename = playing.batch().first().name();
    second.oriEngineCommand = playing.batch().second().commands();
    second.oriEnginename = playing.batch().second().name();
    Lizzie.engineManager = new EngineManager(List.of(engine, second));
    Lizzie.engineGame.publishSnapshotForTest(
        starting
            ? new EngineGameSnapshot.BatchActive(
                playing.batch(), new GameActivity.Starting(playing.view()))
            : playing);
    return second;
  }

  private void queuedCheckboxAction(KataAnalysisParameter parameter, boolean selected) {
    JFontCheckBox checkbox = checkbox(parameter);
    checkbox.setSelected(selected);
    for (var listener : checkbox.getActionListeners()) {
      listener.actionPerformed(new java.awt.event.ActionEvent(checkbox, 0, "queued"));
    }
  }

  private void configureEnabled(KataAnalysisParameter parameter, boolean enabled) {
    if (parameter == KataAnalysisParameter.WRN) {
      Lizzie.config.chkKataEngineWRN = enabled;
      Lizzie.config.uiConfig.put("chk-kata-engine-wrn", enabled);
      menu.setUseWrn(enabled, Lizzie.config.txtKataEngineWRN);
    } else {
      Lizzie.config.chkKataEnginePDA = enabled;
      Lizzie.config.uiConfig.put("chk-kata-engine-pda", enabled);
      menu.setUseGfPda(enabled, Lizzie.config.txtKataEnginePDA);
    }
  }

  private JFontTextField field(KataAnalysisParameter parameter) {
    return parameter == KataAnalysisParameter.WRN ? menu.txtWRN : menu.txtGfPDA;
  }

  private JFontCheckBox checkbox(KataAnalysisParameter parameter) {
    return parameter == KataAnalysisParameter.WRN ? menu.chkWRN : menu.chkPDA;
  }

  private String appliedText(KataAnalysisParameter parameter) {
    return parameter == KataAnalysisParameter.WRN
        ? Lizzie.config.txtKataEngineWRN
        : Lizzie.config.txtKataEnginePDA;
  }

  private static String command(KataAnalysisParameter parameter, String value) {
    return "kata-set-param "
        + (parameter == KataAnalysisParameter.WRN
            ? "analysisWideRootNoise "
            : "playoutDoublingAdvantage ")
        + value;
  }

  private void assertUnchanged(JSONObject before) {
    assertTrue(engine.commands.isEmpty(), engine.commands.toString());
    assertEquals(0, engine.ponders);
    assertTrue(before.similar(Lizzie.config.uiConfig));
    assertEquals("0.5", Lizzie.config.txtKataEngineWRN);
    assertEquals("-0.5", Lizzie.config.txtKataEnginePDA);
    assertEquals(before.getBoolean("chk-kata-engine-wrn"), Lizzie.config.chkKataEngineWRN);
    assertEquals(before.getBoolean("chk-kata-engine-pda"), Lizzie.config.chkKataEnginePDA);
    assertFalse(root.isChanged);
    assertFalse(current.isChanged);
    assertEquals(500, root.getPlayouts());
    assertEquals(1000, current.getPlayouts());
  }

  private static RecordingEngine recordingEngine() throws Exception {
    RecordingEngine result = allocate(RecordingEngine.class);
    result.commands = new ArrayList<>();
    result.isKatago = true;
    return result;
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafe.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) unsafe.get(null)).allocateInstance(type));
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

  private static final class RecordingEngine extends Leelaz {
    List<String> commands;
    int ponders;
    boolean breadthBlocked;

    @Override
    public boolean canSetAnalysisWideRootNoise() {
      return !breadthBlocked;
    }

    private RecordingEngine() throws Exception {
      super("");
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
}
