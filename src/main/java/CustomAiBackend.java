import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link AiBackend} implementation for OpenAI-compatible text-generation
 * endpoints — OpenAI, LiteLLM, Ollama, LM Studio, LocalAI, etc.
 *
 * Set {@code apiKey} to blank for local/unauthenticated endpoints.
 */
public class CustomAiBackend implements AiBackend {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;  // e.g. "https://api.openai.com/v1" or a full text endpoint
    private final String apiKey;   // Bearer token; blank for local endpoints
    private final String model;    // e.g. "gpt-4o-mini", "llama3", …

    public CustomAiBackend(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
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

        List<TextEndpoint> endpoints = preferredEndpoints(baseUrl, model);
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < endpoints.size(); i++) {
            TextEndpoint endpoint = endpoints.get(i);
            URI uri = endpointUri(baseUrl, endpoint);
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(endpoint, systemPrompt, userMessage)));

            if (!apiKey.isBlank()) req.header("Authorization", "Bearer " + apiKey);

            HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                String err = formatHttpError(resp.statusCode(), resp.body());
                if (i + 1 < endpoints.size() && shouldTryNextEndpoint(resp.statusCode())) {
                    failures.add(endpoint.label + " -> " + err);
                    continue;
                }
                throw new Exception(err);
            }

            try {
                return extractContent(resp.body());
            } catch (Exception ex) {
                if (i + 1 < endpoints.size()) {
                    failures.add(endpoint.label + " -> " + ex.getMessage());
                    continue;
                }
                throw ex;
            }
        }

        throw new Exception("Unable to extract a text response from the configured endpoint. Tried: "
                + String.join(" | ", failures));
    }

    /**
     * Accept either an API base URL (for example {@code .../v1}) or a full
     * OpenAI-compatible text endpoint such as {@code .../v1/chat/completions},
     * {@code .../v1/responses}, or {@code .../v1/completions}.
     */
    static URI chatCompletionsUri(String configuredUrl) {
        return endpointUri(configuredUrl, TextEndpoint.CHAT_COMPLETIONS);
    }

    static URI responsesUri(String configuredUrl) {
        return endpointUri(configuredUrl, TextEndpoint.RESPONSES);
    }

    static URI completionsUri(String configuredUrl) {
        return endpointUri(configuredUrl, TextEndpoint.COMPLETIONS);
    }

    static List<TextEndpoint> preferredEndpoints(String configuredUrl, String model) {
        String trimmed = configuredUrl == null ? "" : configuredUrl.strip();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Custom endpoint URL is not configured.");
        }

        TextEndpoint explicit = explicitEndpoint(trimmed);
        if (explicit != null) return List.of(explicit);

        String lowerModel = model == null ? "" : model.strip().toLowerCase(Locale.ROOT);
        if (looksLikeLegacyCompletionModel(lowerModel)) {
            return List.of(TextEndpoint.COMPLETIONS, TextEndpoint.CHAT_COMPLETIONS, TextEndpoint.RESPONSES);
        }
        if (looksLikeResponsesFirstModel(lowerModel)) {
            return List.of(TextEndpoint.RESPONSES, TextEndpoint.CHAT_COMPLETIONS, TextEndpoint.COMPLETIONS);
        }
        return List.of(TextEndpoint.CHAT_COMPLETIONS, TextEndpoint.RESPONSES, TextEndpoint.COMPLETIONS);
    }

    static URI endpointUri(String configuredUrl, TextEndpoint endpoint) {
        String trimmed = configuredUrl == null ? "" : configuredUrl.strip();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Custom endpoint URL is not configured.");
        }

        URI configured = URI.create(trimmed);
        String path = configured.getPath() == null ? "" : configured.getPath();
        String normalizedPath = stripKnownEndpointSuffix(path.replaceAll("/+$", ""));
        normalizedPath = normalizedPath + endpoint.pathSuffix;

        return URI.create(configured.getScheme() + "://" +
                configured.getRawAuthority() +
                normalizedPath +
                (configured.getRawQuery() == null ? "" : "?" + configured.getRawQuery()) +
                (configured.getRawFragment() == null ? "" : "#" + configured.getRawFragment()));
    }

    private static boolean looksLikeResponsesFirstModel(String lowerModel) {
        return lowerModel.startsWith("gpt-5")
                || lowerModel.startsWith("o1")
                || lowerModel.startsWith("o3")
                || lowerModel.startsWith("o4")
                || lowerModel.contains("reason");
    }

    private static boolean looksLikeLegacyCompletionModel(String lowerModel) {
        return lowerModel.contains("instruct")
                || lowerModel.startsWith("text-")
                || lowerModel.startsWith("davinci")
                || lowerModel.startsWith("babbage");
    }

    private static TextEndpoint explicitEndpoint(String configuredUrl) {
        String path = URI.create(configuredUrl).getPath();
        String normalizedPath = path == null ? "" : path.replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        if (normalizedPath.endsWith(TextEndpoint.CHAT_COMPLETIONS.pathSuffix)) return TextEndpoint.CHAT_COMPLETIONS;
        if (normalizedPath.endsWith(TextEndpoint.RESPONSES.pathSuffix)) return TextEndpoint.RESPONSES;
        if (normalizedPath.endsWith(TextEndpoint.COMPLETIONS.pathSuffix)) return TextEndpoint.COMPLETIONS;
        return null;
    }

    private static String stripKnownEndpointSuffix(String path) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        for (TextEndpoint endpoint : TextEndpoint.values()) {
            if (lowerPath.endsWith(endpoint.pathSuffix)) {
                return path.substring(0, path.length() - endpoint.pathSuffix.length());
            }
        }
        return path;
    }

    private static boolean shouldTryNextEndpoint(int statusCode) {
        return statusCode == 400 || statusCode == 404 || statusCode == 405 || statusCode == 422;
    }

    private static String formatHttpError(int statusCode, String body) {
        String message = extractErrorMessage(body);
        return "HTTP " + statusCode + ": " + message;
    }

    private static String extractErrorMessage(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            String message = firstNonBlank(
                    nodeText(root.path("error").path("message")),
                    nodeText(root.path("message")),
                    nodeText(root.path("detail")),
                    nodeText(root.path("error"))
            );
            if (!message.isBlank()) return message;
        } catch (Exception ignored) {
            // Fall back to the raw response body below.
        }
        return body == null || body.isBlank() ? "(empty response body)" : body;
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
     * Handles Chat Completions, Responses, and legacy Completions shapes.
     */
    static String extractContent(String json) throws Exception {
        JsonNode root = JSON.readTree(json);

        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new Exception("API error: " + firstNonBlank(
                    nodeText(error.path("message")),
                    nodeText(root.path("message")),
                    nodeText(root.path("detail")),
                    json));
        }

        String outputText = nodeText(root.path("output_text"));
        if (!outputText.isBlank()) return outputText;

        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode firstChoice = choices.get(0);
            String messageContent = extractMessageContent(firstChoice.path("message").path("content"));
            if (!messageContent.isBlank()) return messageContent;

            String completionText = nodeText(firstChoice.path("text"));
            if (!completionText.isBlank()) return completionText;
        }

        JsonNode output = root.path("output");
        if (output.isArray()) {
            String extracted = extractOutputItems(output);
            if (!extracted.isBlank()) return extracted;
        }

        String messageContent = extractMessageContent(root.path("message").path("content"));
        if (!messageContent.isBlank()) return messageContent;

        throw new Exception("No text content in response: " + json);
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

    private static String extractOutputItems(JsonNode output) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : output) {
            if (!item.path("type").asText("").equals("message")) continue;
            String text = extractMessageContent(item.path("content"));
            if (!text.isBlank()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static String extractMessageContent(JsonNode content) {
        if (content.isMissingNode() || content.isNull()) return "";
        if (content.isTextual()) return content.asText();

        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String text = extractContentPart(part);
                if (!text.isBlank()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(text);
                }
            }
            return sb.toString();
        }

        return extractContentPart(content);
    }

    private static String extractContentPart(JsonNode part) {
        if (part.isMissingNode() || part.isNull()) return "";
        if (part.isTextual()) return part.asText();
        if (part.hasNonNull("text")) return nodeText(part.get("text"));
        if (part.hasNonNull("value")) return nodeText(part.get("value"));
        return "";
    }

    private static String nodeText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isObject()) {
            if (node.hasNonNull("value")) return nodeText(node.get("value"));
            if (node.hasNonNull("text")) return nodeText(node.get("text"));
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String buildRequestBody(TextEndpoint endpoint, String systemPrompt, String userMessage) {
        return switch (endpoint) {
            case CHAT_COMPLETIONS ->
                    "{\"model\":" + jsonString(model) + "," +
                    "\"messages\":[" +
                      "{\"role\":\"system\",\"content\":" + jsonString(systemPrompt) + "}," +
                      "{\"role\":\"user\",\"content\":" + jsonString(userMessage) + "}" +
                    "]}";
            case RESPONSES ->
                    "{\"model\":" + jsonString(model) + "," +
                    "\"instructions\":" + jsonString(systemPrompt) + "," +
                    "\"input\":" + jsonString(userMessage) + "}";
            case COMPLETIONS ->
                    "{\"model\":" + jsonString(model) + "," +
                    "\"prompt\":" + jsonString("System:\n" + systemPrompt + "\n\nUser:\n" + userMessage + "\n\nAssistant:\n") +
                    "}";
        };
    }

    enum TextEndpoint {
        CHAT_COMPLETIONS("/chat/completions", "chat/completions"),
        RESPONSES("/responses", "responses"),
        COMPLETIONS("/completions", "completions");

        final String pathSuffix;
        final String label;

        TextEndpoint(String pathSuffix, String label) {
            this.pathSuffix = pathSuffix;
            this.label = label;
        }
    }
}
