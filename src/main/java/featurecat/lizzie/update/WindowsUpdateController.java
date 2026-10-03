package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class WindowsUpdateController {
  private static final UpdateCheckCoordinator COORDINATOR = new UpdateCheckCoordinator();
  private static final UpdateCheckCoordinator.OfferHandoff HANDOFF = new ProductionHandoff();
  private static final UpdateCheckCoordinator.Runner RUNNER = new SwingRunner();

  private WindowsUpdateController() {}

  public static void openCheckUpdatePage(Component parent) {
    SwingUtilities.invokeLater(() -> {
      int choice = JOptionPane.showConfirmDialog(parent,
          UpdateText.tr("KGNext.update.manual", "KG-next 使用独立发布。打开发布页？",
              "KG-next uses separate releases. Open the release page?"),
          UpdateText.tr("KGNext.update.title", "KG-next 更新", "KG-next update"),
          JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
      if (choice != JOptionPane.OK_OPTION) return;
      try {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
          throw new IOException("Desktop browser is unavailable");
        }
        Desktop.getDesktop().browse(forkReleasesUri());
      } catch (IOException | SecurityException ex) {
        JOptionPane.showMessageDialog(parent,
            UpdateText.tr("KGNext.update.failed", "请手动打开：", "Open this address manually:")
                + "\n" + forkReleasesUri(), "KG-next", JOptionPane.WARNING_MESSAGE);
      }
    });
  }

  static URI forkReleasesUri() {
    return URI.create("https://github.com/22nsuk/KG-next/releases");
  }

  static void checkForUpdate(UpdateCheckCoordinator.Page page, UpdateCheckSelection snapshot) {
    COORDINATOR.start(page, snapshot, UpdateDiscovery::check, HANDOFF, RUNNER);
  }

  private static final class ProductionHandoff implements UpdateCheckCoordinator.OfferHandoff {
    @Override
    public void openWindows(UpdateCheckSelection selection, UpdateCheckResult result) {
      WindowsUpdateService service = new WindowsUpdateService();
      new WindowsUpdateDialog(
              Lizzie.frame, service, result.windowsPlan, UpdateCheckFeedback.warning(result))
          .setVisible(true);
    }

    @Override
    public void openPackage(UpdateCheckSelection selection, UpdateCheckResult result) {
      PlatformUpdateService service = new PlatformUpdateService();
      new PackageUpdateDialog(
              Lizzie.frame, service, result.packagePlan, UpdateCheckFeedback.warning(result))
          .setVisible(true);
    }
  }

  private static final class SwingRunner implements UpdateCheckCoordinator.Runner {
    @Override
    public void runBackground(Runnable work) {
      Thread thread = new Thread(work, "lizzie-update-manual");
      thread.setDaemon(true);
      thread.start();
    }

    @Override
    public void runOnEdt(Runnable work) {
      SwingUtilities.invokeLater(work);
    }
  }
}
