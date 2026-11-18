package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EngineManagerStartupShutdownTest {
  @TempDir Path directory;

  private enum StopAction {
    ALL,
    EXIT,
    OTHER,
    OTHER_EXCEPT_PARTICIPANTS,
    PRIMARY,
    SECONDARY
  }

  @ParameterizedTest
  @EnumSource(StopAction.class)
  void managerStopsConnectingEngine(StopAction action) throws Exception {
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    int previousPrimaryIndex = EngineManager.currentEngineNo;
    int previousSecondaryIndex = EngineManager.currentEngineNo2;
    boolean previousEmpty = EngineManager.isEmpty;
    ConnectingTransport transport = new ConnectingTransport();
    Leelaz connecting =
        new Leelaz(RemoteComputeConfig.COMMAND_ZHIZI) {
          @Override
          protected EngineTransport createRemoteTransport() {
            return transport;
          }
        };
    RecordingEngine idle = new RecordingEngine();
    RecordingEngine running = new RecordingEngine();
    running.started = true;
    EngineManager manager = new EngineManager(List.of(idle, connecting, running));
    FutureTask<Void> startup =
        new FutureTask<>(
            () -> {
              connecting.startEngine(-1);
              return null;
            });
    Thread worker = new Thread(startup, "connecting-engine-shutdown");
    worker.setDaemon(true);

    try {
      Lizzie.config = ConfigTestHelper.createForTests(directory);
      Lizzie.frame = null;
      boolean primaryConnecting = action == StopAction.PRIMARY || action == StopAction.EXIT;
      Lizzie.setPrimaryEngine(primaryConnecting ? connecting : running);
      Lizzie.leelaz2 = action == StopAction.SECONDARY ? connecting : null;
      EngineManager.currentEngineNo = primaryConnecting ? -1 : 2;
      // A provisional secondary selection can still carry a previous catalog index.
      EngineManager.currentEngineNo2 = action == StopAction.SECONDARY ? 0 : -1;
      EngineManager.isEmpty = primaryConnecting;
      worker.start();
      assertTrue(transport.startEntered.await(5, TimeUnit.SECONDS));
      assertFalse(connecting.isStarted());

      switch (action) {
        case ALL -> assertTrue(manager.killAllEngines());
        case EXIT -> manager.forceKillAllEngines();
        case OTHER -> manager.killOtherEngines();
        case OTHER_EXCEPT_PARTICIPANTS -> manager.killOtherEngines(0, 2);
        case PRIMARY -> manager.killThisEngines();
        case SECONDARY -> manager.killThisEngines2();
      }

      assertEquals(1, transport.closeCalls.get(), "manager must retire the pending connection");
      assertEquals(0, idle.quitCalls, "idle catalog entries are skipped");
      int expectedRunningStops = action == StopAction.ALL || action == StopAction.EXIT ? 1 : 0;
      assertEquals(expectedRunningStops, running.quitCalls);

      transport.finishStart.countDown();
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> startup.get(5, TimeUnit.SECONDS));
      assertTrue(failure.getCause() instanceof IllegalStateException);
      assertFalse(connecting.isStarted());
      assertEquals(1, transport.closeCalls.get(), "late startup must not close twice");
    } finally {
      try {
        if (worker.isAlive()) connecting.shutdown();
      } finally {
        transport.finishStart.countDown();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        Lizzie.config = previousConfig;
        Lizzie.frame = previousFrame;
        Lizzie.setPrimaryEngine(previousPrimary);
        Lizzie.leelaz2 = previousSecondary;
        EngineManager.currentEngineNo = previousPrimaryIndex;
        EngineManager.currentEngineNo2 = previousSecondaryIndex;
        EngineManager.isEmpty = previousEmpty;
      }
    }
  }

  private static final class RecordingEngine extends Leelaz {
    private int quitCalls;

    private RecordingEngine() throws IOException {
      super("");
    }

    @Override
    public void forceQuit() {
      quitCalls++;
    }

    @Override
    public void normalQuit() {
      quitCalls++;
    }
  }

  private static final class ConnectingTransport implements EngineTransport {
    private final CountDownLatch startEntered = new CountDownLatch(1);
    private final CountDownLatch finishStart = new CountDownLatch(1);
    private final AtomicInteger closeCalls = new AtomicInteger();

    @Override
    public void start() throws IOException {
      startEntered.countDown();
      try {
        if (!finishStart.await(5, TimeUnit.SECONDS)) {
          throw new IOException("timed out waiting to finish the controlled connection");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException(interrupted);
      }
    }

    @Override
    public InputStream stdout() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream stderr() {
      return InputStream.nullInputStream();
    }

    @Override
    public OutputStream stdin() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public boolean isOpen() {
      return closeCalls.get() == 0;
    }

    @Override
    public String description() {
      return "controlled startup transport";
    }

    @Override
    public void abort() {
      close();
    }

    @Override
    public void close() {
      closeCalls.incrementAndGet();
    }
  }
}
