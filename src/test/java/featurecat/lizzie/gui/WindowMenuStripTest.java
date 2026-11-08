package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;

class WindowMenuStripTest {
  @Test
  void combinedToolbarKeepsItsControlsAndReservesWrappedHeight() {
    JMenuBar menuBar = new JMenuBar();
    menuBar.add(new JMenu("View"));
    WindowMenuStrip strip = new WindowMenuStrip(menuBar);
    JPanel controls = new JPanel();
    JTextField editor = new JTextField("10", 3);
    controls.add(editor);
    controls.setPreferredSize(new Dimension(240, 30));
    strip.setSize(300, 100);
    strip.setCombinedToolbar(controls);
    int wrappedHeight = strip.getPreferredSize().height;
    strip.doLayout();
    assertTrue(controls.getY() > strip.getComponent(0).getY());
    assertTrue(controls.getY() + controls.getHeight() <= wrappedHeight);

    strip.rebuild();
    assertSame(strip, controls.getParent());
    assertSame(controls, editor.getParent());
    assertEquals("10", editor.getText());
    strip.setSize(800, 100);
    assertTrue(strip.getPreferredSize().height < wrappedHeight);

    strip.setCombinedToolbar(null);
    assertEquals(1, strip.getComponentCount());
    assertFalse(strip.isAncestorOf(editor));
  }

  @Test
  void mirrorsProminentToolbarActionsAndDelegatesClicks() {
    JMenuBar menuBar = new JMenuBar();
    menuBar.add(new JMenu("分析"));
    menuBar.add(Box.createHorizontalStrut(6));
    JButton sourceButton = new JButton("AI 解说");
    sourceButton.setName("aiCommentaryToolbarButton");
    sourceButton.getAccessibleContext().setAccessibleName("AI 解说");
    sourceButton.getAccessibleContext().setAccessibleDescription("生成当前棋局解说");
    AtomicInteger invocationCount = new AtomicInteger();
    sourceButton.addActionListener(event -> invocationCount.incrementAndGet());
    menuBar.add(sourceButton);

    WindowMenuStrip strip = new WindowMenuStrip(menuBar);

    assertEquals(2, strip.getComponentCount());
    assertTrue(strip.getComponent(1) instanceof JButton);
    JButton mirroredButton = (JButton) strip.getComponent(1);
    assertEquals("AI 解说", mirroredButton.getText());
    assertEquals("aiCommentaryToolbarButton", mirroredButton.getName());
    assertEquals("AI 解说", mirroredButton.getAccessibleContext().getAccessibleName());
    assertEquals("生成当前棋局解说", mirroredButton.getAccessibleContext().getAccessibleDescription());

    mirroredButton.doClick(0);

    assertEquals(1, invocationCount.get());
  }

  @Test
  void mirrorsAiCoachVisualRoleAndDynamicSelectionState() {
    JMenuBar menuBar = new JMenuBar();
    JButton sourceButton = new JButton("AI 陪练");
    HumanSlTrainingStyle.stylePrimary(sourceButton);
    AppleStyleSupport.preserveCustomButtonStyle(sourceButton);
    menuBar.add(sourceButton);

    WindowMenuStrip strip = new WindowMenuStrip(menuBar);
    JButton mirroredButton = (JButton) strip.getComponent(0);

    assertEquals(HumanSlTrainingStyle.ACCENT, mirroredButton.getBackground());
    assertEquals(java.awt.Color.WHITE, mirroredButton.getForeground());
    assertNotNull(mirroredButton.getUI());
    assertTrue(mirroredButton.getUI().getClass().getName().contains("RoundedButtonUI"));

    sourceButton.setText("陪练中");
    sourceButton.setSelected(true);

    assertEquals("陪练中", mirroredButton.getText());
    assertTrue(mirroredButton.isSelected());
  }

  @Test
  void mirrorsSourceMenuTextAndEnabledStateWithoutRebuild() {
    JMenuBar menuBar = new JMenuBar();
    JMenu engine = new JMenu("[1] KataGo");
    menuBar.add(engine);

    WindowMenuStrip strip = new WindowMenuStrip(menuBar);
    JButton button = (JButton) strip.getComponent(0);

    engine.setText("[2] ZenGTPX");
    engine.setEnabled(false);

    assertEquals("[2] ZenGTPX", button.getText());
    assertEquals("[2] ZenGTPX", button.getAccessibleContext().getAccessibleName());
    assertFalse(button.isEnabled());
  }

  @Test
  void secondPressOnOpenTopMenuClosesInsteadOfReopening() {
    JMenuBar menuBar = new JMenuBar();
    JMenu view = new JMenu("显示");
    view.add(new JMenuItem("面板"));
    menuBar.add(view);

    WindowMenuStrip strip = new WindowMenuStrip(menuBar);
    Component component = strip.getComponent(0);
    assertTrue(component instanceof JButton);

    JButton button = (JButton) component;
    JPopupMenu popup = view.getPopupMenu();
    popup.setInvoker(button);
    popup.setVisible(true);
    assertTrue(popup.isVisible());

    MouseEvent press =
        new MouseEvent(
            button,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            0,
            4,
            4,
            1,
            false,
            MouseEvent.BUTTON1);
    for (MouseListener listener : button.getMouseListeners()) {
      listener.mousePressed(press);
    }
    button.doClick(0);

    assertFalse(popup.isVisible());
  }
}
