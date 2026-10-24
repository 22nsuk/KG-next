package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.remote.CredentialStore;
import featurecat.lizzie.analysis.remote.SshCredentialTestStore;
import featurecat.lizzie.analysis.remote.SshEnginePasswords;
import featurecat.lizzie.util.BundledKataGoProfile;
import featurecat.lizzie.util.Utils;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class MoreEnginesDraftTest {
  @TempDir Path directory;

  private Config previousConfig;
  private ResourceBundle previousBundle;
  private EngineManager previousManager;
  private JTable previousTable;
  private boolean previousNeedUpdate;
  private TrackedMoreEngines editor;
  private SshCredentialTestStore sshStore;
  private Thread.UncaughtExceptionHandler previousExceptionHandler;
  private final AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    previousConfig = Lizzie.config;
    previousBundle = Lizzie.resourceBundle;
    previousManager = Lizzie.engineManager;
    previousTable = MoreEngines.table;
    previousNeedUpdate = MoreEngines.needUpdateEngine;
    previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, failure) -> asynchronousFailure.compareAndSet(null, failure));
    Files.createDirectories(directory.resolve("save"));
    Lizzie.config = ConfigTestHelper.createBootstrapped(directory);
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.ENGLISH);
    Lizzie.engineManager = null;
    Utils.saveEngineSettings(new ArrayList<>());
    SwingUtilities.invokeAndWait(() -> editor = new TrackedMoreEngines());
  }

  @AfterEach
  void tearDown() {
    try {
      assertAll(
          () -> {
            if (editor != null) editor.finishThreadPolicyWorkers();
          },
          () -> SwingUtilities.invokeAndWait(this::restoreGlobals),
          () ->
              SwingUtilities.invokeAndWait(
                  () ->
                      assertTrue(
                          editor == null
                              || editor.threadPolicyRefreshTimer == null
                              || !editor.threadPolicyRefreshTimer.isRunning(),
                          "fixture cleanup must consume worker done() before stopping its refresh timer")),
          () ->
              assertNull(
                  asynchronousFailure.get(),
                  () -> "Unhandled asynchronous fixture failure: " + asynchronousFailure.get()));
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler);
    }
  }

  private void restoreGlobals() {
    if (sshStore != null) SshCredentialTestStore.reset();
    if (editor != null && editor.threadPolicyRefreshTimer != null) {
      editor.threadPolicyRefreshTimer.stop();
    }
    Lizzie.config = previousConfig;
    Lizzie.resourceBundle = previousBundle;
    Lizzie.engineManager = previousManager;
    MoreEngines.table = previousTable;
    MoreEngines.needUpdateEngine = previousNeedUpdate;
  }

  @Test
  void cancellingPasswordEditsDoesNotTouchTheSavedSshCredential() throws Exception {
    sshStore = new SshCredentialTestStore();
    sshStore.install();
    sshStore.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "existing-shared", "previous password");
    EngineData engine = sshEngine();
    Utils.saveEngineSettings(new ArrayList<>(List.of(engine)));
    Map<String, String> original = Map.copyOf(sshStore.values);

    SwingUtilities.invokeAndWait(() -> {
      clickRow(0);
      editor.txtPassword.setText("uncommitted password");
      editor.cancel.doClick();
      assertEquals("vault:existing-shared", Utils.getEngineData().get(0).password);
      assertEquals(original, sshStore.values);
    });
  }

  @Test
  void unrelatedSaveKeepsUnreadablePasswordReferenceAndSuccessfulReplacementIsStable() throws Exception {
    sshStore = new SshCredentialTestStore();
    sshStore.install();
    sshStore.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "existing-shared", "previous password");
    Utils.saveEngineSettings(new ArrayList<>(List.of(sshEngine())));
    sshStore.failReads = true;

    SwingUtilities.invokeAndWait(() -> {
      clickRow(0);
      assertEquals("", new String(editor.txtPassword.getPassword()));
      editor.txtName.setText("renamed remote");
      editor.save.doClick();
      assertEquals("vault:existing-shared", Utils.getEngineData().get(0).password);
      sshStore.failReads = false;
      editor.txtPassword.setText("replacement");
      editor.save.doClick();
      String saved = Utils.getEngineData().get(0).password;
      assertTrue(saved.startsWith("vault:"));
      assertFalse(saved.equals("vault:existing-shared"));
      int writes = sshStore.writes;
      editor.save.doClick();
      assertEquals(writes, sshStore.writes);
      assertEquals(saved, Utils.getEngineData().get(0).password);
    });
    assertEquals("previous password", SshEnginePasswords.reveal("vault:existing-shared"));
    assertEquals("replacement", SshEnginePasswords.reveal(Utils.getEngineData().get(0).password));
  }

  private static EngineData sshEngine() {
    EngineData engine = new EngineData();
    engine.name = "remote";
    engine.commands = "katago gtp";
    engine.useJavaSSH = true;
    engine.ip = "example.test";
    engine.port = "22";
    engine.userName = "user";
    engine.password = "vault:existing-shared";
    return engine;
  }

  @Test
  void navigatingUntouchedEmptyRowsDoesNotPromptOrCreateAnEngine() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          clickRow(0);
          clickRow(1);
          clickRow(0);
          assertTrue(Utils.getEngineData().isEmpty());
          editor.add.doClick();
          ArrayList<EngineData> entries = Utils.getEngineData();
          assertEquals(1, entries.size());
          assertEquals(
              Lizzie.resourceBundle.getString("ChooseMoreEngine.newEngine"), entries.get(0).name);
        });
  }

  @Test
  void savingUntouchedEmptyRowDoesNotCreateAnEngine() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          clickRow(3);
          editor.save.doClick();
          assertTrue(Utils.getEngineData().isEmpty());
        });
  }

  @Test
  void savingEditedEmptyRowCreatesAnEntryThatCanBeReloaded() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          clickRow(2);
          editor.txtName.setText("Local engine");
          editor.command.setText("leelaz --gtp");
          editor.txtInitialCommand.setText("boardsize 13");
          editor.txtWidth.setText("13");
          editor.save.doClick();
          assertEquals(1, Utils.getEngineData().size());
          clickRow(4);
          clickRow(0);
          assertEquals("Local engine", editor.txtName.getText());
          assertEquals("leelaz --gtp", editor.command.getText());
          assertEquals("boardsize 13", editor.txtInitialCommand.getText());
          assertEquals("13", editor.txtWidth.getText());
        });
  }

  @Test
  void renamingManagedEntryPreservesOwnershipButEditingCommandRevokesIt() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          clickRow(0);
          editor.txtName.setText("KataGo Bundled");
          editor.command.setText(
              "engines/katago/linux-x64/katago gtp -model weights/default.bin.gz -config"
                  + " engines/katago/configs/gtp.cfg");
          editor.save.doClick();
          ArrayList<EngineData> entries = Utils.getEngineData();
          String id = entries.get(0).id;
          BundledKataGoProfile.claim(entries.get(0));
          Utils.saveEngineSettings(entries);
          editor.cancel.doClick();
          clickRow(0);
          editor.txtName.setText("Notebook KataGo");
          editor.save.doClick();
          EngineData renamed = Utils.getEngineData().get(0);
          assertEquals(id, renamed.id);
          assertEquals("Notebook KataGo", renamed.name);
          assertTrue(BundledKataGoProfile.isManaged(renamed));
          editor.command.setText("/custom/katago gtp");
          editor.save.doClick();
          EngineData repurposed = Utils.getEngineData().get(0);
          assertEquals(id, repurposed.id);
          assertEquals("/custom/katago gtp", repurposed.commands);
          assertFalse(BundledKataGoProfile.isManaged(repurposed));
        });
  }

  @Test
  void emptyRowsAndEmptySavedEntriesDoNotShowBenchmarkWarnings() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          clickRow(0);
          assertFalse(editor.threadPolicyStatus.isVisible());
          assertTrue(editor.threadPolicyCfg.isVisible());
          assertTrue(editor.threadPolicyCfg.isSelected());
          assertFalse(editor.threadPolicyCfg.isEnabled());
          assertTrue(editor.threadPolicyBenchmark.isVisible());
          assertFalse(editor.threadPolicyBenchmark.isEnabled());
          assertFalse(editor.benchmarkSelectedEngine.isEnabled());
          editor.add.doClick();
          assertFalse(editor.threadPolicyStatus.isVisible());
          assertNull(editor.threadPolicyStatus.getToolTipText());
        });
  }

  @Test
  void leavingBenchmarkedEntryClearsItsStatusTooltip() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          clickRow(0);
          editor.txtName.setText("KataGo A");
          editor.command.setText("katago gtp");
          editor.save.doClick();
          ArrayList<EngineData> entries = Utils.getEngineData();
          entries.get(0).threadPolicy.put("katago-benchmark-threads", 16);
          Utils.saveEngineSettings(entries);
          clickRow(0);
          assertNotNull(editor.threadPolicyStatus.getToolTipText());
          clickRow(3);
          assertNull(editor.threadPolicyStatus.getToolTipText());
          assertFalse(editor.threadPolicyStatus.isVisible());
        });
  }

  @Test
  void unknownDraftLocksCfgWithoutChangingSavedThreadPreference() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          clickRow(0);
          editor.txtName.setText("KataGo A");
          editor.command.setText("katago gtp");
          editor.save.doClick();
          ArrayList<EngineData> entries = Utils.getEngineData();
          entries
              .get(0)
              .threadPolicy
              .put("katago-benchmark-threads", 16)
              .put("source", "BENCHMARK");
          Utils.saveEngineSettings(entries);
          editor.cancel.doClick();
          clickRow(0);
          assertTrue(editor.threadPolicyBenchmark.isSelected());
          editor.command.setText("unknown://target");
          assertTrue(editor.threadPolicyCfg.isVisible());
          assertTrue(editor.threadPolicyCfg.isSelected());
          assertFalse(editor.threadPolicyCfg.isEnabled());
          assertFalse(editor.threadPolicyBenchmark.isEnabled());
          assertFalse(editor.benchmarkSelectedEngine.isEnabled());
          assertFalse(editor.threadPolicyStatus.isVisible());
          assertNull(editor.threadPolicyStatus.getToolTipText());
          editor.command.setText("katago gtp");
          assertTrue(editor.threadPolicyBenchmark.isSelected());
          assertTrue(editor.threadPolicyBenchmark.isEnabled());
          clickRow(3);
          assertTrue(editor.threadPolicyCfg.isSelected());
          assertFalse(editor.threadPolicyBenchmark.isSelected());
        });
  }

  private void clickRow(int row) {
    JTable table = MoreEngines.table;
    table.dispatchEvent(
        new MouseEvent(
            table,
            MouseEvent.MOUSE_CLICKED,
            0,
            0,
            5,
            row * table.getRowHeight() + 5,
            1,
            false,
            MouseEvent.BUTTON1));
  }

  private static final class TrackedMoreEngines extends MoreEngines {
    private record PendingWorker(SwingWorker<Void, Void> worker, CountDownLatch doneOnEdt) {}

    private final ArrayList<PendingWorker> workers = new ArrayList<>();

    @Override
    void executeThreadPolicyWorker(SwingWorker<Void, Void> worker) {
      CountDownLatch doneOnEdt = new CountDownLatch(1);
      worker.addPropertyChangeListener(
          event -> {
            if ("state".equals(event.getPropertyName())
                && event.getNewValue() == SwingWorker.StateValue.DONE) {
              doneOnEdt.countDown();
            }
          });
      workers.add(new PendingWorker(worker, doneOnEdt));
      super.executeThreadPolicyWorker(worker);
    }

    void finishThreadPolicyWorkers() {
      // A failed background result must not skip its EDT callback or another owned worker.
      ArrayList<Executable> completionChecks = new ArrayList<>();
      for (PendingWorker pending : workers) {
        completionChecks.add(
            () -> {
              try {
                pending.worker().get(5, TimeUnit.SECONDS);
              } catch (InterruptedException | TimeoutException failure) {
                pending.worker().cancel(true);
                throw failure;
              }
            });
        completionChecks.add(
            () ->
                assertTrue(
                    pending.doneOnEdt().await(5, TimeUnit.SECONDS),
                    "owned SwingWorker must finish its done() callback on the EDT"));
      }
      assertAll(completionChecks);
    }
  }
}
