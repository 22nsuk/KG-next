package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

public class MessageThemeTest {
  @Test
  void localizedMessagesFitTheirContentAndEscapeClosesTheModalAtEveryFontSize() throws Exception {
    DesktopProbeProcess.requireDisplay();
    int previousFontSize = Config.frameFontSize;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    JDialog[] owner = new JDialog[1];
    PlacementObservedMessage[] dialog = new PlacementObservedMessage[1];
    Robot robot = new Robot();
    CountDownLatch ownerFocused = new CountDownLatch(1);
    try {
      SwingUtilities.invokeAndWait(() -> {
        Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.KOREAN);
        owner[0] = new JDialog();
        owner[0].addWindowFocusListener(new WindowAdapter() {
          @Override public void windowGainedFocus(WindowEvent event) { ownerFocused.countDown(); }
        });
        owner[0].setSize(320, 180);
        owner[0].setLocation(200, 160);
        owner[0].setVisible(true);
      });
      assertTrue(ownerFocused.await(5, TimeUnit.SECONDS), "owner must receive keyboard focus");
      robot.waitForIdle();
      String korean = Lizzie.resourceBundle.getString("SetKataPDA.wrongParameter");
      List<String> messages = List.of(
          "Invalid number: NaN and Infinity are not supported.",
          korean,
          "请输入有效数字。动态比率不能为零。",
          "<html>Invalid number<br>숫자를 확인하세요.<br>Dynamic ratio must not be zero.</html>",
          "");
      for (int size : new int[] {12, 16, 20}) {
        for (String text : messages) {
          CountDownLatch focused = new CountDownLatch(1);
          SwingUtilities.invokeAndWait(() -> {
            Config.frameFontSize = size;
            dialog[0] = new PlacementObservedMessage();
            dialog[0].addWindowFocusListener(new WindowAdapter() {
              @Override public void windowGainedFocus(WindowEvent event) { focused.countDown(); }
            });
          });
          FutureTask<Void> show = new FutureTask<>(() -> {
            dialog[0].setMessage(text, owner[0]);
            return null;
          });
          SwingUtilities.invokeLater(show);
          assertTrue(focused.await(5, TimeUnit.SECONDS), "message must receive keyboard focus");
          // X11 can deliver focus before its final decoration/configure events.
          robot.waitForIdle();
          SwingUtilities.invokeAndWait(() -> {
            PlacementObservedMessage message = dialog[0];
            Rectangle messageBounds = message.getBounds();
            Rectangle requestedBounds = message.requestedBounds;
            Rectangle ownerBounds = owner[0].getBounds();
            Dimension labelSize = message.lblmessage.getSize();
            Dimension labelPreferredSize = message.lblmessage.getPreferredSize();
            assertTrue(message.isModal());
            assertTrue(message.isShowing());
            assertEquals(text, message.lblmessage.getText());
            assertSame(owner[0], message.requestedAnchor,
                "message must request placement relative to the supplied owner");
            assertNotNull(requestedBounds, "message must request centered placement");
            // Native decoration adjustments may change mapped outer size and position. Verify
            // the application's requests here, and real mapped content/keyboard behavior below.
            assertTrue(requestedBounds.width >= 180 && requestedBounds.height >= 80,
                () -> size + " requested message size is smaller than 180x80: " + requestedBounds
                    + ", mapped=" + messageBounds + ", text=" + text);
            assertTrue(labelSize.width >= labelPreferredSize.width,
                size + " clipped message width: " + text + "; actual=" + labelSize
                    + ", preferred=" + labelPreferredSize + ", insets=" + message.getInsets());
            assertTrue(labelSize.height >= labelPreferredSize.height,
                size + " clipped message height: " + text + "; actual=" + labelSize
                    + ", preferred=" + labelPreferredSize + ", insets=" + message.getInsets());
            assertTrue(Math.abs((requestedBounds.x + requestedBounds.width / 2)
                - (ownerBounds.x + ownerBounds.width / 2)) <= 1,
                () -> "message must request centering on the supplied owner: requested="
                    + requestedBounds + ", owner=" + ownerBounds + ", font=" + size + ", text=" + text);
            assertFalse(show.isDone(), "modal show must wait for dismissal");
          });
          if (text.equals(korean)) {
            robot.waitForIdle();
            Path screenshots = Path.of(System.getProperty(
                "lizzie.desktop.evidence.dir", "target/desktop-smoke/probes"));
            Files.createDirectories(screenshots);
            ImageIO.write(robot.createScreenCapture(dialog[0].getBounds()), "png",
                screenshots.resolve("number-error-ko-font-" + size + "-scale-"
                    + dialog[0].getGraphicsConfiguration().getDefaultTransform().getScaleX()
                    + ".png").toFile());
          }
          robot.keyPress(KeyEvent.VK_ESCAPE);
          robot.keyRelease(KeyEvent.VK_ESCAPE);
          show.get(5, TimeUnit.SECONDS);
          SwingUtilities.invokeAndWait(() -> assertFalse(dialog[0].isDisplayable()));
          dialog[0] = null;
        }
      }
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        if (dialog[0] != null) dialog[0].dispose();
        if (owner[0] != null) owner[0].dispose();
        Config.frameFontSize = previousFontSize;
        Lizzie.resourceBundle = previousBundle;
      });
    }
  }

  private static final class PlacementObservedMessage extends Message {
    Component requestedAnchor;
    Rectangle requestedBounds;

    @Override
    public void setLocationRelativeTo(Component anchor) {
      super.setLocationRelativeTo(anchor);
      requestedAnchor = anchor;
      requestedBounds = getBounds();
    }
  }

  @Test
  void messageDialogInstallsTheSharedEscapeCloseAction() throws Exception {
    String source =
        Files.readString(
            Path.of("src/main/java/featurecat/lizzie/gui/Message.java"), StandardCharsets.UTF_8);

    assertTrue(
        source.contains(
            "AccessibilitySupport.installEscapeAction(getRootPane(), this, this::closeAndDispose)"),
        "blocking message dialogs must remain dismissible from the keyboard.");
  }

  @Test
  void darkLookAndFeelBackgroundFallsBackToReadableMessageColors() {
    Color previousPanel = UIManager.getColor("Panel.background");
    Color previousLabel = UIManager.getColor("Label.foreground");
    try {
      UIManager.put("Panel.background", Color.BLACK);
      UIManager.put("Label.foreground", Color.BLACK);
      JLabel label = new JLabel();

      MessageTheme.apply(label);

      assertFalse(Color.BLACK.equals(label.getBackground()));
    } finally {
      UIManager.put("Panel.background", previousPanel);
      UIManager.put("Label.foreground", previousLabel);
    }
  }
}
