package featurecat.lizzie.analysis.ssh;

import java.io.IOException;

/** The server accepted the connection but rejected every offered credential. */
public final class SshAuthenticationException extends IOException {
  private static final long serialVersionUID = 1L;

  SshAuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }
}
