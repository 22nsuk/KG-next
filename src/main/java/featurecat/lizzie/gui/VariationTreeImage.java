package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** One running drawing, one latest request and one replaceable EDT completion for both tree modes. */
final class VariationTreeImage implements AutoCloseable {
  interface Renderer {
    void draw(Graphics2D graphics, int x, int y, int width, int height);
    Optional<BoardHistoryNode> nodeAt(int x, int y);
  }

  record View(
      Board board, BoardHistoryList history, BoardHistoryNode displayNode,
      BoardHistoryNode boardNode, long revision, int x, int y, int width, int height,
      int panelWidth, int panelHeight, LizzieFrame frame, boolean simple,
      VariationTreeSnapshot.Style style) {
    View(Board board, BoardHistoryList history, BoardHistoryNode displayNode,
        BoardHistoryNode boardNode, long revision, int x, int y, int width, int height,
        int panelWidth, int panelHeight) {
      this(board, history, displayNode, boardNode, revision, x, y, width, height,
          panelWidth, panelHeight, Lizzie.frame, true, VariationTreeSnapshot.Style.current());
    }

    boolean hasCurrentHistory() {
      return Lizzie.board == board && Lizzie.frame == frame && frame != null
          && board.getHistory() == history
          && history.getCurrentHistoryNode() == boardNode
          && board.getContextRevision() == revision
          && frame.getDisplayNode() == displayNode;
    }
  }

  record Result(View view, BufferedImage image, Renderer renderer,
      int currentX, int currentY, boolean widthLimited) {}

  @FunctionalInterface
  interface Drawing {
    Result draw(View view, BooleanSupplier cancelled);
  }

  private record Request(long generation, View view, BooleanSupplier current, Consumer<Result> publish) {}
  private record Completion(Request request, Result result, RuntimeException failure) {}

  private final Executor worker;
  private final Consumer<Runnable> completion;
  private final Drawing drawing;
  private volatile long generation;
  private boolean closed;
  private boolean workerScheduled;
  private boolean completionScheduled;
  private Request pending;
  private Completion finished;

  VariationTreeImage() {
    this(ForkJoinPool.commonPool(), SwingUtilities::invokeLater);
  }

  VariationTreeImage(Executor worker, Consumer<Runnable> completion) {
    this(worker, completion, VariationTreeImage::draw);
  }

  VariationTreeImage(Executor worker, Consumer<Runnable> completion, Drawing drawing) {
    this.worker = worker;
    this.completion = completion;
    this.drawing = drawing;
  }

  synchronized void request(View view, BooleanSupplier current, Consumer<Result> publish) {
    if (closed) return;
    pending = new Request(++generation, view, current, publish);
    finished = null;
    if (workerScheduled) return;
    workerScheduled = true;
    try {
      worker.execute(this::drain);
    } catch (RejectedExecutionException failure) {
      workerScheduled = false;
      pending = null;
      throw failure;
    }
  }

  synchronized void cancel() {
    ++generation;
    pending = null;
    finished = null;
  }

  @Override
  public synchronized void close() {
    closed = true;
    cancel();
  }

  private void drain() {
    while (true) {
      Request request;
      synchronized (this) {
        request = pending;
        pending = null;
        if (request == null) {
          workerScheduled = false;
          return;
        }
      }
      Completion result;
      try {
        Result image = drawing.draw(request.view(), () -> generation != request.generation());
        result = new Completion(request, image, null);
      } catch (CancellationException obsolete) {
        continue;
      } catch (RuntimeException failure) {
        // Report a failed drawing at the publication boundary; never leave the worker wedged.
        result = new Completion(request, null, failure);
      }
      synchronized (this) {
        if (generation != request.generation()) continue;
        finished = result;
        if (!completionScheduled) {
          completionScheduled = true;
          completion.accept(this::deliver);
        }
      }
    }
  }

  private void deliver() {
    Completion result;
    synchronized (this) {
      result = finished;
      finished = null;
      completionScheduled = false;
    }
    if (result == null || generation != result.request().generation()
        || !result.request().current().getAsBoolean()) return;
    if (result.failure() != null) {
      org.slf4j.LoggerFactory.getLogger(VariationTreeImage.class)
          .warn("Variation tree drawing failed", result.failure());
      return;
    }
    result.request().publish().accept(result.result());
  }

  static Result draw(View view, BooleanSupplier cancelled) {
    VariationTreeSnapshot snapshot;
    synchronized (view.board()) {
      VariationTreeSnapshot.checkCancelled(cancelled);
      if (!view.hasCurrentHistory()) throw new CancellationException("Stale variation history");
      snapshot = VariationTreeSnapshot.capture(
          view.history().getStart(), view.displayNode(), view.history().getEnd(), view.style(),
          view.frame()::getBlunderNodeColor, cancelled);
    }
    return render(view, snapshot, cancelled);
  }

  /** Package boundary also used by parity/lock tests: rendering must not read the live board. */
  static Result render(View view, VariationTreeSnapshot snapshot, BooleanSupplier cancelled) {
    VariationTreeSnapshot.checkCancelled(cancelled);
    Renderer renderer;
    int width = view.width();
    int height = view.height();
    int currentX = 0;
    int currentY = 0;
    boolean limited = false;
    if (view.simple()) {
      renderer = new VariationTreeBig(snapshot, cancelled);
    } else {
      VariationTree scroll = new VariationTree(snapshot, cancelled);
      // Measure with clipped graphics before allocating the final image. The established layout
      // algorithm is reused, but no full-size throwaway image or repeated resize thread is needed.
      BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
      Graphics2D graphics = scratch.createGraphics();
      try {
        scroll.draw(graphics, 0, 0, width, height);
      } finally {
        graphics.dispose();
      }
      width = Math.min(Math.max(1, snapshot.style.maxWidth()), scroll.contentWidth());
      height = scroll.contentHeight();
      limited = scroll.contentWidth() >= snapshot.style.maxWidth();
      currentX = scroll.currentX();
      currentY = scroll.currentY();
      renderer = scroll;
    }
    VariationTreeSnapshot.checkCancelled(cancelled);
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      renderer.draw(graphics, 0, 0, width, height);
    } finally {
      graphics.dispose();
    }
    VariationTreeSnapshot.checkCancelled(cancelled);
    if (renderer instanceof VariationTree scroll) {
      currentX = scroll.currentX();
      currentY = scroll.currentY();
    }
    return new Result(view, image, renderer, currentX, currentY, limited);
  }
}
