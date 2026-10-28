package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReadBoardHostedUpdateTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void hostedUpdateCopiesFinalSettingsOnlyAfterExitAndDoesNotRestartSurvivor(boolean exits)
      throws Exception {
    String previousDirectory = System.getProperty("user.dir");
    LizzieFrame previousFrame = Lizzie.frame;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    Leelaz previousEngine = Lizzie.leelaz;
    Path target = Files.createDirectory(tempDir.resolve("readboard"));
    Files.writeString(target.resolve("readboard.exe"), "old-exe");
    Files.writeString(target.resolve("config.readboard.json"), "before-quit");
    Path zip = tempDir.resolve("readboard-webview2-v3.2.0.zip");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (String name :
          List.of(
              "readboard.exe",
              "readboard.dll",
              "readboard.runtimeconfig.json",
              "readboard.deps.json",
              "language_cn.txt")) {
        output.putNextEntry(new ZipEntry(name));
        output.write("new-runtime".getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    ReadBoardUpdateRequest request =
        ReadBoardUpdateRequest.tryParse("readboardUpdateReady\tv3.2.0\t" + zip.toAbsolutePath());
    TrackingFrame frame = allocate(TrackingFrame.class);
    ReadBoard readBoard = allocate(ReadBoard.class);
    SavingProcess process = new SavingProcess(target, exits);
    readBoard.process = process;
    setField(ReadBoard.class, readBoard, "conflictTracker", new SyncConflictTracker());
    setField(ReadBoard.class, readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
    setField(
        ReadBoard.class, readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
    setField(ReadBoard.class, readBoard, "usePipe", true);
    setField(LizzieFrame.class, frame, "readBoardRestartLock", new Object());
    setField(LizzieFrame.class, frame, "readBoardUpdateInstaller", new ReadBoardUpdateInstaller());
    frame.readBoard = readBoard;
    try {
      System.setProperty("user.dir", tempDir.toString());
      Lizzie.frame = frame;
      Lizzie.leelaz = null;
      Lizzie.resourceBundle =
          new ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
              return new Object[][] {
                {"ReadBoard.updateInstallSucceeded", "%s installed"},
                {"ReadBoard.updateInstallFailed", "%s failed: %s"}
              };
            }
          };
      Method begin =
          LizzieFrame.class.getDeclaredMethod("beginHostedReadBoardUpdate", ReadBoard.class);
      begin.setAccessible(true);
      assertEquals(true, begin.invoke(frame, readBoard));
      Method install =
          LizzieFrame.class.getDeclaredMethod(
              "installHostedReadBoardUpdate", ReadBoard.class, ReadBoardUpdateRequest.class);
      install.setAccessible(true);

      install.invoke(frame, readBoard, request);
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(exits ? 1 : 0, frame.restarts);
      assertEquals(
          exits ? "new-runtime" : "old-exe", Files.readString(target.resolve("readboard.exe")));
      assertEquals(
          exits ? "saved-on-exit" : "before-quit",
          Files.readString(target.resolve("config.readboard.json")));
      assertEquals(!exits, process.isAlive());
      try (var paths = Files.list(tempDir)) {
        assertEquals(
            exits ? 1 : 0,
            paths
                .filter(path -> path.getFileName().toString().startsWith("readboard.backup-"))
                .count());
      }
    } finally {
      SwingUtilities.invokeAndWait(() -> {});
      System.setProperty("user.dir", previousDirectory);
      Lizzie.frame = previousFrame;
      Lizzie.resourceBundle = previousBundle;
      Lizzie.leelaz = previousEngine;
    }
  }

  private static final class TrackingFrame extends LizzieFrame {
    private int restarts;

    @Override
    protected ReadBoard createNativeReadBoard() throws Exception {
      restarts++;
      return allocate(ReadBoard.class);
    }
  }

  private static final class SavingProcess extends Process {
    private final Path directory;
    private final boolean exits;
    private boolean forced;
    private boolean alive = true;

    private SavingProcess(Path directory, boolean exits) {
      this.directory = directory;
      this.exits = exits;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      if (forced && exits) {
        try {
          assertEquals("old-exe", Files.readString(directory.resolve("readboard.exe")));
          Files.writeString(directory.resolve("config.readboard.json"), "saved-on-exit");
          alive = false;
        } catch (IOException failure) {
          throw new AssertionError(failure);
        }
      }
      return !alive;
    }

    @Override
    public Process destroyForcibly() {
      forced = true;
      return this;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public void destroy() {}

    @Override
    public int waitFor() {
      throw new AssertionError("unbounded wait");
    }

    @Override
    public int exitValue() {
      if (alive) throw new IllegalThreadStateException();
      return 0;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }
  }

  private static void setField(Class<?> type, Object target, String name, Object value)
      throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
