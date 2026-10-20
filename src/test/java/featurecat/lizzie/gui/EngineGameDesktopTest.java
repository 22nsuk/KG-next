package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.Acceptance;
import featurecat.lizzie.enginegame.BatchSummary;
import featurecat.lizzie.enginegame.EngineGameBatchSpec;
import featurecat.lizzie.enginegame.EngineGameControl;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameView;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.GameActivity;
import featurecat.lizzie.enginegame.StartObserver;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineGameDesktopTest {
  @Test
  void invalidLiveLimitDoesNotCallReviseBatchLimit() {
    CountingControl control = new CountingControl();

    assertFalse(EngineGameDesktop.reviseLiveBatchLimit(control, "abc"));
    assertFalse(EngineGameDesktop.reviseLiveBatchLimit(control, " "));
    assertFalse(EngineGameDesktop.reviseLiveBatchLimit(control, null));
    assertEquals(0, control.reviseCalls);
  }

  @Test
  void validLiveLimitCallsReviseBatchLimitOnce() {
    CountingControl control = new CountingControl();

    assertTrue(EngineGameDesktop.reviseLiveBatchLimit(control, " 8 "));
    assertEquals(1, control.reviseCalls);
    assertEquals(8, control.lastLimit);
  }

  @Test
  void pauseCommandRetriesFailedSaveWithoutResumingRetiredEngines() {
    EngineGameSnapshot previous = Lizzie.engineGame.current();
    CountingControl control = new CountingControl();
    EngineParticipantIdentity first = new EngineParticipantIdentity("first", "");
    EngineParticipantIdentity second = new EngineParticipantIdentity("second", "");
    EngineGameView view =
        new EngineGameView(first, second, EngineGamePlayMode.GENMOVE, 1, 0, 1, 0, 1, true, -1);
    BatchSummary batch =
        new BatchSummary(
            first, second, 1, 1, true, 0, 0, 1, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, List.of());
    try {
      Lizzie.engineGame.publishSnapshotForTest(
          new EngineGameSnapshot.BatchActive(batch, new GameActivity.SaveFailed(view)));

      assertTrue(EngineGameDesktop.batchActive());
      EngineGameDesktop.togglePause(control);

      assertEquals(1, control.retrySaveCalls);
      assertEquals(0, control.pauseCalls);
      assertEquals(0, control.resumeCalls);

      Lizzie.engineGame.publishSnapshotForTest(
          new EngineGameSnapshot.BatchActive(batch, new GameActivity.BetweenGames()));
      EngineGameDesktop.togglePause(control);
      assertEquals(1, control.retrySaveCalls);
    } finally {
      Lizzie.engineGame.publishSnapshotForTest(previous);
    }
  }

  private static final class CountingControl implements EngineGameControl {
    private int reviseCalls;
    private int lastLimit;
    private int pauseCalls;
    private int resumeCalls;
    private int retrySaveCalls;

    @Override
    public Acceptance accept(EngineGameBatchSpec spec, StartObserver observer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void stop() {}

    @Override
    public void pause() {
      pauseCalls++;
    }

    @Override
    public void resume() {
      resumeCalls++;
    }

    @Override
    public void retrySave() {
      retrySaveCalls++;
    }

    @Override
    public boolean reviseKomi(double value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void reviseBatchLimit(int gameCount) {
      reviseCalls++;
      lastLimit = gameCount;
    }
  }
}
