package featurecat.lizzie.logging;

import featurecat.lizzie.Config;
import featurecat.lizzie.analysis.EngineCommandVisibility;
import featurecat.lizzie.util.KataGoExperimentalBackendInstaller.Backend;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.katago.tuning.KataGoCommandSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured engine-startup facts for {@link EngineObservation}. Values are log tokens only: no
 * full command lines and no absolute paths.
 *
 * <p>Bundled vs user-configured comes from {@link Config#isBundledKataGoCommand}. Backend detection
 * reuses the same NVIDIA path markers, OpenCL marker file, and {@code
 * lizzieyzy-next-engine-backend.txt} conventions as {@code KataGoRuntimeHelper}.
 */
public final class EngineBootstrapFacts {
  public static final String UNKNOWN = "unknown";
  /** Stands in for a bootstrap value that belongs to a user-hidden engine command. */
  public static final String HIDDEN = "hidden-command";
  public static final String SOURCE_BUNDLED = "bundled";
  public static final String SOURCE_USER_CONFIGURED = "user-configured";

  private static final int TOKEN_MAX_LENGTH = 96;
  private static final String ENGINE_BACKEND_MARKER_NAME = "lizzieyzy-next-engine-backend.txt";
  private static final String NVIDIA_ENGINE_DIR = "windows-x64-nvidia";
  private static final String NVIDIA50_CUDA_ENGINE_DIR = "windows-x64-nvidia50-cuda";
  private static final String NVIDIA_TRT_ENGINE_DIR = "windows-x64-nvidia-tensorrt";
  private static final String NVIDIA50_TRT_ENGINE_DIR = "windows-x64-nvidia50-trt";
  private static final String NVIDIA_BACKEND = "nvidia";
  private static final String NVIDIA50_CUDA_BACKEND = "nvidia50-cuda";
  private static final String NVIDIA_TRT_BACKEND = "nvidia-tensorrt";
  private static final String NVIDIA50_TRT_BACKEND = "nvidia50-trt";
  private static final String OPENCL_BACKEND = "opencl";
  private static final String ONNX_BACKEND = "onnx";
  /** Only explicitly listed package and installer backends may identify a hidden command. */
  private static final Set<String> APP_BACKENDS =
      Set.of(
          NVIDIA_BACKEND,
          NVIDIA50_CUDA_BACKEND,
          NVIDIA_TRT_BACKEND,
          NVIDIA50_TRT_BACKEND,
          OPENCL_BACKEND,
          ONNX_BACKEND,
          "cpu",
          Backend.ROCM_GFX103X.marker(),
          Backend.ROCM_GFX110X.marker(),
          Backend.ROCM_GFX1151.marker(),
          Backend.ROCM_GFX120X.marker());
  /** ONNX execution providers KG-next configures ({@code KataGoAutoSetupHelper}, installer). */
  private static final Set<String> APP_ONNX_PROVIDERS =
      Set.of("directml", "openvino", "openvino-npu");
  private static final Set<String> APP_ENGINE_TYPES = Set.of("katago", "leela");
  private static final Set<String> APP_SOURCES = Set.of(SOURCE_BUNDLED, SOURCE_USER_CONFIGURED);
  private static final String HUMAN_SL_CUDA_COMPANION_NAME = "katago-human-sl-cuda.exe";
  private static final Pattern ONNX_PROVIDER =
      Pattern.compile("(?i)(?:^|[,\\s])onnxProvider=([^,\\s\"]+)");
  private static final Pattern SAFE_TOKEN = Pattern.compile("[^A-Za-z0-9._+-]+");

  private final String engineType;
  private final String version;
  private final String purpose;
  private final String source;
  private final String backend;
  private final String onnxProvider;
  private final String model;
  private final String config;

  private EngineBootstrapFacts(
      String engineType,
      String version,
      String purpose,
      String source,
      String backend,
      String onnxProvider,
      String model,
      String config) {
    this.engineType = engineType;
    this.version = version;
    this.purpose = purpose;
    this.source = source;
    this.backend = backend;
    this.onnxProvider = onnxProvider;
    this.model = model;
    this.config = config;
  }

  public static EngineBootstrapFacts unknown(String purpose) {
    return new EngineBootstrapFacts(
        UNKNOWN,
        UNKNOWN,
        safePurpose(purpose),
        SOURCE_USER_CONFIGURED,
        UNKNOWN,
        UNKNOWN,
        UNKNOWN,
        UNKNOWN);
  }

  public static EngineBootstrapFacts of(
      String engineType,
      String version,
      String purpose,
      String source,
      String backend,
      String onnxProvider,
      String model,
      String config) {
    return new EngineBootstrapFacts(
        safeToken(engineType),
        safeToken(version),
        safePurpose(purpose),
        safeToken(source),
        safeToken(backend),
        safeToken(onnxProvider),
        safeToken(model),
        safeToken(config));
  }

  /**
   * The facts a user-hidden engine command may share. Its model and config names are part of what
   * the user hid. The other values come from the hidden command or its installation too (an {@code
   * onnxProvider=} override, a backend marker kept as written), so each is shared only when it is
   * one of KG-next's own names, written in KG-next's spelling; anything else becomes {@link
   * #HIDDEN}. Pass parsed values, not display tokens: formatting can discard characters. The
   * purpose is the application's own.
   */
  public static EngineBootstrapFacts forHiddenCommand(
      String engineType,
      String version,
      String purpose,
      String source,
      String backend,
      String onnxProvider) {
    return new EngineBootstrapFacts(
        appName(engineType, APP_ENGINE_TYPES),
        // KG-next never names a version of its own here.
        appName(version, Set.of()),
        safePurpose(purpose),
        appName(source, APP_SOURCES),
        appName(backend, APP_BACKENDS),
        appName(onnxProvider, APP_ONNX_PROVIDERS),
        HIDDEN,
        HIDDEN);
  }

  /** {@code value} in KG-next's spelling when it is one of {@code names}; else {@link #HIDDEN}. */
  private static String appName(String value, Set<String> names) {
    if (value == null || UNKNOWN.equals(value)) {
      return UNKNOWN;
    }
    String name = value.toLowerCase(Locale.ROOT);
    return names.contains(name) ? name : HIDDEN;
  }

  public static EngineBootstrapFacts fromCommand(String command, String purpose) {
    return fromCommand(command, purpose, EngineCommandVisibility.VISIBLE);
  }

  public static EngineBootstrapFacts fromCommand(
      String command, String purpose, EngineCommandVisibility visibility) {
    try {
      return parseCommand(command, purpose, visibility);
    } catch (RuntimeException | Error failure) {
      if (failure instanceof VirtualMachineError fatal) {
        throw fatal;
      }
      return visibility.isHidden()
          ? forHiddenCommand(UNKNOWN, UNKNOWN, purpose, SOURCE_USER_CONFIGURED, UNKNOWN, UNKNOWN)
          : unknown(purpose);
    }
  }

  public String engineType() {
    return engineType;
  }

  public String version() {
    return version;
  }

  public String purpose() {
    return purpose;
  }

  public String source() {
    return source;
  }

  public String backend() {
    return backend;
  }

  public String onnxProvider() {
    return onnxProvider;
  }

  public String model() {
    return model;
  }

  public String config() {
    return config;
  }

  String formatLogLine(String stageFragment) {
    StringBuilder line = new StringBuilder("engine event=bootstrap");
    appendField(line, "engineType", engineType);
    appendField(line, "version", version);
    appendField(line, "purpose", purpose);
    appendField(line, "source", source);
    appendField(line, "backend", backend);
    appendField(line, "onnxProvider", onnxProvider);
    appendField(line, "model", model);
    appendField(line, "config", config);
    if (stageFragment != null && !stageFragment.isEmpty()) {
      line.append(stageFragment);
    }
    return line.toString();
  }

  private static EngineBootstrapFacts parseCommand(
      String command, String purpose, EngineCommandVisibility visibility) {
    List<String> parts = Utils.splitCommand(command == null ? "" : command);
    boolean bundled = Config.isBundledKataGoCommand(command);
    String source = bundled ? SOURCE_BUNDLED : SOURCE_USER_CONFIGURED;
    Path executable = executablePath(parts);
    String onnxProvider = commandOnnxProvider(parts, command, visibility);
    String backend = resolveNvidiaBackendFromPath(executable);
    if (onnxProvider == null || backend == null) {
      String marker = classificationInput(readBackendMarker(executable), visibility);
      if (onnxProvider == null) {
        onnxProvider = isOnnxExecutionProvider(marker) ? marker : UNKNOWN;
      }
      if (backend == null) {
        backend = resolveBackendFromMarker(marker, onnxProvider);
      }
    }
    String engineType = inferEngineType(parts, bundled);
    if (visibility.isHidden()) {
      return forHiddenCommand(engineType, UNKNOWN, purpose, source, backend, onnxProvider);
    }
    return new EngineBootstrapFacts(
        engineType,
        UNKNOWN,
        safePurpose(purpose),
        source,
        backend,
        onnxProvider,
        fileIdentity(optionValue(parts, "-model", "--model", "-weights", "--weights")),
        fileIdentity(optionValue(parts, "-config", "--config")));
  }

  private static Path executablePath(List<String> parts) {
    if (parts == null || parts.isEmpty() || parts.get(0) == null || parts.get(0).trim().isEmpty()) {
      return null;
    }
    try {
      return Path.of(parts.get(0).trim());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String inferEngineType(List<String> parts, boolean bundled) {
    if (bundled) {
      return "katago";
    }
    if (parts == null || parts.isEmpty()) {
      return UNKNOWN;
    }
    String executable = fileIdentity(parts.get(0)).toLowerCase(Locale.ROOT);
    if (executable.contains("katago")) {
      return "katago";
    }
    if (executable.contains("leelaz") || executable.contains("leela")) {
      return "leela";
    }
    return UNKNOWN;
  }

  private static String resolveBackendFromMarker(String marker, String onnxProvider) {
    if (NVIDIA50_TRT_BACKEND.equals(marker)) {
      return NVIDIA_TRT_BACKEND;
    }
    if (isOnnxExecutionProvider(marker)) {
      return ONNX_BACKEND;
    }
    if (!UNKNOWN.equals(marker)) {
      return marker;
    }
    if (isOnnxExecutionProvider(onnxProvider)) {
      return ONNX_BACKEND;
    }
    return UNKNOWN;
  }

  /**
   * Mirrors {@code KataGoRuntimeHelper.resolveNvidiaBackend}'s path rules without loading that
   * class from logging tests.
   */
  private static String resolveNvidiaBackendFromPath(Path enginePath) {
    if (enginePath == null) {
      return null;
    }
    Path fileName = enginePath.getFileName();
    if (fileName != null && HUMAN_SL_CUDA_COMPANION_NAME.equalsIgnoreCase(fileName.toString())) {
      return NVIDIA50_CUDA_BACKEND;
    }
    String normalized = enginePath.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    if (normalized.contains("/" + NVIDIA_TRT_ENGINE_DIR + "/")
        || normalized.contains("/" + NVIDIA50_TRT_ENGINE_DIR + "/")) {
      return NVIDIA_TRT_BACKEND;
    }
    if (normalized.contains("/" + NVIDIA50_CUDA_ENGINE_DIR + "/")) {
      return NVIDIA50_CUDA_BACKEND;
    }
    if (normalized.contains("/" + NVIDIA_ENGINE_DIR + "/")) {
      return NVIDIA_BACKEND;
    }
    return null;
  }

  /** A provider specified by the command, or null if the installation marker should be used. */
  private static String commandOnnxProvider(
      List<String> parts, String command, EngineCommandVisibility visibility) {
    String fromOverride = classificationInput(overrideOnnxProvider(parts), visibility);
    if (!UNKNOWN.equals(fromOverride)) {
      return fromOverride;
    }
    Matcher matcher = ONNX_PROVIDER.matcher(command == null ? "" : command);
    if (matcher.find()) {
      return classificationInput(matcher.group(1), visibility);
    }
    return null;
  }

  private static String overrideOnnxProvider(List<String> parts) {
    if (parts == null || parts.isEmpty()) {
      return UNKNOWN;
    }
    try {
      Map<String, String> overrides = KataGoCommandSpec.parse(parts).effectiveOverrides();
      for (Map.Entry<String, String> entry : overrides.entrySet()) {
        if (entry.getKey() != null && "onnxprovider".equalsIgnoreCase(entry.getKey().trim())) {
          return entry.getValue();
        }
      }
    } catch (RuntimeException ignored) {
    }
    return UNKNOWN;
  }

  private static String readBackendMarker(Path executable) {
    if (executable == null) {
      return UNKNOWN;
    }
    try {
      Path engineDir = executable.toAbsolutePath().normalize().getParent();
      if (engineDir == null) {
        return UNKNOWN;
      }
      Path markerPath = engineDir.resolve(ENGINE_BACKEND_MARKER_NAME);
      if (!Files.isRegularFile(markerPath)) {
        return UNKNOWN;
      }
      String marker =
          Files.readString(markerPath, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
      return marker.isEmpty() ? UNKNOWN : marker;
    } catch (Exception ignored) {
      return UNKNOWN;
    }
  }

  private static String classificationInput(String raw, EngineCommandVisibility visibility) {
    if (raw == null || raw.isEmpty()) {
      return UNKNOWN;
    }
    // Display formatting can turn a custom value such as "_openvino_" into an application name.
    return visibility.isHidden() ? raw : safeToken(raw);
  }

  private static boolean isOnnxExecutionProvider(String value) {
    if (value == null || UNKNOWN.equals(value)) {
      return false;
    }
    return APP_ONNX_PROVIDERS.contains(value.toLowerCase(Locale.ROOT));
  }

  private static String optionValue(List<String> parts, String... flags) {
    if (parts == null || flags == null) {
      return null;
    }
    for (int i = 0; i < parts.size(); i++) {
      String token = parts.get(i);
      if (token == null) {
        continue;
      }
      for (String flag : flags) {
        if (flag.equals(token)) {
          return i + 1 < parts.size() ? parts.get(i + 1) : null;
        }
        String prefix = flag + "=";
        if (token.startsWith(prefix)) {
          return token.substring(prefix.length());
        }
      }
    }
    return null;
  }

  static String fileIdentity(String raw) {
    if (raw == null) {
      return UNKNOWN;
    }
    String trimmed = stripQuotes(raw.trim());
    if (trimmed.isEmpty()) {
      return UNKNOWN;
    }
    String normalized = trimmed.replace('\\', '/');
    int separator = normalized.lastIndexOf('/');
    String fileName = separator >= 0 ? normalized.substring(separator + 1) : normalized;
    return safeToken(fileName);
  }

  static String safeToken(String raw) {
    if (raw == null) {
      return UNKNOWN;
    }
    String trimmed = stripQuotes(raw.trim());
    if (trimmed.isEmpty()) {
      return UNKNOWN;
    }
    String collapsed = SAFE_TOKEN.matcher(trimmed).replaceAll("_");
    while (collapsed.startsWith("_")) {
      collapsed = collapsed.substring(1);
    }
    while (collapsed.endsWith("_")) {
      collapsed = collapsed.substring(0, collapsed.length() - 1);
    }
    if (collapsed.isEmpty() || ".".equals(collapsed) || "..".equals(collapsed)) {
      return UNKNOWN;
    }
    if (collapsed.length() > TOKEN_MAX_LENGTH) {
      collapsed = collapsed.substring(0, TOKEN_MAX_LENGTH);
    }
    return collapsed;
  }

  private static String safePurpose(String purpose) {
    String token = safeToken(purpose);
    return UNKNOWN.equals(token) ? UNKNOWN : token;
  }

  private static String stripQuotes(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if (first == last && (first == '"' || first == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  private static void appendField(StringBuilder line, String name, String value) {
    line.append(' ').append(name).append('=').append(value == null ? UNKNOWN : value);
  }
}
