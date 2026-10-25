package featurecat.lizzie.analysis.ssh;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class SshKnownHostsTest {
  @TempDir Path temp;

  @Test
  void failedFirstKeyRecordingRefusesAuthenticationAndPreservesExistingRecords() throws Exception {
    Path file = temp.resolve("known_hosts");
    String original =
        "other.example " + PublicKeyEntry.toString(TestSshServer.ecKeyPair().getPublic()) + "\n";
    Files.writeString(file, original);
    SshKnownHosts hosts =
        new SshKnownHosts(
            file,
            (target, contents) -> {
              throw new IOException("controlled trust-record failure");
            });
    try (TestSshServer server = TestSshServer.builder().password("secret").start()) {
      IOException failure =
          assertThrows(
              IOException.class,
              () ->
                  SshCommandSession.open(
                      server.target("player"), SshCredential.password("secret"), "gtp", hosts));
      assertTrue(
          failure.getMessage().contains("controlled trust-record failure"), failure.toString());
      assertEquals(0, server.passwordAttempts.get());
      assertEquals(0, server.publicKeyAttempts.get());
      assertTrue(server.commands.isEmpty());
      assertEquals(original, Files.readString(file));
    }
  }

  @Test
  void directoryInsteadOfKnownHostsIsAnErrorRatherThanANewHost() throws Exception {
    Path file = Files.createDirectory(temp.resolve("known_hosts"));
    try (TestSshServer server = TestSshServer.builder().password("secret").start()) {
      assertThrows(
          IOException.class,
          () ->
              SshCommandSession.open(
                  server.target("player"),
                  SshCredential.password("secret"),
                  "gtp",
                  new SshKnownHosts(file)));
      assertEquals(0, server.passwordAttempts.get());
      assertTrue(server.commands.isEmpty());
      assertTrue(Files.isDirectory(file));
    }
  }

  @Test
  void malformedTrustRecordCannotBecomeFirstUse() throws Exception {
    Path file = temp.resolve("known_hosts");
    String malformed = "[127.0.0.1]:2222 broken-record\n";
    Files.writeString(file, malformed);
    try (TestSshServer server = TestSshServer.builder().password("secret").start()) {
      assertThrows(
          IOException.class,
          () ->
              SshCommandSession.open(
                  server.target("player"),
                  SshCredential.password("secret"),
                  "gtp",
                  new SshKnownHosts(file)));
      assertEquals(0, server.passwordAttempts.get());
      assertEquals(malformed, Files.readString(file));
    }
  }

  @Test
  void matchingRevokedKeyIsNeverAccepted() throws Exception {
    Path file = temp.resolve("known_hosts");
    try (TestSshServer server = TestSshServer.builder().password("secret").start()) {
      String record =
          "@revoked [127.0.0.1]:"
              + server.port()
              + " "
              + PublicKeyEntry.toString(server.hostKey().getPublic())
              + "\n";
      Files.writeString(file, record);
      assertThrows(
          IOException.class,
          () ->
              SshCommandSession.open(
                  server.target("player"),
                  SshCredential.password("secret"),
                  "gtp",
                  new SshKnownHosts(file)));
      assertEquals(0, server.passwordAttempts.get());
      assertEquals(record, Files.readString(file));
    }
  }

  @Test
  void simultaneousFirstKeysCannotBothBeTrusted() throws Exception {
    Path file = temp.resolve("known_hosts");
    SshTarget target = new SshTarget("example.test", 2222, "player");
    KeyPair first = TestSshServer.ecKeyPair();
    KeyPair second = TestSshServer.ecKeyPair();
    var firstCheck = new SshKnownHosts(file).newCheck(target);
    var secondCheck = new SshKnownHosts(file).newCheck(target);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var left =
          workers.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return firstCheck
                    .verifier()
                    .verifyServerKey(null, new InetSocketAddress(2222), first.getPublic());
              });
      var right =
          workers.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return secondCheck
                    .verifier()
                    .verifyServerKey(null, new InetSocketAddress(2222), second.getPublic());
              });
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      boolean leftAccepted = left.get(10, TimeUnit.SECONDS);
      boolean rightAccepted = right.get(10, TimeUnit.SECONDS);
      assertNotEquals(leftAccepted, rightAccepted, "one key is recorded and the other is rejected");
      assertEquals(1, Files.readAllLines(file).size());
      assertTrue(
          Files.readString(file)
              .contains(
                  PublicKeyEntry.toString(leftAccepted ? first.getPublic() : second.getPublic())));
      assertTrue(
          (leftAccepted ? secondCheck : firstCheck).rejection(null)
              instanceof SshHostKeyMismatchException);
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void simultaneousConnectionsWithTheSameKeyShareOneRecord() throws Exception {
    SshKnownHosts hosts = new SshKnownHosts(temp.resolve("known_hosts"));
    var workers = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try (TestSshServer server = TestSshServer.builder().password("secret").start()) {
      var calls =
          List.of(
              workers.submit(
                  () -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return SshCommandSession.open(
                        server.target("player"), SshCredential.password("secret"), "gtp", hosts);
                  }),
              workers.submit(
                  () -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return SshCommandSession.open(
                        server.target("player"), SshCredential.password("secret"), "gtp", hosts);
                  }));
      start.countDown();
      for (var call : calls) {
        try (var session = call.get(10, TimeUnit.SECONDS)) {
          session.stdin().write("name\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          session.stdin().flush();
          var reader =
              new java.io.BufferedReader(
                  new java.io.InputStreamReader(
                      session.stdout(), java.nio.charset.StandardCharsets.UTF_8));
          assertEquals("= test-engine", reader.readLine());
        }
      }
      assertEquals(1, Files.readAllLines(hosts.file()).size());
      assertEquals(2, server.commands.size());
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }
}
