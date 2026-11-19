package featurecat.lizzie.analysis;

import featurecat.lizzie.gui.EngineFailedMessage;
import featurecat.lizzie.logging.LogCategories;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Routes output from one physical connection to its startup diagnostic and local failure window. */
final class EngineReaderOutput {
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.ENGINE);
  final EngineStartupDiagnostics.Attempt attempt;
  private final LocalTail stdout;
  private final LocalTail stderr;

  EngineReaderOutput(EngineStartupDiagnostics.Attempt attempt, boolean ssh) {
    this.attempt = attempt;
    boolean privateOutput = ssh || (attempt != null && attempt.isSsh());
    stdout = privateOutput ? new LocalTail() : null;
    stderr = privateOutput ? new LocalTail() : null;
  }

  boolean isSsh() {
    return stderr != null;
  }

  void line(boolean errorStream, String line) {
    if (isSsh()) (errorStream ? stderr : stdout).add(line);
    if (attempt != null) attempt.output(errorStream ? "stderr" : "stdout", isSsh() ? "observed" : line);
  }

  void ended(boolean errorStream, String error) {
    if (attempt != null) attempt.streamEnded(errorStream ? "stderr" : "stdout", error);
    if (isSsh()) (errorStream ? stderr : stdout).ended.complete(null);
  }

  static void reportReaderException(boolean ssh, Exception failure) {
    // Parse and transport exceptions may include the text supplied by the remote process.
    if (ssh) LOG.warn("SSH engine reader failure: {}", failure.getClass().getSimpleName());
    else failure.printStackTrace();
  }

  void showLocalOutput(EngineFailedMessage dialog) {
    if (!isSsh()) return;
    // Either reader can finish after the window opens. Only this connection can update it.
    Runnable refresh = () -> {
      if (dialog.isShowing()) dialog.setLocalOutput(stdout.text(), stderr.text());
    };
    if (!stdout.ended.isDone()) stdout.ended.thenRunAsync(refresh, SwingUtilities::invokeLater);
    if (!stderr.ended.isDone()) stderr.ended.thenRunAsync(refresh, SwingUtilities::invokeLater);
    dialog.setLocalOutput(stdout.text(), stderr.text());
  }

  private static final class LocalTail {
    private static final int MAX_LINES = 40;
    private static final int MAX_LINE_BYTES = 2048;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    final CompletableFuture<Void> ended = new CompletableFuture<>();

    synchronized void add(String line) {
      if (line.isBlank()) return;
      if (lines.size() == MAX_LINES) lines.removeFirst();
      lines.addLast(EngineStartupDiagnostic.bounded(line, MAX_LINE_BYTES));
    }

    synchronized String text() {
      return String.join("\n", lines);
    }
  }
}
