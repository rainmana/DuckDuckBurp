import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that each DashboardTab SQL constant returns correct results when
 * executed against a seeded in-memory DuckDB instance.
 */
class DashboardQueriesTest {

    private DuckDbManager db;

    @BeforeEach
    void setUp() throws Exception {
        db = new DuckDbManager("");
        seedTraffic();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    // ── Overview ─────────────────────────────────────────────────────────────

    @Test
    void overviewCountsAllRowsAndDistinctHosts() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_OVERVIEW);
        assertEquals(1, r.rows().size());
        assertEquals(10L, ((Number) r.rows().get(0)[0]).longValue(), "total_requests");
        assertEquals(3L,  ((Number) r.rows().get(0)[1]).longValue(), "unique_hosts");
    }

    // ── Auth Failures ─────────────────────────────────────────────────────────

    @Test
    void authFailuresReturnsOnly401And403() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_AUTH_FAILURES);
        assertFalse(r.rows().isEmpty(), "should find auth failures");
        // All returned rows must have come from 401/403 traffic — verified by
        // checking that no 200/500 paths sneak in
        for (Object[] row : r.rows()) {
            String path = row[0].toString();
            assertTrue(path.equals("/secure") || path.equals("/admin"),
                    "unexpected path in auth failures: " + path);
        }
    }

    @Test
    void authFailuresOrderedByHitsDesc() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_AUTH_FAILURES);
        assertTrue(r.rows().size() >= 2);
        long first  = ((Number) r.rows().get(0)[1]).longValue();
        long second = ((Number) r.rows().get(1)[1]).longValue();
        assertTrue(first >= second, "rows should be ordered by hits DESC");
    }

    // ── Server Errors ─────────────────────────────────────────────────────────

    @Test
    void serverErrorsReturnsOnly5xx() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_SERVER_ERRORS);
        assertFalse(r.rows().isEmpty(), "should find server errors");
        int statusIdx = colIndex(r.columns(), "status_code");
        for (Object[] row : r.rows()) {
            int code = ((Number) row[statusIdx]).intValue();
            assertTrue(code >= 500, "non-5xx row returned: " + code);
        }
    }

    // ── Attack Surface ────────────────────────────────────────────────────────

    @Test
    void attackSurfaceExcludesGetHeadOptions() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_ATTACK_SURFACE);
        assertFalse(r.rows().isEmpty(), "should find non-GET endpoints");
        int methodIdx = colIndex(r.columns(), "method");
        for (Object[] row : r.rows()) {
            String method = row[methodIdx].toString();
            assertNotEquals("GET",     method);
            assertNotEquals("HEAD",    method);
            assertNotEquals("OPTIONS", method);
        }
    }

    @Test
    void attackSurfaceIncludesPostAndPut() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_ATTACK_SURFACE);
        int methodIdx = colIndex(r.columns(), "method");
        boolean hasPost = r.rows().stream().anyMatch(row -> "POST".equals(row[methodIdx].toString()));
        boolean hasPut  = r.rows().stream().anyMatch(row -> "PUT".equals(row[methodIdx].toString()));
        assertTrue(hasPost, "POST should appear in attack surface");
        assertTrue(hasPut,  "PUT should appear in attack surface");
    }

    // ── Largest Responses ─────────────────────────────────────────────────────

    @Test
    void largestResponsesOrderedByRespLengthDesc() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_LARGEST_RESPONSES);
        assertFalse(r.rows().isEmpty());
        int lenIdx = colIndex(r.columns(), "resp_length");
        long prev = Long.MAX_VALUE;
        for (Object[] row : r.rows()) {
            long len = ((Number) row[lenIdx]).longValue();
            assertTrue(len <= prev, "rows not ordered by resp_length DESC");
            prev = len;
        }
    }

    // ── Interesting Paths ─────────────────────────────────────────────────────

    @Test
    void interestingPathsFindsAdminAndDebug() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_INTERESTING_PATHS);
        assertFalse(r.rows().isEmpty(), "should find interesting paths");
        int pathIdx = colIndex(r.columns(), "path");
        boolean foundAdmin = r.rows().stream().anyMatch(row -> row[pathIdx].toString().contains("admin"));
        boolean foundDebug = r.rows().stream().anyMatch(row -> row[pathIdx].toString().contains("debug"));
        assertTrue(foundAdmin, "/admin path should appear");
        assertTrue(foundDebug, "/debug path should appear");
    }

    @Test
    void interestingPathsExcludesOrdinaryPaths() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_INTERESTING_PATHS);
        int pathIdx = colIndex(r.columns(), "path");
        for (Object[] row : r.rows()) {
            String path = row[pathIdx].toString();
            assertFalse(path.equals("/index.html") || path.equals("/style.css"),
                    "ordinary path should not appear: " + path);
        }
    }

    // ── Hosts Seen ────────────────────────────────────────────────────────────

    @Test
    void hostsSeenGroupsByHost() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_HOSTS_SEEN);
        assertEquals(3, r.rows().size(), "should have one row per distinct host");
    }

    @Test
    void hostsSeenCountsErrorsCorrectly() throws Exception {
        DuckDbManager.QueryResult r = db.query(DashboardTab.SQL_HOSTS_SEEN);
        int hostIdx   = colIndex(r.columns(), "host");
        int errorIdx  = colIndex(r.columns(), "errors");
        // api.example.com has one 401, one 500 → 2 errors
        r.rows().stream()
                .filter(row -> "api.example.com".equals(row[hostIdx].toString()))
                .findFirst()
                .ifPresent(row -> assertEquals(2L, ((Number) row[errorIdx]).longValue()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Seeds 10 rows across 3 hosts covering every scenario each query cares about.
     *
     * <pre>
     * id  host               method  path              status  resp_length
     *  1  example.com        GET     /index.html       200     1000
     *  2  example.com        GET     /style.css        200     500
     *  3  example.com        GET     /admin            401     47     ← auth failure
     *  4  example.com        GET     /admin            401     47     ← auth failure (2nd hit)
     *  5  example.com        POST    /api/login        200     300    ← attack surface
     *  6  example.com        PUT     /api/user/1       204     0      ← attack surface
     *  7  api.example.com    GET     /secure           403     80     ← auth failure
     *  8  api.example.com    GET     /crash            500     120    ← server error
     *  9  api.example.com    GET     /debug            200     999999 ← interesting + largest
     * 10  other.host.com     GET     /normal           200     200
     * </pre>
     */
    private void seedTraffic() throws Exception {
        insert(1,  "example.com",     80,  false, "GET",  "/index.html",  200, 1000);
        insert(2,  "example.com",     80,  false, "GET",  "/style.css",   200,  500);
        insert(3,  "example.com",     80,  false, "GET",  "/admin",       401,   47);
        insert(4,  "example.com",     80,  false, "GET",  "/admin",       401,   47);
        insert(5,  "example.com",     80,  false, "POST", "/api/login",   200,  300);
        insert(6,  "example.com",     80,  false, "PUT",  "/api/user/1",  204,    0);
        insert(7,  "api.example.com", 443, true,  "GET",  "/secure",      403,   80);
        insert(8,  "api.example.com", 443, true,  "GET",  "/crash",       500,  120);
        insert(9,  "api.example.com", 443, true,  "GET",  "/debug",       200, 999999);
        insert(10, "other.host.com",  80,  false, "GET",  "/normal",      200,  200);
    }

    private void insert(long id, String host, int port, boolean secure,
                        String method, String path, int status, int respLen) throws Exception {
        // Use a body string whose length matches respLen for simplicity
        String body = "x".repeat(respLen);
        db.insertTraffic(id, host, port, secure, method, path, status,
                "{}", "", "{}", body);
    }

    private int colIndex(String[] columns, String name) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equalsIgnoreCase(name)) return i;
        }
        throw new AssertionError("Column not found: " + name);
    }
}
