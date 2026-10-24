package featurecat.lizzie.analysis.ssh;

import featurecat.lizzie.logging.LogCategories;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.kex.KeyExchangeFactory;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.signature.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One remote command started over SSH, exposing its stdin, stdout and stderr like a local {@link
 * Process}. Every connection is checked against {@link SshKnownHosts} before credentials are sent.
 */
public final class SshCommandSession implements AutoCloseable {
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  static final Duration AUTH_TIMEOUT = Duration.ofSeconds(20);
  static final Duration CHANNEL_OPEN_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(3);

  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.REMOTE);

  private final SshClient client;
  private final ClientSession session;
  private final ChannelExec channel;
  private final Object closeLock = new Object();
  private boolean closed;

  private SshCommandSession(SshClient client, ClientSession session, ChannelExec channel) {
    this.client = client;
    this.session = session;
    this.channel = channel;
  }

  /** Connects with the work directory's known_hosts file and starts {@code command}. */
  public static SshCommandSession open(SshTarget target, SshCredential credential, String command)
      throws IOException {
    return open(target, credential, command, SshKnownHosts.forWorkDirectory());
  }

  public static SshCommandSession open(
      SshTarget target, SshCredential credential, String command, SshKnownHosts knownHosts)
      throws IOException {
    try (ConnectionAttempt attempt = new ConnectionAttempt()) {
      return attempt.open(target, credential, command, knownHosts);
    }
  }

  /** Owns a connection while it is starting, so its caller can cancel before publication. */
  public static final class ConnectionAttempt implements AutoCloseable {
    private SshClient client;
    private boolean started;
    private boolean closed;
    private boolean commandStarting;
    private final Consumer<SshCommandSession> retire;

    public ConnectionAttempt() {
      this(SshCommandSession::close);
    }

    public ConnectionAttempt(Consumer<SshCommandSession> retire) {
      this.retire = Objects.requireNonNull(retire, "retire");
    }

    public SshCommandSession open(
        SshTarget target, SshCredential credential, String command, SshKnownHosts knownHosts)
        throws IOException {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(credential, "credential");
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(knownHosts, "knownHosts");
      SshKnownHosts.Check hostKeyCheck = knownHosts.newCheck(target);
      SshClient connecting = SshClient.setUpDefaultClient();
      // Offer only this engine's configured identity, never ~/.ssh keys or host aliases.
      connecting.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
      connecting.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
      connecting.setKeyExchangeFactories(
          withLegacyKeyExchangeFallbacks(connecting.getKeyExchangeFactories()));
      connecting.setSignatureFactories(
          preferRecordedKeyTypes(
              connecting.getSignatureFactories(), knownHosts.recordedKeyTypes(target)));
      connecting.setServerKeyVerifier(hostKeyCheck.verifier());
      ClientSession connected = null;
      ChannelExec channel = null;
      boolean transferred = false;
      try {
        synchronized (this) {
          if (closed) throw new InterruptedIOException("SSH connection was cancelled.");
          if (started) throw new IllegalStateException("SSH connection attempt was already used.");
          started = true;
          client = connecting;
          connecting.start();
        }
        connected =
            connecting
                .connect(target.username(), target.host(), target.port())
                .verify(CONNECT_TIMEOUT)
                .getClientSession();
        credential.addTo(connected);
        authenticate(connected, hostKeyCheck);
        org.apache.sshd.client.future.OpenFuture opening;
        synchronized (this) {
          if (closed) throw new InterruptedIOException("SSH connection was cancelled.");
          channel = connected.createExecChannel(command);
          opening = channel.open();
          commandStarting = true;
        }
        opening.verify(CHANNEL_OPEN_TIMEOUT);
        SshCommandSession result = new SshCommandSession(connecting, connected, channel);
        synchronized (this) {
          if (!closed) {
            client = null;
            transferred = true;
            return result;
          }
        }
        // A contribute command may already own workers. Let its owner send forcequit before EOF.
        retire.accept(result);
        throw new InterruptedIOException("SSH connection was cancelled.");
      } catch (IOException | RuntimeException error) {
        if (hostKeyCheck.rejected()) throw hostKeyCheck.rejection(error);
        throw error;
      } finally {
        if (!transferred) {
          synchronized (this) {
            if (client == connecting) client = null;
          }
          closeQuietly(channel, connected, connecting);
        }
      }
    }

    @Override
    public void close() {
      SshClient connecting;
      synchronized (this) {
        closed = true;
        // Once exec was sent, await its bounded open result so an owned process can be stopped
        // through its input before disconnecting. Before exec, cancel the transport immediately.
        connecting = commandStarting ? null : client;
        if (!commandStarting) client = null;
      }
      if (connecting != null) connecting.stop();
    }
  }

  private static void authenticate(ClientSession session, SshKnownHosts.Check hostKeyCheck)
      throws IOException {
    AuthFuture auth = session.auth();
    if (!auth.await(AUTH_TIMEOUT)) {
      throw new IOException("SSH authentication timed out after " + AUTH_TIMEOUT.toSeconds() + "s");
    }
    if (hostKeyCheck.rejected()) {
      throw hostKeyCheck.rejection(auth.getException());
    }
    if (auth.isSuccess()) {
      return;
    }
    Throwable failure = auth.getException();
    if (session.isAuthenticated()) {
      return;
    }
    if (failure == null || session.isOpen()) {
      throw new SshAuthenticationException(
          "SSH server rejected the credentials for " + session.getUsername(), failure);
    }
    throw failure instanceof IOException
        ? (IOException) failure
        : new IOException("SSH connection failed: " + failure.getMessage(), failure);
  }

  /**
   * MINA's defaults are modern-only. The previous client (ganymed-ssh2) could only use SHA-1 key
   * exchange, so servers configured for it keep working through these lowest-priority fallbacks.
   */
  static List<KeyExchangeFactory> withLegacyKeyExchangeFallbacks(
      List<KeyExchangeFactory> defaults) {
    List<KeyExchangeFactory> factories = new ArrayList<>(defaults);
    for (BuiltinDHFactories legacy : List.of(BuiltinDHFactories.dhg14, BuiltinDHFactories.dhgex)) {
      boolean present =
          factories.stream().anyMatch(factory -> factory.getName().equals(legacy.getName()));
      if (!present && legacy.isSupported()) {
        factories.add(ClientBuilder.DH2KEX.apply(legacy));
      }
    }
    return factories;
  }

  /** Asks the server for a recorded key type first so a known host is not falsely refused. */
  static List<NamedFactory<Signature>> preferRecordedKeyTypes(
      List<NamedFactory<Signature>> defaults, List<String> recordedKeyTypes) {
    if (recordedKeyTypes.isEmpty()) {
      return defaults;
    }
    List<NamedFactory<Signature>> preferred = new ArrayList<>();
    for (String keyType : recordedKeyTypes) {
      Set<String> equivalents = new HashSet<>(KeyUtils.getAllEquivalentKeyTypes(keyType));
      equivalents.add(keyType);
      // Keep the default order inside a key type, so rsa-sha2-512 still beats ssh-rsa.
      for (NamedFactory<Signature> factory : defaults) {
        if (equivalents.contains(factory.getName()) && !preferred.contains(factory)) {
          preferred.add(factory);
        }
      }
    }
    for (NamedFactory<Signature> factory : defaults) {
      if (!preferred.contains(factory)) {
        preferred.add(factory);
      }
    }
    return preferred;
  }

  public InputStream stdout() {
    return channel.getInvertedOut();
  }

  public InputStream stderr() {
    return channel.getInvertedErr();
  }

  public OutputStream stdin() {
    return channel.getInvertedIn();
  }

  /** Waits until the remote command exits or closes its channel. */
  public boolean awaitCompletion(Duration timeout) {
    Set<ClientChannelEvent> events =
        channel.waitFor(
            EnumSet.of(ClientChannelEvent.CLOSED, ClientChannelEvent.EXIT_STATUS),
            timeout.toMillis());
    return !events.contains(ClientChannelEvent.TIMEOUT);
  }

  public boolean isOpen() {
    return channel.isOpen() && session.isOpen();
  }

  @Override
  public void close() {
    synchronized (closeLock) {
      if (closed) {
        return;
      }
      closed = true;
    }
    closeQuietly(channel, session, client);
  }

  private static void closeQuietly(ChannelExec channel, ClientSession session, SshClient client) {
    try {
      if (channel != null) {
        channel.close(false).await(CLOSE_TIMEOUT);
      }
    } catch (IOException | RuntimeException e) {
      LOG.debug("Unable to close SSH channel cleanly", e);
    }
    try {
      if (session != null) {
        session.close(false).await(CLOSE_TIMEOUT);
      }
    } catch (IOException | RuntimeException e) {
      LOG.debug("Unable to close SSH session cleanly", e);
    }
    try {
      client.stop();
    } catch (RuntimeException e) {
      LOG.debug("Unable to stop SSH client cleanly", e);
    }
  }
}
