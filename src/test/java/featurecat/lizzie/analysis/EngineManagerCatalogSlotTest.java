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
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A catalog refresh can reorder [A, B, C] to [A, C, B] or drop an entry while B's remote session is
 * being recovered. The recovery must restart B at the slot B holds when it starts, under B's name.
 * A refresh that drops B must cancel the recovery instead of restarting a removed entry, and rebuild
 * only after the recovery has released B's lifecycle lease.
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
  }

  @AfterEach
  void restoreSelection() {
    for (SlotEngine engine : List.of(engineA, engineB, engineC)) engine.releaseAll();
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
    CatalogManager manager = selectDisconnectedRemoteB(List.of(a, b, c));
    engineB.secondDeadCheck.arm();
    manager.restartUnresponsiveRemoteEngine(engineB);
    engineB.secondDeadCheck.awaitEntered();

    replaceSavedCatalog(a, c, b);
    SwingUtilities.invokeAndWait(() -> refresh(manager));

    assertSame(engineB, manager.engineList.get(2));
    assertEquals(2, EngineManager.currentEngineNo, "the selection follows B to its new slot");
    assertEquals(2, engineB.currentEngineN(), "B's own slot follows the reorder");
    engineB.secondDeadCheck.release();

    assertTrue(engineB.restarted.await(5, TimeUnit.SECONDS), "B's recovery must not be dropped");
    assertEquals(List.of(2), engineB.startedSlots, "recovery starts B at the slot it holds now");
    assertEquals("B", engineB.oriEnginename, "a stale slot would rename B after C");
    assertEquals(List.of(), engineA.startedSlots);
    assertEquals(List.of(), engineC.startedSlots);
  }

  @Test
  void removingTheRecoveringEntryCancelsItsRecoveryBeforeTheRebuild() throws Exception {
    CatalogManager manager = selectDisconnectedRemoteB(List.of(a, b, c));
    engineB.secondDeadCheck.arm();
    manager.restartUnresponsiveRemoteEngine(engineB);
    engineB.secondDeadCheck.awaitEntered();

    replaceSavedCatalog(a, c);
    SwingUtilities.invokeAndWait(() -> refresh(manager));
    // The refresh claimed the recovery; the recovery itself closes the attempt holding B's lease.
    assertEquals(0, manager.rebuilds, "the rebuild waits for the recovery to let go of B");
    engineB.secondDeadCheck.release();

    awaitCondition(() -> manager.rebuilds == 1);
    assertTrue(
        manager.foregroundLeaseAvailableAtRebuild,
        "the cancelled recovery must not hold the removed primary's lease during the rebuild");
    assertEquals(List.of(), engineB.startedSlots, "a removed entry is never restarted");
    awaitCondition(() -> engineB.canCheckAlive);
    assertReservationsReleased(engineB);
  }

  @Test
  void removingTheEntryWhileItsRecoveryTakesTheLeaseRebuildsOnlyAfterTheRelease()
      throws Exception {
    CatalogManager manager = selectDisconnectedRemoteB(List.of(a, b, c));
    engineB.attemptTaken.arm();
    Thread recovery = startThread(() -> manager.restartUnresponsiveRemoteEngine(engineB));
    engineB.attemptTaken.awaitEntered();

    replaceSavedCatalog(a, c);
    SwingUtilities.invokeAndWait(() -> refresh(manager));
    assertEquals(0, manager.rebuilds, "the recovery still holds B's lease, so the rebuild waits");
    engineB.attemptTaken.release();
    recovery.join(TimeUnit.SECONDS.toMillis(5));

    awaitCondition(() -> manager.rebuilds == 1);
    assertTrue(
        manager.foregroundLeaseAvailableAtRebuild,
        "the rebuild runs once the abandoned recovery has released B's lease");
    assertEquals(List.of(), engineB.startedSlots, "a removed entry is never restarted");
    assertReservationsReleased(engineB);
  }

  @Test
  void removingTheEntryWhileItsRecoveryClosesItsAttemptRebuildsOnlyAfterTheRelease()
      throws Exception {
    CatalogManager manager = selectDisconnectedRemoteB(List.of(a, b, c));
    engineB.secondDeadCheck.arm();
    manager.restartUnresponsiveRemoteEngine(engineB);
    engineB.secondDeadCheck.awaitEntered();
    // B answers again, so its recovery gives up and is held while it releases B's lease.
    engineB.processDead = false;
    engineB.abandoning.arm();
    engineB.secondDeadCheck.release();
    engineB.abandoning.awaitEntered();

    replaceSavedCatalog(a, c);
    SwingUtilities.invokeAndWait(() -> refresh(manager));
    assertEquals(0, manager.rebuilds, "the recovery is still releasing B's lease; rebuild waits");
    engineB.abandoning.release();

    awaitCondition(() -> manager.rebuilds == 1);
    assertTrue(
        manager.foregroundLeaseAvailableAtRebuild,
        "the rebuild runs once the recovery has finished releasing B's lease");
    assertEquals(List.of(), engineB.startedSlots, "a removed entry is never restarted");
    assertReservationsReleased(engineB);
  }

  @Test
  void removingTheEntryWhileItRestartsRebuildsOnlyAfterTheRestartSettles() throws Exception {
    CatalogManager manager = selectDisconnectedRemoteB(List.of(a, b, c));
    engineB.start.arm();
    manager.restartUnresponsiveRemoteEngine(engineB);
    engineB.start.awaitEntered();

    replaceSavedCatalog(a, c);
    SwingUtilities.invokeAndWait(() -> refresh(manager));
    assertEquals(0, manager.rebuilds, "the restart already owns B's lease, so the rebuild waits");
    engineB.start.release();

    awaitCondition(() -> manager.rebuilds == 1);
    assertTrue(
        manager.foregroundLeaseAvailableAtRebuild,
        "the rebuild runs after the restart has settled and released B's lease");
    assertReservationsReleased(engineB);
  }

  @Test
  void removingAnEarlierEntryWhileTheRecoveryStartsKeepsTheRestartedEntrysSlotAndName()
      throws Exception {
    // [A, C, B] with B recovering at slot 2; removing A leaves [C, B] before B's start reads it.
    CatalogManager manager = selectDisconnectedRemoteB(List.of(a, c, b));
    engineB.start.arm();
    manager.restartUnresponsiveRemoteEngine(engineB);
    engineB.start.awaitEntered();

    replaceSavedCatalog(c, b);
    SwingUtilities.invokeAndWait(() -> refresh(manager));
    assertEquals(1, EngineManager.currentEngineNo);
    engineB.start.release();

    assertTrue(
        engineB.restarted.await(5, TimeUnit.SECONDS),
        "slot 2 is past the end of [C, B]; the start must still name B");
    assertEquals(List.of(2), engineB.startedSlots, "the restart started from its claimed slot");
    awaitCondition(() -> engineB.currentEngineN() == 1);
    assertEquals("B", engineB.oriEnginename);
    assertEquals(1, EngineManager.currentEngineNo);
    assertSame(engineB, manager.engineList.get(1));
  }

  private CatalogManager selectDisconnectedRemoteB(List<EngineData> catalog) throws Exception {
    savedCatalog.addAll(catalog);
    List<Leelaz> engines = new ArrayList<>();
    for (EngineData data : catalog) {
      engines.add(data == a ? engineA : data == b ? engineB : engineC);
    }
    int slot = engines.indexOf(engineB);
    CatalogManager manager = new CatalogManager(engines, savedCatalog);
    Lizzie.setEngineManager(manager);
    engineB.useRemoteCompute = true;
    engineB.started = true;
    engineB.processDead = true;
    engineB.getEngineName(slot);
    setStartedSlot(engineB, slot);
    Lizzie.leelaz = engineB;
    Lizzie.leelaz2 = null;
    EngineManager.currentEngineNo = slot;
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

  private static void assertReservationsReleased(Leelaz engine) {
    Leelaz.EngineModeReservation reservation = engine.beginEngineModeReservation();
    assertNotNull(reservation, "the recovery released every reservation");
    reservation.close();
  }

  private static Thread startThread(Runnable body) {
    Thread thread = new Thread(body, "catalog-slot-test-recovery");
    thread.setDaemon(true);
    thread.start();
    return thread;
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
    private volatile int rebuilds;
    private volatile boolean foregroundLeaseAvailableAtRebuild;

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
      Leelaz.ExclusiveGtpLifecycleReservation lease =
          Lizzie.leelaz == null ? null : Lizzie.leelaz.beginExclusiveGtpLifecycleReservation();
      foregroundLeaseAvailableAtRebuild = lease != null;
      if (lease != null) lease.close();
      rebuilds++;
    }
  }

  /** Holds one thread at a test boundary once; it never blocks longer than five seconds. */
  private static final class Gate {
    private final AtomicBoolean armed = new AtomicBoolean();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    void arm() {
      armed.set(true);
    }

    void pass() {
      if (!armed.compareAndSet(true, false)) return;
      entered.countDown();
      try {
        released.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    void awaitEntered() throws InterruptedException {
      assertTrue(entered.await(5, TimeUnit.SECONDS), "the thread did not reach the gate");
    }

    void release() {
      released.countDown();
    }
  }

  /** A remote endpoint whose recovery can be held at its lease, probe, release or start. */
  private static final class SlotEngine extends Leelaz {
    private final List<EngineData> savedCatalog;
    private final List<Integer> startedSlots = new CopyOnWriteArrayList<>();
    private final CountDownLatch restarted = new CountDownLatch(1);
    private final Gate attemptTaken = new Gate();
    private final Gate secondDeadCheck = new Gate();
    private final Gate start = new Gate();
    private final Gate abandoning = new Gate();
    private volatile boolean processDead;
    private int deadChecks;

    private SlotEngine(String command, List<EngineData> savedCatalog) throws IOException {
      super(command);
      this.savedCatalog = savedCatalog;
    }

    void releaseAll() {
      attemptTaken.release();
      secondDeadCheck.release();
      start.release();
      abandoning.release();
    }

    /** Production resolves the saved entry this way, then reads its name. */
    @Override
    public String getEngineName(int index) {
      String name = index < 0 ? "" : savedCatalogEntry(savedCatalog, index, savedEntryId).name;
      oriEnginename = name;
      currentEnginename = name;
      return name;
    }

    @Override
    public AutomaticRestartAttempt beginAutomaticEngineRestartAttempt() {
      AutomaticRestartAttempt attempt = super.beginAutomaticEngineRestartAttempt();
      attemptTaken.pass();
      return attempt;
    }

    /** An abandoned attempt ends its board synchronization while it releases the lease. */
    @Override
    public void endInitialBoardSynchronization() {
      abandoning.pass();
      super.endInitialBoardSynchronization();
    }

    @Override
    public boolean isProcessDead() {
      int check;
      synchronized (this) {
        check = ++deadChecks;
      }
      if (check == 2) secondDeadCheck.pass();
      return processDead;
    }

    @Override
    public void normalQuit() {
      // The controlled remote transport is already gone.
    }

    /** Mirrors production's order: the started slot is recorded, then the entry is named. */
    @Override
    public void startEngine(int index) {
      start.pass();
      startedSlots.add(index);
      try {
        setStartedSlot(this, index);
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
      getEngineName(index);
      // Fail readiness at once so the restart settles without real streams.
      started = false;
      restarted.countDown();
    }
  }
}
