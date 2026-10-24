package featurecat.lizzie.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict engine metadata; historical linkage recognition does not authorize a current release. */
final class KataGoEngineManifest {
  private KataGoEngineManifest() {}

  static Map<String, String> readStrict(Path manifestPath) throws IOException {
    Map<String, String> values = new LinkedHashMap<>();
    String[] lines =
        Files.readString(manifestPath, StandardCharsets.UTF_8).replace("\r", "").split("\n", -1);
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index];
      if (line.isEmpty() && index == lines.length - 1) {
        continue;
      }
      int separator = line.indexOf(": ");
      if (separator <= 0 || separator == line.length() - 2) {
        return null;
      }
      String key = line.substring(0, separator);
      String value = line.substring(separator + 2);
      if (!isAllowedKey(key) || values.putIfAbsent(key, value) != null) {
        return null;
      }
    }
    boolean hasCompanion = values.containsKey("HumanSL companion");
    if (hasCompanion != values.containsKey("HumanSL companion SHA-256")) {
      return null;
    }
    return values;
  }

  private static boolean isAllowedKey(String key) {
    return switch (key) {
      case "Manifest schema",
          "KataGo release",
          "Asset ID",
          "Asset",
          "Asset SHA-256",
          "Executable SHA-256",
          "Backend",
          "Origin",
          "Source commit",
          "Source repository",
          "Engine release repository",
          "Engine release tag",
          "Zlib linkage",
          "HumanSL companion",
          "HumanSL companion SHA-256" ->
          true;
      default -> false;
    };
  }

  static boolean describesProjectStaticZlibBuild(
      Map<String, String> manifest, KataGoAssetCatalog.Asset asset, String executableSha256) {
    if (manifest == null || asset == null) return false;
    String recorded = manifest.getOrDefault("Executable SHA-256", "");
    return "2".equals(manifest.get("Manifest schema"))
        && "project-source-build".equals(manifest.get("Origin"))
        && "static".equals(manifest.get("Zlib linkage"))
        && asset.id().equals(manifest.get("Asset ID"))
        && asset.backend().equals(manifest.get("Backend"))
        && recorded.matches("[0-9a-fA-F]{64}")
        && recorded.equalsIgnoreCase(executableSha256)
        && hasCoherentHistoricalSource(manifest);
  }

  private static boolean hasCoherentHistoricalSource(Map<String, String> manifest) {
    String commit = manifest.getOrDefault("Source commit", "");
    if (!commit.matches("[0-9a-f]{40}")) return false;
    if (!manifest.containsKey("Source repository")
        && !manifest.containsKey("Engine release repository")
        && !manifest.containsKey("Engine release tag")) {
      // Legacy project CUDA 12/TensorRT packages predate the separate fork source identity.
      return "windows-nvidia".equals(manifest.get("Asset ID"))
          || "windows-tensorrt".equals(manifest.get("Asset ID"));
    }
    return ("windows-nvidia".equals(manifest.get("Asset ID"))
            || "windows-nvidia-cuda13".equals(manifest.get("Asset ID")))
        && "https://github.com/22nsuk/KataGo".equals(manifest.get("Source repository"))
        && "22nsuk/KataGo".equals(manifest.get("Engine release repository"))
        && ("kg-next-" + commit.substring(0, 12)).equals(manifest.get("Engine release tag"));
  }
}
