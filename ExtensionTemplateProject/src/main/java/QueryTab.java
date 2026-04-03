import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;

public class QueryTab {

    private static final String HINT_NO_ID = "(include 'id' in your query to see request/response detail)";

    private final DuckDbManager db;
    private final JPanel panel;
    private final JTextArea queryInput;
    private final JTable resultsTable;
    private final DefaultTableModel tableModel;
    private final JLabel statusLabel;
    private final JTextArea requestArea;
    private final JTextArea responseArea;

    public QueryTab(DuckDbManager db) {
        this.db = db;

        panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // --- Query input ---
        queryInput = new JTextArea(6, 80);
        queryInput.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        queryInput.setText("""
                SELECT id, host, method, path, status_code, resp_length
                FROM traffic
                ORDER BY id DESC
                LIMIT 100""");

        JScrollPane inputScroll = new JScrollPane(queryInput);
        inputScroll.setBorder(BorderFactory.createTitledBorder("SQL Query (Ctrl+Enter to run)"));

        // --- Toolbar ---
        JButton runButton = new JButton("Run Query");
        JButton exportButton = new JButton("Export...");
        statusLabel = new JLabel(" ");
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        toolbar.add(runButton);
        toolbar.add(exportButton);
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
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane tableScroll = new JScrollPane(resultsTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Results"));

        // --- Detail view ---
        requestArea = makeDetailArea();
        responseArea = makeDetailArea();

        JTabbedPane detailPane = new JTabbedPane();
        detailPane.addTab("Request", new JScrollPane(requestArea));
        detailPane.addTab("Response", new JScrollPane(responseArea));
        detailPane.setBorder(BorderFactory.createTitledBorder("Detail"));

        // --- Split: results (top) / detail (bottom) ---
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detailPane);
        splitPane.setResizeWeight(0.6);
        splitPane.setOneTouchExpandable(true);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(splitPane, BorderLayout.CENTER);

        // --- Actions ---
        runButton.addActionListener(e -> runQuery());
        exportButton.addActionListener(e -> exportResults());

        queryInput.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "runQuery");
        queryInput.getActionMap().put("runQuery", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                runQuery();
            }
        });

        resultsTable.getSelectionModel().addListSelectionListener(this::onRowSelected);
    }

    private void runQuery() {
        String sql = queryInput.getText().trim();
        if (sql.isEmpty()) return;

        statusLabel.setText("Running...");
        tableModel.setRowCount(0);
        tableModel.setColumnCount(0);
        clearDetail();

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

    private void onRowSelected(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        int row = resultsTable.getSelectedRow();
        if (row < 0) return;

        int idColIdx = columnIndex("id");
        if (idColIdx < 0) {
            requestArea.setText(HINT_NO_ID);
            responseArea.setText(HINT_NO_ID);
            return;
        }

        Object idVal = tableModel.getValueAt(row, idColIdx);
        if (idVal == null) return;
        long id = ((Number) idVal).longValue();

        new Thread(() -> {
            try {
                DuckDbManager.QueryResult result = db.query(
                        "SELECT req_headers, req_body, resp_headers, resp_body FROM traffic WHERE id = " + id
                );
                if (result.rows().isEmpty()) return;
                Object[] r = result.rows().get(0);
                String reqHeaders  = nullToEmpty(r[0]);
                String reqBody     = nullToEmpty(r[1]);
                String respHeaders = nullToEmpty(r[2]);
                String respBody    = nullToEmpty(r[3]);

                String reqText  = formatMessage(reqHeaders, reqBody);
                String respText = formatMessage(respHeaders, respBody);

                SwingUtilities.invokeLater(() -> {
                    requestArea.setText(reqText);
                    requestArea.setCaretPosition(0);
                    responseArea.setText(respText);
                    responseArea.setCaretPosition(0);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        requestArea.setText("Error loading detail: " + ex.getMessage()));
            }
        }, "DuckDuckBurp-detail").start();
    }

    private void exportResults() {
        String sql = queryInput.getText().trim();
        if (sql.isEmpty()) {
            JOptionPane.showMessageDialog(panel, "No query to export.", "Export", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String[] formats = {"CSV", "JSON", "Parquet"};
        String format = (String) JOptionPane.showInputDialog(
                panel, "Export format:", "Export Results",
                JOptionPane.PLAIN_MESSAGE, null, formats, "CSV"
        );
        if (format == null) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save export as...");
        String ext = format.toLowerCase().replace("parquet", "parquet");
        chooser.setFileFilter(new FileNameExtensionFilter(format + " files", ext));
        chooser.setSelectedFile(new File("traffic." + ext));

        if (chooser.showSaveDialog(panel) != JFileChooser.APPROVE_OPTION) return;
        String path = chooser.getSelectedFile().getAbsolutePath();

        String copyOptions = switch (format) {
            case "JSON"    -> "(FORMAT JSON)";
            case "Parquet" -> "(FORMAT PARQUET)";
            default        -> "(FORMAT CSV, HEADER true)";
        };

        // Strip trailing semicolon before wrapping in COPY
        String innerSql = sql.replaceAll(";\\s*$", "");
        String copySql = "COPY (" + innerSql + ") TO '" + path.replace("'", "\\'") + "' " + copyOptions;

        statusLabel.setText("Exporting...");
        new Thread(() -> {
            try {
                db.execute(copySql);
                SwingUtilities.invokeLater(() -> statusLabel.setText("Exported to " + path));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Export failed.");
                    JOptionPane.showMessageDialog(panel, ex.getMessage(),
                            "Export Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "DuckDuckBurp-export").start();
    }

    // --- helpers ---

    private JTextArea makeDetailArea() {
        JTextArea area = new JTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        area.setLineWrap(false);
        return area;
    }

    private void clearDetail() {
        requestArea.setText("");
        responseArea.setText("");
    }

    private int columnIndex(String name) {
        for (int i = 0; i < tableModel.getColumnCount(); i++) {
            if (tableModel.getColumnName(i).equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private String nullToEmpty(Object o) {
        return o == null ? "" : o.toString();
    }

    private String formatMessage(String headers, String body) {
        if (headers.isEmpty() && body.isEmpty()) return "";
        if (body.isEmpty()) return headers;
        return headers + "\r\n\r\n" + body;
    }

    public Component uiComponent() {
        return panel;
    }
}
