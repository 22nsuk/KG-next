package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.SGFParser;
import java.awt.Component;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Real saved-list controls stay usable until the queued record reaches disk. */
class SavedGameSaveDesktopTest {
  private static final String SAVED_NAME = "디스크 저장 완료 후 표시할 기보";
  private static final String CURRENT_SGF =
      "(;FF[4]GM[1]CA[UTF-8]SZ[19]KM[6.5]GN[비동기 저장 검증 기보]"
          + "PB[검증 흑]PW[검증 백]C[저장할 루트 메모]"
          + ";B[pd];W[dp];B[pp](;W[dd];B[qj])(;W[dc]C[보존할 변형]))";

  @Test
  void pendingSaveKeepsTheListResponsiveAndPublishesAfterDiskCompletion() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(getClass(), "saved-game-save", List.of(), List.of());
    JSONObject evidence = new JSONObject(Files.readString(result));
    assertEquals("PASS", evidence.getString("result"));
    assertTrue(evidence.getBoolean("edtRespondedWhilePending"));
    assertTrue(evidence.getBoolean("filesAndListUnchangedWhilePending"));
    assertTrue(evidence.getBoolean("pendingOpenResumedWithoutSecondClick"));
    assertEquals(1, evidence.getInt("savedRecords"));
    for (String image : List.of("pending-save-ko.png", "completed-save-ko.png")) {
      assertNotNull(ImageIO.read(result.resolveSibling(image).toFile()));
    }
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
              + "\"auto-save-exit\":false,\"resume-previous-game\":false,"
              + "\"play-sound\":false,\"use-language\":3}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      awaitEdt(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
      onEdt(
          () -> {
            for (Window window : Window.getWindows()) {
              if (window instanceof JDialog) window.dispose();
            }
            // Use the application's offline state without an empty engine placeholder.
            Lizzie.setPrimaryEngine(null);
            assertNull(Lizzie.leelaz);
            Lizzie.frame.setBounds(60, 60, 1180, 820);
            Lizzie.config.loadSgfLast = true;
            return null;
          });
      awaitEdt(() -> field("autoSaveStartupPrepared", Boolean.class));
      SgfSaveTestSupport.awaitPending();

      Path current = Files.writeString(work.resolve("비동기-저장-검증.sgf"), CURRENT_SGF);
      assertTrue(onEdt(() -> Lizzie.frame.loadFile(current.toFile(), true, false)));
      awaitEdt(() -> !Lizzie.board.isLoadingFile);
      String expectedSgf = onEdt(() -> SGFParser.captureSave(Lizzie.board, false));
      int expectedMove = onEdt(() -> Lizzie.board.getCurrentMovenumber());
      assertTrue(expectedMove > 0);
      JButton newRecord =
          onEdt(
              () -> {
                assertTrue(Lizzie.frame.getSaveGameList().isEmpty());
                Lizzie.frame.showTempGamePanel();
                JButton button = newRecordButton();
                assertEquals("새로운 기록", button.getText());
                assertTrue(button.isShowing());
                newRecordName(button).setText(SAVED_NAME);
                return button;
              });
      String metadataBefore = onEdt(() -> Lizzie.config.saveBoardConfig.toString());
      Path saveDirectory = work.resolve("save");
      Map<String, byte[]> filesBefore = savedFiles(saveDirectory);

      DesktopProbeProcess.phase(result, "block-save-worker");
      try (var blocked = SgfSaveTestSupport.blockWorker()) {
        onEdt(
            () -> {
              newRecord.doClick(0);
              assertFalse(newRecord.isEnabled(), "The submitted control must show its pending state");
              return null;
            });
        // A separate, bounded EDT event must run while the disk worker is still occupied.
        onEdt(
            () -> {
              assertTrue(savedList().isShowing());
              assertSame(newRecord, newRecordButton(), "The list must wait for disk completion");
              assertFalse(newRecord.isEnabled());
              assertTrue(Lizzie.frame.getSaveGameList().isEmpty());
              assertEquals(metadataBefore, Lizzie.config.saveBoardConfig.toString());
              return null;
            });
        assertSavedFilesUnchanged(filesBefore, saveDirectory);
        DesktopProbeProcess.phase(result, "pending-save-edt-responsive");
        capture(result.resolveSibling("pending-save-ko.png"));
        assertSavedFilesUnchanged(filesBefore, saveDirectory);
        blocked.release();
      }

