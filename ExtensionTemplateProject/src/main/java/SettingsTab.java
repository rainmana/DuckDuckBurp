import burp.api.montoya.ai.Ai;
import burp.api.montoya.persistence.Preferences;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * "Settings" sub-tab.  Lets the user choose between Burp's built-in AI and a
 * custom OpenAI-compatible endpoint, and persists the choice via Burp's
 * {@link Preferences} store.
 *
 * Call {@link #getCurrentBackend()} to get the currently configured
 * {@link AiBackend} at any time; this is re-evaluated on every call so changes
 * take effect without reloading the extension.
 */
public class SettingsTab {

    private final Preferences prefs;
    private final Ai          burpAi;
    private final AiSettings  settings;
    private final JPanel      panel;

    // Form fields kept as instance fields so getCurrentBackend() can read live values
    private final JRadioButton burpRadio;
    private final JRadioButton customRadio;
    private final JTextField   urlField;
    private final JPasswordField keyField;
    private final JTextField   modelField;
    private final JLabel       statusLabel;
    private final JLabel       burpAiStatusLabel;

    public SettingsTab(Preferences prefs, Ai burpAi) {
        this.prefs    = prefs;
        this.burpAi   = burpAi;
        this.settings = AiSettings.load(prefs);

        panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        // ── Burp AI live status ───────────────────────────────────────────────
        burpAiStatusLabel = new JLabel();
        JButton refreshStatusBtn = new JButton("Refresh");
        refreshStatusBtn.addActionListener(e -> updateBurpAiStatus());
        JPanel burpStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        burpStatusPanel.setBorder(BorderFactory.createTitledBorder("Burp AI Status"));
        burpStatusPanel.add(burpAiStatusLabel);
        burpStatusPanel.add(refreshStatusBtn);
        updateBurpAiStatus();

        // ── Backend selector ──────────────────────────────────────────────────
        burpRadio   = new JRadioButton("Burp AI  (uses your Burp AI credits)");
        customRadio = new JRadioButton("Custom endpoint  (OpenAI-compatible: OpenAI, Ollama, LM Studio, …)");
        ButtonGroup group = new ButtonGroup();
        group.add(burpRadio);
        group.add(customRadio);

        burpRadio.setSelected(settings.getMode() == AiSettings.Mode.BURP);
        customRadio.setSelected(settings.getMode() == AiSettings.Mode.CUSTOM);

        JPanel radioPanel = new JPanel(new GridLayout(2, 1, 0, 4));
        radioPanel.setBorder(BorderFactory.createTitledBorder("AI Backend"));
        radioPanel.add(burpRadio);
        radioPanel.add(customRadio);

        // ── Custom endpoint form ──────────────────────────────────────────────
        urlField   = new JTextField(settings.getCustomUrl(), 40);
        keyField   = new JPasswordField(settings.getCustomApiKey(), 40);
        modelField = new JTextField(settings.getCustomModel().isBlank() ? "gpt-4o-mini"
                                                                        : settings.getCustomModel(), 20);

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createTitledBorder("Custom Endpoint"));
        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(4, 4, 4, 8);
        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1; fc.insets = new Insets(4, 0, 4, 4);

        lc.gridx = 0; lc.gridy = 0; formPanel.add(new JLabel("Base URL:"), lc);
        fc.gridx = 1; fc.gridy = 0; formPanel.add(urlField, fc);
        JLabel urlHint = new JLabel("e.g. https://api.openai.com/v1  or  http://localhost:11434/v1");
        urlHint.setFont(urlHint.getFont().deriveFont(Font.ITALIC, 11f));
        urlHint.setForeground(Color.GRAY);
        fc.gridy = 1; formPanel.add(urlHint, fc);

        lc.gridy = 2; formPanel.add(new JLabel("API Key:"), lc);
        fc.gridy = 2; formPanel.add(keyField, fc);
        JLabel keyHint = new JLabel("Leave blank for local/unauthenticated endpoints");
        keyHint.setFont(keyHint.getFont().deriveFont(Font.ITALIC, 11f));
        keyHint.setForeground(Color.GRAY);
        fc.gridy = 3; formPanel.add(keyHint, fc);

        lc.gridy = 4; formPanel.add(new JLabel("Model:"), lc);
        fc.gridy = 4; fc.fill = GridBagConstraints.NONE; fc.weightx = 0;
        formPanel.add(modelField, fc);

        // ── Buttons + status ──────────────────────────────────────────────────
        JButton saveButton = new JButton("Save");
        JButton testButton = new JButton("Test Connection");
        statusLabel = new JLabel(" ");

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnPanel.add(saveButton);
        btnPanel.add(testButton);
        btnPanel.add(statusLabel);

        // ── Assemble ──────────────────────────────────────────────────────────
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(burpStatusPanel);
        content.add(Box.createVerticalStrut(6));
        content.add(radioPanel);
        content.add(Box.createVerticalStrut(10));
        content.add(formPanel);
        content.add(Box.createVerticalStrut(10));
        content.add(btnPanel);

        panel.add(content, BorderLayout.NORTH);

        updateFormEnabled();

        // ── Listeners ─────────────────────────────────────────────────────────
        burpRadio.addActionListener(e  -> updateFormEnabled());
        customRadio.addActionListener(e -> updateFormEnabled());

        saveButton.addActionListener(this::save);
        testButton.addActionListener(e -> testConnection());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the currently configured backend, re-reading live form values.
     * Always call this at ask-time rather than caching the result.
     */
    public AiBackend getCurrentBackend() {
        if (burpRadio.isSelected()) {
            return new BurpAiBackend(burpAi);
        }
        return new CustomAiBackend(
                urlField.getText().strip(),
                new String(keyField.getPassword()),
                modelField.getText().strip()
        );
    }

    public Component uiComponent() { return panel; }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void updateBurpAiStatus() {
        boolean enabled = burpAi.isEnabled();
        if (enabled) {
            burpAiStatusLabel.setText("isEnabled() = true  ✓  Burp AI is ready");
            burpAiStatusLabel.setForeground(new Color(0, 128, 0));
        } else {
            burpAiStatusLabel.setText(
                    "isEnabled() = false  —  Go to Burp menu > Settings > AI and check that AI is turned on and this extension is allowed to use it");
            burpAiStatusLabel.setForeground(Color.RED.darker());
        }
    }

    private void updateFormEnabled() {
        boolean custom = customRadio.isSelected();
        urlField.setEnabled(custom);
        keyField.setEnabled(custom);
        modelField.setEnabled(custom);
    }

    private void save(ActionEvent e) {
        settings.setMode(burpRadio.isSelected() ? AiSettings.Mode.BURP : AiSettings.Mode.CUSTOM);
        settings.setCustomUrl(urlField.getText());
        settings.setCustomApiKey(new String(keyField.getPassword()));
        settings.setCustomModel(modelField.getText());
        settings.save(prefs);
        statusLabel.setText("Settings saved.");
    }

    private void testConnection() {
        AiBackend backend = getCurrentBackend();
        if (!backend.isAvailable()) {
            statusLabel.setText("Backend is not configured.");
            return;
        }
        statusLabel.setText("Testing…");
        new Thread(() -> {
            try {
                String response = backend.ask(
                        "You are a test assistant.",
                        "Reply with exactly the word OK and nothing else.");
                SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Connection OK — model replied: " + response.strip()));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Failed: " + ex.getMessage()));
            }
        }, "DuckDuckBurp-ai-test").start();
    }
}
