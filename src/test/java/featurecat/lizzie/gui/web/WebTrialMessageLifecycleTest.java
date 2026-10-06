package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebTrialMessageLifecycleTest {
  @ParameterizedTest
  @ValueSource(strings = {"trial_reset", "trial_navigate", "exit_trial"})
  void laterCommandsCannotOvertakeAQueuedMove(String type) throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      f.enqueue(f.owner, "trial_move", "x", 3, "y", 3);
      f.enqueue(f.owner, type, "direction", "back");
      assertSame(f.anchor, f.display.get());
      assertEquals(2, f.events.work.size());
      f.events.drain();
      assertEquals(2, f.events.publications.size());
      assertEquals(1, f.events.publications.get(0).node().getData().moveNumber);
      assertSame("exit_trial".equals(type) ? null : f.anchor, f.display.get());
      assertSame(
          f.anchor,
          f.board.getHistory().getCurrentHistoryNode(),
          "trial never moves the real cursor");
    }
  }

  @Test
  void consecutiveMovesAndNavigationUseFifoStateRatherThanCapturedParents() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      f.enqueue(f.owner, "trial_move", "x", 3, "y", 3);
      f.enqueue(f.owner, "trial_move", "x", 4, "y", 4);
      f.enqueue(f.owner, "trial_navigate", "direction", "back");
      f.enqueue(f.owner, "trial_navigate", "childIndex", 0);
      f.events.drain();
      assertEquals(
          List.of(1, 2, 1, 2),
          f.events.publications.stream().map(p -> p.node().getData().moveNumber).toList());
      assertArrayEquals(new int[] {4, 4}, f.display.get().getData().lastMove.orElseThrow());
    }
  }

  @Test
  void publicIdentityAndSessionIdDoNotAuthorizeAnotherConnection() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      f.send(f.visitor, "trial_move", "x", 3, "y", 3);
      f.send(f.visitor, "trial_navigate", "direction", "back");
      f.send(f.visitor, "trial_reset");
      f.send(f.visitor, "exit_trial");
      assertEquals("owner", f.manager.getCurrentTrialOwner());
      assertSame(f.anchor, f.display.get());
      assertTrue(f.events.publications.isEmpty());
      assertEquals(1, f.events.timers.size());
      f.send(f.visitor, "enter_trial");
      assertEquals("in_use", f.visitor.messages.get(0).getString("reason"));
      assertFalse(f.visitor.messages.toString().contains(f.resumeToken));
      f.send(f.visitor, "resume_trial", "resumeToken", f.sessionId);
      assertEquals("expired", f.visitor.messages.get(1).getString("reason"));
    }
  }

  @Test
  void reconnectNeedsPrivateTokenAndRevokesTheOldConnectionAndOldToken() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      f.enqueue(f.owner, "trial_move", "x", 3, "y", 3);
      Runnable oldMove = f.events.work.remove();
      String oldToken = f.resumeToken;
      f.owner.open = false;
      f.send(f.visitor, "resume_trial", "resumeToken", oldToken);
      JSONObject grant =
          f.visitor.messages.stream()
              .filter(m -> "trial_granted".equals(m.optString("type")))
              .findFirst()
              .orElseThrow();
      assertEquals(f.sessionId, grant.getString("sessionId"));
      assertNotEquals(oldToken, grant.getString("resumeToken"));
      f.events.publications.clear();
      f.owner.open = true; // Even an already-decoded old callback cannot regain ownership.
      oldMove.run();
      f.send(f.owner, "trial_reset");
      assertTrue(f.events.publications.isEmpty());
      f.visitor.open = false;
      f.send(f.owner, "resume_trial", "resumeToken", oldToken);
      assertEquals("expired", f.owner.messages.get(0).getString("reason"));
      f.visitor.open = true;
      f.send(f.visitor, "trial_move", "x", 4, "y", 4);
      assertEquals(1, f.display.get().getData().moveNumber);
    }
  }

  @Test
  void liveOwnerCannotBeDisplacedEvenByAClonedResumeCredential() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      f.send(f.visitor, "resume_trial", "resumeToken", f.resumeToken);
      assertEquals("in_use", f.visitor.messages.get(0).getString("reason"));
      f.send(f.owner, "trial_move", "x", 3, "y", 3);
      assertEquals(1, f.display.get().getData().moveNumber);
    }
  }

  @Test
  void ownerExitPublishesExactlyOnceAndCleansTheUnusedDummy() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      f.send(f.owner, "exit_trial");
      f.send(f.owner, "exit_trial");
      f.manager.forceExitTrial();
      assertEquals("", f.manager.getCurrentTrialOwner());
      assertNull(f.display.get());
      assertEquals(1, f.events.publications.size());
      assertTrue(f.anchor.variations.isEmpty());
      assertTrue(f.events.timers.get(0).isCancelled());
    }
  }

  @Test
  void cancelledTimerCannotEndTheSameSessionAfterNewActivity() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      WebTrialTestFixture.Timer old = f.events.timers.get(0);
      f.send(f.owner, "trial_reset");
      assertTrue(old.isCancelled());
      f.events.publications.clear();
      old.callback.run();
      assertEquals("owner", f.manager.getCurrentTrialOwner());
      assertTrue(f.events.publications.isEmpty());
      f.events.timers.get(1).callback.run();
      f.events.timers.get(1).callback.run();
      assertEquals(1, f.events.publications.size());
      assertNull(f.display.get());
    }
  }

  @Test
  void queuedCommandsAndOldCredentialsCannotAffectSameOwnerReplacementSession() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      f.enqueue(f.owner, "trial_move", "x", 3, "y", 3);
      f.enqueue(f.owner, "trial_reset");
      f.enqueue(f.owner, "exit_trial");
      var oldWork = List.copyOf(f.events.work);
      f.events.work.clear();
      var oldTimer = f.events.timers.get(0);
      String oldToken = f.resumeToken;
      String oldId = f.sessionId;
      f.manager.forceExitTrial();
      f.enter();
      assertNotEquals(oldId, f.sessionId);
      oldWork.forEach(Runnable::run);
      oldTimer.callback.run();
      assertTrue(f.events.publications.isEmpty());
      assertSame(f.anchor, f.display.get());
      f.owner.open = false;
      f.send(f.visitor, "resume_trial", "sessionId", oldId, "resumeToken", oldToken);
      assertEquals("expired", f.visitor.messages.get(0).getString("reason"));
    }
  }

  @Test
  void invalidCoordinatesAreRejectedBeforeQueueing() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      Object[][] invalid = {
        {-1, 0},
        {0, -1},
        {Board.boardWidth, 0},
        {0, Board.boardHeight},
        {Integer.MAX_VALUE, 0},
        {Long.MAX_VALUE, 0},
        {1.5, 0},
        {"3", 0},
        {null, 0},
        {new BigDecimal("3.0000000000000001"), 0},
        {new BigDecimal("1e-400"), 0}
      };
      for (Object[] point : invalid) f.enqueue(f.owner, "trial_move", "x", point[0], "y", point[1]);
      f.enqueue(f.owner, "trial_navigate", "childIndex", 4294967297L);
      f.enqueue(f.owner, "trial_navigate", "direction", "unknown");
      f.enqueue(f.owner, "unknown");
      f.server.onMessage(f.owner.socket, "not-json");
      assertTrue(f.events.work.isEmpty());
      assertTrue(f.events.publications.isEmpty());
      assertEquals(1, f.events.timers.size());
    }
  }

  @Test
  void queuedCommandsRecheckBoardIdentityAndDimensions() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      f.enqueue(f.owner, "trial_move", "x", 3, "y", 3);
      f.enqueue(f.owner, "trial_reset");
      Lizzie.board = new Board();
      f.events.drain();
      assertTrue(f.events.publications.isEmpty());
      Lizzie.board = f.board;
      f.enqueue(f.owner, "trial_move", "x", 3, "y", 3);
      int width = Board.boardWidth;
      try {
        Board.boardWidth = width + 1;
        f.events.drain();
        assertSame(f.anchor, f.display.get());
      } finally {
        Board.boardWidth = width;
      }
    }
  }

  @Test
  void ownerCanStillExitAfterTheSourceBoardIsReplaced() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      f.enqueue(f.owner, "exit_trial");
      Lizzie.board = new Board();
      f.events.drain();
      assertEquals("", f.manager.getCurrentTrialOwner());
      assertNull(f.display.get());
      assertTrue(f.anchor.variations.isEmpty());
      assertTrue(f.events.timers.get(0).isCancelled());
    }
  }

  @Test
  void newEntryRetiresAStaleBoardSessionWithoutGrantingItsOldCredential() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      String oldSession = f.sessionId;
      String oldToken = f.resumeToken;
      f.owner.open = false;
      Board replacement = new Board();
      Lizzie.board = replacement;
      f.send(f.visitor, "enter_trial");
      assertTrue(
          f.visitor.messages.stream().anyMatch(m -> "trial_granted".equals(m.optString("type"))),
          "a replaced board must not keep the old session reservation");
      JSONObject grant =
          f.visitor.messages.stream()
              .filter(m -> "trial_granted".equals(m.optString("type")))
              .findFirst()
              .orElseThrow();
      assertNotEquals(oldSession, grant.getString("sessionId"));
      assertNotEquals(oldToken, grant.getString("resumeToken"));
      assertSame(
          replacement.getHistory().getCurrentHistoryNode(), f.manager.getTrialAnchorForTest());
      assertTrue(f.anchor.variations.isEmpty());
      assertTrue(f.events.timers.get(0).isCancelled());
      f.events.publications.clear();
      f.owner.open = true;
      f.send(f.owner, "exit_trial");
      assertEquals("owner", f.manager.getCurrentTrialOwner());
      assertTrue(f.events.publications.isEmpty());
    }
  }

  @Test
  void replacedServerCannotDispatchQueuedOrNewMessages() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      WebBoardServer replacement = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 2);
      try {
        f.enqueue(f.owner, "exit_trial");
        f.manager.attachWebSocketServer(replacement);
        f.events.drain();
        f.send(f.owner, "exit_trial");
        replacement.onMessage(
            f.owner.socket,
            new JSONObject()
                .put("type", "exit_trial")
                .put("clientId", "owner")
                .put("sessionId", f.sessionId)
                .toString());
        f.events.drain();
        assertTrue(f.events.publications.isEmpty());
        assertEquals("owner", f.manager.getCurrentTrialOwner());
      } finally {
        replacement.stop(1000);
      }
    }
  }

  @Test
  void serverIdentityIsRecheckedAfterWaitingForTransitionLock() throws Exception {
    try (WebTrialTestFixture f = new WebTrialTestFixture()) {
      WebBoardServer replacement = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 2);
      FutureTask<Void> dispatch =
          new FutureTask<>(
              () -> {
                f.enqueue(f.owner, "exit_trial");
                return null;
              });
      Thread reader = new Thread(dispatch, "test-old-web-reader");
      reader.setDaemon(true);
      try {
        synchronized (f.manager) {
          reader.start();
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
          while (reader.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
            Thread.sleep(1);
          assertEquals(Thread.State.BLOCKED, reader.getState());
          f.manager.attachWebSocketServer(replacement);
        }
        dispatch.get(3, TimeUnit.SECONDS);
        assertTrue(f.events.work.isEmpty());
      } finally {
        reader.join(3000);
        replacement.stop(1000);
      }
    }
  }
}
