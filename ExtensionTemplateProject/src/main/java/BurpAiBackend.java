import burp.api.montoya.ai.Ai;
import burp.api.montoya.ai.chat.Message;

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
        return ai.prompt()
                .execute(
                        Message.systemMessage(systemPrompt),
                        Message.userMessage(userMessage)
                )
                .content();
    }
}
