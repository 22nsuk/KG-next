package featurecat.lizzie.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
    // An explicit path is never a request to look for another installation on PATH.
    if (!OsPlatform.isWindows()) {
      Path childDirectory = workingDirectory == null ? Path.of("") : workingDirectory.toPath();
      if (direct.isAbsolute() || direct.getParent() != null) {
        return childDirectory.resolve(direct).toAbsolutePath();
      }
      return Utils.findOnPath(List.of(token), childDirectory);
    }
    if (direct.isAbsolute() || direct.getParent() != null) {
      return direct.toAbsolutePath().normalize();
    }
    // CreateProcess searches native executable names, not the shell's PATHEXT alternatives.
    String name = token.contains(".") ? token : token + ".exe";
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
      Path candidate = directory.resolve(name);
      if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
    }
    return Utils.findOnPath(List.of(name), null);
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
