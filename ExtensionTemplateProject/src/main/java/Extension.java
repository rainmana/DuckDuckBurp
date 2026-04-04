import burp.api.montoya.BurpExtension;
import burp.api.montoya.EnhancedCapability;
import burp.api.montoya.MontoyaApi;

import javax.swing.*;
import java.io.File;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

public class Extension implements BurpExtension {

    @Override
    public Set<EnhancedCapability> enhancedCapabilities() {
        return Set.of(EnhancedCapability.AI_FEATURES);
    }

    @Override
    public void initialize(MontoyaApi montoyaApi) {
        montoyaApi.extension().setName("DuckDuckBurp");

        try {
            // ── Directories ───────────────────────────────────────────────────
            File ddbDir = Paths.get(System.getProperty("user.home"), ".burp", "duckduckburp").toFile();
            ddbDir.mkdirs();

            // ── Per-project traffic DB ────────────────────────────────────────
            // Montoya preferences are project-scoped, so this UUID is unique
            // per Burp project file — giving each engagement its own traffic store.
            var prefs = montoyaApi.persistence().preferences();
            String projectId = prefs.getString("ddb.project.id");
            if (projectId == null || projectId.isBlank()) {
                projectId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                prefs.setString("ddb.project.id", projectId);
            }
            String trafficDbPath = new File(ddbDir, projectId + ".db").getAbsolutePath();

            // ── Shared queries DB ─────────────────────────────────────────────
            // Saved queries live here so they persist across all projects.
            String queriesDbPath = new File(ddbDir, "shared.db").getAbsolutePath();

            DuckDbManager trafficDb = new DuckDbManager(trafficDbPath);
            DuckDbManager queriesDb = new DuckDbManager(queriesDbPath);

            TrafficHandler handler  = new TrafficHandler(trafficDb, montoyaApi.logging());
            DashboardTab   dashboard = new DashboardTab(trafficDb);
            QueryTab       queryTab  = new QueryTab(trafficDb, queriesDb);
            SettingsTab    settingsTab = new SettingsTab(prefs, montoyaApi.ai());

            JTabbedPane tabbedPane = new JTabbedPane();

            AiTab aiTab = new AiTab(trafficDb, queriesDb, settingsTab::getCurrentBackend,
                    sql -> { tabbedPane.setSelectedComponent(queryTab.uiComponent()); queryTab.runSql(sql); },
                    queryTab::refreshSidebar);

            tabbedPane.addTab("Dashboard",  dashboard.uiComponent());
            tabbedPane.addTab("Query",      queryTab.uiComponent());
            tabbedPane.addTab("AI Analyst", aiTab.uiComponent());
            tabbedPane.addTab("Settings",   settingsTab.uiComponent());

            montoyaApi.proxy().registerResponseHandler(handler);
            montoyaApi.userInterface().registerSuiteTab("DuckDuckBurp", tabbedPane);

            montoyaApi.extension().registerUnloadingHandler(() -> {
                dashboard.shutdown();
                handler.shutdown();
                trafficDb.close();
                queriesDb.close();
                montoyaApi.logging().logToOutput("DuckDuckBurp unloaded.");
            });

            montoyaApi.logging().logToOutput("DuckDuckBurp loaded.");
            montoyaApi.logging().logToOutput("  Traffic DB : " + trafficDbPath);
            montoyaApi.logging().logToOutput("  Queries DB : " + queriesDbPath);
            montoyaApi.logging().logToOutput("  Burp AI    : " + montoyaApi.ai().isEnabled());

        } catch (Exception e) {
            montoyaApi.logging().logToError("DuckDuckBurp failed to initialize: " + e.getMessage());
        }
    }
}
