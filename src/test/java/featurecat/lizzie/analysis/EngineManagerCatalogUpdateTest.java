package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The settings screen, the automatic setup and a recovery each refresh the catalog, and closing the
 * settings screen rebuilds it, from different threads. Each update reads the saved catalog, changes
 * the shared engine objects and publishes. These cases interleave two updates and require the
 * result of running them one after the other: no entry twice, no older read applied or published
 * after a newer one, and an update that returns only after it published what it read.
 */
class EngineManagerCatalogUpdateTest {
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
  private final List<TestEngine> engines = new ArrayList<>();
  private TestEngine engineA;
  private TestEngine engineB;
  private TestEngine engineC;
  private EngineData a;
  private EngineData b;
  private EngineData c;

  @BeforeEach
  void noSelection() throws Exception {
    Lizzie.config = null;
    Lizzie.board = null;
    Menu.engineMenu = null;
    Lizzie.leelaz = null;
    Lizzie.leelaz2 = null;
    EngineManager.currentEngineNo = -1;
    EngineManager.currentEngineNo2 = -1;
    EngineManager.isEmpty = true;
    engineA = engine("A");
    engineB = engine("B");
    engineC = engine("C");
    a = entry(engineA);
    b = entry(engineB);
    c = entry(engineC);
  }

