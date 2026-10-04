package featurecat.lizzie.util;

import featurecat.lizzie.Config;
import featurecat.lizzie.gui.EngineData;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONObject;

/** Ownership of the generated bundled entry, independent of its display name and model contents. */
public final class BundledKataGoProfile {
  public static final String TYPE = "bundled-katago";
  public static final String CUDA13_TYPE = "bundled-katago-cuda13";
  public static final String CUDA13_DIRECTORY = "windows-x64-nvidia-cuda13";

  private BundledKataGoProfile() {}

  public static boolean isManaged(EngineData entry) {
    return entry != null
        && isManaged(
            entry.managedProfileType,
            entry.managedProfileCommand,
            entry.commands,
            entry.useJavaSSH);
  }

  public static boolean isManaged(JSONObject entry) {
    return isManaged(
        entry.optString("managedProfileType"),
        entry.optString("managedProfileCommand"),
        entry.optString("command"),
        entry.optBoolean("useJavaSSH", false));
  }

  public static boolean isManagedCuda13(JSONObject entry) {
    return !entry.optBoolean("useJavaSSH", false)
        && CUDA13_TYPE.equals(entry.optString("managedProfileType"))
        && !entry.optString("managedProfileCommand").isBlank()
        && entry.optString("managedProfileCommand").equals(entry.optString("command"));
  }

  public static boolean isCuda13Command(String command) {
    List<String> parts = Utils.splitCommand(command == null ? "" : command);
    if (parts.isEmpty()) return false;
    String executable = parts.get(0).replace('\\', '/');
    return executable.endsWith("/engines/katago/" + CUDA13_DIRECTORY + "/katago.exe")
        || executable.equals("engines/katago/" + CUDA13_DIRECTORY + "/katago.exe");
  }

  private static boolean isManaged(String type, String generated, String command, boolean ssh) {
    return !ssh
        && TYPE.equals(type)
        && generated != null
        && !generated.isBlank()
        && generated.equals(command);
  }

  public static void claim(EngineData entry) {
    entry.managedProfileType = isCuda13Command(entry.commands) ? CUDA13_TYPE : TYPE;
    entry.managedProfileCommand = entry.commands;
  }

  public static void claim(JSONObject entry) {
    entry.put("managedProfileType", isCuda13Command(entry.optString("command")) ? CUDA13_TYPE : TYPE);
    entry.put("managedProfileCommand", entry.getString("command"));
  }

  public static boolean canMigrate(JSONObject entry, Path appRoot) {
    return canMigrate(
        entry.has("managedProfileType"),
        entry.optBoolean("useJavaSSH", false),
        entry.optString("name"),
        entry.optString("command"),
        appRoot);
  }

  public static boolean canMigrate(EngineData entry, Path appRoot) {
    return entry != null
        && canMigrate(
            !entry.managedProfileType.isBlank(),
            entry.useJavaSSH,
            entry.name,
            entry.commands,
            appRoot);
  }

  private static boolean canMigrate(
      boolean hasIdentity, boolean ssh, String name, String command, Path appRoot) {
    if (hasIdentity || ssh || isCuda13Command(command)) return false;
    boolean legacyName = "KataGo Bundled".equals(name) || "KataGo Auto Setup".equals(name);
    return legacyName && (command == null || command.isBlank())
        || isDefaultCommand(command, appRoot, legacyName);
  }

  /**
   * Exact generated GTP shape; arbitrary extra flags and custom configs are not ownership proof.
   */
  public static boolean isDefaultCommand(String command, Path appRoot, boolean allowPreviousRoot) {
    if (appRoot == null || !Config.isBundledKataGoCommand(command)) return false;
    List<String> parts = Utils.splitCommand(command);
    if (parts.size() < 2 || !"gtp".equals(parts.get(1))) return false;
    try {
      Path current = appRoot.toAbsolutePath().normalize();
      Path executable = resolve(current, parts.get(0));
      Path platform = executable.getParent();
      Path katago = platform == null ? null : platform.getParent();
      Path engines = katago == null ? null : katago.getParent();
      Path source = engines == null ? null : engines.getParent();
      String binary = executable.getFileName().toString();
      if (source == null
          || !("katago".equals(binary) || "katago.exe".equals(binary))
          || !"katago".equals(katago.getFileName().toString())
          || !"engines".equals(engines.getFileName().toString())
          || !allowPreviousRoot && !current.equals(source)) return false;
      if (parts.size() == 2) return allowPreviousRoot;
      String model = null;
      String config = null;
      for (int i = 2; i < parts.size(); i++) {
        String flag = parts.get(i);
        int equals = flag.indexOf('=');
        String value;
        if (equals >= 0) {
          value = flag.substring(equals + 1);
          flag = flag.substring(0, equals);
        } else {
          if (++i == parts.size()) return false;
          value = parts.get(i);
        }
        if (("-model".equals(flag) || "--model".equals(flag)) && model == null) model = value;
        else if (("-config".equals(flag) || "--config".equals(flag)) && config == null)
          config = value;
        else return false;
      }
      if (model == null || config == null) return false;
      Path modelPath = resolve(current, model);
      Path configPath = resolve(current, config);
      return (isDefaultModel(modelPath, current)
              || allowPreviousRoot && isDefaultModel(modelPath, source))
          && (configPath.equals(current.resolve("engines/katago/configs/gtp.cfg"))
              || allowPreviousRoot
                  && configPath.equals(source.resolve("engines/katago/configs/gtp.cfg")));
    } catch (RuntimeException invalidPath) {
      return false;
    }
  }

  private static Path resolve(Path appRoot, String token) {
    Path path = Path.of(token);
    return (path.isAbsolute() ? path : appRoot.resolve(path)).toAbsolutePath().normalize();
  }

  private static boolean isDefaultModel(Path model, Path root) {
    return model.equals(root.resolve("weights/default.bin.gz"))
        || model.equals(
            root.resolve(
                "weights/" + KataGoAutoSetupHelper.LEGACY_DEFAULT_WEIGHT_MODEL + ".bin.gz"));
  }
}
