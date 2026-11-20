package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A catalog refresh can reorder [A, B, C] to [A, C, B] or drop B while B's remote session is
 * being recovered. The recovery must restart B at the slot B holds when it starts, under B's name,
 * and a refresh that drops B must cancel it instead of restarting a removed entry.
 */
class EngineManagerCatalogSlotTest {
  private final Leelaz previousPrimary = Lizzie.leelaz;
  private final Leelaz previousSecondary = Lizzie.leelaz2;
  private final EngineManager previousManager = Lizzie.engineManager;
  private final Config previousConfig = Lizzie.config;
  private final Board previousBoard = Lizzie.board;
  private final featurecat.lizzie.gui.JFontMenu previousEngineMenu = Menu.engineMenu;
  private final int previousPrimaryIndex = EngineManager.currentEngineNo;
  private final int previousSecondaryIndex = EngineManager.currentEngineNo2;
  private final boolean previousEmpty = EngineManager.isEmpty;

  private final List<EngineData> savedCatalog = new CopyOnWriteArrayList<>();
  private EngineData a;
  private EngineData b;
  private EngineData c;
  private SlotEngine engineA;
  private SlotEngine engineB;
  private SlotEngine engineC;

  @BeforeEach
  void installCatalog() throws Exception {
    Lizzie.config = null;
    Lizzie.board = null;
    Menu.engineMenu = null;
    engineA = new SlotEngine("remote-a", savedCatalog);
    engineB = new SlotEngine("remote-b", savedCatalog);
    engineC = new SlotEngine("remote-c", savedCatalog);
    a = entry(engineA, "A");
    b = entry(engineB, "B");
    c = entry(engineC, "C");
    savedCatalog.addAll(List.of(a, b, c));
  }

  @AfterEach
  void restoreSelection() {
    engineB.releaseSecondDeadCheck.countDown();
    Lizzie.setEngineManager(previousManager);
    Lizzie.leelaz = previousPrimary;
    Lizzie.leelaz2 = previousSecondary;
    Lizzie.config = previousConfig;
    Lizzie.board = previousBoard;
    Menu.engineMenu = previousEngineMenu;
    EngineManager.currentEngineNo = previousPrimaryIndex;
    EngineManager.currentEngineNo2 = previousSecondaryIndex;
    EngineManager.isEmpty = previousEmpty;
  }

  @Test
  void reorderedRemoteRecoveryRestartsTheEntryAtItsCurrentSlot() throws Exception {
    CatalogManager manager = selectDisconnectedRemoteB();
    manager.restartUnresponsiveRemoteEngine(engineB);
    assertTrue(engineB.secondDeadCheckEntered.await(5, TimeUnit.SECONDS));

    replaceSavedCatalog(a, c, b);
    SwingUtilities.invokeAndWait(() -> refresh(manager));

    assertSame(engineB, manager.engineList.get(2));
    assertEquals(2, EngineManager.currentEngineNo, "the selection follows B to its new slot");
    assertEquals(2, engineB.currentEngineN(), "B's own slot follows the reorder");
    engineB.releaseSecondDeadCheck.countDown();

    assertTrue(engineB.restarted.await(5, TimeUnit.SECONDS), "B's recovery must not be dropped");
    assertEquals(List.of(2), engineB.startedSlots, "recovery starts B at the slot it holds now");
    assertEquals("B", engineB.oriEnginename, "a stale slot would rename B after C");
    assertEquals(List.of(), engineA.startedSlots);
    assertEquals(List.of(), engineC.startedSlots);
  }

  @Test
  void removingTheRecoveringEntryCancelsItsRecoveryBeforeTheRebuild() throws Exception {
    CatalogManager manager = selectDisconnectedRemoteB();
    manager.restartUnresponsiveRemoteEngine(engineB);
    assertTrue(engineB.secondDeadCheckEntered.await(5, TimeUnit.SECONDS));

    replaceSavedCatalog(a, c);
    SwingUtilities.invokeAndWait(() -> refresh(manager));

    assertEquals(1, manager.rebuilds, "removing the primary rebuilds the catalog");
    assertTrue(
        manager.foregroundLeaseAvailableAtRebuild,
        "the cancelled recovery must not hold the removed primary's lease during the rebuild");
    engineB.releaseSecondDeadCheck.countDown();

    awaitCondition(() -> engineB.canCheckAlive);
    assertEquals(List.of(), engineB.startedSlots, "a removed entry is never restarted");
    Leelaz.EngineModeReservation reservation = engineB.beginEngineModeReservation();
    assertNotNull(reservation, "the cancelled attempt released every reservation");
    reservation.close();
  }