  @AfterEach
  void restoreSelection() {
    for (TestEngine engine : engines) engine.naming.release();
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
  void aRefreshDuringARebuildRunsAfterItInsteadOfAddingToItsHalfBuiltList() throws Exception {
    CatalogManager manager = manager(List.of(engineA, engineB), a, b);
    manager.create.arm("rebuild");
    Background rebuild = background("rebuild", manager::updateEngines);
    // The rebuild has published an empty list and is filling it.
    manager.create.awaitEntered();
    Background refresh = background("refresh", manager::refreshEngineCatalog);
    awaitWaitingOrDone(refresh);
    manager.create.release();
    rebuild.finish();
    refresh.finish();

    assertEquals(List.of("A", "B"), ids(manager.engineList), "each saved entry is listed once");
  }

  @Test
  void aRefreshOffTheEventThreadReturnsOnlyAfterPublishingTheCatalogItRead() throws Exception {
    // The automatic setup adds B to [A], refreshes, then switches to B's slot.
    CatalogManager manager = manager(List.of(engineA), a, b);
    engineA.naming.arm("setup");
    Background setup = background("setup", manager::refreshEngineCatalog);
    engineA.naming.awaitEntered();
    // Another refresh is asked for while the setup's refresh is matching what it read.
    manager.load.arm("other");
    Background other = background("other", manager::refreshEngineCatalog);
    awaitWaitingOrHeld(other, manager.load);
    engineA.naming.release();
    setup.finish();

    assertEquals(List.of("A", "B"), ids(manager.engineList), "the setup finds B where it saved it");
    manager.load.release();
    other.finish();
    assertEquals(List.of("A", "B"), ids(manager.engineList));
  }

  @Test
  void anOlderReadNeverOverwritesTheSettingsANewerReadApplied() throws Exception {
    CatalogManager manager = manager(List.of(engineA, engineB), withInitialCommand(a, "old"), b);
    manager.load.arm("older");
    Background older = background("older", manager::refreshEngineCatalog);
    // The older refresh has read the catalog and is held before it applies it.
    manager.load.awaitEntered();
    replaceSavedCatalog(withInitialCommand(a, "new"), b);
    Background newer = background("newer", manager::refreshEngineCatalog);
    awaitWaitingOrDone(newer);
    manager.load.release();
    older.finish();
    newer.finish();

    assertEquals("new", engineA.initialCommand, "A starts next with the settings saved last");
    assertSame(engineA, manager.engineList.get(0));
  }

  @Test
  void aRebuildForARemovedPrimaryEndsBeforeANewerCatalogIsRead() throws Exception {
    CatalogManager manager = manager(List.of(engineA, engineB), b);
    Lizzie.leelaz = engineA;
    EngineManager.currentEngineNo = 0;
    EngineManager.isEmpty = false;
    List<Leelaz> published = manager.engineList;
    manager.standInRebuild = true;
    manager.rebuild.arm("older");
    // The older refresh read a catalog without the primary and must rebuild.
    Background older = background("older", manager::refreshEngineCatalog);
    manager.rebuild.awaitEntered();
    replaceSavedCatalog(a, b);
    Background newer = background("newer", manager::refreshEngineCatalog);
    awaitWaitingOrDone(newer);
    manager.rebuild.release();
    older.finish();
    newer.finish();

    assertSame(
        published,
        manager.listAtStandInRebuild,
        "no newer catalog is published while the rebuild that read A removed is still to run");
    assertEquals(List.of("A", "B"), ids(manager.engineList), "the newer catalog is published next");
  }

  @Test
  void selectionControlsDrawnLateShowTheCatalogPublishedLast() throws Exception {
    CatalogManager manager = manager(List.of(engineA, engineB, engineC), a, b, c);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            // A refresh off the event thread publishes and queues its controls behind this event.
            replaceSavedCatalog(a, c, b);
            background("older", manager::refreshEngineCatalog).finish();
            // The settings screen refreshes next, on the event thread, and draws at once.
            replaceSavedCatalog(c, a, b);
            manager.refreshEngineCatalog();
          } catch (Throwable thrown) {
            failure.set(thrown);
          }
        });
    SwingUtilities.invokeAndWait(() -> {});
    if (failure.get() != null) throw new AssertionError(failure.get());

    assertEquals(List.of("C", "A", "B"), ids(manager.engineList));
    List<List<String>> drawn = manager.drawnControls;
    assertEquals(
        List.of("C", "A", "B"),
        drawn.get(drawn.size() - 1),
        "a game started from these controls joins the entries they show");
  }

  private CatalogManager manager(List<Leelaz> running, EngineData... saved) {
    replaceSavedCatalog(saved);
    CatalogManager manager = new CatalogManager(running);
    Lizzie.setEngineManager(manager);
    return manager;
  }

  private void replaceSavedCatalog(EngineData... entries) {
    savedCatalog.clear();
    savedCatalog.addAll(List.of(entries));
  }

  private TestEngine engine(String id) throws IOException {
    TestEngine engine = new TestEngine("engine-" + id.toLowerCase(), savedCatalog);
    engine.savedEntryId = id;
    engines.add(engine);
    return engine;
  }

  /** A saved entry that matches {@code engine}'s process, as the catalog saves it. */
  private static EngineData entry(Leelaz engine) {
    EngineData data = new EngineData();
    data.id = data.name = engine.savedEntryId;
    data.commands = engine.oriEngineCommand;
    data.width = engine.oriWidth;
    data.height = engine.oriHeight;
    data.useJavaSSH = engine.useJavaSSH;
    data.ip = engine.ip;
    data.port = engine.port;
    data.userName = engine.userName;
    return data;
  }

  /** The same entry saved again with other start-up commands; it still names the same process. */
  private static EngineData withInitialCommand(EngineData saved, String initialCommand) {
    EngineData data = new EngineData();
    data.id = saved.id;
    data.name = saved.name;
    data.commands = saved.commands;
    data.width = saved.width;
    data.height = saved.height;
    data.useJavaSSH = saved.useJavaSSH;
    data.ip = saved.ip;
    data.port = saved.port;
    data.userName = saved.userName;
    data.initialCommand = initialCommand;
    return data;
  }

  private static List<String> ids(List<Leelaz> engines) {
    List<String> ids = new ArrayList<>();
    for (Leelaz engine : engines) ids.add(engine.savedEntryId);
    return ids;
  }

  /** The second update either waits for the catalog or, if nothing orders them, runs to the end. */
  private static void awaitWaitingOrDone(Background update) throws InterruptedException {
    awaitCondition(() -> !update.thread.isAlive() || waiting(update));
  }

  /** As {@link #awaitWaitingOrDone}, where an unordered update stops at {@code gate}. */
  private static void awaitWaitingOrHeld(Background update, Gate gate)
      throws InterruptedException {
    awaitCondition(() -> gate.entered() || waiting(update));
  }

  /** Parked on the catalog lock; a test gate waits with a timeout instead. */
  private static boolean waiting(Background update) {
    return update.thread.getState() == Thread.State.WAITING;
  }

  private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), "the update thread did not reach the expected point");
  }

  private interface CatalogUpdate {
    void run() throws JSONException, IOException;
  }

  private static Background background(String name, CatalogUpdate update) {
    return new Background(name, update);
  }

  /** An update on its own thread; {@link #finish} fails the test if it did not return normally. */
  private static final class Background {
    private final Thread thread;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private Background(String name, CatalogUpdate update) {
      thread =
          new Thread(
              () -> {
                try {
                  update.run();
                } catch (Throwable thrown) {
                  failure.set(thrown);
                }
              },
              name);
      thread.setDaemon(true);
      thread.start();
    }

    void finish() throws InterruptedException {
      thread.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(thread.isAlive(), thread.getName() + " did not return");
      if (failure.get() != null) {
        throw new AssertionError(thread.getName() + " failed", failure.get());
      }
    }
  }

  private final class CatalogManager extends EngineManager {
    /** Holds a catalog read after it took its snapshot. */
    final Gate load = new Gate();
    /** Holds the rebuild once it has published its empty list and creates the first entry. */
    final Gate create = new Gate();
    /** Holds the stand-in for the rebuild a removed primary needs. */
    final Gate rebuild = new Gate();

    volatile boolean standInRebuild;
    volatile List<Leelaz> listAtStandInRebuild;
    final List<List<String>> drawnControls = new CopyOnWriteArrayList<>();

    private CatalogManager(List<Leelaz> running) {
      super(new ArrayList<>(running));
    }

    @Override
    protected ArrayList<EngineData> loadEngineCatalogSnapshot() {
      ArrayList<EngineData> snapshot = new ArrayList<>(savedCatalog);
      load.pass();
      return snapshot;
    }

    @Override
    protected Leelaz createUnstartedEngine(EngineData data) throws IOException {
      create.pass();
      TestEngine engine = new TestEngine(data.commands, savedCatalog);
      engine.savedEntryId = data.id;
      return engine;
    }

    /** With the stand-in on, records the list a removed primary's rebuild would replace. */
    @Override
    public void updateEngines() throws JSONException, IOException {
      if (!standInRebuild) {
        super.updateEngines();
        return;
      }
      rebuild.pass();
      listAtStandInRebuild = engineList;
    }

    @Override
    protected void refreshEngineSelectionControls(ArrayList<EngineData> engineData) {
      List<String> shown = new ArrayList<>();
      for (EngineData data : engineData) shown.add(data.id);
      drawnControls.add(shown);
    }
  }

  /** Holds one named thread at a test boundary once; it never blocks longer than five seconds. */
  private static final class Gate {
    private final AtomicReference<String> armedFor = new AtomicReference<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    void arm(String threadName) {
      armedFor.set(threadName);
    }

    void pass() {
      String name = Thread.currentThread().getName();
      if (!armedFor.compareAndSet(name, null)) return;
      entered.countDown();
      try {
        released.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    boolean entered() {
      return entered.getCount() == 0;
    }

    void awaitEntered() throws InterruptedException {
      assertTrue(entered.await(5, TimeUnit.SECONDS), "the thread did not reach the gate");
    }

    void release() {
      released.countDown();
    }
  }

  /** An entry that is never started; it is named from the saved catalog as production does. */
  private static final class TestEngine extends Leelaz {
    private final List<EngineData> savedCatalog;
    final Gate naming = new Gate();

    private TestEngine(String command, List<EngineData> savedCatalog) throws IOException {
      super(command);
      this.savedCatalog = savedCatalog;
    }

    @Override
    public String getEngineName(int index) {
      naming.pass();
      String name = index < 0 ? "" : savedCatalogEntry(savedCatalog, index, savedEntryId).name;
      oriEnginename = name;
      currentEnginename = name;
      return name;
    }
  }
}
