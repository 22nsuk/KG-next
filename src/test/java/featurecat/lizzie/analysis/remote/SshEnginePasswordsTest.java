package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.FileAccessTestSupport;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.RemoteEngineData;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SshEnginePasswordsTest {
  @TempDir Path directory;
  private Config previousConfig;
  private ResourceBundle previousBundle;
  private final SshCredentialTestStore store = new SshCredentialTestStore();

  @BeforeEach
  void setUp() throws Exception {
    previousConfig = Lizzie.config;
    previousBundle = Lizzie.resourceBundle;
    Lizzie.config = ConfigTestHelper.createBootstrapped(directory);
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.ENGLISH);
    store.install();
    Utils.saveEngineSettings(new ArrayList<>());
  }

  @AfterEach
  void restore() {
    SshCredentialTestStore.reset();
    Lizzie.config = previousConfig;
    Lizzie.resourceBundle = previousBundle;
  }

  @Test
  void migratesEachStoredCredentialWithoutOverwritingAnotherLoginForTheSameServer()
      throws Exception {
    JSONObject leelaz = Lizzie.config.leelazConfig;
    leelaz.put("engine-settings-list", new JSONArray().put(legacy("general secret")));
    leelaz.put("analysis-engine-ssh-info", legacy("analysis secret"));
    leelaz.put("contribute-engine-ssh-info", legacy("contribution secret"));
    Lizzie.config.save();

    assertTrue(SshEnginePasswords.migrateLegacyPasswords(Lizzie.config));

    JSONObject saved = readConfig().getJSONObject("leelaz");
    JSONObject engine = saved.getJSONArray("engine-settings-list").getJSONObject(0);
    JSONObject analysis = saved.getJSONObject("analysis-engine-ssh-info");
    JSONObject contribution = saved.getJSONObject("contribute-engine-ssh-info");
    assertEquals("general secret", SshEnginePasswords.reveal(SshEnginePasswords.readFrom(engine)));
    assertEquals(
        "analysis secret", SshEnginePasswords.reveal(SshEnginePasswords.readFrom(analysis)));
    assertEquals(
        "contribution secret",
        SshEnginePasswords.reveal(SshEnginePasswords.readFrom(contribution)));
    assertEquals(
        3,
        java.util.Set.of(
                engine.getString("passwordRef"),
                analysis.getString("passwordRef"),
                contribution.getString("passwordRef"))
            .size());
    for (JSONObject entry : List.of(engine, analysis, contribution))
      assertEquals("", entry.getString("password"));
    int writes = store.writes;
    assertFalse(SshEnginePasswords.migrateLegacyPasswords(Lizzie.config));
    assertEquals(writes, store.writes, "already migrated credentials are not rewritten");
  }

  @ParameterizedTest
  @ValueSource(strings = {"unavailable", "write-failure", "read-failure", "read-mismatch"})
  void failedVaultMigrationPreservesTheOriginalSettingsAndSecret(String failure) throws Exception {
    Lizzie.config.leelazConfig.put(
        "analysis-engine-ssh-info", legacy("secret with trailing space "));
    Lizzie.config.save();
    byte[] original = Files.readAllBytes(configPath());
    JSONObject originalRoot = Lizzie.config.config;
    failVault(failure);

    assertFalse(SshEnginePasswords.migrateLegacyPasswords(Lizzie.config));

    assertSame(originalRoot, Lizzie.config.config);
    assertArrayEquals(original, Files.readAllBytes(configPath()));
    assertEquals(
        "secret with trailing space ",
        SshEnginePasswords.reveal(Utils.getAnalysisEngineRemoteEngineData().password));
    assertTrue(store.values.isEmpty(), "only an attempted fresh credential may be discarded");
  }

  @Test
  void failedConfigMigrationPreservesLiveSettingsAndRollsBackOnlyNewVaultEntries()
      throws Exception {
    store.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "existing-shared", "existing");
    Lizzie.config.leelazConfig.put("analysis-engine-ssh-info", legacy("new migration"));
    Lizzie.config.save();
    JSONObject original = Lizzie.config.config;
    byte[] bytes = Files.readAllBytes(configPath());
    Map<String, String> oldSecrets = Map.copyOf(store.values);

    try (AutoCloseable ignored = FileAccessTestSupport.denyParentWrite(directory)) {
      assertThrows(IOException.class, () -> Files.createTempFile(directory, "write-probe", ".tmp"));
      assertThrows(
          IOException.class, () -> SshEnginePasswords.migrateLegacyPasswords(Lizzie.config));
    }

    assertSame(original, Lizzie.config.config);
    assertArrayEquals(bytes, Files.readAllBytes(configPath()));
    assertEquals(oldSecrets, store.values);
  }

  @Test
  void aCancelledRemoteSettingsDraftNeverWritesOrChangesAnExistingCredential() throws Exception {
    store.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "existing-shared", "previous");
    SshEnginePasswords.Display password = SshEnginePasswords.display("vault:existing-shared");
    Map<String, String> original = Map.copyOf(store.values);

    RemoteEngineData draft = new RemoteEngineData();
    draft.password = password.edited("replacement");
    JSONObject candidate = new JSONObject(Lizzie.config.leelazConfig.toString());
    Utils.updateAnalysisEngineRemoteEngineData(candidate, draft);

    assertEquals(original, store.values);
    assertFalse(Lizzie.config.leelazConfig.has("analysis-engine-ssh-info"));
    assertEquals("previous", SshEnginePasswords.reveal("vault:existing-shared"));
  }

  @Test
  void savingGeneralEngineReplacesOnlyItsReferenceAndAdoptsItInMemory() throws Exception {
    store.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "existing-shared", "previous");
    EngineData engine = engine("vault:existing-shared");
    ArrayList<EngineData> engines = new ArrayList<>(List.of(engine));
    Utils.saveEngineSettings(engines);
    engine.password = SshEnginePasswords.draft("replacement");

    Utils.saveEngineSettings(engines);

    assertTrue(engine.password.startsWith("vault:"));
    assertEquals("replacement", SshEnginePasswords.reveal(engine.password));
    assertEquals("previous", SshEnginePasswords.reveal("vault:existing-shared"));
    assertEquals(engine.password, Utils.getEngineData().get(0).password);
    String json = Files.readString(configPath());
    assertFalse(json.contains("draft:"));
    assertFalse(json.contains(Utils.doEncrypt("replacement")));
    int writes = store.writes;
    Utils.saveEngineSettings(engines);
    assertEquals(writes, store.writes);
  }

  @Test
  void failedGeneralEngineSaveKeepsSavedReferenceAndInputDraftForRetry() throws Exception {
    store.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "existing-shared", "previous");
    ArrayList<EngineData> engines = new ArrayList<>(List.of(engine("vault:existing-shared")));
    Utils.saveEngineSettings(engines);
    byte[] before = Files.readAllBytes(configPath());
    String draft = SshEnginePasswords.draft("replacement");
    engines.get(0).password = draft;
    Map<String, String> original = Map.copyOf(store.values);

    try (AutoCloseable ignored = FileAccessTestSupport.denyParentWrite(directory)) {
      assertThrows(IOException.class, () -> Files.createTempFile(directory, "write-probe", ".tmp"));
      assertThrows(java.io.UncheckedIOException.class, () -> Utils.saveEngineSettings(engines));
    }

    assertArrayEquals(before, Files.readAllBytes(configPath()));
    assertEquals("vault:existing-shared", Utils.getEngineData().get(0).password);
    assertEquals(draft, engines.get(0).password);
    assertEquals(original, store.values);
    Utils.saveEngineSettings(engines);
    assertEquals("replacement", SshEnginePasswords.reveal(engines.get(0).password));
  }

  @ParameterizedTest
  @ValueSource(strings = {"analysis", "contribution"})
  void directRemoteSaveAdoptsTheCommittedReferenceWithoutRewritingIt(String target)
      throws Exception {
    store.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "existing-shared", "previous");
    RemoteEngineData remote = new RemoteEngineData();
    remote.useJavaSSH = true;
    remote.password = "vault:existing-shared";
    saveRemote(target, remote);
    remote.password = SshEnginePasswords.draft("replacement");

    saveRemote(target, remote);

    assertTrue(remote.password.startsWith("vault:"));
    assertNotEquals("vault:existing-shared", remote.password);
    assertEquals("replacement", SshEnginePasswords.reveal(remote.password));
    assertEquals("previous", SshEnginePasswords.reveal("vault:existing-shared"));
    assertEquals(remote.password, readRemote(target).password);
    String reference = remote.password;
    int writes = store.writes;
    saveRemote(target, remote);
    assertEquals(writes, store.writes);
    assertEquals(reference, remote.password);
    assertEquals(reference, readRemote(target).password);
  }

  @ParameterizedTest
  @ValueSource(strings = {"analysis", "contribution"})
  void failedDirectRemoteSaveRetainsTheDraftAndSavedReferenceForRetry(String target)
      throws Exception {
    store.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "existing-shared", "previous");
    RemoteEngineData remote = new RemoteEngineData();
    remote.useJavaSSH = true;
    remote.password = "vault:existing-shared";
    saveRemote(target, remote);
    byte[] originalBytes = Files.readAllBytes(configPath());
    Map<String, String> originalSecrets = Map.copyOf(store.values);
    String draft = SshEnginePasswords.draft("replacement");
    remote.password = draft;

    try (AutoCloseable ignored = FileAccessTestSupport.denyParentWrite(directory)) {
      assertThrows(IOException.class, () -> Files.createTempFile(directory, "write-probe", ".tmp"));
      assertThrows(java.io.UncheckedIOException.class, () -> saveRemote(target, remote));
    }

    assertArrayEquals(originalBytes, Files.readAllBytes(configPath()));
    assertEquals(originalSecrets, store.values);
    assertEquals("vault:existing-shared", readRemote(target).password);
    assertEquals(draft, remote.password);
    saveRemote(target, remote);
    assertEquals("replacement", SshEnginePasswords.reveal(remote.password));
    assertEquals(remote.password, readRemote(target).password);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unavailable", "write-failure", "read-failure", "read-mismatch"})
  void newPasswordKeepsCompatibilityFallbackWithoutPersistingDraftMarker(String failure)
      throws Exception {
    failVault(failure);
    RemoteEngineData remote = new RemoteEngineData();
    remote.useJavaSSH = true;
    remote.password = SshEnginePasswords.draft("new password ");

    Utils.saveAnalysisEngineRemoteEngineData(remote);

    JSONObject saved =
        readConfig().getJSONObject("leelaz").getJSONObject("analysis-engine-ssh-info");
    assertFalse(saved.has("passwordRef"));
    assertEquals(Utils.doEncrypt("new password "), saved.getString("password"));
    assertEquals("new password ", SshEnginePasswords.reveal(SshEnginePasswords.readFrom(saved)));
    assertTrue(store.values.isEmpty());
  }

  @Test
  void unavailableSavedReferenceIsRetainedWhenItsFieldIsUnchanged() throws Exception {
    store.available = false;
    SshEnginePasswords.Display password = SshEnginePasswords.display("vault:existing");
    assertFalse(password.readable());
    assertFalse(password.changed(password.text()));
    assertEquals("vault:existing", password.edited(password.text()));
    assertThrows(IOException.class, () -> SshEnginePasswords.reveal("vault:existing"));
    assertEquals("replacement", SshEnginePasswords.reveal(password.edited("replacement")));
  }

  private void failVault(String failure) {
    store.available = !failure.equals("unavailable");
    store.failWrites = failure.equals("write-failure");
    store.failReads = failure.equals("read-failure");
    store.corruptReads = failure.equals("read-mismatch");
  }

  private void saveRemote(String target, RemoteEngineData remote) {
    if (target.equals("analysis")) Utils.saveAnalysisEngineRemoteEngineData(remote);
    else Utils.saveContributeRemoteEngineData(remote);
  }

  private RemoteEngineData readRemote(String target) {
    return target.equals("analysis")
        ? Utils.getAnalysisEngineRemoteEngineData()
        : Utils.getContributeRemoteEngineData();
  }

  private JSONObject legacy(String password) {
    return new JSONObject()
        .put("useJavaSSH", true)
        .put("ip", "same.example")
        .put("port", "22")
        .put("userName", "same-user")
        .put("password", Utils.doEncrypt(password));
  }

  private EngineData engine(String password) {
    EngineData engine = new EngineData();
    engine.useJavaSSH = true;
    engine.ip = "same.example";
    engine.port = "22";
    engine.userName = "same-user";
    engine.password = password;
    engine.commands = "katago gtp";
    return engine;
  }

  private Path configPath() {
    return Path.of(Lizzie.config.getConfigFilePath());
  }

  private JSONObject readConfig() throws IOException {
    return new JSONObject(Files.readString(configPath()));
  }
}
