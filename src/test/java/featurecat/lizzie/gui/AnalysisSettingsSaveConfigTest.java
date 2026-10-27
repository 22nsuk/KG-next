package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.remote.CredentialStore;
import featurecat.lizzie.analysis.remote.SshCredentialTestStore;
import featurecat.lizzie.analysis.remote.SshEnginePasswords;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.LocalKataGoDiscoveryResult;
import featurecat.lizzie.util.Utils;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JOptionPane;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisSettingsSaveConfigTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void successfulAnalysisAndRememberedEngineSavesPreserveBothChanges(
      boolean overlap, @TempDir Path directory) throws Exception {
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    Config config = ConfigTestHelper.createForTests(directory);
    initializeConfig(config);
    config.analysisEngineCommand = "katago analysis";
    AtomicBoolean started = new AtomicBoolean();
    FutureTask<Void> remember = null;
    try {
      Lizzie.config = config;
      Lizzie.frame = null;
      LocalKataGoDiscoveryResult selected = localSelection(directory);
      CountDownLatch attemptingSave = new CountDownLatch(1);
      remember =
          new FutureTask<>(
              () -> {
                attemptingSave.countDown();
                KataGoAutoSetupHelper.rememberSelectedLocalKataGo(selected);
                return null;
              });
      FutureTask<Void> result = remember;
      Thread worker = new Thread(remember, "analysis-settings-remember-engine");
      if (overlap) {
        config.uiConfig =
            new JSONObject(config.uiConfig.toString()) {
              @Override
              public String toString() {
                String captured = super.toString();
                if (started.compareAndSet(false, true)) {
                  // The UI has captured its candidate. The helper either commits now (old code)
                  // or waits for the same configuration transaction to finish.
                  worker.start();
                  awaitCommitTurn(config, worker, result, attemptingSave);
                }
                return captured;
              }
            };
        config.config.put("ui", config.uiConfig);
      }
      SilentAnalysisSettings settings = settingsWithRemoteDraft(new RemoteEngineData());

      SwingUtilities.invokeAndWait(() -> assertTrue(settings.saveConfig()));
      if (!overlap) {
        started.set(true);
        worker.start();
      }
      remember.get(5, TimeUnit.SECONDS);

      assertTrue(started.get(), "both production save paths must execute");
      assertRememberedSelection(config, selected);
      assertEquals(20, config.analysisMaxVisits);
      assertEquals(20, config.uiConfig.getInt("analysis-max-visits"));
      assertEquals(20, readConfig(config).getJSONObject("ui").getInt("analysis-max-visits"));
    } finally {
      try {
        if (started.get() && remember != null) remember.get(5, TimeUnit.SECONDS);
      } finally {
        Lizzie.config = previousConfig;
        Lizzie.frame = previousFrame;
      }
    }
  }

  @Test
  void parentSaveCommitsRemotePasswordDraftOnceAndKeepsThePreviousReferenceUsable() throws Exception {
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    Path directory = Files.createTempDirectory("analysis-ssh-password-commit");
    Config config = ConfigTestHelper.createForTests(directory);
    initializeConfig(config);
    config.analysisEngineCommand = "katago analysis";
    SshCredentialTestStore store = new SshCredentialTestStore();
    store.install();
    store.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "existing-shared", "previous password");
    RemoteEngineData remote = new RemoteEngineData();
    remote.useJavaSSH = true;
    remote.password = SshEnginePasswords.display("vault:existing-shared").edited("replacement");
    SilentAnalysisSettings settings = settingsWithRemoteDraft(remote);
    try {
      Lizzie.config = config;
      Lizzie.frame = null;
      assertEquals(1, store.writes, "the nested settings draft must not write to the vault");
      SwingUtilities.invokeAndWait(() -> assertTrue(settings.saveConfig()));
      assertTrue(remote.password.startsWith("vault:"));
      assertEquals("replacement", SshEnginePasswords.reveal(remote.password));
      assertEquals("previous password", SshEnginePasswords.reveal("vault:existing-shared"));
      assertEquals(remote.password, Utils.getAnalysisEngineRemoteEngineData().password);
      int writes = store.writes;
      SwingUtilities.invokeAndWait(() -> assertTrue(settings.saveConfig()));
      assertEquals(writes, store.writes);
    } finally {
      SshCredentialTestStore.reset();
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void failedParentSavePreservesRemotePasswordDraftAndOriginalVaultValue() throws Exception {
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    Path directory = Files.createTempDirectory("analysis-ssh-password-failure");
    Config config = ConfigTestHelper.createForTests(directory);
    initializeConfig(config);
    config.analysisEngineCommand = "katago analysis";
    SshCredentialTestStore store = new SshCredentialTestStore();
    store.install();
    store.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "existing-shared", "previous password");
    Map<String, String> existing = Map.copyOf(store.values);
    RemoteEngineData remote = new RemoteEngineData();
    remote.password = SshEnginePasswords.draft("replacement");
    String draft = remote.password;
    SilentAnalysisSettings settings = settingsWithRemoteDraft(remote);
    try {
      Lizzie.config = config;
      Lizzie.frame = null;
      LocalKataGoDiscoveryResult selected = localSelection(directory);
      KataGoAutoSetupHelper.rememberSelectedLocalKataGo(selected);
      assertRememberedSelection(config, selected);
      JSONObject originalRoot = config.config;
      JSONObject originalUi = config.uiConfig;
      JSONObject originalLeelaz = config.leelazConfig;
      Path configFile = Path.of(config.getConfigFilePath());
      byte[] saved = Files.readAllBytes(configFile);
      Path preservedFile = directory.resolve("saved-config.txt");
      Files.move(configFile, preservedFile);
      Files.createDirectory(configFile);
      Path sentinel = Files.writeString(configFile.resolve("keep"), "keep");

      SwingUtilities.invokeAndWait(() -> assertFalse(settings.saveConfig()));

      assertSame(originalRoot, config.config);
      assertSame(originalUi, config.uiConfig);
      assertSame(originalLeelaz, config.leelazConfig);
      assertEquals(
          selected.enginePath.toString(),
          config.uiConfig.getString("katago-auto-setup-engine-path"));
      assertArrayEquals(saved, Files.readAllBytes(preservedFile));
      assertEquals("keep", Files.readString(sentinel));
      assertEquals(draft, remote.password);
      assertEquals(existing, store.values);

      Files.delete(sentinel);
      Files.delete(configFile);
      Files.move(preservedFile, configFile);
      SwingUtilities.invokeAndWait(() -> assertTrue(settings.saveConfig()));
      assertRememberedSelection(config, selected);
      assertEquals(20, readConfig(config).getJSONObject("ui").getInt("analysis-max-visits"));
      assertEquals("replacement", SshEnginePasswords.reveal(remote.password));
      assertEquals("previous password", SshEnginePasswords.reveal("vault:existing-shared"));
    } finally {
      SshCredentialTestStore.reset();
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
    }
  }

  private static SilentAnalysisSettings settingsWithRemoteDraft(RemoteEngineData remote) throws Exception {
    SilentAnalysisSettings settings = allocate(SilentAnalysisSettings.class);
    setField(AnalysisSettings.class, settings, "context", AnalysisSettings.Context.NORMAL);
    setField(AnalysisSettings.class, settings, "engineCmd", new JFontTextArea("katago analysis"));
    setField(AnalysisSettings.class, settings, "txtMaxVisits", new JTextField("20"));
    for (String field : List.of("chkAutoExit", "chkPreLoad", "chkAlwaysOverride", "chkReuseCurrentEngine")) {
      setField(AnalysisSettings.class, settings, field, selectedCheckBox(false));
    }
    setField(AnalysisSettings.class, settings, "chkUseJavaSSH", selectedCheckBox(true));
    setField(AnalysisSettings.class, settings, "rdoUseCurrentRules", selectedRadio(false));
    setField(AnalysisSettings.class, settings, "rdoUseSpecificRules", selectedRadio(true));
    setField(AnalysisSettings.class, settings, "pendingRemoteEngineData", remote);
    return settings;
  }


  @Test
  void unchangedGeneratedCommandRemainsAutoManagedAfterSavingDedicatedMode() {
    assertFalse(
        AnalysisSettings.commandCustomizedAfterSave(
            false, false, false, "generated-command", "generated-command"));
    assertTrue(
        AnalysisSettings.commandCustomizedAfterSave(
            false, false, true, "generated-command", "generated-command"));
    assertTrue(
        AnalysisSettings.commandCustomizedAfterSave(
            false, false, false, "generated-command", "edited-command"));
    assertFalse(
        AnalysisSettings.commandCustomizedAfterSave(
            false, true, true, "generated-command", "edited-command"));
    assertTrue(
        AnalysisSettings.commandCustomizedAfterSave(
            true, false, false, "custom-command", "custom-command"));
  }

  @Test
  void foregroundLeasePreventsDeferredHumanSlStartReservation() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz previousEngine = Lizzie.leelaz;
    TrackingFlashAnalysisFrame frame = allocate(TrackingFlashAnalysisFrame.class);
    Leelaz foreground = new Leelaz("");
    foreground.isLoaded = true;
    foreground.started = true;
    foreground.isKatago = true;
    foreground.commandLists.addAll(
        List.of(
            "stop",
            "boardsize",
            "komi",
            "kata-get-rules",
            "kata-set-rules",
            "clear_board",
            "play",
            "set_position",
            "kata-analyze"));
    setField(Leelaz.class, foreground, "endGetCommandList", true);
    setField(
        Leelaz.class,
        foreground,
        "outputStream",
        new BufferedOutputStream(new ByteArrayOutputStream()));
    AtomicInteger starts = new AtomicInteger();
    try {
      Lizzie.frame = frame;
      Lizzie.leelaz = foreground;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          foreground.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));

      assertFalse(frame.runWithForegroundEngineModeReservation(starts::incrementAndGet));

      assertEquals(0, starts.get());
      assertEquals(1, frame.reservationConflictCount);
      assertTrue(foreground.hasExclusiveGtpLease());
    } finally {
      foreground.endExclusiveGtpSession();
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void missingCommandConfirmationResumesOriginalRequestOnlyAfterSettingsSaveCallback()
      throws Exception {
    TrackingFlashAnalysisFrame frame = allocate(TrackingFlashAnalysisFrame.class);

    frame.handleMissingFlashAnalysisCommandChoice(JOptionPane.OK_OPTION, true, false);

    assertEquals(1, frame.settingsShown);
    assertEquals(0, frame.flashCalls);

    frame.resumeFlashAnalysisAfterSettings();

    assertEquals(1, frame.flashCalls);
    assertSame(Boolean.TRUE, frame.lastAllGame);
    assertSame(Boolean.FALSE, frame.lastAllBranches);
  }

  @Test
  void missingCommandCancellationDiscardsPendingRequestAndDoesNotStartAnalysis()
      throws Exception {
    TrackingFlashAnalysisFrame frame = allocate(TrackingFlashAnalysisFrame.class);

    frame.handleMissingFlashAnalysisCommandChoice(JOptionPane.OK_OPTION, false, true);
    frame.cancelPendingFlashAnalysisAfterSettings();
    frame.resumeFlashAnalysisAfterSettings();

    assertEquals(1, frame.settingsShown);
    assertEquals(0, frame.flashCalls);
  }

  @Test
  void emptyDedicatedCommandCannotSaveOrResumePendingRequest() throws Exception {
    Config previousConfig = Lizzie.config;
    Config config = allocate(Config.class);
    config.analysisEngineCommand = "";
    config.analysisReuseCurrentEngine = false;
    try {
      Lizzie.config = config;
      SilentAnalysisSettings settings = allocate(SilentAnalysisSettings.class);
      setField(AnalysisSettings.class, settings, "engineCmd", new JFontTextArea("   "));
      setField(
          AnalysisSettings.class, settings, "chkReuseCurrentEngine", selectedCheckBox(false));

      assertFalse(settings.saveConfig());
      assertEquals(1, settings.commandRequiredCount);
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void failedSaveLeavesRuntimeEngineAndPendingContinuationUnchanged() throws Exception {
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    Path workDirectory = Files.createTempDirectory("analysis-settings-save-failure");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfig(config);
    config.analysisEngineCommand = "old-command";
    config.analysisEngineCommandCustomized = false;
    config.analysisMaxVisits = 20;
    config.batchAnalysisPlayouts = 30;
    config.analysisAutoQuit = false;
    config.analysisEnginePreLoad = false;
    config.analysisReuseCurrentEngine = false;
    config.analysisAlwaysOverride = false;
    config.analysisUseCurrentRules = false;
    JSONObject previousRoot = config.config;
    JSONObject previousUi = config.uiConfig;
    JSONObject previousLeelaz = config.leelazConfig;

    LizzieFrame frame = allocate(LizzieFrame.class);
    AnalysisEngine existingEngine = allocate(AnalysisEngine.class);
    frame.analysisEngine = existingEngine;
    Object pendingRequest =
        allocate(Class.forName("featurecat.lizzie.gui.LizzieFrame$FlashAnalysisRequest"));
    setField(LizzieFrame.class, frame, "pendingFlashAnalysisAfterSettings", pendingRequest);

    SilentAnalysisSettings settings = allocate(SilentAnalysisSettings.class);
    setField(AnalysisSettings.class, settings, "context", AnalysisSettings.Context.NORMAL);
    setField(AnalysisSettings.class, settings, "engineCmd", new JFontTextArea("new-command"));
    setField(AnalysisSettings.class, settings, "txtMaxVisits", new JTextField("99"));
    setField(AnalysisSettings.class, settings, "chkAutoExit", selectedCheckBox(true));
    setField(AnalysisSettings.class, settings, "chkPreLoad", selectedCheckBox(true));
    setField(AnalysisSettings.class, settings, "chkAlwaysOverride", selectedCheckBox(true));
    setField(AnalysisSettings.class, settings, "chkReuseCurrentEngine", selectedCheckBox(false));
    setField(AnalysisSettings.class, settings, "chkUseJavaSSH", selectedCheckBox(true));
    setField(AnalysisSettings.class, settings, "rdoUseCurrentRules", selectedRadio(true));
    setField(AnalysisSettings.class, settings, "rdoUseSpecificRules", selectedRadio(false));

    Path configFile = Path.of(config.getConfigFilePath());
    Files.delete(configFile);
    Files.createDirectory(configFile);
    try {
      Lizzie.config = config;
      Lizzie.frame = frame;

      assertFalse(settings.saveConfig());

      assertSame(previousRoot, config.config);
      assertSame(previousUi, config.uiConfig);
      assertSame(previousLeelaz, config.leelazConfig);
      assertSame(existingEngine, frame.analysisEngine);
      assertSame(
          pendingRequest,
          getField(LizzieFrame.class, frame, "pendingFlashAnalysisAfterSettings"));
      assertFalse(config.analysisAutoQuit);
      assertFalse(config.analysisEnginePreLoad);
      assertFalse(config.analysisReuseCurrentEngine);
      assertFalse(config.analysisAlwaysOverride);
      assertFalse(config.analysisUseCurrentRules);
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
    }
  }

  private static void initializeConfig(Config config) throws IOException {
    JSONObject root = new JSONObject();
    JSONObject ui = new JSONObject();
    JSONObject leelaz = new JSONObject();
    root.put("ui", ui);
    root.put("leelaz", leelaz);
    config.config = root;
    config.uiConfig = ui;
    config.leelazConfig = leelaz;
    config.save();
  }

  private static LocalKataGoDiscoveryResult localSelection(Path directory) throws IOException {
    Path engine = Files.writeString(directory.resolve("selected-katago"), "");
    Path gtp = Files.writeString(directory.resolve("gtp.cfg"), "");
    Files.writeString(directory.resolve("analysis.cfg"), "");
    Path weight = Files.writeString(directory.resolve("selected.bin.gz"), "");
    return KataGoAutoSetupHelper.inspectSelectedLocalKataGo(engine, gtp, weight);
  }

  private static JSONObject readConfig(Config config) throws IOException {
    return new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
  }

  private static void assertRememberedSelection(Config config, LocalKataGoDiscoveryResult selected)
      throws IOException {
    JSONObject savedUi = readConfig(config).getJSONObject("ui");
    Map<String, Path> expected =
        Map.of(
            "katago-auto-setup-engine-path", selected.enginePath,
            "katago-auto-setup-gtp-config-path", selected.gtpConfigPath,
            "katago-auto-setup-analysis-config-path", selected.analysisConfigPath,
            "katago-auto-setup-weight-path", selected.activeWeightPath);
    expected.forEach(
        (key, path) -> {
          assertEquals(path.toString(), config.uiConfig.optString(key), "live " + key);
          assertEquals(path.toString(), savedUi.optString(key), "persisted " + key);
        });
  }

  private static void awaitCommitTurn(
      Config config, Thread worker, FutureTask<Void> result, CountDownLatch attemptingSave) {
    try {
      assertTrue(attemptingSave.await(5, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!result.isDone()) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(worker.getId());
        if (info != null
            && info.getThreadState() == Thread.State.BLOCKED
            && info.getLockInfo() != null
            && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(config)) {
          return;
        }
        assertTrue(
            System.nanoTime() < deadline,
            "the helper must commit or wait for the analysis settings transaction");
        Thread.onSpinWait();
      }
      result.get(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new AssertionError(e);
    }
  }

  private static JFontCheckBox selectedCheckBox(boolean selected) {
    JFontCheckBox checkBox = new JFontCheckBox();
    checkBox.setSelected(selected);
    return checkBox;
  }

  private static JRadioButton selectedRadio(boolean selected) {
    JRadioButton radioButton = new JRadioButton();
    radioButton.setSelected(selected);
    return radioButton;
  }

  private static void setField(Class<?> owner, Object target, String name, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Class<?> owner, Object target, String name) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws InstantiationException {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class SilentAnalysisSettings extends AnalysisSettings {
    private int commandRequiredCount;

    private SilentAnalysisSettings() {
      super(false, false);
    }

    @Override
    void showSaveFailure(IOException error) {}

    @Override
    void showCommandRequired() {
      commandRequiredCount++;
    }
  }

  private static final class TrackingFlashAnalysisFrame extends LizzieFrame {
    private int settingsShown;
    private int flashCalls;
    private Boolean lastAllGame;
    private Boolean lastAllBranches;
    private int reservationConflictCount;

    private TrackingFlashAnalysisFrame() {}

    @Override
    protected void showMissingFlashAnalysisSettings() {
      settingsShown++;
    }

    @Override
    protected void showForegroundEngineModeReservationConflict() {
      reservationConflictCount++;
    }

    @Override
    public void flashAnalyzeGame(boolean isAllGame, boolean isAllBranches) {
      flashCalls++;
      lastAllGame = isAllGame;
      lastAllBranches = isAllBranches;
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE;

    static {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        UNSAFE = (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
