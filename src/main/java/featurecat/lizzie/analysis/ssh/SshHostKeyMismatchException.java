package featurecat.lizzie.analysis.ssh;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The server presented a host key that differs from the one recorded for it, which means either the
 * server was reinstalled or someone is intercepting the connection. The connection is refused
 * before any password or key is sent.
 */
public final class SshHostKeyMismatchException extends IOException {
  private static final long serialVersionUID = 1L;

  private final String hostName;
  private final String expectedFingerprint;
  private final String presentedFingerprint;
  private final transient Path knownHostsFile;

  SshHostKeyMismatchException(
      String hostName,
      String expectedFingerprint,
      String presentedFingerprint,
      Path knownHostsFile,
      Throwable cause) {
    super(
        "SSH host key for "
            + hostName
            + " does not match the recorded key (recorded "
            + expectedFingerprint
            + ", presented "
            + presentedFingerprint
            + "). Recorded keys: "
            + knownHostsFile,
        cause);
    this.hostName = hostName;
    this.expectedFingerprint = expectedFingerprint;
    this.presentedFingerprint = presentedFingerprint;
    this.knownHostsFile = knownHostsFile;
  }

  /** The known_hosts name of the server, for example {@code [example.com]:2222}. */
  public String hostName() {
    return hostName;
  }

  public String expectedFingerprint() {
    return expectedFingerprint;
  }

  public String presentedFingerprint() {
    return presentedFingerprint;
  }

  public Path knownHostsFile() {
    return knownHostsFile;
  }
}
