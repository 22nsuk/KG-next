package featurecat.lizzie.analysis.ssh;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.ChannelOutputStream;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.channel.ChannelSessionFactory;
import org.apache.sshd.server.command.Command;
import org.slf4j.LoggerFactory;

/**
 * Loopback SSH server for tests. Commands answer a tiny GTP subset on stdin/stdout, except {@code
 * query-server}, which prints the configured catalog response and exits, and {@code
 * eof-output-wait-forcequit}, which ends its output while continuing to accept input.
 */
public final class TestSshServer implements AutoCloseable {
  static {
    // MINA logs every packet at DEBUG; keep test output readable.
    org.slf4j.Logger sshd = org.slf4j.LoggerFactory.getLogger("org.apache.sshd");
    if (sshd instanceof ch.qos.logback.classic.Logger logback) {
      logback.setLevel(ch.qos.logback.classic.Level.WARN);
    }
  }

  public final AtomicInteger passwordAttempts = new AtomicInteger();
  public final AtomicInteger publicKeyAttempts = new AtomicInteger();
  public final List<String> commands = new CopyOnWriteArrayList<>();
  public final List<String> inputCommands = new CopyOnWriteArrayList<>();
  public final CountDownLatch forcequitReceived = new CountDownLatch(1);

  private final SshServer server;
  private final KeyPair hostKey;
  private final String catalogResponse;

  private TestSshServer(Builder builder) throws IOException {
    this.hostKey = builder.hostKey;
    this.catalogResponse = builder.catalogResponse;
    server = SshServer.setUpDefaultServer();
    server.setHost("127.0.0.1");
    server.setPort(0);
    server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
    server.setChannelFactories(
        List.of(
            new ChannelSessionFactory() {
              @Override
              public org.apache.sshd.common.channel.Channel createChannel(
                  org.apache.sshd.common.session.Session session) throws IOException {
                builder.beforeChannelOpen.run();
                return super.createChannel(session);
              }
            }));
    if (builder.legacyOnly) {
      // What an SSH server that only ever talked to ganymed-ssh2 negotiates.
      server.setKeyExchangeFactories(
          List.of(
              ServerBuilder.DH2KEX.apply(BuiltinDHFactories.dhg14),
              ServerBuilder.DH2KEX.apply(BuiltinDHFactories.dhgex)));
      server.setSignatureFactories(List.of(BuiltinSignatures.rsa));
    }
    String password = builder.password;
    PublicKey acceptedKey = builder.acceptedClientKey;
    server.setPasswordAuthenticator(
        (username, offered, session) -> {
          passwordAttempts.incrementAndGet();
          builder.beforePasswordAuthentication.run();
          return password != null && password.equals(offered);
        });
    server.setPublickeyAuthenticator(
        (username, offered, session) -> {
          publicKeyAttempts.incrementAndGet();
          return acceptedKey != null && acceptedKey.equals(offered);
        });
    server.setCommandFactory(
        (channel, command) -> {
          commands.add(command);
          return new ScriptedCommand(command);
        });
    server.start();
  }

  public static Builder builder() {
    return new Builder();
  }

  public int port() {
    return server.getPort();
  }

  public KeyPair hostKey() {
    return hostKey;
  }

  public SshTarget target(String username) {
    return new SshTarget("127.0.0.1", port(), username);
  }

  @Override
  public void close() throws IOException {
    server.stop(true);
  }

  public static KeyPair ecKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static KeyPair rsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static final class Builder {
    private KeyPair hostKey = ecKeyPair();
    private String password;
    private PublicKey acceptedClientKey;
    private boolean legacyOnly;
    private String catalogResponse = "";
    private Runnable beforePasswordAuthentication = () -> {};
    private Runnable beforeChannelOpen = () -> {};

    public Builder hostKey(KeyPair hostKey) {
      this.hostKey = hostKey;
      return this;
    }

    public Builder beforeChannelOpen(Runnable action) {
      this.beforeChannelOpen = action;
      return this;
    }

    public Builder beforePasswordAuthentication(Runnable action) {
      this.beforePasswordAuthentication = action;
      return this;
    }

    public Builder password(String password) {
      this.password = password;
      return this;
    }

    public Builder acceptedClientKey(PublicKey key) {
      this.acceptedClientKey = key;
      return this;
    }

    /** Only SHA-1 key exchange and {@code ssh-rsa} host-key signatures; needs an RSA host key. */
    public Builder legacyAlgorithmsOnly() {
      this.legacyOnly = true;
      this.hostKey = rsaKeyPair();
      return this;
    }

    public Builder catalogResponse(String response) {
      this.catalogResponse = response;
      return this;
    }

    public TestSshServer start() throws IOException {
      return new TestSshServer(this);
    }
  }

  private final class ScriptedCommand implements Command {
    private final String command;
    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exit;
    private Thread worker;

    private ScriptedCommand(String command) {
      this.command = command;
    }

    @Override
    public void setInputStream(InputStream in) {
      this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
      this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
      this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
      this.exit = callback;
    }

    @Override
    public void start(ChannelSession channel, org.apache.sshd.server.Environment env)
        throws IOException {
      err.write(("started " + command + "\n").getBytes(StandardCharsets.UTF_8));
      err.flush();
      if ("query-server".equals(command)) {
        out.write(catalogResponse.getBytes(StandardCharsets.UTF_8));
        out.flush();
        exit.onExit(0);
        return;
      }
      if ("eof-output-wait-forcequit".equals(command)) {
        // MINA's supplied stdout does not send SSH EOF when closed. End only the server's output
        // direction so the client must still send forcequit through the live input channel.
        try (OutputStream output =
            new ChannelOutputStream(
                channel,
                channel.getRemoteWindow(),
                LoggerFactory.getLogger(TestSshServer.class),
                SshConstants.SSH_MSG_CHANNEL_DATA,
                true)) {
          output.write("output before EOF\n".repeat(200).getBytes(StandardCharsets.UTF_8));
        }
      }
      worker = new Thread(this::answerGtp, "test-ssh-gtp");
      worker.setDaemon(true);
      worker.start();
    }

    private void answerGtp() {
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          inputCommands.add(line.trim());
          if ("forcequit".equals(line.trim())) {
            forcequitReceived.countDown();
            break;
          }
          String reply =
              switch (line.trim()) {
                case "name" -> "= test-engine\n\n";
                case "quit" -> "= \n\n";
                default -> "? unknown command\n\n";
              };
          out.write(reply.getBytes(StandardCharsets.UTF_8));
          out.flush();
          if ("quit".equals(line.trim())) {
            break;
          }
        }
        exit.onExit(0);
      } catch (IOException e) {
        exit.onExit(1, e.getMessage());
      }
    }

    @Override
    public void destroy(ChannelSession channel) {
      if (worker != null) {
        worker.interrupt();
      }
    }
  }
}
