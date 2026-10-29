package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ReadBoardShutdownTest {
  private static final long MAX_EDT_RESTART_BLOCK_MS = 150L;
  private static final long SHUTDOWN_WAIT_BLOCK_MS = 300L;
  private static final long RESTART_TIMEOUT_MS = 2L;

  @Test
  void updateWaitsForActualExitAfterForcedTermination() throws Exception {
    ReadBoard readBoard = initializedReadBoardForRetirement();
    UpdateProcess process = new UpdateProcess();
    process.exitAfterForce = true;
    readBoard.process = process;

    readBoard.shutdownForUpdate();

    assertTrue(process.forced);
    assertFalse(process.isAlive());
    assertEquals(3, process.waits, "forced termination still needs a completed exit wait");
    assertNull(readBoard.process, "normal shutdown should still detach the public process");
  }

  @Test
  void updateRejectsSurvivingProcessAndMissingProcessIdentity() throws Exception {
    ReadBoard readBoard = initializedReadBoardForRetirement();
    UpdateProcess process = new UpdateProcess();
    readBoard.process = process;

    assertThrows(IOException.class, readBoard::shutdownForUpdate);
    assertTrue(process.isAlive());
    assertTrue(process.forced);
    assertThrows(IOException.class, initializedReadBoardForRetirement()::shutdownForUpdate);
  }

  @Test
  void updatePreservesInterruptionAndDoesNotConfirmExit() throws Exception {
    ReadBoard readBoard = initializedReadBoardForRetirement();
    UpdateProcess process = new UpdateProcess();
    process.interruptWait = true;
    readBoard.process = process;
    try {
      IOException failure = assertThrows(IOException.class, readBoard::shutdownForUpdate);
      assertTrue(failure.getCause() instanceof InterruptedException);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void updateStillWaitsWhenReaderAlreadyDetachedPublicProcess() throws Exception {
    ReadBoard readBoard = initializedReadBoardForRetirement();
    CountDownLatch readerClosing = new CountDownLatch(1);
    CountDownLatch releaseReader = new CountDownLatch(1);
    UpdateProcess process = new UpdateProcess();
    readBoard.process = process;
    setField(
        readBoard,
        "inputStream",
        new InputStreamReader(InputStream.nullInputStream()) {
          @Override
          public void close() {
            readerClosing.countDown();
            try {
              assertTrue(releaseReader.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
              throw new AssertionError(failure);
            }
          }
        });
    AtomicReference<Throwable> readerFailure = new AtomicReference<>();
    Thread reader =
        new Thread(
            () -> {
              try {
                readBoard.shutdownAfterProcessEnd();
              } catch (Throwable failure) {
                readerFailure.set(failure);
              }
            });
    reader.start();
    try {
      assertTrue(readerClosing.await(5, TimeUnit.SECONDS));
      assertNull(readBoard.process);
      assertThrows(IOException.class, readBoard::shutdownForUpdate);
      assertEquals(1, process.waits, "update must wait for the retained process, not trust null");
    } finally {
      releaseReader.countDown();
      reader.join(5000);
    }
    assertFalse(reader.isAlive());
    assertNull(readerFailure.get());
  }

  private static final class UpdateProcess extends Process {
    private volatile boolean alive = true;
    private boolean forced;
    private boolean exitAfterForce;
    private boolean interruptWait;
    private int waits;

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      waits++;
      if (interruptWait) throw new InterruptedException("controlled update interruption");
      if (forced && exitAfterForce) alive = false;
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
      throw new AssertionError("unbounded process wait");
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

  @Test
  void shutdownReleasesHostedReadBoardResources() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      TrackingInputStreamReader inputStream =
          new TrackingInputStreamReader(new ByteArrayInputStream(new byte[0]));
      TrackingBufferedOutputStream outputStream = new TrackingBufferedOutputStream();
      TrackingServerSocket serverSocket = new TrackingServerSocket();
      TrackingSocket socket = new TrackingSocket();
      ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

      frame.syncBoard = true;
      frame.bothSync = true;
      frame.readBoard = readBoard;
      Lizzie.frame = frame;

      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      setField(readBoard, "usePipe", true);
      setField(readBoard, "inputStream", inputStream);
      setField(readBoard, "outputStream", outputStream);
      setField(readBoard, "executor", executor);
      setField(readBoard, "s", serverSocket);
      setField(readBoard, "socket", socket);

      readBoard.shutdown();

      assertFalse(frame.syncBoard, "shutdown should clear syncBoard state.");
      assertFalse(frame.bothSync, "shutdown should clear bothSync state.");
      assertNull(frame.readBoard, "shutdown should detach the closed ReadBoard from frame.");
      assertTrue(outputStream.closeCalled, "shutdown should close hosted stdin.");
      assertTrue(inputStream.closeCalled, "shutdown should close hosted stdout reader.");
      assertTrue(serverSocket.closeCalled, "shutdown should close hosted server socket.");
      assertTrue(socket.closeCalled, "shutdown should close hosted socket.");
      assertTrue(executor.isShutdown(), "shutdown should stop the read executor.");
      assertNull(
          getField(readBoard, "outputStream"), "shutdown should drop hosted stdin reference.");
      assertNull(
          getField(readBoard, "inputStream"), "shutdown should drop hosted stdout reference.");
      assertNull(
          getField(readBoard, "executor"), "shutdown should drop hosted executor reference.");
      assertNull(getField(readBoard, "socket"), "shutdown should drop hosted socket reference.");
      assertNull(getField(readBoard, "s"), "shutdown should drop hosted server socket reference.");
      assertTrue(
          outputStream.writtenText().contains("quit"),
          "shutdown should send quit before releasing resources.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void shutdownAfterProcessEndDoesNotWriteQuitToClosedPipe() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      TrackingInputStreamReader inputStream =
          new TrackingInputStreamReader(new ByteArrayInputStream(new byte[0]));
      TrackingBufferedOutputStream outputStream = new TrackingBufferedOutputStream();
      ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

      frame.syncBoard = true;
      frame.bothSync = true;
      frame.readBoard = readBoard;
      Lizzie.frame = frame;

      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      setField(readBoard, "usePipe", true);
      setField(readBoard, "inputStream", inputStream);
      setField(readBoard, "outputStream", outputStream);
      setField(readBoard, "executor", executor);

      readBoard.shutdownAfterProcessEnd();

      assertFalse(frame.syncBoard, "process-ended shutdown should clear syncBoard state.");
      assertFalse(frame.bothSync, "process-ended shutdown should clear bothSync state.");
      assertNull(frame.readBoard, "process-ended shutdown should detach the closed ReadBoard.");
      assertTrue(outputStream.closeCalled, "process-ended shutdown should close hosted stdin.");
      assertTrue(
          inputStream.closeCalled, "process-ended shutdown should close hosted stdout reader.");
      assertFalse(
          outputStream.writtenText().contains("quit"),
          "process-ended shutdown should not write quit to a readboard pipe that already ended.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void shutdownOnlyWritesQuitOnceWhenCalledRepeatedly() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      TrackingInputStreamReader inputStream =
          new TrackingInputStreamReader(new ByteArrayInputStream(new byte[0]));
      TrackingBufferedOutputStream outputStream = new TrackingBufferedOutputStream();
      ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

      frame.readBoard = readBoard;
      Lizzie.frame = frame;

      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      setField(readBoard, "usePipe", true);
      setField(readBoard, "inputStream", inputStream);
      setField(readBoard, "outputStream", outputStream);
      setField(readBoard, "executor", executor);

      readBoard.shutdown();
      String firstShutdownWrite = outputStream.writtenText();
      readBoard.shutdown();

      assertTrue(
          firstShutdownWrite.contains("quit"), "normal shutdown should ask readboard to quit.");
      assertEquals(
          firstShutdownWrite,
          outputStream.writtenText(),
          "repeated shutdown should not write another quit command.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void explicitShutdownRetiresGmaAndConsumesOneLateTerminalResponse() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      RecordingGmaRetirementLeelaz engine = new RecordingGmaRetirementLeelaz();
      ReadBoard readBoard = initializedReadBoardForRetirement();
      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.readBoard = readBoard;
      Lizzie.frame = frame;
      Lizzie.leelaz = engine;
      setField(readBoard, "readBoardGmaPending", true);
      engine.bindReadBoardGmaResponseOwner(readBoard);

      readBoard.shutdown();
      readBoard.shutdown();

      assertEquals(1, engine.retirementCount);
      assertTrue(readBoard.handleReadBoardGmaEnginePlay("D4"));
      assertFalse(readBoard.handleReadBoardGmaEnginePlay("Q16"));
      assertFalse(getBooleanField(readBoard, "readBoardGmaPending"));
      assertNull(frame.readBoard);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void eofAndIoShutdownShareTheSameExactlyOnceRetirementPath() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      RecordingGmaRetirementLeelaz engine = new RecordingGmaRetirementLeelaz();
      Lizzie.leelaz = engine;
      LizzieFrame frame = allocate(LizzieFrame.class);
      Lizzie.frame = frame;

      ReadBoard eof = initializedReadBoardForRetirement();
      frame.readBoard = eof;
      eof.shutdownAfterProcessEnd();
      eof.shutdownAfterProcessEnd();
      assertEquals(1, engine.retirementCount);

      ReadBoard io = initializedReadBoardForRetirement();
      frame.readBoard = io;
      setField(
          io,
          "inputStream",
          new InputStreamReader(
              new InputStream() {
                @Override
                public int read() throws java.io.IOException {
                  throw new java.io.IOException("controlled helper read failure");
                }
              }));
      Method read = ReadBoard.class.getDeclaredMethod("read");
      read.setAccessible(true);
      read.invoke(io);

      assertEquals(2, engine.retirementCount);
      assertNull(frame.readBoard);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }


  @Test
  void openBoardSyncDoesNotBlockEventDispatchThreadWhileRestarting() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      TrackingLizzieFrame frame = allocate(TrackingLizzieFrame.class);
      ReadBoard existingReadBoard = allocate(ReadBoard.class);
      frame.initialize(existingReadBoard, SHUTDOWN_WAIT_BLOCK_MS);
      frame.readBoard = existingReadBoard;
      Lizzie.frame = frame;

      AtomicLong elapsedMs = new AtomicLong();
      SwingUtilities.invokeAndWait(
          () -> {
            long startNanos = System.nanoTime();
            frame.openBoardSync();
            elapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
          });

      assertTrue(
          elapsedMs.get() < MAX_EDT_RESTART_BLOCK_MS,
          "restarting readboard should return quickly on the event dispatch thread.");
      assertTrue(
          frame.awaitShutdownStarted(RESTART_TIMEOUT_MS, TimeUnit.SECONDS),
          "restart should begin shutting down the existing readboard in the background.");
      assertTrue(
          frame.awaitRestart(RESTART_TIMEOUT_MS, TimeUnit.SECONDS),
          "restart should eventually create a replacement readboard.");
      SwingUtilities.invokeAndWait(() -> {});
      assertFalse(
          frame.startedBeforeShutdownCompleted,
          "replacement readboard should not start before the previous instance finishes shutting down.");
      assertSame(
          frame.createdReadBoard, frame.readBoard, "restart should publish the new readboard.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static boolean getBooleanField(Object target, String name) throws Exception {
    return (boolean) getField(target, name);
  }

  private static ReadBoard initializedReadBoardForRetirement() throws Exception {
    ReadBoard readBoard = allocate(ReadBoard.class);
    setField(readBoard, "conflictTracker", new SyncConflictTracker());
    setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
    setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
    setField(readBoard, "tempcount", new ArrayList<Integer>());
    setField(readBoard, "usePipe", true);
    return readBoard;
  }


  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }

  private static final class TrackingInputStreamReader extends InputStreamReader {
    private boolean closeCalled;

    private TrackingInputStreamReader(InputStream stream) {
      super(stream, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
      closeCalled = true;
    }
  }

  private static final class TrackingBufferedOutputStream extends BufferedOutputStream {
    private boolean closeCalled;
    private final ByteArrayOutputStream capture;

    private TrackingBufferedOutputStream() {
      this(new ByteArrayOutputStream());
    }

    private TrackingBufferedOutputStream(ByteArrayOutputStream capture) {
      super(capture);
      this.capture = capture;
    }

    @Override
    public void close() {
      closeCalled = true;
    }

    private String writtenText() {
      return capture.toString(StandardCharsets.UTF_8);
    }
  }

  private static final class TrackingServerSocket extends ServerSocket {
    private boolean closeCalled;

    private TrackingServerSocket() throws Exception {
      super();
    }

    @Override
    public void close() {
      closeCalled = true;
    }
  }

  private static final class RecordingGmaRetirementLeelaz extends Leelaz {
    private int retirementCount;

    private RecordingGmaRetirementLeelaz() throws Exception {
      super("");
    }

    @Override
    void retireReadBoardGmaSession() {
      retirementCount++;
    }
  }


  private static final class TrackingSocket extends Socket {
    private boolean closeCalled;

    @Override
    public synchronized void close() {
      closeCalled = true;
    }
  }

  private static final class TrackingLizzieFrame extends LizzieFrame {
    private CountDownLatch shutdownStartedSignal;
    private CountDownLatch restartSignal;
    private ReadBoard existingReadBoard;
    private ReadBoard createdReadBoard;
    private long shutdownWaitBlockMs;
    private volatile boolean shutdownCompleted;
    private volatile boolean startedBeforeShutdownCompleted;

    private void initialize(ReadBoard existingReadBoard, long shutdownWaitBlockMs) {
      this.existingReadBoard = existingReadBoard;
      this.shutdownWaitBlockMs = shutdownWaitBlockMs;
      this.shutdownStartedSignal = new CountDownLatch(1);
      this.restartSignal = new CountDownLatch(1);
      try {
        Field field = LizzieFrame.class.getDeclaredField("readBoardRestartLock");
        field.setAccessible(true);
        field.set(this, new Object());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private boolean awaitShutdownStarted(long timeout, TimeUnit unit) throws InterruptedException {
      return shutdownStartedSignal.await(timeout, unit);
    }

    private boolean awaitRestart(long timeout, TimeUnit unit) throws InterruptedException {
      return restartSignal.await(timeout, unit);
    }

    @Override
    protected void shutdownReadBoard(ReadBoard targetReadBoard) {
      if (targetReadBoard != existingReadBoard) {
        throw new AssertionError("unexpected readboard shutdown target");
      }
      shutdownStartedSignal.countDown();
      try {
        Thread.sleep(shutdownWaitBlockMs);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError(ex);
      }
      shutdownCompleted = true;
      readBoard = null;
    }

    @Override
    protected boolean isNativeBoardSyncSupported() {
      return true;
    }

    @Override
    protected boolean isNativeReadBoardAvailable() {
      return true;
    }

    @Override
    protected ReadBoard createNativeReadBoard() throws Exception {
      startedBeforeShutdownCompleted = !shutdownCompleted;
      createdReadBoard = allocate(ReadBoard.class);
      restartSignal.countDown();
      return createdReadBoard;
    }
  }
}
