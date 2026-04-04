import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DuckDbManagerTest {

    // Empty path → in-memory DuckDB instance (jdbc:duckdb:)
    private DuckDbManager db;

    @BeforeEach
    void setUp() throws Exception {
        db = new DuckDbManager("");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void schemaIsCreatedOnConstruction() throws Exception {
        DuckDbManager.QueryResult result = db.query(
                "SELECT table_name FROM information_schema.tables WHERE table_name = 'traffic'"
        );
        assertEquals(1, result.rows().size(), "traffic table should exist after construction");
        assertEquals("traffic", result.rows().get(0)[0].toString().toLowerCase());
    }

    @Test
    void insertAndQuerySingleRow() throws Exception {
        db.insertTraffic(1L, "example.com", 443, true, "GET", "/foo", 200,
                "{}", "", "{}", "hello");

        DuckDbManager.QueryResult result = db.query("SELECT * FROM traffic");
        assertEquals(1, result.rows().size());

        // Verify column values by name
        int hostIdx = columnIndex(result.columns(), "host");
        int methodIdx = columnIndex(result.columns(), "method");
        int pathIdx = columnIndex(result.columns(), "path");
        int statusIdx = columnIndex(result.columns(), "status_code");
        int protocolIdx = columnIndex(result.columns(), "protocol");

        Object[] row = result.rows().get(0);
        assertEquals("example.com", row[hostIdx]);
        assertEquals("GET", row[methodIdx]);
        assertEquals("/foo", row[pathIdx]);
        assertEquals(200, ((Number) row[statusIdx]).intValue());
        assertEquals("https", row[protocolIdx]);
    }

    @Test
    void httpProtocolStoredForInsecure() throws Exception {
        db.insertTraffic(2L, "plain.com", 80, false, "POST", "/bar", 201,
                "{}", "body", "{}", "");

        DuckDbManager.QueryResult result = db.query("SELECT protocol FROM traffic WHERE id = 2");
        assertEquals("http", result.rows().get(0)[0]);
    }

    @Test
    void duplicateIdIsIgnored() throws Exception {
        db.insertTraffic(42L, "a.com", 80, false, "GET", "/", 200, "{}", "", "{}", "");
        db.insertTraffic(42L, "b.com", 443, true, "POST", "/other", 500, "{}", "", "{}", "");

        DuckDbManager.QueryResult result = db.query("SELECT host FROM traffic WHERE id = 42");
        assertEquals(1, result.rows().size(), "duplicate id should be silently ignored");
        assertEquals("a.com", result.rows().get(0)[0], "original row should be unchanged");
    }

    @Test
    void queryOnEmptyTableReturnsNoRows() throws Exception {
        DuckDbManager.QueryResult result = db.query("SELECT * FROM traffic");
        assertEquals(0, result.rows().size());
        assertTrue(result.columns().length > 0, "columns should still be present for empty result");
    }

    @Test
    void multipleRowsReturnedInOrder() throws Exception {
        db.insertTraffic(10L, "a.com", 80, false, "GET", "/a", 200, "{}", "", "{}", "");
        db.insertTraffic(20L, "b.com", 80, false, "GET", "/b", 404, "{}", "", "{}", "");
        db.insertTraffic(30L, "c.com", 80, false, "GET", "/c", 500, "{}", "", "{}", "");

        DuckDbManager.QueryResult result = db.query(
                "SELECT id FROM traffic ORDER BY id ASC"
        );
        assertEquals(3, result.rows().size());
        assertEquals(10L, ((Number) result.rows().get(0)[0]).longValue());
        assertEquals(20L, ((Number) result.rows().get(1)[0]).longValue());
        assertEquals(30L, ((Number) result.rows().get(2)[0]).longValue());
    }

    @Test
    void queryResultColumnsMatchSchema() throws Exception {
        DuckDbManager.QueryResult result = db.query("SELECT * FROM traffic LIMIT 0");
        List<String> cols = List.of(result.columns());
        assertTrue(cols.contains("id"));
        assertTrue(cols.contains("host"));
        assertTrue(cols.contains("method"));
        assertTrue(cols.contains("path"));
        assertTrue(cols.contains("status_code"));
        assertTrue(cols.contains("protocol"));
        assertTrue(cols.contains("req_headers"));
        assertTrue(cols.contains("resp_headers"));
        assertTrue(cols.contains("resp_length"));
    }

    @Test
    void invalidSqlThrowsSQLException() {
        assertThrows(Exception.class, () -> db.query("THIS IS NOT SQL"));
    }

    @Test
    void closeIsIdempotent() {
        assertDoesNotThrow(() -> {
            db.close();
            db.close();
        });
    }

    @Test
    void executeRunsNonQueryStatement() throws Exception {
        db.execute("CREATE TABLE test_exec (val VARCHAR)");
        DuckDbManager.QueryResult result = db.query(
                "SELECT table_name FROM information_schema.tables WHERE table_name = 'test_exec'"
        );
        assertEquals(1, result.rows().size());
    }

    @Test
    void exportToCsvWritesFile(@TempDir Path tempDir) throws Exception {
        db.insertTraffic(1L, "export.com", 80, false, "GET", "/export", 200, "{}", "", "{}", "body");
        Path csv = tempDir.resolve("export.csv");
        db.execute("COPY (SELECT host, method, path FROM traffic) TO '" + csv + "' (FORMAT CSV, HEADER true)");
        assertTrue(Files.exists(csv), "CSV file should be created");
        String content = Files.readString(csv);
        assertTrue(content.contains("export.com"));
        assertTrue(content.contains("host"), "CSV should have header row");
    }

    @Test
    void exportToJsonWritesFile(@TempDir Path tempDir) throws Exception {
        db.insertTraffic(2L, "json.com", 443, true, "POST", "/api", 201, "{}", "{}", "{}", "");
        Path json = tempDir.resolve("export.json");
        db.execute("COPY (SELECT host, method FROM traffic) TO '" + json + "' (FORMAT JSON)");
        assertTrue(Files.exists(json));
        String content = Files.readString(json);
        assertTrue(content.contains("json.com"));
    }

    @Test
    void executeWithInvalidSqlThrows() {
        assertThrows(Exception.class, () -> db.execute("NOT VALID SQL AT ALL"));
    }

    // ── Saved queries ─────────────────────────────────────────────────────────

    @Test
    void saveQueryPersistsRow() throws Exception {
        db.saveQuery("Testing", "My Query", "SELECT 1");
        List<DuckDbManager.SavedQuery> saved = db.loadSavedQueries();
        assertEquals(1, saved.size());
        assertEquals("Testing",  saved.get(0).category());
        assertEquals("My Query", saved.get(0).name());
        assertEquals("SELECT 1", saved.get(0).sql());
    }

    @Test
    void loadSavedQueriesOrderedByCategoryThenName() throws Exception {
        db.saveQuery("Zeta", "Z Query",  "SELECT 3");
        db.saveQuery("Alpha", "B Query", "SELECT 2");
        db.saveQuery("Alpha", "A Query", "SELECT 1");

        List<DuckDbManager.SavedQuery> saved = db.loadSavedQueries();
        assertEquals(3, saved.size());
        assertEquals("Alpha", saved.get(0).category());
        assertEquals("A Query", saved.get(0).name());
        assertEquals("Alpha", saved.get(1).category());
        assertEquals("B Query", saved.get(1).name());
        assertEquals("Zeta", saved.get(2).category());
    }

    @Test
    void deleteSavedQueryRemovesOnlyThatRow() throws Exception {
        db.saveQuery("Cat", "Keep",   "SELECT 1");
        db.saveQuery("Cat", "Delete", "SELECT 2");

        List<DuckDbManager.SavedQuery> before = db.loadSavedQueries();
        assertEquals(2, before.size());
        long deleteId = before.stream()
                .filter(q -> q.name().equals("Delete"))
                .findFirst().orElseThrow().id();

        db.deleteSavedQuery(deleteId);

        List<DuckDbManager.SavedQuery> after = db.loadSavedQueries();
        assertEquals(1, after.size());
        assertEquals("Keep", after.get(0).name());
    }

    @Test
    void savedQueryIdsAreUniqueAcrossInserts() throws Exception {
        db.saveQuery("Cat", "Q1", "SELECT 1");
        db.saveQuery("Cat", "Q2", "SELECT 2");
        db.saveQuery("Cat", "Q3", "SELECT 3");

        List<DuckDbManager.SavedQuery> saved = db.loadSavedQueries();
        long distinctIds = saved.stream().map(DuckDbManager.SavedQuery::id).distinct().count();
        assertEquals(3, distinctIds, "each saved query should have a unique id");
    }

    @Test
    void exportAndImportSavedQueriesRoundTrip(@TempDir Path tempDir) throws Exception {
        db.saveQuery("Recon", "All Hosts",  "SELECT host FROM traffic GROUP BY host");
        db.saveQuery("Auth",  "Auth Fails", "SELECT * FROM traffic WHERE status_code = 401");

        Path file = tempDir.resolve("queries.json");
        db.exportSavedQueries(file.toString());
        assertTrue(Files.exists(file), "export file should be created");

        // Import into a fresh DB
        DuckDbManager db2 = new DuckDbManager("");
        db2.importSavedQueries(file.toString());
        List<DuckDbManager.SavedQuery> imported = db2.loadSavedQueries();
        db2.close();

        assertEquals(2, imported.size());
        assertTrue(imported.stream().anyMatch(q -> q.name().equals("All Hosts")));
        assertTrue(imported.stream().anyMatch(q -> q.name().equals("Auth Fails")));
    }

    // --- helpers ---

    private int columnIndex(String[] columns, String name) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equalsIgnoreCase(name)) return i;
        }
        throw new AssertionError("Column not found: " + name);
    }
}
