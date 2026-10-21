package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.BatchSummary;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameView;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.GameActivity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class MenuEngineGameSaveStateTest {
  @Test
  void retryRemainsReachableAfterEnginesRetireAndNewGameWaitsForSave() throws Exception {
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz previousEngine = Lizzie.leelaz;
    JFontMenu previousEngineMenu = Menu.engineMenu;
    EngineGameSnapshot previousSnapshot = Lizzie.engineGame.current();
    Menu menu = allocate(Menu.class);
    JFontButton pause = new JFontButton();
    JFontButton stop = new JFontButton();
    JFontButton newGame = new JFontButton();
    set(menu, "resourceBundle", Lizzie.resourceBundle);
    set(menu, "doubleMenuPauseGame", pause);
    set(menu, "doubleMenuStopGame", stop);
    set(menu, "doubleMenuNewGame", newGame);
    set(menu, "doubleMenuResign", new JFontButton());
    Method apply = Menu.class.getDeclaredMethod("applyDoubleMenuGameStatus");
    apply.setAccessible(true);
    EngineParticipantIdentity first = new EngineParticipantIdentity("first", "");
    EngineParticipantIdentity second = new EngineParticipantIdentity("second", "");
    EngineGameView view =
        new EngineGameView(first, second, EngineGamePlayMode.GENMOVE, 1, 0, 1, 0, 1, true, -1);
    BatchSummary batch =
        new BatchSummary(
            first, second, 1, 1, true, 0, 0, 1, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, List.of());
    try {
      Lizzie.config = allocate(Config.class);
      Lizzie.config.showDoubleMenu = true;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.leelaz = null;
      Menu.engineMenu = new JFontMenu();
      SwingUtilities.invokeAndWait(
          () -> {
            Lizzie.engineGame.publishSnapshotForTest(
                new EngineGameSnapshot.BatchActive(batch, new GameActivity.SaveFailed(view)));
            apply(apply, menu);

            assertTrue(pause.isEnabled());
            assertEquals(
                Lizzie.resourceBundle.getString("EngineGameInfo.retrySave"), pause.getText());
            assertTrue(stop.isVisible());
            assertFalse(newGame.isVisible());

            Lizzie.engineGame.publishSnapshotForTest(
                new EngineGameSnapshot.BatchActive(batch, new GameActivity.BetweenGames()));
            apply(apply, menu);
            assertFalse(pause.isEnabled());
            assertTrue(stop.isVisible());
            assertFalse(newGame.isVisible());

            Lizzie.engineGame.publishSnapshotForTest(new EngineGameSnapshot.Idle());
            apply(apply, menu);
            assertFalse(pause.isEnabled());
            assertEquals(Lizzie.resourceBundle.getString("Menu.pauseGameBtn"), pause.getText());
            assertFalse(stop.isVisible());
            assertTrue(newGame.isVisible());
          });
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
      Menu.engineMenu = previousEngineMenu;
      Lizzie.engineGame.publishSnapshotForTest(previousSnapshot);
    }
  }

  private static void apply(Method apply, Menu menu) {
    try {
      apply.invoke(menu);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void set(Menu menu, String name, Object value) throws Exception {
    Field field = Menu.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(menu, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
