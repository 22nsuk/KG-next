package featurecat.lizzie.analysis.ssh;

import featurecat.lizzie.logging.LogCategories;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.kex.KeyExchangeFactory;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.signature.Signature;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.core.CoreModuleProperties;
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

  /**
   * Runs a finite command, collecting both output streams as packets arrive. The command timeout
   * starts after the bounded connection/authentication/channel-open phases. Output ends at remote
   * EOF or channel close, never at exit status alone. Each stream has its own byte limit.
   */
  public static CommandOutput execute(
      SshTarget target,
      SshCredential credential,
      String command,
      SshKnownHosts knownHosts,
      Duration timeout,
      int maxOutputBytes)
      throws IOException {
    if (timeout.isNegative() || timeout.isZero() || maxOutputBytes <= 0) {
      throw new IllegalArgumentException("Command timeout and output limit must be positive.");
    }
    LimitedOutput stdout = new LimitedOutput(maxOutputBytes);
    LimitedOutput stderr = new LimitedOutput(maxOutputBytes);
    try (ConnectionAttempt attempt = new ConnectionAttempt();
        SshCommandSession running =
            attempt.open(target, credential, command, knownHosts, stdout, stderr)) {
      running.stdin().close();
      CountDownLatch completed = new CountDownLatch(1);
      running.channel.addCloseFutureListener(ignored -> completed.countDown());
      running.channel.addChannelListener(
          new ChannelListener() {
            @Override
            public void channelStateChanged(Channel changed, String hint) {
              if (changed.isEofSignalled()) completed.countDown();
            }
          });
      // EOF may have arrived before the listener was registered, including during exec open.
      if (running.channel.isEofSignalled()) completed.countDown();
      if (!completed.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
        throw new SocketTimeoutException("SSH command timed out.");
      }
      return new CommandOutput(stdout.text(), stderr.text());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      InterruptedIOException failure = new InterruptedIOException("SSH command interrupted.");
      failure.initCause(interrupted);
      throw failure;
    } catch (IOException failure) {
      // An output-limit failure can also close the channel while exec is still opening.
      stdout.checkLimit();
      stderr.checkLimit();
      throw failure;
    }
  }

  public record CommandOutput(String stdout, String stderr) {}

  private static final class LimitedOutput extends OutputStream {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private boolean exceeded;

    private LimitedOutput(int limit) {
      this.limit = limit;
    }

    @Override
    public synchronized void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] data, int offset, int length) throws IOException {
      if (length > limit - bytes.size()) exceeded = true;
      checkLimit();
      bytes.write(data, offset, length);
    }

    private synchronized void checkLimit() throws IOException {
      if (exceeded) throw new IOException("SSH command output is too large.");
    }

    private synchronized String text() throws IOException {
      checkLimit();
      return bytes.toString(StandardCharsets.UTF_8);
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
      return open(target, credential, command, knownHosts, null, null);
    }

    private SshCommandSession open(
        SshTarget target,
        SshCredential credential,
        String command,
        SshKnownHosts knownHosts,
        OutputStream stdout,
        OutputStream stderr)
        throws IOException {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(credential, "credential");
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(knownHosts, "knownHosts");
      SshKnownHosts.Check hostKeyCheck = knownHosts.newCheck(target);
      SshClient connecting = SshClient.setUpDefaultClient();
      // Paused engines may be silent indefinitely; their owner decides when to close them.
      CoreModuleProperties.IDLE_TIMEOUT.set(connecting, Duration.ZERO);
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
          if (stdout == null) {
            forwardOutputEof(channel);
          } else {
            channel.setOut(stdout);
            channel.setErr(stderr);
          }
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

  private static void forwardOutputEof(ChannelExec channel) {
    channel.addChannelListener(
        new ChannelListener() {
          @Override
          public void channelStateChanged(Channel changed, String hint) {
            if (changed.isEofSignalled()) {
              // MINA's synchronous pipes otherwise wait for channel close after remote EOF.
              // Close the writers so buffered output drains, keeping stdin open for forcequit.
              IoUtils.closeQuietly(channel.getOut(), channel.getErr());
            }
          }
        });
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

  /**
   * Waits for exit status or channel close, which need not include output EOF. Use {@link #execute}
   * for a finite command whose complete response must be collected within a deadline.
   */
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
