import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiResponseParserTest {

    // ── extractSqlBlocks ──────────────────────────────────────────────────────

    @Test
    void extractsTaggedSqlBlock() {
        String md = "Here is a query:\n```sql\nSELECT * FROM traffic\n```\nDone.";
        List<String> blocks = AiResponseParser.extractSqlBlocks(md);
        assertEquals(1, blocks.size());
        assertEquals("SELECT * FROM traffic", blocks.get(0));
    }

    @Test
    void extractsUntaggedBlockThatLooksSql() {
        String md = "Try this:\n```\nSELECT host FROM traffic LIMIT 10\n```";
        List<String> blocks = AiResponseParser.extractSqlBlocks(md);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).contains("SELECT"));
    }

    @Test
    void skipsUntaggedBlockThatDoesNotLookSql() {
        String md = "Run this:\n```\ncurl -X GET https://example.com\n```";
        List<String> blocks = AiResponseParser.extractSqlBlocks(md);
        assertEquals(0, blocks.size());
    }

    @Test
    void extractsMultipleBlocks() {
        String md = """
                Query 1:
                ```sql
                SELECT host FROM traffic
                ```
                Query 2:
                ```sql
                SELECT path FROM traffic WHERE status_code = 401
                ```
                """;
        List<String> blocks = AiResponseParser.extractSqlBlocks(md);
        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0).contains("host"));
        assertTrue(blocks.get(1).contains("401"));
    }

    @Test
    void extractsMultilineQuery() {
        String md = "```sql\nSELECT host, COUNT(*) AS cnt\nFROM traffic\nGROUP BY host\n```";
        List<String> blocks = AiResponseParser.extractSqlBlocks(md);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).contains("GROUP BY"));
    }

    @Test
    void returnsEmptyListForNullInput() {
        assertTrue(AiResponseParser.extractSqlBlocks(null).isEmpty());
    }

    @Test
    void returnsEmptyListForNoBlocks() {
        assertTrue(AiResponseParser.extractSqlBlocks("Just plain text here.").isEmpty());
    }

    @Test
    void stripsLeadingTrailingWhitespace() {
        String md = "```sql\n\n  SELECT 1  \n\n```";
        List<String> blocks = AiResponseParser.extractSqlBlocks(md);
        assertEquals(1, blocks.size());
        assertEquals("SELECT 1", blocks.get(0));
    }

    @Test
    void caseInsensitiveSqlTag() {
        String md = "```SQL\nSELECT 1 FROM traffic\n```";
        List<String> blocks = AiResponseParser.extractSqlBlocks(md);
        assertEquals(1, blocks.size());
    }

    // ── looksLikeSql ──────────────────────────────────────────────────────────

    @Test
    void selectStartIsSql() {
        assertTrue(AiResponseParser.looksLikeSql("SELECT host FROM traffic"));
    }

    @Test
    void withClauseIsSql() {
        assertTrue(AiResponseParser.looksLikeSql("WITH cte AS (SELECT 1) SELECT * FROM cte"));
    }

    @Test
    void curlCommandIsNotSql() {
        assertFalse(AiResponseParser.looksLikeSql("curl -X GET https://example.com"));
    }

    @Test
    void pythonCodeIsNotSql() {
        assertFalse(AiResponseParser.looksLikeSql("print('hello')"));
    }

    @Test
    void fromKeywordInMiddleIsSql() {
        assertTrue(AiResponseParser.looksLikeSql("SELECT * FROM traffic WHERE id = 1"));
    }

    // ── suggestCategory ───────────────────────────────────────────────────────

    @Test
    void authCategoryForStatusCode401() {
        assertEquals("Authentication",
                AiResponseParser.suggestCategory("SELECT * FROM traffic WHERE status_code = 401"));
    }

    @Test
    void authCategoryForLoginKeyword() {
        assertEquals("Authentication",
                AiResponseParser.suggestCategory("SELECT * FROM traffic WHERE path LIKE '%login%'"));
    }

    @Test
    void errorCategoryForServerErrors() {
        assertEquals("Errors & Leakage",
                AiResponseParser.suggestCategory("SELECT * FROM traffic WHERE status_code >= 500"));
    }

    @Test
    void inputSurfaceCategoryForPostMethod() {
        assertEquals("Input Surface",
                AiResponseParser.suggestCategory(
                        "SELECT * FROM traffic WHERE method = 'POST' AND req_body IS NOT NULL"));
    }

    @Test
    void reconCategoryForCountGroupBy() {
        assertEquals("Recon",
                AiResponseParser.suggestCategory(
                        "SELECT host, COUNT(*) FROM traffic GROUP BY host ORDER BY 2 DESC"));
    }

    @Test
    void aiGeneratedFallback() {
        assertEquals("AI Generated",
                AiResponseParser.suggestCategory("SELECT id FROM traffic LIMIT 1"));
    }

    @Test
    void securityCategoryForInjection() {
        assertEquals("Security",
                AiResponseParser.suggestCategory(
                        "SELECT * FROM traffic WHERE path LIKE '%UNION SELECT%'"));
    }
}