      DesktopProbeProcess.phase(result, "save-committed");
      TempGameData saved =
          onEdt(
              () -> {
                assertTrue(savedList().isShowing());
                assertTrue(newRecord.isEnabled());
                assertNotSame(newRecord, newRecordButton(), "Successful save must refresh the list");
                assertTrue(button("LizzieFrame.saveAndLoad.load", 50).isShowing());
                assertEquals(1, Lizzie.frame.getSaveGameList().size());
                TempGameData entry = Lizzie.frame.getSaveGameList().get(0);
                assertEquals(SAVED_NAME, entry.name);
                assertEquals(expectedMove, entry.curMoveNumer);
                return entry;
              });
      Path sgf = saveDirectory.resolve("game" + saved.index + ".sgf");
      Path preview = saveDirectory.resolve("game" + saved.index + ".bmp");
      assertEquals(expectedSgf, Files.readString(sgf));
      var previewImage = ImageIO.read(preview.toFile());
      assertNotNull(previewImage, "The committed record must include its board preview");
      assertTrue(previewImage.getWidth() > 0 && previewImage.getHeight() > 0);
      JSONObject manifest =
          new JSONObject(Files.readString(saveDirectory.resolve("save"))).getJSONObject("save");
      assertEquals(List.of(saved.index), manifest.getJSONArray("save-game-index").toList());
      assertEquals(SAVED_NAME, manifest.getJSONArray("save-game-name").getString(0));
      assertEquals(expectedMove, manifest.getJSONArray("save-game-move-number").getInt(0));
      onEdt(
          () -> {
            assertTrue(manifest.similar(Lizzie.config.saveBoardConfig));
            return null;
          });
      capture(result.resolveSibling("completed-save-ko.png"));
      onEdt(() -> {
        Lizzie.board.getHistory().getStart().getData().setAuthoredComment("저장본과 구분할 현재 주석");
        return null;
      });
      DesktopProbeProcess.phase(result, "open-while-save-pending");
      try (var blocked = SgfSaveTestSupport.blockWorker()) {
        onEdt(() -> {
          button("LizzieFrame.saveAndLoad.load", 50).doClick(0);
          assertTrue(savedList().isShowing());
          assertEquals("저장본과 구분할 현재 주석", Lizzie.board.getHistory().getStart().getData().comment);
          return null;
        });
        onEdt(() -> {
          Lizzie.frame.showTempGamePanel();
          assertTrue(savedList().isShowing());
          return null;
        });
        blocked.release();
      }
      awaitEdt(() -> !savedList().isVisible());
      onEdt(() -> {
        assertEquals("저장할 루트 메모", Lizzie.board.getHistory().getStart().getData().comment);
        assertEquals(expectedMove, Lizzie.board.getCurrentMovenumber());
        return null;
      });
      Files.writeString(
          result,
          new JSONObject()
              .put("result", "PASS")
              .put("edtRespondedWhilePending", true)
              .put("filesAndListUnchangedWhilePending", true)
              .put("pendingOpenResumedWithoutSecondClick", true)
              .put("savedRecords", 1)
              .put("savedIndex", saved.index)
              .put("savedMove", expectedMove)
              .toString(2));
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static JButton newRecordButton() {
    // The same label is also used by the 300px thumbnail action.
    return button("LizzieFrame.saveAndLoad.newRecord", 80);
  }

  private static JButton button(String resourceKey, int width) {
    String text = Lizzie.resourceBundle.getString(resourceKey);
    for (Component component : field("tempGamePanel", JPanel.class).getComponents()) {
      if (component instanceof JButton button
          && text.equals(button.getText())
          && button.getWidth() == width) return button;
    }
    throw new AssertionError("Saved-list button missing: " + text);
  }

  private static JTextField newRecordName(JButton button) {
    for (Component component : field("tempGamePanel", JPanel.class).getComponents()) {
      if (component instanceof JTextField name
          && name.getY() == button.getY()
          && name.getX() + name.getWidth() == button.getX() - 1) return name;
    }
    throw new AssertionError("New-record name field missing");
  }

  private static JComponent savedList() {
    return field("tempGamePanelAll", JComponent.class);
  }

  private static Map<String, byte[]> savedFiles(Path directory) throws Exception {
    Map<String, byte[]> files = new TreeMap<>();
    if (!Files.exists(directory)) return files;
    try (var paths = Files.walk(directory)) {
      for (Path file : paths.filter(Files::isRegularFile).toList()) {
        files.put(directory.relativize(file).toString(), Files.readAllBytes(file));
      }
    }
    return files;
  }

  private static void assertSavedFilesUnchanged(Map<String, byte[]> before, Path directory)
      throws Exception {
    Map<String, byte[]> after = savedFiles(directory);
    assertEquals(before.keySet(), after.keySet(), "Pending save must not create record files");
    for (String file : before.keySet()) {
      assertArrayEquals(before.get(file), after.get(file), "Pending save changed " + file);
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
              Toolkit.getDefaultToolkit().sync();
              return Lizzie.frame.getBounds();
            });
    assertTrue(
        ImageIO.write(new Robot().createScreenCapture(bounds), "png", destination.toFile()));
  }
}
