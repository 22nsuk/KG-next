package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class PlatformCredentialStoreTest {
  @Test
  void sshPasswordsUseIndependentMacAndLinuxCredentialNamespaces() throws Exception {
    RecordingRunner mac = new RecordingRunner();
    PlatformCredentialStore.create("Mac OS X", Path.of("unused"), mac)
        .write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "reference", "ssh-secret");
    assertTrue(mac.flattenedCommands().contains("cn.lizzieyzy.next.ssh-engine.password"));
    assertFalse(mac.flattenedCommands().contains("ssh-secret"));

    RecordingRunner linux = new RecordingRunner();
    PlatformCredentialStore.create("Linux", Path.of("unused"), linux)
        .write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "reference", "ssh-secret");
    assertTrue(linux.flattenedCommands().contains("kind ssh-engine-password account reference"));
    assertFalse(linux.flattenedCommands().contains("ssh-secret"));
  }

  @Test
  void windowsDpapiPreservesWhitespaceInSshPasswords() throws Exception {
    Path directory = Files.createTempDirectory("dpapi-ssh-whitespace");
    CredentialStore store = PlatformCredentialStore.create("Windows 11", directory,
        new RecordingRunner(), new FakeWindowsDataProtector());
    String secret = "  SSH password \t ";
    store.write(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "reference", secret);
    assertEquals(secret, store.read(CredentialStore.Kind.SSH_ENGINE_PASSWORD, "reference").orElseThrow());
  }

  @Test
  void macKeychainReceivesSecretsOnlyThroughStandardInput() throws Exception {
    RecordingRunner runner = new RecordingRunner();
    runner.readOutput = "stored-secret\n";
    CredentialStore store = PlatformCredentialStore.create("Mac OS X", Path.of("unused"), runner);

    store.write(CredentialStore.Kind.PASSWORD, "user@example.com", "never-in-argv");
    assertEquals(
        "stored-secret",
        store.read(CredentialStore.Kind.PASSWORD, "user@example.com").orElseThrow());

    assertFalse(runner.flattenedCommands().contains("never-in-argv"));
    assertTrue(
        runner.inputs.contains(
            "never-in-argv" + System.lineSeparator() + "never-in-argv" + System.lineSeparator()));
    assertTrue(runner.flattenedCommands().contains("add-generic-password"));
    assertTrue(runner.flattenedCommands().contains("find-generic-password"));
  }

  @Test
  void aiCommentaryUsesAnIndependentMacKeychainService() throws Exception {
    RecordingRunner runner = new RecordingRunner();
    CredentialStore store = PlatformCredentialStore.create("Mac OS X", Path.of("unused"), runner);

    store.write(CredentialStore.Kind.API_KEY, "provider-account", "never-in-argv");

    String commands = runner.flattenedCommands();
    assertTrue(commands.contains("cn.lizzieyzy.next.ai-commentary.api-key"));
    assertFalse(commands.contains("cn.lizzieyzy.next.zhizi.api-key"));
    assertFalse(commands.contains("never-in-argv"));
  }

  @Test
  void linuxSecretServiceReceivesSecretsOnlyThroughStandardInput() throws Exception {
    RecordingRunner runner = new RecordingRunner();
    runner.readOutput = "linux-secret\n";
    CredentialStore store = PlatformCredentialStore.create("Linux", Path.of("unused"), runner);

    store.write(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com", "never-in-argv");
    assertEquals(
        "linux-secret",
        store.read(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com").orElseThrow());

    assertFalse(runner.flattenedCommands().contains("never-in-argv"));
    assertTrue(runner.inputs.contains("never-in-argv" + System.lineSeparator()));
    assertTrue(runner.flattenedCommands().contains("secret-tool store"));
    assertTrue(runner.flattenedCommands().contains("secret-tool lookup"));
  }

  @Test
  void windowsDpapiPersistsOnlyEncryptedUserScopedBlob() throws Exception {
    Path directory = Files.createTempDirectory("dpapi-store");
    RecordingRunner runner = new RecordingRunner();
    FakeWindowsDataProtector protector = new FakeWindowsDataProtector();
    protector.protectedOutput = "encrypted-dpapi-blob".getBytes(StandardCharsets.UTF_8);
    CredentialStore store =
        PlatformCredentialStore.create("Windows 11", directory, runner, protector);

    store.write(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com", "never-in-file");

    List<Path> files;
    try (var paths = Files.list(directory)) {
      files = paths.toList();
    }
    assertEquals(1, files.size());
    assertEquals(
        Base64.getEncoder().encodeToString(protector.protectedOutput),
        Files.readString(files.get(0)));
    assertFalse(files.get(0).getFileName().toString().contains("user@example.com"));
    assertEquals(
        "never-in-file",
        store.read(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com").orElseThrow());
    assertTrue(runner.commands.isEmpty());
    assertTrue(protector.protectCalls >= 2);
    assertTrue(protector.unprotectCalls >= 3);
  }

  @Test
  void windowsDpapiRejectsCredentialThatCannotBeReadBack() throws Exception {
    Path directory = Files.createTempDirectory("dpapi-store-verification");
    RecordingRunner runner = new RecordingRunner();
    FakeWindowsDataProtector protector = new FakeWindowsDataProtector();
    protector.corruptAfterProbe = true;
    CredentialStore store =
        PlatformCredentialStore.create("Windows 11", directory, runner, protector);

    assertThrows(
        IOException.class,
        () -> store.write(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com", "token"));
    try (var files = Files.list(directory)) {
      assertEquals(0L, files.count());
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void windowsDpapiRoundTripsAcrossRealNativeCalls() throws Exception {
    Path directory = Files.createTempDirectory("dpapi-store-real");
    String account = "dpapi-regression-test";
    String secret = "round-trip-secret-\u4e2d\u6587";

    CredentialStore writer = PlatformCredentialStore.create(directory);
    assertTrue(writer.isAvailable());
    writer.write(CredentialStore.Kind.ACCOUNT_TOKEN, account, secret);

    CredentialStore reader = PlatformCredentialStore.create(directory);
    assertTrue(reader.isAvailable());
    assertEquals(secret, reader.read(CredentialStore.Kind.ACCOUNT_TOKEN, account).orElseThrow());

    reader.delete(CredentialStore.Kind.ACCOUNT_TOKEN, account);
    assertTrue(reader.read(CredentialStore.Kind.ACCOUNT_TOKEN, account).isEmpty());
  }

  @Test
  void unsupportedPlatformUsesSessionOnlyBackend() {
    CredentialStore store =
        PlatformCredentialStore.create("Plan 9", Path.of("unused"), new RecordingRunner());

    assertEquals("session-only", store.backendName());
    assertFalse(store.isAvailable());
  }

  private static final class RecordingRunner
      implements PlatformCredentialStore.CredentialCommandRunner {
    final List<List<String>> commands = new ArrayList<>();
    final List<String> inputs = new ArrayList<>();
    String readOutput = "";

    @Override
    public PlatformCredentialStore.CommandResult run(
        List<String> command, String input, Duration timeout) throws IOException {
      commands.add(List.copyOf(command));
      inputs.add(input == null ? "" : input);
      String flattened = String.join(" ", command);
      if (flattened.contains("find-generic-password") || flattened.contains("secret-tool lookup")) {
        return new PlatformCredentialStore.CommandResult(0, readOutput);
      }
      return new PlatformCredentialStore.CommandResult(0, "");
    }

    String flattenedCommands() {
      StringBuilder out = new StringBuilder();
      for (List<String> command : commands) {
        if (out.length() > 0) {
          out.append('\n');
        }
        out.append(String.join(" ", command));
      }
      return out.toString();
    }
  }

  private static final class FakeWindowsDataProtector
      implements PlatformCredentialStore.WindowsDataProtector {
    byte[] protectedOutput = "encrypted".getBytes(StandardCharsets.UTF_8);
    byte[] lastPlaintext = new byte[0];
    int protectCalls;
    int unprotectCalls;
    boolean corruptAfterProbe;

    @Override
    public byte[] protect(byte[] plaintext) {
      protectCalls++;
      lastPlaintext = Arrays.copyOf(plaintext, plaintext.length);
      return Arrays.copyOf(protectedOutput, protectedOutput.length);
    }

    @Override
    public byte[] unprotect(byte[] encrypted) {
      unprotectCalls++;
      if (corruptAfterProbe && unprotectCalls > 1) {
        return "different-secret".getBytes(StandardCharsets.UTF_8);
      }
      return Arrays.copyOf(lastPlaintext, lastPlaintext.length);
    }
  }
}
