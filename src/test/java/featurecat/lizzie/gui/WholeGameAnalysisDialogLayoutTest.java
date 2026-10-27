package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisProvenance;
import featurecat.lizzie.analysis.WholeGameAnalysisOptions;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class WholeGameAnalysisDialogLayoutTest {
  @Test
  void runningMetadataFitsTheInitialDialogWithoutAnOrdinaryScrollbar() throws Exception {
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle =
          ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      SwingUtilities.invokeAndWait(
          () -> {
            WholeGameAnalysisDialog dialog = null;
            try {
              dialog = new WholeGameAnalysisDialog(null);
              JLabel progress =
                  (JLabel) findByName(dialog.getContentPane(), "wholeGameProgressText");
              JLabel mode = (JLabel) findByName(dialog.getContentPane(), "wholeGameMode");
              JLabel remaining =
                  (JLabel) findByName(dialog.getContentPane(), "wholeGameRemaining");
              JScrollPane scrollPane =
                  (JScrollPane) findByName(dialog.getContentPane(), "wholeGameScrollPane");
              progress.setText("已完成 1234 / 2345 个局面 · 当前计算量 5000");
              mode.setText(Lizzie.resourceBundle.getString("WholeGameAnalysis.mode.local"));
              remaining.setText(
                  java.text.MessageFormat.format(
                      Lizzie.resourceBundle.getString("WholeGameAnalysis.remaining"), "12:34"));
              layoutTree(dialog.getContentPane());
              scrollPane.doLayout();

              assertFalse(scrollPane.getVerticalScrollBar().isVisible());
              assertTextFits(progress, Locale.SIMPLIFIED_CHINESE);
              assertTextFits(mode, Locale.SIMPLIFIED_CHINESE);
              assertTextFits(remaining, Locale.SIMPLIFIED_CHINESE);
            } catch (Throwable throwable) {
              failure.set(throwable);
            } finally {
              if (dialog != null) {
                dialog.dispose();
              }
            }
          });
      if (failure.get() != null) {
        throw new AssertionError("Running-state layout failed", failure.get());
      }
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void localizedProgressAndControlsRemainVisibleAtCommonDpiScales() throws Exception {
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      for (Locale locale :
          List.of(
              Locale.SIMPLIFIED_CHINESE,
              Locale.TRADITIONAL_CHINESE,
              Locale.US,
              Locale.JAPAN,
              Locale.KOREA,
              Locale.forLanguageTag("th-TH"))) {
        for (float scale : List.of(1.0f, 1.25f, 1.5f, 2.0f)) {
          AtomicReference<Throwable> failure = new AtomicReference<>();
          SwingUtilities.invokeAndWait(
              () -> {
                WholeGameAnalysisDialog dialog = null;
                try {
                  ResourceBundle resources = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
                  Lizzie.resourceBundle = resources;
                  dialog = new WholeGameAnalysisDialog(null);
                  JLabel progress =
                      (JLabel) findByName(dialog.getContentPane(), "wholeGameProgressText");
                  JLabel mode = (JLabel) findByName(dialog.getContentPane(), "wholeGameMode");
                  JLabel remaining =
                      (JLabel) findByName(dialog.getContentPane(), "wholeGameRemaining");
                  JComboBox<?> visits =
                      (JComboBox<?>) findByName(dialog.getContentPane(), "wholeGameVisitsPreset");
                  JSpinner customVisits =
                      (JSpinner) findByName(dialog.getContentPane(), "wholeGameCustomVisits");
                  JComboBox<?> policy =
                      (JComboBox<?>) findByName(dialog.getContentPane(), "wholeGameAnalysisPolicy");
                  JTextArea provenance =
                      (JTextArea) findByName(dialog.getContentPane(), "wholeGameAnalysisProvenance");
                  assertNotNull(progress, locale.toString());
                  assertNotNull(mode, locale.toString());
                  assertNotNull(remaining, locale.toString());
                  assertNotNull(visits, locale.toString());
                  assertNotNull(customVisits, locale.toString());
                  assertNotNull(policy, locale.toString());
                  assertNotNull(provenance, locale.toString());
                  progress.setText(
                      java.text.MessageFormat.format(
                          resources.getString("WholeGameAnalysis.progress"), 1234, 2345, 5000));
                  mode.setText(resources.getString("WholeGameAnalysis.mode.local"));
                  remaining.setText(
                      java.text.MessageFormat.format(
                          resources.getString("WholeGameAnalysis.remaining"), "12:34"));
                  scaleFonts(dialog.getContentPane(), scale);
                  policy.setSelectedIndex(1);
                  provenance.setText(
                      AnalysisProvenanceDisplay.describe(longRecordedAnalysis(), resources));
                  dialog.pack();
                  layoutTree(dialog.getContentPane());

                  assertVisibleSize(progress, locale);
                  assertVisibleSize(mode, locale);
                  assertVisibleSize(remaining, locale);
                  assertVisibleSize(visits, locale);
                  assertVisibleSize(policy, locale);
                  assertVisibleSize(provenance, locale);
                  assertTrue(policy.getWidth() >= policy.getPreferredSize().width);
                  assertTrue(provenance.getLineWrap());
                  JScrollPane provenanceScroll =
                      (JScrollPane) findByName(dialog.getContentPane(), "wholeGameProvenanceScrollPane");
                  assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                      provenanceScroll.getHorizontalScrollBarPolicy());
                  assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                      provenanceScroll.getVerticalScrollBarPolicy());
                  assertTrue(
                      visits.getWidth() >= visits.getPreferredSize().width,
                      locale + " visits preset width at " + scale);
                  assertFalse(customVisits.isVisible(), locale + " default custom field");
                  assertTextFits(progress, locale);
                  assertTextFits(mode, locale);
                  assertTextFits(remaining, locale);
                  assertButtonFits(
                      (JButton) findByName(dialog.getContentPane(), "wholeGameStart"), locale);
                  assertButtonFits(
                      (JButton) findByName(dialog.getContentPane(), "wholeGamePause"), locale);
                  assertButtonFits(
                      (JButton) findByName(dialog.getContentPane(), "wholeGameStop"), locale);
                } catch (Throwable throwable) {
                  failure.set(throwable);
                } finally {
                  if (dialog != null) {
                    dialog.dispose();
                  }
                }
              });
          if (failure.get() != null) {
            throw new AssertionError(
                "Layout failed for " + locale + " at " + scale, failure.get());
          }
        }
      }
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void customVisitSelectionShowsAnEditableBoundedValue() throws Exception {
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle =
          ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      SwingUtilities.invokeAndWait(
          () -> {
            WholeGameAnalysisDialog dialog = null;
            try {
              dialog = new WholeGameAnalysisDialog(null);
              dialog.setSelectedVisits(12_345);
              dialog.pack();
              layoutTree(dialog.getContentPane());
              JSpinner customVisits =
                  (JSpinner) findByName(dialog.getContentPane(), "wholeGameCustomVisits");

              assertVisibleSize(customVisits, Locale.SIMPLIFIED_CHINESE);
              assertTrue(customVisits.isEnabled());
              assertTrue(dialog.selectedOptions().isValid());
              assertEquals(12_345, dialog.selectedOptions().deepVisits());
            } catch (Throwable throwable) {
              failure.set(throwable);
            } finally {
              if (dialog != null) {
                dialog.dispose();
              }
            }
          });
      if (failure.get() != null) {
        throw new AssertionError("Custom visits layout failed", failure.get());
      }
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void startCommitsTypedCustomVisitsAndRejectsAnUncommittedOutOfRangeValue() throws Exception {
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle =
          ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      SwingUtilities.invokeAndWait(
          () -> {
            WholeGameAnalysisDialog dialog = null;
            try {
              dialog = new WholeGameAnalysisDialog(null);
              dialog.setSelectedVisits(12_345);
              JSpinner customVisits =
                  (JSpinner) findByName(dialog.getContentPane(), "wholeGameCustomVisits");
              JFormattedTextField editor =
                  ((JSpinner.DefaultEditor) customVisits.getEditor()).getTextField();

              editor.setText("54321");
              WholeGameAnalysisOptions committed = dialog.commitSelectedOptions();
              assertNotNull(committed);
              assertEquals(54_321, committed.deepVisits());
              assertEquals(54_321, customVisits.getValue());

              editor.setText("12345500");
              assertNull(dialog.commitSelectedOptions());
              assertEquals(54_321, customVisits.getValue());
            } catch (Throwable throwable) {
              failure.set(throwable);
            } finally {
              if (dialog != null) {
                dialog.dispose();
              }
            }
          });
      if (failure.get() != null) {
        throw new AssertionError("Custom visits commit failed", failure.get());
      }
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void selectedReusePolicyReachesStartAndRejectsAnEstimateForTheOtherPolicy() throws Exception {
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    ResourceBundle previous = Lizzie.resourceBundle;
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try {
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.KOREA);
      SwingUtilities.invokeAndWait(
          () -> {
            WholeGameAnalysisDialog dialog = null;
            try {
              dialog = new WholeGameAnalysisDialog(null);
              RecordingFrame frame = allocate(RecordingFrame.class);
              field(WholeGameAnalysisDialog.class, "ownerFrame").set(dialog, frame);
              dialog.setSelectedVisits(3000);
              JComboBox<?> policy =
                  (JComboBox<?>) findByName(dialog.getContentPane(), "wholeGameAnalysisPolicy");
              JTextArea hint =
                  (JTextArea) findByName(dialog.getContentPane(), "wholeGameVisitsHint");
              assertEquals(WholeGameAnalysisOptions.ExistingAnalysisPolicy.REUSE_SAVED,
                  dialog.selectedOptions().existingAnalysisPolicy());
              WholeGameAnalysisOptions savedSelection = dialog.selectedOptions();

              policy.setSelectedIndex(1);
              assertTrue(dialog.selectedOptions().reevaluateCurrentEngine());
              String currentHint = hint.getText();
              dialog.showPreStartEstimate(savedSelection, 90_000);
              assertEquals(currentHint, hint.getText());
              dialog.showPreStartEstimate(dialog.selectedOptions(), 90_000);
              assertTrue(hint.getText().contains("01:30"));

              ((JButton) findByName(dialog.getContentPane(), "wholeGameStart")).doClick();

              assertNotNull(frame.startedOptions);
              assertEquals(3000, frame.startedOptions.deepVisits());
              assertTrue(frame.startedOptions.reevaluateCurrentEngine());
              assertFalse(policy.isEnabled());
            } catch (Throwable throwable) {
              failure.set(throwable);
            } finally {
              if (dialog != null) dialog.dispose();
            }
          });
      if (failure.get() != null) throw new AssertionError("Policy selection failed", failure.get());
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void displayedPositionSourceRefreshesAndItsTimerStopsWhenHiddenOrDisposed() throws Exception {
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    ResourceBundle previousResources = Lizzie.resourceBundle;
    Board previousBoard = Lizzie.board;
    AtomicReference<WholeGameAnalysisDialog> dialog = new AtomicReference<>();
    try {
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.KOREA);
      BoardData legacy = BoardData.empty(19, 19);
      legacy.setPlayouts(700);
      legacy.engineName = "Saved legacy name";
      BoardHistoryList history = new BoardHistoryList(legacy);
      BoardData recorded = longRecordedAnalysis();
      recorded.moveNumber = 1;
      history.getStart().add(new BoardHistoryNode(recorded));
      Board board = allocate(Board.class);
      field(Board.class, "history").set(board, history);
      Lizzie.board = board;
      SwingUtilities.invokeAndWait(
          () -> {
            dialog.set(new WholeGameAnalysisDialog(null));
            dialog.get().showOnScreen();
          });
      waitOnEdt(
          () -> provenanceText(dialog.get()).contains("Saved legacy name")
              && provenanceText(dialog.get()).contains("분석 출처 미확인"));
      Timer timer = (Timer) field(WholeGameAnalysisDialog.class, "provenanceRefresh").get(dialog.get());
      waitOnEdt(timer::isRunning);

      SwingUtilities.invokeAndWait(() -> history.next(true));

      waitOnEdt(
          () -> provenanceText(dialog.get()).contains("현재 국면: 1수")
              && provenanceText(dialog.get()).contains("b18c384nbt-"));
      Path screenshot = Path.of("target/provenance-ui/whole-game-source-ko.png");
      Files.createDirectories(screenshot.getParent());
      SwingUtilities.invokeAndWait(
          () -> {
            BufferedImage image =
                new BufferedImage(dialog.get().getWidth(), dialog.get().getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            try {
              dialog.get().paintAll(graphics);
              ImageIO.write(image, "png", screenshot.toFile());
            } catch (java.io.IOException exception) {
              throw new java.io.UncheckedIOException(exception);
            } finally {
              graphics.dispose();
            }
            dialog.get().setVisible(false);
          });
      waitOnEdt(() -> !timer.isRunning());
      SwingUtilities.invokeAndWait(() -> dialog.get().showOnScreen());
      waitOnEdt(timer::isRunning);
      SwingUtilities.invokeAndWait(() -> dialog.get().dispose());
      waitOnEdt(() -> !timer.isRunning());
    } finally {
      SwingUtilities.invokeAndWait(() -> { if (dialog.get() != null) dialog.get().dispose(); });
      Lizzie.board = previousBoard;
      Lizzie.resourceBundle = previousResources;
    }
  }

  private static BoardData longRecordedAnalysis() {
    BoardData data = BoardData.empty(19, 19);
    data.setPlayouts(700);
    data.setPrimaryAnalysisProvenance(
        new AnalysisProvenance(
            "KataGo", "1.16.0", "b18c384nbt-" + "long-recorded-model-name-".repeat(5),
            "a".repeat(64), "b".repeat(64), "chinese", 7.5, 0.0, 0.25, "rank_5d"));
    data.estimateArray = List.of(0.5);
    return data;
  }

  private static String provenanceText(WholeGameAnalysisDialog dialog) {
    return ((JTextArea) findByName(dialog.getContentPane(), "wholeGameAnalysisProvenance")).getText();
  }

  private static void waitOnEdt(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      AtomicReference<Boolean> ready = new AtomicReference<>();
      SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
      if (Boolean.TRUE.equals(ready.get())) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Timed out waiting for provenance UI state");
  }

  private static Field field(Class<?> type, String name) throws NoSuchFieldException {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
    return type.cast(unsafe.allocateInstance(type));
  }

  private static final class RecordingFrame extends LizzieFrame {
    private WholeGameAnalysisOptions startedOptions;

    @Override
    boolean startWholeGameDeepAnalysis(
        WholeGameAnalysisDialog dialog, WholeGameAnalysisOptions options) {
      startedOptions = options;
      return true;
    }

    @Override
    void requestWholeGameAnalysisEstimate(
        WholeGameAnalysisDialog dialog, WholeGameAnalysisOptions options) {}
  }

  private static void assertVisibleSize(Component component, Locale locale) {
    assertNotNull(component, locale.toString());
    assertTrue(component.isVisible(), locale.toString());
    assertTrue(component.getWidth() > 0, locale + " width");
    assertTrue(component.getHeight() > 0, locale + " height");
  }

  private static void assertTextFits(JLabel label, Locale locale) {
    assertTrue(
        label.getWidth() >= label.getPreferredSize().width,
        locale + " label width: " + label.getText());
    assertTrue(
        label.getHeight() >= label.getPreferredSize().height,
        locale + " label height: " + label.getText());
  }

  private static void assertButtonFits(JButton button, Locale locale) {
    assertVisibleSize(button, locale);
    Dimension preferred = button.getPreferredSize();
    Dimension natural = button.getUI().getPreferredSize(button);
    String dimensions =
        locale + " button: " + button.getText()
            + " actual=" + button.getSize() + " preferred=" + preferred + " natural=" + natural;
    assertTrue(
        button.getWidth() >= preferred.width,
        "Preferred width does not fit: " + dimensions);
    assertTrue(
        button.getHeight() >= preferred.height,
        "Preferred height does not fit: " + dimensions);
    assertTrue(
        button.getWidth() >= natural.width,
        "Current-font content width does not fit: " + dimensions);
    assertTrue(
        button.getHeight() >= natural.height,
        "Current-font content height does not fit: " + dimensions);
  }

  private static void scaleFonts(Component component, float scale) {
    Font font = component.getFont();
    if (font != null) {
      component.setFont(font.deriveFont(font.getSize2D() * scale));
    }
    if (component instanceof Container) {
      for (Component child : ((Container) component).getComponents()) {
        scaleFonts(child, scale);
      }
    }
  }

  private static void layoutTree(Container container) {
    container.doLayout();
    for (Component child : container.getComponents()) {
      if (child instanceof Container) {
        layoutTree((Container) child);
      }
    }
  }

  private static Component findByName(Container container, String name) {
    for (Component child : container.getComponents()) {
      if (name.equals(child.getName())) {
        return child;
      }
      if (child instanceof Container) {
        Component match = findByName((Container) child, name);
        if (match != null) {
          return match;
        }
      }
    }
    return null;
  }
}
