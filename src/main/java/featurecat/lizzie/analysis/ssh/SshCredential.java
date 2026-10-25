package featurecat.lizzie.analysis.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;

/** A password or an unencrypted private-key file used to log in to an SSH server. */
public abstract sealed class SshCredential {
  private SshCredential() {}

  public static SshCredential password(String password) {
    return new Password(password);
  }

  public static SshCredential privateKeyFile(Path keyFile) {
    return new PrivateKeyFile(keyFile);
  }

  abstract void addTo(ClientSession session) throws IOException;

  static final class Password extends SshCredential {
    private final String password;

    private Password(String password) {
      this.password = password == null ? "" : password;
    }

    @Override
    void addTo(ClientSession session) {
      session.addPasswordIdentity(password);
    }
  }

  static final class PrivateKeyFile extends SshCredential {
    private final Path keyFile;

    private PrivateKeyFile(Path keyFile) {
      this.keyFile = Objects.requireNonNull(keyFile, "keyFile");
    }

    @Override
    void addTo(ClientSession session) throws IOException {
      for (KeyPair keyPair : load(session)) {
        session.addPublicKeyIdentity(keyPair);
      }
    }

    private List<KeyPair> load(ClientSession session) throws IOException {
      if (!Files.isRegularFile(keyFile)) {
        throw new IOException("SSH private key file not found: " + keyFile);
      }
      List<KeyPair> keyPairs = new ArrayList<>();
      try (InputStream input = Files.newInputStream(keyFile)) {
        Iterable<KeyPair> loaded =
            SecurityUtils.loadKeyPairIdentities(
                session,
                NamedResource.ofName(keyFile.toString()),
                input,
                FilePasswordProvider.EMPTY);
        if (loaded != null) {
          loaded.forEach(keyPairs::add);
        }
      } catch (GeneralSecurityException | RuntimeException e) {
        throw new IOException(
            "Unable to read SSH private key " + keyFile.getFileName() + ": " + e.getMessage(), e);
      }
      if (keyPairs.isEmpty()) {
        throw new IOException("No usable SSH private key in " + keyFile.getFileName());
      }
      return keyPairs;
    }
  }
}
