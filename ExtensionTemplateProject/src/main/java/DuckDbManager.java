import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class DuckDbManager {

    private final Connection connection;

    public DuckDbManager(String dbPath) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        this.connection = DriverManager.getConnection("jdbc:duckdb:" + dbPath);
        createSchema();
    }

    private void createSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS traffic (
                    id          BIGINT PRIMARY KEY,
                    timestamp   TIMESTAMPTZ,
                    host        VARCHAR,
                    port        INTEGER,
                    protocol    VARCHAR,
                    method      VARCHAR,
                    path        VARCHAR,
                    status_code INTEGER,
                    req_headers JSON,
                    req_body    VARCHAR,
                    resp_headers JSON,
                    resp_body   VARCHAR,
                    resp_length INTEGER
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS saved_queries (
                    id         INTEGER PRIMARY KEY,
                    category   VARCHAR NOT NULL,
                    name       VARCHAR NOT NULL,
                    sql_text   VARCHAR NOT NULL,
                    created_at TIMESTAMPTZ DEFAULT NOW()
                )
                """);
        }
    }

    // ── Saved queries ─────────────────────────────────────────────────────────

    public record SavedQuery(long id, String category, String name, String sql) {
        @Override public String toString() { return name; }
    }

    public synchronized void saveQuery(String category, String name, String sqlText) throws SQLException {
        String sql = """
            INSERT INTO saved_queries (id, category, name, sql_text)
            SELECT COALESCE(MAX(id), 0) + 1, ?, ?, ?
            FROM saved_queries
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, category);
            ps.setString(2, name);
            ps.setString(3, sqlText);
            ps.executeUpdate();
        }
    }

    public synchronized List<SavedQuery> loadSavedQueries() throws SQLException {
        QueryResult result = query("""
            SELECT id, category, name, sql_text
            FROM saved_queries
            ORDER BY category, name
            """);
        List<SavedQuery> list = new ArrayList<>();
        for (Object[] row : result.rows()) {
            list.add(new SavedQuery(
                ((Number) row[0]).longValue(),
                String.valueOf(row[1]),
                String.valueOf(row[2]),
                String.valueOf(row[3])
            ));
        }
        return list;
    }

    public synchronized void deleteSavedQuery(long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM saved_queries WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public synchronized void exportSavedQueries(String path) throws SQLException {
        execute("COPY (SELECT category, name, sql_text FROM saved_queries ORDER BY category, name) "
                + "TO '" + path + "' (FORMAT JSON)");
    }

    public synchronized void importSavedQueries(String path) throws SQLException {
        execute("""
            INSERT INTO saved_queries (id, category, name, sql_text)
            SELECT (SELECT COALESCE(MAX(id), 0) FROM saved_queries)
                   + row_number() OVER () AS id,
                   category, name, sql_text
            FROM read_json_auto('""" + path + "')");
    }

    public synchronized void insertTraffic(
            long id,
            String host, int port, boolean secure,
            String method, String path, int statusCode,
            String reqHeaders, String reqBody,
            String respHeaders, String respBody
    ) throws SQLException {
        String sql = """
            INSERT INTO traffic
                (id, timestamp, host, port, protocol, method, path, status_code,
                 req_headers, req_body, resp_headers, resp_body, resp_length)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, host);
            ps.setInt(4, port);
            ps.setString(5, secure ? "https" : "http");
            ps.setString(6, method);
            ps.setString(7, path);
            ps.setInt(8, statusCode);
            ps.setString(9, reqHeaders);
            ps.setString(10, reqBody);
            ps.setString(11, respHeaders);
            ps.setString(12, respBody);
            ps.setInt(13, respBody != null ? respBody.length() : 0);
            ps.executeUpdate();
        }
    }

    public synchronized QueryResult query(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();

            String[] columns = new String[cols];
            for (int i = 1; i <= cols; i++) {
                columns[i - 1] = meta.getColumnName(i);
            }

            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                Object[] row = new Object[cols];
                for (int i = 1; i <= cols; i++) {
                    row[i - 1] = rs.getObject(i);
                }
                rows.add(row);
            }

            return new QueryResult(columns, rows);
        }
    }

    public synchronized void execute(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    public record QueryResult(String[] columns, List<Object[]> rows) {
    }
}
