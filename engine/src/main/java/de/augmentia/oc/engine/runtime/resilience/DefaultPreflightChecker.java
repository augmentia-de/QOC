package de.augmentia.oc.engine.runtime.resilience;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link PreflightChecker}.
 *
 * <p>Runs DNS lookup, HTTP health check, API key validation, DB connectivity,
 * and workspace writability checks. Each check is independent; all issues are
 * collected and returned together.</p>
 *
 * <p>DNS and HTTP results are cached per provider URL for a configurable TTL
 * so a slow gateway does not delay every turn.</p>
 */
public final class DefaultPreflightChecker implements PreflightChecker {

    private final HttpClient httpClient;
    private final Duration httpTimeout;
    private final Duration cacheTtl;
    private final boolean dnsEnabled;
    private final boolean httpEnabled;
    private final Map<String, CachedIssues> cache = new ConcurrentHashMap<>();

    public DefaultPreflightChecker() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(5), Duration.ofSeconds(60), true, true);
    }

    public DefaultPreflightChecker(HttpClient httpClient, Duration httpTimeout) {
        this(httpClient, httpTimeout, Duration.ofSeconds(60), true, true);
    }

    public DefaultPreflightChecker(HttpClient httpClient, Duration httpTimeout, Duration cacheTtl,
                                   boolean dnsEnabled, boolean httpEnabled) {
        this.httpClient = httpClient != null ? httpClient
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.httpTimeout = httpTimeout != null ? httpTimeout : Duration.ofSeconds(5);
        this.cacheTtl = cacheTtl != null ? cacheTtl : Duration.ofSeconds(60);
        this.dnsEnabled = dnsEnabled;
        this.httpEnabled = httpEnabled;
    }

    @Override
    public PreflightResult check(PreflightContext ctx) {
        List<PreflightIssue> issues = new ArrayList<>();

        if (ctx.providerUrl() != null && !ctx.providerUrl().isBlank()) {
            String key = ctx.providerUrl();
            CachedIssues cached = cache.get(key);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                issues.addAll(cached.issues());
            } else {
                List<PreflightIssue> providerIssues = new ArrayList<>();
                if (dnsEnabled) providerIssues.addAll(checkDns(ctx.providerUrl()));
                if (httpEnabled) providerIssues.addAll(checkHttp(ctx.providerUrl()));
                issues.addAll(providerIssues);
                cache.put(key, new CachedIssues(Instant.now().plus(cacheTtl), List.copyOf(providerIssues)));
            }
        }

        // API key validation
        if (ctx.apiKey() == null || ctx.apiKey().isBlank()) {
            issues.add(new PreflightIssue("auth", "API key is not set", true));
        } else if (ctx.apiKey().equals("demo-key") || ctx.apiKey().equals("sk-...")) {
            issues.add(new PreflightIssue("auth", "API key appears to be a placeholder/demo key", false));
        }

        // Database connectivity
        if (ctx.databaseChecker() != null) {
            try {
                if (!ctx.databaseChecker().isHealthy()) {
                    issues.add(new PreflightIssue("database", "Database connection is not valid", true));
                }
            } catch (Exception e) {
                issues.add(new PreflightIssue("database", "Database connectivity check failed: " + e.getMessage(), true));
            }
        }

        // Workspace writability
        if (ctx.workspacePath() != null) {
            try {
                if (!Files.exists(ctx.workspacePath())) {
                    issues.add(new PreflightIssue("workspace", "Workspace directory does not exist: " + ctx.workspacePath(), true));
                } else if (!Files.isWritable(ctx.workspacePath())) {
                    issues.add(new PreflightIssue("workspace", "Workspace directory is not writable: " + ctx.workspacePath(), true));
                }
            } catch (Exception e) {
                issues.add(new PreflightIssue("workspace", "Workspace check failed: " + e.getMessage(), true));
            }
        }

        boolean passed = issues.stream().noneMatch(PreflightIssue::blocking);
        return new PreflightResult(passed, issues);
    }

    private List<PreflightIssue> checkDns(String providerUrl) {
        List<PreflightIssue> issues = new ArrayList<>();
        try {
            URI uri = URI.create(providerUrl);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                InetAddress.getByName(host);
            }
        } catch (Exception e) {
            issues.add(new PreflightIssue("dns", "DNS lookup failed for provider URL: " + e.getMessage(), true));
        }
        return issues;
    }

    private List<PreflightIssue> checkHttp(String providerUrl) {
        List<PreflightIssue> issues = new ArrayList<>();
        try {
            URI uri = URI.create(providerUrl);
            String base = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(base))
                    .timeout(httpTimeout)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> res = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() >= 500) {
                issues.add(new PreflightIssue("health", "Provider returned HTTP " + res.statusCode(), false));
            }
        } catch (Exception e) {
            issues.add(new PreflightIssue("health", "HTTP health check failed: " + e.getMessage(), true));
        }
        return issues;
    }

    private record CachedIssues(Instant expiresAt, List<PreflightIssue> issues) {
    }
}
