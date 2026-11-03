package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.AppleStyleSupport;
import featurecat.lizzie.gui.ScoreResult;
import featurecat.lizzie.gui.TopHeaderPanel;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real count controls and SGF persistence, with the existing offline board/engine fixture. */
class ManualScoreResultTest {
  @TempDir Path temp;

  @Test
  void drawsDisplayConfirmAndReopenAcrossRulesLanguagesAndThemes() throws Exception {
    requireDisplay();
    onEdt(
        () -> {
          String[][] languages = {
            {"en-US", "Draw"},
            {"zh-CN", "和棋"},
            {"zh-TW", "和棋"},
            {"zh-HK", "和棋"},
            {"ja-JP", "持碁"},
            {"ko", "무승부"},
            {"th-TH", "เสมอ"}
          };
          for (String[] language : languages) {
            for (int theme = 0; theme < 3; theme++) {
              try (Environment ignored = new Environment(language[0], theme)) {
                ScoreResult dialog = new ScoreResult(null);
                try {
                  for (boolean territory : new boolean[] {false, true}) {
                    Lizzie.config.useTerritoryInScore = territory;
                    // Area: 184 = 177 + 7. Territory: 20 - 3 = 14 - 4 + 7.
                    if (territory) dialog.setScore(100, 20, 200, 14, 4, 3, 7);
                    else dialog.setScore(100, 84, 100, 77, 19, 23, 7);
                    assertEquals(language[1], text(dialog, "scoreResult"), language[0]);
                    assertEquals(Color.GRAY, line(dialog, "scoreResult").getBackground());
                    assertEquals(Color.WHITE, line(dialog, "scoreResult").getForeground());
                    assertEquals("", Lizzie.board.getHistory().getGameInfo().getResult());
                    if (!territory && theme == 0) capture(dialog, language[0]);
                    confirmAndReopen(dialog, language[1]);
                    Lizzie.board.getHistory().getGameInfo().setResultNoUI("");
                  }
                } finally {
                  dialog.dispose();
                }
              }
            }
          }
        });
  }

  @Test
  void winsRetainMarginsColorsAndRuleToggleRecalculatesTheConfirmedResult() throws Exception {
    requireDisplay();
    onEdt(
        () -> {
          try (Environment ignored = new Environment("en-US", 0)) {
            ScoreResult dialog = new ScoreResult(null);
            try {
              for (double komi : new double[] {6, 6.5, 7.5, 8}) {
                dialog.setScore(100, 84, 100, 77, 0, 0, komi);
                String expected = komi < 7 ? "Black win" : "White win";
                expected += Math.abs(7 - komi) + " points";
                assertEquals(expected, text(dialog, "scoreResult"));
                assertEquals(
                    komi < 7 ? Color.BLACK : AppleStyleSupport.validFieldBackground(),
                    line(dialog, "scoreResult").getBackground());
                assertEquals(
                    komi < 7 ? Color.WHITE : Color.BLACK,
                    line(dialog, "scoreResult").getForeground());
                confirmAndReopen(dialog, expected);
              }
              dialog.setScore(100, 20, 200, 14, 4, 3, 7);
              assertEquals("White win101.0 points", text(dialog, "scoreResult"));
              ((JButton) field(dialog, "btnToggleRules")).doClick(0);
              assertTrue(Lizzie.config.useTerritoryInScore);
              assertEquals("Draw", text(dialog, "scoreResult"));
              assertEquals("Black:20-3=17.0", text(dialog, "blackScore"));
              assertEquals("White:14-4+7.0=17.0", text(dialog, "whiteScore"));
              confirmAndReopen(dialog, "Draw");
              ((JButton) field(dialog, "btnToggleRules")).doClick(0);
              assertEquals("White win101.0 points", text(dialog, "scoreResult"));
              assertTrue(SGFParser.loadFromStringforedit("(;SZ[19];B[aa];B[bb];W[cc])"));
              for (String bonus : new String[] {"N", "N-1"}) {
                while (Lizzie.board.getHistory().next().isPresent()) {}
                Lizzie.leelaz.recentRulesLine = "= {\"whiteHandicapBonus\":\"" + bonus + "\"}";
                dialog.setScore(bonus.equals("N") ? 186 : 185, 0, 177, 0, 0, 0, 7);
                assertEquals("Draw", text(dialog, "scoreResult"), bonus);
                confirmAndReopen(dialog, "Draw");
              }
            } finally {
              dialog.dispose();
            }
          }
        });
  }

