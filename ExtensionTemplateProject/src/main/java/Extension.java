import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import java.io.File;
import java.nio.file.Paths;

public class Extension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi montoyaApi) {
        montoyaApi.extension().setName("DuckDuckBurp");

        try {
            File burpDir = Paths.get(System.getProperty("user.home"), ".burp").toFile();
            burpDir.mkdirs();
            String dbPath = new File(burpDir, "duckduckburp.db").getAbsolutePath();

            DuckDbManager db = new DuckDbManager(dbPath);
            TrafficHandler handler = new TrafficHandler(db, montoyaApi.logging());
            QueryTab tab = new QueryTab(db);

            montoyaApi.proxy().registerResponseHandler(handler);
            montoyaApi.userInterface().registerSuiteTab("DuckDuckBurp", tab.uiComponent());

            montoyaApi.extension().registerUnloadingHandler(() -> {
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
