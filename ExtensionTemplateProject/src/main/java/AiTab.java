import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.function.Supplier;

public class AiTab {

    // Compact template — ~80 tokens vs the old ~400
    private static final String SYSTEM_PROMPT_TEMPLATE =
            "You are a penetration testing assistant. The user captures HTTP traffic from pentest " +
            "targets into DuckDB.\n\n" +
            "Table: traffic(id BIGINT, timestamp, host, port, protocol, method, path, " +
            "status_code INT, req_headers JSON, req_body, resp_headers JSON, resp_body, resp_length INT)\n\n" +
            "%s\n\n" +
            "Give concise, actionable pentest insights. Suggest DuckDB SQL queries where useful.";

    private final DuckDbManager       db;
    private final Supplier<AiBackend> backendSupplier;
    private final JPanel              panel;
    private final JTextArea           questionArea;
    private final JTextArea           responseArea;
    private final JLabel              statusLabel;
    private final JCheckBox           includeContextCheckbox;

    public AiTab(DuckDbManager db, Supplier<AiBackend> backendSupplier) {
        this.db              = db;
        this.backendSupplier = backendSupplier;

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

        // ── Layout ────────────────────────────────────────────────────────────
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, responseScroll);
        split.setResizeWeight(0.3);
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
                SwingUtilities.invokeLater(() -> {
                    responseArea.setText(response);
                    responseArea.setCaretPosition(0);
                    statusLabel.setText("Done. (~" + totalChars + " chars sent)");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Error: " + ex.getMessage());
                    responseArea.setText("Error communicating with AI:\n\n" + ex.getMessage());
                });
            }
        }, "DuckDuckBurp-ai").start();
    }

    /**
     * Builds a compact plain-text traffic summary.  Intentionally terse to
     * minimise token usage when sending to AI backends.  Package-private for tests.
     */
    static String buildTrafficSummary(DuckDbManager db) throws Exception {
        // All scalar stats in one query
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

        // Hosts — top 5, inline
        appendCompact(sb, "hosts", db.query(
                "SELECT host || '(' || COUNT(*) || ')' AS v FROM traffic " +
                "GROUP BY host ORDER BY COUNT(*) DESC LIMIT 5"));

        // Status codes — top 8, inline
        appendCompact(sb, "status", db.query(
                "SELECT CAST(status_code AS VARCHAR) || '(' || COUNT(*) || ')' AS v FROM traffic " +
                "GROUP BY status_code ORDER BY COUNT(*) DESC LIMIT 8"));

        // Methods — all, inline
        appendCompact(sb, "methods", db.query(
                "SELECT method || '(' || COUNT(*) || ')' AS v FROM traffic " +
                "GROUP BY method ORDER BY COUNT(*) DESC"));

        // Recent 10 paths — most useful context for analysis
        DuckDbManager.QueryResult recent = db.query(
                "SELECT method, path, status_code FROM traffic ORDER BY id DESC LIMIT 10");
        if (!recent.rows().isEmpty()) {
            sb.append("recent:");
            for (Object[] row : recent.rows())
                sb.append(" ").append(row[0]).append(" ").append(row[1]).append("→").append(row[2]).append(",");
            // trim trailing comma
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

    public Component uiComponent() { return panel; }
}
