import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;

public class QueryTab {

    private final DuckDbManager db;
    private final JPanel panel;
    private final JTextArea queryInput;
    private final JTable resultsTable;
    private final DefaultTableModel tableModel;
    private final JLabel statusLabel;

    public QueryTab(DuckDbManager db) {
        this.db = db;

        panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // --- Query input ---
        queryInput = new JTextArea(6, 80);
        queryInput.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        queryInput.setText("""
                SELECT host, method, path, status_code, resp_length
                FROM traffic
                ORDER BY id DESC
                LIMIT 100""");

        JScrollPane inputScroll = new JScrollPane(queryInput);
        inputScroll.setBorder(BorderFactory.createTitledBorder("SQL Query (Ctrl+Enter to run)"));

        // --- Toolbar ---
        JButton runButton = new JButton("Run Query");
        statusLabel = new JLabel(" ");
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        toolbar.add(runButton);
        toolbar.add(statusLabel);

        JPanel topPanel = new JPanel(new BorderLayout(0, 4));
        topPanel.add(inputScroll, BorderLayout.CENTER);
        topPanel.add(toolbar, BorderLayout.SOUTH);

        // --- Results table ---
        tableModel = new DefaultTableModel() {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        resultsTable = new JTable(tableModel);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultsTable.setFillsViewportHeight(true);

        JScrollPane tableScroll = new JScrollPane(resultsTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Results"));

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(tableScroll, BorderLayout.CENTER);

        // --- Actions ---
        runButton.addActionListener(e -> runQuery());

        queryInput.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "runQuery");
        queryInput.getActionMap().put("runQuery", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                runQuery();
            }
        });
    }

    private void runQuery() {
        String sql = queryInput.getText().trim();
        if (sql.isEmpty()) return;

        statusLabel.setText("Running...");
        tableModel.setRowCount(0);
        tableModel.setColumnCount(0);

        new Thread(() -> {
            try {
                DuckDbManager.QueryResult result = db.query(sql);
                SwingUtilities.invokeLater(() -> {
                    tableModel.setColumnIdentifiers(result.columns());
                    for (Object[] row : result.rows()) {
                        tableModel.addRow(row);
                    }
                    statusLabel.setText(result.rows().size() + " row(s)");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Error: " + ex.getMessage());
                    JOptionPane.showMessageDialog(panel, ex.getMessage(),
                            "Query Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "DuckDuckBurp-query").start();
    }

    public Component uiComponent() {
        return panel;
    }
}
