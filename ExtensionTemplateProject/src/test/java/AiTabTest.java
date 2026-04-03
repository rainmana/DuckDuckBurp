import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiTabTest {

    // ── AiBackend contract ────────────────────────────────────────────────────

    @Test
    void availableBackendReportsTrue() {
        AiBackend backend = new AiBackend() {
            @Override public boolean isAvailable() { return true; }
            @Override public String ask(String sys, String user) { return "ok"; }
        };
        assertTrue(backend.isAvailable());
    }

    @Test
    void unavailableBackendReportsFalse() {
        AiBackend backend = new AiBackend() {
            @Override public boolean isAvailable() { return false; }
            @Override public String ask(String sys, String user) { return ""; }
        };
        assertFalse(backend.isAvailable());
    }

    @Test
    void mockBackendReceivesCorrectArguments() throws Exception {
        List<String[]> calls = new ArrayList<>();
        AiBackend backend = new AiBackend() {
            @Override public boolean isAvailable() { return true; }
            @Override public String ask(String sys, String user) throws Exception {
                calls.add(new String[]{sys, user});
                return "Mock response";
            }
        };

        String response = backend.ask("system context", "user question");
        assertEquals("Mock response", response);
        assertEquals(1, calls.size());
        assertEquals("system context", calls.get(0)[0]);
        assertEquals("user question",  calls.get(0)[1]);
    }

    @Test
    void supplierIsCalledFreshOnEachAsk() throws Exception {
        // Simulates settings changing between asks
        AiBackend[] current = { new AiBackend() {
            @Override public boolean isAvailable() { return true; }
            @Override public String ask(String s, String u) { return "first"; }
        }};
        java.util.function.Supplier<AiBackend> supplier = () -> current[0];

        assertEquals("first", supplier.get().ask("s", "u"));

        current[0] = new AiBackend() {
            @Override public boolean isAvailable() { return true; }
            @Override public String ask(String s, String u) { return "second"; }
        };
        assertEquals("second", supplier.get().ask("s", "u"));
    }

    @Test
    void backendThrowsOnError() {
        AiBackend erroring = new AiBackend() {
            @Override public boolean isAvailable() { return true; }
            @Override public String ask(String sys, String user) throws Exception {
                throw new Exception("quota exceeded");
            }
        };
        Exception ex = assertThrows(Exception.class, () -> erroring.ask("sys", "user"));
        assertEquals("quota exceeded", ex.getMessage());
    }

    // ── buildTrafficSummary ───────────────────────────────────────────────────

    @Test
    void summaryOnEmptyDbReturnsNoCapturedMessage() throws Exception {
        DuckDbManager db = new DuckDbManager("");
        try {
            String summary = AiTab.buildTrafficSummary(db);
            assertEquals("No traffic captured yet.", summary);
        } finally {
            db.close();
        }
    }

    @Test
    void summaryContainsTotalAndHosts() throws Exception {
        DuckDbManager db = new DuckDbManager("");
        try {
            db.insertTraffic(1L, "alpha.com",  80,  false, "GET",  "/a",   200, "{}", "", "{}", "body");
            db.insertTraffic(2L, "beta.com",   443, true,  "POST", "/b",   302, "{}", "x", "{}", "");
            db.insertTraffic(3L, "alpha.com",  80,  false, "GET",  "/c",   404, "{}", "", "{}", "");

            String summary = AiTab.buildTrafficSummary(db);

            assertTrue(summary.contains("3 reqs"),   "should show total request count");
            assertTrue(summary.contains("2 hosts"),  "should show unique host count");
            assertTrue(summary.contains("alpha.com"), "should list alpha.com");
            assertTrue(summary.contains("beta.com"),  "should list beta.com");
        } finally {
            db.close();
        }
    }

    @Test
    void summaryContainsStatusCodeDistribution() throws Exception {
        DuckDbManager db = new DuckDbManager("");
        try {
            db.insertTraffic(1L, "x.com", 80, false, "GET", "/ok",  200, "{}", "", "{}", "");
            db.insertTraffic(2L, "x.com", 80, false, "GET", "/bad", 401, "{}", "", "{}", "");
            db.insertTraffic(3L, "x.com", 80, false, "GET", "/err", 500, "{}", "", "{}", "");

            String summary = AiTab.buildTrafficSummary(db);
            assertTrue(summary.contains("200"), "should show status 200");
            assertTrue(summary.contains("401"), "should show status 401");
            assertTrue(summary.contains("500"), "should show status 500");
        } finally {
            db.close();
        }
    }

    @Test
    void summaryReportsAuthFailures() throws Exception {
        DuckDbManager db = new DuckDbManager("");
        try {
            db.insertTraffic(1L, "x.com", 80, false, "GET", "/admin",  401, "{}", "", "{}", "");
            db.insertTraffic(2L, "x.com", 80, false, "GET", "/secret", 403, "{}", "", "{}", "");
            db.insertTraffic(3L, "x.com", 80, false, "GET", "/open",   200, "{}", "", "{}", "body");

            String summary = AiTab.buildTrafficSummary(db);
            assertTrue(summary.contains("2 auth_fails"),
                    "should report both 401 and 403 as auth failures");
        } finally {
            db.close();
        }
    }

    @Test
    void summaryReportsServerErrors() throws Exception {
        DuckDbManager db = new DuckDbManager("");
        try {
            db.insertTraffic(1L, "x.com", 80, false, "GET", "/crash", 500, "{}", "", "{}", "");
            db.insertTraffic(2L, "x.com", 80, false, "GET", "/gate",  502, "{}", "", "{}", "");
            db.insertTraffic(3L, "x.com", 80, false, "GET", "/ok",    200, "{}", "", "{}", "body");

            String summary = AiTab.buildTrafficSummary(db);
            assertTrue(summary.contains("2 server_errs"),
                    "should report 5xx count");
        } finally {
            db.close();
        }
    }

    @Test
    void summaryContainsMethodDistribution() throws Exception {
        DuckDbManager db = new DuckDbManager("");
        try {
            db.insertTraffic(1L, "x.com", 80, false, "GET",    "/",    200, "{}", "", "{}", "body");
            db.insertTraffic(2L, "x.com", 80, false, "POST",   "/api", 201, "{}", "d", "{}", "");
            db.insertTraffic(3L, "x.com", 80, false, "DELETE", "/r/1", 204, "{}", "", "{}", "");

            String summary = AiTab.buildTrafficSummary(db);
            assertTrue(summary.contains("GET"),    "should include GET method");
            assertTrue(summary.contains("POST"),   "should include POST method");
            assertTrue(summary.contains("DELETE"), "should include DELETE method");
        } finally {
            db.close();
        }
    }

    @Test
    void summaryContainsRecentRequests() throws Exception {
        DuckDbManager db = new DuckDbManager("");
        try {
            db.insertTraffic(1L, "x.com", 80, false, "GET", "/specific-path", 200, "{}", "", "{}", "body");

            String summary = AiTab.buildTrafficSummary(db);
            assertTrue(summary.contains("/specific-path"), "recent requests should appear in summary");
        } finally {
            db.close();
        }
    }
}
