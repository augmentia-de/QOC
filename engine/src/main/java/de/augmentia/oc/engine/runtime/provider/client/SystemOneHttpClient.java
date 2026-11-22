package de.augmentia.oc.engine.runtime.provider.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Shared HTTP transport for the TypeSafe/Flide {@code /v1/systemone} API.
 *
 * <p>Both the engine's {@link TypeSafeDecisionAdapter} and the workflow-runner's
 * {@code TypeSafeDecisionClient} use this class so timeout, retry, 4xx handling
 * and auth-header logic cannot drift apart. The callers keep their own
 * request/response mapping.</p>
 */
public final class SystemOneHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(SystemOneHttpClient.class);

    private SystemOneHttpClient() {}

    /**
     * POSTs {@code body} to {@code <baseUrl>/<apiPath>} and returns the raw
     * response body. Retries 5xx/transport errors, never retries 4xx.
     *
     * @throws IllegalStateException when the request ultimately fails
     */
    public static String post(String baseUrl, String apiPath, String apiKey,
                              Map<String, String> customHeaders, String body,
                              int timeoutSeconds, int maxRetries) {
        String url = normalizeBase(baseUrl) + "/" + normalizePath(apiPath);
        String key = (apiKey == null || apiKey.isBlank())
                ? System.getenv().getOrDefault("TYPESAFE_API_KEY", null)
                : apiKey;
        int timeout = Math.max(1, timeoutSeconds);
        int retries = Math.max(0, maxRetries);
        Exception lastErr = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeout)).build();
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(timeout))
                        .header("Content-Type", "application/json");
                if (key != null && !key.isBlank()) {
                    rb.header("Authorization", "Bearer " + key);
                }
                if (customHeaders != null) {
                    for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                        if (e.getKey() == null || e.getKey().isBlank()) continue;
                        rb.header(e.getKey(), e.getValue() == null ? "" : e.getValue());
                    }
                }
                HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return resp.body();
                }
                if (resp.statusCode() >= 400 && resp.statusCode() < 500) {
                    throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
                }
                lastErr = new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (IllegalStateException e) {
                throw e; // 4xx: no retry
            } catch (Exception e) {
                lastErr = e;
            }
            if (attempt < retries) {
                try {
                    Thread.sleep(200L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        LOG.warn("TypeSafe request failed after {} attempt(s): {}", retries + 1,
                lastErr == null ? "unknown" : lastErr.getMessage());
        throw new IllegalStateException("TypeSafe request failed: "
                + (lastErr == null ? "unknown" : lastErr.getMessage()), lastErr);
    }

    private static String normalizeBase(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? "https://api.typesafe.ai" : baseUrl;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static String normalizePath(String apiPath) {
        String path = apiPath == null || apiPath.isBlank() ? "v1/systemone" : apiPath;
        while (path.startsWith("/")) path = path.substring(1);
        return path;
    }
}
