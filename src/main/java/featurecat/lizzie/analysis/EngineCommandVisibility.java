package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.ObservationText;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Whether an engine's launch command may be shown to the user.
 *
 * <p>The engine editor saves a command the user chose to hide as {@code encryption||<blob>}. The
 * owner that decodes the blob records {@link #HIDDEN} at that point and hands it to diagnostics
 * and the failure dialog, which never try to recognise a hidden command from its text.
 *
 * <p>A hidden command's text cannot be removed from what other programs print about it: a shell
 * reports a word it split off an argument, a short executable name is indistinguishable from
 * ordinary words, and bounded text can end inside a value. So text that came from the command or
 * the engine is never shared for a hidden command. What is shared is what the owner can state from
 * structure: the exception type, the operating system's reason for a failed process start, and
 * the names of runtime libraries KG-next itself requires, in its own spelling. Text the
 * application wrote itself stays as it is.
 */
public enum EngineCommandVisibility {
  VISIBLE,
  HIDDEN;

  /** Stands in for every value that came from a hidden command or its engine's output. */
  public static final String HIDDEN_VALUE = "<hidden-command>";

  /** The JDK's ProcessBuilder.start wrapper; its program and directory are the hidden command. */
  private static final String PROCESS_START_PREFIX = "Cannot run program \"";

  private static final String PROCESS_START_DIRECTORY = "\" (in directory \"";

  /**
   * The JDK's native process-start reason: an OS error number and the system's text for it, as
   * {@code CreateProcess error=2, <text>} (Windows), {@code error=2, <text>} (older POSIX JDKs) or
   * {@code Exec failed, error: 2 (<text>)} (current POSIX JDKs, naming the failed launch step).
   */
  private static final Pattern SYSTEM_ERROR =
      Pattern.compile(
          "(?:CreateProcess )?error=-?\\d+(?:, [^\\r\\n]*)?"
              + "|[A-Za-z][A-Za-z ]{0,40} failed, error: -?\\d+(?: \\([^\\r\\n]*\\))?");

  private static final int MAX_SYSTEM_ERROR_BYTES = 512;

  /**
   * The runtime libraries KG-next's own runtime profiles require by exact name
   * ({@code KataGoRuntimeHelper}), keyed case-insensitively and valued in the application's
   * spelling. Profile entries written as patterns ({@code nvJitLink*.dll}) are not here: a pattern
   * admits names nobody confirmed, and so does any family rule such as {@code api-ms-win-*}.
   */
  private static final Map<String, String> RUNTIME_LIBRARIES =
      Stream.of(
              "cudart64_12.dll",
              "cudart64_13.dll",
              "cublas64_12.dll",
              "cublas64_13.dll",
              "cublasLt64_12.dll",
              "cublasLt64_13.dll",
              "cudnn64_8.dll",
              "cudnn64_9.dll",
              "cudnn_adv64_9.dll",
              "cudnn_cnn64_9.dll",
              "cudnn_engines_precompiled64_9.dll",
              "cudnn_engines_runtime_compiled64_9.dll",
              "cudnn_engines_tensor_ir64_9.dll",
              "cudnn_ext64_9.dll",
              "cudnn_graph64_9.dll",
              "cudnn_heuristic64_9.dll",
              "cudnn_ops64_9.dll",
              "nvJitLink_130_0.dll",
              "nvrtc64_120_0.dll",
              "nvrtc64_130_0.dll",
              "nvrtc-builtins64_128.dll",
              "nvrtc-builtins64_132.dll",
              "nvinfer_10.dll",
              "nvinfer_plugin_10.dll",
              "nvonnxparser_10.dll",
              "zlibwapi.dll",
              "libz.dll",
              "z.dll")
          .collect(
              Collectors.toUnmodifiableMap(name -> name.toLowerCase(Locale.ROOT), name -> name));

  public boolean isHidden() {
    return this == HIDDEN;
  }

  /**
   * {@link Throwable#toString()} of an error raised while starting or reaching the engine. For a
   * hidden command only the exception type and the structured process-start reason remain.
   */
  public String errorText(Throwable error) {
    if (!isHidden() || error == null) return String.valueOf(error);
    return error.getClass().getName() + ": " + hiddenErrorMessage(error);
  }

  /** {@link Throwable#getLocalizedMessage()} of such an error, for failure text and the log. */
  public String errorMessage(Throwable error) {
    if (error == null) return null;
    return isHidden() ? hiddenErrorMessage(error) : error.getLocalizedMessage();
  }

  /**
   * Engine stdout or stderr, or a snapshot of recent engine lines. A hidden command's engine can
   * echo any part of it in any form, so its output is shared only as the fact that there was some.
   */
  public String engineOutput(String text) {
    if (!isHidden() || text == null || text.isEmpty()) return text;
    return HIDDEN_VALUE;
  }

  /**
   * A library name parsed from engine output. For a hidden command the result is one of KG-next's
   * required runtime library names, written by the application, or the marker; never the engine's
   * text.
   */
  public String libraryName(String name) {
    if (!isHidden() || name == null) return name;
    return RUNTIME_LIBRARIES.getOrDefault(name.toLowerCase(Locale.ROOT), HIDDEN_VALUE);
  }

  /**
   * The JDK writes {@code Cannot run program "<argv0>" (in directory "<cwd>"): <reason>} around the
   * native failure it keeps as the cause. Both quoted values are the hidden command whatever their
   * length or spelling, so they are never parsed out; the reason is the cause's own message.
   */
  private static String hiddenErrorMessage(Throwable error) {
    String message = error.getMessage();
    if (message == null || !message.startsWith(PROCESS_START_PREFIX)) return HIDDEN_VALUE;
    StringBuilder text = new StringBuilder("Cannot run program ").append(HIDDEN_VALUE);
    if (message.contains(PROCESS_START_DIRECTORY))
      text.append(" (in directory ").append(HIDDEN_VALUE).append(')');
    return text.append(": ").append(systemReason(message, error.getCause())).toString();
  }

  /** The JDK appends {@code ": " + cause.getMessage()}; any other shape is not its reason. */
  private static String systemReason(String message, Throwable cause) {
    String reason = cause == null ? null : cause.getMessage();
    if (reason == null || !message.endsWith(": " + reason)) return HIDDEN_VALUE;
    String line = reason.strip().lines().findFirst().orElse("").strip();
    if (!SYSTEM_ERROR.matcher(line).matches()) return HIDDEN_VALUE;
    return ObservationText.boundedUtf8(line, MAX_SYSTEM_ERROR_BYTES, 1);
  }
}
