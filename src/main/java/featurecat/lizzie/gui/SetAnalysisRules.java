package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import org.json.JSONObject;

public class SetAnalysisRules extends JDialog {
  // private JDialog thisFrame = this;
  private ResourceBundle resourceBundle = Lizzie.resourceBundle;
  final KataRulesEditor editor;

  public SetAnalysisRules() {
    this.setModal(true);
    // setType(Type.POPUP);
    setResizable(false);
    setTitle(
        resourceBundle.getString("SetAnalysisRules.title")); // ("KataGo规则设置(仅支持KataGo v1.3引擎)");
    setAlwaysOnTop(true);
    setLocationRelativeTo(Lizzie.frame);
    getContentPane().setLayout(null);

    //	    this.addWindowListener(
    //	        new WindowAdapter() {
    //	          public void windowClosing(WindowEvent e) {
    //	            if (Lizzie.leelaz.isPondering()) Lizzie.leelaz.ponder();
    //	            setVisible(false);
    //	          }
    //	        });

    editor = new KataRulesEditor(getContentPane(), resourceBundle);
    int below = editor.bottom();
    if (Lizzie.config.analysisSpecificRules.isEmpty()) {
      editor.applyPreset("tromp-taylor");
    } else {
      editor.apply(new JSONObject(Lizzie.config.analysisSpecificRules));
    }

    JFontButton btnCancel =
        new JFontButton(resourceBundle.getString("SetKataRules.btnCancel")); // ("取消");
    btnCancel.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            setVisible(false);
          }
        });
    btnCancel.setBounds(291, below + 5, 93, 23);
    getContentPane().add(btnCancel);

    JFontButton btnApply =
        new JFontButton(resourceBundle.getString("SetKataRules.btnApply")); // ("确定");
    btnApply.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            Lizzie.config.analysisSpecificRules = editor.toJson().toString();
            Lizzie.config.uiConfig.put(
                "analysis-specific-rules", Lizzie.config.analysisSpecificRules);
            setVisible(false);
          }
        });
    btnApply.setBounds(183, below + 5, 93, 23);
    getContentPane().add(btnApply);
    int width = editor.width();
    int buttonHeight = Math.max(btnApply.getPreferredSize().height, btnCancel.getPreferredSize().height);
    int buttonWidth = Math.max(93, Math.max(btnApply.getPreferredSize().width,
        btnCancel.getPreferredSize().width));
    btnApply.setBounds(width / 2 - buttonWidth - 6, below, buttonWidth, buttonHeight);
    btnCancel.setBounds(width / 2 + 6, below, buttonWidth, buttonHeight);
    getContentPane().setPreferredSize(new Dimension(width, below + buttonHeight + 16));
    pack();
    try {
      this.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
      Dimension screensize = Toolkit.getDefaultToolkit().getScreenSize();
      int x = (int) screensize.getWidth() / 2 - this.getWidth() / 2;
      int y = (int) screensize.getHeight() / 2 - this.getHeight() / 2;
      setLocation(x, y);
    } catch (IOException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }
  }
}
