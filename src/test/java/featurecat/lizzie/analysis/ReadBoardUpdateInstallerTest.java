package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReadBoardUpdateInstallerTest {
  @TempDir Path tempDir;

  private final ReadBoardUpdateInstaller installer = new ReadBoardUpdateInstaller();

  @Test
  void validateRequestRejectsEmptyRequest() {
    IOException failure = assertThrows(IOException.class, () -> installer.validateRequest(null));

    assertTrue(failure.getMessage().contains("request is empty"));
  }

  @Test
  void validateRequestAcceptsFlatRootPackage() throws Exception {
    ReadBoardUpdateRequest request =
        createRequest(
            "v3.0.2",
            Map.of(
                "readboard.exe", "new-exe",
                "readboard.dll", "new-dll",
                "readboard.runtimeconfig.json", "{}",
                "readboard.deps.json", "{}",
                "language_cn.txt", "cn"));

    assertDoesNotThrow(() -> installer.validateRequest(request));
  }

  @Test
  void validateRequestAcceptsSingleTopLevelDirectoryPackage() throws Exception {
    ReadBoardUpdateRequest request =
        createRequest(
            "v3.0.2",
            Map.of(
                "bundle/readboard.exe", "new-exe",
                "bundle/readboard.dll", "new-dll",
                "bundle/readboard.runtimeconfig.json", "{}",
                "bundle/readboard.deps.json", "{}",
                "bundle/language_cn.txt", "cn"));

    assertDoesNotThrow(() -> installer.validateRequest(request));
  }

  @Test
  void validateRequestAcceptsWebView2PackageName() throws Exception {
    ReadBoardUpdateRequest request =
        createRequest(
            "readboard-webview2-",
            "v3.1.0",
            Map.of(
                "readboard.exe", "new-exe",
                "readboard.dll", "new-dll",
                "readboard.runtimeconfig.json", "{}",
                "readboard.deps.json", "{}",
                "language_cn.txt", "cn"));

    assertDoesNotThrow(() -> installer.validateRequest(request));
  }

  @Test
  void validateRequestRejectsUnapprovedZipName() throws Exception {
    ReadBoardUpdateRequest request =
        createRequest(
            "readboard-custom-",
            "v3.1.0",
            Map.of(
                "readboard.exe", "new-exe",
                "readboard.dll", "new-dll",
                "readboard.runtimeconfig.json", "{}",
                "readboard.deps.json", "{}",
                "language_cn.txt", "cn"));

    assertThrows(IOException.class, () -> installer.validateRequest(request));
  }

  @Test
  void validateRequestRejectsNestedRequiredFilesBelowInstallRoot() throws Exception {
    ReadBoardUpdateRequest request =
        createRequest(
            "v3.0.2",
            Map.of(
                "bundle/bin/readboard.exe", "new-exe",
                "bundle/bin/readboard.dll", "new-dll",
                "bundle/bin/readboard.runtimeconfig.json", "{}",
                "bundle/bin/readboard.deps.json", "{}",
                "bundle/bin/language_cn.txt", "cn"));

    IOException failure = assertThrows(IOException.class, () -> installer.validateRequest(request));
    assertTrue(failure.getMessage().contains("missing required runtime files"));
  }

  @Test
  void validateRequestRejectsPathTraversalEntries() throws Exception {
    ReadBoardUpdateRequest request =
        createRequest(
            "v3.0.2",
            Map.of(
                "../evil.txt", "x",
                "readboard.exe", "new-exe",
                "readboard.dll", "new-dll",
                "readboard.runtimeconfig.json", "{}",
                "readboard.deps.json", "{}",
                "language_cn.txt", "cn"));

    assertThrows(IOException.class, () -> installer.validateRequest(request));
  }

  @Test
  void installReplacesExistingDirectoryAndKeepsBackup() throws Exception {
    Path installDirectory = Files.createDirectories(tempDir.resolve("readboard"));
    Files.writeString(installDirectory.resolve("old.txt"), "old", StandardCharsets.UTF_8);
    Files.writeString(installDirectory.resolve("readboard.exe"), "old-exe", StandardCharsets.UTF_8);

    ReadBoardUpdateRequest request =
        createRequest(
            "v3.0.2",
            orderedEntries(
                "readboard.exe", "new-exe",
                "readboard.dll", "new-dll",
                "readboard.runtimeconfig.json", "{}",
                "readboard.deps.json", "{}",
                "language_cn.txt", "cn"));

    installer.install(request, installDirectory);

    assertEquals(
        "new-exe",
        Files.readString(installDirectory.resolve("readboard.exe"), StandardCharsets.UTF_8));
    assertEquals(
        "new-dll",
        Files.readString(installDirectory.resolve("readboard.dll"), StandardCharsets.UTF_8));
    Path backupDirectory = findDirectory("readboard.backup-");
    assertNotNull(backupDirectory);
    assertEquals(
        "old", Files.readString(backupDirectory.resolve("old.txt"), StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "bundle/"})
  void installPreservesOnlyUserConfigBytesAndKeepsOriginalBackup(String prefix) throws Exception {
    Path target = Files.createDirectory(tempDir.resolve("readboard"));
    Map<String, byte[]> configs =
        Map.of(
            "config.readboard.json",
                "{\"theme\":\"dark\",\"unknown\":true}\r\n".getBytes(StandardCharsets.UTF_8),
            "config_readboard.txt", new byte[] {(byte) 0xff, (byte) 0xfe, 65, 0, 13, 0, 10, 0},
            "config_readboard_others.txt", new byte[] {(byte) 0xb0, (byte) 0xa1, 13, 10});
    for (var config : configs.entrySet()) {
      Files.write(target.resolve(config.getKey()), config.getValue());
    }
    Files.writeString(target.resolve("readboard.exe"), "old-exe");
    Files.writeString(target.resolve("readboard.dll"), "old-dll");
    Files.writeString(target.resolve("obsolete.dll"), "obsolete");
    Files.writeString(target.resolve("unlisted-user-file.txt"), "original");
    Map<String, String> entries = runtimeEntries(prefix);
    configs.keySet().forEach(name -> entries.put(prefix + name, "package-default"));

    installer.install(createRequest("readboard-webview2-", "v3.2.0", entries), target);

    Path backup = findDirectory("readboard.backup-");
    assertNotNull(backup);
    for (var config : configs.entrySet()) {
      assertArrayEquals(config.getValue(), Files.readAllBytes(target.resolve(config.getKey())));
      assertArrayEquals(config.getValue(), Files.readAllBytes(backup.resolve(config.getKey())));
    }
    assertEquals("new-exe", Files.readString(target.resolve("readboard.exe")));
    assertEquals("new-dll", Files.readString(target.resolve("readboard.dll")));
    assertEquals("old-exe", Files.readString(backup.resolve("readboard.exe")));
    assertFalse(Files.exists(target.resolve("obsolete.dll")));
    assertFalse(Files.exists(target.resolve("unlisted-user-file.txt")));
    assertEquals("original", Files.readString(backup.resolve("unlisted-user-file.txt")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "json", "legacy"})
  void installAcceptsMissingOptionalConfigAndLeavesMigrationToHelper(String format)
      throws Exception {
    Path target = Files.createDirectory(tempDir.resolve("readboard"));
    List<String> names =
        "json".equals(format)
            ? List.of("config.readboard.json")
            : "legacy".equals(format)
                ? List.of("config_readboard.txt", "config_readboard_others.txt")
                : List.of();
    for (String name : names) Files.writeString(target.resolve(name), "original-" + name);

    installer.install(createRequest("v3.0.2", runtimeEntries("")), target);

    for (String name :
        List.of("config.readboard.json", "config_readboard.txt", "config_readboard_others.txt")) {
      assertEquals(names.contains(name), Files.exists(target.resolve(name)));
      if (names.contains(name))
        assertEquals("original-" + name, Files.readString(target.resolve(name)));
    }
  }

  @Test
  void freshInstallRetainsPackageDefaults() throws Exception {
    Path target = tempDir.resolve("readboard");
    Map<String, String> entries = runtimeEntries("");
    entries.put("config.readboard.json", "package-default");

    installer.install(createRequest("v3.0.2", entries), target);

    assertEquals("package-default", Files.readString(target.resolve("config.readboard.json")));
    assertEquals(null, findDirectory("readboard.backup-"));
  }

  @Test
  void invalidExistingConfigAbortsBeforeMovingOriginal() throws Exception {
    Path target = Files.createDirectory(tempDir.resolve("readboard"));
    Files.writeString(target.resolve("readboard.exe"), "old-exe");
    Files.writeString(target.resolve("config.readboard.json"), "original-json");
    Files.createDirectory(target.resolve("config_readboard.txt"));

    assertThrows(
        IOException.class,
        () -> installer.install(createRequest("v3.0.2", runtimeEntries("")), target));

    assertEquals("old-exe", Files.readString(target.resolve("readboard.exe")));
    assertEquals("original-json", Files.readString(target.resolve("config.readboard.json")));
    assertEquals(null, findDirectory("readboard.backup-"));
    assertEquals(null, findDirectory("readboard.staging-"));
  }

  @Test
  void extractionFailureCleansStagingAndDoesNotMoveOriginal() throws Exception {
    Path target = Files.createDirectory(tempDir.resolve("readboard"));
    Files.writeString(target.resolve("readboard.exe"), "old-exe");
    Map<String, String> entries = runtimeEntries("");
    entries.put("conflict", "file");
    entries.put("conflict/nested", "cannot-extract");

    assertThrows(
        IOException.class, () -> installer.install(createRequest("v3.0.2", entries), target));

    assertEquals("old-exe", Files.readString(target.resolve("readboard.exe")));
    assertEquals(null, findDirectory("readboard.backup-"));
    assertEquals(null, findDirectory("readboard.staging-"));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void moveFailureRetainsOriginalAndCleansStaging(int failedMove) throws Exception {
    verifyFailedInstall(failedMove, false, false);
  }

  @Test
  void cleanupFailureDoesNotPreventRollbackOrReplaceInstallFailure() throws Exception {
    verifyFailedInstall(2, false, true);
  }

  @Test
  void rollbackAndCleanupFailuresAreSuppressedAndBackupIsRetained() throws Exception {
    verifyFailedInstall(2, true, true);
  }

  private void verifyFailedInstall(int failedMove, boolean failRollback, boolean failCleanup)
      throws Exception {
    Path target = Files.createDirectory(tempDir.resolve("readboard"));
    Files.writeString(target.resolve("readboard.exe"), "old-exe");
    Files.writeString(target.resolve("config.readboard.json"), "original-json");
    IOException primary = new IOException("installation failed");
    IOException rollback = new IOException("rollback failed");
    IOException cleanup = new IOException("cleanup failed");
    ReadBoardUpdateInstaller failingInstaller =
        new ReadBoardUpdateInstaller(
            new ReadBoardUpdateInstaller.FileOperations() {
              private int moves;

              @Override
              void moveDirectory(Path source, Path destination) throws IOException {
                if (++moves == failedMove) throw primary;
                if (moves == 3 && failRollback) throw rollback;
                super.moveDirectory(source, destination);
              }

              @Override
              void deleteRecursively(Path directory) throws IOException {
                if (failCleanup && Files.exists(directory)) throw cleanup;
                super.deleteRecursively(directory);
              }
            });

    assertSame(
        primary,
        assertThrows(
            IOException.class,
            () -> failingInstaller.install(createRequest("v3.0.2", runtimeEntries("")), target)));

    assertArrayEquals(
        failRollback
            ? new Throwable[] {rollback, cleanup}
            : failCleanup ? new Throwable[] {cleanup} : new Throwable[0],
        primary.getSuppressed());
    Path retained = failRollback ? findDirectory("readboard.backup-") : target;
    assertNotNull(retained);
    assertEquals("old-exe", Files.readString(retained.resolve("readboard.exe")));
    assertEquals("original-json", Files.readString(retained.resolve("config.readboard.json")));
    assertEquals(!failRollback, Files.exists(target));
    assertEquals(failCleanup, findDirectory("readboard.staging-") != null);
    assertEquals(failRollback, findDirectory("readboard.backup-") != null);
  }

  private Path findDirectory(String prefix) throws IOException {
    try (var paths = Files.list(tempDir)) {
      return paths
          .filter(path -> path.getFileName().toString().startsWith(prefix))
          .findFirst()
          .orElse(null);
    }
  }

  private static Map<String, String> runtimeEntries(String prefix) {
    return orderedEntries(
        prefix + "readboard.exe",
        "new-exe",
        prefix + "readboard.dll",
        "new-dll",
        prefix + "readboard.runtimeconfig.json",
        "{}",
        prefix + "readboard.deps.json",
        "{}",
        prefix + "language_cn.txt",
        "cn");
  }

  private ReadBoardUpdateRequest createRequest(String versionTag, Map<String, String> entries)
      throws Exception {
    return createRequest("readboard-github-release-", versionTag, entries);
  }

  private ReadBoardUpdateRequest createRequest(
      String packagePrefix, String versionTag, Map<String, String> entries) throws Exception {
    Path zipPath = createZip(tempDir.resolve(packagePrefix + versionTag + ".zip"), entries);
    ReadBoardUpdateRequest request =
        ReadBoardUpdateRequest.tryParse(
            "readboardUpdateReady\t" + versionTag + "\t" + zipPath.toAbsolutePath());
    assertNotNull(request);
    return request;
  }

  private static Path createZip(Path zipPath, Map<String, String> entries) throws Exception {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        output.putNextEntry(new ZipEntry(entry.getKey()));
        output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    return zipPath;
  }

  private static Map<String, String> orderedEntries(String... values) {
    Map<String, String> entries = new LinkedHashMap<String, String>();
    for (int index = 0; index < values.length; index += 2) {
      entries.put(values[index], values[index + 1]);
    }
    return entries;
  }
}
