package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.SshEnginePasswords;
import featurecat.lizzie.analysis.ssh.SshAuthenticationException;
import featurecat.lizzie.analysis.ssh.SshCommandSession;
import featurecat.lizzie.analysis.ssh.SshCredential;
import featurecat.lizzie.analysis.ssh.SshHostKeyMismatchException;
import featurecat.lizzie.analysis.ssh.SshKnownHosts;
import featurecat.lizzie.analysis.ssh.SshTarget;
import featurecat.lizzie.logging.LogCategories;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.MessageFormat;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts an engine command on a remote host over SSH and exposes its streams. Subclasses decide how
 * a rejected login or a failed connection is reported for their kind of engine.
 */
abstract class SshEngineController {
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.REMOTE);

  private final String host;
  private final int port;
  private volatile SshCommandSession session;
  private SshCommandSession.ConnectionAttempt connecting;
  private boolean closed;

  SshEngineController(String ip, String port) {
    this.host = ip == null ? "" : ip.trim();
    this.port = SshTarget.parsePort(port);
  }

  /** Logs in with a password saved by the engine settings dialog. */
  public Boolean login(String command, String userName, String password) {
    if (isClosed()) return Boolean.FALSE;
    String plaintext;
    try {
      plaintext = SshEnginePasswords.reveal(password);
    } catch (IOException e) {
      LOG.warn("Saved SSH password for {}:{} is unavailable", host, port, e);
      if (!isClosed()) onConnectionFailed(savedPasswordUnavailableMessage(e));
      return Boolean.FALSE;
    }
    return start(command, userName, SshCredential.password(plaintext));
  }

  private static String savedPasswordUnavailableMessage(IOException failure) {
    if (Lizzie.resourceBundle != null
        && Lizzie.resourceBundle.containsKey("SSHController.savedPasswordUnavailable")) {
      return Lizzie.resourceBundle.getString("SSHController.savedPasswordUnavailable");
    }
    return failure.getMessage();
  }

  /** Logs in with an unencrypted private-key file, using the configured port. */
  public Boolean loginByFileKey(String command, String userName, File keyFile) {
    if (isClosed()) return Boolean.FALSE;
    if (keyFile == null) {
      onConnectionFailed("SSH private key file is not set.");
      return Boolean.FALSE;
    }
    return start(command, userName, SshCredential.privateKeyFile(keyFile.toPath()));
  }

  private Boolean start(String command, String userName, SshCredential credential) {
    SshCommandSession.ConnectionAttempt attempt =
        new SshCommandSession.ConnectionAttempt(this::closeSession);
    synchronized (this) {
      if (closed || connecting != null || session != null) return Boolean.FALSE;
      connecting = attempt;
    }
    SshCommandSession candidate = null;
    boolean published = false;
    try {
      SshTarget target = new SshTarget(host, port, userName == null ? "" : userName);
      candidate = attempt.open(target, credential, command, knownHosts());
      synchronized (this) {
        if (closed) return Boolean.FALSE;
        session = candidate;
        connecting = null;
        published = true;
      }
      LOG.info("Started remote engine over SSH on {}", target.knownHostsName());
      return Boolean.TRUE;
    } catch (SshAuthenticationException e) {
      LOG.warn("Remote SSH engine login rejected for {}:{}", host, port);
      if (!isClosed()) onAuthenticationRejected();
      return Boolean.FALSE;
    } catch (IOException | RuntimeException e) {
      LOG.warn("Remote SSH engine connection to {}:{} failed", host, port, e);
      if (!isClosed()) onConnectionFailed(describeFailure(e));
      return Boolean.FALSE;
    } finally {
      synchronized (this) {
        if (connecting == attempt) connecting = null;
      }
      try {
        attempt.close();
      } finally {
        if (!published && candidate != null) closeSession(candidate);
      }
    }
  }

  protected final synchronized boolean isClosed() {
    return closed;
  }

  protected final void publishFailure(Runnable action) {
    Runnable publish =
        () -> {
          if (isClosed()) return;
          try {
            action.run();
          } catch (org.json.JSONException failure) {
            LOG.warn("Unable to display SSH connection failure", failure);
          }
        };
    if (SwingUtilities.isEventDispatchThread()) publish.run();
    else SwingUtilities.invokeLater(publish);
  }

  SshKnownHosts knownHosts() {
    return SshKnownHosts.forWorkDirectory();
  }

  /** The server rejected the user name, password or key. */
  protected abstract void onAuthenticationRejected();

  /** The connection could not be established or the command could not be started. */
  protected abstract void onConnectionFailed(String message);

  static String describeFailure(Exception failure) {
    if (failure instanceof SshHostKeyMismatchException mismatch
        && Lizzie.resourceBundle != null
        && Lizzie.resourceBundle.containsKey("SSHController.hostKeyChanged")) {
      return MessageFormat.format(
          Lizzie.resourceBundle.getString("SSHController.hostKeyChanged"),
          mismatch.hostName(),
          mismatch.expectedFingerprint(),
          mismatch.presentedFingerprint(),
          String.valueOf(mismatch.knownHostsFile()));
    }
    String message = failure.getLocalizedMessage();
    if (message != null && !message.isBlank()) {
      return message;
    }
    return Lizzie.resourceBundle == null
        ? failure.getClass().getSimpleName()
        : Lizzie.resourceBundle.getString("Leelaz.engineStartNoExceptionMessage");
  }

  public void close() {
    SshCommandSession current;
    SshCommandSession.ConnectionAttempt pending;
    synchronized (this) {
      if (closed) return;
      closed = true;
      current = session;
      session = null;
      pending = connecting;
      connecting = null;
    }
    try {
      if (pending != null) pending.close();
    } finally {
      if (current != null) closeSession(current);
    }
  }

  protected void closeSession(SshCommandSession current) {
    current.close();
  }

  public InputStream getStdout() {
    return requireSession().stdout();
  }

  public InputStream getSterr() {
    return requireSession().stderr();
  }

  public OutputStream getStdin() {
    return requireSession().stdin();
  }

  private SshCommandSession requireSession() {
    SshCommandSession current = session;
    if (current == null) {
      throw new IllegalStateException("SSH engine is not connected.");
    }
    return current;
  }
}
