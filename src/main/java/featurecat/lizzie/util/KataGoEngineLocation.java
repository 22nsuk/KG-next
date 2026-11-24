package featurecat.lizzie.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Engine installation lookup shared by launch and logging, independent of the runtime catalog. */
public final class KataGoEngineLocation {
  static final String NVIDIA_ENGINE_DIR = "windows-x64-nvidia";
  static final String NVIDIA_CUDA13_ENGINE_DIR = "windows-x64-nvidia-cuda13";
  static final String NVIDIA50_CUDA_ENGINE_DIR = "windows-x64-nvidia50-cuda";
  static final String NVIDIA_TRT_ENGINE_DIR = "windows-x64-nvidia-tensorrt";
  static final String NVIDIA50_TRT_ENGINE_DIR = "windows-x64-nvidia50-trt";
  static final String HUMAN_SL_CUDA_COMPANION_NAME = "katago-human-sl-cuda.exe";
  static final String ENGINE_BACKEND_MARKER_NAME = "lizzieyzy-next-engine-backend.txt";

  private KataGoEngineLocation() {}

  // Setup and failure diagnostics also use the intended path of an executable that is missing.
  static Path configuredExecutable(String token) {
    if (token == null || token.trim().isEmpty()) {
      return null;
    }
    Path resolved = Utils.resolveExistingExecutable(token);
    if (resolved != null) {
      return resolved.toAbsolutePath().normalize();
    }
    try {
      return Path.of(token).toAbsolutePath().normalize();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  public static Path bootstrapExecutable(List<String> commands, File workingDirectory) {
    if (commands == null || commands.isEmpty()) {
      return null;
    }
    String token = commands.get(0);
    if (token == null || token.isBlank()) {
      return null;
    }
    Path direct;
    try {
      direct = Path.of(token);
    } catch (InvalidPathException invalid) {
      // The remaining command arguments can still provide independent startup facts.
      return null;
    }
    if (!OsPlatform.isWindows()) {
      Path childDirectory = workingDirectory == null ? Path.of("") : workingDirectory.toPath();
      try {
        return unixBootstrapExecutable(token, direct, childDirectory);
      } catch (IOException | InvalidPathException | SecurityException unavailable) {
        return null;
      }
    }
    Path selected = windowsBootstrapExecutable(direct);
    if (selected == null) return null;
    try {
      // Classify the installed filename (including Win32 aliases), keeping its installation directory.
      return selected.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException | SecurityException unavailable) {
      // Startup failures can still report markers beside the explicitly configured path.
      return selected;
    }
  }

  private static Path windowsBootstrapExecutable(Path program) {
    if (program.getFileName() == null) return null;
    String leaf = program.getFileName().toString();
    String nativeName = leaf.contains(".") ? leaf : leaf + ".exe";
    List<String> names = List.of(nativeName);
    if (program.getRoot() != null || program.getParent() != null) {
      Path explicit = program.toAbsolutePath().normalize();
      if (explicit.getParent() == null) return null;
      // A qualified operand tries the exact file before the default .exe, in this directory only.
      Path found =
          Utils.findInDirectory(
              explicit.getParent(), leaf.equals(nativeName) ? names : List.of(leaf, nativeName));
      return found != null ? found : explicit;
    }
    // CreateProcessW checks the parent image, native cwd and Windows directories before PATH.
    List<Path> directories = new ArrayList<>();
    ProcessHandle.current().info().command()
        .map(Path::of)
        .map(Path::getParent)
        .ifPresent(directories::add);
    directories.add(Path.of(""));
    String windows = System.getenv("SystemRoot");
    if (windows != null && !windows.isBlank()) {
      Path root = Path.of(windows);
      directories.add(root.resolve("System32"));
      directories.add(root.resolve("System"));
      directories.add(root);
    }
    for (Path directory : directories) {
      Path candidate = Utils.findInDirectory(directory, names);
      if (candidate != null) return candidate.toAbsolutePath().normalize();
    }
    return Utils.findOnPath(names);
  }

  private static Path unixInstallationPath(Path executable) throws IOException {
    Path absolute = executable.toAbsolutePath();
    if (absolute.equals(absolute.normalize())) return absolute;
    // Let the filesystem traverse symlinks before "..", retaining the configured executable name.
    return absolute.getParent().toRealPath().resolve(absolute.getFileName());
  }

  private static Path unixBootstrapExecutable(String token, Path program, Path childDirectory)
      throws IOException {
    // Path drops trailing slashes. exec receives the verbatim operand, which must name a file.
    if (token.endsWith("/") || program.endsWith(".") || program.endsWith("..")) return null;
    if (token.indexOf('/') >= 0) {
      Path configured = childDirectory.resolve(program);
      return Files.isDirectory(configured) ? null : unixInstallationPath(configured);
    }

    String path = System.getenv("PATH");
    // With PATH unset, both libc and OpenJDK search these system directories on our Unix targets.
    // OpenJDK can additionally search the child cwd when passing an explicit environment.
    String searchPath = path == null ? "/bin:/usr/bin" : path;
    Path installation = null;
    for (String entry : searchPath.split(File.pathSeparator, -1)) {
      Path candidate = childDirectory.resolve(entry).resolve(program);
      if (!isExecutableFile(candidate)) continue;
      Path executable = unixInstallationPath(candidate);
      // Unix can skip an executable at launch. Only attribute markers to a unique installation.
      if (installation == null) {
        installation = executable;
      } else if (!Files.isSameFile(installation.getParent(), executable.getParent())) {
        return null;
      }
    }
    if (path == null
        && installation != null
        && isExecutableFile(childDirectory.resolve(program))
        && !Files.isSameFile(installation.getParent(), childDirectory.toAbsolutePath())) {
      return null;
    }
    return installation;
  }

  private static boolean isExecutableFile(Path path) {
    return Files.isRegularFile(path) && Files.isExecutable(path);
  }

  public static String nvidiaBackendFromPath(Path executable) {
    if (executable == null) {
      return null;
    }
    Path name = executable.getFileName();
    if (name != null && HUMAN_SL_CUDA_COMPANION_NAME.equalsIgnoreCase(name.toString())) {
      return "nvidia50-cuda";
    }
    String path =
        executable.toAbsolutePath().normalize().toString()
            .replace('\\', '/').toLowerCase(Locale.ROOT);
    if (path.contains("/" + NVIDIA_TRT_ENGINE_DIR + "/")
        || path.contains("/" + NVIDIA50_TRT_ENGINE_DIR + "/")) {
      return "nvidia-tensorrt";
    }
    if (path.contains("/" + NVIDIA50_CUDA_ENGINE_DIR + "/")) {
      return "nvidia50-cuda";
    }
    if (path.contains("/" + NVIDIA_ENGINE_DIR + "/")
        || path.contains("/" + NVIDIA_CUDA13_ENGINE_DIR + "/")) {
      return "nvidia";
    }
    return null;
  }

  public static String nvidiaBackendFromMarker(String marker) {
    if ("nvidia50-trt".equals(marker)) {
      return "nvidia-tensorrt";
    }
    return marker.startsWith("nvidia") ? marker : null;
  }

  public static String readBackendMarker(Path executable) {
    if (executable == null) {
      return "";
    }
    Path directory = executable.toAbsolutePath().normalize().getParent();
    if (directory == null) {
      return "";
    }
    Path file = directory.resolve(ENGINE_BACKEND_MARKER_NAME);
    if (!Files.isRegularFile(file)) {
      return "";
    }
    try {
      return Files.readString(file, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
    } catch (IOException ignored) {
      return "";
    }
  }
}
