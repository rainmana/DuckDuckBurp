import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class DashboardTab {

    // Package-private so tests can execute these directly against a seeded DB
    static final String SQL_OVERVIEW = """
            SELECT COUNT(*)              AS total_requests,
                   COUNT(DISTINCT host)  AS unique_hosts
            FROM traffic
            """;

    static final String SQL_AUTH_FAILURES = """
            SELECT path,
                   COUNT(*)              AS hits,
                   COUNT(DISTINCT host)  AS hosts
            FROM traffic
            WHERE status_code IN (401, 403)
            GROUP BY path
            ORDER BY hits DESC
            LIMIT 15
            """;

    static final String SQL_SERVER_ERRORS = """
            SELECT host, path, status_code, COUNT(*) AS hits
            FROM traffic
            WHERE status_code >= 500
            GROUP BY host, path, status_code
            ORDER BY hits DESC
            LIMIT 15
            """;

    static final String SQL_ATTACK_SURFACE = """
            SELECT method, host, path,
                   COUNT(*)                   AS requests,
                   COUNT(DISTINCT status_code) AS response_variants
            FROM traffic
            WHERE method NOT IN ('GET', 'HEAD', 'OPTIONS')
            GROUP BY method, host, path
            ORDER BY requests DESC
            LIMIT 20
            """;

    static final String SQL_LARGEST_RESPONSES = """
            SELECT host, method, path, status_code, resp_length
            FROM traffic
            ORDER BY resp_length DESC
            LIMIT 10
            """;

    static final String SQL_INTERESTING_PATHS = """
            SELECT host, method, path, status_code, COUNT(*) AS hits
            FROM traffic
            WHERE regexp_extract(lower(path),
                'admin|debug|backup|upload|config|secret|token|password|\\.env|swagger|graphql|actuator|phpmyadmin|\\.git|wp-admin|jenkins|kibana|passwd'
            ) <> ''
            GROUP BY host, method, path, status_code
            ORDER BY hits DESC, path
            LIMIT 20
            """;

    static final String SQL_HOSTS_SEEN = """
            SELECT host, protocol,
                   COUNT(*)                                                    AS requests,
                   COUNT(DISTINCT path)                                        AS unique_paths,
                   SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END)        AS errors
            FROM traffic
            GROUP BY host, protocol
            ORDER BY requests DESC
            """;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DuckDbManager db;
    private final JPanel panel;
    private final Timer autoRefreshTimer;
    private final JLabel statsLabel   = new JLabel("No data yet");
    private final JLabel updatedLabel = new JLabel("");
    private final JToggleButton autoToggle = new JToggleButton("Auto-refresh: ON", true);

    private final DefaultTableModel authFailuresModel    = nonEditable();
    private final DefaultTableModel serverErrorsModel    = nonEditable();
    private final DefaultTableModel attackSurfaceModel   = nonEditable();
    private final DefaultTableModel largestResponsesModel = nonEditable();
    private final DefaultTableModel interestingPathsModel = nonEditable();
    private final DefaultTableModel hostsSeenModel       = nonEditable();

    public DashboardTab(DuckDbManager db) {
        this.db = db;

        panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // --- Toolbar ---
        JButton refreshButton = new JButton("Refresh");
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        toolbar.add(refreshButton);
        toolbar.add(autoToggle);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(statsLabel);
        toolbar.add(Box.createHorizontalStrut(16));
        toolbar.add(updatedLabel);
        panel.add(toolbar, BorderLayout.NORTH);

        // --- 3x2 panel grid ---
        JPanel grid = new JPanel(new GridLayout(3, 2, 8, 8));
        grid.add(makePanel("Auth Failures  (401 / 403)",        authFailuresModel));
        grid.add(makePanel("Server Errors  (5xx)",              serverErrorsModel));
        grid.add(makePanel("Attack Surface  (non-GET endpoints)", attackSurfaceModel));
        grid.add(makePanel("Largest Responses",                  largestResponsesModel));
        grid.add(makePanel("Interesting Paths",                  interestingPathsModel));
        grid.add(makePanel("Hosts Seen",                         hostsSeenModel));
        panel.add(new JScrollPane(grid), BorderLayout.CENTER);

        // --- Wire up actions ---
        autoRefreshTimer = new Timer(30_000, e -> refresh());
        autoRefreshTimer.setInitialDelay(0);
        autoRefreshTimer.start();

        refreshButton.addActionListener(e -> refresh());
        autoToggle.addActionListener(e -> {
            boolean on = autoToggle.isSelected();
            autoToggle.setText("Auto-refresh: " + (on ? "ON" : "OFF"));
            if (on) autoRefreshTimer.start(); else autoRefreshTimer.stop();
        });
    }

    private void refresh() {
        new Thread(() -> {
            try {
                // Overview strip
                DuckDbManager.QueryResult ov = db.query(SQL_OVERVIEW);
                if (!ov.rows().isEmpty()) {
                    Object[] r = ov.rows().get(0);
                    long total = r[0] == null ? 0 : ((Number) r[0]).longValue();
                    long hosts = r[1] == null ? 0 : ((Number) r[1]).longValue();
                    String ts = LocalTime.now().format(TIME_FMT);
                    SwingUtilities.invokeLater(() -> {
                        statsLabel.setText(total + " requests  |  " + hosts + " host(s)");
                        updatedLabel.setText("Last refresh: " + ts);
                    });
                }

                // Panels
                populate(authFailuresModel,     db.query(SQL_AUTH_FAILURES));
                populate(serverErrorsModel,     db.query(SQL_SERVER_ERRORS));
                populate(attackSurfaceModel,    db.query(SQL_ATTACK_SURFACE));
                populate(largestResponsesModel, db.query(SQL_LARGEST_RESPONSES));
                populate(interestingPathsModel, db.query(SQL_INTERESTING_PATHS));
                populate(hostsSeenModel,        db.query(SQL_HOSTS_SEEN));

            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        statsLabel.setText("Refresh error: " + ex.getMessage()));
            }
        }, "DuckDuckBurp-dashboard").start();
    }

    private void populate(DefaultTableModel model, DuckDbManager.QueryResult result) {
        SwingUtilities.invokeLater(() -> {
            model.setColumnIdentifiers(result.columns());
            model.setRowCount(0);
            for (Object[] row : result.rows()) model.addRow(row);
        });
    }

    private JPanel makePanel(String title, DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);

        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.setPreferredSize(new Dimension(440, 210));
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        return p;
    }

    private static DefaultTableModel nonEditable() {
        return new DefaultTableModel() {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    public void shutdown() {
        autoRefreshTimer.stop();
    }

    public Component uiComponent() {
        return panel;
    }
}
