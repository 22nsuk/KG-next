package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.ManualRulesSelection;
import featurecat.lizzie.analysis.RulePreset;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import javax.swing.JPopupMenu;
import javax.swing.Timer;

/** Captures a popup's target; the existing manual-selection owner applies and confirms its choice. */
final class RulesQuickSwitch implements RulesQuickMenu.Actions {
  private final Leelaz engine;
  private final long engineGeneration;
  private final Board board;
  private final BoardHistoryList history;
  private final GameInfo game;
  private final long gameRevision;
  private final LizzieFrame frame;
  private final ResourceBundle strings;
  private final Consumer<String> showMessage;

  RulesQuickSwitch(
      Leelaz engine, Board board, LizzieFrame frame,
      ResourceBundle strings, Consumer<String> showMessage) {
    this.engine = engine;
    this.engineGeneration = Lizzie.capturePrimaryEngineGeneration(engine);
    this.board = board;
    this.history = board.getHistory();
    this.game = history.getGameInfo();
    this.gameRevision = game.rulesMetadataRevision();
    this.frame = frame;
    this.strings = strings;
    this.showMessage = showMessage;
  }

  JPopupMenu menu() {
    boolean handicap = game.getHandicap() > 0;
    return RulesQuickMenu.build(strings,
        RulePreset.current(engine.currentConfirmedRules(), game.getKomi(), handicap),
        handicap, this);
  }

  private boolean currentContext() {
    return Lizzie.board == board && board.getHistory() == history
        && history.getGameInfo() == game && Lizzie.frame == frame
        && Lizzie.capturePrimaryEngineGenerationSnapshot(engine) == engineGeneration;
  }

  private boolean available() {
    String reason = currentContext() && game.rulesMetadataRevision() == gameRevision
        ? frame.rulesUnavailableReason(engine) : "RulesQuickMenu.contextChanged";
    if (reason == null) return true;
    showMessage.accept(strings.getString(reason));
    return false;
  }

  @Override
  public void apply(RulePreset preset) {
    if (!available()) return;
    ManualRulesSelection selection = ManualRulesSelection.apply(engine, preset);
    if (selection == null) {
      showMessage.accept(strings.getString("AnalysisSettings.reuseStatus.existing_lease"));
      return;
    }
    if (selection.unchanged()) return;
    Timer completion = new Timer(100, null);
    completion.addActionListener(event -> {
      if (!currentContext()) {
        completion.stop();
      } else if (selection.isDone()) {
        completion.stop();
        if (selection.isLatestSelection() && !selection.applied()) {
          showMessage.accept(strings.getString("SetKataRules.status.setFailed"));
        }
      }
    });
    completion.start();
  }

  @Override
  public void openRulesDialog() {
    if (available()) frame.setRules();
  }
}
