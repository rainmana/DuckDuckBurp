import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CannedQueriesTest {

    private static DuckDbManager db;

    @BeforeAll
    static void setUpDb() throws Exception {
        db = new DuckDbManager("");
        // Seed a handful of rows so queries that filter/group have something to work with
        db.insertTraffic(1L,  "example.com",     80,  false, "GET",    "/index.html",   200, "{}", "",         "{}", "body");
        db.insertTraffic(2L,  "example.com",     80,  false, "POST",   "/api/login",    200, "{\"Content-Type\":\"application/json\"}", "{\"user\":\"a\"}", "{}", "ok");
        db.insertTraffic(3L,  "example.com",     80,  false, "GET",    "/admin",        401, "{}", "",         "{}", "");
        db.insertTraffic(4L,  "example.com",     80,  false, "GET",    "/admin",        200, "{}", "",         "{}", "secret");
        db.insertTraffic(5L,  "api.example.com", 443, true,  "PUT",    "/api/user/42",  204, "{}", "data",     "{}", "");
        db.insertTraffic(6L,  "api.example.com", 443, true,  "GET",    "/crash",        500, "{}", "",         "{}", "err");
        db.insertTraffic(7L,  "api.example.com", 443, true,  "GET",    "/debug",        200, "{}", "",         "{}", "x".repeat(50000));
        db.insertTraffic(8L,  "api.example.com", 443, true,  "GET",    "/login",        302, "{}", "",         "{}", "");
        db.insertTraffic(9L,  "other.com",       80,  false, "DELETE", "/items/7",      204, "{\"Authorization\":\"Bearer tok\"}", "", "{}", "");
        db.insertTraffic(10L, "other.com",       80,  false, "GET",    "/page?q=test",  200, "{}", "",         "{}", "html");
    }

    @AfterAll
    static void tearDownDb() {
        db.close();
    }

    // ── Structure checks ──────────────────────────────────────────────────────

    @Test
    void allCategoriesHaveAtLeastOneQuery() {
        for (CannedQueries.Category cat : CannedQueries.ALL) {
            assertFalse(cat.queries().isEmpty(), cat.name() + " has no queries");
        }
    }

    @Test
    void noQueryHasBlankNameOrSql() {
        for (CannedQueries.Category cat : CannedQueries.ALL) {
            for (CannedQueries.Query q : cat.queries()) {
                assertFalse(q.name().isBlank(), "blank name in " + cat.name());
                assertFalse(q.sql().isBlank(),  "blank SQL for " + q.name());
            }
        }
    }

    @Test
    void queryToStringReturnsName() {
        CannedQueries.Query q = CannedQueries.ALL.get(0).queries().get(0);
        assertEquals(q.name(), q.toString());
    }

    // ── SQL execution checks (parameterized) ─────────────────────────────────

    static Stream<CannedQueries.Query> allQueries() {
        return CannedQueries.ALL.stream()
                .flatMap(cat -> cat.queries().stream());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allQueries")
    void everySqlExecutesWithoutError(CannedQueries.Query query) {
        assertDoesNotThrow(
                () -> db.query(query.sql()),
                "Query failed: " + query.name()
        );
    }

    // ── Spot-checks on specific query correctness ─────────────────────────────

    @Test
    void authFailuresQueryReturnsRow3() throws Exception {
        CannedQueries.Query q = findQuery("All Auth Failures (401/403)");
        DuckDbManager.QueryResult r = db.query(q.sql());
        assertTrue(r.rows().stream().anyMatch(row -> {
            for (Object o : row) if ("/admin".equals(String.valueOf(o))) return true;
            return false;
        }), "Expected /admin (401) in auth failures");
    }

    @Test
    void mixedAuthQueryFindsAdminPath() throws Exception {
        CannedQueries.Query q = findQuery("Paths with Mixed Auth (bypass?)");
        DuckDbManager.QueryResult r = db.query(q.sql());
        // /admin has both 200 (id=4) and 401 (id=3)
        assertFalse(r.rows().isEmpty(), "Expected /admin to show as mixed-auth path");
        assertTrue(r.rows().stream().anyMatch(row -> "/admin".equals(String.valueOf(row[0]))));
    }

    @Test
    void nonGetQueryExcludesGet() throws Exception {
        CannedQueries.Query q = findQuery("Non-GET Endpoints");
        DuckDbManager.QueryResult r = db.query(q.sql());
        int methodIdx = colIndex(r.columns(), "method");
        for (Object[] row : r.rows()) {
            assertNotEquals("GET", row[methodIdx].toString());
        }
    }

    @Test
    void largestResponsesOrderedDesc() throws Exception {
        CannedQueries.Query q = findQuery("Largest Responses (top 20)");
        DuckDbManager.QueryResult r = db.query(q.sql());
        int lenIdx = colIndex(r.columns(), "resp_length");
        long prev = Long.MAX_VALUE;
        for (Object[] row : r.rows()) {
            long len = ((Number) row[lenIdx]).longValue();
            assertTrue(len <= prev, "Not ordered DESC by resp_length");
            prev = len;
        }
    }

    @Test
    void numericIdPatternNormalisesIds() throws Exception {
        CannedQueries.Query q = findQuery("Numeric ID Patterns (IDOR?)");
        DuckDbManager.QueryResult r = db.query(q.sql());
        int patternIdx = colIndex(r.columns(), "path_pattern");
        // /api/user/42 and /items/7 should appear normalised
        boolean found = r.rows().stream()
                .anyMatch(row -> row[patternIdx].toString().contains("{id}"));
        assertTrue(found, "Expected normalised {id} pattern");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CannedQueries.Query findQuery(String name) {
        return CannedQueries.ALL.stream()
                .flatMap(c -> c.queries().stream())
                .filter(q -> q.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Query not found: " + name));
    }

    private int colIndex(String[] columns, String name) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equalsIgnoreCase(name)) return i;
        }
        throw new AssertionError("Column not found: " + name);
    }
}
