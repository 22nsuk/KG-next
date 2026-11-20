package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.ObservationText;
import java.util.regex.Pattern;

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
 * library names from the runtime vocabulary. Text the application wrote itself stays as it is.
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
   * Runtime libraries KG-next checks for or that GPU and C runtimes are made of. A library name in
   * engine output outside this vocabulary may be the engine's own and is shared only when visible.
   */
  private static final Pattern RUNTIME_LIBRARY =
      Pattern.compile(
          "(?i)(?:"
              + "cudart(?:32|64)?(?:_\\d+)*"
              + "|cublas(?:lt)?(?:32|64)?(?:_\\d+)*"
              + "|cudnn(?:_(?:ops|cnn|adv|graph|heuristic|engines_precompiled"
              + "|engines_runtime_compiled)(?:_(?:infer|train))?)?(?:32|64)?(?:_\\d+)*"
              + "|(?:cufft|curand|cusolver|cusparse|nvjitlink|nvrtc(?:-builtins)?)(?:64)?(?:_\\d+)*"
              + "|nvinfer(?:_plugin|_lean|_dispatch|_builder_resource(?:_win)?)?(?:_\\d+)*"
              + "|nvonnxparser(?:_\\d+)*"
              + "|nvcuda|nvml|opencl|zlibwapi|zlib1|libz|z"
              + "|vcruntime\\d+(?:_\\d+)?|msvcp\\d+(?:_(?:\\d+|atomic_wait|codecvt_ids))?"
              + "|concrt\\d+|vcomp\\d+|vccorlib\\d+|ucrtbase|api-ms-win-[a-z0-9-]+"
              + "|onnxruntime(?:_providers_(?:shared|cuda|tensorrt|dml|openvino))?"
              + "|directml|d3d12|dxgi|dxcore"
              + "|libopenblas|openblas|libgcc_s_seh-1|libstdc\\+\\+-6|libwinpthread-1|libgomp-1"
              + ")\\.dll");

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

  /** A library name parsed from engine output. */
  public String libraryName(String name) {
    if (!isHidden() || name == null) return name;
    return RUNTIME_LIBRARY.matcher(name).matches() ? name : HIDDEN_VALUE;
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
