package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.enginegame.EngineGameControl;
import featurecat.lizzie.enginegame.EngineGamePlan;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameTransaction;
import featurecat.lizzie.enginegame.EngineGameView;
import featurecat.lizzie.enginegame.GameActivity;
import featurecat.lizzie.enginegame.RunState;
import featurecat.lizzie.util.Utils;

/**
 * Desktop command adapter. Parses live-limit text and routes start/stop/pause/resume to Control
 * without using Swing clicks or toolbar flags as a command channel.
 */
public final class EngineGameDesktop {
  private EngineGameDesktop() {}

  public static boolean batchActive() {
    return !(Lizzie.engineGame.current() instanceof EngineGameSnapshot.Idle)
        || EngineManager.occupiesEngineGameAdmission();
  }

  /** Analysis-batch parameters remain owned across games and until restoration completes. */
  public static boolean parametersOwned() {
    if (EngineManager.hasUnrestoredEngineGameParameters()) return true;
    EngineGameSnapshot snapshot = Lizzie.engineGame.current();
    if (snapshot instanceof EngineGameSnapshot.Idle) return false;
    EngineGameView view = snapshot.view();
    if (view != null) return !view.genmove();
    // BetweenGames has no view, but the module retains the completed game's plan until retirement.
    EngineGamePlan plan = Lizzie.engineGame.firstPlan();
    return plan != null && plan.playMode() == EngineGamePlayMode.ANALYSIS;
  }

  public static void stop() {
    Lizzie.engineGame.stop();
  }

  public static void togglePause() {
    togglePause(Lizzie.engineGame);
  }

  static void togglePause(EngineGameControl control) {
    EngineGameSnapshot snapshot = Lizzie.engineGame.current();
    if (snapshot.saveFailed()) {
      control.retrySave();
      return;
    }
    if (!(snapshot instanceof EngineGameSnapshot.BatchActive active)
        || !(active.activity() instanceof GameActivity.Playing playing)) {
      return;
    }
    if (playing.runState() == RunState.PAUSED) {
      EngineGameTransaction product = Lizzie.engineGame.transaction();
      if (product != null
          && product.plan() != null
          && product.plan().playMode() == EngineGamePlayMode.GENMOVE
          && !product.genmovePauseSettled()) {
        if (Lizzie.resourceBundle != null) {
          Utils.showMsg(Lizzie.resourceBundle.getString("BottomToolbar.genmoveStopHint"));
        }
        return;
      }
      control.resume();
      return;
    }
    control.pause();
  }

  public static boolean reviseLiveBatchLimit(String text) {
    return reviseLiveBatchLimit(Lizzie.engineGame, text);
  }

  static boolean reviseLiveBatchLimit(EngineGameControl control, String text) {
    Integer parsed = parseLimit(text);
    if (parsed == null) {
      return false;
    }
    control.reviseBatchLimit(parsed);
    return true;
  }

  private static Integer parseLimit(String text) {
    if (text == null) {
      return null;
    }
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException invalid) {
      return null;
    }
  }
}
