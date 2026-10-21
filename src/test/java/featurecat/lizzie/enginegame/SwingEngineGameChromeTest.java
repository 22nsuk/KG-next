package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SwingEngineGameChromeTest {
  @Test
  void backgroundPublishDeliversOnEdt() throws Exception {
    AtomicBoolean onEdt = new AtomicBoolean();
    CountDownLatch delivered = new CountDownLatch(1);
    SwingEngineGameChrome chrome =
        new SwingEngineGameChrome() {
          @Override
          void applyOnEdt(EngineGameChromeTransition transition) {
            onEdt.set(SwingUtilities.isEventDispatchThread());
            delivered.countDown();
          }
        };

    chrome.publish(
        new EngineGameChromeTransition(
            EngineGameChromeTransition.Kind.STARTING, new EngineGameSnapshot.Idle()));

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertTrue(onEdt.get());
  }

  @Test
  void edtPublishAppliesImmediatelyOnEdt() throws Exception {
    AtomicBoolean onEdt = new AtomicBoolean();
    SwingUtilities.invokeAndWait(
        () -> {
          SwingEngineGameChrome chrome =
              new SwingEngineGameChrome() {
                @Override
                void applyOnEdt(EngineGameChromeTransition transition) {
                  onEdt.set(SwingUtilities.isEventDispatchThread());
                }
              };
          chrome.publish(
              new EngineGameChromeTransition(
                  EngineGameChromeTransition.Kind.PLAYING, new EngineGameSnapshot.Idle()));
        });
    assertTrue(onEdt.get());
  }

  @Test
  void userStoppedRestoresEngineMenuOffPlayingLabel() throws Exception {
    JFontMenu engineMenu = new JFontMenu("对战中");
    JFontMenu previousMenu = Menu.engineMenu;
    boolean previousEmpty = EngineManager.isEmpty;
    Menu.engineMenu = engineMenu;
    EngineManager.isEmpty = true;
    try {
      SwingUtilities.invokeAndWait(
          () ->
              new SwingEngineGameChrome()
                  .applyOnEdt(
                      new EngineGameChromeTransition(
                          EngineGameChromeTransition.Kind.USER_STOPPED,
                          new EngineGameSnapshot.Idle())));
      assertNotEquals("对战中", engineMenu.getText());
    } finally {
      Menu.engineMenu = previousMenu;
      EngineManager.isEmpty = previousEmpty;
    }
  }

  @Test
  void failedSaveShowsReachableRetryAndStaleFailureCannotReplaceIdleControls() throws Exception {
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Menu previousMenu = LizzieFrame.menu;
    JFontMenu previousEngineMenu = Menu.engineMenu;
    EngineGameSnapshot previousSnapshot = Lizzie.engineGame.current();
    RecordingToolbar toolbar = allocate(RecordingToolbar.class);
    toolbar.btnEnginePkStop = new JButton("pause");
    toolbar.btnStartPk = new JButton("stop");
    EngineParticipantIdentity first = new EngineParticipantIdentity("first", "");
    EngineParticipantIdentity second = new EngineParticipantIdentity("second", "");
    EngineGameView view =
        new EngineGameView(first, second, EngineGamePlayMode.GENMOVE, 1, 0, 1, 0, 1, true, -1);
    BatchSummary batch =
        new BatchSummary(
            first, second, 1, 1, true, 0, 0, 1, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, List.of());
    EngineGameSnapshot failed =
        new EngineGameSnapshot.BatchActive(batch, new GameActivity.SaveFailed(view));
    try {
      LizzieFrame.toolbar = toolbar;
      LizzieFrame.menu = null;
      Menu.engineMenu = new JFontMenu("playing");
      SwingUtilities.invokeAndWait(
          () -> {
            SwingEngineGameChrome chrome = new SwingEngineGameChrome();
            Lizzie.engineGame.publishSnapshotForTest(failed);
            chrome.applyOnEdt(
                new EngineGameChromeTransition(EngineGameChromeTransition.Kind.SAVE_FAILED, failed));

            assertFalse(toolbar.settingsEnabled);
            assertTrue(toolbar.btnEnginePkStop.isEnabled());
            assertEquals(
                Lizzie.resourceBundle.getString("EngineGameInfo.retrySave"),
                toolbar.btnEnginePkStop.getText());
            assertEquals(
                Lizzie.resourceBundle.getString("EngineGameInfo.saveFailed"),
                Menu.engineMenu.getText());

            EngineGameSnapshot idle = new EngineGameSnapshot.Idle();
            Lizzie.engineGame.publishSnapshotForTest(idle);
            chrome.applyOnEdt(
                new EngineGameChromeTransition(EngineGameChromeTransition.Kind.BATCH_ENDED, idle));
            String idleText = toolbar.btnEnginePkStop.getText();
            chrome.applyOnEdt(
                new EngineGameChromeTransition(EngineGameChromeTransition.Kind.SAVE_FAILED, failed));

            assertTrue(toolbar.settingsEnabled);
            assertEquals(idleText, toolbar.btnEnginePkStop.getText());
          });
    } finally {
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.menu = previousMenu;
      Menu.engineMenu = previousEngineMenu;
      Lizzie.engineGame.publishSnapshotForTest(previousSnapshot);
    }
  }

  private static final class RecordingToolbar extends BottomToolbar {
    boolean settingsEnabled;

    @Override
    public void enableDisabelForEngineGame(boolean enable) {
      settingsEnabled = enable;
      btnEnginePkStop.setText("pause");
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
