package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class LizzieFrameRulesCaptionTest {
  @Test
  void captionHitOpensPresetsUnlessSavedMatchRulesTakePriority() throws Exception {
    var reset = EngineManager.class.getDeclaredMethod("resetEngineGameTransactionStateForTest");
    reset.setAccessible(true);
    reset.invoke(null);
    Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    Frame frame = (Frame) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Frame.class);
    Field bounds = LizzieFrame.class.getDeclaredField("matchRulesCaptionBounds");
    bounds.setAccessible(true);
    Rectangle caption = new Rectangle(20, 30, 80, 20);
    bounds.set(frame, caption);
    try {
      assertFalse(frame.tryInspectMatchRulesAt(10, 10));
      assertEquals(0, frame.menus);
      assertTrue(frame.tryInspectMatchRulesAt(40, 40));
      assertEquals(1, frame.menus);
      assertEquals(caption, frame.shownAt);
      frame.inspectable = true;
      assertTrue(frame.tryInspectMatchRulesAt(40, 40));
      assertEquals(1, frame.menus, "Stored engine-game rules keep their detail view");
      bounds.set(frame, null);
      assertFalse(frame.tryInspectMatchRulesAt(40, 40));
    } finally {
      reset.invoke(null);
    }
  }

  private static final class Frame extends LizzieFrame {
    boolean inspectable;
    int menus;
    Rectangle shownAt;
    @Override public boolean inspectMatchRules() { return inspectable; }
    @Override void showRulesQuickMenu(Rectangle caption) { menus++; shownAt = caption; }
  }
}
