package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.SavedEnginePathResolver.Candidate;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Lets the user pick one saved engine entry whose local executable path is filled into the
 * contribution settings. Rows are bound to the candidate objects of this opening only.
 */
final class SavedEnginePathChooser extends JDialog {
  private static final String[] COLUMN_KEYS = {
    "ContributeSettings.savedEngine.columnIndex",
    "ContributeSettings.savedEngine.columnName",
    "ContributeSettings.savedEngine.columnType",
    "ContributeSettings.savedEngine.columnPath"
  };

  private final List<Candidate> candidates;
  private final JTable table;
  private final JTextArea detail;
  private final JButton fillButton;
  private Candidate chosen;

  private SavedEnginePathChooser(Window owner, List<Candidate> candidates) {
    super(owner, text("ContributeSettings.savedEngine.title"), ModalityType.DOCUMENT_MODAL);
    this.candidates = candidates;
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    Font font = new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize);

    JTextArea hint = wrappedText(text("ContributeSettings.savedEngineTip"), font);
    hint.setOpaque(false);
    hint.setWrapStyleWord(true);
    hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

    table = new JTable(new CandidateTableModel());
    table.setFont(font);
    table.getTableHeader().setFont(font);
    table.setRowHeight(table.getFontMetrics(font).getHeight() + 6);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setFillsViewportHeight(true);
    table.setDefaultRenderer(Object.class, new CandidateRenderer());
    int[] widths = {36, 150, 110, 420};
    for (int i = 0; i < widths.length; i++) {
      table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    }
    table.getColumnModel().getColumn(0).setMaxWidth(60);
    table.setPreferredScrollableViewportSize(
        new Dimension(716, Math.min(Math.max(candidates.size(), 3), 8) * table.getRowHeight()));
    table.getSelectionModel().addListSelectionListener(event -> updateSelection());
    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            if (event.getClickCount() == 2) fill();
          }
        });

    detail = wrappedText("", font);
    detail.setRows(3);
    JScrollPane detailScroll = new JScrollPane(detail);

    JPanel center = new JPanel(new BorderLayout(0, 6));
    center.add(hint, BorderLayout.NORTH);
    center.add(new JScrollPane(table), BorderLayout.CENTER);
    center.add(detailScroll, BorderLayout.SOUTH);

    fillButton = new JFontButton(text("ContributeSettings.savedEngine.fill"));
    fillButton.addActionListener(event -> fill());
    JButton cancelButton = new JFontButton(text("ContributeSettings.savedEngine.cancel"));
    cancelButton.addActionListener(event -> dispose());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(fillButton);
    buttons.add(cancelButton);

    JPanel content = new JPanel(new BorderLayout(0, 6));
    content.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
    content.add(center, BorderLayout.CENTER);
    content.add(buttons, BorderLayout.SOUTH);
    setContentPane(content);
    getRootPane().setDefaultButton(fillButton);
    AccessibilitySupport.installEscapeToClose(getRootPane(), this);

    table.setRowSelectionInterval(0, 0);
    updateSelection();
    pack();
    setLocationRelativeTo(owner);
  }

  /** Shows the list; returns the confirmed available candidate, or null when cancelled. */
  static Candidate choose(Window owner, List<Candidate> candidates) {
    SavedEnginePathChooser chooser = new SavedEnginePathChooser(owner, candidates);
    chooser.setVisible(true);
    return chooser.chosen;
  }

  private Candidate selected() {
    int row = table.getSelectedRow();
    return row < 0 ? null : candidates.get(table.convertRowIndexToModel(row));
  }

  private void updateSelection() {
    Candidate candidate = selected();
    fillButton.setEnabled(candidate != null && candidate.isAvailable());
    if (candidate == null) {
      detail.setText("");
    } else if (candidate.isAvailable()) {
      detail.setText(
          text("ContributeSettings.savedEngine.detailPath")
              + "\n"
              + candidate.executable().toString());
    } else {
      detail.setText(
          text("ContributeSettings.savedEngine.detailUnavailable") + "\n" + reason(candidate));
    }
    detail.setCaretPosition(0);
  }

  private void fill() {
    Candidate candidate = selected();
    if (candidate == null || !candidate.isAvailable()) return;
    chosen = candidate;
    dispose();
  }

  private static String cell(Candidate candidate, int column) {
    switch (column) {
      case 0:
        return Integer.toString(candidate.displayIndex());
      case 1:
        return candidate.name().isEmpty()
            ? text("ContributeSettings.savedEngine.unnamed")
            : candidate.name();
      case 2:
        switch (candidate.kind()) {
          case LOCAL:
            return text("ContributeSettings.savedEngine.typeLocal");
          case REMOTE:
            return text("ContributeSettings.savedEngine.typeRemote");
          case INDIRECT:
            return text("ContributeSettings.savedEngine.typeIndirect");
          default:
            return text("ContributeSettings.savedEngine.typeUnknown");
        }
      default:
        return candidate.isAvailable() ? candidate.executable().toString() : reason(candidate);
    }
  }

  private static String reason(Candidate candidate) {
    switch (candidate.reason()) {
      case EMPTY_COMMAND:
        return text("ContributeSettings.savedEngine.reasonEmpty");
      case UNPARSEABLE_COMMAND:
        return text("ContributeSettings.savedEngine.reasonUnparseable");
      case MISSING_FILE:
        return text("ContributeSettings.savedEngine.reasonMissing");
      case REMOTE_TRANSPORT:
        return text("ContributeSettings.savedEngine.reasonRemote");
      default:
        return text("ContributeSettings.savedEngine.reasonIndirect");
    }
  }

  private static JTextArea wrappedText(String value, Font font) {
    JTextArea area = new JTextArea(value);
    area.setFont(font);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(false);
    return area;
  }

  private static String text(String key) {
    return Lizzie.resourceBundle.getString(key);
  }

  private final class CandidateTableModel extends AbstractTableModel {
    @Override
    public int getRowCount() {
      return candidates.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMN_KEYS.length;
    }

    @Override
    public String getColumnName(int column) {
      return text(COLUMN_KEYS[column]);
    }

    @Override
    public Object getValueAt(int row, int column) {
      return cell(candidates.get(row), column);
    }
  }

  private final class CandidateRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean selected, boolean focused, int row, int column) {
      super.getTableCellRendererComponent(table, value, selected, focused, row, column);
      Candidate candidate = candidates.get(table.convertRowIndexToModel(row));
      setToolTipText(value == null ? null : value.toString());
      if (!candidate.isAvailable() && !selected) {
        Color disabled = UIManager.getColor("Label.disabledForeground");
        setForeground(disabled == null ? Color.GRAY : disabled);
      } else if (!selected) {
        setForeground(table.getForeground());
      }
      return this;
    }
  }
}
