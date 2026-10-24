package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.ssh.SshKnownHosts;
import featurecat.lizzie.analysis.ssh.TestSshServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class ZhiziServerCatalogSshTest {
  @TempDir Path temp;
  private static final Duration QUERY_TIMEOUT = Duration.ofMillis(300);

  enum Incomplete {
    EXIT_STATUS_ONLY,
    SILENT,
    CONTINUOUS_OUTPUT
  }

  @ParameterizedTest
  @EnumSource(Incomplete.class)
  void incompleteCommandsTimeOutAndReleaseTheirChannel(Incomplete mode) throws Exception {
    Script command =
        new Script() {
          @Override
          void run(ChannelSession channel) throws Exception {
            switch (mode) {
              case EXIT_STATUS_ONLY -> sendExitStatus(channel);
              case SILENT -> {}
              case CONTINUOUS_OUTPUT -> {
                while (!destroyed.await(30, TimeUnit.MILLISECONDS)) {
                  out.write('x');
                  out.flush();
                }
              }
            }
          }
        };
    try (TestSshServer server = server(command)) {
      IOException failure =
          assertTimeoutPreemptively(
              Duration.ofSeconds(8),
              () -> assertThrows(IOException.class, () -> query(server, QUERY_TIMEOUT)));
      assertEquals("iKataGo catalog query timed out.", failure.getMessage());
      assertTrue(command.destroyed.await(5, TimeUnit.SECONDS), "timed-out channel is retired");
    }
  }

  @Test
  void statusBeforeOutputDoesNotTruncateTheResponse() throws Exception {
    String response = "{\"servers\":[]}\n";
    Script command =
        new Script() {
          @Override
          void run(ChannelSession channel) throws Exception {
            sendExitStatus(channel);
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
            sendEof(channel);
            channel.close(false);
          }
        };
    try (TestSshServer server = server(command)) {
      assertEquals(response, query(server, Duration.ofSeconds(5)));
      assertTrue(command.destroyed.await(5, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completeOutputDoesNotRequireExitStatus(boolean eofOnly) throws Exception {
    String response = "{\"servers\":[]}\n";
    Script command =
        new Script() {
          @Override
          void run(ChannelSession channel) throws Exception {
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (eofOnly) sendEof(channel);
            else channel.close(false);
          }
        };
    try (TestSshServer server = server(command)) {
      assertEquals(response, query(server, Duration.ofSeconds(5)));
      assertTrue(command.destroyed.await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void collectsBothStreamsUpToTheByteLimitWithoutWaitingForReaders() throws Exception {
    String response = "한".repeat(174762) + "ok"; // Exactly 512 KiB in UTF-8.
    Script command =
        new Script() {
          @Override
          void run(ChannelSession channel) throws Exception {
            err.write(new byte[512 * 1024]);
            err.flush();
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
            exit.onExit(0);
          }
        };
    try (TestSshServer server = server(command)) {
      assertEquals(response, query(server, Duration.ofSeconds(5)));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsOversizedOutputOnEitherStream(boolean stderr) throws Exception {
    Script command =
        new Script() {
          @Override
          void run(ChannelSession channel) throws Exception {
            OutputStream stream = stderr ? err : out;
            stream.write(new byte[512 * 1024 + 1]);
            stream.flush();
            exit.onExit(0);
          }
        };
    try (TestSshServer server = server(command)) {
      IOException failure =
          assertThrows(IOException.class, () -> query(server, Duration.ofSeconds(5)));
      assertTrue(failure.getMessage().contains("too large"), failure.toString());
      assertTrue(command.destroyed.await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void emptyResponseStillReportsTheRemoteError() throws Exception {
    Script command =
        new Script() {
          @Override
          void run(ChannelSession channel) throws Exception {
            err.write("catalog unavailable\n".getBytes(StandardCharsets.UTF_8));
            err.flush();
            exit.onExit(1);
          }
        };
    try (TestSshServer server = server(command)) {
      IOException failure =
          assertThrows(IOException.class, () -> query(server, Duration.ofSeconds(5)));
      assertEquals("catalog unavailable", failure.getMessage());
    }
  }

  @Test
  void interruptedQueryReleasesItsChannel() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    Script command =
        new Script() {
          @Override
          void run(ChannelSession channel) {
            started.countDown();
          }
        };
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (TestSshServer server = server(command)) {
      Thread caller =
          new Thread(
              () -> {
                try {
                  query(server, Duration.ofMinutes(1));
                } catch (Throwable error) {
                  failure.set(error);
                }
              },
              "catalog-query-test");
      caller.setDaemon(true);
      caller.start();
      try {
        assertTrue(started.await(5, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(5000);
        assertTrue(!caller.isAlive(), "interruption must not wait for the command deadline");
        assertTrue(failure.get() instanceof IOException, String.valueOf(failure.get()));
        assertTrue(command.destroyed.await(5, TimeUnit.SECONDS));
      } finally {
        caller.interrupt();
      }
    }
  }

  private TestSshServer server(Script command) throws IOException {
    return TestSshServer.builder()
        .password("secret")
        .commandFactory(
            (channel, text) -> {
              assertEquals("query-server", text);
              return command;
            })
        .start();
  }

  private String query(TestSshServer server, Duration timeout) throws IOException {
    return ZhiziServerCatalogClient.queryOverSsh(
        "127.0.0.1",
        server.port(),
        "player",
        "secret",
        "query-server",
        new SshKnownHosts(temp.resolve("known_hosts")),
        timeout);
  }

  private static void sendExitStatus(ChannelSession channel) throws IOException {
    Buffer packet = channel.getSession().createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST);
    packet.putUInt(channel.getRecipient());
    packet.putString("exit-status");
    packet.putBoolean(false);
    packet.putInt(0);
    channel.writePacket(packet);
  }

  private static void sendEof(ChannelSession channel) throws IOException {
    Buffer packet = channel.getSession().createBuffer(SshConstants.SSH_MSG_CHANNEL_EOF);
    packet.putUInt(channel.getRecipient());
    channel.writePacket(packet);
  }

  private abstract static class Script implements Command {
    OutputStream out;
    OutputStream err;
    ExitCallback exit;
    final CountDownLatch destroyed = new CountDownLatch(1);
    private Thread worker;

    abstract void run(ChannelSession channel) throws Exception;

    @Override
    public void setInputStream(InputStream input) {}

    @Override
    public void setOutputStream(OutputStream output) {
      out = output;
    }

    @Override
    public void setErrorStream(OutputStream error) {
      err = error;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
      exit = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment environment) {
      worker =
          new Thread(
              () -> {
                try {
                  run(channel);
                } catch (Exception failure) {
                  exit.onExit(1, failure.toString());
                }
              },
              "catalog-ssh-test-command");
      worker.setDaemon(true);
      worker.start();
    }

    @Override
    public void destroy(ChannelSession channel) {
      destroyed.countDown();
      if (worker != null) worker.interrupt();
    }
  }
}
