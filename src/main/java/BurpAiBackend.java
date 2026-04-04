import burp.api.montoya.ai.Ai;
import burp.api.montoya.ai.chat.Message;
import burp.api.montoya.ai.chat.PromptException;

/**
 * {@link AiBackend} implementation that delegates to Burp Suite's
 * built-in AI service ({@code montoyaApi.ai()}).
 *
 * Requires an active Burp AI subscription; returns {@code false} from
 * {@link #isAvailable()} otherwise.
 */
public class BurpAiBackend implements AiBackend {

    private final Ai ai;

    public BurpAiBackend(Ai ai) {
        this.ai = ai;
    }

    @Override
    public boolean isAvailable() {
        return ai.isEnabled();
    }

    @Override
    public String ask(String systemPrompt, String userMessage) throws Exception {
        try {
            // Try the structured message form first
            return ai.prompt()
                    .execute(
                            Message.systemMessage(systemPrompt),
                            Message.userMessage(userMessage)
                    )
                    .content();
        } catch (PromptException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // 402 = payment/credits issue — rethrow with actionable guidance
            if (msg.contains("402") || msg.toLowerCase().contains("credit")) {
                throw new Exception(
                        "Burp AI returned a credits/billing error (402).\n\n" +
                        "Things to check:\n" +
                        "  1. Burp > Settings > AI — confirm credits are shown as available\n" +
                        "  2. Burp > Account — make sure you are signed in to your PortSwigger account\n" +
                        "  3. Try restarting Burp to refresh the credit balance\n\n" +
                        "Alternatively, use the Custom Endpoint option in the Settings tab\n" +
                        "(Ollama running locally is free — no credits needed).\n\n" +
                        "Original error: " + msg);
            }
            throw e;
        }
    }
}
