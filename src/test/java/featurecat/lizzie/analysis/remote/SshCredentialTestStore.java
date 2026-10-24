package featurecat.lizzie.analysis.remote;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Isolated credential backend shared by SSH persistence and dialog regression fixtures. */
public final class SshCredentialTestStore implements CredentialStore {
  public final Map<String, String> values = new HashMap<>();
  public boolean available = true;
  public boolean failWrites;
  public boolean failReads;
  public boolean corruptReads;
  public int writes;

  public void install() {
    RemoteComputeConfig.setCredentialStoreForTests(this);
  }

  public static void reset() {
    RemoteComputeConfig.resetCredentialStateForTests();
  }

  @Override
  public String backendName() {
    return "test-ssh-vault";
  }

  @Override
  public boolean isAvailable() {
    return available;
  }

  @Override
  public Optional<String> read(Kind kind, String account) throws IOException {
    if (failReads) throw new IOException("Injected credential read failure");
    String value = values.get(kind + ":" + account);
    return Optional.ofNullable(value == null ? null : corruptReads ? value.stripTrailing() : value);
  }

  @Override
  public void write(Kind kind, String account, String secret) throws IOException {
    writes++;
    values.put(kind + ":" + account, secret);
    if (failWrites) throw new IOException("Injected failure after credential write");
  }

  @Override
  public void delete(Kind kind, String account) {
    values.remove(kind + ":" + account);
  }
}
