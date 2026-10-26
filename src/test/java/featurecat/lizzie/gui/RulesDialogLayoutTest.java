package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.util.LocaleFontSupport;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesDialogLayoutTest {
  @TempDir Path directory;

  @Test
  void bothEditorsFitTranslationsAndFontSizes() throws Exception {
    boolean headless = GraphicsEnvironment.isHeadless();
    assertFalse(Boolean.getBoolean("lizzie.desktop.required") && headless);
    assumeFalse(headless);
    UiState[] saved = new UiState[1];
    onEdt(() -> saved[0] = new UiState(Lizzie.config, Lizzie.resourceBundle, Lizzie.leelaz,
        Config.frameFontSize, Config.sysDefaultFontName));
    UiState previous = saved[0];
    try {
      onEdt(() -> {
        Lizzie.config = ConfigTestHelper.createForTests(directory);
        Lizzie.config.analysisSpecificRules = "";
      });
      Leelaz pondering = new Leelaz("") {
        @Override public boolean isPondering() { return true; }
        @Override public void ponder() { fail("Cancel must not change the main engine"); }
      };
      for (String language : List.of("zh-CN", "zh-TW", "en-US", "ja-JP", "ko", "th-TH")) {
        Locale locale = Locale.forLanguageTag(language);
        onEdt(() -> {
          Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
          Config.sysDefaultFontName = LocaleFontSupport.resolveDefaultFontName(
              Lizzie.resourceBundle.getString("Lizzie.defaultFontName"), locale);
        });
        for (int size : List.of(12, 16, 20)) {
          JDialog[] dialogs = new JDialog[2];
          try {
            onEdt(() -> {
              Config.frameFontSize = size;
              Lizzie.leelaz = size == 12 ? null : pondering;
              dialogs[0] = new SetKataRules(null, true, KataGoRules.parse("goe").orElseThrow());
              dialogs[1] = new SetAnalysisRules();
              dialogs[1].setModal(false);
            });
            for (JDialog dialog : dialogs) {
              showAndAwaitFocus(dialog);
              onEdt(() -> {
                dialog.validate();
                Path output = Path.of(System.getProperty("lizzie.desktop.evidence.dir",
                    "target/desktop-smoke/probes"), "rules-dialogs");
                Files.createDirectories(output);
                BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { dialog.printAll(graphics); } finally { graphics.dispose(); }
                ImageIO.write(image, "png", output.resolve(dialog.getClass().getSimpleName()
                    + "-" + language + "-" + size + ".png").toFile());
                for (Component control : dialog.getContentPane().getComponents()) {
                  if (!control.isVisible()) continue;
                  assertTrue(control.getX() >= 0 && control.getY() >= 0
                      && control.getX() + control.getWidth() <= dialog.getContentPane().getWidth()
                      && control.getY() + control.getHeight() <= dialog.getContentPane().getHeight(),
                      language + "/" + size + " outside dialog: " + control);
                  assertTrue(control.getWidth() >= control.getPreferredSize().width
                      && control.getHeight() >= control.getPreferredSize().height,
                      language + "/" + size + " clipped text: " + control);
                  String text = control instanceof JLabel label ? label.getText()
                      : control instanceof AbstractButton button ? button.getText() : null;
                  if (text != null) {
                    assertEquals(-1, control.getFont().canDisplayUpTo(text),
                        language + "/" + size + " missing glyphs: " + text);
                  }
                }
              });
            }
            onEdt(() -> {
              JDialog analysis = dialogs[1];
              for (Component component : analysis.getContentPane().getComponents()) {
                if (component instanceof JFontButton button
                    && button.getText().equals(Lizzie.resourceBundle.getString("SetKataRules.btnCancel"))) {
                  button.doClick();
                }
              }
              assertFalse(analysis.isVisible(), "Cancel must work with or without an engine");
            });
          } finally {
            onEdt(() -> {
              for (JDialog dialog : dialogs) if (dialog != null) dialog.dispose();
            });
          }
        }
      }
    } finally {
      onEdt(() -> {
        Lizzie.config = previous.config();
        Lizzie.resourceBundle = previous.bundle();
        Lizzie.leelaz = previous.engine();
        Config.frameFontSize = previous.fontSize();
        Config.sysDefaultFontName = previous.fontName();
      });
    }
  }

  private record UiState(Config config, ResourceBundle bundle, Leelaz engine,
      int fontSize, String fontName) {}

  private static void showAndAwaitFocus(JDialog dialog) throws Exception {
    CountDownLatch focused = new CountDownLatch(1);
    WindowAdapter listener = new WindowAdapter() {
      @Override public void windowGainedFocus(WindowEvent event) {
        focused.countDown();
      }
    };
    onEdt(() -> {
      dialog.addWindowFocusListener(listener);
      dialog.setVisible(true);
    });
    try {
      // Native decoration insets and content bounds may still change after setVisible returns.
      // Let the EDT process initial mapping before validating and measuring the visible layout.
      assertTrue(focused.await(5, TimeUnit.SECONDS), "rules editor must receive initial focus");
    } finally {
      onEdt(() -> dialog.removeWindowFocusListener(listener));
    }
  }

  private static void onEdt(CheckedRunnable action) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try {
        action.run();
      } catch (Exception failure) {
        throw new RuntimeException(failure);
      }
    });
  }

  private interface CheckedRunnable {
    void run() throws Exception;
  }
}
