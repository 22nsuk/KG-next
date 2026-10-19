package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.HumanSlStrengthPreparation;
import featurecat.lizzie.analysis.HumanSlStrengthReuseSession;
import featurecat.lizzie.analysis.PlayerStrengthEstimator;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

/** Explicit progress only; selecting a model never opens this dialog. */
public final class HumanSlStrengthDialog extends JDialog {
  private final JLabel status = new JLabel(text("HumanSL40.preparing"));
  private final JProgressBar progress = new JProgressBar();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final KataGoAutoSetupHelper.DownloadSession download =
      new KataGoAutoSetupHelper.DownloadSession();
  private volatile HumanSlStrengthPreparation.Task task;
  private PlayerStrengthEstimator.Report result;
  private final Timer timer;

  public static PlayerStrengthEstimator.Report prepare(LizzieFrame owner) {
    try {
      var capture = HumanSlStrengthReuseSession.preparationSnapshot();
      var assets = HumanSlStrengthPreparation.discoverAssets();
      if (assets.engine() == null || !Files.isRegularFile(assets.engine())) {
        JOptionPane.showMessageDialog(owner, text("HumanSL40.needEngine"));
        return null;
      }
      if (assets.human() == null
          && JOptionPane.showConfirmDialog(
                  owner, text("HumanSL40.downloadPrompt"), "HumanSL 40", JOptionPane.YES_NO_OPTION)
              != JOptionPane.YES_OPTION) return null;
      return prepare(owner, capture, assets.human(), owner.humanSlStrengthPreparation());
    } catch (RuntimeException error) {
      JOptionPane.showMessageDialog(
          owner, errorText(error), "HumanSL 40", JOptionPane.WARNING_MESSAGE);
      return null;
    }
  }

  static PlayerStrengthEstimator.Report prepare(
      LizzieFrame owner,
      HumanSlStrengthReuseSession.Capture capture,
      Path installedHuman,
      HumanSlStrengthPreparation preparation) {
    var dialog = new HumanSlStrengthDialog(owner, capture, installedHuman, preparation);
    dialog.setVisible(true);
    return dialog.result;
  }

  private HumanSlStrengthDialog(
      LizzieFrame owner,
      HumanSlStrengthReuseSession.Capture capture,
      Path installedHuman,
      HumanSlStrengthPreparation preparation) {
    super(owner, "HumanSL 40", true);
    setName("strength40-progress");
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    JPanel content = new JPanel(new BorderLayout(12, 12));
    content.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
    content.add(status, BorderLayout.NORTH);
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
    timer.start();
    new SwingWorker<Void, String>() {
      @Override
      protected Void doInBackground() throws Exception {
        if (installedHuman == null)
          KataGoAutoSetupHelper.downloadHumanSlModel(
              (message, bytes, total) ->
                  publish(
                      text("HumanSL40.downloading")
                          + (total > 0 ? " " + bytes * 100 / total + "%" : "")),
              download);
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
        if (cancelled.get()) return;
        try {
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
        }
      }
    }.execute();
  }

  @Override
  public void dispose() {
    // Retire the request before a queued SwingWorker.done() callback can publish after close.
    cancelled.set(true);
    download.cancel();
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
