package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.enginegame.EngineGamePresentation;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.theme.MorandiPalette;
import featurecat.lizzie.util.Utils;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowStateListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import org.json.JSONArray;

public class AnalysisFrame extends JFrame {
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  ;
  TableModel dataModel;
  JScrollPane scrollpane;
  JPanel topPanel;
  JPanel bottomPanel;
  public JTable table;
  Timer timer;
  int sortnum = -1;
  public int selectedorder = -1;
  public int clickOrder = -1;
  int currentRow = -1;
  Font winrateFont;
  Font headFont;
  int index;
  private String oriTitle = "";

  public AnalysisFrame(int engine) {
    index = engine;
    dataModel = getTableModel();
    if (Lizzie.config.isDoubleEngineMode()) {
      if (index == 1) oriTitle = resourceBundle.getString("AnalysisFrame.titleMain");
      else if (index == 2) oriTitle = resourceBundle.getString("AnalysisFrame.titleSub");
    } else oriTitle = resourceBundle.getString("AnalysisFrame.title");
    setTitle(oriTitle);
    setAlwaysOnTop(Lizzie.config.suggestionsalwaysontop || Lizzie.frame.isAlwaysOnTop());
    setTopTitle();
    // JDialog dialog = new JDialog(owner,
    // "单击显示紫圈(小棋盘显示变化),右键落子,双击显示后续变化图,快捷键U显示/关闭");
    addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            Lizzie.frame.closeBestMoves(AnalysisFrame.this);
          }
        });

    // Create and set up the content pane.
    // final AnalysisFrame newContentPane = new AnalysisFrame();
    // newContentPane.setOpaque(true); // content panes must be opaque
    // setContentPane(newContentPane);
    // Display the window.
    // jfs.setSize(521, 285);

    try {
      setIconImage(ImageIO.read(getClass().getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    table = new JTable(dataModel);

    winrateFont = new Font(Lizzie.config.uiFontName, Font.BOLD, Math.max(Config.frameFontSize, 14));
    headFont = new Font(Lizzie.config.uiFontName, Font.PLAIN, Math.max(Config.frameFontSize, 13));

    table.getTableHeader().setFont(headFont);
    table.getTableHeader().setReorderingAllowed(false);
    table.setFont(winrateFont);
    table.setRowHeight(Config.menuHeight);
    TableCellRenderer tcr = new ColorTableCellRenderer();
    table.setDefaultRenderer(Object.class, tcr);

    ((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer())
        .setHorizontalAlignment(JLabel.CENTER);
    scrollpane = new JScrollPane(table);
    topPanel = new JPanel(new BorderLayout());
    topPanel.add(scrollpane);

    bottomPanel =
        new JPanel() {
          @Override
          public void paintComponent(Graphics g) {
            paintBottomPanel(g, bottomPanel.getWidth(), bottomPanel.getHeight());
          }
        };
    bottomPanel.setLayout(null);

    JCheckBox checkList = new JCheckBox(resourceBundle.getString("AnalysisFrame.checkList"));
    bottomPanel.add(checkList);
    checkList.setBounds(370, 0, 50, 18);
    checkList.setFocusable(false);
    checkList.setMargin(new Insets(0, 0, 0, 0));
    checkList.setSelected(Lizzie.config.showBestMovesList);
    checkList.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            Lizzie.config.showBestMovesList = checkList.isSelected();
            Lizzie.frame.toggleBestMoves();
            Lizzie.frame.toggleBestMoves();
            Lizzie.config.uiConfig.put("show-bestmoves-list", Lizzie.config.showBestMovesList);
          }
        });

    JCheckBox checkGraph = new JCheckBox(resourceBundle.getString("AnalysisFrame.checkGraph"));
    bottomPanel.add(checkGraph);
    checkGraph.setBounds(420, 0, 65, 18);
    checkGraph.setFocusable(false);
    checkGraph.setMargin(new Insets(0, 0, 0, 0));
    checkGraph.setSelected(Lizzie.config.showBestMovesGraph);
    checkGraph.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            Lizzie.config.showBestMovesGraph = checkGraph.isSelected();
            Lizzie.frame.toggleBestMoves();
            Lizzie.frame.toggleBestMoves();
            Lizzie.config.uiConfig.put("show-bestmoves-graph", Lizzie.config.showBestMovesGraph);
          }
        });

    JCheckBox checkShowNext =
        new JCheckBox(resourceBundle.getString("AnalysisFrame.checkShowNext"));
    bottomPanel.add(checkShowNext);
    checkShowNext.setBounds(485, 0, 65, 18);
    checkShowNext.setFocusable(false);
    checkShowNext.setMargin(new Insets(0, 0, 0, 0));
    checkShowNext.setSelected(Lizzie.config.anaFrameShowNext);
    checkShowNext.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            Lizzie.config.anaFrameShowNext = checkShowNext.isSelected();
            Lizzie.frame.toggleBestMoves();
            Lizzie.frame.toggleBestMoves();
            Lizzie.config.uiConfig.put("anaframe-show-next", Lizzie.config.anaFrameShowNext);
          }
        });

    JCheckBox checkUseMouseMove =
        new JCheckBox(resourceBundle.getString("AnalysisFrame.checkUseMouseMove"));
    bottomPanel.add(checkUseMouseMove);
    checkUseMouseMove.setBounds(550, 0, 95, 18);
    checkUseMouseMove.setFocusable(false);
    checkUseMouseMove.setMargin(new Insets(0, 0, 0, 0));
    checkUseMouseMove.setSelected(Lizzie.config.anaFrameUseMouseMove);
    checkUseMouseMove.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            Lizzie.config.anaFrameUseMouseMove = checkUseMouseMove.isSelected();
            Lizzie.frame.toggleBestMoves();
            Lizzie.frame.toggleBestMoves();
            Lizzie.config.uiConfig.put(
                "anaframe-use-mousemove", Lizzie.config.anaFrameUseMouseMove);
          }
        });

    //    anaFrameShowNext = uiConfig.optBoolean("anaframe-show-next", true);
    // anaFrameUseMouseMove = uiConfig.optBoolean("anaframe-use-mousemove", true);

    this.getContentPane().setLayout(new BorderLayout());
    this.getContentPane().add(bottomPanel, BorderLayout.SOUTH);
    if (!Lizzie.config.showBestMovesList) {
    } else this.getContentPane().add(topPanel, BorderLayout.NORTH);

    this.addComponentListener(
        new ComponentAdapter() {
          public void componentResized(ComponentEvent e) {
            int height = getContentPane().getHeight();

            if (!Lizzie.config.showBestMovesList) {
              getContentPane().remove(topPanel);
              bottomPanel.setPreferredSize(new Dimension(getWidth(), height));
            } else if (Lizzie.config.showBestMovesGraph) {
              topPanel.setPreferredSize(new Dimension(getWidth(), height / 2));
              bottomPanel.setPreferredSize(new Dimension(getWidth(), height / 2));
            } else {
              topPanel.setPreferredSize(new Dimension(getWidth(), height - 22));
              bottomPanel.setPreferredSize(new Dimension(getWidth(), 22));
            }
          }
        });

    this.addWindowStateListener(
        new WindowStateListener() {
          public void windowStateChanged(WindowEvent state) {
            int height = getHeight() - 40;
            if (!Lizzie.config.showBestMovesList) {
              getContentPane().remove(topPanel);
              bottomPanel.setPreferredSize(new Dimension(getWidth(), height));
            } else if (Lizzie.config.showBestMovesGraph) {
              topPanel.setPreferredSize(new Dimension(getWidth(), height / 2));
              bottomPanel.setPreferredSize(new Dimension(getWidth(), height / 2));
            } else {
              topPanel.setPreferredSize(new Dimension(getWidth(), height - 22));
              bottomPanel.setPreferredSize(new Dimension(getWidth(), 22));
            }
          }
        });

    timer =
        new Timer(
            100,
            new ActionListener() {
              public void actionPerformed(ActionEvent evt) {
                if (Lizzie.config.showBestMovesList) refreshTableData();
                repaint();
                // table.validate();
                // table.updateUI();
              }
            });
    timer.start();

    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setFillsViewportHeight(true);
    table.getColumnModel().getColumn(0).setPreferredWidth(35);
    table.getColumnModel().getColumn(1).setPreferredWidth(20);
    table.getColumnModel().getColumn(2).setPreferredWidth(40);
    table.getColumnModel().getColumn(3).setPreferredWidth(70);
    table.getColumnModel().getColumn(4).setPreferredWidth(35);
    table.getColumnModel().getColumn(5).setPreferredWidth(50);
    table.getColumnModel().getColumn(6).setPreferredWidth(35);
    if (table.getColumnCount() == 9) {
      table.getColumnModel().getColumn(7).setPreferredWidth(70);
      table.getColumnModel().getColumn(8).setPreferredWidth(35);
    }
    boolean persisted = Lizzie.config.persistedUi != null;

    if (persisted) {

      if (table.getColumnCount() == 9) {
        if (Lizzie.config.persistedUi.optJSONArray("suggestions-list-position-9") != null
            && Lizzie.config.persistedUi.optJSONArray("suggestions-list-position-9").length()
                == 13) {
          JSONArray pos = Lizzie.config.persistedUi.getJSONArray("suggestions-list-position-9");
          table.getColumnModel().getColumn(0).setPreferredWidth(pos.getInt(4));
          table.getColumnModel().getColumn(1).setPreferredWidth(pos.getInt(5));
          table.getColumnModel().getColumn(2).setPreferredWidth(pos.getInt(6));
          table.getColumnModel().getColumn(3).setPreferredWidth(pos.getInt(7));
          table.getColumnModel().getColumn(4).setPreferredWidth(pos.getInt(8));
          table.getColumnModel().getColumn(5).setPreferredWidth(pos.getInt(9));
          table.getColumnModel().getColumn(6).setPreferredWidth(pos.getInt(10));
          table.getColumnModel().getColumn(7).setPreferredWidth(pos.getInt(11));
          table.getColumnModel().getColumn(8).setPreferredWidth(pos.getInt(12));
          setBounds(pos.getInt(0), pos.getInt(1), pos.getInt(2), pos.getInt(3));
        } else {
          setBounds(-9, 550, 650, 320);
        }
      } else if (Lizzie.config.persistedUi.optJSONArray("suggestions-list-position-7") != null
          && Lizzie.config.persistedUi.optJSONArray("suggestions-list-position-7").length() == 11) {
        JSONArray pos = Lizzie.config.persistedUi.getJSONArray("suggestions-list-position-7");
        table.getColumnModel().getColumn(0).setPreferredWidth(pos.getInt(4));
        table.getColumnModel().getColumn(1).setPreferredWidth(pos.getInt(5));
        table.getColumnModel().getColumn(2).setPreferredWidth(pos.getInt(6));
        table.getColumnModel().getColumn(3).setPreferredWidth(pos.getInt(7));
        table.getColumnModel().getColumn(4).setPreferredWidth(pos.getInt(8));
        table.getColumnModel().getColumn(5).setPreferredWidth(pos.getInt(9));
        table.getColumnModel().getColumn(6).setPreferredWidth(pos.getInt(10));
        setBounds(pos.getInt(0), pos.getInt(1), pos.getInt(2), pos.getInt(3));
        Dimension screensize = Toolkit.getDefaultToolkit().getScreenSize();
        int widths = (int) screensize.getWidth();
        int heights = (int) screensize.getHeight();
        if (pos.getInt(0) >= widths || pos.getInt(1) >= heights) this.setLocation(0, 0);
      } else {
        setBounds(-9, 480, 650, 320);
      }
    } else {
      setBounds(-9, 480, 650, 320);
    }

    if (!Lizzie.config.showBestMovesList) {
      bottomPanel.setPreferredSize(new Dimension(this.getWidth(), this.getHeight() - 40));
    }
    setVisible(false);
    setVisible(true);
    if (Lizzie.config.isDoubleEngineMode()) {
      if (index == 2) {
        if (Lizzie.frame.analysisFrame != null)
          this.setLocation(
              Lizzie.frame.analysisFrame.getX() + Lizzie.frame.analysisFrame.getWidth(),
              Lizzie.frame.analysisFrame.getY());
      }
    }
    JTableHeader header = table.getTableHeader();

    table.addMouseMotionListener(
        new MouseMotionListener() {
          @Override
          public void mouseDragged(MouseEvent e) {
            // TODO Auto-generated method stub
            mouseMoved(e);
          }

          @Override
          public void mouseMoved(MouseEvent e) {
            // TODO Auto-generated method stub
            if (!Lizzie.config.anaFrameUseMouseMove || clickOrder != -1) return;
            if (!tableContextIsCurrent()) return;
            Point p = e.getPoint();
            int row = table.rowAtPoint(p);
            if (row < 0) {
              if (Lizzie.frame.suggestionclick != LizzieFrame.outOfBoundCoordinate) {
                LizzieFrame.boardRenderer.startNormalBoard();
                LizzieFrame.boardRenderer.clearBranch();
                Lizzie.frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
                Lizzie.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
                selectedorder = -1;
                currentRow = -1;
                clickOrder = -1;
                Lizzie.frame.refresh();
              }
              return;
            }
            if (table.getValueAt(row, 1).toString().startsWith("pass")) return;
            String coordsName =
                Board.maybeConvertOtherCoordsToNormal(table.getValueAt(row, 1).toString());
            int[] coords = Board.convertNameToCoordinates(coordsName);
            if (selectedorder >= 0
                && coords[0] == Lizzie.frame.suggestionclick[0]
                && coords[1] == Lizzie.frame.suggestionclick[1]) {
            } else {
              LizzieFrame.boardRenderer.startNormalBoard();
              selectedorder = row;
              currentRow = row;
              // int[] coords = Board.convertNameToCoordinates(table.getValueAt(row, 1).toString());
              Lizzie.frame.mouseOverCoordinate = coords;
              Lizzie.frame.suggestionclick = coords;
              Lizzie.frame.refresh();
            }
          }
        });

    table.addMouseWheelListener(
        new MouseWheelListener() {
          @Override
          public void mouseWheelMoved(MouseWheelEvent e) {
            // TODO Auto-generated method stub
            if (clickOrder != -1) {
              if (e.getWheelRotation() > 0) {
                Lizzie.frame.doBranch(1);
              } else if (e.getWheelRotation() < 0) {
                Lizzie.frame.doBranch(-1);
              }
              Lizzie.frame.refresh();
            } else {
              scrollpane.dispatchEvent(e);
            }
          }
        });
    table.addMouseListener(
        new MouseAdapter() {
          public void mouseExited(MouseEvent e) {
            if (!Lizzie.config.anaFrameUseMouseMove || clickOrder != -1) return;
            if (Lizzie.frame.suggestionclick != LizzieFrame.outOfBoundCoordinate) {
              LizzieFrame.boardRenderer.startNormalBoard();
              LizzieFrame.boardRenderer.clearBranch();
              Lizzie.frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
              Lizzie.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
              selectedorder = -1;
              currentRow = -1;
              Lizzie.frame.refresh();
            }
          }

          public void mouseClicked(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            int col = table.columnAtPoint(e.getPoint());
            //            if (e.getClickCount() == 2) {
            //              if (row >= 0 && col >= 0) {
            //                try {
            //                  handleTableDoubleClick(row, col);
            //                } catch (Exception ex) {
            //                  ex.printStackTrace();
            //                }
            //              }
            //            } else {

            if (row >= 0 && col >= 0) {
              if (e.getButton() == MouseEvent.BUTTON3) {
                try {
                  handleTableRightClick(row, col);
                } catch (Exception ex) {
                  ex.printStackTrace();
                }
              } else
                try {
                  handleTableClick(row, col);
                } catch (Exception ex) {
                  ex.printStackTrace();
                }
            }
          }
          //    }
        });
    table.addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_U) {
              Lizzie.frame.toggleBestMoves();
            }
            if (e.getKeyCode() == KeyEvent.VK_UP) {
              Lizzie.board.previousMove(true);
            }
            if (e.getKeyCode() == KeyEvent.VK_DOWN) {
              Lizzie.board.nextMove(true);
            }
            if (e.getKeyCode() == KeyEvent.VK_Y) {
              Lizzie.frame.toggleBadMoves();
            }
            if (e.getKeyCode() == KeyEvent.VK_SPACE) {
              Lizzie.frame.togglePonderMannul();
            }

            if (e.getKeyCode() == KeyEvent.VK_Q) {
              Lizzie.frame.toggleAnalysisFrameAlwaysontop();
            }
          }
        });
    addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_U) {
              Lizzie.frame.toggleBestMoves();
            }
            if (e.getKeyCode() == KeyEvent.VK_Y) {
              Lizzie.frame.toggleBadMoves();
            }
            if (e.getKeyCode() == KeyEvent.VK_SPACE) {
              Lizzie.frame.togglePonderMannul();
            }

            if (e.getKeyCode() == KeyEvent.VK_Q) {
              Lizzie.frame.toggleAnalysisFrameAlwaysontop();
            }
          }
        });

    header.addMouseListener(
        new MouseAdapter() {
          public void mouseReleased(MouseEvent e) {
            int pick = header.columnAtPoint(e.getPoint());
            sortnum = pick;
            refreshTableData();
            table.repaint();
          }
        });
  }

  public void setTopTitle() {
    if (this.isAlwaysOnTop())
      setTitle(Lizzie.resourceBundle.getString("Lizzie.alwaysOnTopTitle") + oriTitle);
    else setTitle(oriTitle);
  }

  private void paintBottomPanel(Graphics g0, int width, int height) {
    // TODO Auto-generated method stub
    int minHeight = 22;
    int trueHeight = height - minHeight;
    int totalPlayouts = index == 2
        ? Lizzie.board.getData().getPlayouts2() : Lizzie.board.getData().getPlayouts();
    int maxPlayouts = 0;
    int maxMoveVisits = 0;
    double stable = 0;
    List<MoveData> bestMoves = null;
    if (index == 1) bestMoves = Lizzie.board.getData().bestMoves;
    else if (index == 2) bestMoves = Lizzie.board.getData().bestMoves2;
    if (bestMoves == null || bestMoves.isEmpty()) return;
    int totalAllocation = MoveData.getAllocationVisits(bestMoves);
    Graphics2D g = (Graphics2D) g0;
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    for (MoveData move : bestMoves) {
      if (move.allocationVisits() > maxPlayouts) maxPlayouts = move.allocationVisits();
      if (move.playouts > maxMoveVisits) maxMoveVisits = move.playouts;
    }
    int length = bestMoves.size();
    for (int i = 0; i < 11; i++) {
      if (i < length)
        stable +=
            (double) (maxPlayouts - bestMoves.get(i).allocationVisits())
                * (maxPlayouts - bestMoves.get(i).allocationVisits());
      else stable += (double) maxPlayouts * maxPlayouts;
    }
    stable = totalAllocation > 0 ? stable / 10 / totalAllocation / totalAllocation : 0;
    stable = Math.pow(stable, 0.5) * 100;
    g.setColor(Color.BLACK);
    g.setFont(new Font(Lizzie.config.uiFontName, Font.PLAIN, 13));
    g.drawString(
        resourceBundle.getString("AnalysisFrame.totalVisits")
            + Utils.getPlayoutsString(totalPlayouts)
            + " "
            + resourceBundle.getString("AnalysisFrame.maxVisits")
            + Utils.getPlayoutsString(maxMoveVisits)
            + " "
            + resourceBundle.getString("AnalysisFrame.concentration")
            + String.format(Locale.ENGLISH, "%.2f", stable)
            + "%",
        5,
        15);
    if (Lizzie.config.showBestMovesGraph)
    // 画横向柱状图,前X选点
    {
      g.setStroke(new BasicStroke(1));
      g.drawLine(0, 20, width, 20);
      int nums = trueHeight / 20;
      for (int i = 0; i < nums; i++) {
        g.drawString(String.valueOf(i + 1), 3, minHeight + i * 20 + 15);
        g.setColor(Color.DARK_GRAY);
        if (i < length) {
          double percents = bestMoves.get(i).allocationRatio(maxPlayouts);
          g.fillRect(20, minHeight + i * 20 + 2, (int) ((width - 80) * percents), 16);
          g.drawString(
              String.format(
                      Locale.ENGLISH,
                      "%.2f",
                      bestMoves.get(i).allocationRatio(totalAllocation) * 100)
                  + "%",
              26 + (int) ((width - 80) * percents),
              minHeight + i * 20 + 15);
        }
      }
    }
  }

  class ColorTableCellRenderer extends DefaultTableCellRenderer {
    Object mainValue;
    boolean isPlayoutPercents = false;
    boolean isSelect = false;
    boolean isChanged = false;
    boolean isNextMove;
    double diff = 0;
    double scoreDiff = 0;

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      // if(row%2 == 0){
      setHorizontalAlignment(CENTER);
      if (column == 5) {
        isPlayoutPercents = true;
        mainValue = value;
      } else {
        isPlayoutPercents = false;
      }
      if (table.getValueAt(row, 0).toString().length() > 3) {
        isNextMove = true;
        diff = CandidateTableText.differenceIn(table.getValueAt(row, 3).toString());
        scoreDiff =
            table.getColumnCount() > 7
                ? CandidateTableText.differenceIn(table.getValueAt(row, 7).toString())
                : 0;
      } else isNextMove = false;

      String coordsName =
          Board.maybeConvertOtherCoordsToNormal(table.getValueAt(row, 1).toString());
      int[] coords = new int[] {-2, -2};
      if (!coordsName.startsWith("pas") && coordsName.length() > 1) {
        coords = Board.convertNameToCoordinates(coordsName);
      }
      if (coords[0] == Lizzie.frame.suggestionclick[0]
          && coords[1] == Lizzie.frame.suggestionclick[1]) {
        if (selectedorder >= 0 && selectedorder != row) {
          currentRow = row;
          // selectedorder = -1;
          isChanged = true;
          // setForeground(Color.RED);
        } else {
          isChanged = false;
        }
        isSelect = true;
        // setBackground(new Color(238, 221, 130));
        return super.getTableCellRendererComponent(table, value, false, false, row, column);

      } else {
        isSelect = false;
        isChanged = false;
        return super.getTableCellRendererComponent(table, value, false, false, row, column);
      }
    }

    @Override
    public void paintComponent(Graphics g) {
      if (isPlayoutPercents) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setColor(Color.LIGHT_GRAY);
        g2.fillRect(
            0,
            0,
            (int) (getWidth() * (Double.parseDouble(mainValue.toString()) / 100)),
            getHeight());

      } else {
        if (isSelect) {
          setForeground(Lizzie.config.useMorandiColors ? MorandiPalette.MUDED_TEAL : Color.BLUE);
          setBackground(new Color(0, 0, 0, 35));
        }
        if (isChanged) {
          setForeground(Lizzie.config.useMorandiColors ? MorandiPalette.MUDED_RED : Color.RED);
        }
        if (isNextMove) {
          if (isSelect) {
            if (diff <= Lizzie.config.winLossThreshold5
                || scoreDiff <= Lizzie.config.scoreLossThreshold5)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_BLUNDER_ALPHA
                      : new Color(85, 25, 80, 120));
            else if (diff <= Lizzie.config.winLossThreshold4
                || scoreDiff <= Lizzie.config.scoreLossThreshold4)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_MISTAKE_ALPHA
                      : new Color(208, 16, 19, 100));
            else if (diff <= Lizzie.config.winLossThreshold3
                || scoreDiff <= Lizzie.config.scoreLossThreshold3)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_SLOW_ALPHA
                      : new Color(200, 140, 50, 100));
            else if (diff <= Lizzie.config.winLossThreshold2
                || scoreDiff <= Lizzie.config.scoreLossThreshold2)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_CAUTION_ALPHA
                      : new Color(180, 180, 0, 100));
            else if (diff <= Lizzie.config.winLossThreshold1
                || scoreDiff <= Lizzie.config.scoreLossThreshold1)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_GOOD_ALPHA
                      : new Color(140, 202, 34, 100));
            else
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_BEST_ALPHA
                      : new Color(0, 180, 0, 100));
          } else {
            if (diff <= Lizzie.config.winLossThreshold5
                || scoreDiff <= Lizzie.config.scoreLossThreshold5)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_BLUNDER_LIGHT
                      : new Color(85, 25, 80, 70));
            else if (diff <= Lizzie.config.winLossThreshold4
                || scoreDiff <= Lizzie.config.scoreLossThreshold4)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_MISTAKE_LIGHT
                      : new Color(208, 16, 19, 50));
            else if (diff <= Lizzie.config.winLossThreshold3
                || scoreDiff <= Lizzie.config.scoreLossThreshold3)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_SLOW_LIGHT
                      : new Color(200, 140, 50, 50));
            else if (diff <= Lizzie.config.winLossThreshold2
                || scoreDiff <= Lizzie.config.scoreLossThreshold2)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_CAUTION_LIGHT
                      : new Color(180, 180, 0, 50));
            else if (diff <= Lizzie.config.winLossThreshold1
                || scoreDiff <= Lizzie.config.scoreLossThreshold1)
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_GOOD_LIGHT
                      : new Color(140, 202, 34, 50));
            else
              setBackground(
                  Lizzie.config.useMorandiColors
                      ? MorandiPalette.SUGGESTION_BEST_LIGHT
                      : new Color(0, 180, 0, 60));
          }
        } else if (!isSelect && !isChanged) {
          setForeground(Color.BLACK);
          setBackground(AppleStyleSupport.validFieldBackground());
        }
      }
      super.paintComponent(g);
    }
  }

  private void handleTableClick(int row, int col) {
    if (!tableContextIsCurrent()) return;
    LizzieFrame.boardRenderer.startNormalBoard();
    if (table.getValueAt(row, 1).toString().startsWith("pass")) return;
    String coordsName = Board.maybeConvertOtherCoordsToNormal(table.getValueAt(row, 1).toString());
    int[] coords = Board.convertNameToCoordinates(coordsName);
    if (clickOrder != -1
        && selectedorder >= 0
        && coords[0] == Lizzie.frame.suggestionclick[0]
        && coords[1] == Lizzie.frame.suggestionclick[1]) {
      Lizzie.frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
      Lizzie.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      LizzieFrame.boardRenderer.clearBranch();
      selectedorder = -1;
      clickOrder = -1;
      currentRow = -1;
      Lizzie.frame.refresh();
    } else {

      clickOrder = row;
      selectedorder = row;
      currentRow = row;
      // int[] coords = Board.convertNameToCoordinates(table.getValueAt(row, 1).toString());
      Lizzie.frame.mouseOverCoordinate = coords;
      Lizzie.frame.suggestionclick = coords;
      Lizzie.frame.refresh();
    }
  }

  private void handleTableRightClick(int row, int col) {
    if (!tableContextIsCurrent()) return;
    if (table.getValueAt(row, 1).toString().startsWith("pass")) return;
    if (selectedorder != row) {
      String coordsName =
          Board.maybeConvertOtherCoordsToNormal(table.getValueAt(row, 1).toString());
      int[] coords = Board.convertNameToCoordinates(coordsName);
      Lizzie.frame.suggestionclick = coords;
      Lizzie.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      Lizzie.frame.refresh();
      selectedorder = row;
    } else {
      Lizzie.frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
      Lizzie.frame.refresh();
      selectedorder = -1;
    }
  }

  @Override
  public void dispose() {
    if (timer != null) timer.stop();
    super.dispose();
  }

  private record TableData(
      BoardHistoryList history,
      BoardHistoryNode node,
      BoardHistoryNode next,
      Leelaz engine,
      List<MoveData> rows,
      int columns,
      int totalVisits,
      double scoreOffset) {}

  // Only the EDT refresh paths prepare rows. Cell reads, rendering and clicks share them.
  private TableData tableData;

  private void refreshTableData() {
    BoardHistoryList history = Lizzie.board.getHistory();
    BoardHistoryNode node = history.getCurrentHistoryNode();
    Leelaz engine = index == 1 ? Lizzie.leelaz : Lizzie.leelaz2;
    List<MoveData> source = index == 1 ? node.getData().bestMoves : node.getData().bestMoves2;
    if (index == 1
        && EngineGamePresentation.current().playing()
        && Lizzie.config.showPreviousBestmovesInEngineGame) {
      source = List.of();
      if (node.previous().isPresent() && engine != null) {
        source = engine.getBestMoves();
        if (source.isEmpty()) source = node.previous().get().getData().bestMoves;
      }
    }
    ArrayList<MoveData> rows = new ArrayList<>();
    if (!Lizzie.frame.isInPlayMode()) {
      if (source != null) for (MoveData move : source) rows.add(copyTableMove(move));
      if (Lizzie.config.anaFrameShowNext && node.next().isPresent() && !rows.isEmpty()) {
        addActualMove(rows, node.next().get());
      }
    }
    rows.sort(
        (left, right) -> {
          return switch (sortnum) {
            case 2 -> compareTableValue(right.lcb, left.lcb);
            case 3 -> compareTableValue(right.winrate, left.winrate);
            case 4, 5 -> Integer.compare(right.playouts, left.playouts);
            case 6 -> compareTableValue(right.policy, left.policy);
            case 7 -> compareTableValue(right.scoreMean, left.scoreMean);
            case 8 -> compareTableValue(right.scoreStdev, left.scoreStdev);
            default -> 0;
          };
        });
    int columns =
        engine != null && (engine.isKatago || engine.isSai)
                || (index == 1
                    ? Lizzie.board.isContainsKataData()
                    : Lizzie.board.isContainsKataData2())
            ? 9
            : 7;
    double scoreOffset = 0;
    if (Lizzie.config.showKataGoScoreLeadWithKomi) {
      boolean addKomi = history.isBlacksTurn();
      if (EngineGamePresentation.current().playingGenmove()) addKomi = !addKomi;
      scoreOffset = (addKomi ? 1 : -1) * node.getData().getKomi();
    }
    tableData =
        new TableData(
            history,
            node,
            node.next().orElse(null),
            engine,
            rows,
            columns,
            MoveData.getAllocationVisits(rows),
            scoreOffset);
    if (table != null) table.revalidate();
  }

  private static int compareTableValue(double left, double right) {
    return left < right ? -1 : left > right ? 1 : 0;
  }

  private static MoveData copyTableMove(MoveData source) {
    MoveData copy = new MoveData();
    copy.coordinate = source.coordinate;
    copy.order = source.order;
    copy.playouts = source.playouts;
    copy.edgeVisits = source.edgeVisits;
    copy.isSymmetry = source.isSymmetry;
    copy.winrate = source.winrate;
    copy.lcb = source.lcb;
    copy.policy = source.policy;
    copy.scoreMean = source.scoreMean;
    copy.scoreStdev = source.scoreStdev;
    return copy;
  }

  private void addActualMove(ArrayList<MoveData> rows, BoardHistoryNode next) {
    if (!LizzieFrame.hasRealMoveCoordinates(next.getData())) return;
    int[] coords = next.getData().lastMove.get();
    MoveData best = rows.get(0);
    MoveData match = null;
    for (MoveData move : rows) {
      int[] candidate = Board.convertNameToCoordinates(move.coordinate);
      if (candidate[0] == coords[0] && candidate[1] == coords[1]) {
        match = move;
        break;
      }
    }
    if (match != null && match.order == 0) {
      match.isNextMove = true;
      match.winrate = best.winrate;
      match.bestWinrate = best.winrate;
      match.bestScoreMean = best.scoreMean;
      return;
    }
    BoardData nextData = next.getData();
    boolean analyzed = !(index == 1 ? nextData.bestMoves : nextData.bestMoves2).isEmpty();
    if (match == null && !analyzed) return;
    int nextVisits = index == 1 ? nextData.getPlayouts() : nextData.getPlayouts2();
    MoveData actual = match == null ? new MoveData() : copyTableMove(match);
    // Synthetic actual-move rows retain the existing legacy-visits allocation policy.
    actual.edgeVisits = -1;
    actual.isSymmetry = false;
    if (match == null || (analyzed && nextVisits > match.playouts)) {
      actual.coordinate = Board.convertCoordinatesToName(coords[0], coords[1]);
      actual.playouts = match == null ? 0 : nextVisits;
      actual.winrate = 100.0 - (index == 1 ? nextData.winrate : nextData.winrate2);
      actual.scoreMean = -(index == 1 ? nextData.scoreMean : nextData.scoreMean2);
      actual.scoreStdev = index == 1 ? nextData.scoreStdev : nextData.scoreStdev2;
      actual.lcb = -10000;
      if (match == null) {
        actual.policy = -10000;
        actual.order = -100;
      }
    }
    actual.isNextMove = true;
    actual.bestWinrate = best.winrate;
    actual.bestScoreMean = best.scoreMean;
    rows.add(0, actual);
  }

  private boolean tableContextIsCurrent() {
    if (tableData.history == Lizzie.board.getHistory()
        && tableData.node == Lizzie.board.getHistory().getCurrentHistoryNode()
        && tableData.next == tableData.node.next().orElse(null)
        && tableData.engine == (index == 1 ? Lizzie.leelaz : Lizzie.leelaz2)) return true;
    refreshTableData();
    repaint();
    return false;
  }

  public AbstractTableModel getTableModel() {
    refreshTableData();
    return new AbstractTableModel() {
      public int getColumnCount() {
        return tableData.columns;
      }

      public int getRowCount() {
        return tableData.rows.size();
      }

      public String getColumnName(int column) {
        if (column == 0) return resourceBundle.getString("AnalysisFrame.column1"); // "序号";
        if (column == 1) return resourceBundle.getString("AnalysisFrame.column2"); // "坐标";
        if (column == 2) return resourceBundle.getString("AnalysisFrame.column3"); // "Lcb(%)";
        if (column == 3) return resourceBundle.getString("AnalysisFrame.column4"); // "胜率(%)";
        if (column == 4) return resourceBundle.getString("AnalysisFrame.column5"); // "计算量";
        if (column == 5) return resourceBundle.getString("AnalysisFrame.column6"); // "占比(%)";
        if (column == 6) return resourceBundle.getString("AnalysisFrame.column7"); // "策略网络(%)";
        if (column == 7) return resourceBundle.getString("AnalysisFrame.column8"); // "目差";
        if (column == 8) return resourceBundle.getString("AnalysisFrame.column9"); // "局面复杂度";
        return "";
      }

      public Object getValueAt(int row, int col) {
        TableData prepared = tableData;
        MoveData data = prepared.rows.get(row);
        switch (col) {
          case 0:
            if (data.order == -100)
              return "\n" + resourceBundle.getString("AnalysisFrame.actual") + "\n";
            else if (data.isNextMove)
              return data.order + 1 + "(" + resourceBundle.getString("AnalysisFrame.actual") + ")";
            else return data.order + 1;
          case 1:
            //
            // if(Lizzie.board.convertNameToCoordinates(data.coordinate)[0]==Lizzie.frame.suggestionclick[0]&&Lizzie.board.convertNameToCoordinates(data.coordinate)[1]==Lizzie.frame.suggestionclick[1])
            // {return "*"+data.coordinate;}
            // else
            return Board.maybeConvertNormalCoordsToOther(data.coordinate);
          case 2:
            if (data.isNextMove && data.lcb < -1000) return "--";
            return String.format(Locale.ENGLISH, "%.1f", data.lcb);
          case 3:
            if (data.isNextMove) {
              if (data.order != 0) {
                return CandidateTableText.valueWithDifference(
                    data.winrate, data.winrate - data.bestWinrate);
              }
            }
            return String.format(Locale.ENGLISH, "%.1f", data.winrate);
          case 4:
            if (data.order == -100) return resourceBundle.getString("AnalysisFrame.exclude");
            else return Utils.getPlayoutsString(data.playouts);
          case 5:
            return String.format(
                Locale.ENGLISH, "%.1f", data.allocationRatio(prepared.totalVisits) * 100);
          case 6:
            if (data.isNextMove && data.policy < -1000) return "--";
            return String.format(Locale.ENGLISH, "%.2f", data.policy);
          case 7:
            double score = data.scoreMean + prepared.scoreOffset;
            if (data.isNextMove && data.order != 0) {
              return CandidateTableText.valueWithDifference(
                  score, data.scoreMean - data.bestScoreMean);
            } else return String.format(Locale.ENGLISH, "%.1f", score);
          case 8:
            return String.format(Locale.ENGLISH, "%.1f", data.scoreStdev);
          default:
            return "";
        }
      }
    };
  }
}
