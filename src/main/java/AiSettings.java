import burp.api.montoya.persistence.Preferences;

/**
 * Holds AI backend configuration. Backed by Burp's {@link Preferences} for
 * persistence across sessions, but the state itself is plain Java so it can
 * be tested without a running Burp instance.
 */
public class AiSettings {

    public enum Mode { BURP, CUSTOM }

    private static final String KEY_MODE   = "ddb.ai.mode";
    private static final String KEY_URL    = "ddb.ai.custom.url";
    private static final String KEY_KEY    = "ddb.ai.custom.key";
    private static final String KEY_MODEL  = "ddb.ai.custom.model";

    private Mode   mode;
    private String customUrl;
    private String customApiKey;
    private String customModel;

    /** Creates an {@code AiSettings} with default values (Burp AI mode, no custom endpoint). */
    public AiSettings() {
        this.mode         = Mode.BURP;
        this.customUrl    = "";
        this.customApiKey = "";
        this.customModel  = "gpt-4o-mini";
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public static AiSettings load(Preferences prefs) {
        AiSettings s = new AiSettings();
        String modeStr = prefs.getString(KEY_MODE);
        if ("custom".equals(modeStr)) s.mode = Mode.CUSTOM;
        String url   = prefs.getString(KEY_URL);
        String key   = prefs.getString(KEY_KEY);
        String model = prefs.getString(KEY_MODEL);
        if (url   != null && !url.isBlank())   s.customUrl    = url;
        if (key   != null)                     s.customApiKey = key;
        if (model != null && !model.isBlank()) s.customModel  = model;
        return s;
    }

    public void save(Preferences prefs) {
        prefs.setString(KEY_MODE,  mode == Mode.CUSTOM ? "custom" : "burp");
        prefs.setString(KEY_URL,   customUrl);
        prefs.setString(KEY_KEY,   customApiKey);
        prefs.setString(KEY_MODEL, customModel);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Mode   getMode()         { return mode; }
    public String getCustomUrl()    { return customUrl; }
    public String getCustomApiKey() { return customApiKey; }
    public String getCustomModel()  { return customModel; }

    public void setMode(Mode mode)               { this.mode = mode; }
    public void setCustomUrl(String url)         { this.customUrl = url == null ? "" : url.strip(); }
    public void setCustomApiKey(String key)      { this.customApiKey = key == null ? "" : key; }
    public void setCustomModel(String model)     { this.customModel = model == null ? "" : model.strip(); }
}
