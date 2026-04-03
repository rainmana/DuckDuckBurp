import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for extracting actionable content from AI-generated markdown responses.
 */
public class AiResponseParser {

    // Matches ```sql ... ``` and ``` ... ``` blocks (language tag optional)
    private static final Pattern SQL_BLOCK = Pattern.compile(
            "```(?:sql)?[ \t]*\n(.*?)```",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /**
     * Extracts SQL query strings from markdown code blocks in the given text.
     * Blocks without a language tag are included only if they look like SQL.
     */
    public static List<String> extractSqlBlocks(String markdown) {
        List<String> results = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return results;

        Matcher m = SQL_BLOCK.matcher(markdown);
        while (m.find()) {
            String block = m.group(1).strip();
            if (!block.isBlank() && looksLikeSql(block)) {
                results.add(block);
            }
        }
        return results;
    }

    /**
     * Returns {@code true} if the text appears to be a SQL statement rather
     * than bash/python/shell output or plain prose.
     */
    static boolean looksLikeSql(String text) {
        String upper = text.toUpperCase();
        return upper.startsWith("SELECT")
                || upper.startsWith("WITH")
                || upper.startsWith("INSERT")
                || upper.startsWith("UPDATE")
                || upper.startsWith("DELETE")
                || upper.startsWith("CREATE")
                || upper.contains(" FROM ")
                || upper.contains("\nFROM ");
    }

    /**
     * Suggests an existing CannedQueries / sidebar category for the given SQL
     * based on simple keyword heuristics.  Falls back to {@code "AI Generated"}.
     */
    public static String suggestCategory(String sql) {
        String up = sql.toUpperCase();

        if (containsAny(up, "401", "403", "AUTH", "LOGIN", "LOGOUT", "SESSION",
                "CREDENTIAL", "PASSWORD", "BEARER", "SIGN_IN", "REGISTER")) {
            return "Authentication";
        }
        if (containsAny(up, ">=500", ">= 500", "5XX", "SERVER_ERROR",
                "EXCEPTION", "STACK_TRACE")) {
            return "Errors & Leakage";
        }
        if (containsAny(up, "REQ_BODY", "POST", "PUT", "PATCH",
                "PARAM", "BODY_LENGTH", "MULTIPART", "UPLOAD")) {
            return "Input Surface";
        }
        if (containsAny(up, "SCRIPT", "INJECT", "XSS", "SQLI", "TRAVERSAL",
                "SSRF", "REDIRECT", "CSRF", "UNION SELECT", "DROP TABLE",
                "EXEC(", "EVAL(")) {
            return "Security";
        }
        if (containsAny(up, "COUNT(*)", "GROUP BY", "DISTINCT", "HOST",
                "TIMELINE", "STRFTIME", "DATE_TRUNC")) {
            return "Recon";
        }
        return "AI Generated";
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }
}
