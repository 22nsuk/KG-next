package featurecat.lizzie.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ReadBoardUpdateInstaller {
  private static final String LEGACY_PACKAGE_PREFIX = "readboard-github-release-";
  private static final String WEBVIEW2_PACKAGE_PREFIX = "readboard-webview2-";
  private static final String PACKAGE_SUFFIX = ".zip";
  private static final DateTimeFormatter OPERATION_SUFFIX_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
  private static final Set<String> REQUIRED_FILES =
      Set.of(
          "readboard.exe",
          "readboard.dll",
          "readboard.runtimeconfig.json",
          "readboard.deps.json",
          "language_cn.txt");
  private static final List<String> USER_CONFIG_FILES =
      List.of("config.readboard.json", "config_readboard.txt", "config_readboard_others.txt");
  private final FileOperations fileOperations;

  public ReadBoardUpdateInstaller() {
    this(new FileOperations());
  }

  ReadBoardUpdateInstaller(FileOperations fileOperations) {
    this.fileOperations = fileOperations;
  }

  public void validateRequest(ReadBoardUpdateRequest request) throws IOException {
    inspectPackage(request);
  }

  public void install(ReadBoardUpdateRequest request, Path installDirectory) throws IOException {
    PackageLayout layout = inspectPackage(request);
    Path targetDirectory = installDirectory.toAbsolutePath().normalize();
    Path parentDirectory = targetDirectory.getParent();
    if (parentDirectory == null) {
      throw new IOException("ReadBoard install directory has no parent directory.");
    }

    String operationSuffix = OPERATION_SUFFIX_FORMAT.format(LocalDateTime.now());
    Path stagingDirectory = parentDirectory.resolve("readboard.staging-" + operationSuffix);
    Path backupDirectory = parentDirectory.resolve("readboard.backup-" + operationSuffix);
    boolean movedCurrentDirectory = false;
    try {
      extractPackage(layout, stagingDirectory);
      preserveUserConfig(targetDirectory, stagingDirectory);
      if (Files.exists(targetDirectory)) {
        fileOperations.moveDirectory(targetDirectory, backupDirectory);
        movedCurrentDirectory = true;
      }
      fileOperations.moveDirectory(stagingDirectory, targetDirectory);
    } catch (IOException installFailure) {
      if (movedCurrentDirectory
          && !Files.exists(targetDirectory)
          && Files.exists(backupDirectory)) {
        try {
          fileOperations.moveDirectory(backupDirectory, targetDirectory);
        } catch (IOException rollbackFailure) {
          installFailure.addSuppressed(rollbackFailure);
        }
      }
      try {
        fileOperations.deleteRecursively(stagingDirectory);
      } catch (IOException cleanupFailure) {
        installFailure.addSuppressed(cleanupFailure);
      }
      throw installFailure;
    }
  }

  private static void preserveUserConfig(Path currentDirectory, Path stagingDirectory)
      throws IOException {
    // The caller must confirm helper exit first: shutdown can persist its final settings.
    // Preserve bytes, including legacy encodings; migration belongs to the new helper.
    for (String fileName : USER_CONFIG_FILES) {
      Path source = currentDirectory.resolve(fileName);
      if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("ReadBoard configuration is not a regular file: " + source);
      }
      Files.copy(source, stagingDirectory.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private PackageLayout inspectPackage(ReadBoardUpdateRequest request) throws IOException {
    if (request == null) {
      throw new IOException("ReadBoard update request is empty.");
    }
    Path zipPath = request.zipPath().toPath().toAbsolutePath().normalize();
    if (!Files.isRegularFile(zipPath)) {
      throw new IOException("ReadBoard update package does not exist.");
    }
    if (!isExpectedPackageFileName(request.versionTag(), zipPath.getFileName().toString())) {
      throw new IOException(
          "ReadBoard update package filename does not match the requested version.");
    }

    try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
      List<ZipEntry> entries = new ArrayList<ZipEntry>();
      Set<String> rootSegments = new LinkedHashSet<String>();
      Set<String> requiredFiles = new LinkedHashSet<String>();
      Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
      while (enumeration.hasMoreElements()) {
        ZipEntry entry = enumeration.nextElement();
        EntryPath entryPath = normalizeEntryPath(entry);
        if (entryPath == null) {
          continue;
        }
        entries.add(entry);
        rootSegments.add(entryPath.rootSegment());
      }

      if (entries.isEmpty()) {
        throw new IOException("ReadBoard update package is empty.");
      }

      String stripRootSegment =
          rootSegments.size() == 1 && !rootSegments.contains("")
              ? rootSegments.iterator().next()
              : "";

      for (ZipEntry entry : entries) {
        EntryPath entryPath = normalizeEntryPath(entry);
        if (entryPath == null || entry.isDirectory()) {
          continue;
        }
        Path relativePath = resolveRelativePath(entryPath.normalizedPath(), stripRootSegment);
        if (relativePath == null) {
          continue;
        }
        requiredFiles.add(relativePath.toString().replace('\\', '/'));
      }

      if (!requiredFiles.containsAll(REQUIRED_FILES)) {
        throw new IOException("ReadBoard update package is missing required runtime files.");
      }

      return new PackageLayout(zipPath, stripRootSegment, entries);
    }
  }

  private void extractPackage(PackageLayout layout, Path stagingDirectory) throws IOException {
    fileOperations.deleteRecursively(stagingDirectory);
    Files.createDirectories(stagingDirectory);
    try (ZipFile zipFile = new ZipFile(layout.zipPath().toFile())) {
      for (ZipEntry entry : layout.entries()) {
        EntryPath entryPath = normalizeEntryPath(entry);
        if (entryPath == null) {
          continue;
        }
        Path relativePath =
            resolveRelativePath(entryPath.normalizedPath(), layout.stripRootSegment());
        if (relativePath == null) {
          continue;
        }
        Path targetPath = stagingDirectory.resolve(relativePath).normalize();
        if (!targetPath.startsWith(stagingDirectory)) {
          throw new IOException("ReadBoard update package contains an unsafe path.");
        }
        if (entry.isDirectory()) {
          Files.createDirectories(targetPath);
          continue;
        }
        Files.createDirectories(targetPath.getParent());
        try (InputStream entryStream = zipFile.getInputStream(entry)) {
          Files.copy(entryStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static EntryPath normalizeEntryPath(ZipEntry entry) throws IOException {
    String rawName = entry.getName();
    if (rawName == null) {
      throw new IOException("ReadBoard update package contains an unnamed entry.");
    }
    String normalizedName = rawName.replace('\\', '/');
    while (normalizedName.endsWith("/")) {
      normalizedName = normalizedName.substring(0, normalizedName.length() - 1);
    }
    if (normalizedName.isEmpty()) {
      return null;
    }
    if (normalizedName.startsWith("/") || normalizedName.startsWith("//")) {
      throw new IOException("ReadBoard update package contains an absolute path.");
    }
    if (normalizedName.length() >= 2
        && Character.isLetter(normalizedName.charAt(0))
        && normalizedName.charAt(1) == ':') {
      throw new IOException("ReadBoard update package contains a drive-relative path.");
    }

    String[] segments = normalizedName.split("/");
    for (String segment : segments) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw new IOException("ReadBoard update package contains an unsafe relative path.");
      }
    }
    return new EntryPath(normalizedName, segments[0]);
  }

  private static Path resolveRelativePath(String normalizedEntryPath, String stripRootSegment)
      throws IOException {
    String relativeText = normalizedEntryPath;
    if (!stripRootSegment.isEmpty()) {
      String prefix = stripRootSegment + "/";
      if (relativeText.equals(stripRootSegment)) {
        return null;
      }
      if (!relativeText.startsWith(prefix)) {
        throw new IOException(
            "ReadBoard update package contains inconsistent top-level directories.");
      }
      relativeText = relativeText.substring(prefix.length());
    }
    if (relativeText.isEmpty()) {
      return null;
    }
    Path relativePath = Path.of(relativeText).normalize();
    if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
      throw new IOException("ReadBoard update package contains an unsafe extraction path.");
    }
    return relativePath;
  }

  private static boolean isExpectedPackageFileName(String versionTag, String fileName) {
    return (LEGACY_PACKAGE_PREFIX + versionTag + PACKAGE_SUFFIX).equals(fileName)
        || (WEBVIEW2_PACKAGE_PREFIX + versionTag + PACKAGE_SUFFIX).equals(fileName);
  }

  static class FileOperations {
    void moveDirectory(Path source, Path target) throws IOException {
      try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(source, target);
      }
    }

    void deleteRecursively(Path directory) throws IOException {
      if (!Files.exists(directory)) {
        return;
      }
      List<Path> paths = new ArrayList<Path>();
      try (var stream = Files.walk(directory)) {
        stream.forEach(paths::add);
      } catch (UncheckedIOException traversalFailure) {
        throw traversalFailure.getCause();
      }
      for (int index = paths.size() - 1; index >= 0; index--) {
        Files.deleteIfExists(paths.get(index));
      }
    }
  }

  private record EntryPath(String normalizedPath, String rootSegment) {}

  private record PackageLayout(Path zipPath, String stripRootSegment, List<ZipEntry> entries) {}
}
