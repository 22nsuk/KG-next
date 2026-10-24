package featurecat.lizzie.analysis.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class SshCommandSessionTest {
  @TempDir Path temp;

  @Test
  void passwordLoginStartsTheCommandAndRecordsTheHostKey() throws Exception {
    SshKnownHosts knownHosts = new SshKnownHosts(temp.resolve("ssh/known_hosts"));
    try (TestSshServer server = TestSshServer.builder().password("secret").start();
        SshCommandSession session =
            SshCommandSession.open(
                server.target("player"),
                SshCredential.password("secret"),
                "katago gtp -model b18.bin.gz",
                knownHosts)) {
      assertEquals("started katago gtp -model b18.bin.gz", readLine(session.stderr()));
      assertEquals("= test-engine", gtp(session, "name"));
      assertEquals(List.of("katago gtp -model b18.bin.gz"), server.commands);
      assertEquals(0, server.publicKeyAttempts.get(), "the user's own ~/.ssh keys are not offered");

      String recorded = Files.readString(knownHosts.file());
      assertTrue(recorded.contains("[127.0.0.1]:" + server.port()), recorded);
      assertTrue(
          recorded.contains(PublicKeyEntry.toString(server.hostKey().getPublic())), recorded);
    }
  }

  @Test
  void reconnectingToARecordedHostDoesNotAddAnotherEntry() throws Exception {
    SshKnownHosts knownHosts = new SshKnownHosts(temp.resolve("known_hosts"));
    try (TestSshServer server = TestSshServer.builder().password("secret").start()) {
      for (int attempt = 0; attempt < 2; attempt++) {
        try (SshCommandSession session =
            SshCommandSession.open(
                server.target("player"), SshCredential.password("secret"), "gtp", knownHosts)) {
          assertEquals("= test-engine", gtp(session, "name"));
        }
      }
      assertEquals(1, Files.readAllLines(knownHosts.file()).size());
    }
  }

  @Test
  void rejectedPasswordIsReportedAsAnAuthenticationFailure() throws Exception {
    SshKnownHosts knownHosts = new SshKnownHosts(temp.resolve("known_hosts"));
    try (TestSshServer server = TestSshServer.builder().password("secret").start()) {
      assertThrows(
          SshAuthenticationException.class,
          () ->
              SshCommandSession.open(
                  server.target("player"), SshCredential.password("wrong"), "gtp", knownHosts));
      assertTrue(server.commands.isEmpty());
    }
  }

  @Test
  void changedHostKeyIsRefusedBeforeAnyCredentialIsSent() throws Exception {
    SshKnownHosts knownHosts = new SshKnownHosts(temp.resolve("known_hosts"));
    try (TestSshServer server = TestSshServer.builder().password("secret").start()) {
      KeyPair previousKey = TestSshServer.ecKeyPair();
      Files.writeString(
          knownHosts.file(),
          "[127.0.0.1]:"
              + server.port()
              + " "
              + PublicKeyEntry.toString(previousKey.getPublic())
              + "\n");

      SshHostKeyMismatchException mismatch =
          assertThrows(
              SshHostKeyMismatchException.class,
              () ->
                  SshCommandSession.open(
                      server.target("player"),
                      SshCredential.password("secret"),
                      "gtp",
                      knownHosts));

      assertEquals("[127.0.0.1]:" + server.port(), mismatch.hostName());
      assertEquals(
          SshKnownHosts.fingerprint(previousKey.getPublic()), mismatch.expectedFingerprint());
      assertEquals(
          SshKnownHosts.fingerprint(server.hostKey().getPublic()), mismatch.presentedFingerprint());
      assertEquals(0, server.passwordAttempts.get());
      assertTrue(server.commands.isEmpty());
      assertEquals(1, Files.readAllLines(knownHosts.file()).size(), "the record is kept");
    }
  }

  @Test
  void hostRecordedWithAnotherKeyTypeIsRefusedInsteadOfTrustedAgain() throws Exception {
    SshKnownHosts knownHosts = new SshKnownHosts(temp.resolve("known_hosts"));
    try (TestSshServer server = TestSshServer.builder().password("secret").start()) {
      KeyPair recordedRsaKey = TestSshServer.rsaKeyPair();
      Files.writeString(
          knownHosts.file(),
          "[127.0.0.1]:"
              + server.port()
              + " "
              + PublicKeyEntry.toString(recordedRsaKey.getPublic())
              + "\n");

      SshHostKeyMismatchException mismatch =
          assertThrows(
              SshHostKeyMismatchException.class,
              () ->
                  SshCommandSession.open(
                      server.target("player"),
                      SshCredential.password("secret"),
                      "gtp",
                      knownHosts));

      assertEquals(
          SshKnownHosts.fingerprint(recordedRsaKey.getPublic()), mismatch.expectedFingerprint());
      assertEquals(0, server.passwordAttempts.get());
      assertEquals(1, Files.readAllLines(knownHosts.file()).size(), "no second key is trusted");
    }
  }

  @Test
  void openSshFormatKeyFileLogsInOnTheConfiguredPort() throws Exception {
    KeyPair clientKey = TestSshServer.ecKeyPair();
    Path keyFile = temp.resolve("id_ecdsa");
    try (OutputStream out = Files.newOutputStream(keyFile)) {
      OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(clientKey, "test", null, out);
    }
    SshKnownHosts knownHosts = new SshKnownHosts(temp.resolve("known_hosts"));
    try (TestSshServer server =
            TestSshServer.builder().acceptedClientKey(clientKey.getPublic()).start();
        SshCommandSession session =
            SshCommandSession.open(
                server.target("player"),
                SshCredential.privateKeyFile(keyFile),
                "gtp",
                knownHosts)) {
      assertTrue(server.port() != SshTarget.DEFAULT_PORT);
      assertEquals("= test-engine", gtp(session, "name"));
      assertTrue(server.publicKeyAttempts.get() > 0);
    }
  }

  @Test
  void pemRsaKeyFileStillWorks() throws Exception {
    KeyPair clientKey = TestSshServer.rsaKeyPair();
    Path keyFile = temp.resolve("id_rsa");
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(clientKey.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    Files.writeString(keyFile, pem);
    SshKnownHosts knownHosts = new SshKnownHosts(temp.resolve("known_hosts"));
    try (TestSshServer server =
            TestSshServer.builder().acceptedClientKey(clientKey.getPublic()).start();
        SshCommandSession session =
            SshCommandSession.open(
                server.target("player"),
                SshCredential.privateKeyFile(keyFile),
                "gtp",
                knownHosts)) {
      assertEquals("= test-engine", gtp(session, "name"));
    }
  }

  @Test
  void unreadableKeyFileFailsWithoutContactingTheServerCommand() throws Exception {
    Path keyFile = temp.resolve("not-a-key");
    Files.writeString(keyFile, "hello\n");
    SshKnownHosts knownHosts = new SshKnownHosts(temp.resolve("known_hosts"));
    try (TestSshServer server = TestSshServer.builder().password("secret").start()) {
      IOException failure =
          assertThrows(
              IOException.class,
              () ->
                  SshCommandSession.open(
                      server.target("player"),
                      SshCredential.privateKeyFile(keyFile),
                      "gtp",
                      knownHosts));
      assertFalse(failure instanceof SshHostKeyMismatchException);
      assertTrue(server.commands.isEmpty());
    }
  }

  @Test
  void serverLimitedToGanymedEraAlgorithmsStillConnects() throws Exception {
    SshKnownHosts knownHosts = new SshKnownHosts(temp.resolve("known_hosts"));
    try (TestSshServer server =
            TestSshServer.builder().legacyAlgorithmsOnly().password("secret").start();
        SshCommandSession session =
            SshCommandSession.open(
                server.target("player"), SshCredential.password("secret"), "gtp", knownHosts)) {
      assertEquals("= test-engine", gtp(session, "name"));
    }
  }

  @Test
  void commandOutputIsReadableAfterTheRemoteCommandExits() throws Exception {
    String catalog = "{\"servers\":[{\"name\":\"b18\"}]}\n".repeat(200);
    SshKnownHosts knownHosts = new SshKnownHosts(temp.resolve("known_hosts"));
    try (TestSshServer server =
            TestSshServer.builder().password("secret").catalogResponse(catalog).start();
        SshCommandSession session =
            SshCommandSession.open(
                server.target("player"),
                SshCredential.password("secret"),
                "query-server",
                knownHosts)) {
      session.stdin().close();
      assertTrue(session.awaitCompletion(Duration.ofSeconds(10)));
      assertEquals(catalog, new String(session.stdout().readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void legacyKeyExchangeFallbacksComeAfterEveryModernDefault() {
    SshClient client = SshClient.setUpDefaultClient();
    List<String> defaults = NamedResource.getNameList(client.getKeyExchangeFactories());
    List<String> configured =
        NamedResource.getNameList(
            SshCommandSession.withLegacyKeyExchangeFallbacks(client.getKeyExchangeFactories()));

    assertEquals(defaults, configured.subList(0, defaults.size()));
    assertEquals(
        List.of("diffie-hellman-group14-sha1", "diffie-hellman-group-exchange-sha1"),
        configured.subList(defaults.size(), configured.size()));
  }

  @Test
  void recordedKeyTypeIsRequestedFirst() {
    SshClient client = SshClient.setUpDefaultClient();
    List<String> reordered =
        NamedResource.getNameList(
            SshCommandSession.preferRecordedKeyTypes(
                client.getSignatureFactories(), List.of("ssh-rsa")));
    assertEquals("rsa-sha2-512", reordered.get(0));
    assertTrue(reordered.indexOf("ssh-rsa") < reordered.indexOf("ecdsa-sha2-nistp256"));
    assertEquals(
        client.getSignatureFactories().size(), reordered.size(), "no algorithm is dropped");
  }

  @Test
  void targetUsesOpenSshKnownHostsNamesAndLegacyPortParsing() {
    assertEquals("example.com", new SshTarget("Example.com", 22, "u").knownHostsName());
    assertEquals("[example.com]:2222", new SshTarget("example.com", 2222, "u").knownHostsName());
    assertEquals(2222, SshTarget.parsePort(" 2222 "));
    assertEquals(22, SshTarget.parsePort(""));
    assertEquals(22, SshTarget.parsePort("not-a-port"));
    assertEquals(22, SshTarget.parsePort("70000"));
  }

  private static String gtp(SshCommandSession session, String command) throws IOException {
    session.stdin().write((command + "\n").getBytes(StandardCharsets.UTF_8));
    session.stdin().flush();
    return readLine(session.stdout());
  }

  private static String readLine(java.io.InputStream stream) throws IOException {
    StringBuilder line = new StringBuilder();
    int next;
    while ((next = stream.read()) != -1 && next != '\n') {
      line.append((char) next);
    }
    return line.toString();
  }
}
