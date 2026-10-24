package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame.HtmlKit;
import featurecat.lizzie.util.EngineThreadPolicy;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.imageio.ImageIO;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.Document;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.StyleSheet;

public class SetKataEngines extends JDialog {
  private JFontTextField txtPDA;
  private JCheckBox chkEditPDA;
  private JCheckBox chkAutoLoadPDA;
  private JFontLabel lblWRN;
  private JCheckBox chkEditWRN;
  private JCheckBox chkAutoLoadWRN;
  private JFontTextField txtWRN;
  private JFontLabel lblRPT;
  private JCheckBox chkEditRPT;
  private JCheckBox chkWRNInMenu;
  private JCheckBox chkPDAInMenu;
  private JFontTextField txtRPT;
  private JCheckBox chkAutoRPT;
  private JFontLabel lblHint;
  private Leelaz.RuntimeSearchThreads runtimeThreads;
  private final Leelaz openingEngine;
  private final boolean isPdaEngine;
  private final boolean remoteManagedThreads;
  private JCheckBox chkEditThreads;
  private JFontTextField txtThreads;
  private JFontLabel lblThreadReadStatus;
  private JFontButton btnApply;
  private JFontButton btnCancel;
  private boolean threadInputEdited;
  private boolean applyingThreads;
  private int threadQueryGeneration;

