package de.augmentia.oc.engine.runtime.tool.a2a;

import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpClientFactory;
import org.a2aproject.sdk.spec.AgentCard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Small, thread-safe TTL cache for remote A2A AgentCards.
 *
 * <p>Fetching a card is an HTTP round trip. Tool registration and the discovery
 * endpoint both need the same card, so caching avoids duplicate requests and
 * keeps the advertised tool name stable. Failures are never cached: the next
 * call retries, which lets an operator recover from a temporary outage.</p>
 *
 * <p>Auth headers are part of the cache key, so endpoints with different
 * credentials do not share a cached card.</p>
 *
 * <p>A TTL of {@link Duration#ZERO} disables caching.</p>
 */
public final class A2AAgentCardCache {

    /** Fetches the agent card for the given base URL and headers. */
    @FunctionalInterface
    public interface Fetcher {
        AgentCard fetch(String url, Map<String, String> headers);
    }

    /** Result of a cache lookup. */
    public record Resolution(AgentCard card, Instant fetchedAt, boolean cached) {
    }

    private record Entry(AgentCard card, Instant fetchedAt) {
    }

    private final Duration ttl;
    private final Clock clock;
    private final Fetcher fetcher;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    public A2AAgentCardCache(Duration ttl, Clock clock, Fetcher fetcher) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must not be negative");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /** Creates a cache using the system clock and the default SDK fetcher. */
    public static A2AAgentCardCache defaults(Duration ttl) {
        return new A2AAgentCardCache(ttl, Clock.systemUTC(),
                (url, headers) -> A2A.getAgentCard(url, null, headers == null ? null : headers));
    }

    /** Returns the cached card when it is still fresh, otherwise fetches and caches it. */
    public Resolution resolve(String url) {
        return resolve(url, Map.of());
    }

    /**
     * Returns the cached card when it is still fresh, otherwise fetches and caches it.
     *
     * @throws RuntimeException if the fetch fails; nothing is cached in that case
     */
    public Resolution resolve(String url, Map<String, String> headers) {
        Map<String, String> h = headers == null ? Map.of() : headers;
        String key = key(url, h);
        Instant now = clock.instant();
        Entry entry = entries.get(key);
        if (entry != null && isFresh(entry, now)) {
            return new Resolution(entry.card(), entry.fetchedAt(), true);
        }
        AgentCard card = fetcher.fetch(url, h);
        if (card == null) {
            throw new IllegalStateException("agent card is null for " + url);
        }
        Instant fetchedAt = clock.instant();
        entries.put(key, new Entry(card, fetchedAt));
        return new Resolution(card, fetchedAt, false);
    }

    /** Returns the fetch time of a fresh entry, if one exists. */
    public Optional<Instant> fetchedAt(String url) {
        return fetchedAt(url, Map.of());
    }

    public Optional<Instant> fetchedAt(String url, Map<String, String> headers) {
        Entry entry = entries.get(key(url, headers == null ? Map.of() : headers));
        if (entry == null || !isFresh(entry, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.fetchedAt());
    }

    /** Drops every cached card. */
    public void invalidate() {
        entries.clear();
    }

    /** Drops the cached card for one endpoint (any headers). */
    public void invalidate(String url) {
        entries.keySet().removeIf(k -> k.equals(url) || k.startsWith(url + "\u0000"));
    }

    /** Number of cached endpoints. Visible for tests. */
    public int size() {
        return entries.size();
    }

    private boolean isFresh(Entry entry, Instant now) {
        if (ttl.isZero()) {
            return false;
        }
        return !entry.fetchedAt().plus(ttl).isBefore(now);
    }

    private static String key(String url, Map<String, String> headers) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        String base = url.trim();
        if (headers == null || headers.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base);
        sb.append('\u0000');
        headers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append(';'));
        return sb.toString();
    }
}
