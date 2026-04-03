import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiSettingsTest {

    @Test
    void defaultModeIsBurp() {
        AiSettings s = new AiSettings();
        assertEquals(AiSettings.Mode.BURP, s.getMode());
    }

    @Test
    void defaultCustomFieldsAreEmpty() {
        AiSettings s = new AiSettings();
        assertTrue(s.getCustomUrl().isEmpty());
        assertTrue(s.getCustomApiKey().isEmpty());
    }

    @Test
    void defaultModelIsSet() {
        AiSettings s = new AiSettings();
        assertFalse(s.getCustomModel().isBlank(), "default model should be pre-filled");
    }

    @Test
    void setModeToCustom() {
        AiSettings s = new AiSettings();
        s.setMode(AiSettings.Mode.CUSTOM);
        assertEquals(AiSettings.Mode.CUSTOM, s.getMode());
    }

    @Test
    void setCustomUrlTrimsWhitespace() {
        AiSettings s = new AiSettings();
        s.setCustomUrl("  https://api.example.com/v1  ");
        assertEquals("https://api.example.com/v1", s.getCustomUrl());
    }

    @Test
    void setCustomUrlNullBecomesEmpty() {
        AiSettings s = new AiSettings();
        s.setCustomUrl(null);
        assertEquals("", s.getCustomUrl());
    }

    @Test
    void setCustomApiKeyPreservesLeadingTrailingChars() {
        AiSettings s = new AiSettings();
        s.setCustomApiKey("sk-abc123");
        assertEquals("sk-abc123", s.getCustomApiKey());
    }

    @Test
    void setCustomApiKeyNullBecomesEmpty() {
        AiSettings s = new AiSettings();
        s.setCustomApiKey(null);
        assertEquals("", s.getCustomApiKey());
    }

    @Test
    void setCustomModelTrimsWhitespace() {
        AiSettings s = new AiSettings();
        s.setCustomModel("  llama3  ");
        assertEquals("llama3", s.getCustomModel());
    }

    @Test
    void setCustomModelNullBecomesEmpty() {
        AiSettings s = new AiSettings();
        s.setCustomModel(null);
        assertEquals("", s.getCustomModel());
    }

    @Test
    void mutationsAreIndependent() {
        AiSettings s = new AiSettings();
        s.setMode(AiSettings.Mode.CUSTOM);
        s.setCustomUrl("http://localhost:11434/v1");
        s.setCustomModel("llama3");

        assertEquals(AiSettings.Mode.CUSTOM, s.getMode());
        assertEquals("http://localhost:11434/v1", s.getCustomUrl());
        assertEquals("llama3", s.getCustomModel());
        assertTrue(s.getCustomApiKey().isEmpty(), "API key should still be empty");
    }
}
