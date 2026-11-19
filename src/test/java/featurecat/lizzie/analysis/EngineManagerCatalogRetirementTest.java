package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EngineManagerCatalogRetirementTest {
  private final Leelaz previousPrimary = Lizzie.leelaz;
  private final EngineManager previousManager = Lizzie.engineManager;
  private final Leelaz previousSecondary = Lizzie.leelaz2;
  private final int previousPrimaryIndex = EngineManager.currentEngineNo;
  private final int previousSecondaryIndex = EngineManager.currentEngineNo2;
  private final boolean previousEmpty = EngineManager.isEmpty;

  @AfterEach
  void restoreSelection() {
    Lizzie.setEngineManager(previousManager);
    Lizzie.setPrimaryEngine(previousPrimary);
    Lizzie.leelaz2 = previousSecondary;
    EngineManager.currentEngineNo = previousPrimaryIndex;
    EngineManager.currentEngineNo2 = previousSecondaryIndex;
    EngineManager.isEmpty = previousEmpty;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void refreshRetiresDiscardedSecondaryAndQueuedPreload(boolean replaceSecondary) throws Exception {
    EngineData primaryEntry = entry("primary");
    EngineData secondaryEntry = entry("secondary");
    EngineData preloadEntry = entry("preload");
    CatalogEngine primary = new CatalogEngine(primaryEntry);
    CatalogEngine secondary = new CatalogEngine(secondaryEntry);
    CatalogEngine preload = new CatalogEngine(preloadEntry);
    primary.started = secondary.started = true;
    select(primary, 0, secondary, 1);
    Leelaz.UpdateEngineStartAttempt queued = preload.beginPreloadStartAttempt();
    List<Leelaz> original = List.of(primary, secondary, preload);
    secondaryEntry.commands = "changed-command";
    preloadEntry.commands = "changed-preload-command";
    List<EngineData> snapshot =
        replaceSecondary ? List.of(secondaryEntry, primaryEntry, preloadEntry) : List.of(primaryEntry);
    EngineManager manager = new CatalogManager(original, snapshot);

    SwingUtilities.invokeAndWait(() -> assertDoesNotThrow(manager::refreshEngineCatalog));

    assertFalse(secondary.isStarted(), "the discarded secondary must be stopped during refresh");
    assertNull(Lizzie.leelaz2);
    assertEquals(-1, EngineManager.currentEngineNo2);
    assertThrows(IllegalStateException.class, () -> queued.startEngine(2));
    assertFalse(preload.hasEngineToStop());
    assertSame(primary, Lizzie.leelaz);
    assertSame(primary, manager.engineList.get(EngineManager.currentEngineNo));
    assertTrue(primary.isStarted());
    if (replaceSecondary) {
      Leelaz replacement = manager.engineList.get(0);
      assertNotSame(secondary, replacement);
      assertEquals(0, ((CatalogEngine) replacement).stops);
    }
    manager.forceKillAllEngines();
    assertEquals(1, secondary.stops);
    assertEquals(1, preload.stops);
    assertEquals(1, primary.stops);
  }

  @Test
  void reorderingLiveSlotsPreservesBothEndpoints() throws Exception {
    EngineData primaryEntry = entry("primary");
    EngineData secondaryEntry = entry("secondary");
    CatalogEngine primary = new CatalogEngine(primaryEntry);
    CatalogEngine secondary = new CatalogEngine(secondaryEntry);
    primary.started = secondary.started = true;
    select(primary, 0, secondary, 1);
    EngineManager manager =
        new CatalogManager(List.of(primary, secondary), List.of(secondaryEntry, primaryEntry));

    SwingUtilities.invokeAndWait(() -> assertDoesNotThrow(manager::refreshEngineCatalog));

    assertEquals(1, EngineManager.currentEngineNo);
    assertEquals(0, EngineManager.currentEngineNo2);
    assertSame(primary, Lizzie.leelaz);
    assertSame(secondary, Lizzie.leelaz2);
    assertTrue(primary.isStarted());
    assertTrue(secondary.isStarted());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void concurrentRefreshUsesLatestSavedEntriesAndMetadata(boolean olderPublishesFirst)
      throws Exception {
    EngineData oldPrimary = entry("primary");
    EngineData renamedPrimary = entry("primary");
    renamedPrimary.name = "renamed primary";
    CatalogEngine primary = new CatalogEngine(oldPrimary);
    CatalogEngine secondary = new CatalogEngine(entry("secondary"));
    primary.started = secondary.started = true;
    // A previous selection may still refer to an endpoint outside the catalog.
    select(primary, 0, secondary, -1);
    CountDownLatch[] captured = {new CountDownLatch(1), new CountDownLatch(1)};
    CountDownLatch[] publish = {new CountDownLatch(1), new CountDownLatch(1)};
    AtomicInteger reads = new AtomicInteger();
    EngineManager manager =
        new CatalogManager(List.of(primary), List.of(renamedPrimary)) {
          @Override
          protected ArrayList<EngineData> loadEngineCatalogSnapshot() {
            int read = reads.getAndIncrement();
            if (read < 2) {
              captured[read].countDown();
              assertTrue(assertDoesNotThrow(() -> publish[read].await(10, TimeUnit.SECONDS)));
            }
            return read == 0
                ? new ArrayList<>(List.of(oldPrimary, entry("removed")))
                : super.loadEngineCatalogSnapshot();
          }
        };
    Callable<Void> refresh =
        () -> {
          manager.refreshEngineCatalog();
          return null;
        };
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<Void> older = workers.submit(refresh);
      assertTrue(captured[0].await(10, TimeUnit.SECONDS));
      Future<Void> newer = workers.submit(refresh);
      assertTrue(captured[1].await(10, TimeUnit.SECONDS));
      int first = olderPublishesFirst ? 0 : 1;
      publish[first].countDown();
      (olderPublishesFirst ? older : newer).get(10, TimeUnit.SECONDS);
      publish[1 - first].countDown();
      older.get(10, TimeUnit.SECONDS);
      newer.get(10, TimeUnit.SECONDS);
      SwingUtilities.invokeAndWait(() -> {});
      assertEquals(List.of(primary), manager.engineList);
      assertEquals(renamedPrimary.name, primary.oriEnginename);
      assertTrue(primary.isStarted());
      assertFalse(secondary.isStarted());
      assertNull(Lizzie.leelaz2);
    } finally {
      publish[0].countDown();
      publish[1].countDown();
      workers.shutdown();
      assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stopIncludesLiveSlotEndpointsOutsideCatalog(boolean keepPrimary) throws Exception {
    CatalogEngine primary = new CatalogEngine(entry("primary"));
    CatalogEngine secondary = new CatalogEngine(entry("secondary"));
    primary.started = secondary.started = true;
    select(primary, -1, secondary, -1);
    EngineManager manager = new EngineManager(List.of());

    if (keepPrimary) manager.killOtherEngines();
    else manager.forceKillAllEngines();

    assertEquals(keepPrimary, primary.isStarted());
    assertFalse(secondary.isStarted());
    assertNull(Lizzie.leelaz2);
  }

  @Test
  void supersededManagerStopsOnlyItsOwnEndpoints() throws Exception {
    CatalogEngine old = new CatalogEngine(entry("old"));
    CatalogEngine primary = new CatalogEngine(entry("new-primary"));
    CatalogEngine secondary = new CatalogEngine(entry("new-secondary"));
    old.started = primary.started = secondary.started = true;
    EngineManager next = new EngineManager(List.of(primary, secondary));
    EngineManager stale =
        new CatalogManager(List.of(old), List.of(entry("old"))) {
          @Override
          protected ArrayList<EngineData> loadEngineCatalogSnapshot() {
            Lizzie.setEngineManager(next);
            select(primary, 0, secondary, 1);
            return super.loadEngineCatalogSnapshot();
          }
        };
    Lizzie.setEngineManager(stale);
    select(old, 0, null, -1);

    SwingUtilities.invokeAndWait(() -> assertDoesNotThrow(stale::refreshEngineCatalog));
    stale.forceKillAllEngines();

    assertFalse(old.isStarted());
    assertTrue(primary.isStarted());
    assertTrue(secondary.isStarted());
    assertSame(primary, Lizzie.leelaz);
    assertSame(secondary, Lizzie.leelaz2);
    assertEquals(0, EngineManager.currentEngineNo);
    assertEquals(1, EngineManager.currentEngineNo2);
  }

  private static void select(Leelaz primary, int primaryIndex, Leelaz secondary, int secondaryIndex) {
    Lizzie.setPrimaryEngine(primary);
    EngineManager.currentEngineNo = primaryIndex;
    EngineManager.isEmpty = primaryIndex < 0;
    Lizzie.leelaz2 = secondary;
    EngineManager.currentEngineNo2 = secondaryIndex;
  }

  private static EngineData entry(String id) {
    EngineData data = new EngineData();
    data.id = data.name = data.commands = id;
    data.width = data.height = 19;
    return data;
  }

  private static class CatalogManager extends EngineManager {
    private final ArrayList<EngineData> snapshot;

    CatalogManager(List<Leelaz> oldEngines, List<EngineData> newEntries) {
      super(oldEngines);
      snapshot = new ArrayList<>(newEntries);
    }

    @Override
    protected ArrayList<EngineData> loadEngineCatalogSnapshot() {
      return snapshot;
    }

    @Override
    protected Leelaz createUnstartedEngine(EngineData data) throws IOException {
      return new CatalogEngine(data);
    }
  }

  private static final class CatalogEngine extends Leelaz {
    private int stops;

    CatalogEngine(EngineData data) throws IOException {
      super(data.commands);
      savedEntryId = data.id;
      oriWidth = data.width;
      oriHeight = data.height;
      ip = data.ip;
      port = data.port;
      userName = data.userName;
    }

    @Override
    public String getEngineName(int index) {
      return savedEntryId;
    }

    @Override
    public void forceQuit() {
      stops++;
      super.forceQuit();
    }
  }
}
