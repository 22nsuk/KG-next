package featurecat.lizzie.analysis.remote;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns SSH password references and their preparation for the existing settings commit. */
public final class SshEnginePasswords {
  public static final String REFERENCE_KEY = "passwordRef";
  static final String VAULT_PREFIX = "vault:";
  private static final String DRAFT_PREFIX = "draft:";
  private static final String ENGINE_LIST_KEY = "engine-settings-list";
  private static final String ANALYSIS_ENGINE_KEY = "analysis-engine-ssh-info";
  private static final String CONTRIBUTE_ENGINE_KEY = "contribute-engine-ssh-info";
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.REMOTE);

  private SshEnginePasswords() {}

  /**
   * A dialog draft has no credential-store side effects, including when its parent is cancelled.
   */
  public static String draft(String plaintext) {
    return plaintext == null || plaintext.isEmpty()
        ? ""
        : DRAFT_PREFIX + Utils.doEncrypt(plaintext);
  }

  public static String reveal(String stored) throws IOException {
    if (stored == null || stored.isEmpty()) return "";
    return reveal(RemoteComputeConfig.credentialStore(), stored);
  }

  static String reveal(CredentialStore store, String stored) throws IOException {
    if (stored == null || stored.isEmpty()) return "";
    if (!stored.startsWith(VAULT_PREFIX)) {
      return decryptLegacy(
          stored.startsWith(DRAFT_PREFIX) ? stored.substring(DRAFT_PREFIX.length()) : stored);
    }
    if (store == null || !store.isAvailable()) {
      throw new IOException("The system credential store is unavailable.");
    }
    Optional<String> secret =
        store.read(
            CredentialStore.Kind.SSH_ENGINE_PASSWORD, stored.substring(VAULT_PREFIX.length()));
    if (secret.isEmpty()) throw new IOException("The saved SSH password is unavailable.");
    return secret.get();
  }

  /** Preserve the stored value when an unreadable password field is left untouched. */
  public record Display(String stored, String text, boolean readable) {
    public String edited(String value) {
      return text.equals(value) ? stored : draft(value);
    }

    public boolean changed(String value) {
      return !text.equals(value);
    }
  }

  public static Display display(String stored) {
    String value = stored == null ? "" : stored;
    try {
      return new Display(value, reveal(value), true);
    } catch (IOException | RuntimeException failure) {
      LOG.warn("Unable to display a saved SSH password ({})", failure.getClass().getSimpleName());
      return new Display(value, "", false);
    }
  }

  public static String storageHint() {
    return text(
        "SSHController.passwordStorageHint",
        "SSH passwords use the system credential store when available. Otherwise the existing "
            + "reversible storage is used; it is not strong encryption.");
  }

  public static String displayHint(Display display) {
    return display.readable()
        ? storageHint()
        : text(
            "SSHController.savedPasswordUnchanged",
            "The saved password cannot be read. Leave this field unchanged to keep it, or enter a"
                + " replacement.");
  }

  private static String text(String key, String fallback) {
    return Lizzie.resourceBundle != null && Lizzie.resourceBundle.containsKey(key)
        ? Lizzie.resourceBundle.getString(key)
        : fallback;
  }

  public static String readFrom(JSONObject settings) {
    if (settings == null) return "";
    String reference = settings.optString(REFERENCE_KEY, "").trim();
    return reference.isEmpty() ? settings.optString("password", "") : VAULT_PREFIX + reference;
  }

  /** Serializes an in-memory candidate; Config prepares drafts before writing any bytes. */
  public static void writeTo(JSONObject settings, String stored) {
    String value = stored == null ? "" : stored;
    if (value.startsWith(VAULT_PREFIX)) {
      settings.put(REFERENCE_KEY, value.substring(VAULT_PREFIX.length()));
      settings.put("password", "");
    } else {
      settings.remove(REFERENCE_KEY);
      settings.put("password", value);
    }
  }

  /** Mutates only the caller's candidate JSON, never live settings or an existing vault entry. */
  public static Prepared prepareForSave(JSONObject candidate) {
    Prepared prepared = new Prepared(RemoteComputeConfig.credentialStore());
    prepared.prepare(candidate, false);
    return prepared;
  }

  /** Existing settings are adopted only after the same transactional file save used by editors. */
  public static boolean migrateLegacyPasswords(Config config) throws IOException {
    synchronized (Utils.class) {
      synchronized (config) {
        JSONObject candidate = new JSONObject(config.leelazConfig.toString());
        try (Prepared prepared = new Prepared(RemoteComputeConfig.credentialStore())) {
          prepared.prepare(candidate, true);
          if (!prepared.changed) return false;
          config.updateConfigSections(
              (ui, leelaz) -> {
                for (String key : List.of(ENGINE_LIST_KEY, ANALYSIS_ENGINE_KEY, CONTRIBUTE_ENGINE_KEY)) {
                  if (candidate.has(key)) leelaz.put(key, candidate.get(key));
                }
              });
          prepared.commit();
          return true;
        }
      }
    }
  }

  public static final class Prepared implements AutoCloseable {
    private final CredentialStore store;
    private final List<String> created = new ArrayList<>();
    private boolean changed;
    private boolean committed;

    private Prepared(CredentialStore store) {
      this.store = store;
    }

    private void prepare(JSONObject candidate, boolean migrateLegacy) {
      if (candidate == null) return;
      JSONArray engines = candidate.optJSONArray(ENGINE_LIST_KEY);
      if (engines != null) {
        for (int i = 0; i < engines.length(); i++)
          prepareEntry(engines.optJSONObject(i), migrateLegacy);
      }
      prepareEntry(candidate.optJSONObject(ANALYSIS_ENGINE_KEY), migrateLegacy);
      prepareEntry(candidate.optJSONObject(CONTRIBUTE_ENGINE_KEY), migrateLegacy);
    }

    private void prepareEntry(JSONObject settings, boolean migrateLegacy) {
      if (settings == null) return;
      String stored = readFrom(settings);
      if (stored.isEmpty() || stored.startsWith(VAULT_PREFIX)) return;
      boolean draft = stored.startsWith(DRAFT_PREFIX);
      if (!draft && !migrateLegacy) return;
      String legacy = draft ? stored.substring(DRAFT_PREFIX.length()) : stored;
      String reference = "kg-ssh-" + UUID.randomUUID();
      boolean writeStarted = false;
      try {
        String plaintext = decryptLegacy(legacy);
        if (store != null && store.isAvailable()) {
          writeStarted = true;
          store.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, reference, plaintext);
          Optional<String> verified =
              store.read(CredentialStore.Kind.SSH_ENGINE_PASSWORD, reference);
          if (verified.isEmpty() || !plaintext.equals(verified.get())) {
            throw new IOException("SSH credential verification failed.");
          }
          created.add(reference);
          writeTo(settings, VAULT_PREFIX + reference);
          changed = true;
          return;
        }
      } catch (IOException | RuntimeException failure) {
        if (writeStarted) discard(reference);
        LOG.warn(
            "SSH credential storage was not committed; preserving the legacy value ({})",
            failure.getClass().getSimpleName());
      }
      // Retain the existing compatibility fallback without ever persisting an internal draft
      // marker.
      if (draft) writeTo(settings, legacy);
    }

    public void commit() {
      committed = true;
    }

    @Override
    public void close() {
      if (!committed) for (String reference : created) discard(reference);
    }

    private void discard(String reference) {
      try {
        store.delete(CredentialStore.Kind.SSH_ENGINE_PASSWORD, reference);
      } catch (IOException | RuntimeException failure) {
        LOG.warn(
            "Unable to discard an uncommitted SSH password ({})",
            failure.getClass().getSimpleName());
      }
    }
  }

  private static String decryptLegacy(String value) throws IOException {
    try {
      String plaintext = Utils.doDecrypt(value);
      if (plaintext == null) throw new IOException("The saved SSH password cannot be decoded.");
      return plaintext;
    } catch (RuntimeException failure) {
      throw new IOException("The saved SSH password cannot be decoded.");
    }
  }
}
