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
        }
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
