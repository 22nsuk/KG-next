package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.FileAccessTestSupport;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkDirectoryReadFailurePreservationTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unreadableCurrentConfigKeepsSelectedDirectoryAndPreventsDefaultStartup(boolean portable)
      throws Exception {
    Profile profile = profile(portable);
    Path configFile = profile.target().resolve("config.txt");
    byte[] original = userConfig("current 사용자 설정").getBytes(StandardCharsets.UTF_8);
    Files.write(configFile, original);
    byte[] legacy = Files.readAllBytes(profile.source().resolve("config.txt"));
    WorkDirectoryResolution resolution;
    Throwable startupFailure = null;

    try (AutoCloseable ignored = FileAccessTestSupport.denyRead(configFile)) {
      assertThrows(IOException.class, () -> Files.readAllBytes(configFile));
      assertTrue(Files.isWritable(profile.target()), "Replacement must be possible in this fixture");

      resolution = WorkDirectoryResolver.resolve(profile.environment());
      try {
        ConfigTestHelper.createBootstrapped(resolution.directory());
      } catch (IOException | RuntimeException e) {
        startupFailure = e;
      }
    }

    Throwable failure = startupFailure;
    assertAll(
        () -> assertArrayEquals(original, Files.readAllBytes(configFile)),
        () -> assertEquals(profile.target(), resolution.directory()),
        () -> assertTrue(hasDiagnostic(resolution, "migration-failed")),
        () -> assertInstanceOf(IOException.class, failure),
        () -> assertTrue(backups(profile.target()).isEmpty()),
        () -> assertArrayEquals(legacy, Files.readAllBytes(profile.source().resolve("config.txt"))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void currentConfigDirectoryCollisionDoesNotSelectAnotherProfile(boolean portable)
      throws Exception {
    Profile profile = profile(portable);
    Path configDirectory = Files.createDirectory(profile.target().resolve("config.txt"));
    Path marker = configDirectory.resolve("user-data.txt");
    Files.writeString(marker, "preserve directory contents");

    WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(profile.environment());

    assertAll(
        () -> assertEquals(profile.target(), resolution.directory()),
        () -> assertTrue(hasDiagnostic(resolution, "migration-failed")),
        () -> assertTrue(Files.isDirectory(configDirectory)),
        () -> assertEquals("preserve directory contents", Files.readString(marker)),
        () ->
            assertThrows(
                IOException.class,
                () -> ConfigTestHelper.createBootstrapped(resolution.directory())));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unreadableOptionalLegacySourceDoesNotReplaceValidCurrentConfig(boolean portable)
      throws Exception {
    Profile profile = profile(portable);
    Path configFile = profile.target().resolve("config.txt");
    String original = userConfig("current");
    Files.writeString(configFile, original);
    Path sourceConfig = profile.source().resolve("config.txt");

    try (AutoCloseable ignored = FileAccessTestSupport.denyRead(sourceConfig)) {
      assertThrows(IOException.class, () -> Files.readAllBytes(sourceConfig));

      WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(profile.environment());

      assertEquals(profile.target(), resolution.directory());
      assertFalse(hasDiagnostic(resolution, "migration-failed"));
    }

    assertEquals(original, Files.readString(configFile));
    assertTrue(backups(profile.target()).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("malformedProfiles")
  void malformedCurrentConfigIsBackedUpBeforeLegacyRecovery(boolean portable, String malformed)
      throws Exception {
    Profile profile = profile(portable);
    Path configFile = profile.target().resolve("config.txt");
    Files.writeString(configFile, malformed);
    String legacy = Files.readString(profile.source().resolve("config.txt"));

    WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(profile.environment());

    assertEquals(profile.target(), resolution.directory());
    assertTrue(hasDiagnostic(resolution, "unreadable-backup"));
    assertEquals(
        malformed, Files.readString(profile.target().resolve("config.txt.unreadable-backup")));
    assertEquals(legacy, Files.readString(configFile));
    assertEquals(
        "legacy",
        new JSONObject(Files.readString(configFile)).getJSONObject("ui").getString("user-note"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void missingCurrentConfigMigratesWithoutInventingAnOriginalBackup(boolean portable)
      throws Exception {
    Profile profile = profile(portable);

    WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(profile.environment());

    assertEquals(profile.target(), resolution.directory());
    assertEquals(
        Files.readString(profile.source().resolve("config.txt")),
        Files.readString(profile.target().resolve("config.txt")));
    assertTrue(backups(profile.target()).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void existingBackupsAreNeverOverwrittenByAnotherMalformedRecovery(boolean portable)
      throws Exception {
    Profile profile = profile(portable);
    Path configFile = profile.target().resolve("config.txt");
    Path previousBackup = profile.target().resolve("config.txt.unreadable-backup");
    Path backupDirectory = profile.target().resolve("config.txt.unreadable-backup.1");
    Files.writeString(previousBackup, "previous preserved bytes");
    Files.createDirectory(backupDirectory);
    Files.writeString(configFile, "{first malformed");

    WorkDirectoryResolver.resolve(profile.environment());
    Files.writeString(configFile, "{second malformed");
    WorkDirectoryResolver.resolve(profile.environment());

    assertAll(
        () -> assertEquals("previous preserved bytes", Files.readString(previousBackup)),
        () -> assertTrue(Files.isDirectory(backupDirectory)),
        () ->
            assertEquals(
                "{first malformed",
                Files.readString(profile.target().resolve("config.txt.unreadable-backup.2"))),
        () ->
            assertEquals(
                "{second malformed",
                Files.readString(profile.target().resolve("config.txt.unreadable-backup.3"))));
  }

  @Test
  void malformedConfigCannotBeReplacedWhenOriginalBackupCannotBeWritten() throws Exception {
    Profile profile = profile(true);
    Path configFile = profile.target().resolve("config.txt");
    String original = "{malformed user settings";
    Files.writeString(configFile, original);
    List<WorkDirectoryDiagnostic> diagnostics = new ArrayList<>();
    Method migrate =
        WorkDirectoryResolver.class.getDeclaredMethod(
            "migrateWorkDirIfNeeded", Path.class, List.class, Path[].class);
    migrate.setAccessible(true);

    // The directory has already been selected when migration runs. Exercise a write denial
    // at that boundary without turning it into the initial unwritable-directory fallback.
    try (AutoCloseable ignored = FileAccessTestSupport.denyParentWrite(profile.target())) {
      assertThrows(
          IOException.class, () -> Files.createFile(profile.target().resolve("write-probe")));
      assertEquals(original, Files.readString(configFile));

      InvocationTargetException failure =
          assertThrows(
              InvocationTargetException.class,
              () ->
                  migrate.invoke(
                      null, profile.target(), diagnostics, new Path[] {profile.source()}));

      FileSystemException backupFailure =
          assertInstanceOf(FileSystemException.class, failure.getCause());
      String backupPath = profile.target().resolve("config.txt.unreadable-backup").toString();
      assertTrue(
          backupPath.equals(backupFailure.getFile())
              || backupPath.equals(backupFailure.getOtherFile()),
          "The original backup failure must escape before any replacement attempt");
      assertTrue(diagnostics.stream().anyMatch(value -> "unreadable-backup".equals(value.code())));
      assertEquals(original, Files.readString(configFile));
      assertTrue(backups(profile.target()).isEmpty());
    }
  }

  @Test
  void jsonReaderPreservesUnderlyingIoFailureInsteadOfReportingMalformedJson() throws Exception {
    IOException readFailure = new IOException("controlled mid-read failure");
    try (Reader reader =
        new Reader() {
          private final StringReader prefix = new StringReader("{\"user-note\":\"");

          @Override
          public int read(char[] buffer, int offset, int length) throws IOException {
            int count = prefix.read(buffer, offset, length);
            if (count < 0) throw readFailure;
            return count;
          }

          @Override
          public void close() {
            prefix.close();
          }
        }) {
      InvocationTargetException failure =
          assertThrows(InvocationTargetException.class, () -> readJson(reader));

      assertSame(readFailure, failure.getCause());
    }
  }

  @Test
  void jsonReaderStillIdentifiesMalformedJsonSeparately() throws Exception {
    try (Reader reader = new StringReader("{\"user-note\":")) {
      InvocationTargetException failure =
          assertThrows(InvocationTargetException.class, () -> readJson(reader));

      assertInstanceOf(JSONException.class, failure.getCause());
    }
  }

  private static Object readJson(Reader reader) throws ReflectiveOperationException {
    Method read = WorkDirectoryResolver.class.getDeclaredMethod("readJsonObject", Reader.class);
    read.setAccessible(true);
    return read.invoke(null, reader);
  }

  private Profile profile(boolean portable) throws IOException {
    Path home = Files.createDirectories(tempDir.resolve("home"));
    Path source = Files.createDirectories(home.resolve(WorkDirectoryResolver.USER_WORK_DIR_NAME));
    Files.writeString(source.resolve("config.txt"), userConfig("legacy"));
    Path publicDirectory = Files.createDirectories(tempDir.resolve("public"));
    Path app;
    Path target;
    if (portable) {
      Path portableRoot = Files.createDirectories(tempDir.resolve("portable"));
      Files.writeString(portableRoot.resolve(".lizzie-portable"), "portable");
      app = Files.createDirectories(portableRoot.resolve("app"));
      target = portableRoot.resolve("user-data");
    } else {
      app = Files.createDirectories(tempDir.resolve("installed"));
      target = publicDirectory.resolve("Documents").resolve("LizzieYzyNext");
    }
    Files.createDirectories(target.resolve("save"));
    WorkDirectoryEnvironment environment =
        new WorkDirectoryEnvironment(
            true,
            home.toString(),
            app.toString(),
            "",
            publicDirectory.toString(),
            null,
            List.of(app));
    return new Profile(environment, target.toAbsolutePath().normalize(), source);
  }

  private static String userConfig(String note) {
    return new JSONObject()
        .put("ui", new JSONObject().put("user-note", note))
        .put(
            "leelaz",
            new JSONObject()
                .put(
                    "engine-settings-list",
                    new JSONArray()
                        .put(
                            new JSONObject()
                                .put("name", "User engine")
                                .put("command", "user-katago"))))
        .put("future-user-setting", note)
        .toString(2);
  }

  private static boolean hasDiagnostic(WorkDirectoryResolution resolution, String code) {
    return resolution.diagnostics().stream().anyMatch(value -> code.equals(value.code()));
  }

  private static List<Path> backups(Path directory) throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .filter(path -> path.getFileName().toString().startsWith("config.txt.unreadable-backup"))
          .toList();
    }
  }

  private static Stream<Arguments> malformedProfiles() {
    return Stream.of(
        Arguments.of(true, ""),
        Arguments.of(true, "{\"ui\":{"),
        Arguments.of(false, ""),
        Arguments.of(false, "{\"ui\":{"));
  }

  private record Profile(WorkDirectoryEnvironment environment, Path target, Path source) {}
}
