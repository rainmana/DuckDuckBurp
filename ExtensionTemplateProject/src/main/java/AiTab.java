import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class AiTab {

    private static final String SYSTEM_PROMPT_TEMPLATE =
            "You are a penetration testing assistant. The user captures HTTP traffic from pentest " +
            "targets into DuckDB.\n\n" +
            "Table: traffic(id BIGINT, timestamp, host, port, protocol, method, path, " +
            "status_code INT, req_headers JSON, req_body, resp_headers JSON, resp_body, resp_length INT)\n\n" +
            "%s\n\n" +
            "Give concise, actionable pentest insights. Suggest DuckDB SQL queries where useful.";

    private final DuckDbManager       db;
    private final Supplier<AiBackend> backendSupplier;
    private final Consumer<String>    runInQueryTab;   // load SQL into Query tab and run it
    private final Runnable            sidebarRefresh;  // refresh the saved-queries sidebar
    private final JPanel              panel;
    private final JTextArea           questionArea;
    private final JTextArea           responseArea;
    private final JLabel              statusLabel;
    private final JCheckBox           includeContextCheckbox;
    private final JPanel              suggestionRows;
    private final JScrollPane         suggestionsScroll;

    public AiTab(DuckDbManager db,
                 Supplier<AiBackend> backendSupplier,
                 Consumer<String>    runInQueryTab,
                 Runnable            sidebarRefresh) {
        this.db              = db;
        this.backendSupplier = backendSupplier;
        this.runInQueryTab   = runInQueryTab;
        this.sidebarRefresh  = sidebarRefresh;

        panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // ── Question input ────────────────────────────────────────────────────
        questionArea = new JTextArea(5, 80);
        questionArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        questionArea.setLineWrap(true);
        questionArea.setWrapStyleWord(true);
        questionArea.setText("Summarize the attack surface and highlight anything worth investigating.");
        JScrollPane questionScroll = new JScrollPane(questionArea);
        questionScroll.setBorder(BorderFactory.createTitledBorder("Question (Ctrl+Enter to send)"));

        // ── Toolbar ───────────────────────────────────────────────────────────
        JButton askButton = new JButton("Ask AI");
        includeContextCheckbox = new JCheckBox("Include traffic summary", true);
        statusLabel = new JLabel("Configure AI backend in the Settings tab.");

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        toolbar.add(askButton);
        toolbar.add(includeContextCheckbox);
        toolbar.add(statusLabel);

        JPanel topPanel = new JPanel(new BorderLayout(0, 4));
        topPanel.add(questionScroll, BorderLayout.CENTER);
        topPanel.add(toolbar,        BorderLayout.SOUTH);

        // ── Response area ─────────────────────────────────────────────────────
        responseArea = new JTextArea();
        responseArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        responseArea.setEditable(false);
        responseArea.setLineWrap(true);
        responseArea.setWrapStyleWord(true);
        JScrollPane responseScroll = new JScrollPane(responseArea);
        responseScroll.setBorder(BorderFactory.createTitledBorder("AI Response"));

        // ── Suggested queries panel ───────────────────────────────────────────
        suggestionRows = new JPanel();
        suggestionRows.setLayout(new BoxLayout(suggestionRows, BoxLayout.Y_AXIS));
        suggestionsScroll = new JScrollPane(suggestionRows);
        suggestionsScroll.setBorder(BorderFactory.createTitledBorder("Suggested Queries"));
        suggestionsScroll.setPreferredSize(new Dimension(0, 160));
        suggestionsScroll.setVisible(false);

        JPanel bottomPanel = new JPanel(new BorderLayout(0, 4));
        bottomPanel.add(responseScroll,    BorderLayout.CENTER);
        bottomPanel.add(suggestionsScroll, BorderLayout.SOUTH);

        // ── Layout ────────────────────────────────────────────────────────────
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, bottomPanel);
        split.setResizeWeight(0.25);
        split.setOneTouchExpandable(true);

        panel.add(split, BorderLayout.CENTER);

        // ── Actions ───────────────────────────────────────────────────────────
        askButton.addActionListener(e -> sendQuestion());
        questionArea.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "ask");
        questionArea.getActionMap().put("ask", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { sendQuestion(); }
        });
    }

    private void sendQuestion() {
        String question = questionArea.getText().trim();
        if (question.isEmpty()) return;

        AiBackend backend = backendSupplier.get();
        if (!backend.isAvailable()) {
            statusLabel.setText("AI backend is not available — check the Settings tab.");
            return;
        }

        statusLabel.setText("Thinking…");
        responseArea.setText("");
        suggestionsScroll.setVisible(false);

        new Thread(() -> {
            try {
                String context = includeContextCheckbox.isSelected()
                        ? buildTrafficSummary(db)
                        : "";
                String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, context);
                int totalChars = systemPrompt.length() + question.length();
                SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Thinking… (~" + totalChars + " chars)"));

                String response = backend.ask(systemPrompt, question);
                List<String> sqlBlocks = AiResponseParser.extractSqlBlocks(response);

                SwingUtilities.invokeLater(() -> {
                    responseArea.setText(response);
                    responseArea.setCaretPosition(0);
                    populateSuggestions(sqlBlocks);
                    String hint = sqlBlocks.isEmpty() ? "" : "  •  " + sqlBlocks.size() + " SQL queries detected ↓";
                    statusLabel.setText("Done. (~" + totalChars + " chars sent)" + hint);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Error: " + ex.getMessage());
                    responseArea.setText("Error communicating with AI:\n\n" + ex.getMessage());
                });
            }
        }, "DuckDuckBurp-ai").start();
    }

    private void populateSuggestions(List<String> queries) {
        suggestionRows.removeAll();
        if (queries.isEmpty()) {
            suggestionsScroll.setVisible(false);
            return;
        }
        for (String sql : queries) {
            suggestionRows.add(buildSuggestionRow(sql));
            suggestionRows.add(Box.createVerticalStrut(2));
        }
        suggestionsScroll.setVisible(true);
        suggestionsScroll.revalidate();
        suggestionsScroll.repaint();
    }

    private JPanel buildSuggestionRow(String sql) {
        // One-line preview: collapse whitespace, truncate
        String preview = sql.replaceAll("\\s+", " ").trim();
        if (preview.length() > 110) preview = preview.substring(0, 107) + "…";

        JLabel sqlLabel = new JLabel(preview);
        sqlLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        sqlLabel.setForeground(Color.DARK_GRAY);
        sqlLabel.setToolTipText("<html><pre>" + escapeHtml(sql) + "</pre></html>");

        JButton runBtn  = new JButton("▶ Run");
        JButton saveBtn = new JButton("💾 Save");
        runBtn.setMargin(new Insets(1, 6, 1, 6));
        saveBtn.setMargin(new Insets(1, 6, 1, 6));
        runBtn.addActionListener(e  -> runInQueryTab.accept(sql));
        saveBtn.addActionListener(e -> saveFromAi(sql));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        buttons.setOpaque(false);
        buttons.add(runBtn);
        buttons.add(saveBtn);

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(3, 6, 3, 4)));
        row.add(sqlLabel, BorderLayout.CENTER);
        row.add(buttons,  BorderLayout.EAST);
        return row;
    }

    private void saveFromAi(String sql) {
        String suggestedCategory = AiResponseParser.suggestCategory(sql);

        JTextField nameField = new JTextField(28);
        List<String> existingCats = new ArrayList<>();
        try {
            existingCats = db.loadSavedQueries().stream()
                    .map(DuckDbManager.SavedQuery::category)
                    .distinct().sorted()
                    .collect(Collectors.toList());
        } catch (Exception ignored) {}
        if (!existingCats.contains(suggestedCategory)) existingCats.add(0, suggestedCategory);

        JComboBox<String> categoryBox = new JComboBox<>(existingCats.toArray(new String[0]));
        categoryBox.setEditable(true);
        categoryBox.setSelectedItem(suggestedCategory);

        JPanel form = new JPanel(new GridLayout(2, 2, 5, 5));
        form.add(new JLabel("Name:"));     form.add(nameField);
        form.add(new JLabel("Category:")); form.add(categoryBox);

        int result = JOptionPane.showConfirmDialog(panel, form, "Save Query",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String name     = nameField.getText().trim();
        String category = String.valueOf(categoryBox.getSelectedItem()).trim();
        if (name.isEmpty() || category.isEmpty()) {
            JOptionPane.showMessageDialog(panel, "Name and category are required.",
                    "Save Query", JOptionPane.WARNING_MESSAGE);
            return;
        }

        new Thread(() -> {
            try {
                db.saveQuery(category, name, sql);
                sidebarRefresh.run();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(panel, ex.getMessage(),
                                "Save Error", JOptionPane.ERROR_MESSAGE));
            }
        }, "DuckDuckBurp-save-ai-query").start();
    }

    // ── Traffic summary (compact, token-efficient) ────────────────────────────

    static String buildTrafficSummary(DuckDbManager db) throws Exception {
        DuckDbManager.QueryResult stats = db.query(
                "SELECT COUNT(*) AS total, COUNT(DISTINCT host) AS hosts, " +
                "COUNT(DISTINCT method) AS methods, " +
                "SUM(CASE WHEN status_code IN (401,403) THEN 1 ELSE 0 END) AS auth_fails, " +
                "SUM(CASE WHEN status_code >= 500 THEN 1 ELSE 0 END) AS server_errs " +
                "FROM traffic");
        if (stats.rows().isEmpty()) return "No traffic captured yet.";
        Object[] s = stats.rows().get(0);
        long total = ((Number) s[0]).longValue();
        if (total == 0) return "No traffic captured yet.";

        StringBuilder sb = new StringBuilder();
        sb.append(total).append(" reqs | ")
          .append(s[1]).append(" hosts | ")
          .append(s[2]).append(" methods | ")
          .append(s[3]).append(" auth_fails | ")
          .append(s[4]).append(" server_errs\n");

        appendCompact(sb, "hosts", db.query(
                "SELECT host || '(' || COUNT(*) || ')' AS v FROM traffic " +
                "GROUP BY host ORDER BY COUNT(*) DESC LIMIT 5"));
        appendCompact(sb, "status", db.query(
                "SELECT CAST(status_code AS VARCHAR) || '(' || COUNT(*) || ')' AS v FROM traffic " +
                "GROUP BY status_code ORDER BY COUNT(*) DESC LIMIT 8"));
        appendCompact(sb, "methods", db.query(
                "SELECT method || '(' || COUNT(*) || ')' AS v FROM traffic " +
                "GROUP BY method ORDER BY COUNT(*) DESC"));

        DuckDbManager.QueryResult recent = db.query(
                "SELECT method, path, status_code FROM traffic ORDER BY id DESC LIMIT 10");
        if (!recent.rows().isEmpty()) {
            sb.append("recent:");
            for (Object[] row : recent.rows())
                sb.append(" ").append(row[0]).append(" ").append(row[1]).append("→").append(row[2]).append(",");
            sb.setLength(sb.length() - 1);
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void appendCompact(StringBuilder sb, String label, DuckDbManager.QueryResult r) {
        if (r.rows().isEmpty()) return;
        sb.append(label).append(":");
        for (Object[] row : r.rows()) sb.append(" ").append(row[0]);
        sb.append("\n");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public Component uiComponent() { return panel; }
}
