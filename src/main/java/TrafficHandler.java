import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TrafficHandler implements ProxyResponseHandler {

    private final DuckDbManager db;
    private final Logging logging;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public TrafficHandler(DuckDbManager db, Logging logging) {
        this.db = db;
        this.logging = logging;
    }

    @Override
    public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse response) {
        // Capture data on calling thread — InterceptedResponse may not be safe to hold past return
        long id = response.messageId();
        String host = response.initiatingRequest().httpService().host();
        int port = response.initiatingRequest().httpService().port();
        boolean secure = response.initiatingRequest().httpService().secure();
        String method = response.initiatingRequest().method();
        String path = response.initiatingRequest().path();
        int statusCode = response.statusCode();
        String reqHeaders = headersToJson(response.initiatingRequest().headers());
        String reqBody = response.initiatingRequest().bodyToString();
        String respHeaders = headersToJson(response.headers());
        String respBody = response.bodyToString();

        executor.submit(() -> {
            try {
                db.insertTraffic(id, host, port, secure, method, path, statusCode,
                        reqHeaders, reqBody, respHeaders, respBody);
            } catch (Exception e) {
                logging.logToError("DuckDuckBurp: failed to save traffic: " + e.getMessage());
            }
        });

        return ProxyResponseReceivedAction.continueWith(response);
    }

    @Override
    public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse response) {
        return ProxyResponseToBeSentAction.continueWith(response);
    }

    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String headersToJson(List<HttpHeader> headers) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(jsonEscape(headers.get(i).name())).append("\":");
            sb.append("\"").append(jsonEscape(headers.get(i).value())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