  public SetKataEngines() {
    // this.setModal(true);
    // setType(Type.POPUP);
    openingEngine = Lizzie.leelaz;
    runtimeThreads = openingEngine == null ? null : openingEngine.captureRuntimeSearchThreads();
    remoteManagedThreads = EngineThreadPolicy.isRemoteManaged(openingEngine);
    isPdaEngine = openingEngine != null && openingEngine.isKataGoPda;
    setResizable(false);
    setTitle(Lizzie.resourceBundle.getString("SetKataEngines.title")); // ("设置KataGo引擎高级参数");
    setAlwaysOnTop(true);
    setLocationRelativeTo(Lizzie.frame);
    getContentPane().setLayout(null);

    btnCancel =
        new JFontButton(Lizzie.resourceBundle.getString("SetKataEngines.btnCancel")); // ("取消");
    btnCancel.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            setVisible(false);
          }
        });
    btnCancel.setBounds(
        Lizzie.config.isFrameFontSmall() ? 376 : (Lizzie.config.isFrameFontMiddle() ? 440 : 540),
        173,
        93,
        25);
    getContentPane().add(btnCancel);

    btnApply =
        new JFontButton(Lizzie.resourceBundle.getString("SetKataEngines.btnApply")); // ("确定");
    btnApply.addActionListener(event -> confirmSettings());
    btnApply.setBounds(
        Lizzie.config.isFrameFontSmall() ? 278 : (Lizzie.config.isFrameFontMiddle() ? 340 : 440),
        173,
        93,
        25);
    getContentPane().add(btnApply);

    JFontLabel lblPDA =
        new JFontLabel(Lizzie.resourceBundle.getString("SetKataEngines.lblPDA")); // ("激进/保守程度
    // (playoutDoublingAdvantage,默认0.0,有效范围-3.0到3.0):");
    lblPDA.setBounds(10, 24, 700, 25);
    getContentPane().add(lblPDA);

    txtPDA = new JFontTextField();
    txtPDA.setBounds(
        Lizzie.config.isFrameFontSmall() ? 482 : (Lizzie.config.isFrameFontMiddle() ? 591 : 711),
        24,
        133,
        24);
    getContentPane().add(txtPDA);

    bindAnalysisField(txtPDA, KataAnalysisParameter.PDA);

    JFontLabel lblAutoLoad =
        new JFontLabel(Lizzie.resourceBundle.getString("SetKataEngines.lblAutoLoad")); // ("自动加载");
    lblAutoLoad.setBounds(
        Lizzie.config.isFrameFontSmall() ? 626 : (Lizzie.config.isFrameFontMiddle() ? 735 : 855),
        3,
        100,
        20);
    getContentPane().add(lblAutoLoad);

    chkEditPDA = new JCheckBox();
    chkEditPDA.setBounds(
        Lizzie.config.isFrameFontSmall() ? 451 : (Lizzie.config.isFrameFontMiddle() ? 560 : 680),
        25,
        25,
        23);
    getContentPane().add(chkEditPDA);

    chkEditPDA.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            // TODO Auto-generated method stub
            if (chkEditPDA.isSelected()) {
              chkAutoLoadPDA.setEnabled(true);
              txtPDA.setEnabled(true);
            } else {
              chkAutoLoadPDA.setSelected(false);
              chkAutoLoadPDA.setEnabled(false);
              txtPDA.setEnabled(false);
            }
          }
        });

    chkAutoLoadPDA = new JCheckBox();
    chkAutoLoadPDA.setBounds(
        Lizzie.config.isFrameFontSmall() ? 640 : (Lizzie.config.isFrameFontMiddle() ? 756 : 885),
        25,
        25,
        23);
    getContentPane().add(chkAutoLoadPDA);

    lblWRN = new JFontLabel(Lizzie.resourceBundle.getString("SetKataEngines.lblWRN")); // ("分析广度拓展
    // (analysisWideRootNoise,默认0.0,有效范围0.0到2.0):");
    lblWRN.setBounds(10, 56, 700, 25);
    getContentPane().add(lblWRN);

    chkEditWRN = new JCheckBox();
    chkEditWRN.setBounds(
        Lizzie.config.isFrameFontSmall() ? 451 : (Lizzie.config.isFrameFontMiddle() ? 560 : 680),
        57,
        25,
        23);
    getContentPane().add(chkEditWRN);
    chkEditWRN.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            // TODO Auto-generated method stub
            if (chkEditWRN.isSelected()) {
              txtWRN.setEnabled(true);
              chkAutoLoadWRN.setEnabled(true);
            } else {
              txtWRN.setEnabled(false);
              chkAutoLoadWRN.setSelected(false);
              chkAutoLoadWRN.setEnabled(false);
            }
          }
        });

    chkAutoLoadWRN = new JCheckBox();
    chkAutoLoadWRN.setBounds(
        Lizzie.config.isFrameFontSmall() ? 640 : (Lizzie.config.isFrameFontMiddle() ? 756 : 885),
        57,
        25,
        23);
    getContentPane().add(chkAutoLoadWRN);

    txtWRN = new JFontTextField();
    // txtWRN.setColumns(10);
    txtWRN.setBounds(
        Lizzie.config.isFrameFontSmall() ? 482 : (Lizzie.config.isFrameFontMiddle() ? 591 : 711),
        57,
        133,
        24);
    getContentPane().add(txtWRN);

    bindAnalysisField(txtWRN, KataAnalysisParameter.WRN);

    lblRPT =
        new JFontLabel(
            Lizzie.resourceBundle.getString(
                "SetKataEngines.lblRPT")); // ("根策略温度      (rootPolicyTemperature,默认1.0,
    // 有效范围0.01到100.0):");
    lblRPT.setBounds(10, 88, 700, 25);
    //  getContentPane().add(lblRPT);

    chkEditRPT = new JCheckBox();
    chkEditRPT.setBounds(
        Lizzie.config.isFrameFontSmall() ? 451 : (Lizzie.config.isFrameFontMiddle() ? 560 : 680),
        89,
        25,
        23);
    // getContentPane().add(chkEditRPT);

    chkEditRPT.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            // TODO Auto-generated method stub
            if (chkEditRPT.isSelected()) {
              txtRPT.setEnabled(true);
              chkAutoRPT.setEnabled(true);
            } else {
              chkAutoRPT.setSelected(false);
              txtRPT.setEnabled(false);
              chkAutoRPT.setEnabled(false);
            }
          }
        });

    txtRPT = new JFontTextField();
    txtRPT.setColumns(10);
    txtRPT.setBounds(
        Lizzie.config.isFrameFontSmall() ? 482 : (Lizzie.config.isFrameFontMiddle() ? 591 : 711),
        89,
        133,
        24);
    // getContentPane().add(txtRPT);

    Document dt2 = txtRPT.getDocument();
    dt2.addDocumentListener(
        new DocumentListener() {
          public void insertUpdate(DocumentEvent e) {
            double rpt = 0;
            boolean error = false;
            try {
              rpt = Double.parseDouble(txtRPT.getText());
            } catch (NumberFormatException s) {
              // TODO Auto-generated catch block
              error = true;
            }
            if (error || rpt > 100 || rpt < 0.01) {
              txtRPT.setBackground(AppleStyleSupport.errorFieldBackground());
            } else txtRPT.setBackground(AppleStyleSupport.validFieldBackground());
          }

          public void removeUpdate(DocumentEvent e) {
            double rpt = 0;
            boolean error = false;
            try {
              rpt = Double.parseDouble(txtRPT.getText());
            } catch (NumberFormatException s) {
              // TODO Auto-generated catch block
              error = true;
            }
            if (error || rpt > 100 || rpt < 0.01) {
              txtRPT.setBackground(AppleStyleSupport.errorFieldBackground());
            } else txtRPT.setBackground(AppleStyleSupport.validFieldBackground());
          }

          public void changedUpdate(DocumentEvent e) {
            double rpt = 0;
            boolean error = false;
            try {
              rpt = Double.parseDouble(txtRPT.getText());
            } catch (NumberFormatException s) {
              // TODO Auto-generated catch block
              error = true;
            }
            if (error || rpt > 100 || rpt < 0.01) {
              txtRPT.setBackground(AppleStyleSupport.errorFieldBackground());
            } else txtRPT.setBackground(AppleStyleSupport.validFieldBackground());
          }
        });

    chkAutoRPT = new JCheckBox();
    chkAutoRPT.setBounds(
        Lizzie.config.isFrameFontSmall() ? 640 : (Lizzie.config.isFrameFontMiddle() ? 756 : 885),
        89,
        25,
        23);
    // getContentPane().add(chkAutoRPT);

    lblHint =
        new JFontLabel(
            Lizzie.resourceBundle.getString(
                "SetKataEngines.lblHint")); // ("注:需要至少KataGoV1.4引擎,勾选自动加载则在所有KataGo引擎启动后自动加载勾选的选项");
    lblHint.setBounds(10, 123, 1100, 25);
    getContentPane().add(lblHint);

    LinkLabel lblHint2 =
        new LinkLabel(Lizzie.resourceBundle.getString("SetKataEngines.Hint2"), lblHint.getForeground());
    lblHint2.setBounds(7, 145, 559, 28);
    getContentPane().add(lblHint2);

    setSize(983, 342);
    // Lizzie.setFrameSize(this, 770, 230);
    Lizzie.setFrameSize(
        this,
        Lizzie.config.isFrameFontSmall() ? 790 : (Lizzie.config.isFrameFontMiddle() ? 940 : 1120),
        230);
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

    // chkEditRPT.setSelected(Lizzie.config.chkKataEngineRPT);
    // chkAutoRPT.setSelected(Lizzie.config.autoLoadKataEngineRPT);

    JFontLabel lblShowInMenu =
        new JFontLabel(Lizzie.resourceBundle.getString("SetKataEngines.lblShowInMenu"));
    lblShowInMenu.setBounds(
        (Lizzie.config.isChinese ? 0 : Lizzie.config.isFrameFontSmall() ? 5 : 10)
            + (Lizzie.config.isFrameFontSmall()
                ? 688
                : (Lizzie.config.isFrameFontMiddle() ? 807 : 947)),
        3,
        161,
        20);
    getContentPane().add(lblShowInMenu);

    chkWRNInMenu = new JCheckBox("");
    chkWRNInMenu.setBounds(
        Lizzie.config.isFrameFontSmall() ? 718 : (Lizzie.config.isFrameFontMiddle() ? 854 : 1008),
        57,
        25,
        23);
    getContentPane().add(chkWRNInMenu);
    //    chkWRNInMenu.addActionListener(
    //        new ActionListener() {
    //          public void actionPerformed(ActionEvent e) {
    //            if (Lizzie.frame.isPlayingAgainstLeelaz
    //                || (Lizzie.engineManager.isEngineGame
    //                    && Lizzie.engineManager.engineGameInfo.isGenmove)) return;
    //            if (chkWRNInMenu.isSelected()) {
    //              txtWRN.setEnabled(false);
    //              chkAutoLoadWRN.setEnabled(false);
    //              chkEditWRN.setEnabled(false);
    //            } else {
    //              chkEditWRN.setEnabled(true);
    //              if (chkEditWRN.isSelected()) {
    //                txtWRN.setEnabled(true);
    //                chkAutoLoadWRN.setEnabled(true);
    //              }
    //            }
    //          }
    //        });

    chkPDAInMenu = new JCheckBox("");
    chkPDAInMenu.setBounds(
        Lizzie.config.isFrameFontSmall() ? 718 : (Lizzie.config.isFrameFontMiddle() ? 854 : 1008),
        25,
        25,
        23);
    getContentPane().add(chkPDAInMenu);

    //    chkPDAInMenu.addActionListener(
    //        new ActionListener() {
    //          public void actionPerformed(ActionEvent e) {
    //            if (chkPDAInMenu.isSelected()) {
    //              txtPDA.setEnabled(false);
    //              chkAutoLoadPDA.setEnabled(false);
    //              chkEditPDA.setEnabled(false);
    //            } else {
    //
    //              chkEditPDA.setEnabled(true);
    //              if (chkEditPDA.isSelected()) {
    //                txtPDA.setEnabled(true);
    //                chkAutoLoadPDA.setEnabled(true);
    //              }
    //            }
    //          }
    //        });

    chkEditPDA.setSelected(Lizzie.config.chkKataEnginePDA);
    chkAutoLoadPDA.setSelected(Lizzie.config.autoLoadKataEnginePDA);
    //   if (Lizzie.config.chkKataEnginePDA || Lizzie.config.autoLoadKataEnginePDA)
    txtPDA.setText(Lizzie.config.txtKataEnginePDA);

    String threadTooltip =
        Lizzie.resourceBundle.getString(remoteManagedThreads
            ? "SetKataEngines.threadRemoteTooltip" : "SetKataEngines.threadLocalTooltip");
    int threadSettingsX =
        Lizzie.config.isFrameFontSmall() ? 482 : (Lizzie.config.isFrameFontMiddle() ? 591 : 711);
    int threadEnableX = threadSettingsX - 31;
    JFontLabel lblNumSearchThreads =
        new JFontLabel(Lizzie.resourceBundle.getString("SetKataEngines.lblNumSearchThreads"));
    lblNumSearchThreads.setBounds(10, 88, threadEnableX - 20, 25);
    lblNumSearchThreads.setToolTipText(threadTooltip);
    getContentPane().add(lblNumSearchThreads);

    chkEditThreads = new JCheckBox();
    chkEditThreads.setBounds(threadEnableX, 89, 25, 23);
    chkEditThreads.setToolTipText(threadTooltip);
    getContentPane().add(chkEditThreads);

    txtThreads = new JFontTextField();
    txtThreads.setBounds(threadSettingsX, 89, 133, 24);
    txtThreads.setEnabled(false);
    txtThreads.setToolTipText(Lizzie.resourceBundle.getString("SetKataEngines.threadUnknown"));
    getContentPane().add(txtThreads);
    lblThreadReadStatus = new JFontLabel("");
    lblThreadReadStatus.setBounds(threadSettingsX + 141, 89, 150, 24);
    getContentPane().add(lblThreadReadStatus);
    chkEditThreads.addActionListener(event -> {
      if (remoteManagedThreads && !chkEditThreads.isSelected() && runtimeThreads != null
          && runtimeThreads.overrideState() == Leelaz.RuntimeThreadOverrideState.ON) {
        chkEditThreads.setSelected(true);
        showThreadError("SetKataEngines.threadRemoteRestoreUnsupported");
      }
      txtThreads.setEnabled(chkEditThreads.isSelected());
    });
    txtThreads.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent event) { threadInputEdited = true; }
      public void removeUpdate(DocumentEvent event) { threadInputEdited = true; }
      public void changedUpdate(DocumentEvent event) { threadInputEdited = true; }
    });

    if (!Lizzie.config.chkKataEnginePDA) {
      txtPDA.setEnabled(false);
      chkAutoLoadPDA.setEnabled(false);
    }
    if (Lizzie.config.autoLoadKataEnginePDA) {
      txtPDA.setEnabled(true);
      chkAutoLoadPDA.setEnabled(true);
      chkEditPDA.setSelected(true);
    }

    chkEditWRN.setSelected(Lizzie.config.chkKataEngineWRN);
    chkAutoLoadWRN.setSelected(Lizzie.config.autoLoadKataEngineWRN);
    //  if (Lizzie.config.chkKataEngineWRN || Lizzie.config.autoLoadKataEngineWRN)
    txtWRN.setText(Lizzie.config.txtKataEngineWRN);

    if (!Lizzie.config.chkKataEngineWRN) {
      txtWRN.setEnabled(false);
      chkAutoLoadWRN.setEnabled(false);
    }
    if (Lizzie.config.autoLoadKataEngineWRN) {
      txtWRN.setEnabled(true);
      chkAutoLoadWRN.setEnabled(true);
      chkEditWRN.setSelected(true);
    }

    if (Lizzie.config.showWRNInMenu) {
      chkWRNInMenu.setSelected(true);
      // txtWRN.setEnabled(false);
      //   chkAutoLoadWRN.setEnabled(false);
      //   chkEditWRN.setEnabled(false);
    } else {
      chkWRNInMenu.setSelected(false);
    }

    if (Lizzie.config.showPDAInMenu) {
      chkPDAInMenu.setSelected(true);
      //    txtPDA.setEnabled(false);
      //    chkAutoLoadPDA.setEnabled(false);
      //    chkEditPDA.setEnabled(false);
    } else {
      chkPDAInMenu.setSelected(false);
    }


    //   if (Lizzie.config.chkKataEngineRPT || Lizzie.config.autoLoadKataEngineRPT)
    //  txtRPT.setText(Lizzie.config.txtKataEngineRPT);

    //    if (!Lizzie.config.chkKataEngineRPT) {
    //      txtRPT.setEnabled(false);
    //      chkAutoRPT.setEnabled(false);
    //    }
    //    if (Lizzie.config.autoLoadKataEngineRPT) {
    //      txtRPT.setEnabled(true);
    //      chkAutoRPT.setEnabled(true);
    //      chkEditRPT.setSelected(true);
    //    }
    if (Lizzie.frame.isPlayingAgainstLeelaz || Lizzie.engineGame.current().playingGenmove()) {
      txtWRN.setEnabled(false);
      chkEditWRN.setEnabled(false);
    }

    if (isPdaEngine) {
      txtPDA.setVisible(false);
      this.chkAutoLoadPDA.setVisible(false);
      this.chkEditPDA.setVisible(false);
      this.chkPDAInMenu.setVisible(false);

      JFontLabel lblPdaEngineUseMenu =
          new JFontLabel(Lizzie.resourceBundle.getString("SetKataEngines.lblPdaEngineUseMenu"));
      getContentPane().add(lblPdaEngineUseMenu);
      lblPdaEngineUseMenu.setBounds(
          Lizzie.config.isFrameFontSmall() ? 451 : (Lizzie.config.isFrameFontMiddle() ? 560 : 680),
          25,
          400,
          23);
    }
  }

  private void confirmSettings() {
    if (applyingThreads) return;
    AnalysisSettings settings = captureAnalysisSettings();
    if (settings == null) return;
    if (runtimeThreads != null && !runtimeThreads.isCurrent()) {
      showThreadError("SetKataEngines.threadStale");
      return;
    }
    Leelaz.RuntimeThreadOverrideState state = runtimeThreads == null
        ? Leelaz.RuntimeThreadOverrideState.OFF : runtimeThreads.overrideState();
    if (!chkEditThreads.isSelected()) {
      if (state == Leelaz.RuntimeThreadOverrideState.ON) {
        if (remoteManagedThreads) {
          chkEditThreads.setSelected(true);
          txtThreads.setEnabled(true);
          showThreadError("SetKataEngines.threadRemoteRestoreUnsupported");
          return;
        }
        restoreSource(settings);
      } else {
        applyExistingSettings(openingEngine, settings, true);
      }
      return;
    }
    int threads;
    try {
      threads = Integer.parseInt(txtThreads.getText().trim());
      if (threads < 1 || threads > 1024) throw new NumberFormatException();
    } catch (NumberFormatException failure) {
      showThreadError("SetKataEngines.threadInvalid");
      txtThreads.requestFocusInWindow();
      return;
    }
    if (runtimeThreads == null) {
      showThreadError("SetKataEngines.threadStale");
      return;
    }
    if (state == Leelaz.RuntimeThreadOverrideState.ON && !threadInputEdited
        && runtimeThreads.lastConfirmedValue() != null
        && threads == runtimeThreads.lastConfirmedValue()) {
      applyExistingSettings(openingEngine, settings, true);
      return;
    }
    beginThreadOperation();
    int generation = threadQueryGeneration;
    runtimeThreads
        .apply(threads)
        .whenComplete(
            (value, failure) ->
                SwingUtilities.invokeLater(
                    () -> {
                      endThreadOperation();
                      if (generation != threadQueryGeneration || !isVisible()) return;
                      if (failure != null || !runtimeThreads.isCurrent()) {
                        showThreadError(
                            runtimeThreads.isCurrent()
                                ? "SetKataEngines.threadFailed"
                                : "SetKataEngines.threadStale",
                            failure);
                      } else {
                        applyExistingSettings(openingEngine, settings, true);
                      }
                    }));
  }

  private int previousCloseOperation;

  private void beginThreadOperation() {
    previousCloseOperation = getDefaultCloseOperation();
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    applyingThreads = true;
    btnApply.setEnabled(false);
    btnCancel.setEnabled(false);
  }

  private void endThreadOperation() {
    applyingThreads = false;
    setDefaultCloseOperation(previousCloseOperation);
    btnApply.setEnabled(true);
    btnCancel.setEnabled(true);
  }

  private void restoreSource(AnalysisSettings settings) {
    Leelaz.RuntimeThreadSourceRestore restore;
    try {
      restore = runtimeThreads.prepareRestore();
    } catch (RuntimeException failure) {
      if (runtimeThreads.overrideState() == Leelaz.RuntimeThreadOverrideState.ON) {
        chkEditThreads.setSelected(true);
        txtThreads.setEnabled(true);
      }
      showThreadError("SetKataEngines.threadRestoreFailed", failure);
      return;
    }
    String source = Lizzie.resourceBundle.getString(
        "SetKataEngines.threadSource" + restore.source().name());
    String recommendation = restore.recommendedThreads() == null
        ? Lizzie.resourceBundle.getString("SetKataEngines.threadSourceUnknown")
        : restore.recommendedThreads().toString();
    String warning = MessageFormat.format(
        Lizzie.resourceBundle.getString("SetKataEngines.threadRestoreConfirm"),
        source, recommendation);
    if (JOptionPane.showConfirmDialog(this, warning,
        Lizzie.resourceBundle.getString("SetKataEngines.title"), JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
      chkEditThreads.setSelected(true);
      txtThreads.setEnabled(true);
      return;
    }

    beginThreadOperation();
    int generation = threadQueryGeneration;
    try {
      restore
          .execute(
              successor -> {
                CompletableFuture<Void> applied = new CompletableFuture<>();
                SwingUtilities.invokeLater(
                    () -> {
                      try {
                        if (generation != threadQueryGeneration
                            || !isVisible()
                            || Lizzie.leelaz != openingEngine
                            || !successor.isCurrent()) {
                          throw new IllegalStateException(
                              Lizzie.resourceBundle.getString("SetKataEngines.threadStale"));
                        }
                        applyExistingSettings(openingEngine, settings, false);
                        runtimeThreads = successor;
                        applied.complete(null);
                      } catch (Throwable failure) {
                        applied.completeExceptionally(failure);
                      }
                    });
                return applied;
              })
          .whenComplete(
              (successor, failure) ->
                  SwingUtilities.invokeLater(
                      () -> {
                        endThreadOperation();
                        if (failure == null) {
                          if (isVisible()) setVisible(false);
                        } else {
                          updateThreadTooltip();
                          if (runtimeThreads != null
                              && runtimeThreads.overrideState()
                                  == Leelaz.RuntimeThreadOverrideState.ON) {
                            chkEditThreads.setSelected(true);
                            txtThreads.setEnabled(true);
                          }
                          if (isVisible())
                            showThreadError("SetKataEngines.threadRestoreFailed", failure);
                        }
                      }));
    } catch (RuntimeException failure) {
      endThreadOperation();
      if (runtimeThreads.overrideState() == Leelaz.RuntimeThreadOverrideState.ON) {
        chkEditThreads.setSelected(true);
        txtThreads.setEnabled(true);
      }
      updateThreadTooltip();
      showThreadError("SetKataEngines.threadRestoreFailed", failure);
    }
  }

  private void updateThreadTooltip() {
    String key = runtimeThreads != null
        && runtimeThreads.overrideState() == Leelaz.RuntimeThreadOverrideState.UNKNOWN
        ? "SetKataEngines.threadProvenanceUnknown"
        : remoteManagedThreads ? "SetKataEngines.threadRemoteTooltip"
            : "SetKataEngines.threadLocalTooltip";
    chkEditThreads.setToolTipText(Lizzie.resourceBundle.getString(key));
    txtThreads.setToolTipText(Lizzie.resourceBundle.getString(key));
  }

  private static void bindAnalysisField(JFontTextField field, KataAnalysisParameter parameter) {
    field
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent event) {
                refresh();
              }

              public void removeUpdate(DocumentEvent event) {
                refresh();
              }

              public void changedUpdate(DocumentEvent event) {
                refresh();
              }

              private void refresh() {
                field.setBackground(
                    parameter.parse(field.getText()).isPresent()
                        ? AppleStyleSupport.validFieldBackground()
                        : AppleStyleSupport.errorFieldBackground());
              }
            });
  }

  private record ParameterSetting(boolean enabled, boolean autoLoad, String value) {}

  // One validated submission owns both the immediate and asynchronous apply paths.
  // A null PDA setting means that this engine manages PDA outside this dialog.
  private record AnalysisSettings(
      boolean showWrn, boolean showPda, ParameterSetting wrn, ParameterSetting pda) {}

  private AnalysisSettings captureAnalysisSettings() {
    ParameterSetting wrn =
        captureParameter(
            KataAnalysisParameter.WRN,
            txtWRN,
            chkEditWRN,
            chkAutoLoadWRN,
            Lizzie.config.txtKataEngineWRN);
    if (wrn == null) return null;
    ParameterSetting pda = null;
    if (!isPdaEngine) {
      pda =
          captureParameter(
              KataAnalysisParameter.PDA,
              txtPDA,
              chkEditPDA,
              chkAutoLoadPDA,
              Lizzie.config.txtKataEnginePDA);
      if (pda == null) return null;
    }
    return new AnalysisSettings(chkWRNInMenu.isSelected(), chkPDAInMenu.isSelected(), wrn, pda);
  }

  private ParameterSetting captureParameter(
      KataAnalysisParameter parameter,
      JFontTextField field,
      JCheckBox enabled,
      JCheckBox autoLoad,
      String appliedValue) {
    // Disabled values are not submitted; preserve the remembered value, including an unset default.
    if (!enabled.isSelected()) return new ParameterSetting(false, false, appliedValue);
    OptionalDouble parsed = parameter.parse(field.getText());
    if (parsed.isEmpty()) {
      field.setBackground(AppleStyleSupport.errorFieldBackground());
      field.setEnabled(true);
      field.requestFocusInWindow();
      return null;
    }
    return new ParameterSetting(true, autoLoad.isSelected(), String.valueOf(parsed.getAsDouble()));
  }

  private void applyExistingSettings(Leelaz engine, AnalysisSettings settings, boolean resume) {
    Lizzie.config.showWRNInMenu = settings.showWrn();
    Lizzie.config.uiConfig.put("show-wrn-in-menu", settings.showWrn());

    ParameterSetting pda = settings.pda();
    if (pda != null) {
      Lizzie.config.showPDAInMenu = settings.showPda();
      Lizzie.config.uiConfig.put("show-pda-in-menu", settings.showPda());
      Lizzie.config.chkKataEnginePDA = pda.enabled();
      Lizzie.config.autoLoadKataEnginePDA = pda.autoLoad();
      Lizzie.config.txtKataEnginePDA = pda.value();
      engine.setPda(pda.enabled() ? pda.value() : "0");
      if (pda.autoLoad()) {
        Lizzie.config.autoLoadTxtKataEnginePDA = pda.value();
        Lizzie.config.uiConfig.put("auto-load-txt-kata-engine-pda", pda.value());
      }
      LizzieFrame.menu.setUseGfPda(pda.enabled(), pda.value());
      Lizzie.config.uiConfig.put("chk-kata-engine-pda", pda.enabled());
      Lizzie.config.uiConfig.put("autoload-kata-engine-pda", pda.autoLoad());
      Lizzie.config.uiConfig.put("txt-kata-engine-pda", pda.value());
    }

    ParameterSetting wrn = settings.wrn();
    Lizzie.config.chkKataEngineWRN = wrn.enabled();
    Lizzie.config.autoLoadKataEngineWRN = wrn.autoLoad();
    Lizzie.config.txtKataEngineWRN = wrn.value();
    engine.sendCommand(
        "kata-set-param analysisWideRootNoise " + (wrn.enabled() ? wrn.value() : "0"));
    if (wrn.enabled()) Lizzie.board.clearBestMovesAfter(Lizzie.board.getHistory().getStart());
    if (wrn.autoLoad()) {
      Lizzie.config.autoLoadTxtKataEngineWRN = wrn.value();
      Lizzie.config.uiConfig.put("auto-load-txt-kata-engine-wrn", wrn.value());
    }
    LizzieFrame.menu.setUseWrn(wrn.enabled(), wrn.value());
    Lizzie.config.uiConfig.put("chk-kata-engine-wrn", wrn.enabled());
    Lizzie.config.uiConfig.put("autoload-kata-engine-wrn", wrn.autoLoad());
    Lizzie.config.uiConfig.put("txt-kata-engine-wrn", wrn.value());
    if (resume) setVisible(false);
    LizzieFrame.menu.updateMenuStatusForEngine();
    if (resume && engine.isPondering()) engine.ponder();
  }

  @Override
  public void setVisible(boolean visible) {
    if (!visible) threadQueryGeneration++;
    boolean opening = visible && !isVisible();
    if (opening && chkEditThreads != null) {
      threadInputEdited = false;
      boolean active = runtimeThreads != null
          && runtimeThreads.overrideState() == Leelaz.RuntimeThreadOverrideState.ON;
      chkEditThreads.setSelected(active);
      txtThreads.setEnabled(active);
      Integer confirmed = runtimeThreads == null ? null : runtimeThreads.lastConfirmedValue();
      txtThreads.setText(confirmed == null ? "" : confirmed.toString());
      lblThreadReadStatus.setText(runtimeThreads == null ? ""
          : Lizzie.resourceBundle.getString(confirmed == null
              ? "SetKataEngines.threadReading" : "SetKataEngines.threadRefreshing"));
      lblThreadReadStatus.setToolTipText(null);
      updateThreadTooltip();
    }
    super.setVisible(visible);
    if (opening) queryThreads();
  }

  @Override
  public void dispose() {
    threadQueryGeneration++;
    super.dispose();
  }

  private void queryThreads() {
    if (runtimeThreads == null) return;
    int generation = ++threadQueryGeneration;
    threadInputEdited = false;
    runtimeThreads.query().whenComplete((value, failure) ->
        SwingUtilities.invokeLater(() -> {
          if (generation != threadQueryGeneration || !isVisible()) return;
          if (!runtimeThreads.isCurrent()) {
            if (!threadInputEdited) txtThreads.setText("");
            lblThreadReadStatus.setText(Lizzie.resourceBundle.getString("SetKataEngines.threadReadFailed"));
            lblThreadReadStatus.setToolTipText(Lizzie.resourceBundle.getString("SetKataEngines.threadStale"));
            updateThreadTooltip();
            return;
          }
          lblThreadReadStatus.setText(failure == null ? ""
              : Lizzie.resourceBundle.getString("SetKataEngines.threadReadFailed"));
          lblThreadReadStatus.setToolTipText(failure == null ? null
              : Lizzie.resourceBundle.getString("SetKataEngines.threadReadFailedHint"));
          if (failure == null && !threadInputEdited) {
            txtThreads.setText(value.toString());
            threadInputEdited = false;
          }
          updateThreadTooltip();
        }));
  }

  private void showThreadError(String key) {
    showThreadError(key, null);
  }

  private void showThreadError(String key, Throwable failure) {
    String message = Lizzie.resourceBundle.getString(key);
    if (failure != null) {
      Throwable cause = failure instanceof CompletionException && failure.getCause() != null
          ? failure.getCause() : failure;
      String detail = cause.getMessage();
      if (detail != null && !detail.isBlank()) {
        detail = detail.replaceAll("[\\p{Cntrl}]", " ").trim();
        message += "\n" + detail.substring(0, Math.min(detail.length(), 240));
      }
    }
    JOptionPane.showMessageDialog(this, message,
        Lizzie.resourceBundle.getString("SetKataEngines.title"), JOptionPane.WARNING_MESSAGE);
  }

  private class LinkLabel extends JTextPane {
    public LinkLabel(String text, Color foreground) {
      super();

      HTMLDocument htmlDoc;
      HtmlKit htmlKit;
      StyleSheet htmlStyle;

      htmlKit = new HtmlKit();
      htmlDoc = (HTMLDocument) htmlKit.createDefaultDocument();
      htmlStyle = htmlKit.getStyleSheet();
      String style =
          "body {color:#"
              + String.format(
                  "%02x%02x%02x", foreground.getRed(), foreground.getGreen(), foreground.getBlue())
              + "; font-family:"
              + Lizzie.config.fontName
              + ", Consolas, Menlo, Monaco, 'Ubuntu Mono', monospace;"
              + ("font-size:" + Config.frameFontSize)
              + "}";
      htmlStyle.addRule(style);
      setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
      setForeground(foreground);
      setEditorKit(htmlKit);
      setDocument(htmlDoc);
      boolean lightText = foreground.getRed() + foreground.getGreen() + foreground.getBlue() > 384;
      setText(text.replace("color=\"blue\"", lightText ? "color=\"#8ab4f8\"" : "color=\"blue\""));
      setEditable(false);
      setOpaque(false);
      setBackground(new Color(0, 0, 0, 0));
      putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
      addHyperlinkListener(
          new HyperlinkListener() {
            public void hyperlinkUpdate(HyperlinkEvent e) {
              if (HyperlinkEvent.EventType.ACTIVATED.equals(e.getEventType())) {
                if (Desktop.isDesktopSupported()) {
                  try {
                    Desktop.getDesktop().browse(e.getURL().toURI());
                  } catch (Exception ex) {
                  }
                }
              }
            }
          });
    }
  }
}
