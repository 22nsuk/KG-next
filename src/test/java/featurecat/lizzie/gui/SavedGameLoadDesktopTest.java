package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import java.awt.Component;
import java.awt.Container;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.image.RenderedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Production windows, saved-list listeners, parser rollback, and successful retry. */
class SavedGameLoadDesktopTest {
  private static final String CURRENT_SGF =
      "(;FF[4]GM[1]CA[UTF-8]SZ[19]KM[6.5]GN[현재 사용자 기보]PB[원래 흑]PW[원래 백]"
          + "RE[B+2.5]C[보존할 루트 메모]"
          + ";B[aa]C[첫 수](;W[bb];B[cc])(;W[dd]C[보존할 변형];B[ee]))";
  private static final String SAVED_SGF =
      "(;FF[4]GM[1]CA[UTF-8]SZ[19]KM[6.5]GN[다시 연 저장 기보]C[저장 기보 메모]"
          + ";B[kk](;W[nn];B[oo])(;W[ll];B[mm]C[저장된 위치];W[pp]))";
  private static final String SAVED_MOVES = "12,12,b_11,11,w_10,10,b";

  @Test
  void failedSavedListOpensPreserveCurrentGameAndAllowRetry() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(getClass(), "saved-game-load", List.of(), List.of());
    JSONObject evidence = new JSONObject(Files.readString(result));
    assertEquals("PASS", evidence.getString("result"));
    assertEquals(6, evidence.getJSONArray("cases").length());
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},"
              + "\"logging\":{\"diagnostics-enabled\":false},"
              + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,"
              + "\"play-sound\":false,\"use-language\":3}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      awaitEdt(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
      onEdt(
          () -> {
            for (Window window : Window.getWindows()) {
              if (window instanceof JDialog) window.dispose();
            }
            // Match the application's offline state without sending moves to its empty placeholder.
            Lizzie.setPrimaryEngine(null);
            assertNull(Lizzie.leelaz);
            Lizzie.frame.setBounds(60, 60, 1180, 820);
            Lizzie.config.loadSgfLast = true;
            return null;
          });

      Path current = Files.writeString(work.resolve("현재-사용자-기보.sgf"), CURRENT_SGF);
      Path saved = Files.createDirectories(work.resolve("save")).resolve("game1.sgf");
      Files.writeString(saved, SAVED_SGF);
      openFixture(saved);
      assertTrue(
          ImageIO.write(
              onEdt(() -> (RenderedImage) Lizzie.frame.saveMainBoardToImageOri()),
              "bmp",
              saved.resolveSibling("game1.bmp").toFile()));

      JSONArray cases = new JSONArray();
      for (String route : List.of("button", "thumbnail", "preview")) {
        for (String failure : List.of("missing", "malformed")) {
          openFixture(current);
          Snapshot before =
              onEdt(
                  () -> {
                    BoardHistoryList history = Lizzie.board.getHistory();
                    BoardHistoryNode branch =
                        history.getStart().next().orElseThrow().getVariation(1).orElseThrow();
                    history.setHead(branch);
                    LizzieFrame.curFile = current.toFile();
                    Lizzie.frame.refresh();
                    return Snapshot.capture();
                  });
          if (failure.equals("missing")) Files.deleteIfExists(saved);
          else Files.writeString(saved, "This is not an SGF game.");
          onEdt(
              () -> {
                Lizzie.config.saveBoardConfig =
                    new JSONObject()
                        .put("save-game-index", new JSONArray().put(1))
                        .put("save-game-name", new JSONArray().put("복원할 저장 기보"))
                        .put("save-game-time", new JSONArray().put("저장 목록 열기 검증"))
                        .put("save-game-move-number", new JSONArray().put(1))
                        .put(
                            "save-game-move-list",
                            new JSONArray().put(route.equals("thumbnail") ? "" : SAVED_MOVES));
                Lizzie.config.loadASaveZoom = route.equals("preview");
                Lizzie.frame.showTempGamePanel();
                return null;
              });

          String scenario = route + "-" + failure;
          DesktopProbeProcess.phase(result, scenario + "-open");
          clickSavedItem(route);
          awaitEdt(() -> errorDialog() != null);
          onEdt(
              () -> {
                before.assertPreserved();
                assertTrue(savedList().isShowing(), "Failed open must leave the list available");
                assertEquals(Lizzie.frame, errorDialog().getOwner());
                return null;
              });
          capture(result.resolveSibling(scenario + "-failure-ko.png"));
          onEdt(
              () -> {
                errorDialog().getRootPane().getDefaultButton().doClick(0);
                return null;
              });
          awaitEdt(() -> errorDialog() == null);
          onEdt(
              () -> {
                before.assertPreserved();
                assertTrue(savedList().isShowing());
                return null;
              });
          assertEquals(CURRENT_SGF, Files.readString(current));

          // Repair the same saved entry and retry its existing UI action without rebuilding the list.
          Files.writeString(saved, SAVED_SGF);
          clickSavedItem(route);
          awaitEdt(() -> !savedList().isVisible() && !Lizzie.board.isLoadingFile);
          onEdt(
              () -> {
                assertNotSame(before.history, Lizzie.board.getHistory());
                assertEquals(
                    route.equals("thumbnail") ? 1 : 3, Lizzie.board.getCurrentMovenumber());
                assertEquals(Stone.BLACK, stoneAt(10, 10));
                assertEquals(Stone.EMPTY, stoneAt(0, 0));
                if (!route.equals("thumbnail")) {
                  assertEquals(Stone.WHITE, stoneAt(11, 11));
                  assertEquals(Stone.BLACK, stoneAt(12, 12));
                  assertEquals("저장된 위치", Lizzie.board.getHistory().getData().comment);
                }
                assertEquals("game1.sgf", LizzieFrame.fileNameTitle);
                assertNull(errorDialog());
                JPopupMenu popup = field("bigBoardPanel", JPopupMenu.class);
                assertTrue(popup == null || !popup.isVisible());
                return null;
              });
          capture(result.resolveSibling(scenario + "-success-ko.png"));
          assertEquals(CURRENT_SGF, Files.readString(current));
          cases.put(
              new JSONObject().put("route", route).put("failure", failure).put("retry", "PASS"));
        }
      }
      Files.writeString(
          result, new JSONObject().put("result", "PASS").put("cases", cases).toString(2));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void openFixture(Path file) throws Exception {
    assertTrue(onEdt(() -> Lizzie.frame.loadFile(file.toFile(), true, false)));
    awaitEdt(() -> !Lizzie.board.isLoadingFile);
  }

  private static void clickSavedItem(String route) throws Exception {
    if (route.equals("preview")) {
      onEdt(
          () -> {
            JPanel panel = field("tempGamePanel", JPanel.class);
            // Move off the image before hovering again, as after dismissing a popup or dialog.
            panel.dispatchEvent(mouse(panel, MouseEvent.MOUSE_MOVED, 305, 5));
            panel.dispatchEvent(mouse(panel, MouseEvent.MOUSE_MOVED, 150, 150));
            return null;
          });
      awaitEdt(
          () -> {
            JPopupMenu popup = field("bigBoardPanel", JPopupMenu.class);
            return popup != null && popup.isShowing();
          });
    }
    onEdt(
        () -> {
          JPanel panel = field("tempGamePanel", JPanel.class);
          if (route.equals("button")) {
            String text = Lizzie.resourceBundle.getString("LizzieFrame.saveAndLoad.load");
            for (Component component : panel.getComponents()) {
              if (component instanceof JButton button && text.equals(button.getText())) {
                button.doClick(0);
                return null;
              }
            }
            fail("Saved item load button missing");
          } else if (route.equals("thumbnail")) {
            panel.dispatchEvent(mouse(panel, MouseEvent.MOUSE_CLICKED, 150, 150));
          } else {
            JPopupMenu popup = field("bigBoardPanel", JPopupMenu.class);
            // The current production popup listener accepts its origin click only.
            popup.dispatchEvent(mouse(popup, MouseEvent.MOUSE_CLICKED, 0, 0));
          }
          return null;
        });
  }

  private static MouseEvent mouse(Component source, int id, int x, int y) {
    return new MouseEvent(source, id, System.currentTimeMillis(), 0, x, y, 1, false);
  }

  private static JComponent savedList() {
    return field("tempGamePanelAll", JComponent.class);
  }

  private static JDialog errorDialog() {
    String message = Lizzie.resourceBundle.getString("LizzieFrame.prompt.failedToOpenFile");
    for (Window window : Window.getWindows()) {
      if (window instanceof JDialog dialog
          && dialog.isShowing()
          && containsMessage(dialog, message)) {
        return dialog;
      }
    }
    return null;
  }

  private static boolean containsMessage(Container container, String message) {
    if (container instanceof JOptionPane pane && message.equals(pane.getMessage())) return true;
    for (Component component : container.getComponents()) {
      if (component instanceof Container child && containsMessage(child, message)) return true;
    }
    return false;
  }

  private static Stone stoneAt(int x, int y) {
    return Lizzie.board.getHistory().getData().stones[Board.getIndex(x, y)];
  }

  private record Snapshot(
      BoardHistoryList history, BoardHistoryNode node, String sgf, File file, String title) {
    static Snapshot capture() throws Exception {
      return new Snapshot(
          Lizzie.board.getHistory(),
          Lizzie.board.getHistory().getCurrentHistoryNode(),
          SGFParser.saveSnapshot(Lizzie.board, true, true, false),
          LizzieFrame.curFile,
          Lizzie.frame.getTitle());
    }

    void assertPreserved() throws Exception {
      assertSame(history, Lizzie.board.getHistory());
      assertSame(node, history.getCurrentHistoryNode());
      assertEquals(sgf, SGFParser.saveSnapshot(Lizzie.board, true, true, false));
      assertEquals(file, LizzieFrame.curFile);
      assertEquals(title, Lizzie.frame.getTitle());
      assertEquals(2, history.getMoveNumber());
      assertEquals(Stone.BLACK, stoneAt(0, 0));
      assertEquals(Stone.WHITE, stoneAt(3, 3));
      assertEquals("보존할 변형", history.getData().comment);
    }
  }

  private static <T> T field(String name, Class<T> type) {
    try {
      Field field = LizzieFrame.class.getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(Lizzie.frame));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static <T> T onEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeAndWait(task);
    return task.get();
  }

  private static void awaitEdt(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!onEdt(condition::getAsBoolean)) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Saved-game UI condition timed out");
      Thread.sleep(50);
    }
  }

  private static void capture(Path destination) throws Exception {
    var bounds =
        onEdt(
            () -> {
              var root = Lizzie.frame.getRootPane();
              root.paintImmediately(0, 0, root.getWidth(), root.getHeight());
              JDialog error = errorDialog();
              if (error != null) {
                var dialogRoot = error.getRootPane();
                dialogRoot.paintImmediately(
                    0, 0, dialogRoot.getWidth(), dialogRoot.getHeight());
              }
              Toolkit.getDefaultToolkit().sync();
              return Lizzie.frame.getBounds();
            });
    assertTrue(
        ImageIO.write(
            new Robot().createScreenCapture(bounds), "png", destination.toFile()));
  }
}
