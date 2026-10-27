package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.util.Utils;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

public class SetKataPDA extends JDialog {
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  private JFontTextField txtStaticCur;
  JFontCheckBox chkDymPda;
  JFontCheckBox chkStaticPda;
  JFontCheckBox chkNoPDA;
  JFontCheckBox chkAutoPDA;
  public JLabel curPDA;
  public JFontTextField txtDymCap;

  public SetKataPDA() {
    setResizable(false);
    setTitle(
        resourceBundle.getString("SetKataPDA.title")); // ("KataGo 激进参数PDA设置(仅支持KataGo PDA引擎)");
    setAlwaysOnTop(true);
    JPanel content = new JPanel(new GridBagLayout());
    content.setBorder(new EmptyBorder(6, 6, 6, 6));
    setContentPane(content);

    JFontButton btnCancel =
        new JFontButton(resourceBundle.getString("SetKataPDA.btnCancel")); // ("取消");
    btnCancel.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            setVisible(false);
          }
        });
    addControl(btnCancel, 5, 7, 1);

    JFontButton btnApply =
        new JFontButton(resourceBundle.getString("SetKataPDA.btnApply")); // ("确定");
    btnApply.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            // Analysis batches own runtime PDA; other modes retain their existing participant path.
            Leelaz engine =
                EngineGameDesktop.parametersOwned()
                    ? null
                    : kataGoPdaEngineGameParticipantOrPrimary();
            if (chkDymPda.isSelected()) {
              double dymCap;
              try {
                dymCap = Double.parseDouble(txtDymCap.getText().trim());
              } catch (NumberFormatException es) {
                invalidParameter(txtDymCap);
                return;
              }

              if (!Double.isFinite(dymCap) || dymCap == 0) {
                invalidParameter(txtDymCap);
                return;
              }
              if (chkAutoPDA.isSelected()) Lizzie.config.AutoPDA = "dympdacap " + dymCap;
              if (engine != null) {
                engine.pda = 0;
                engine.sendCommand("pda 0");
                LizzieFrame.menu.txtPDA.setText("0.000");
                engine.sendCommand("dympdacap " + dymCap);
                engine.pdaCap = dymCap;
                engine.isStaticPda = false;
              }

            } else if (chkStaticPda.isSelected()) {
              double staticCur;
              try {
                staticCur = Double.parseDouble(txtStaticCur.getText().trim());

              } catch (NumberFormatException es) {
                invalidParameter(txtStaticCur);
                return;
              }
              if (!Double.isFinite(staticCur)) {
                invalidParameter(txtStaticCur);
                return;
              }
              if (chkAutoPDA.isSelected()) Lizzie.config.AutoPDA = "pda " + staticCur;
              if (engine != null) {
                engine.sendCommand("pda " + staticCur);
                engine.pda = staticCur;
                engine.isStaticPda = true;
                LizzieFrame.menu.txtPDA.setText(String.valueOf(staticCur));
              }
            }
            if (chkNoPDA.isSelected()) {
              Lizzie.config.AutoPDA = "pda 0";
              if (engine != null) {
                engine.sendCommand("pda 0");
                engine.pda = 0;
                if (Lizzie.config.isDoubleEngineMode()) Lizzie.leelaz2.pda = 0;
                LizzieFrame.menu.txtPDA.setText("0");
                engine.isStaticPda = true;
              }
            }

            Lizzie.config.chkDymPDA = chkDymPda.isSelected();
            Lizzie.config.chkStaticPDA = chkStaticPda.isSelected();
            Lizzie.config.chkAutoPDA = chkAutoPDA.isSelected();
            Lizzie.config.dymPDACap = txtDymCap.getText();
            Lizzie.config.staticPDAcur = txtStaticCur.getText();

            Lizzie.config.uiConfig.put("chk-dym-pda", Lizzie.config.chkDymPDA);
            Lizzie.config.uiConfig.put("chk-static-pda", Lizzie.config.chkStaticPDA);
            Lizzie.config.uiConfig.put("chk-auto-pda", Lizzie.config.chkAutoPDA);
            Lizzie.config.uiConfig.put("dym-pda-cap", Lizzie.config.dymPDACap);
            Lizzie.config.uiConfig.put("static-pda-cur", Lizzie.config.staticPDAcur);
            Lizzie.config.uiConfig.put("auto-pda", Lizzie.config.AutoPDA);
            if (engine != null && engine.isPondering()) engine.ponder();
            setVisible(false);
          }
        });
    addControl(btnApply, 4, 7, 1);
    getRootPane().setDefaultButton(btnApply);
    AccessibilitySupport.installEscapeAction(getRootPane(), this, () -> setVisible(false));

    chkDymPda = new JFontCheckBox(resourceBundle.getString("SetKataPDA.chkDymPda")); // ("动态PDA");
    addControl(chkDymPda, 0, 1, 1);

    chkStaticPda =
        new JFontCheckBox(resourceBundle.getString("SetKataPDA.chkStaticPda")); // ("固定PDA");
    addControl(chkStaticPda, 3, 1, 1);

    txtStaticCur = new JFontTextField();
    txtStaticCur.setColumns(6);
    addControl(txtStaticCur, 4, 1, 1);
    AccessibilitySupport.named(txtStaticCur, chkStaticPda.getText(), null);

    txtStaticCur.setEnabled(false);

    JFontLabel lblTip =
        new JFontLabel(
            resourceBundle.getString(
                "SetKataPDA.lblTip")); // "注:动态变化系数修改后,会自动按让子数计算PDA,并设为当前值,不推荐序盘以后修改");
    addControl(lblTip, 0, 2, 6);

    JLabel lblCurPDA = new JLabel(resourceBundle.getString("SetKataPDA.lblCurPDA")); // ("当前PDA:");
    lblCurPDA.setFont(new Font("Song", Font.PLAIN, Math.max(Config.frameFontSize, 14)));
    addControl(lblCurPDA, 0, 0, 1);

    curPDA = new JLabel("0.0");
    curPDA.setFont(new Font("Song", Font.PLAIN, Math.max(Config.frameFontSize, 15)));
    addControl(curPDA, 1, 0, 5);
    curPDA.setText(String.format(Locale.ENGLISH, "%.3f", Lizzie.leelaz.pda));

    chkAutoPDA =
        new JFontCheckBox(
            resourceBundle.getString(
                "SetKataPDA.chkAutoPDA")); // ("自动加载此PDA设置(每次对局开始时,包括人机对局和引擎对局)");
    addControl(chkAutoPDA, 0, 3, 6);

    chkDymPda.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            txtDymCap.setEnabled(true);
            txtStaticCur.setEnabled(false);
          }
        });
    chkStaticPda.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            txtDymCap.setEnabled(false);
            txtStaticCur.setEnabled(true);
          }
        });

    chkNoPDA = new JFontCheckBox(resourceBundle.getString("SetKataPDA.chkNoPDA")); // ("不启用PDA");
    chkNoPDA.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            txtStaticCur.setEnabled(false);
            txtDymCap.setEnabled(false);
          }
        });

    ButtonGroup bg = new ButtonGroup();
    bg.add(chkDymPda);
    bg.add(chkStaticPda);
    bg.add(chkNoPDA);

    addControl(chkNoPDA, 5, 1, 1);

    JFontLabel lblDymRatio =
        new JFontLabel(resourceBundle.getString("SetKataPDA.lblDymRatio")); // ("动态PDA系数:");
    addControl(lblDymRatio, 1, 1, 1);

    txtDymCap = new JFontTextField();
    txtDymCap.setColumns(6);
    addControl(txtDymCap, 2, 1, 1);
    txtDymCap.setEnabled(false);
    AccessibilitySupport.labelFor(lblDymRatio, txtDymCap, chkDymPda.getText());

    JFontLabel lblpdakatagopdapda =
        new JFontLabel(
            resourceBundle.getString(
                "SetKataPDA.lblpdakatagopdapda")); // ("注:PDA为KataGo控制行棋激进/消极程度的参数,分先一般不需开启,让子需要激进(PDA为正)");
    lblpdakatagopdapda.setForeground(Color.RED);
    addControl(lblpdakatagopdapda, 0, 4, 6);

    JFontLabel lblpdapda = new JFontLabel("被让子需要消极(PDA为负,如默认为白视角,则黑方需设置PDA为正才是消极)");
    lblpdapda.setForeground(Color.RED);
    if (Lizzie.config.isChinese) addControl(lblpdapda, 0, 5, 6);

    if (!Lizzie.leelaz.isStaticPda) {
      chkDymPda.setSelected(true);
    } else if (Lizzie.leelaz.pda == 0) {
      chkNoPDA.setSelected(true);
    } else {
      chkStaticPda.setSelected(true);
    }

    chkAutoPDA.setSelected(Lizzie.config.chkAutoPDA);
    txtDymCap.setText(String.valueOf(Lizzie.leelaz.pdaCap));
    txtStaticCur.setText(String.valueOf(Lizzie.leelaz.pda));

    JFontLabel label = new JFontLabel("一般默认为白方视角,也可修改配置文件改为黑方视角,或无视角(轮谁下是谁的视角)");
    label.setForeground(Color.RED);
    if (Lizzie.config.isChinese) addControl(label, 0, 6, 6);
    if (chkDymPda.isSelected()) {
      txtDymCap.setEnabled(true);
      txtStaticCur.setEnabled(false);
    } else if (chkStaticPda.isSelected()) {
      txtDymCap.setEnabled(false);
      txtStaticCur.setEnabled(true);
    } else {
      chkNoPDA.setSelected(true);
      txtDymCap.setEnabled(false);
      txtStaticCur.setEnabled(false);
    }

    pack();
    setLocationRelativeTo(Lizzie.frame);
    try {
      this.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }
  }

  private void addControl(JComponent component, int column, int row, int columns) {
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = column;
    constraints.gridy = row;
    constraints.gridwidth = columns;
    constraints.insets = new Insets(4, 4, 4, 4);
    constraints.anchor =
        component instanceof JFontButton ? GridBagConstraints.LINE_END : GridBagConstraints.LINE_START;
    if (component instanceof JTextField) {
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.weightx = 1.0;
    }
    getContentPane().add(component, constraints);
  }

  private void invalidParameter(JFontTextField field) {
    Utils.showMsg(resourceBundle.getString("SetKataPDA.wrongParameter"), this);
    field.requestFocusInWindow();
    field.selectAll();
  }

  private static Leelaz kataGoPdaEngineGameParticipantOrPrimary() {
    EngineGameSnapshot snapshot = Lizzie.engineGame.current();
    EngineManager manager = Lizzie.engineManager;
    if (!snapshot.playing() || manager == null || manager.engineList == null) {
      return Lizzie.leelaz;
    }
    EngineGameSnapshot.BatchActive active = (EngineGameSnapshot.BatchActive) snapshot;
    int first = manager.resolveEngineGameParticipant(active.batch().first());
    int second = manager.resolveEngineGameParticipant(active.batch().second());
    if (first >= 0
        && first < manager.engineList.size()
        && manager.engineList.get(first).isKataGoPda) {
      return manager.engineList.get(first);
    }
    if (second >= 0 && second < manager.engineList.size()) {
      return manager.engineList.get(second);
    }
    return Lizzie.leelaz;
  }
}
