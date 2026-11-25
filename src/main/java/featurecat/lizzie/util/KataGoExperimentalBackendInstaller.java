package featurecat.lizzie.util;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.ProgressListener;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs self-contained experimental KataGo backends from pinned official release assets. */
public final class KataGoExperimentalBackendInstaller {
  private static final String USER_AGENT = "LizzieYzy-Next-Backend-Installer";
  private static final String BACKEND_MARKER = "lizzieyzy-next-engine-backend.txt";
  private static final String ENGINE_MANIFEST = "lizzieyzy-next-katago-engine-manifest.txt";
  private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);

  private KataGoExperimentalBackendInstaller() {}

  public enum Backend {
    DIRECTML(
        "windows-directml",
        "windows-x64-directml",
        "directml",
        "AutoSetup.experimentalDirectMl",
        "DirectML (DirectX 12 GPU)"),
    OPENVINO_GPU(
        "windows-openvino",
        "windows-x64-openvino-gpu",
        "openvino",
        "AutoSetup.experimentalOpenVinoGpu",
        "OpenVINO (Intel GPU)"),
    OPENVINO_NPU(
        "windows-openvino",
        "windows-x64-openvino-npu",
        "openvino-npu",
        "AutoSetup.experimentalOpenVinoNpu",
        "OpenVINO (Intel NPU)"),
    ROCM_GFX103X(
        "windows-rocm-gfx103x",
        "windows-x64-rocm-gfx103x",
        "rocm-gfx103x",
        "AutoSetup.experimentalRocmGfx103x",
        "ROCm gfx103X (RX 6000 / RDNA2)"),
    ROCM_GFX110X(
        "windows-rocm-gfx110x",
        "windows-x64-rocm-gfx110x",
        "rocm-gfx110x",
        "AutoSetup.experimentalRocmGfx110x",
        "ROCm gfx110X (RX 7000 / RDNA3)"),
    ROCM_GFX1151(
        "windows-rocm-gfx1151",
        "windows-x64-rocm-gfx1151",
        "rocm-gfx1151",
        "AutoSetup.experimentalRocmGfx1151",
        "ROCm gfx1151 (Ryzen AI Max)"),
    ROCM_GFX120X(
        "windows-rocm-gfx120x",
        "windows-x64-rocm-gfx120x",
        "rocm-gfx120x",
        "AutoSetup.experimentalRocmGfx120x",
        "ROCm gfx120X (RX 9000 / RDNA4)");

    private final String assetId;
    private final String installDir;
    private final String marker;
    private final String resourceKey;
    private final String fallbackName;

    Backend(
        String assetId,
        String installDir,
        String marker,
        String resourceKey,
        String fallbackName) {
      this.assetId = assetId;
      this.installDir = installDir;
      this.marker = marker;
      this.resourceKey = resourceKey;
      this.fallbackName = fallbackName;
    }

    public KataGoAssetCatalog.Asset asset() {
      return KataGoAssetCatalog.get().asset(assetId);
    }

    public String displayName() {
      return resource(resourceKey, fallbackName);
    }

    @Override
    public String toString() {
      return displayName();
    }

    String installDir() {
      return installDir;
    }

    public String marker() {
      return marker;
    }
  }

  public record Status(Backend backend, Path enginePath, boolean installed, boolean active) {}

  public static Status inspect(SetupSnapshot snapshot, Backend backend) {
    Path engine = resolveInstalledEngine(snapshot, backend);
    boolean installed = isCurrentInstall(engine, backend);
    boolean active =
        installed
            && snapshot != null
            && snapshot.enginePath != null
            && normalize(snapshot.enginePath).equals(normalize(engine));
    return new Status(backend, engine, installed, active);
  }

  public static SetupResult installAndApply(
      SetupSnapshot snapshot,
      Backend backend,
      ProgressListener listener,
      DownloadSession session)
      throws IOException {
    requireWindows();
    if (snapshot == null || !snapshot.hasConfigs() || !snapshot.hasWeight()) {
      throw new IOException(
          resource(
              "AutoSetup.experimentalBackendNeedsSetup",
              "Configure a local KataGo weight and config before installing this backend."));
    }
    if (!INSTALLING.compareAndSet(false, true)) {
      throw new IOException(
          resource(
              "AutoSetup.experimentalBackendBusy",
              "Another experimental backend installation is already running."));
    }
    DownloadSession activeSession = session == null ? new DownloadSession() : session;
    try {
      Path engine = targetEnginePath(backend);
      if (!isCurrentInstall(engine, backend)) {
        KataGoAssetCatalog.Asset asset = backend.asset();
        Path archive = downloadAsset(asset, listener, activeSession);
        activeSession.throwIfCancelled();
        installArchive(archive, backend, engine.getParent());
        deleteFileQuietly(archive);
      }
      if (listener != null) {
        listener.onProgress(backend.displayName(), backend.asset().sizeBytes(), backend.asset().sizeBytes());
      }
      return KataGoAutoSetupHelper.applyEngineProfile(
          snapshot.withEnginePath(engine), "KataGo " + backend.displayName(), true);
    } finally {
      INSTALLING.set(false);
    }
  }

  static void installArchive(Path archive, Backend backend, Path targetDir) throws IOException {
    Path parent = targetDir.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new IOException("Experimental KataGo target directory is invalid: " + targetDir);
    }
    Files.createDirectories(parent);
    Path extracted = Files.createTempDirectory(parent, ".katago-extract-");
    Path prepared = Files.createTempDirectory(parent, ".katago-install-");
    Path backup = parent.resolve(targetDir.getFileName() + ".previous");
    boolean movedExisting = false;
    try {
      extractZipSecurely(archive, extracted);
      Path engine = findEngine(extracted);
      copyTree(engine.getParent(), prepared);
      Files.writeString(
          prepared.resolve(BACKEND_MARKER), backend.marker() + "\n", StandardCharsets.UTF_8);
      Files.writeString(
          prepared.resolve(ENGINE_MANIFEST), manifestText(backend), StandardCharsets.UTF_8);
      deleteTree(backup);
      if (Files.exists(targetDir)) {
        moveDirectory(targetDir, backup);
        movedExisting = true;
      }
      try {
        moveDirectory(prepared, targetDir);
      } catch (IOException installFailure) {
        if (movedExisting && Files.exists(backup) && !Files.exists(targetDir)) {
          try {
            moveDirectory(backup, targetDir);
          } catch (IOException restoreFailure) {
            installFailure.addSuppressed(restoreFailure);
          }
        }
        throw installFailure;
      }
      deleteTreeQuietly(backup);
    } finally {
      deleteTreeQuietly(extracted);
      deleteTreeQuietly(prepared);
    }
  }

  private static Path downloadAsset(
      KataGoAssetCatalog.Asset asset, ProgressListener listener, DownloadSession session)
      throws IOException {
    Path cache = runtimeRoot().resolve("katago-backend-downloads");
    Files.createDirectories(cache);
    Path target = cache.resolve(asset.assetName());
    Path part = cache.resolve(asset.assetName() + ".part");
    if (isExpectedFile(target, asset.sizeBytes(), asset.sha256())) {
      return target;
    }
    Files.deleteIfExists(target);
    if (Files.isRegularFile(part) && Files.size(part) > asset.sizeBytes()) {
      Files.delete(part);
    }
    long resumeFrom = Files.isRegularFile(part) ? Files.size(part) : 0L;
    while (true) {
      session.throwIfCancelled();
      HttpURLConnection connection =
          (HttpURLConnection)
              NetworkProxy.openConnection(URI.create(assetUrl(asset)).toURL());
      session.attach(connection);
      try {
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/octet-stream,*/*");
        if (resumeFrom > 0L) {
          connection.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        }
        int code = connection.getResponseCode();
        if (resumeFrom > 0L && (code == 416 || code == HttpURLConnection.HTTP_OK)) {
          if (code == 416 && isExpectedFile(part, asset.sizeBytes(), asset.sha256())) {
            break;
          }
          Files.deleteIfExists(part);
          resumeFrom = 0L;
          continue;
        }
        if (code < 200 || code >= 400) {
          throw new IOException("HTTP " + code + " from " + assetUrl(asset));
        }
        boolean resumed = resumeFrom > 0L && code == HttpURLConnection.HTTP_PARTIAL;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
            OutputStream output =
                Files.newOutputStream(
                    part,
                    StandardOpenOption.CREATE,
                    resumed ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
          byte[] buffer = new byte[64 * 1024];
          long downloaded = resumed ? resumeFrom : 0L;
          long lastReport = 0L;
          int read;
          while ((read = input.read(buffer)) >= 0) {
            session.throwIfCancelled();
            output.write(buffer, 0, read);
            downloaded += read;
            long now = System.currentTimeMillis();
            if (listener != null && (now - lastReport >= 120L || downloaded == asset.sizeBytes())) {
              listener.onProgress(asset.assetName(), downloaded, asset.sizeBytes());
              lastReport = now;
            }
          }
        }
        break;
      } finally {
        connection.disconnect();
        session.clear();
      }
    }
    session.throwIfCancelled();
    if (!isExpectedFile(part, asset.sizeBytes(), asset.sha256())) {
      throw new IOException(
          resource(
              "AutoSetup.experimentalBackendChecksumFailed",
              "The experimental backend download failed size or SHA-256 verification."));
    }
    try {
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  private static String assetUrl(KataGoAssetCatalog.Asset asset) {
    String property = "lizzie.experimentalBackend." + asset.id() + ".url";
    return System.getProperty(property, KataGoAssetCatalog.get().assetDownloadUrl(asset));
  }

  private static Path targetEnginePath(Backend backend) {
    return runtimeRoot()
        .resolve("engines")
        .resolve("katago")
        .resolve(backend.installDir())
        .resolve("katago.exe")
        .toAbsolutePath()
        .normalize();
  }

  private static Path resolveInstalledEngine(SetupSnapshot snapshot, Backend backend) {
    if (snapshot != null
        && snapshot.enginePath != null
        && isCurrentInstall(snapshot.enginePath, backend)) {
      return snapshot.enginePath.toAbsolutePath().normalize();
    }
    return targetEnginePath(backend);
  }

  private static Path runtimeRoot() {
    if (Lizzie.config != null) {
      return Lizzie.config.getRuntimeWorkDirectory().toPath().toAbsolutePath().normalize();
    }
    return Path.of(System.getProperty("user.dir", "."), "runtime").toAbsolutePath().normalize();
  }

  private static boolean isCurrentInstall(Path engine, Backend backend) {
    if (!Files.isRegularFile(engine) || engine.getParent() == null) {
      return false;
    }
    try {
      String marker =
          Files.readString(engine.getParent().resolve(BACKEND_MARKER), StandardCharsets.UTF_8)
              .trim();
      String manifest =
          Files.readString(engine.getParent().resolve(ENGINE_MANIFEST), StandardCharsets.UTF_8);
      return backend.marker().equalsIgnoreCase(marker)
          && manifest.contains("KataGo release: " + KataGoAssetCatalog.get().katagoReleaseTag())
          && manifest.contains("Asset SHA-256: " + backend.asset().sha256());
    } catch (IOException e) {
      return false;
    }
  }

  private static String manifestText(Backend backend) {
    KataGoAssetCatalog catalog = KataGoAssetCatalog.get();
    KataGoAssetCatalog.Asset asset = backend.asset();
    return "KataGo release: "
        + catalog.katagoReleaseTag()
        + "\nAsset: "
        + asset.assetName()
        + "\nAsset SHA-256: "
        + asset.sha256()
        + "\nBackend: "
        + backend.marker()
        + "\n";
  }

  private static void extractZipSecurely(Path archive, Path target) throws IOException {
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        Path output = target.resolve(entry.getName().replace('\\', '/')).normalize();
        if (!output.startsWith(target)) {
          throw new IOException("Unsafe path in KataGo backend archive: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(output);
        } else {
          Files.createDirectories(output.getParent());
          Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static Path findEngine(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      Optional<Path> engine =
          paths
              .filter(Files::isRegularFile)
              .filter(path -> "katago.exe".equalsIgnoreCase(path.getFileName().toString()))
              .findFirst();
      if (engine.isPresent()) {
        return engine.get();
      }
    }
    throw new IOException("KataGo backend archive did not contain katago.exe");
  }

  private static void copyTree(Path source, Path target) throws IOException {
    try (var paths = Files.walk(source)) {
      for (Path path : paths.sorted().toList()) {
        Path relative = source.relativize(path);
        Path destination = target.resolve(relative);
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          Files.createDirectories(destination.getParent());
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static void moveDirectory(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target);
    }
  }

  private static void deleteTree(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static void deleteTreeQuietly(Path root) {
    try {
      deleteTree(root);
    } catch (IOException ignored) {
      // Installation state is authoritative; stale cleanup paths can be removed on a later run.
    }
  }

  private static void deleteFileQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // A verified cache file is safe to retain when antivirus software still has it open.
    }
  }

  private static boolean isExpectedFile(Path path, long expectedSize, String expectedSha256)
      throws IOException {
    return Files.isRegularFile(path)
        && Files.size(path) == expectedSize
        && expectedSha256.equalsIgnoreCase(sha256(path));
  }

  private static String sha256(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(path)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          digest.update(buffer, 0, read);
        }
      }
      StringBuilder result = new StringBuilder(64);
      for (byte value : digest.digest()) {
        result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 is unavailable", e);
    }
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private static void requireWindows() throws IOException {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      throw new IOException(
          resource(
              "AutoSetup.experimentalBackendWindowsOnly",
              "These experimental backend packages are currently available on Windows only."));
    }
  }

  private static String resource(String key, String fallback) {
    try {
      return Lizzie.resourceBundle == null ? fallback : Lizzie.resourceBundle.getString(key);
    } catch (Exception e) {
      return fallback;
    }
  }
}
