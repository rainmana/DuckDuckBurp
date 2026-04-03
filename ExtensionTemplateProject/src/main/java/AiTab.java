import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

public class AiTab {

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a penetration testing assistant analyzing HTTP traffic captured during a security assessment.

            The traffic is stored in a DuckDB database with the following schema:

            Table: traffic
              id           BIGINT    — unique request ID
              timestamp    TIMESTAMPTZ
              host         VARCHAR   — target hostname
              port         INTEGER
              protocol     VARCHAR   — 'http' or 'https'
              method       VARCHAR   — GET, POST, PUT, DELETE, …
              path         VARCHAR   — URL path + query string
              status_code  INTEGER   — HTTP response status
              req_headers  JSON
              req_body     VARCHAR
              resp_headers JSON
              resp_body    VARCHAR
              resp_length  INTEGER   — response body size in bytes

            Current traffic summary:
            %s

            Provide concise, actionable security insights. Focus on findings that are \
            genuinely useful during a pentest. When relevant, suggest specific DuckDB SQL \
            queries the tester could run to dig deeper.
            """;

    private final DuckDbManager db;
    private final AiBackend aiBackend;
    private final JPanel panel;
    private final JTextArea questionArea;
    private final JTextArea responseArea;
    private final JLabel statusLabel;
    private final JCheckBox includeContextCheckbox;

    public AiTab(DuckDbManager db, AiBackend aiBackend) {
        this.db = db;
        this.aiBackend = aiBackend;

        panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // ── AI availability banner ────────────────────────────────────────────
        boolean available = aiBackend.isAvailable();
        JLabel aiStatusLabel = new JLabel(available
                ? "Burp AI is enabled"
                : "Burp AI is not available — requires a Burp AI subscription");
        aiStatusLabel.setFont(aiStatusLabel.getFont().deriveFont(Font.ITALIC));
        aiStatusLabel.setForeground(available ? new Color(0, 128, 0) : Color.RED.darker());
        JPanel bannerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        bannerPanel.add(aiStatusLabel);

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
        askButton.setEnabled(available);
        includeContextCheckbox = new JCheckBox("Include traffic summary", true);
        statusLabel = new JLabel(" ");

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        toolbar.add(askButton);
        toolbar.add(includeContextCheckbox);
        toolbar.add(statusLabel);

        JPanel topPanel = new JPanel(new BorderLayout(0, 4));
        topPanel.add(bannerPanel,      BorderLayout.NORTH);
        topPanel.add(questionScroll,   BorderLayout.CENTER);
        topPanel.add(toolbar,          BorderLayout.SOUTH);

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

        if (!aiBackend.isAvailable()) {
            statusLabel.setText("AI is not available.");
            return;
        }

        statusLabel.setText("Thinking...");
        responseArea.setText("");

        new Thread(() -> {
            try {
                String context = includeContextCheckbox.isSelected()
                        ? buildTrafficSummary(db)
                        : "(traffic summary not included)";
                String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, context);
                String response = aiBackend.ask(systemPrompt, question);
                SwingUtilities.invokeLater(() -> {
                    responseArea.setText(response);
                    responseArea.setCaretPosition(0);
                    statusLabel.setText("Done.");
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
     * Builds a plain-text summary of current traffic for inclusion in the AI
     * system prompt. Package-private so it can be tested directly.
     */
    static String buildTrafficSummary(DuckDbManager db) throws Exception {
        // Check for data first
        DuckDbManager.QueryResult overview = db.query(
                "SELECT COUNT(*) AS total, COUNT(DISTINCT host) AS hosts, " +
                "COUNT(DISTINCT method) AS methods FROM traffic");
        if (overview.rows().isEmpty()) return "No traffic captured yet.";
        long total = ((Number) overview.rows().get(0)[0]).longValue();
        if (total == 0) return "No traffic captured yet.";

        StringBuilder sb = new StringBuilder();
        Object[] ov = overview.rows().get(0);
        sb.append("Total requests: ").append(ov[0])
          .append(" | Unique hosts: ").append(ov[1])
          .append(" | Methods seen: ").append(ov[2]).append("\n");

        // Top hosts
        DuckDbManager.QueryResult hosts = db.query(
                "SELECT host, COUNT(*) AS cnt FROM traffic " +
                "GROUP BY host ORDER BY cnt DESC LIMIT 10");
        if (!hosts.rows().isEmpty()) {
            sb.append("\nTop hosts:\n");
            for (Object[] row : hosts.rows())
                sb.append("  ").append(row[0]).append(": ").append(row[1]).append(" requests\n");
        }

        // Status code distribution
        DuckDbManager.QueryResult statuses = db.query(
                "SELECT status_code, COUNT(*) AS cnt FROM traffic " +
                "GROUP BY status_code ORDER BY cnt DESC LIMIT 15");
        if (!statuses.rows().isEmpty()) {
            sb.append("\nStatus codes:\n");
            for (Object[] row : statuses.rows())
                sb.append("  ").append(row[0]).append(": ").append(row[1]).append("\n");
        }

        // Method distribution
        DuckDbManager.QueryResult methods = db.query(
                "SELECT method, COUNT(*) AS cnt FROM traffic " +
                "GROUP BY method ORDER BY cnt DESC");
        if (!methods.rows().isEmpty()) {
            sb.append("\nMethods:\n");
            for (Object[] row : methods.rows())
                sb.append("  ").append(row[0]).append(": ").append(row[1]).append("\n");
        }

        // Auth failures
        DuckDbManager.QueryResult authFails = db.query(
                "SELECT COUNT(*) AS cnt FROM traffic WHERE status_code IN (401, 403)");
        if (!authFails.rows().isEmpty())
            sb.append("\nAuth failures (401/403): ").append(authFails.rows().get(0)[0]).append("\n");

        // Server errors
        DuckDbManager.QueryResult serverErrors = db.query(
                "SELECT COUNT(*) AS cnt FROM traffic WHERE status_code >= 500");
        if (!serverErrors.rows().isEmpty())
            sb.append("Server errors (5xx): ").append(serverErrors.rows().get(0)[0]).append("\n");

        // Recent 30 requests for path context
        DuckDbManager.QueryResult recent = db.query(
                "SELECT method, path, status_code FROM traffic ORDER BY id DESC LIMIT 30");
        if (!recent.rows().isEmpty()) {
            sb.append("\nRecent requests (newest first):\n");
            for (Object[] row : recent.rows())
                sb.append("  ").append(row[0]).append(" ").append(row[1])
                  .append(" → ").append(row[2]).append("\n");
        }

        return sb.toString();
    }

    public Component uiComponent() { return panel; }
}
