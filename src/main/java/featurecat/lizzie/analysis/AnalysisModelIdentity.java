package featurecat.lizzie.analysis;

import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** File observations owned by one engine launch; never performs I/O in an analysis publication. */
final class AnalysisModelIdentity {
  private static final ExecutorService READERS =
      Executors.newFixedThreadPool(
          2,
          task -> {
            Thread thread = new Thread(task, "analysis-model-identity");
            thread.setDaemon(true);
            return thread;
          });
  static final FilesIdentity UNKNOWN = new FilesIdentity("", "", "");

  private AnalysisModelIdentity() {}

  record FilesIdentity(String modelName, String modelSha256, String configSha256) {}

  static Lookup local(List<String> command, Path workingDirectory) {
    if (!directLocalCommand(command)) return unknown();
    List<String> frozen = List.copyOf(command);
    Path directory =
        (workingDirectory == null ? Path.of("") : workingDirectory).toAbsolutePath().normalize();
    return new Lookup(READERS.submit(() -> readLocal(frozen, directory)));
  }

  static Lookup knownModel(String modelIdentifier) {
    return new Lookup(
        CompletableFuture.completedFuture(
            new FilesIdentity(modelIdentifier == null ? "" : modelIdentifier, "", "")));
  }

  static Lookup unknown() {
    return new Lookup(CompletableFuture.completedFuture(UNKNOWN));
  }

  static FilesIdentity readLocal(List<String> command, Path workingDirectory) {
    if (!directLocalCommand(command)) return UNKNOWN;
    try {
      List<Path> models =
          pathsFor(command, workingDirectory, List.of("-model", "--model", "-weights", "--weights"));
      List<Path> configs = pathsFor(command, workingDirectory, List.of("-config", "--config"));
      String modelName = "";
      String modelHash = "";
      if (models.size() == 1 && Files.isRegularFile(models.get(0))) {
        Path model = models.get(0);
        BasicFileAttributes before = Files.readAttributes(model, BasicFileAttributes.class);
        String observedName = KataGoAutoSetupHelper.readWeightModelName(model);
        String observedHash = digest(model);
        if (unchanged(before, Files.readAttributes(model, BasicFileAttributes.class))) {
          modelName = observedName;
          modelHash = observedHash;
        }
      }
      // Multiple configuration inputs can have engine-specific include/override semantics.
      // Do not claim that the last path alone identifies those settings.
      String configHash = configs.size() == 1 ? stableDigest(configs.get(0)) : "";
      return new FilesIdentity(modelName, modelHash, configHash);
    } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
      return UNKNOWN;
    }
  }

  private static boolean directLocalCommand(List<String> command) {
    return command != null
        && !command.isEmpty()
        && command.get(0) != null
        && !command.get(0).isBlank()
        && !CommandLaunchHelper.isIndirectLauncher(command.get(0));
  }

  private static List<Path> pathsFor(
      List<String> command, Path directory, List<String> flags) {
    List<Path> paths = new ArrayList<>();
    for (int index = 1; index < command.size(); index++) {
      String argument = command.get(index);
      for (String flag : flags) {
        String value = null;
        if (argument.equals(flag)) {
          if (++index >= command.size()) return List.of();
          value = command.get(index);
        } else if (argument.startsWith(flag + "=")) {
          value = argument.substring(flag.length() + 1);
        }
        if (value != null) {
          if (value.isBlank()) return List.of();
          Path path = Path.of(value);
          paths.add((path.isAbsolute() ? path : directory.resolve(path)).normalize());
          break;
        }
      }
    }
    return paths;
  }

  private static String stableDigest(Path path) throws IOException {
    if (!Files.isRegularFile(path)) return "";
    BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
    String hash = digest(path);
    return unchanged(before, Files.readAttributes(path, BasicFileAttributes.class)) ? hash : "";
  }

  private static boolean unchanged(BasicFileAttributes before, BasicFileAttributes after) {
    return before.size() == after.size()
        && before.lastModifiedTime().equals(after.lastModifiedTime())
        && Objects.equals(before.fileKey(), after.fileKey());
  }

  private static String digest(Path path) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", unavailable);
    }
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      for (int count; (count = input.read(buffer)) >= 0; ) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Identity lookup stopped");
        digest.update(buffer, 0, count);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static final class Lookup {
    private final Future<FilesIdentity> result;

    Lookup(Future<FilesIdentity> result) {
      this.result = result;
    }

    FilesIdentity snapshot() {
      if (!result.isDone()) return UNKNOWN;
      try {
        return result.get();
      } catch (CancellationException | ExecutionException unavailable) {
        return UNKNOWN;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return UNKNOWN;
      }
    }

    void close() {
      result.cancel(true);
    }
  }
}
