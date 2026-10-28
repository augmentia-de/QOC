package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.runtime.tool.a2a.A2AEgress;
import de.augmentia.oc.engine.runtime.tool.a2a.A2AAgentCardCache;
import de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider;
import de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider.A2AEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the configured external A2A endpoints and exposes card discovery.
 *
 * <p>Configuration stays file/environment based; the discovery endpoint only
 * reports the effective configuration and the resolved agent cards.</p>
 */
@ApplicationScoped
public class A2AConfig {
    private static final long DEFAULT_CACHE_TTL_SECONDS = 300;
    private static final long MAX_CACHE_TTL_SECONDS = 86_400;

    private final Config config;
    private final A2AAgentCardCache cache;
    private final java.util.List<String> globalAllowedHosts;
    private final java.util.List<String> globalAllowedIps;
    private final boolean globalTlsRequired;

    public A2AConfig(Config config) {
        this.config = config;
        long ttlSeconds = config
                .getOptionalValue("engine.a2a.cache.ttl-seconds", Long.class)
                .orElse(DEFAULT_CACHE_TTL_SECONDS);
        long bounded = Math.max(0, Math.min(ttlSeconds, MAX_CACHE_TTL_SECONDS));
        this.cache = A2AAgentCardCache.defaults(Duration.ofSeconds(bounded));
        this.globalAllowedHosts = splitList(config.getOptionalValue("engine.a2a.egress.allowedHosts", String.class));
        this.globalAllowedIps = splitList(config.getOptionalValue("engine.a2a.egress.allowedIps", String.class));
        this.globalTlsRequired = config.getOptionalValue("engine.a2a.egress.tlsRequired", Boolean.class).orElse(false);
    }

    /** Shared AgentCard cache used by tool registration and discovery. */
    public A2AAgentCardCache cards() {
        return cache;
    }

    /**
     * Returns a snapshot of all configured endpoints with their card status.
     *
     * @param refresh when {@code true}, drops cached cards first and fetches again
     */
    public List<A2AAgentDiscovery> discover(boolean refresh) {
        if (refresh) {
            cache.invalidate();
        }
        List<A2AAgentDiscovery> result = new ArrayList<>();
        for (A2AEndpoint endpoint : endpoints()) {
            if (!endpoint.enabled()) {
                result.add(A2AAgentDiscovery.disabled(endpoint,
                        A2AToolProvider.toolName(endpoint, null)));
                continue;
            }
            String egressError = A2AEgress.check(endpoint.url(), endpoint.allowedHosts(),
                    endpoint.allowedIps(), endpoint.tlsRequired());
            if (egressError != null) {
                result.add(A2AAgentDiscovery.error(endpoint,
                        A2AToolProvider.toolName(endpoint, null),
                        "egress blocked: " + egressError));
                continue;
            }
            try {
                A2AAgentCardCache.Resolution resolution = cache.resolve(endpoint.url(), endpoint.headers());
                result.add(A2AAgentDiscovery.ok(endpoint,
                        A2AToolProvider.toolName(endpoint, resolution.card()),
                        resolution.card(), resolution.fetchedAt(), resolution.cached()));
            } catch (RuntimeException e) {
                result.add(A2AAgentDiscovery.error(endpoint,
                        A2AToolProvider.toolName(endpoint, null), e.getMessage()));
            }
        }
        return List.copyOf(result);
    }

    public List<A2AEndpoint> endpoints() {
        List<A2AEndpoint> result = new ArrayList<>();
        // Support env/MP config: engine.a2a.endpoints.0.url etc not easily mapped, use simple list via comma
        Optional<String> list = config.getOptionalValue("engine.a2a.endpoints", String.class);
        if (list.isEmpty()) {
            Optional<String> urls = config.getOptionalValue("engine.a2a.urls", String.class);
            if (urls.isPresent()) {
                String[] parts = urls.get().split(",");
                int i = 0;
                for (String part : parts) {
                    String url = part.trim();
                    if (url.isEmpty()) continue;
                    result.add(new A2AEndpoint("a2a" + i, url, true, ""));
                    i++;
                }
            }
            return result;
        }
        String raw = list.get();
        String[] entries = raw.split(";");
        int i = 0;
        for (String entry : entries) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            String url = null;
            String id = "a2a" + i;
            String tool = "";
            boolean enabled = true;
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            java.util.List<String> allowedHosts = new java.util.ArrayList<>();
            java.util.List<String> allowedIps = new java.util.ArrayList<>();
            boolean tlsRequired = false;
            for (String pair : e.split("\\|")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) continue;
                String k = kv[0].trim();
                String v = kv[1].trim();
                if ("url".equalsIgnoreCase(k)) url = v;
                else if ("id".equalsIgnoreCase(k)) id = v;
                else if ("tool".equalsIgnoreCase(k) || "toolName".equalsIgnoreCase(k)) tool = v;
                else if ("enabled".equalsIgnoreCase(k)) enabled = Boolean.parseBoolean(v);
                else if ("auth".equalsIgnoreCase(k)) applyAuth(v, headers);
                else if ("headers".equalsIgnoreCase(k)) {
                    for (String hp : v.split(",")) {
                        String[] hkv = hp.split(":", 2);
                        if (hkv.length == 2) headers.put(hkv[0].trim(), hkv[1].trim());
                    }
                } else if ("allowedHosts".equalsIgnoreCase(k)) {
                    for (String h : v.split(",")) if (!h.isBlank()) allowedHosts.add(h.trim());
                } else if ("allowedIps".equalsIgnoreCase(k)) {
                    for (String ip : v.split(",")) if (!ip.isBlank()) allowedIps.add(ip.trim());
                } else if ("tlsRequired".equalsIgnoreCase(k)) {
                    tlsRequired = Boolean.parseBoolean(v);
                }
            }
            if (url != null && !url.isBlank()) {
                if (allowedHosts.isEmpty()) allowedHosts = new java.util.ArrayList<>(globalAllowedHosts);
                if (allowedIps.isEmpty()) allowedIps = new java.util.ArrayList<>(globalAllowedIps);
                boolean tls = tlsRequired || globalTlsRequired;
                result.add(new A2AEndpoint(id, url, enabled, tool, headers,
                        allowedHosts, allowedIps, tls));
                i++;
            }
        }
        return result;
    }

    private java.util.List<String> splitList(java.util.Optional<String> value) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (value.isEmpty() || value.get().isBlank()) return result;
        for (String part : value.get().split(",")) {
            if (!part.isBlank()) result.add(part.trim());
        }
        return result;
    }

    private static void applyAuth(String spec, java.util.Map<String, String> headers) {
        if (spec == null || spec.isBlank() || "none".equalsIgnoreCase(spec.trim())) return;
        String s = spec.trim();
        if (s.toLowerCase().startsWith("bearer:")) {
            headers.put("Authorization", "Bearer " + s.substring("bearer:".length()).trim());
        } else if (s.toLowerCase().startsWith("basic:")) {
            String rest = s.substring("basic:".length()).trim();
            String[] parts = rest.split(":", 2);
            String user = parts.length > 0 ? parts[0] : "";
            String pass = parts.length > 1 ? parts[1] : "";
            String encoded = java.util.Base64.getEncoder()
                    .encodeToString((user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + encoded);
        } else if (s.toLowerCase().startsWith("header:")) {
            String rest = s.substring("header:".length()).trim();
            int idx = rest.indexOf('=');
            if (idx > 0) headers.put(rest.substring(0, idx).trim(), rest.substring(idx + 1).trim());
        }
    }
}
