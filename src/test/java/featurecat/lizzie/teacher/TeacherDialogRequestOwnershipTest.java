package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.analysis.remote.CredentialStore;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Window;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual dialog, request controller, HTTP client, verifier and SGF writes share these scenarios. */
class TeacherDialogRequestOwnershipTest {
  @TempDir Path temporary;

  private final AtomicReference<BoardHistoryNode> root = new AtomicReference<>();
  private final AtomicReference<BoardHistoryNode> current = new AtomicReference<>();
  private final List<JSONObject> sent = new CopyOnWriteArrayList<>();
  private final List<CountDownLatch> releases = new ArrayList<>();
  private ResourceBundle previousBundle;
  private int previousWidth;
  private int previousHeight;
  private boolean fixtureInitialized;
  private HttpServer server;
  private TeacherSettings settings;
  private TeacherDialog dialog;
  private TeacherDialogView view;

  @BeforeEach
  void setUp() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "requires a real Swing display");
    previousBundle = Lizzie.resourceBundle;
    previousWidth = Board.boardWidth;
    previousHeight = Board.boardHeight;
    fixtureInitialized = true;
    Board.boardWidth = 19;
    Board.boardHeight = 19;
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    BoardHistoryNode start = new BoardHistoryNode(BoardData.empty(19, 19));
    start.getData().bestMoves = List.of(candidate("D4"));
    start.getData().comment = "user note";
    root.set(start);
    current.set(start);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    settings = new TeacherSettings(temporary.resolve("teacher.properties"), new SessionOnlyStore());
    settings.save(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
        "test-model",
        "test-key".toCharArray(),
        false);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (!fixtureInitialized) {
      return;
    }
    for (CountDownLatch release : releases) {
      release.countDown();
    }
    onEdt(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof TeacherSettingsDialog || window == dialog) {
              window.dispose();
            }
          }
        });
    if (server != null) server.stop(0);
    Lizzie.resourceBundle = previousBundle;
    Board.boardWidth = previousWidth;
    Board.boardHeight = previousHeight;
  }

  @Test
  void verifiedCompletionWritesOriginalNodeAfterCursorMoves() throws Exception {
    CountDownLatch release = blockedResponse("Consider D4.");
    openDialog();
    BoardHistoryNode target = root.get();
    BoardHistoryNode next = append(target, "Q16");

    onEdt(() -> view.explainNext().doClick());
    await(() -> sent.size() == 1);
    onEdt(
        () -> {
          current.set(next);
          target.getData().comment = "user note edited while generating";
        });
    release.countDown();
    awaitIdle();

    assertEquals("Consider D4.", TeacherCommentCodec.extract(target.getData().comment).orElseThrow());
    assertTrue(target.getData().comment.startsWith("user note edited while generating"));
    assertTrue(TeacherCommentCodec.extract(next.getData().comment).isEmpty());
    assertDisplayed("Consider D4.");
    captureDialog("verified-commentary.png");
  }

  @Test
  void followUpRetainsFrozenEvidenceAndOriginalTarget() throws Exception {
    respondWith("Consider D4.", "D4 is supported by the original analysis.");
    openDialog();
    BoardHistoryNode target = root.get();
    BoardHistoryNode next = append(target, "Q16");
    next.getData().bestMoves = List.of(candidate("Q4"));
    onEdt(() -> view.explainNext().doClick());
    awaitIdle();
    onEdt(
        () -> {
          target.getData().bestMoves = List.of(candidate("C3"));
          current.set(next);
          view.followUp().setText("Why this candidate?");
          view.ask().doClick();
        });
    awaitIdle();

    assertEquals(2, sent.size());
    assertEquals(
        sent.get(0).getJSONArray("messages").getJSONObject(1).getString("content"),
        sent.get(1).getJSONArray("messages").getJSONObject(1).getString("content"));
    assertEquals(
        "Consider D4.",
        sent.get(1).getJSONArray("messages").getJSONObject(2).getString("content"));
    assertEquals(
        "D4 is supported by the original analysis.",
        TeacherCommentCodec.extract(target.getData().comment).orElseThrow());
    assertTrue(TeacherCommentCodec.extract(next.getData().comment).isEmpty());
    assertDisplayed("D4 is supported by the original analysis.");
  }

  @Test
  void savedCommentaryFollowUpUsesSavedNodeAfterNavigation() throws Exception {
    root.get().getData().comment = TeacherCommentCodec.upsert("user note", "Saved D4 answer.", "old");
    BoardHistoryNode savedNode = root.get();
    BoardHistoryNode next = append(savedNode, "Q16");
    next.getData().bestMoves = List.of(candidate("Q4"));
    respondWith("D4 remains in the supplied candidates.");
    openDialog();
    onEdt(
        () -> {
          current.set(next);
          view.followUp().setText("Explain the saved answer.");
          view.ask().doClick();
        });
    awaitIdle();

    assertEquals(
        "Saved D4 answer.",
        sent.get(0).getJSONArray("messages").getJSONObject(2).getString("content"));
    assertEquals(
        "D4 remains in the supplied candidates.",
        TeacherCommentCodec.extract(savedNode.getData().comment).orElseThrow());
    assertTrue(TeacherCommentCodec.extract(next.getData().comment).isEmpty());
  }

  @Test
  void ungroundedDraftIsNeverDisplayedOrSavedDuringCorrection() throws Exception {
    CountDownLatch correctionEntered = new CountDownLatch(1);
    CountDownLatch release = newRelease();
    serve(
        exchange -> {
          if (sent.size() == 1) {
            respond(exchange, 200, "Recommend A1 with 150% winrate.");
          } else {
            correctionEntered.countDown();
            hold(release);
            respond(exchange, 200, "Consider D4.");
          }
        });
    openDialog();
    onEdt(() -> view.explainNext().doClick());
    assertTrue(correctionEntered.await(5, TimeUnit.SECONDS));
    onEdt(
        () -> {
          assertFalse(view.output().getText().contains("150%"));
          assertFalse(view.output().getText().contains("A1"));
          assertTrue(view.stop().isEnabled());
          assertEquals("user note", root.get().getData().comment);
        });
    release.countDown();
    awaitIdle();
    assertEquals(2, sent.size());
    assertDisplayed("Consider D4.");
    assertEquals("Consider D4.", TeacherCommentCodec.extract(root.get().getData().comment).orElseThrow());
  }

  @Test
  void oversizedCommentPreservesSgfAndAllowsAnotherRequest() throws Exception {
    respondWith("Verified commentary. ".repeat(5_001), "D4 remains available.");
    openDialog();
    onEdt(() -> view.explainNext().doClick());
    awaitIdle();
    onEdt(
        () -> {
          assertEquals("user note", root.get().getData().comment);
          assertTrue(view.status().getText().contains("too large"), view.status().getText());
          assertTrue(view.explainNext().isEnabled());
          assertFalse(view.output().getText().contains("Verified commentary."));
          view.explainNext().doClick();
        });
    awaitIdle();
    assertEquals(2, sent.size());
    assertDisplayed("D4 remains available.");
    assertEquals(
        "D4 remains available.",
        TeacherCommentCodec.extract(root.get().getData().comment).orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void replacementDropsOldCompletionOrFailureAlreadyQueuedOnEdt(boolean oldFailure) throws Exception {
    CountDownLatch releaseNew = newRelease();
    serve(
        exchange -> {
          if (sent.size() == 1) {
            respond(exchange, oldFailure ? 500 : 200, oldFailure ? "old failure" : "Old D4 answer.");
          } else {
            hold(releaseNew);
            respond(exchange, 200, "New D4 answer.");
          }
        });
    openDialog();
    onEdt(
        () -> {
          view.explainNext().doClick();
          awaitWorkerWithEdtBlocked();
          view.stop().doClick();
          view.explainNext().doClick();
        });
    await(() -> sent.size() == 2);
    onEdt(
        () -> {
          assertTrue(view.stop().isEnabled(), "stale completion must not finish the replacement");
          assertFalse(view.output().getText().contains("Old D4 answer."));
          assertFalse(view.status().getText().contains("old failure"));
          assertEquals("user note", root.get().getData().comment);
        });
    releaseNew.countDown();
    awaitIdle();
    assertDisplayed("New D4 answer.");
    assertEquals("New D4 answer.", TeacherCommentCodec.extract(root.get().getData().comment).orElseThrow());
  }

  @Test
  void cancellationDropsAnAnswerAlreadyQueuedOnEdt() throws Exception {
    respondWith("Cancelled D4 answer.");
    openDialog();
    onEdt(
        () -> {
          view.explainNext().doClick();
          awaitWorkerWithEdtBlocked();
          view.stop().doClick();
        });
    awaitIdle();
    onEdt(() -> assertFalse(view.output().getText().contains("Cancelled D4 answer.")));
    assertEquals("user note", root.get().getData().comment);
  }

  @Test
  void disposalDropsAnAnswerAlreadyQueuedOnEdt() throws Exception {
    respondWith("Closed D4 answer.");
    openDialog();
    onEdt(
        () -> {
          view.explainNext().doClick();
          awaitWorkerWithEdtBlocked();
          dialog.dispose();
        });
    onEdt(() -> assertFalse(dialog.isDisplayable()));
    assertEquals("user note", root.get().getData().comment);
  }

  @Test
  void gameSwitchDropsAnAnswerAlreadyQueuedOnEdt() throws Exception {
    respondWith("Old game D4 answer.");
    openDialog();
    BoardHistoryNode oldRoot = root.get();
    onEdt(
        () -> {
          view.explainNext().doClick();
          awaitWorkerWithEdtBlocked();
          switchGame();
        });
    awaitIdle();
    assertEquals("user note", oldRoot.getData().comment);
    assertEquals("", root.get().getData().comment);
    onEdt(() -> assertFalse(view.output().getText().contains("Old game D4 answer.")));
    captureDialog("blocked-commentary.png");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deletedOrEditedTargetRejectsPendingAnswer(boolean deleteTarget) throws Exception {
    BoardHistoryNode target = append(root.get(), "Q16");
    target.getData().bestMoves = List.of(candidate("D4"));
    current.set(target);
    respondWith("Obsolete D4 answer.");
    openDialog();
    onEdt(
        () -> {
          view.explainNext().doClick();
          awaitWorkerWithEdtBlocked();
          if (deleteTarget) {
            root.get().deleteChild(0);
            current.set(root.get());
          } else {
            target.getData().stones[Board.getIndex(0, 0)] = Stone.BLACK;
          }
        });
    awaitIdle();
    assertEquals("", target.getData().comment);
    onEdt(() -> assertFalse(view.output().getText().contains("Obsolete D4 answer.")));
  }

  @ParameterizedTest
  @EnumSource(RecordedChange.class)
  void recordedContinuationOrRecentHistoryChangeRejectsPendingAnswer(RecordedChange change)
      throws Exception {
    BoardHistoryNode previous = append(root.get(), "Q16");
    BoardHistoryNode target = append(previous, "Q4");
    append(target, "C3");
    target.getData().bestMoves = List.of(candidate("D4"));
    current.set(target);
    respondWith("The recorded continuation includes C3.");
    openDialog();
    onEdt(
        () -> {
          view.explainNext().doClick();
          awaitWorkerWithEdtBlocked();
          switch (change) {
            case HISTORY -> previous.getData().lastMove = Board.asCoordinates("Q15");
            case CONTINUATION -> {
              target.deleteChild(0);
              append(target, "C4");
            }
            case REMOVED_STONE -> target.next().orElseThrow().setRemovedStone();
            case EXTRA_STONES -> target.next().orElseThrow().addExtraStones(0, 0, true);
          }
        });
    awaitIdle();
    assertEquals("", target.getData().comment);
    onEdt(() -> assertFalse(view.output().getText().contains("The recorded continuation includes C3.")));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rangeAndWholeGameRejectEditsOutsideSelectedEvidence(boolean wholeGame) throws Exception {
    BoardHistoryNode first = append(root.get(), "Q16");
    BoardHistoryNode unselected = append(first, "Q4");
    append(unselected, "C3");
    respondWith("Old range D4 answer.");
    openDialog();
    onEdt(
        () -> {
          if (wholeGame) {
            view.explainWhole().doClick();
          } else {
            view.rangeStart().setValue(1);
            view.rangeEnd().setValue(1);
            view.explainRange().doClick();
          }
          awaitWorkerWithEdtBlocked();
          unselected.getData().stones[Board.getIndex(0, 0)] = Stone.BLACK;
        });
    awaitIdle();
    assertEquals("user note", root.get().getData().comment);
    onEdt(() -> assertFalse(view.output().getText().contains("Old range D4 answer.")));
  }

  @Test
  void cancelledFollowUpRestoresQuestionAndLastVerifiedAnswer() throws Exception {
    CountDownLatch release = newRelease();
    serve(
        exchange -> {
          if (sent.size() == 1) {
            respond(exchange, 200, "Original D4 answer.");
          } else {
            hold(release);
            respond(exchange, 200, "Cancelled draft about D4.");
          }
        });
    openDialog();
    onEdt(() -> view.explainNext().doClick());
    awaitIdle();
    onEdt(
        () -> {
          view.followUp().setText("Why D4?");
          view.ask().doClick();
        });
    await(() -> sent.size() == 2);
    onEdt(
        () -> {
          view.stop().doClick();
          assertEquals("Why D4?", view.followUp().getText());
          assertTrue(view.output().getText().contains("Original D4 answer."));
        });
    release.countDown();
    assertEquals(
        "Original D4 answer.",
        TeacherCommentCodec.extract(root.get().getData().comment).orElseThrow());
  }

  @Test
  void settingsDialogGameChangeRejectsPreparedRequestBeforeNetwork() throws Exception {
    settings.save(settings.snapshot().baseUrl, "test-model", new char[0], false);
    respondWith("Must never be requested.");
    openDialog();
    CountDownLatch prepared = new CountDownLatch(1);
    SwingUtilities.invokeLater(
        () -> {
          view.explainNext().doClick();
          prepared.countDown();
        });
    awaitOnEdt(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof TeacherSettingsDialog
                && window.isShowing()
                && field(window, "saveButton", JButton.class).isEnabled()) {
              switchGame();
              field(window, "apiKeyField", JPasswordField.class).setText("test-key");
              field(window, "saveButton", JButton.class).doClick();
              return true;
            }
          }
          return false;
        });
    assertTrue(prepared.await(5, TimeUnit.SECONDS));
    awaitIdle();
    assertEquals(0, sent.size(), "old-game evidence must not be sent after completing settings");
    assertEquals("", root.get().getData().comment);
  }

  private void openDialog() throws Exception {
    server.start();
    onEdt(
        () -> {
          dialog = new TeacherDialog(null, settings, current::get, root::get);
          view = (TeacherDialogView) dialog.getContentPane();
          dialog.setVisible(true);
        });
    awaitOnEdt(() -> view.explainNext().isEnabled());
  }

  private void respondWith(String... answers) {
    AtomicInteger count = new AtomicInteger();
    serve(
        exchange ->
            respond(exchange, 200, answers[Math.min(count.getAndIncrement(), answers.length - 1)]));
  }

  private CountDownLatch blockedResponse(String answer) {
    CountDownLatch release = newRelease();
    serve(
        exchange -> {
          hold(release);
          respond(exchange, 200, answer);
        });
    return release;
  }

  private CountDownLatch newRelease() {
    CountDownLatch release = new CountDownLatch(1);
    releases.add(release);
    return release;
  }

  private void serve(com.sun.net.httpserver.HttpHandler response) {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          sent.add(
              new JSONObject(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          response.handle(exchange);
        });
  }

  private static void respond(HttpExchange exchange, int status, String answer) throws IOException {
    JSONObject payload =
        status == 200
            ? new JSONObject()
                .put(
                    "choices",
                    List.of(
                        new JSONObject().put("delta", new JSONObject().put("content", answer))))
            : new JSONObject().put("error", new JSONObject().put("message", answer));
    String body = status == 200 ? "data: " + payload + "\n\ndata: [DONE]\n\n" : payload.toString();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders()
        .set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void hold(CountDownLatch release) throws IOException {
    try {
      if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test response was not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("test response interrupted", interrupted);
    }
  }

  private void awaitIdle() throws Exception {
    awaitOnEdt(() -> !view.stop().isEnabled());
  }

  private void assertDisplayed(String text) throws Exception {
    onEdt(() -> assertTrue(view.output().getText().contains(text), view.output().getText()));
  }

  private void captureDialog(String name) throws Exception {
    Path directory = Path.of("target", "desktop-smoke", "probes", "teacher");
    Files.createDirectories(directory);
    onEdt(() -> view.paintImmediately(0, 0, view.getWidth(), view.getHeight()));
    ImageIO.write(
        new Robot().createScreenCapture(dialog.getBounds()),
        "png",
        directory.resolve(name).toFile());
  }

  private void awaitWorkerWithEdtBlocked() {
    assertTrue(SwingUtilities.isEventDispatchThread());
    TeacherRequestController controller = field(dialog, "requests", TeacherRequestController.class);
    Future<?> future = field(controller, "future", Future.class);
    assertNotNull(future);
    try {
      // Waiting for this exact worker guarantees its terminal callback is queued, not yet handled.
      future.get(5, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError("request did not finish while EDT was deliberately held", failure);
    }
  }

  private void switchGame() {
    BoardHistoryNode other = new BoardHistoryNode(BoardData.empty(19, 19));
    other.getData().bestMoves = List.of(candidate("Q4"));
    root.set(other);
    current.set(other);
  }

  private static BoardHistoryNode append(BoardHistoryNode parent, String coordinate) {
    BoardData before = parent.getData();
    int[] move = Board.asCoordinates(coordinate).orElseThrow();
    Stone color = before.blackToPlay ? Stone.BLACK : Stone.WHITE;
    Stone[] stones = before.stones.clone();
    stones[Board.getIndex(move[0], move[1])] = color;
    BoardHistoryNode child =
        new BoardHistoryNode(
            BoardData.move(
                stones,
                move,
                color,
                !before.blackToPlay,
                new Zobrist(),
                before.moveNumber + 1,
                new int[stones.length],
                0,
                0,
                50,
                0));
    parent.getVariations().add(child);
    parent.setPreviousForChild(child);
    return child;
  }

  private static MoveData candidate(String coordinate) {
    MoveData move = new MoveData();
    move.coordinate = coordinate;
    move.winrate = 60;
    move.scoreMean = 2;
    move.playouts = 1000;
    move.variation = List.of(coordinate);
    return move;
  }

  private static <T> T field(Object target, String name, Class<T> type) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(target));
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static void awaitOnEdt(BooleanSupplier condition) throws Exception {
    await(
        () -> {
          AtomicReference<Boolean> result = new AtomicReference<>(false);
          try {
            onEdt(() -> result.set(condition.getAsBoolean()));
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
          return result.get();
        });
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "timed out waiting for the expected event");
      Thread.sleep(10);
    }
  }

  private static void onEdt(Runnable action) throws Exception {
    try {
      SwingUtilities.invokeAndWait(action);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Error error) throw error;
      if (failure.getCause() instanceof RuntimeException error) throw error;
      throw failure;
    }
  }

  private enum RecordedChange { CONTINUATION, HISTORY, REMOVED_STONE, EXTRA_STONES }

  private static final class SessionOnlyStore implements CredentialStore {
    @Override
    public String backendName() {
      return "session-only-test";
    }

    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public Optional<String> read(Kind kind, String account) {
      return Optional.empty();
    }

    @Override
    public void write(Kind kind, String account, String secret) {
      throw new AssertionError("must not persist test key");
    }

    @Override
    public void delete(Kind kind, String account) {}
  }
}
