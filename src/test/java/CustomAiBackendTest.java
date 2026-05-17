import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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

    // ── endpoint resolution ──────────────────────────────────────────────────

    @Test
    void chatCompletionsUriAppendsEndpointWhenGivenBaseUrl() {
        URI uri = CustomAiBackend.chatCompletionsUri("http://127.0.0.1:4000/v1");
        assertEquals("http://127.0.0.1:4000/v1/chat/completions", uri.toString());
    }

    @Test
    void responsesUriAppendsEndpointWhenGivenBaseUrl() {
        URI uri = CustomAiBackend.responsesUri("http://127.0.0.1:4000/v1");
        assertEquals("http://127.0.0.1:4000/v1/responses", uri.toString());
    }

    @Test
    void completionsUriAppendsEndpointWhenGivenBaseUrl() {
        URI uri = CustomAiBackend.completionsUri("http://127.0.0.1:4000/v1");
        assertEquals("http://127.0.0.1:4000/v1/completions", uri.toString());
    }

    @Test
    void chatCompletionsUriKeepsFullEndpointAsIs() {
        URI uri = CustomAiBackend.chatCompletionsUri("http://127.0.0.1:4000/v1/chat/completions");
        assertEquals("http://127.0.0.1:4000/v1/chat/completions", uri.toString());
    }

    @Test
    void endpointUrisPreserveQueryParameters() {
        URI chatUri = CustomAiBackend.chatCompletionsUri("https://example.test/openai/v1?api-version=preview");
        URI responsesUri = CustomAiBackend.responsesUri("https://example.test/openai/v1?api-version=preview");

        assertEquals("https://example.test/openai/v1/chat/completions?api-version=preview", chatUri.toString());
        assertEquals("https://example.test/openai/v1/responses?api-version=preview", responsesUri.toString());
    }

    @Test
    void preferredEndpointsUsesResponsesFirstForGpt5Models() {
        List<CustomAiBackend.TextEndpoint> endpoints =
                CustomAiBackend.preferredEndpoints("http://127.0.0.1:4000/v1", "gpt-5");
        assertEquals(List.of(
                CustomAiBackend.TextEndpoint.RESPONSES,
                CustomAiBackend.TextEndpoint.CHAT_COMPLETIONS,
                CustomAiBackend.TextEndpoint.COMPLETIONS), endpoints);
    }

    @Test
    void preferredEndpointsUsesCompletionsFirstForLegacyModels() {
        List<CustomAiBackend.TextEndpoint> endpoints =
                CustomAiBackend.preferredEndpoints("http://127.0.0.1:4000/v1", "gpt-3.5-turbo-instruct");
        assertEquals(List.of(
                CustomAiBackend.TextEndpoint.COMPLETIONS,
                CustomAiBackend.TextEndpoint.CHAT_COMPLETIONS,
                CustomAiBackend.TextEndpoint.RESPONSES), endpoints);
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
    void extractContentFromResponsesOutputText() throws Exception {
        String json = """
                {"id":"resp_123","object":"response","output_text":"OK from responses"}
                """;
        assertEquals("OK from responses", CustomAiBackend.extractContent(json));
    }

    @Test
    void extractContentFromResponsesOutputArray() throws Exception {
        String json = """
                {"id":"resp_123","object":"response","output":[
                  {"id":"msg_1","type":"message","role":"assistant","content":[
                    {"type":"output_text","text":"OK from output array"}
                  ]}
                ]}
                """;
        assertEquals("OK from output array", CustomAiBackend.extractContent(json));
    }

    @Test
    void extractContentFromLegacyCompletionResponse() throws Exception {
        String json = """
                {"id":"cmpl_123","object":"text_completion","choices":[
                  {"text":"OK from legacy completions","index":0,"finish_reason":"stop"}
                ]}
                """;
        assertEquals("OK from legacy completions", CustomAiBackend.extractContent(json));
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

    // ── ask / fallback behavior ──────────────────────────────────────────────

    @Test
    void askUsesProvidedFullChatEndpointWithoutDuplicatingPath() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(
                requestPath,
                requestBody,
                new EndpointResponse("/v1/chat/completions", 200,
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}")
        );
        int port = server.getAddress().getPort();

        try {
            CustomAiBackend backend = new CustomAiBackend(
                    "http://127.0.0.1:" + port + "/v1/chat/completions",
                    "",
                    "gpt-4o-mini");

            String reply = backend.ask("system", "user");

            assertEquals("OK", reply);
            assertEquals("/v1/chat/completions", requestPath.get());
            assertTrue(requestBody.get().contains("\"messages\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void askFallsBackFromChatToResponsesWhenBaseUrlIsUsed() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(
                requestPath,
                requestBody,
                new EndpointResponse("/v1/chat/completions", 404, "{\"detail\":\"Not Found\"}"),
                new EndpointResponse("/v1/responses", 200,
                        "{\"output_text\":\"OK from responses\"}")
        );
        int port = server.getAddress().getPort();

        try {
            CustomAiBackend backend = new CustomAiBackend(
                    "http://127.0.0.1:" + port + "/v1",
                    "",
                    "llama3");

            String reply = backend.ask("system", "user");

            assertEquals("OK from responses", reply);
            assertEquals("/v1/responses", requestPath.get());
            assertTrue(requestBody.get().contains("\"input\""));
            assertTrue(requestBody.get().contains("\"instructions\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void askUsesResponsesFirstForGpt5LikeModels() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(
                requestPath,
                requestBody,
                new EndpointResponse("/v1/responses", 200,
                        "{\"output_text\":\"OK from gpt-5 responses\"}")
        );
        int port = server.getAddress().getPort();

        try {
            CustomAiBackend backend = new CustomAiBackend(
                    "http://127.0.0.1:" + port + "/v1",
                    "",
                    "gpt-5");

            String reply = backend.ask("system", "user");

            assertEquals("OK from gpt-5 responses", reply);
            assertEquals("/v1/responses", requestPath.get());
            assertTrue(requestBody.get().contains("\"input\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void askSupportsExplicitResponsesEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(
                requestPath,
                requestBody,
                new EndpointResponse("/v1/responses", 200,
                        "{\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK explicit responses\"}]}]}")
        );
        int port = server.getAddress().getPort();

        try {
            CustomAiBackend backend = new CustomAiBackend(
                    "http://127.0.0.1:" + port + "/v1/responses",
                    "",
                    "gpt-5");

            String reply = backend.ask("system", "user");

            assertEquals("OK explicit responses", reply);
            assertEquals("/v1/responses", requestPath.get());
            assertTrue(requestBody.get().contains("\"instructions\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void askUsesCompletionsFirstForLegacyInstructModels() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(
                requestPath,
                requestBody,
                new EndpointResponse("/v1/completions", 200,
                        "{\"choices\":[{\"text\":\"OK from legacy completions\"}]}")
        );
        int port = server.getAddress().getPort();

        try {
            CustomAiBackend backend = new CustomAiBackend(
                    "http://127.0.0.1:" + port + "/v1",
                    "",
                    "gpt-3.5-turbo-instruct");

            String reply = backend.ask("system", "user");

            assertEquals("OK from legacy completions", reply);
            assertEquals("/v1/completions", requestPath.get());
            assertTrue(requestBody.get().contains("\"prompt\""));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(
            AtomicReference<String> requestPath,
            AtomicReference<String> requestBody,
            EndpointResponse... responses) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (EndpointResponse response : responses) {
            server.createContext(response.path(), exchange -> {
                requestPath.set(exchange.getRequestURI().getPath());
                requestBody.set(readBody(exchange.getRequestBody()));
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.statusCode(), body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
        }
        server.start();
        return server;
    }

    private static String readBody(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private record EndpointResponse(String path, int statusCode, String body) {}
}
