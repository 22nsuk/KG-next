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
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest
  @ValueSource(strings = {"list", "popup"})
  void unavailablePreviewsKeepSavedGamesUsable(String mode) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.run(
        getClass(), "saved-game-preview-" + mode, List.of(), List.of(mode));
    JSONObject evidence = new JSONObject(Files.readString(result));
    assertEquals("PASS", evidence.getString("result"));
    assertEquals(3, evidence.getJSONArray("cases").length());
  }

  @Test
  void previewReadsKeepListResponsiveAndRetireStaleImages() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result = DesktopProbeProcess.run(
        getClass(), "saved-game-preview-async", List.of(), List.of("async"));
    JSONObject evidence = new JSONObject(Files.readString(result));
    assertEquals("PASS", evidence.getString("result"));
    assertEquals(9, evidence.getJSONArray("cases").length());
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[args.length - 2]);
    Path result = Path.of(args[args.length - 1]);
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

      if (args.length == 3) {
        if (args[0].equals("async")) verifyAsyncPreviews(result, current, saved);
        else verifyUnavailablePreviews(args[0], work, result, current, saved);
        System.exit(0);
        return;
      }

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
          try (var read = SgfReadTestSupport.block()) {
            clickSavedItem(route);
            read.awaitStarted();
            onEdt(
                () -> {
                  before.assertPreserved();
                  assertTrue(savedList().isShowing(), "Disk reads must leave the list responsive");
                  assertNull(errorDialog());
                  return null;
                });
            read.release();
          }
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
              new JSONObject().put("route", route).put("failure", failure).put("retry", "PASS")
                  .put("responsiveWhileReading", true));
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

  private static void verifyUnavailablePreviews(
      String mode, Path work, Path result, Path current, Path saved) throws Exception {
    awaitEdt(() -> field("autoSaveStartupPrepared", Boolean.class));
    SgfSaveTestSupport.awaitPending();
    Path auto = saved.resolveSibling("autoGame1.sgf");
    Path healthy = saved.resolveSibling("game2.sgf");
    Path autoImage = saved.resolveSibling("autoGame1.bmp");
    Path savedImage = saved.resolveSibling("game1.bmp");
    byte[] validImage = Files.readAllBytes(savedImage);
    Files.writeString(auto, SAVED_SGF);
    Files.writeString(healthy, CURRENT_SGF);
    Files.write(healthy.resolveSibling("game2.bmp"), validImage);
    JSONObject metadata = new JSONObject()
        .put("save-auto-game-index1", 1)
        .put("save-auto-game-move-number1", 1)
        .put("save-game-index", new JSONArray(List.of(1, 2)))
        .put("save-game-name", new JSONArray(List.of("미리보기 오류 기보", "정상 미리보기 기보")))
        .put("save-game-time", new JSONArray(List.of("저장 시각 1", "저장 시각 2")))
        .put("save-game-move-number", new JSONArray(List.of(1, 1)))
        .put("save-game-move-list", new JSONArray(List.of("", "")));
    Path manifest = work.resolve("save/save");
    String persisted = new JSONObject().put("save", metadata).toString();
    Files.writeString(manifest, persisted);
    JSONArray cases = new JSONArray();
    List<String> failures = mode.equals("list")
        ? List.of("unsupported", "missing", "truncated")
        : List.of("missing", "unsupported", "truncated");
    for (String failure : failures) {
      DesktopProbeProcess.phase(result, mode + "-" + failure);
      openFixture(current);
      Snapshot before = onEdt(() -> {
        Lizzie.config.saveBoardConfig = new JSONObject(metadata.toString());
        Lizzie.config.loadASaveZoom = false;
        BoardHistoryList history = Lizzie.board.getHistory();
        history.setHead(history.getStart().next().orElseThrow().getVariation(1).orElseThrow());
        return Snapshot.capture();
      });
      Files.write(autoImage, validImage);
      Files.write(savedImage, validImage);
      if (mode.equals("list")) {
        damagePreview(autoImage, failure, validImage);
        damagePreview(savedImage, failure, validImage);
      }
      onEdt(() -> {
        Lizzie.frame.showTempGamePanel();
        assertEquals(3, savedButtons("load").size());
        assertEquals(3, savedButtons("del").size());
        assertEquals(3, savedButtons("reName").size());
        assertFalse(savedButtons("reName").get(0).isEnabled());
        assertTrue(savedButtons("reName").get(1).isEnabled());
        assertTrue(savedButtons("save").get(1).isEnabled());
        assertFalse(savedButtons("newRecord").isEmpty());
        assertTrue(metadata.similar(Lizzie.config.saveBoardConfig));
        return null;
      });
      if (mode.equals("list")) {
        SavedGamePreviewTestSupport.awaitPending(Lizzie.frame);
        onEdt(() -> {
          List<JLabel> images = previewLabels();
          assertEquals(3, images.size());
          for (int row : List.of(0, 1)) {
            assertNull(images.get(row).getIcon());
            assertFalse(images.get(row).getText().isBlank());
          }
          assertNotNull(images.get(2).getIcon());
          assertNull(errorDialog());
          return null;
        });
        capture(result.resolveSibling(failure + "-list-ko.png"));
        for (int row : List.of(0, 1, 2)) {
          if (row > 0) onEdt(() -> { Lizzie.frame.showTempGamePanel(); return null; });
          onEdt(() -> {
            if (row == 0) {
              JPanel panel = field("tempGamePanel", JPanel.class);
              panel.dispatchEvent(mouse(panel, MouseEvent.MOUSE_CLICKED, 150, 150));
            } else savedButtons("load").get(row).doClick(0);
            return null;
          });
          awaitEdt(() -> !savedList().isVisible() && !Lizzie.board.isLoadingFile);
          onEdt(() -> {
            assertEquals(1, Lizzie.board.getCurrentMovenumber());
            assertEquals(row == 2 ? "보존할 루트 메모" : "저장 기보 메모",
                Lizzie.board.getHistory().getStart().getData().comment);
            assertNull(errorDialog());
            return null;
          });
        }
      } else {
        onEdt(() -> { showPreview(false, 2); return null; });
        awaitEdt(() -> {
          JPopupMenu popup = field("bigBoardPanel", JPopupMenu.class);
          return popup != null && popup.isShowing();
        });
        onEdt(() -> {
          Lizzie.config.loadASaveZoom = true;
          JPanel panel = field("tempGamePanel", JPanel.class);
          panel.dispatchEvent(mouse(panel, MouseEvent.MOUSE_MOVED, 305, 5));
          return null;
        });
        damagePreview(autoImage, failure, validImage);
        onEdt(() -> {
          showPreview(true, 1);
          return null;
        });
        SavedGamePreviewTestSupport.awaitPending(Lizzie.frame);
        onEdt(() -> {
          JPopupMenu popup = field("bigBoardPanel", JPopupMenu.class);
          assertTrue(popup == null || !popup.isVisible());
          assertTrue(savedList().isShowing());
          assertNull(errorDialog());
          before.assertPreserved();
          return null;
        });
        Files.write(autoImage, validImage);
        // Retry the same preview without moving the pointer or rebuilding the list.
        onEdt(() -> { showPreview(true, 1); return null; });
        awaitEdt(() -> {
          JPopupMenu popup = field("bigBoardPanel", JPopupMenu.class);
          return popup != null && popup.isShowing();
        });
        capture(result.resolveSibling(failure + "-preview-recovered-ko.png"));
        onEdt(() -> {
          JPopupMenu popup = field("bigBoardPanel", JPopupMenu.class);
          popup.dispatchEvent(mouse(popup, MouseEvent.MOUSE_CLICKED, 0, 0));
          return null;
        });
        awaitEdt(() -> !savedList().isVisible() && !Lizzie.board.isLoadingFile);
        onEdt(() -> {
          assertEquals("저장 기보 메모", Lizzie.board.getHistory().getStart().getData().comment);
          assertEquals(1, Lizzie.board.getCurrentMovenumber());
          // Retire the previous successful popup through its existing mouse listener.
          JPanel panel = field("tempGamePanel", JPanel.class);
          panel.dispatchEvent(mouse(panel, MouseEvent.MOUSE_MOVED, 304, 5));
          return null;
        });
      }
      assertEquals(CURRENT_SGF, Files.readString(current));
      assertEquals(CURRENT_SGF, Files.readString(healthy));
      assertEquals(SAVED_SGF, Files.readString(auto));
      assertEquals(SAVED_SGF, Files.readString(saved));
      assertEquals(persisted, Files.readString(manifest));
      cases.put(new JSONObject().put("failure", failure).put("result", "PASS"));
    }
    Files.writeString(result, new JSONObject().put("result", "PASS").put("cases", cases).toString(2));
  }

  private static void verifyAsyncPreviews(Path result, Path current, Path saved) throws Exception {
    awaitEdt(() -> field("autoSaveStartupPrepared", Boolean.class));
    SgfSaveTestSupport.awaitPending();
    Path second = saved.resolveSibling("game2.sgf");
    Files.writeString(second, CURRENT_SGF);
    writeSolidPreview(saved.resolveSibling("game1.bmp"), 0xcc2211);
    writeSolidPreview(saved.resolveSibling("game2.bmp"), 0x22aa44);
    responsiveEdt(() -> {
      Lizzie.config.saveBoardConfig = new JSONObject()
          .put("save-game-index", new JSONArray(List.of(1, 2)))
          .put("save-game-name", new JSONArray(List.of("첫 기록", "다음 기록")))
          .put("save-game-time", new JSONArray(List.of("시각 1", "시각 2")))
          .put("save-game-move-number", new JSONArray(List.of(1, 1)))
          .put("save-game-move-list", new JSONArray(List.of("", "")));
      return null;
    });
    openFixture(current);
    JSONArray cases = new JSONArray();
    try (var read = SavedGamePreviewTestSupport.block(Lizzie.frame)) {
      responsiveEdt(() -> { Lizzie.frame.showTempGamePanel(); return null; });
      read.awaitStarted();
      JLabel old = responsiveEdt(() -> {
        assertEquals(2, savedButtons("load").size());
        assertEquals(1, read.reads());
        assertTrue(savedList().isShowing());
        assertNull(previewLabels().get(0).getIcon());
        return previewLabels().get(0);
      });
      capture(result.resolveSibling("loading-previews-ko.png"));
      // Image decoding does not share the SGF worker or delay its completion.
      clickSavedItem("button");
      awaitEdt(() -> !savedList().isVisible() && !Lizzie.board.isLoadingFile);
      writeSolidPreview(saved.resolveSibling("game1.bmp"), 0x2244cc);
      responsiveEdt(() -> { Lizzie.frame.showTempGamePanel(); return null; });
      read.release();
      SavedGamePreviewTestSupport.awaitPending(Lizzie.frame);
      responsiveEdt(() -> {
        assertNull(old.getIcon(), "The retired label must never receive its old image");
        assertEquals(0x2244cc, iconColor(previewLabels().get(0)));
        assertEquals(0x22aa44, iconColor(previewLabels().get(1)));
        assertNull(errorDialog());
        return null;
      });
      cases.put("responsive-list-and-reopen");
    }
    try (var read = SavedGamePreviewTestSupport.block(Lizzie.frame)) {
      responsiveEdt(() -> { Lizzie.frame.showTempGamePanel(); return null; });
      read.awaitStarted();
      JLabel retained = responsiveEdt(() -> {
        JLabel label = previewLabels().get(0);
        Lizzie.frame.setVisible(false);
        return label;
      });
      read.release();
      SavedGamePreviewTestSupport.awaitPending(Lizzie.frame);
      responsiveEdt(() -> {
        Lizzie.frame.setVisible(true);
        assertSame(retained, previewLabels().get(0));
        assertEquals(0x2244cc, iconColor(retained));
        return null;
      });
      cases.put("hidden-frame-retains-thumbnails");
    }
    try (var read = SavedGamePreviewTestSupport.block(Lizzie.frame)) {
      responsiveEdt(() -> {
        Lizzie.config.loadASaveZoom = true;
        JPanel panel = field("tempGamePanel", JPanel.class);
        javax.swing.Timer timer = null;
        for (int x = 100; x < 200; x++) {
          panel.dispatchEvent(mouse(panel, MouseEvent.MOUSE_MOVED, x, 150));
          var currentTimer = field("savedGameHoverTimer", javax.swing.Timer.class);
          if (timer != null) assertSame(timer, currentTimer);
          timer = currentTimer;
        }
        assertTrue(timer.isRunning());
        return null;
      });
      read.awaitStarted();
      responsiveEdt(() -> {
        assertEquals(1, read.reads(), "Rapid hover must converge to one image read");
        JPanel panel = field("tempGamePanel", JPanel.class);
        panel.dispatchEvent(mouse(panel, MouseEvent.MOUSE_MOVED, 460, 150));
        return null;
      });
      read.release();
      awaitEdt(() -> {
        JPopupMenu popup = field("bigBoardPanel", JPopupMenu.class);
        if (popup == null || !popup.isShowing()) return false;
        assertEquals(0x22aa44, iconColor((JLabel) popup.getComponent(0)),
            "Only the latest hovered record may be shown");
        return true;
      });
      assertEquals(2, read.reads());
      cases.put("latest-hover");
    }
    for (String action : List.of("option", "rebuild", "hide", "frame-hidden", "delete", "dispose")) {
      DesktopProbeProcess.phase(result, "async-" + action);
      responsiveEdt(() -> {
        Lizzie.frame.setVisible(true);
        Lizzie.config.loadASaveZoom = true;
        Lizzie.frame.showTempGamePanel();
        return null;
      });
      try (var read = SavedGamePreviewTestSupport.block(Lizzie.frame)) {
        responsiveEdt(() -> { showPreview(false, action.equals("dispose") ? 2 : 1); return null; });
        read.awaitStarted();
        responsiveEdt(() -> {
          switch (action) {
            case "option" -> {
              String text = Lizzie.resourceBundle.getString("LizzieFrame.saveAndLoad.chkZoomImage");
              Arrays.stream(field("tempGamePanelTop", JPanel.class).getComponents())
                  .filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast)
                  .filter(box -> text.equals(box.getText())).findFirst().orElseThrow().doClick(0);
            }
            case "rebuild" -> Lizzie.frame.showTempGamePanel();
            case "hide" -> Lizzie.frame.hideTempGamePanel(false, false);
            case "frame-hidden" -> Lizzie.frame.setVisible(false);
            case "delete" -> savedButtons("del").get(0).doClick(0);
            case "dispose" -> Lizzie.frame.dispose();
            default -> throw new AssertionError(action);
          }
          return null;
        });
        if (action.equals("delete")) SgfSaveTestSupport.awaitPending();
        read.release();
        SavedGamePreviewTestSupport.awaitPending(Lizzie.frame);
        responsiveEdt(() -> {
          JPopupMenu popup = field("bigBoardPanel", JPopupMenu.class);
          assertTrue(popup == null || !popup.isVisible(), action);
          assertFalse(field("savedGameHoverTimer", javax.swing.Timer.class).isRunning(), action);
          return null;
        });
        cases.put(action);
      }
    }
    Files.writeString(result, new JSONObject().put("result", "PASS").put("cases", cases).toString(2));
  }

  private static int iconColor(JLabel label) {
    return ((BufferedImage) ((ImageIcon) label.getIcon()).getImage()).getRGB(0, 0) & 0xffffff;
  }

  private static void writeSolidPreview(Path path, int color) throws Exception {
    BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) image.setRGB(x, y, color);
    assertTrue(ImageIO.write(image, "bmp", path.toFile()));
  }

  private static void damagePreview(Path path, String failure, byte[] valid) throws Exception {
    switch (failure) {
      case "missing" -> Files.delete(path);
      case "unsupported" -> Files.writeString(path, "This is not an image.");
      case "truncated" -> Files.write(path, Arrays.copyOf(valid, 26));
      default -> throw new AssertionError(failure);
    }
  }

  private static List<JButton> savedButtons(String suffix) {
    String text = Lizzie.resourceBundle.getString("LizzieFrame.saveAndLoad." + suffix);
    return Arrays.stream(field("tempGamePanel", JPanel.class).getComponents())
        .filter(JButton.class::isInstance).map(JButton.class::cast)
        .filter(button -> text.equals(button.getText())).toList();
  }

  private static List<JLabel> previewLabels() {
    return Arrays.stream(field("tempGamePanel", JPanel.class).getComponents())
        .filter(JLabel.class::isInstance).map(JLabel.class::cast)
        .filter(label -> label.getWidth() == 300 && label.getHeight() == 300).toList();
  }

  private static void showPreview(boolean automatic, int index) throws Exception {
    Method show = LizzieFrame.class.getDeclaredMethod("showBigBoardImage", boolean.class,
        int.class, JPanel.class, int.class, int.class, int.class, String.class,
        boolean.class, boolean.class);
    show.setAccessible(true);
    Lizzie.config.loadASaveZoom = true;
    show.invoke(Lizzie.frame, automatic, index, field("tempGamePanel", JPanel.class),
        10, 25, 1, "", false, false);
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

  /** A blocked preview decoder must not hold unrelated UI actions until it is released. */
  private static <T> T responsiveEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(5, TimeUnit.SECONDS);
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
