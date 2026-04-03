import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link AiBackend} implementation for any OpenAI-compatible chat completions
 * endpoint — OpenAI, Ollama, LM Studio, LocalAI, etc.
 *
 * Set {@code apiKey} to blank for local/unauthenticated endpoints.
 */
public class CustomAiBackend implements AiBackend {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final String baseUrl;  // e.g. "https://api.openai.com/v1"
    private final String apiKey;   // Bearer token; blank for local endpoints
    private final String model;    // e.g. "gpt-4o-mini", "llama3", …

    public CustomAiBackend(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey  = apiKey  == null ? "" : apiKey;
        this.model   = (model == null || model.isBlank()) ? "gpt-4o-mini" : model;
    }

    @Override
    public boolean isAvailable() {
        return !baseUrl.isBlank();
    }

    @Override
    public String ask(String systemPrompt, String userMessage) throws Exception {
        if (!isAvailable()) throw new IllegalStateException("Custom endpoint URL is not configured.");

        String body = "{\"model\":" + jsonString(model) + "," +
                      "\"messages\":[" +
                        "{\"role\":\"system\",\"content\":" + jsonString(systemPrompt) + "}," +
                        "{\"role\":\"user\",\"content\":" + jsonString(userMessage) + "}" +
                      "]}";

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (!apiKey.isBlank()) req.header("Authorization", "Bearer " + apiKey);

        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 400) {
            String errMsg = extractString(resp.body(), "message");
            throw new Exception("HTTP " + resp.statusCode() + ": " +
                    (errMsg.isEmpty() ? resp.body() : errMsg));
        }

        return extractContent(resp.body());
    }

    // ── JSON helpers (no external dependency) ────────────────────────────────

    /** Serialise a Java string to a JSON string literal including surrounding quotes. */
    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else          sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Extract the assistant's reply from an OpenAI-compatible JSON response.
     * Handles standard error shapes before attempting to read content.
     */
    static String extractContent(String json) throws Exception {
        if (json.contains("\"error\"")) {
            String msg = extractString(json, "message");
            throw new Exception("API error: " + (msg.isEmpty() ? json : msg));
        }
        String content = extractString(json, "content");
        if (content.isEmpty()) throw new Exception("No content in response: " + json);
        return content;
    }

    /**
     * Find the first {@code "key": "value"} pair in {@code json} and return
     * the unescaped string value.  Returns an empty string if not found or if
     * the value is null / not a string.
     */
    static String extractString(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx < 0) return "";

        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return "";

        int i = colonIdx + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return "";
        i++; // skip opening quote

        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char esc = json.charAt(++i);
                switch (esc) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'u'  -> {
                        if (i + 4 < json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> { sb.append('\\'); sb.append(esc); }
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }
}
