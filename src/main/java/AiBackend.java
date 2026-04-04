/**
 * Abstraction over an AI inference backend.
 *
 * The default implementation delegates to Burp's built-in AI service
 * ({@code montoyaApi.ai()}). A custom-endpoint implementation can be
 * swapped in later without changing any call-site code.
 */
public interface AiBackend {

    /** Returns {@code true} if the backend is ready to accept prompts. */
    boolean isAvailable();

    /**
     * Sends a chat-style prompt to the backend and returns the response text.
     *
     * @param systemPrompt instructions / context for the model
     * @param userMessage  the question or instruction from the user
     * @return the model's reply
     * @throws Exception on network errors, quota exhaustion, or backend-specific failures
     */
    String ask(String systemPrompt, String userMessage) throws Exception;
}
