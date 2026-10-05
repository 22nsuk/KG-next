package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;

/** Binds both board context menus to their captured desktop owner, including float-board use. */
final class BoardPopupMenus {
  private static final Object LIFECYCLE_KEY = new Object();

  private BoardPopupMenus() {}

  static void install(JPopupMenu popup, boolean clearHoverAfterHide) {
    PopupMenuLifecycle.install(
        popup,
        () -> lifecycleFor(Lizzie.frame),
        () -> clearHoverAfterHide ? captureHoverCleanup(Lizzie.frame) : () -> {});
  }

  private static PopupMenuLifecycle lifecycleFor(LizzieFrame frame) {
    JRootPane root = frame.getRootPane();
    PopupMenuLifecycle lifecycle = (PopupMenuLifecycle) root.getClientProperty(LIFECYCLE_KEY);
    if (lifecycle == null) {
      lifecycle = new PopupMenuLifecycle(root, showing -> frame.isShowingRightMenu = showing);
      root.putClientProperty(LIFECYCLE_KEY, lifecycle);
    }
    return lifecycle;
  }

  private static Runnable captureHoverCleanup(LizzieFrame frame) {
    IndependentMainBoard independent = frame.independentMainBoard;
    return () -> {
      // clearMoved uses other application state too: never run an old frame's cleanup on a new app.
      if (Lizzie.frame != frame || !frame.isDisplayable()) return;
      if (frame.isMouseOver) {
        frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
        frame.isMouseOver = false;
        frame.clearMoved();
      }
      if (independent != null
          && frame.independentMainBoard == independent
          && independent.isDisplayable()
          && independent.isMouseOver) {
        independent.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
        independent.isMouseOver = false;
        independent.clearMoved();
      }
    };
  }
}
