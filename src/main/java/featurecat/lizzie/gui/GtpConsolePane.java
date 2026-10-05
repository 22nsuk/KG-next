package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.EngineGamePresentation;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.logging.ObservationText;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.DocType;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.json.JSONArray;

public class GtpConsolePane extends JDialog {
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  private static final Pattern CONSOLE_VERTEX =
      Pattern.compile("([A-HJ-Z]+)([0-9]+)", Pattern.CASE_INSENSITIVE);

  // private int scrollLength = 0;
  private JScrollPane scrollPane;
  public JIMSendTextPane console;
  public final JFontTextField txtCommand = new JFontTextField();
  public JButton send;
  private JFontLabel lblCommand = new JFontLabel();
  private JPanel pnlCommand = new JPanel();
  private GtpConsoleUpdatePump updatePump;
  // private int checkCount = 0;
  private Font gtpFont;
  private GtpConsoleBuffer consoleBuffer;
  private int max_length = 30000;

  /** Creates a Gtp Console Window */
  public GtpConsolePane(Window owner) {
    super(owner);
    setTitle(resourceBundle.getString("GtpConsolePane.title"));
    try {
      gtpFont =
          Font.createFont(
              Font.TRUETYPE_FONT,
              Thread.currentThread()
                  .getContextClassLoader()
                  .getResourceAsStream("fonts/SourceCodePro-Regular.ttf"));

      gtpFont = gtpFont.deriveFont(Font.PLAIN, Config.frameFontSize);
    } catch (IOException | FontFormatException e) {
      e.printStackTrace();
      gtpFont = new Font(Font.MONOSPACED, Font.PLAIN, Config.frameFontSize);
    }
    consoleBuffer = new GtpConsoleBuffer();
    boolean persisted =
        Lizzie.config.persistedUi != null
            && Lizzie.config.persistedUi.optJSONArray("gtp-console-position") != null
            && Lizzie.config.persistedUi.optJSONArray("gtp-console-position").length() == 4;
    if (persisted) {
      JSONArray pos = Lizzie.config.persistedUi.getJSONArray("gtp-console-position");
      this.setBounds(pos.getInt(0), pos.getInt(1), pos.getInt(2), pos.getInt(3));
      Dimension screensize = Toolkit.getDefaultToolkit().getScreenSize();
      int width = (int) screensize.getWidth();
      int height = (int) screensize.getHeight();
      if (pos.getInt(0) >= width || pos.getInt(1) >= height) this.setLocation(0, 0);
    } else {
      Dimension screensize = Toolkit.getDefaultToolkit().getScreenSize();
      setBounds((int) screensize.getWidth() - 400, (int) screensize.getHeight() - 700, 400, 650);
    }
    console = new JIMSendTextPane(false);
    console.setBorder(BorderFactory.createEmptyBorder());
    console.setEditable(false);
    console.setBackground(Color.BLACK);
    console.setForeground(Color.LIGHT_GRAY);
    console.setFont(gtpFont);
    scrollPane = new JScrollPane();
    scrollPane.setBorder(BorderFactory.createEmptyBorder());
    txtCommand.setBackground(Color.DARK_GRAY);
    txtCommand.setForeground(Color.WHITE);
    lblCommand.setFont(new Font(Config.sysDefaultFontName, Font.BOLD, 11));
    lblCommand.setOpaque(true);
    lblCommand.setBackground(Color.DARK_GRAY);
    lblCommand.setForeground(Color.WHITE);
    lblCommand.setText("GTP>");
    AccessibilitySupport.labelFor(
        lblCommand,
        txtCommand,
        resourceBundle.getString("GtpConsolePane.title")
            + " - "
            + resourceBundle.getString("GtpConsolePane.send"));
    pnlCommand.setLayout(new BorderLayout(0, 0));
    pnlCommand.add(lblCommand, BorderLayout.WEST);

    pnlCommand.add(txtCommand);

    getContentPane()
        .addKeyListener(
            new KeyAdapter() {
              public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_E) {
                  Lizzie.frame.toggleGtpConsole();
                }
              }
            });
    console.addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_E) {
              Lizzie.frame.toggleGtpConsole();
            }
          }
        });

    JPanel buttonPane = new JPanel();
    JButton clear = new JButton(resourceBundle.getString("GtpConsolePane.clear"));
    clear.setFocusable(false);
    clear.setFocusable(false);
    clear.setMargin(new Insets(0, 5, 0, 5));
    clear.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            console.setText("");
          }
        });

    JButton commands = new JButton(resourceBundle.getString("GtpConsolePane.commands"));
    commands.setFocusable(false);
    commands.setFocusable(false);
    commands.setMargin(new Insets(0, 0, 0, 0));
    commands.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            openCommands();
          }
        });
    send = new JButton(resourceBundle.getString("GtpConsolePane.send"));
    send.addActionListener(e -> postCommand(e));
    buttonPane.setLayout(new GridLayout(1, 3, 0, 0));
    send.setMargin(new Insets(0, 5, 0, 5));
    send.setFocusable(false);
    buttonPane.add(send);
    buttonPane.add(commands);
    buttonPane.add(clear);
    pnlCommand.add(buttonPane, BorderLayout.EAST);
    getContentPane().add(scrollPane, BorderLayout.CENTER);
    getContentPane().add(pnlCommand, BorderLayout.SOUTH);
    scrollPane.setViewportView(console);
    getRootPane().setBorder(BorderFactory.createEmptyBorder());
    txtCommand.addActionListener(e -> postCommand(e));
    AccessibilitySupport.applyToTree(this);
    AccessibilitySupport.installEscapeAction(
        getRootPane(), this, () -> Lizzie.frame.toggleGtpConsole());
    this.addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            Lizzie.frame.toggleGtpConsole();
          }
        });
    updatePump =
        new GtpConsoleUpdatePump(
            consoleBuffer,
            doc -> {
              addDocs(doc);
              checkConsole();
            },
            this::refreshLoadingComment);
    updatePump.start();
  }

  @Override
  public void dispose() {
    if (updatePump != null) updatePump.close();
    super.dispose();
  }

  public void openCommands() {
    // TODO Auto-generated method stub
    if (Lizzie.leelaz.commandLists.isEmpty()) {
      Utils.showMsg(resourceBundle.getString("GtpConsolePane.noCommands"));
      return;
    }
    FastCommands fastCommands = new FastCommands(this);
    fastCommands.setVisible(true);
  }

  private void addDocs(DocType doc) {
    SimpleAttributeSet attrSet = new SimpleAttributeSet();
    StyleConstants.setForeground(attrSet, doc.contentColor);
    if (doc.isCommand) {
      StyleConstants.setFontFamily(attrSet, Lizzie.config.uiFontName);
    }
    StyleConstants.setFontSize(attrSet, doc.fontSize);
    String insertContent =
        ObservationText.boundedUtf8(
            doc.content, max_length / 10, ObservationText.RAW_EVENT_MAX_LINES);
    insert(insertContent, attrSet);
    console.setCaretPosition(console.getDocument().getLength());
  }

  private void setDocs(String str, Color col, boolean isCommand, int fontSize) {
    DocType doc = new DocType();
    doc.content = str;
    doc.contentColor = col;
    doc.isCommand = isCommand;
    doc.fontSize = fontSize;
    consoleBuffer().offer(doc);
  }

  private void checkConsole() {
    Document doc = console.getDocument();
    int length = doc.getLength();
    try {
      if (length > max_length) {
        doc.remove(0, length - max_length / 2);
      }
    } catch (BadLocationException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private void insert(String str, AttributeSet attrSet) {
    Document doc = console.getDocument();
    try {
      doc.insertString(doc.getLength(), str, attrSet);
    } catch (BadLocationException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private void refreshLoadingComment() {
    EngineGameSnapshot snapshot = EngineGamePresentation.current();
    Leelaz blackEngine = EngineGamePresentation.blackEngine(snapshot);
    Leelaz whiteEngine = EngineGamePresentation.whiteEngine(snapshot);
    if (Lizzie.frame != null
        && ((Lizzie.leelaz != null && !Lizzie.leelaz.isLoaded())
            || (snapshot.starting()
                && (whiteEngine == null
                    || !whiteEngine.isLoaded()
                    || blackEngine == null
                    || !blackEngine.isLoaded())))) {
      Lizzie.frame.setCommentEditable(false);
      Lizzie.frame.appendComment();
    }
  }

  private GtpConsoleBuffer consoleBuffer() {
    GtpConsoleBuffer buffer = consoleBuffer;
    if (buffer == null) {
      buffer = new GtpConsoleBuffer();
      consoleBuffer = buffer;
    }
    return buffer;
  }

  //  public void checkConsole() {
  //    if (console.getText().length() > 5000) {
  //      console.setText(
  //          console
  //              .getText()
  //              .substring(console.getText().length()/2, console.getText().length()));
  //      //console.setCaretPosition(console.getDocument().getLength());
  //    }
  //  }

  public void addCommandForEngineGame(
      String command, int commandNumber, String engineName, boolean isBlack) {
    if (command == null || command.trim().length() == 0) {
      return;
    }
    setDocs(
        (isBlack ? "○" : "●") + engineName + "> " + command + "\n",
        Color.WHITE,
        true,
        Config.frameFontSize);
  }

  public void addCommand(String command, int commandNumber, String engineName) {
    if (command == null || command.trim().length() == 0) {
      return;
    }
    setDocs(engineName + "> " + command + "\n", Color.WHITE, true, Config.frameFontSize);
  }


  public void addLineReadBoard(String line) {
    if (line == null || line.trim().length() == 0) {
      return;
    }
    setDocs(" " + line, Color.ORANGE, false, Config.frameFontSize);
  }

  public void addLine(String line) {
    if (line == null || line.trim().length() == 0) {
      return;
    }
    setDocs(" " + line, Color.GREEN, false, Config.frameFontSize);
  }

  public void addLineEstimate(String line) {
    if (line == null || line.trim().length() == 0) {
      return;
    }
    setDocs(" " + line, Color.GRAY, false, Config.frameFontSize);
  }

  private void postCommand(ActionEvent e) {
    if (txtCommand.getText() == null || txtCommand.getText().trim().isEmpty()) {
      return;
    }
    if (Lizzie.leelaz != null && !Lizzie.leelaz.hasGtpCapability()) {
      addLine(resourceBundle.getString("Benchmark.gtpUnavailable") + "\n");
      return;
    }
    String command = txtCommand.getText().trim();
    String commandToLower = command.toLowerCase(Locale.ROOT);
    String[] moveParams = command.split("\\s+");
    String commandName = moveParams[0].toLowerCase(Locale.ROOT);
    txtCommand.setText("");

    if (EngineGamePresentation.current().playing()) {
      this.setDocs(
          resourceBundle.getString("GtpConsolePane.isEngineGame") + "\r\n",
          new Color(255, 255, 0),
          false,
          Config.frameFontSize);
      return;
    }

    if (Lizzie.leelaz != null) {
      if ("play".equals(commandName)) {
        if (!playConsoleMove(moveParams)) wrongMoveParameters();
      } else if ("genmove".equals(commandName)) {
        Stone color = moveParams.length == 2 ? consoleColor(moveParams[1]) : Stone.EMPTY;
        if (color == Stone.EMPTY) {
          wrongMoveParameters();
        } else if (!Lizzie.leelaz.isThinking) {
          BoardData position = Lizzie.board.getData();
          boolean previousBlackToPlay = position.blackToPlay;
          boolean accepted = false;
          position.blackToPlay = color == Stone.BLACK;
          try {
            accepted = Lizzie.leelaz.genmove(color == Stone.BLACK ? "b" : "w", true);
          } finally {
            if (!accepted) position.blackToPlay = previousBlackToPlay;
          }
        }
      } else if ("showboard".equals(commandToLower)) {
        if (Lizzie.leelaz.sendRawConsoleCommand(command) && Lizzie.leelaz.isPondering()) {
          Lizzie.leelaz.ponder();
        }
      } else if ("clear_board".equals(commandToLower)) {
        Lizzie.board.clear(false);
        Lizzie.frame.refresh();
      } else if ("heatmap".equals(commandToLower)) {
        Lizzie.leelaz.toggleHeatmap(false);
      } else if (commandToLower.startsWith("kata-raw")) {
        if (Lizzie.leelaz.sendRawConsoleCommand(command)) {
        Lizzie.leelaz.setHeatmap();
        }
      } else if (commandToLower.startsWith("boardsize")) {
        String cmdParams[] = command.split(" ");
        if (cmdParams.length >= 2) {
          int width = Integer.parseInt(cmdParams[1]);
          int height = width;
          if (cmdParams.length >= 3) {
            height = Integer.parseInt(cmdParams[2]);
          }
          Lizzie.board.reopen(width, height);
        } else {
          this.setDocs(
              resourceBundle.getString("GtpConsolePane.wrongParameters") + "\r\n",
              new Color(255, 255, 0),
              false,
              Config.frameFontSize);
        }
      } else if (commandToLower.startsWith("komi")) {
        String cmdParams[] = command.split(" ");
        if (cmdParams.length == 2) {
          Lizzie.board.getHistory().getGameInfo().setKomi(Double.parseDouble(cmdParams[1]));
          Lizzie.board.getHistory().getGameInfo().changeKomi();
          // Lizzie.frame.komi = cmdParams[1];
          if (LizzieFrame.toolbar.setkomi != null)
            LizzieFrame.toolbar.setkomi.textFieldKomi.setText(cmdParams[1]);
        }
        Lizzie.leelaz.sendCommand(command);
        Lizzie.board.clearBestMovesAfter(Lizzie.board.getHistory().getStart());
        if (Lizzie.leelaz.isPondering()) {
          Lizzie.leelaz.ponder();
        }
      } else if ("undo".equals(command)) {
        if (!Lizzie.board.previousMove(true))
          this.setDocs(
              resourceBundle.getString("GtpConsolePane.wrongPrevious") + "\r\n",
              new Color(255, 255, 0),
              false,
              Config.frameFontSize);
      } else if (command.startsWith("pda")
          || command.startsWith("dympdacap")
          || command.startsWith("getpda")
          || command.startsWith("getdympdacap")) {
        if (commandToLower.startsWith("pda")) {
          String[] params = command.trim().split(" ");
          if (params.length == 2) {
            try {
              Double pda = Double.parseDouble(params[1]);
              Lizzie.leelaz.pda = pda;
              Lizzie.leelaz.isStaticPda = true;
              if (Lizzie.config.isDoubleEngineMode()) Lizzie.leelaz2.pda = 0;
              if (LizzieFrame.menu.setPda != null)
                LizzieFrame.menu.setPda.curPDA.setText(String.valueOf(pda));
              LizzieFrame.menu.txtPDA.setText(String.valueOf(pda));
            } catch (Exception es) {
              es.printStackTrace();
            }
          }
        }
        if (commandToLower.startsWith("dympdacap")) {
          Lizzie.leelaz.sendCommand("pda 0");
          LizzieFrame.menu.txtPDA.setText("0.000");
          Lizzie.leelaz.isStaticPda = false;
          String[] params = command.trim().split(" ");
          if (params.length == 2) {
            try {
              double dymCap = Double.parseDouble(params[1]);
              Lizzie.leelaz.pdaCap = dymCap;
            } catch (Exception es) {
              es.printStackTrace();
            }
          }
        }
        Lizzie.leelaz.sendCommand(command);
        if (Lizzie.leelaz.isPondering()) Lizzie.leelaz.ponder();
      } else if (command.startsWith("dympdacap")) {
        String[] params = command.trim().split(" ");
        if (params.length == 2) {
          try {
            Double pdaCap = Double.parseDouble(params[1]);
            Lizzie.leelaz.pdaCap = pdaCap;
            if (LizzieFrame.menu.setPda != null)
              LizzieFrame.menu.setPda.txtDymCap.setText(String.valueOf(pdaCap));
          } catch (Exception es) {
            es.printStackTrace();
          }
        }
        Lizzie.leelaz.sendCommand(command);
        if (Lizzie.leelaz.isPondering()) Lizzie.leelaz.ponder();
      } else {
        Lizzie.leelaz.sendRawConsoleCommand(command);
      }
    }
  }

  private static Stone consoleColor(String value) {
    switch (value.toLowerCase(Locale.ROOT)) {
      case "b":
      case "black":
        return Stone.BLACK;
      case "w":
      case "white":
        return Stone.WHITE;
      default:
        return Stone.EMPTY;
    }
  }

  private boolean playConsoleMove(String[] params) {
    if (params.length != 3) return false;
    Stone color = consoleColor(params[1]);
    if (color == Stone.EMPTY) return false;
    if ("pass".equalsIgnoreCase(params[2])) {
      Lizzie.board.pass(color);
      return true;
    }
    Matcher vertex = CONSOLE_VERTEX.matcher(params[2]);
    if (!vertex.matches()) return false;
    // Board.asCoordinates intentionally repairs oversized rows for other callers. Console input
    // must be validated as written, not silently shortened into a different move (e.g. A50 -> A5).
    int row;
    try {
      row = Integer.parseInt(vertex.group(2));
    } catch (NumberFormatException invalidRow) {
      return false;
    }
    if (row < 1 || row > Board.boardHeight) return false;
    Optional<int[]> coordinates = Board.asCoordinates(params[2]);
    if (!coordinates.isPresent()) return false;
    int[] point = coordinates.get();
    if (!Board.isValid(point[0], point[1])) return false;
    if (!Board.coordsAsName(point[0]).equalsIgnoreCase(vertex.group(1))) return false;
    // Use the explicit-color API: a rejected move must not change the current node's turn.
    Lizzie.board.place(point[0], point[1], color);
    return true;
  }

  private void wrongMoveParameters() {
    setDocs(
        resourceBundle.getString("GtpConsolePane.wrongParameters") + "\r\n",
        new Color(255, 255, 0),
        false,
        Config.frameFontSize);
  }

  public void addErrorLine(String line) {
    // TODO Auto-generated method stub
    setDocs(line, new Color(255, 0, 0), false, Config.frameFontSize);
  }

  public void setViewEnd() {
    // TODO Auto-generated method stub
    Color cl = console.getSelectionColor();
    console.setSelectionColor(new Color(0, 0, 0, 0));
    console.selectAll();
    console.setSelectionColor(cl);
    getContentPane().requestFocus();
  }
}
