package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.IOException;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public class Message extends JDialog {
  JLabel lblmessage;
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;

  public Message() {
    initializeDialog();
  }

  public Message(Window owner) {
    super(owner);
    initializeDialog();
  }

  private void initializeDialog() {
    // this.setModal(true);
    // setType(Type.POPUP);
    this.setResizable(false);
    setMinimumSize(new Dimension(180, 80));
    setTitle(resourceBundle.getString("Message.title")); // "消息提醒");
    setAlwaysOnTop(true);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    AccessibilitySupport.installEscapeAction(getRootPane(), this, this::closeAndDispose);
    //  setLocationByPlatform(true);
    lblmessage = new JLabel("", JLabel.CENTER);
    getContentPane().setBackground(MessageTheme.background());
    MessageTheme.apply(lblmessage);
    lblmessage.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
    lblmessage.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    this.add(lblmessage);
    try {
      this.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void setMessage(String message) {
    showMessage(message, Lizzie.frame != null ? Lizzie.frame : null, true);
  }

  public void setMessageNoModal(String message) {
    showMessage(message, Lizzie.frame != null ? Lizzie.frame : null, false);
  }

  public void setMessageNoModal(String message, int seconds) {
    showMessage(message, Lizzie.frame != null ? Lizzie.frame : null, false);
    Timer closeTimer =
        new Timer(
            Math.max(1, seconds) * 1000,
            e -> {
              closeAndDispose();
            });
    closeTimer.setRepeats(false);
    closeTimer.start();
  }

  public void setMessage(String message, Window owner) {
    showMessage(message, owner, true);
  }

  private void showMessage(String message, Window owner, boolean modal) {
    Runnable show =
        () -> {
          setModal(modal);
          lblmessage.setText(message);
          // Keep pack's client sizing when native decoration insets arrive later.
          pack();
          setLocationRelativeTo(owner);
          setVisible(true);
        };
    runOnEdt(show);
  }

  private void closeAndDispose() {
    Runnable close =
        () -> {
          Window owner = getOwner();
          setVisible(false);
          dispose();
          Window repaintTarget = owner != null ? owner : Lizzie.frame;
          if (repaintTarget != null) {
            repaintTarget.invalidate();
            repaintTarget.repaint();
          }
          Toolkit.getDefaultToolkit().sync();
        };
    runOnEdt(close);
  }

  private static void runOnEdt(Runnable runnable) {
    if (SwingUtilities.isEventDispatchThread()) {
      runnable.run();
      return;
    }
    try {
      SwingUtilities.invokeAndWait(runnable);
    } catch (Exception ex) {
      SwingUtilities.invokeLater(runnable);
    }
  }
}
