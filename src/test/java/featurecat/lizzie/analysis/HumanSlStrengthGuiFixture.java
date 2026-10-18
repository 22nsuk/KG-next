package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Controlled computation and synthetic analysis for the actual Swing progress-dialog tests. */
public final class HumanSlStrengthGuiFixture {
  private HumanSlStrengthGuiFixture() {}

  public static void loadSyntheticGame() {
    if (!SGFParser.loadFromString(
        "(;GM[1]FF[4]SZ[19]KM[7.5]RU[Chinese]PB[Synthetic Black]PW[Synthetic White]"
            + "C[Synthetic UI example; no measured player strength]"
            + ";B[dd];W[pp];B[pd];W[dp];B[cf];W[cn];B[qf];W[qn];B[dk];W[pk];B[jd];W[jp])",
        false)) throw new AssertionError("Synthetic SGF did not load");
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    while (true) {
      var data = node.getData();
      var next = node.next(true);
      String played =
          next.flatMap(n -> n.getData().lastMove)
              .map(p -> Board.convertCoordinatesToName(p[0], p[1]))
              .orElse("pass");
      synchronized (data) {
        data.isKataData = true;
        data.scoreMean = 0;
        data.winrate = 50;
        data.setPlayouts(100);
        data.engineName = "Synthetic ordinary analysis";
        MoveData move = new MoveData();
        move.coordinate = played;
        move.order = 0;
        move.isKataData = true;
        move.scoreMean = 0;
        move.winrate = 50;
        move.policy = 100;
        move.playouts = 100;
        move.variation = List.of(played);
        data.bestMoves = List.of(move);
      }
      if (next.isEmpty()) break;
      node = next.get();
    }
  }

  public static final class Manual implements AutoCloseable {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch cancelled = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private final HumanSlStrengthPreparation preparation =
        new HumanSlStrengthPreparation(
            () ->
                new HumanSlStrengthPreparation.Worker() {
                  public JSONObject prepare(
                      JSONObject game, AtomicBoolean cancellation, IntConsumer progress)
                      throws Exception {
                    started.countDown();
                    if (!release.await(15, TimeUnit.SECONDS))
                      throw new AssertionError("The controlled supplement was never released");
                    // Return even after cancellation to test rejection of an already-computed result.
                    JSONObject policies = new JSONObject();
                    for (String profile : HumanSlStrength40.PROFILES) {
                      JSONArray positions = new JSONArray();
                      for (int i = 0; i <= game.getJSONArray("moves").length(); i++) {
                        JSONArray policy = new JSONArray();
                        for (int point = 0; point < 362; point++) policy.put(1.0 / 362);
                        positions.put(policy);
                      }
                      policies.put(profile, positions);
                    }
                    progress.accept(game.getJSONArray("moves").length());
                    return policies;
                  }

                  public void cancel() {
                    cancelled.countDown();
                  }

                  public void close() {
                    closed.countDown();
                  }
                });

    public HumanSlStrengthPreparation preparation() {
      return preparation;
    }

    public void awaitStarted() throws Exception {
      if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("Manual work did not start");
    }

    public void awaitCancelled() throws Exception {
      if (!cancelled.await(5, TimeUnit.SECONDS)) throw new AssertionError("Manual work was not cancelled");
    }

    public void complete() {
      release.countDown();
    }

    public void awaitClosed() throws Exception {
      if (!closed.await(5, TimeUnit.SECONDS)) throw new AssertionError("Manual worker did not close");
    }

    @Override
    public void close() throws Exception {
      release.countDown();
      preparation.close();
      preparation.termination().get(5, TimeUnit.SECONDS);
    }
  }
}