  private CatalogManager selectDisconnectedRemoteB() throws Exception {
    CatalogManager manager = new CatalogManager(List.of(engineA, engineB, engineC), savedCatalog);
    Lizzie.setEngineManager(manager);
    engineB.useRemoteCompute = true;
    engineB.started = true;
    engineB.processDead = true;
    engineB.blockSecondDeadCheck = true;
    engineB.getEngineName(1);
    setStartedSlot(engineB, 1);
    Lizzie.leelaz = engineB;
    Lizzie.leelaz2 = null;
    EngineManager.currentEngineNo = 1;
    EngineManager.currentEngineNo2 = -1;
    EngineManager.isEmpty = false;
    return manager;
  }

  private void replaceSavedCatalog(EngineData... entries) {
    savedCatalog.clear();
    savedCatalog.addAll(List.of(entries));
  }

  private static void refresh(EngineManager manager) {
    try {
      manager.refreshEngineCatalog();
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private static EngineData entry(Leelaz engine, String name) {
    EngineData data = new EngineData();
    data.id = engine.engineCommand;
    data.name = name;
    data.commands = engine.oriEngineCommand;
    data.width = engine.oriWidth;
    data.height = engine.oriHeight;
    data.ip = engine.ip;
    data.port = engine.port;
    data.userName = engine.userName;
    engine.savedEntryId = data.id;
    return data;
  }

  /** {@link Leelaz#startEngine(int)} is the only writer of the started slot in production. */
  private static void setStartedSlot(Leelaz engine, int slot) throws Exception {
    Field field = Leelaz.class.getDeclaredField("currentEngineN");
    field.setAccessible(true);
    field.setInt(engine, slot);
  }

  private static void awaitCondition(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), "the recovery thread did not settle");
  }

  private static final class CatalogManager extends EngineManager {
    private final List<EngineData> savedCatalog;
    private int rebuilds;
    private boolean foregroundLeaseAvailableAtRebuild;

    private CatalogManager(List<Leelaz> engines, List<EngineData> savedCatalog) {
      super(new ArrayList<>(engines));
      this.savedCatalog = savedCatalog;
    }

    @Override
    protected ArrayList<EngineData> loadEngineCatalogSnapshot() {
      return new ArrayList<>(savedCatalog);
    }

    /** Stands in for the full rebuild; it first needs the foreground lease, as killAllEngines. */
    @Override
    public void updateEngines() {
      rebuilds++;
      Leelaz.ExclusiveGtpLifecycleReservation lease =
          Lizzie.leelaz == null ? null : Lizzie.leelaz.beginExclusiveGtpLifecycleReservation();
      foregroundLeaseAvailableAtRebuild = lease != null;
      if (lease != null) lease.close();
    }
  }

  /** A remote endpoint whose second liveness probe can be held inside the recovery thread. */
  private static final class SlotEngine extends Leelaz {
    private final List<EngineData> savedCatalog;
    private final List<Integer> startedSlots = new CopyOnWriteArrayList<>();
    private final CountDownLatch secondDeadCheckEntered = new CountDownLatch(1);
    private final CountDownLatch releaseSecondDeadCheck = new CountDownLatch(1);
    private final CountDownLatch restarted = new CountDownLatch(1);
    private volatile boolean processDead;
    private volatile boolean blockSecondDeadCheck;
    private int deadChecks;

    private SlotEngine(String command, List<EngineData> savedCatalog) throws IOException {
      super(command);
      this.savedCatalog = savedCatalog;
    }

    /** Production reads the saved catalog at this index; so does this lookup. */
    @Override
    public String getEngineName(int index) {
      String name = index < 0 || index >= savedCatalog.size() ? "" : savedCatalog.get(index).name;
      oriEnginename = name;
      currentEnginename = name;
      return name;
    }

    @Override
    public boolean isProcessDead() {
      synchronized (this) {
        deadChecks++;
      }
      if (blockSecondDeadCheck && deadChecks == 2) {
        secondDeadCheckEntered.countDown();
        try {
          releaseSecondDeadCheck.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      return processDead;
    }

    @Override
    public void normalQuit() {
      // The controlled remote transport is already gone.
    }

    @Override
    public void startEngine(int index) {
      startedSlots.add(index);
      getEngineName(index);
      // Fail readiness at once so the restart settles without real streams.
      started = false;
      restarted.countDown();
    }
  }
}
