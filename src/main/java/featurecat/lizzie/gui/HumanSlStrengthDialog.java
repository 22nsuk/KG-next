package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.HumanSlStrengthPreparation;
import featurecat.lizzie.analysis.HumanSlStrengthReuseSession;
import featurecat.lizzie.analysis.PlayerStrengthEstimator;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.*;

/** Explicit progress only; selecting a model never opens this dialog. */
public final class HumanSlStrengthDialog extends JDialog {
  private final JLabel status = new JLabel(text("HumanSL40.preparing"));
  private final JProgressBar progress = new JProgressBar();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final CompletableFuture<Void> completion = new CompletableFuture<>();
  private final KataGoAutoSetupHelper.DownloadSession download =
      new KataGoAutoSetupHelper.DownloadSession();
  private volatile HumanSlStrengthPreparation.Task task;
  private PlayerStrengthEstimator.Report result;
  private final Timer timer;

  public static PlayerStrengthEstimator.Report prepare(LizzieFrame owner) {
    try {
      var capture = HumanSlStrengthReuseSession.preparationSnapshot();
      return prepare(
          owner,
          capture,
          HumanSlStrengthPreparation::discoverAssets,
          owner.humanSlStrengthPreparation());
    } catch (RuntimeException error) {
      JOptionPane.showMessageDialog(
          owner, errorText(error), "HumanSL 40", JOptionPane.WARNING_MESSAGE);
      return null;
    }
  }

  static PlayerStrengthEstimator.Report prepare(
      LizzieFrame owner,
      HumanSlStrengthReuseSession.Capture capture,
      Supplier<HumanSlStrengthPreparation.Assets> discoverAssets,
      HumanSlStrengthPreparation preparation) {
    var dialog = new HumanSlStrengthDialog(owner, capture, discoverAssets, preparation);
    dialog.setVisible(true);
    return dialog.result;
  }

  private HumanSlStrengthDialog(
      LizzieFrame owner,
      HumanSlStrengthReuseSession.Capture capture,
      Supplier<HumanSlStrengthPreparation.Assets> discoverAssets,
      HumanSlStrengthPreparation preparation) {
    super(owner, "HumanSL 40", true);
    setName("strength40-progress");
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    JPanel content = new JPanel(new BorderLayout(12, 12));
    content.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
    content.add(status, BorderLayout.NORTH);
    progress.setIndeterminate(true);
    content.add(progress, BorderLayout.CENTER);
    JButton cancel = new JButton(text("HumanSL40.cancel"));
    cancel.setName("strength40-cancel");
    cancel.addActionListener(e -> dispose());
    JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
    actions.add(cancel);
    content.add(actions, BorderLayout.SOUTH);
    setContentPane(content);
    setSize(500, 165);
    setLocationRelativeTo(owner);
    AccessibilitySupport.installEscapeAction(getRootPane(), this, this::dispose);
    timer =
        new Timer(
            150,
            event -> {
              if (task == null) return;
              var state = preparation.status();
              status.setText(
                  MessageFormat.format(text(state.key()), state.completed(), state.total()));
              progress.setIndeterminate(false);
              progress.setMaximum(state.total());
              progress.setValue(state.completed());
            });
    var worker =
        new SwingWorker<Void, String>() {
          @Override
          protected Void doInBackground() throws Exception {
            if (cancelled.get()) throw new CancellationException();
            var assets = discoverAssets.get();
            if (cancelled.get()) throw new CancellationException();
            if (assets.engine() == null || !Files.isRegularFile(assets.engine()))
              throw new IllegalStateException("HumanSL40.needEngine");
            if (assets.human() == null) {
              if (!confirmDownload()) throw new CancellationException();
              KataGoAutoSetupHelper.installOfficialHumanSlModel(
                  (message, bytes, total) ->
                      publish(
                          text("HumanSL40.downloading")
                              + (total > 0 ? " " + bytes * 100 / total + "%" : "")),
                  download);
            }
            if (cancelled.get()) throw new CancellationException();
            task = preparation.manual(capture);
            if (cancelled.get()) task.cancel();
            task.completion().get();
            return null;
          }

          @Override
          protected void process(List<String> values) {
            if (!cancelled.get()) {
              status.setText(values.get(values.size() - 1));
              progress.setIndeterminate(true);
            }
          }

          @Override
          protected void done() {
            try {
              if (cancelled.get()) return;
              get();
              if (!HumanSlStrengthReuseSession.stillCurrent(capture))
                throw new IllegalStateException("HumanSL40.gameChanged");
              try {
                result = preparation.readyReport(HumanSlStrengthReuseSession.capture());
              } catch (IllegalArgumentException missingOrdinaryAnalysis) {
                result = null;
              }
            } catch (Exception error) {
              Throwable cause = error;
              while (cause.getCause() != null) cause = cause.getCause();
              if (!(cause instanceof CancellationException))
                JOptionPane.showMessageDialog(
                    HumanSlStrengthDialog.this,
                    errorText(cause),
                    "HumanSL 40",
                    JOptionPane.ERROR_MESSAGE);
            } finally {
              dispose();
              completion.complete(null);
            }
          }
        };
    // Asset discovery includes directory I/O and SHA verification. Start only after the dialog
    // is visible, and keep it off the EDT so Cancel/close remain available during a slow read.
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowOpened(WindowEvent event) {
            if (!cancelled.get()) {
              timer.start();
              worker.execute();
            }
          }
        });
  }

  /** Completion of the final EDT callback, including a retired acquisition's late result. */
  CompletableFuture<Void> completion() {
    return completion.copy();
  }

  private boolean confirmDownload() throws Exception {
    AtomicBoolean accepted = new AtomicBoolean();
    SwingUtilities.invokeAndWait(
        () -> {
          if (cancelled.get()) return;
          accepted.set(
              JOptionPane.showConfirmDialog(
                      this,
                      text("HumanSL40.downloadPrompt"),
                      "HumanSL 40",
                      JOptionPane.YES_NO_OPTION)
                  == JOptionPane.YES_OPTION);
        });
    return accepted.get() && !cancelled.get();
  }

  @Override
  public void dispose() {
    // Retire the request before a queued SwingWorker.done() callback can publish after close.
    boolean firstClose = cancelled.compareAndSet(false, true);
    // HttpURLConnection.disconnect may wait for an in-flight read. Retire the UI request now,
    // and disconnect off the EDT so closing the progress window remains responsive.
    if (firstClose) download.cancelAsync();
    var active = task;
    if (active != null && !active.completion().isDone()) active.cancel();
    if (timer != null) timer.stop();
    super.dispose();
  }

  private static String text(String key) {
    return Lizzie.resourceBundle.getString(key);
  }

  private static String errorText(Throwable error) {
    String message = error.getMessage();
    return message != null && message.startsWith("HumanSL40.")
        ? text(message)
        : text("HumanSL40.failed")
            + "\n"
            + (message == null ? error.getClass().getSimpleName() : message);
  }
}
