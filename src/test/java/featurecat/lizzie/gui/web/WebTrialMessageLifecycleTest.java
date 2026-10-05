package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class WebTrialMessageLifecycleTest {
  @Test
  void nonOwnerMessagesCannotPublishAnExitOrMutateTheTrial() throws Exception {
    try (Fixture f = new Fixture()) {
      f.message("trial_move", "other", "x", 3, "y", 3);
      f.message("trial_navigate", "other", "direction", "back");
      f.message("trial_reset", "other");
      f.message("exit_trial", "other");
      assertEquals("owner", f.manager.getCurrentTrialOwner());
      assertSame(f.anchor, f.override.get());
      assertTrue(f.events.work.isEmpty());
      assertTrue(f.events.publications.isEmpty(), "rejected commands must not broadcast a state change");
      assertEquals(1, f.events.timers.size(), "rejected commands must not refresh activity");
    }
  }

  @Test
  void ownerExitThroughJsonDispatchPublishesExactlyOnce() throws Exception {
    try (Fixture f = new Fixture()) {
      f.message("exit_trial", "owner");
      assertEquals("", f.manager.getCurrentTrialOwner());
      assertNull(f.override.get());
      assertEquals(List.of(new Publication("", null)), f.events.publications);
      assertTrue(f.anchor.variations.isEmpty(), "unused mainline dummy must be removed");
      assertTrue(f.events.timers.get(0).isCancelled());
      f.message("exit_trial", "owner");
      f.manager.forceExitTrial();
      assertEquals(1, f.events.publications.size());
    }
  }

  @Test
  void forcedAndIdleExitShareTheSamePublicationPath() throws Exception {
    try (Fixture f = new Fixture()) {
      f.manager.forceExitTrial();
      assertEquals(List.of(new Publication("", null)), f.events.publications);
      assertTrue(f.manager.enterTrial("owner", f.anchor));
      f.events.publications.clear();
      TimerTask current = f.events.timers.get(f.events.timers.size() - 1);
      current.fireEvenIfCancelled();
      current.fireEvenIfCancelled();
      assertEquals(List.of(new Publication("", null)), f.events.publications);
      assertNull(f.override.get());
    }
  }

  @Test
  void cancelledIdleCallbackCannotEndTheSameSessionAfterNewActivity() throws Exception {
    try (Fixture f = new Fixture()) {
      TimerTask old = f.events.timers.get(0);
      f.message("trial_reset", "owner");
      TimerTask current = f.events.timers.get(1);
      assertTrue(old.isCancelled());
      f.events.publications.clear();
      old.fireEvenIfCancelled(); // Simulates a callback already waiting for the manager monitor.
      assertEquals("owner", f.manager.getCurrentTrialOwner());
      assertTrue(f.events.publications.isEmpty());
      current.fireEvenIfCancelled();
      assertEquals(List.of(new Publication("", null)), f.events.publications);
    }
  }

  @Test
  void invalidCoordinatesAreRejectedBeforeQueueingAndAValidMoveStillPublishes() throws Exception {
    try (Fixture f = new Fixture()) {
      Object[][] invalid = {
        {-1, 0}, {0, -1}, {Board.boardWidth, 0}, {0, Board.boardHeight},
        {Integer.MAX_VALUE, 0}, {Long.MAX_VALUE, 0}, {1.5, 0}, {"3", 0}, {null, 0}
      };
      for (Object[] point : invalid) {
        f.message("trial_move", "owner", "x", point[0], "y", point[1]);
      }
      f.server.onMessage(null, "not-json");
      f.message("unknown", "owner");
      assertTrue(f.events.work.isEmpty());
      assertTrue(f.events.publications.isEmpty());
      assertEquals(1, f.events.timers.size());
      assertSame(f.anchor, f.manager.getDisplayNodeForTest());

      f.message("trial_move", "owner", "x", 3, "y", 3);
      assertEquals(1, f.events.work.size());
      assertTrue(f.events.publications.isEmpty(), "queueing is not successful adoption");
      f.events.work.remove().run();
      BoardHistoryNode child = f.manager.getDisplayNodeForTest();
      assertNotSame(f.anchor, child);
      assertArrayEquals(new int[] {3, 3}, child.getData().lastMove.orElseThrow());
      assertSame(child, f.override.get());
      assertEquals(List.of(new Publication("owner", child)), f.events.publications);
    }
  }

  @Test
  void queuedMoveAndOldTimerCannotMutateAReplacementSessionWithTheSameOwner() throws Exception {
    try (Fixture f = new Fixture()) {
      f.message("trial_move", "owner", "x", 3, "y", 3);
      Runnable oldMove = f.events.work.remove();
      TimerTask oldTimer = f.events.timers.get(f.events.timers.size() - 1);
      f.message("exit_trial", "owner");
      BoardHistoryNode replacement = new BoardHistoryNode(BoardData.empty(Board.boardWidth, Board.boardHeight));
      assertTrue(f.manager.enterTrial("owner", replacement));
      f.events.publications.clear();
      oldMove.run();
      oldTimer.fireEvenIfCancelled();
      assertSame(replacement, f.manager.getDisplayNodeForTest());
      assertEquals("owner", f.manager.getCurrentTrialOwner());
      assertEquals(1, replacement.variations.size(), "only the mainline placeholder may exist");
      assertTrue(f.events.publications.isEmpty());
    }
  }

  @Test
  void queuedMoveRechecksBoardDimensionsBeforeIndexing() throws Exception {
    try (Fixture f = new Fixture()) {
      f.message("trial_move", "owner", "x", 3, "y", 3);
      int width = Board.boardWidth;
      int height = Board.boardHeight;
      try {
        Board.boardWidth = width == 9 ? 19 : 9;
        Board.boardHeight = height == 9 ? 19 : 9;
        assertDoesNotThrow(() -> f.events.work.remove().run());
        assertSame(f.anchor, f.manager.getDisplayNodeForTest());
        assertTrue(f.events.publications.isEmpty());
      } finally {
        Board.boardWidth = width;
        Board.boardHeight = height;
      }
    }
  }

  @Test
  void replacedServerCannotDispatchIntoTheCurrentTrial() throws Exception {
    try (Fixture f = new Fixture()) {
      WebBoardServer replacement = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 2);
      try {
        f.manager.attachWebSocketServer(replacement);
        f.message("exit_trial", "owner");
        assertEquals("owner", f.manager.getCurrentTrialOwner());
        assertTrue(f.events.publications.isEmpty());
        replacement.onMessage(null, "{\"type\":\"exit_trial\",\"clientId\":\"owner\"}");
        assertEquals(List.of(new Publication("", null)), f.events.publications);
      } finally {
        replacement.stop(1000);
      }
    }
  }

  private record Publication(String owner, BoardHistoryNode displayNode) {}

  private static final class ManualEvents implements WebBoardManager.TrialEvents {
    final ArrayDeque<Runnable> work = new ArrayDeque<>();
    final List<TimerTask> timers = new ArrayList<>();
    final List<Publication> publications = new ArrayList<>();

    @Override
    public void execute(Runnable task) {
      work.add(task);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
      assertEquals(5 * 60 * 1000L, unit.toMillis(delay));
      TimerTask timer = new TimerTask(task);
      timers.add(timer);
      return timer;
    }

    @Override
    public void publish(WebBoardManager.TrialSession session) {
      publications.add(new Publication(session == null ? "" : session.ownerClientId,
          session == null ? null : session.displayNode));
    }
  }

  private static final class TimerTask extends FutureTask<Void> implements ScheduledFuture<Void> {
    private final Runnable callback;

    TimerTask(Runnable callback) {
      super(callback, null);
      this.callback = callback;
    }

    void fireEvenIfCancelled() {
      callback.run();
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed other) {
      return 0;
    }
  }

  /** Real model objects and an explicit event seam; no Unsafe or collector worker is needed. */
  private static final class Fixture implements AutoCloseable {
    final Leelaz previousEngine = Lizzie.leelaz;
    final WebBoardManager previousManager = Lizzie.webBoardManager;
    final LizzieFrame previousFrame = Lizzie.frame;
    final ManualEvents events = new ManualEvents();
    final WebBoardManager manager = new WebBoardManager(events);
    final WebBoardServer server = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 2);
    final BoardHistoryNode anchor = new BoardHistoryNode(BoardData.empty(Board.boardWidth, Board.boardHeight));
    final AtomicReference<BoardHistoryNode> override = new AtomicReference<>();

    Fixture() throws Exception {
      Lizzie.leelaz = new Leelaz("");
      Lizzie.webBoardManager = manager;
      Lizzie.frame = null;
      manager.setOverrideSinkForTest(override::set);
      manager.setDesktopRefresherForTest(() -> {});
      manager.setMainlineTailSupplier(() -> anchor);
      manager.attachWebSocketServer(server);
      assertTrue(manager.enterTrial("owner", anchor));
    }

    void message(String type, String owner, Object... values) {
      JSONObject json = new JSONObject().put("type", type).put("clientId", owner);
      for (int i = 0; i < values.length; i += 2) json.put((String) values[i], values[i + 1]);
      // Exercise the same JSON decoding + registered manager callback used by WebSocket input.
      server.onMessage(null, json.toString());
    }

    @Override
    public void close() throws Exception {
      try {
        manager.forceExitTrial();
        server.stop(1000);
      } finally {
        Lizzie.leelaz = previousEngine;
        Lizzie.webBoardManager = previousManager;
        Lizzie.frame = previousFrame;
      }
    }
  }
}
