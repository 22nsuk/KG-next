package featurecat.lizzie.update;

import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class WindowsUpdateController {
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
}
