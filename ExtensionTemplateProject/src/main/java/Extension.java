import burp.api.montoya.BurpExtension;
import burp.api.montoya.EnhancedCapability;
import burp.api.montoya.MontoyaApi;

import javax.swing.*;
import java.io.File;
import java.nio.file.Paths;
import java.util.Set;

public class Extension implements BurpExtension {

    @Override
    public Set<EnhancedCapability> enhancedCapabilities() {
        return Set.of(EnhancedCapability.AI_FEATURES);
    }

    @Override
    public void initialize(MontoyaApi montoyaApi) {
        montoyaApi.extension().setName("DuckDuckBurp");

        try {
            File burpDir = Paths.get(System.getProperty("user.home"), ".burp").toFile();
            burpDir.mkdirs();
            String dbPath = new File(burpDir, "duckduckburp.db").getAbsolutePath();

            DuckDbManager db = new DuckDbManager(dbPath);
            TrafficHandler handler = new TrafficHandler(db, montoyaApi.logging());
            DashboardTab dashboard = new DashboardTab(db);
            QueryTab queryTab = new QueryTab(db);
            SettingsTab settingsTab = new SettingsTab(
                    montoyaApi.persistence().preferences(), montoyaApi.ai());
            AiTab aiTab = new AiTab(db, settingsTab::getCurrentBackend);

            JTabbedPane tabbedPane = new JTabbedPane();
            tabbedPane.addTab("Dashboard",    dashboard.uiComponent());
            tabbedPane.addTab("Query",        queryTab.uiComponent());
            tabbedPane.addTab("AI Analyst",   aiTab.uiComponent());
            tabbedPane.addTab("Settings",     settingsTab.uiComponent());

            montoyaApi.proxy().registerResponseHandler(handler);
            montoyaApi.userInterface().registerSuiteTab("DuckDuckBurp", tabbedPane);

            montoyaApi.extension().registerUnloadingHandler(() -> {
                dashboard.shutdown();
                handler.shutdown();
                db.close();
                montoyaApi.logging().logToOutput("DuckDuckBurp unloaded.");
            });

            montoyaApi.logging().logToOutput("DuckDuckBurp loaded. DB: " + dbPath);

        } catch (Exception e) {
            montoyaApi.logging().logToError("DuckDuckBurp failed to initialize: " + e.getMessage());
        }
    }
}