  private void confirmAndReopen(ScoreResult dialog, String expected) throws Exception {
    ((JButton) field(dialog, "confirmResult")).doClick(0);
    assertEquals(expected, Lizzie.board.getHistory().getGameInfo().getResult());
    assertTrue(resultTitle().contains(expected));
    Path saved = temp.resolve("count.sgf");
    for (int round = 0; round < 2; round++) {
      SGFParser.save(Lizzie.board, saved.toString(), false);
      String sgf = Files.readString(saved);
      assertTrue(sgf.contains("RE[" + expected + "]"), sgf);
      assertTrue(SGFParser.loadFromStringforedit(sgf));
      assertEquals(expected, Lizzie.board.getHistory().getGameInfo().getResult());
      assertTrue(resultTitle().contains(expected));
    }
  }

  private static void requireDisplay() {
    if (Boolean.getBoolean("lizzie.desktop.required")) {
      assertFalse(GraphicsEnvironment.isHeadless(), "required count UI test needs a display");
    }
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
  }

  private static Object field(ScoreResult dialog, String name) throws Exception {
    Field field = ScoreResult.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(dialog);
  }

  private static String resultTitle() throws Exception {
    Field field = featurecat.lizzie.gui.LizzieFrame.class.getDeclaredField("resultTitle");
    field.setAccessible(true);
    return (String) field.get(Lizzie.frame);
  }

  private static JComponent line(ScoreResult dialog, String name) throws Exception {
    return (JComponent) field(dialog, name);
  }

  private static String text(ScoreResult dialog, String name) throws Exception {
    Object line = field(dialog, name);
    var method = line.getClass().getDeclaredMethod("getText");
    method.setAccessible(true);
    return (String) method.invoke(line);
  }

  private static void capture(ScoreResult dialog, String language) throws Exception {
    Path directory =
        Path.of(System.getProperty("lizzie.desktop.evidence.dir", "target/manual-score"));
    Files.createDirectories(directory);
    dialog.pack();
    dialog.setVisible(true);
    BufferedImage image =
        new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      dialog.paint(graphics);
    } finally {
      graphics.dispose();
    }
    ImageIO.write(image, "png", directory.resolve("manual-draw-" + language + ".png").toFile());
  }

  private static void onEdt(CheckedRunnable task) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            task.run();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private interface CheckedRunnable {
    void run() throws Exception;
  }

  private static final class Environment implements AutoCloseable {
    private final ResourceBundle previousBundle = Lizzie.resourceBundle;
    private final String previousFont = Config.sysDefaultFontName;
    private final RulesLayerTestHarness board = RulesLayerTestHarness.open(19);

    private Environment(String language, int theme) throws Exception {
      Lizzie.resourceBundle =
          ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(language));
      Config.sysDefaultFontName = "Dialog";
      Lizzie.config.uiConfig = new org.json.JSONObject();
      Lizzie.config.useMorandiColors = theme == 1;
      Lizzie.config.isAppleStyle = theme == 2;
      Lizzie.frame.mainPanel = new JPanel();
      Lizzie.frame.topPanel = new TopHeaderPanel();
    }

    @Override
    public void close() {
      board.close();
      Lizzie.resourceBundle = previousBundle;
      Config.sysDefaultFontName = previousFont;
    }
  }
}
