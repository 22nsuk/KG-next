package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.util.Utils;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Test;

/** Real input and live-sync callers with a fixed board node or a replaced scroll context. */
public class VariationTreeInvalidationTest {
  @Test
  void branchReorderAndLiveFirstSyncRespectCurrentTree() throws Exception {
    DesktopProbeProcess.requireDisplay();
    for (String scale : List.of("1", "2")) {
      Path result =
          DesktopProbeProcess.run(
              VariationTreeInvalidationTest.class,
              "variation-invalidation-" + scale,
              List.of("-Dsun.java2d.uiScale=" + scale),
              List.of());
      assertTrue(Files.readString(result).contains("PASS"));
    }
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,"
              + "\"first-time-load\":false,\"use-language\":2}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(Lizzie.frame.isShowing());
            // Keep the real window within the display at both Java2D scales.
            var screen = Lizzie.frame.getGraphicsConfiguration().getBounds();
            Lizzie.frame.setBounds(
                screen.x, screen.y, Math.min(900, screen.width), Math.min(700, screen.height));
            Lizzie.config.showVariationGraph = true;
            Lizzie.config.ignoreOutOfWidth = true;
          });
      for (boolean simple : new boolean[] {true, false}) {
        branchReorderScenario(simple, result);
      }
      liveFirstSyncScenario(result);
      newerContextScenario(result);
      Files.writeString(
          result,
          "PASS: Shift+Left/Right redraw both tree modes without changing the current node; "
              + "live first sync scrolls to the end after refresh; newer nodes and histories discard old end-scroll intent; "
              + "javaScaleFactor="
              + Lizzie.javaScaleFactor
              + "\n");
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(result, failure.toString());
      System.exit(1);
    }
  }

  private static void branchReorderScenario(boolean simple, Path result) throws Exception {
    AtomicReference<BoardHistoryNode> parent = new AtomicReference<>();
    AtomicReference<BoardHistoryNode> selected = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          assertTrue(
              SGFParser.loadFromString(
                  "(;FF[4]GM[1]SZ[19];B[aa](;W[ba];B[ca])(;W[da];B[ea])(;W[fa];B[ga]))",
                  false));
          parent.set(Lizzie.board.getHistory().getStart().next().orElseThrow());
          selected.set(parent.get().getVariation(1).orElseThrow());
          Lizzie.board.navigateToNode(selected.get());
          Lizzie.config.showScrollVariation = !simple;
          Lizzie.frame.refresh();
          assertTrue(
              Arrays.asList(Lizzie.frame.mainPanel.getKeyListeners()).contains(Lizzie.frame.input),
              "exercise the main panel's registered keyboard handler");
        });
    VariationTreeImage.Result previous =
        awaitTree(null, selected.get(), simple, tree -> true, "initial branch tree");
    for (int key : new int[] {KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT}) {
      VariationTreeImage.Result before = previous;
      SwingUtilities.invokeAndWait(
          () -> {
            var board = Lizzie.board;
            var history = board.getHistory();
            long revision = board.getContextRevision();
            List<BoardHistoryNode> siblings = List.copyOf(parent.get().variations);
            int oldIndex = siblings.indexOf(selected.get());
            int newIndex = oldIndex + (key == KeyEvent.VK_LEFT ? -1 : 1);
            assertTrue(newIndex >= 0 && newIndex < siblings.size());
            Lizzie.frame.input.keyPressed(
                new KeyEvent(
                    Lizzie.frame.mainPanel,
                    KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(),
                    InputEvent.SHIFT_DOWN_MASK,
                    key,
                    KeyEvent.CHAR_UNDEFINED));
            assertSame(board, Lizzie.board);
            assertSame(history, board.getHistory());
            assertSame(selected.get(), history.getCurrentHistoryNode());
            assertEquals(revision, board.getContextRevision());
            assertSame(selected.get(), parent.get().getVariation(newIndex).orElseThrow());
            assertSame(siblings.get(newIndex), parent.get().getVariation(oldIndex).orElseThrow());
          });
      previous =
          awaitTree(before, selected.get(), simple, tree -> true, "branch shortcut redraw");
      VariationTreeImage.Result after = previous;
      SwingUtilities.invokeAndWait(
          () -> {
            assertEquals(before.view(), after.view(), "node and viewport identity did not change");
            assertFrozenLayoutMatchesCurrentTree(after);
          });
      ImageIO.write(
          after.image(),
          "png",
          result.resolveSibling("branch-" + simple + "-" + key + ".png").toFile());
    }
    DesktopProbeProcess.phase(result, "branch-shortcuts-" + (simple ? "simple" : "scroll") + "-passed");
  }

  private static void liveFirstSyncScenario(Path result) throws Exception {
    AtomicReference<BoardHistoryNode> root = new AtomicReference<>();
    AtomicReference<BoardHistoryNode> end = new AtomicReference<>();
    AtomicReference<JScrollPane> scroll = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          assertTrue(SGFParser.loadFromString(longBranchFixture(), false));
          Lizzie.config.showScrollVariation = true;
          root.set(Lizzie.board.getHistory().getStart());
          assertEquals(2, root.get().numberOfChildren(), "fixture keeps distinct main and side branches");
          end.set(Lizzie.board.getHistory().getMainEnd());
          assertEquals(80, end.get().getData().moveNumber);
          Lizzie.board.goToMoveNumber(0);
          Lizzie.frame.refresh();
          scroll.set(scrollPane());
        });
    VariationTreeImage.Result before =
        awaitTree(null, root.get(), false, tree -> true, "initial live tree");
    SwingUtilities.invokeAndWait(
        () -> {
          LizzieFrame.urlSgf = true;
          Lizzie.frame.syncLiveBoardStat();
          Timer timer = Lizzie.frame.timer;
          // Trigger the production timer listener once, without depending on a wall-clock tick.
          timer.stop();
          for (var listener : timer.getActionListeners()) {
            listener.actionPerformed(new ActionEvent(timer, ActionEvent.ACTION_PERFORMED, "first-sync"));
          }
        });
    VariationTreeImage.Result after =
        awaitTree(
            before,
            end.get(),
            false,
            tree -> scroll.get().getViewport().getViewPosition().y == maximumY(scroll.get()),
            "live first sync must scroll to the tree end after refresh");
    SwingUtilities.invokeAndWait(
        () -> {
          assertFalse(Lizzie.frame.firstSync);
          assertEquals(80, Lizzie.frame.maxMvNum);
          assertTrue(maximumY(scroll.get()) > centeredY(after, scroll.get()) + 100,
              "longer side branch distinguishes scrolling to the end from centering the main end");
          assertEquals(maximumY(scroll.get()), scroll.get().getViewport().getViewPosition().y);
          LizzieFrame.urlSgf = false;
          Lizzie.frame.timer.stop();
          Lizzie.frame.timer = null;
        });
    ImageIO.write(after.image(), "png", result.resolveSibling("live-first-sync.png").toFile());
    DesktopProbeProcess.phase(result, "live-first-sync-end-scroll-passed");
  }

  private static void newerContextScenario(Path result) throws Exception {
    AtomicReference<BoardHistoryNode> current = new AtomicReference<>();
    AtomicReference<JScrollPane> scroll = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          scroll.set(scrollPane());
          // The EDT cannot publish the old drawing before the newer navigation cancels it.
          Lizzie.frame.renderVarTree(0, 0, false, true);
          Lizzie.board.goToMoveNumber(2);
          current.set(Lizzie.board.getHistory().getCurrentHistoryNode());
        });
    VariationTreeImage.Result navigated =
        awaitTree(null, current.get(), false,
            tree -> isCentered(tree, scroll.get()), "newer navigation must discard end-scroll intent");
    SwingUtilities.invokeAndWait(
        () -> {
          assertTrue(centeredY(navigated, scroll.get()) < maximumY(scroll.get()));
          var oldHistory = Lizzie.board.getHistory();
          Lizzie.frame.renderVarTree(0, 0, false, true);
          assertTrue(SGFParser.loadFromString(longBranchFixture(), false));
          assertNotSame(oldHistory, Lizzie.board.getHistory());
          Lizzie.board.goToMoveNumber(3);
          current.set(Lizzie.board.getHistory().getCurrentHistoryNode());
          Lizzie.frame.refresh();
        });
    VariationTreeImage.Result replaced =
        awaitTree(null, current.get(), false,
            tree -> isCentered(tree, scroll.get()), "replacement history must discard end-scroll intent");
    SwingUtilities.invokeAndWait(
        () -> assertTrue(centeredY(replaced, scroll.get()) < maximumY(scroll.get())));
    DesktopProbeProcess.phase(result, "newer-scroll-contexts-passed");
  }

  private static boolean isCentered(VariationTreeImage.Result tree, JScrollPane scroll) {
    return scroll.getViewport().getViewPosition().y == centeredY(tree, scroll);
  }

  private static int centeredY(VariationTreeImage.Result tree, JScrollPane scroll) {
    return Math.min(maximumY(scroll),
        Math.max(0, Utils.zoomIn(tree.currentY()) - scroll.getViewport().getExtentSize().height / 2));
  }

  private static int maximumY(JScrollPane scroll) {
    var viewport = scroll.getViewport();
    return Math.max(0, viewport.getViewSize().height - viewport.getExtentSize().height);
  }

  private static JScrollPane scrollPane() {
    try {
      var field = LizzieFrame.class.getDeclaredField("varTreeScrollPane");
      field.setAccessible(true);
      return (JScrollPane) field.get(Lizzie.frame);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static VariationTreeImage.Result awaitTree(
      VariationTreeImage.Result previous,
      BoardHistoryNode node,
      boolean simple,
      Predicate<VariationTreeImage.Result> settled,
      String scenario) throws Exception {
    CompletableFuture<VariationTreeImage.Result> ready = new CompletableFuture<>();
    SwingUtilities.invokeAndWait(
        () -> {
          Timer observer = new Timer(30, null);
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          observer.addActionListener(
              event -> {
                try {
                  VariationTreeImage.Result tree = Lizzie.frame.currentVariationTreeImage();
                  if (System.nanoTime() > deadline) {
                    Point position = scrollPane().getViewport().getViewPosition();
                    throw new AssertionError(scenario + "; published=" + (tree != null)
                        + "; viewport=" + position + "; maxY=" + maximumY(scrollPane()));
                  }
                  if (tree == null || tree == previous || tree.view().simple() != simple
                      || tree.view().boardNode() != node || !settled.test(tree)) return;
                  observer.stop();
                  ready.complete(tree);
                } catch (Throwable failure) {
                  observer.stop();
                  ready.completeExceptionally(failure);
                }
              });
          observer.start();
        });
    return ready.get(15, TimeUnit.SECONDS);
  }

  private static void assertFrozenLayoutMatchesCurrentTree(VariationTreeImage.Result actual) {
    VariationTreeImage.Result expected = VariationTreeImage.draw(actual.view(), () -> false);
    int width = actual.image().getWidth();
    int height = actual.image().getHeight();
    assertEquals(width, expected.image().getWidth());
    assertEquals(height, expected.image().getHeight());
    assertArrayEquals(
        expected.image().getRGB(0, 0, width, height, null, 0, width),
        actual.image().getRGB(0, 0, width, height, null, 0, width));
    for (int y = 0; y < height; y += 5) {
      for (int x = 0; x < width; x += 5) {
        assertEquals(expected.renderer().nodeAt(x, y), actual.renderer().nodeAt(x, y));
      }
    }
  }

  private static String longBranchFixture() {
    StringBuilder sgf = new StringBuilder("(;FF[4]GM[1]SZ[19]");
    for (int length : new int[] {80, 150}) {
      sgf.append('(');
      for (int move = 0; move < length; move++) {
        // A distinct opening prevents the SGF parser from merging equal move identities.
        if (length == 150 && move == 0) {
          sgf.append(";B[ss]");
          continue;
        }
        sgf.append(move % 2 == 0 ? ";B[" : ";W[")
            .append((char) ('a' + move % 19))
            .append((char) ('a' + move / 19))
            .append(']');
      }
      sgf.append(')');
    }
    return sgf.append(')').toString();
  }
}
