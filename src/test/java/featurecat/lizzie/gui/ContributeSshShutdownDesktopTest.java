package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.ssh.TestSshServer;
import featurecat.lizzie.util.Utils;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Real GUI/SSH transport and children that deliberately survive stdin EOF, as KataGo does. */
public class ContributeSshShutdownDesktopTest {
  @Test
  void stopsRemoteChildrenOnUserStopFatalErrorAndRetry() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(getClass(), "contribute-ssh-stop", List.of(), List.of(), 120);
    assertEquals("passed", new JSONObject(Files.readString(result)).getString("result"));
  }

  public static void main(String[] args) throws Exception {
    if (args[0].equals("child")) {
      remoteChild(Path.of(args[1]), args[2]);
      return;
    }
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    try (SshFixture server = new SshFixture(work)) {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":2}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      ContributeStartupDesktopTest.await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
      for (String mode : List.of("stop", "clean", "fatal", "retry")) {
        int first = server.children.size();
        start(server, mode);
        ContributeStartupDesktopTest.await(() -> server.children.size() > first);
        Child child = server.children.get(first);
        ContributeStartupDesktopTest.await(() -> Files.exists(child.work.resolve("ready")));
        if (mode.equals("stop")) {
          var old = Lizzie.frame.contributeEngine;
          SwingUtilities.invokeAndWait(() -> Lizzie.frame.closeContributeEngine());
          verifyStopped(child);
          start(server, mode);
          ContributeStartupDesktopTest.await(() -> server.children.size() == first + 2);
          Child successor = server.children.get(first + 1);
          ContributeStartupDesktopTest.await(() -> Files.exists(successor.work.resolve("ready")));
          SwingUtilities.invokeAndWait(old::normalQuit);
          assertTrue(
              successor.process.isAlive(), "Old owner must not terminate the new SSH session");
          assertTrue(Lizzie.frame.isContributing);
          SwingUtilities.invokeAndWait(() -> Lizzie.frame.closeContributeEngine());
          verifyStopped(successor);
          assertEquals(first + 2, server.children.size());
        } else if (mode.equals("clean")) {
          ContributeStartupDesktopTest.await(() -> !Lizzie.frame.isContributing);
          assertTrue(child.process.waitFor(10, TimeUnit.SECONDS));
          assertEquals(0, child.process.exitValue());
          assertEquals(first + 1, server.children.size(), "Clean SSH exit must not retry");
          assertNull(ContributeStartupDesktopTest.failureWindow());
        } else {
          ContributeStartupDesktopTest.await(
              () -> ContributeStartupDesktopTest.failureWindow() != null);
          assertEquals(mode.equals("retry") ? 4 : 1, server.children.size() - first);
          for (Child remote : server.children.subList(first, server.children.size()))
            verifyStopped(remote);
          assertFalse(Lizzie.frame.isContributing);
        }
        assertNull(server.failure.get(), "SSH fixture must not fail");
      }
      SwingUtilities.invokeAndWait(
          () -> {
            Lizzie.frame.closeContributeEngine();
            for (Window window : Window.getWindows()) window.dispose();
          });
      Files.writeString(result, new JSONObject().put("result", "passed").toString());
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(
          result,
          new JSONObject().put("result", "failed").put("error", failure.toString()).toString());
      System.exit(1);
    }
    System.exit(0);
  }

  private static void start(SshFixture server, String mode) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Lizzie.frame.closeContributeEngine();
          for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
          Lizzie.config.contributeUseCommand = true;
          Lizzie.config.contributeUsePureCommand = true;
          Lizzie.config.contributeUserName = "fixture-user";
          Lizzie.config.contributeCommand = mode;
          Lizzie.config.leelazConfig.put(
              "contribute-engine-ssh-info",
              new JSONObject()
                  .put("useJavaSSH", true)
                  .put("ip", "127.0.0.1")
                  .put("port", Integer.toString(server.server.getPort()))
                  .put("userName", "fixture")
                  .put("password", Utils.doEncrypt("fixture-password")));
          Lizzie.frame.startContributeEngine();
        });
  }

  private static void verifyStopped(Child child) throws Exception {
    assertTrue(
        child.process.waitFor(10, TimeUnit.SECONDS), "SSH child must exit, not just receive EOF");
    assertEquals(0, child.process.exitValue());
    assertEquals("forcequit", Files.readString(child.work.resolve("command")));
    assertFalse(
        Files.exists(child.work.resolve("eof")), "Termination command must precede disconnect");
  }

  private static void remoteChild(Path work, String mode) throws Exception {
    Files.writeString(work.resolve("ready"), "ready");
    if (mode.equals("clean")) {
      System.out.println("All cleaned up, quitting");
      System.exit(0);
    }
    if (mode.equals("fatal")) System.out.println(ContributeStartupDesktopTest.REJECTION);
    if (mode.equals("retry")) {
      System.out.println("ERROR controlled remote failure");
      System.out.close();
    }
    var input = new BufferedReader(new InputStreamReader(System.in));
    String line;
    while ((line = input.readLine()) != null) {
      if (line.equals("forcequit")) {
        Files.writeString(work.resolve("command"), line);
        System.exit(0);
      }
    }
    // KataGo stops its input loop on EOF, but its contribution workers continue running.
    Files.writeString(work.resolve("eof"), "eof");
    while (true) Thread.sleep(1000);
  }

  private record Child(Process process, Path work) {}

  private static final class SshFixture implements AutoCloseable {
    final SshServer server = SshServer.setUpDefaultServer();
    final List<Child> children = new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final java.util.concurrent.ExecutorService workers =
        Executors.newCachedThreadPool(
            r -> {
              Thread thread = new Thread(r, "contribution-ssh-fixture");
              thread.setDaemon(true);
              return thread;
            });
    final Path work;

    SshFixture(Path work) throws IOException {
      this.work = work;
      server.setHost("127.0.0.1");
      server.setPort(0);
      server.setKeyPairProvider(KeyPairProvider.wrap(TestSshServer.ecKeyPair()));
      server.setPasswordAuthenticator(
          (user, password, session) ->
              user.equals("fixture") && password.equals("fixture-password"));
      server.setCommandFactory((channel, command) -> new RemoteCommand(command));
      server.start();
    }

    private final class RemoteCommand implements Command {
      private final String command;
      private InputStream input;
      private OutputStream output;
      private ExitCallback exit;

      private RemoteCommand(String command) {
        this.command = command;
      }

      @Override
      public void setInputStream(InputStream input) {
        this.input = input;
      }

      @Override
      public void setOutputStream(OutputStream output) {
        this.output = output;
      }

      @Override
      public void setErrorStream(OutputStream error) {}

      @Override
      public void setExitCallback(ExitCallback exit) {
        this.exit = exit;
      }

      @Override
      public void start(ChannelSession channel, org.apache.sshd.server.Environment environment)
          throws IOException {
        try {
          if (!List.of("stop", "clean", "fatal", "retry").contains(command))
            throw new IOException("Unexpected fixture command");
          Path run = Files.createTempDirectory(work, "ssh-child-");
          List<String> args =
              List.of(
                  Path.of(
                          System.getProperty("java.home"),
                          "bin",
                          System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                      .toString(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  ContributeSshShutdownDesktopTest.class.getName(),
                  "child",
                  run.toString(),
                  command);
          Process process =
              new ProcessBuilder(args).redirectError(run.resolve("stderr.log").toFile()).start();
          children.add(new Child(process, run));
          CountDownLatch stdoutDrained = new CountDownLatch(1);
          workers.execute(
              () -> {
                try (var remoteInput = process.getOutputStream()) {
                  byte[] buffer = new byte[1024];
                  int count;
                  while ((count = input.read(buffer)) != -1) {
                    remoteInput.write(buffer, 0, count);
                    remoteInput.flush();
                  }
                } catch (IOException closed) {
                  // Channel closure delivers EOF; it must not kill the child for the client.
                }
              });
          workers.execute(
              () -> {
                try (var remoteOutput = output) {
                  process.getInputStream().transferTo(remoteOutput);
                } catch (IOException closed) {
                  // The client may close its channel after sending forcequit.
                } finally {
                  stdoutDrained.countDown();
                }
              });
          workers.execute(
              () -> {
                try {
                  int status = process.waitFor();
                  // Preserve the final clean-exit message before closing the SSH channel.
                  if (!stdoutDrained.await(10, TimeUnit.SECONDS)) {
                    failure.compareAndSet(
                        null, new IOException("SSH fixture did not drain child stdout"));
                  }
                  exit.onExit(status);
                } catch (InterruptedException interrupted) {
                  failure.compareAndSet(null, interrupted);
                  Thread.currentThread().interrupt();
                }
              });
        } catch (IOException | RuntimeException error) {
          failure.compareAndSet(null, error);
          throw error;
        }
      }

      @Override
      public void destroy(ChannelSession channel) {
        try {
          input.close();
        } catch (IOException closed) {
          // The child deliberately survives EOF; only fixture cleanup may force it to exit.
        }
      }
    }

    public void close() throws Exception {
      server.stop(true);
      for (Child child : children) {
        if (child.process.isAlive()) child.process.destroyForcibly();
        assertTrue(child.process.waitFor(10, TimeUnit.SECONDS), "Fixture child cleanup");
      }
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Fixture worker cleanup");
    }
  }
}
