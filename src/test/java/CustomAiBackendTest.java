import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomAiBackendTest {

    // ── isAvailable ───────────────────────────────────────────────────────────

    @Test
    void availableWhenUrlSet() {
        CustomAiBackend b = new CustomAiBackend("https://api.openai.com/v1", "key", "gpt-4o-mini");
        assertTrue(b.isAvailable());
    }

    @Test
    void unavailableWhenUrlBlank() {
        assertFalse(new CustomAiBackend("",   "key", "gpt-4o-mini").isAvailable());
        assertFalse(new CustomAiBackend("  ", "key", "gpt-4o-mini").isAvailable());
        assertFalse(new CustomAiBackend(null, "key", "gpt-4o-mini").isAvailable());
    }

    // ── jsonString ────────────────────────────────────────────────────────────

    @Test
    void jsonStringSimpleValue() {
        assertEquals("\"hello\"", CustomAiBackend.jsonString("hello"));
    }

    @Test
    void jsonStringEscapesDoubleQuote() {
        assertEquals("\"say \\\"hi\\\"\"", CustomAiBackend.jsonString("say \"hi\""));
    }

    @Test
    void jsonStringEscapesBackslash() {
        assertEquals("\"a\\\\b\"", CustomAiBackend.jsonString("a\\b"));
    }

    @Test
    void jsonStringEscapesNewlineAndTab() {
        assertEquals("\"line1\\nline2\"", CustomAiBackend.jsonString("line1\nline2"));
        assertEquals("\"a\\tb\"",         CustomAiBackend.jsonString("a\tb"));
    }

    @Test
    void jsonStringEscapesControlChars() {
        String result = CustomAiBackend.jsonString("\u0001\u001f");
        assertTrue(result.contains("\\u0001"));
        assertTrue(result.contains("\\u001f"));
    }

    @Test
    void jsonStringEmptyString() {
        assertEquals("\"\"", CustomAiBackend.jsonString(""));
    }

    // ── extractContent ────────────────────────────────────────────────────────

    @Test
    void extractContentFromTypicalOpenAiResponse() throws Exception {
        String json = """
                {"id":"chatcmpl-abc","object":"chat.completion","choices":[
                  {"index":0,"message":{"role":"assistant","content":"Hello, pentest!"},
                   "finish_reason":"stop"}],
                  "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                """;
        assertEquals("Hello, pentest!", CustomAiBackend.extractContent(json));
    }

    @Test
    void extractContentWithEscapedCharacters() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"line1\\nline2\\ttabbed\"}}]}";
        assertEquals("line1\nline2\ttabbed", CustomAiBackend.extractContent(json));
    }

    @Test
    void extractContentWithEscapedQuotes() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"He said \\\"hello\\\"\"}}]}";
        assertEquals("He said \"hello\"", CustomAiBackend.extractContent(json));
    }

    @Test
    void extractContentWithUnicodeEscape() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"caf\\u00e9\"}}]}";
        assertEquals("café", CustomAiBackend.extractContent(json));
    }

    @Test
    void extractContentThrowsOnErrorResponse() {
        String json = "{\"error\":{\"message\":\"Invalid API key\",\"type\":\"auth_error\"}}";
        Exception ex = assertThrows(Exception.class, () -> CustomAiBackend.extractContent(json));
        assertTrue(ex.getMessage().contains("Invalid API key"));
    }

    @Test
    void extractContentThrowsWhenNoContent() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}";
        assertThrows(Exception.class, () -> CustomAiBackend.extractContent(json));
    }

    // ── extractString ─────────────────────────────────────────────────────────

    @Test
    void extractStringFindsValue() {
        assertEquals("bar", CustomAiBackend.extractString("{\"foo\":\"bar\"}", "foo"));
    }

    @Test
    void extractStringReturnsEmptyWhenMissing() {
        assertEquals("", CustomAiBackend.extractString("{\"other\":\"value\"}", "foo"));
    }

    @Test
    void extractStringHandlesWhitespaceAfterColon() {
        assertEquals("baz", CustomAiBackend.extractString("{\"k\": \"baz\"}", "k"));
    }
}
