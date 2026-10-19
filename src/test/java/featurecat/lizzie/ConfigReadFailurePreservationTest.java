package featurecat.lizzie;

import static featurecat.lizzie.FileAccessTestSupport.denyParentWrite;
import static featurecat.lizzie.FileAccessTestSupport.denyRead;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ConfigReadFailurePreservationTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @EnumSource(ConfigFile.class)
  void unreadableExistingFileStopsStartupWithoutReplacingUserData(ConfigFile kind)
      throws Exception {
    Path workDir = existingProfile();
    Path file = kind.path(workDir);
    Map<Path, byte[]> before = profileBytes(workDir);
    IOException failure;

    try (AutoCloseable ignored = denyRead(file)) {
      assertThrows(IOException.class, () -> Files.readAllBytes(file));
      if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
        assertFalse(file.toFile().canRead(), "Reproduce the canRead-based replacement path");
      }
      // The directory still permits the atomic replacement that used to erase unreadable files.
      Path replacementProbe = Files.createTempFile(file.getParent(), ".replacement-probe", ".tmp");
      Files.delete(replacementProbe);

      failure = bootstrapFailure(workDir);
    }

    assertAll(
        () -> assertNotNull(failure, "An existing unreadable file must stop startup with IOException"),
        () -> assertProfileUnchanged(before),
        () -> assertTrue(backups(file).isEmpty(), "A read failure must not trigger JSON recovery"));
  }

  @ParameterizedTest
  @EnumSource(ConfigFile.class)
  void directoryAtExpectedFilePathStopsStartupWithoutRemovingItsContents(ConfigFile kind)
      throws Exception {
    Path workDir = existingProfile();
    Path file = kind.path(workDir);
    Map<Path, byte[]> before = profileBytes(workDir);
    before.remove(file);
    Files.delete(file);
    Files.createDirectory(file);
    Path userFile = file.resolve("user-data.txt");
    byte[] original = "Keep the existing directory and its contents.\n".getBytes(StandardCharsets.UTF_8);
    Files.write(userFile, original);

    IOException failure = bootstrapFailure(workDir);

    assertAll(
        () -> assertNotNull(failure, "A directory is not a missing settings file"),
        () -> assertTrue(Files.isDirectory(file)),
        () -> assertArrayEquals(original, Files.readAllBytes(userFile)),
        () -> assertProfileUnchanged(before));
  }

  @ParameterizedTest
  @EnumSource(ConfigFile.class)
  void failedDefaultMergeWriteStopsStartupAndPreservesReadableUserData(ConfigFile kind)
      throws Exception {
    Path workDir = existingProfile();
    Path file = kind.path(workDir);
    // A readable but incomplete document requires the normal default-key merge to be persisted.
    Files.writeString(file, kind.minimalUserConfig().toString(2), StandardCharsets.UTF_8);
    Map<Path, byte[]> before = profileBytes(workDir);
    IOException failure;

    try (AutoCloseable ignored = denyParentWrite(file.getParent())) {
      assertParentCreationDenied(file.getParent());
      assertArrayEquals(before.get(file), Files.readAllBytes(file));

      failure = bootstrapFailure(workDir);
    }

    assertAll(
        () -> assertNotNull(failure, "A failed merge write must not become in-memory defaults"),
        () -> assertProfileUnchanged(before),
        () -> assertTrue(backups(file).isEmpty(), "A write failure is not malformed JSON"));
  }

  @ParameterizedTest
  @EnumSource(ConfigFile.class)
  void malformedFileRecoveryPreservesExactBytesAndExistingBackups(ConfigFile kind)
      throws Exception {
    Path workDir = existingProfile();
    Path file = kind.path(workDir);
    byte[] malformed = ("{\"" + kind.section + "\":{\"사용자 설정\":\"").getBytes(StandardCharsets.UTF_8);
    byte[] oldBackup = "Earlier recovery: preserve this file too.\n".getBytes(StandardCharsets.UTF_8);
    Files.write(file, malformed);
    Path previousBackup = file.resolveSibling(file.getFileName() + ".unreadable-backup");
    Files.write(previousBackup, oldBackup);
    Map<Path, byte[]> otherFiles = profileBytes(workDir);
    otherFiles.remove(file);

    Config recovered = ConfigTestHelper.createBootstrapped(workDir);

    assertTrue(kind.loadedRoot(recovered).has(kind.section));
    assertFalse(kind.loadedRoot(recovered).has("user-marker"));
    assertArrayEquals(oldBackup, Files.readAllBytes(previousBackup));
    List<Path> newBackups = new ArrayList<>(backups(file));
    newBackups.remove(previousBackup);
    assertEquals(1, newBackups.size(), "Recovery must preserve the original in a separate backup");
    assertArrayEquals(malformed, Files.readAllBytes(newBackups.get(0)));
    if (kind == ConfigFile.PERSIST) {
      // Persist retains its existing strategy: use defaults in memory until the next normal save.
      assertArrayEquals(malformed, Files.readAllBytes(file));
    } else {
      assertTrue(Config.readJsonObjectForTests(file).has(kind.section));
    }
    assertProfileUnchanged(otherFiles);
  }

  @ParameterizedTest
  @EnumSource(ConfigFile.class)
  void failedMalformedFileBackupStopsRecoveryAndPreservesOriginal(ConfigFile kind)
      throws Exception {
    Path workDir = existingProfile();
    Path file = kind.path(workDir);
    byte[] malformed = ("{\"" + kind.section + "\":{").getBytes(StandardCharsets.UTF_8);
    Files.write(file, malformed);
    Map<Path, byte[]> before = profileBytes(workDir);
    IOException failure;

    try (AutoCloseable ignored = denyParentWrite(file.getParent())) {
      assertParentCreationDenied(file.getParent());
      assertArrayEquals(malformed, Files.readAllBytes(file));

      failure = bootstrapFailure(workDir);
    }

    assertAll(
        () -> assertNotNull(failure, "Recovery must stop when the original cannot be backed up"),
        () -> assertProfileUnchanged(before),
        () -> assertTrue(backups(file).isEmpty()));
  }

  @ParameterizedTest
  @EnumSource(ConfigFile.class)
  void confirmedMissingFileIsInitializedWithoutResettingExistingFiles(ConfigFile kind)
      throws Exception {
    Path workDir = existingProfile();
    Path file = kind.path(workDir);
    Map<Path, byte[]> otherFiles = profileBytes(workDir);
    otherFiles.remove(file);
    Files.delete(file);
    assertTrue(Files.notExists(file));

    Config config = ConfigTestHelper.createBootstrapped(workDir);

    assertTrue(Config.readJsonObjectForTests(file).has(kind.section));
    assertTrue(kind.loadedRoot(config).has(kind.section));
    assertEquals(kind == ConfigFile.MAIN, config.isNewProfile());
    if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
      assertEquals(
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
          Files.getPosixFilePermissions(file),
          "First creation must retain the private settings-file permissions");
    }
    assertProfileUnchanged(otherFiles);
    assertTrue(backups(file).isEmpty());
  }

  @Test
  void initialCreationDoesNotReplaceAFileCreatedAfterTheMissingCheck() throws Exception {
    Path file = tempDir.resolve("config.txt");
    assertTrue(Files.notExists(file));
    byte[] original = "{\"user-marker\":\"Created by another startup\"}".getBytes(StandardCharsets.UTF_8);
    Files.write(file, original);
    Method create =
        Config.class.getDeclaredMethod("createDefaultConfigIfAbsent", JSONObject.class, Path.class);
    create.setAccessible(true);

    boolean created =
        (boolean) create.invoke(null, new JSONObject().put("ui", new JSONObject()), file);

    assertFalse(created);
    assertArrayEquals(original, Files.readAllBytes(file));
    try (Stream<Path> files = Files.list(tempDir)) {
      assertEquals(List.of(file), files.toList(), "Failed exclusive creation must remove its temp file");
    }
  }

  @Test
  void backupReadFailureIsPropagatedEvenWhenTheParentAllowsWrites() throws Exception {
    Path file = tempDir.resolve("config.txt");
    byte[] original = "{\"ui\":{\"사용자 설정\":\"".getBytes(StandardCharsets.UTF_8);
    Files.write(file, original);
    Method backup = Config.class.getDeclaredMethod("backupUnreadableConfig", File.class);
    backup.setAccessible(true);

    try (AutoCloseable ignored = denyRead(file)) {
      assertThrows(IOException.class, () -> Files.readAllBytes(file));
      Path writableSibling = Files.createTempFile(tempDir, "backup-write-probe", ".tmp");
      Files.delete(writableSibling);

      InvocationTargetException failure =
          assertThrows(InvocationTargetException.class, () -> backup.invoke(null, file.toFile()));

      assertInstanceOf(IOException.class, failure.getCause());
    }
    assertArrayEquals(original, Files.readAllBytes(file));
  }

  @Test
  void ioFailureAfterJsonParsingStartsKeepsItsOriginalException() throws Exception {
    IOException readFailure = new IOException("The settings stream failed after its initial bytes");
    Reader source =
        new Reader() {
          private final String prefix = "{\"ui\":{\"user-marker\":\"";
          private int position;

          @Override
          public int read(char[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (position == prefix.length()) throw readFailure;
            int count = Math.min(length, prefix.length() - position);
            prefix.getChars(position, position + count, buffer, offset);
            position += count;
            return count;
          }

          @Override
          public void close() {}
        };
    Method readJson = Config.class.getDeclaredMethod("readJsonObject", Reader.class);
    readJson.setAccessible(true);

    InvocationTargetException failure =
        assertThrows(InvocationTargetException.class, () -> readJson.invoke(null, source));

    assertSame(readFailure, failure.getCause(), "JSONTokener must not disguise I/O as broken JSON");
  }

  private Path existingProfile() throws IOException {
    Path workDir = Files.createDirectories(tempDir.resolve("profile"));
    Files.createDirectories(workDir.resolve("save"));
    ConfigTestHelper.createBootstrapped(workDir);
    for (ConfigFile kind : ConfigFile.values()) {
      Path file = kind.path(workDir);
      JSONObject contents = Config.readJsonObjectForTests(file);
      contents.put("user-marker", "Keep my " + kind.relativePath);
      Files.writeString(file, contents.toString(2), StandardCharsets.UTF_8);
    }
    return workDir;
  }

  private static IOException bootstrapFailure(Path workDir) {
    try {
      ConfigTestHelper.createBootstrapped(workDir);
      return null;
    } catch (IOException failure) {
      return failure;
    }
  }

  private static Map<Path, byte[]> profileBytes(Path workDir) throws IOException {
    Map<Path, byte[]> result = new LinkedHashMap<>();
    for (ConfigFile kind : ConfigFile.values()) {
      Path file = kind.path(workDir);
      result.put(file, Files.readAllBytes(file));
    }
    return result;
  }

  private static void assertProfileUnchanged(Map<Path, byte[]> before) throws IOException {
    for (Map.Entry<Path, byte[]> entry : before.entrySet()) {
      assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()), entry.getKey().toString());
    }
  }

  private static List<Path> backups(Path file) throws IOException {
    try (Stream<Path> siblings = Files.list(file.getParent())) {
      String prefix = file.getFileName() + ".unreadable-backup";
      return siblings.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
    }
  }

  private static void assertParentCreationDenied(Path parent) {
    assertThrows(
        IOException.class,
        () ->
            Files.writeString(
                parent.resolve(".creation-must-be-denied"),
                "permission probe",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE));
  }

  private enum ConfigFile {
    MAIN("config.txt", "ui"),
    PERSIST("persist", "ui-persist"),
    SAVE_BOARD("save/save", "save");

    private final String relativePath;
    private final String section;

    ConfigFile(String relativePath, String section) {
      this.relativePath = relativePath;
      this.section = section;
    }

    Path path(Path workDir) {
      return workDir.resolve(relativePath);
    }

    JSONObject minimalUserConfig() {
      return new JSONObject()
          .put(section, new JSONObject().put("user-setting", "keep"))
          .put("user-marker", "Keep my " + relativePath);
    }

    JSONObject loadedRoot(Config config) {
      return switch (this) {
        case MAIN -> config.config;
        case PERSIST -> config.persisted;
        case SAVE_BOARD -> config.saveBoard;
      };
    }
  }
}
