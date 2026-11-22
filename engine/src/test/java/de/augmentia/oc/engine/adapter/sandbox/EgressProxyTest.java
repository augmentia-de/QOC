package de.augmentia.oc.engine.adapter.sandbox;

import de.augmentia.oc.engine.core.port.world.HttpTransport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EgressProxyTest {

    static class StubTransport implements HttpTransport {
        @Override
        public ResponseStream postJson(URI url, Map<String, String> headers, String body, Duration timeout) {
            return new ResponseStream() {
                public int status() { return 200; }
                public Map<String, List<String>> headers() { return Map.of(); }
                public java.io.InputStream body() { return new java.io.ByteArrayInputStream(new byte[0]); }
                public void close() {}
            };
        }
        @Override
        public Channel openChannel(URI url, Map<String, String> headers) {
            return new Channel() {
                public void send(String text) {}
                public void onMessage(java.util.function.Consumer<String> listener) {}
                public void close() {}
            };
        }
    }

    @Test
    void noneModeBlocksAll() {
        HttpTransport delegate = new StubTransport();
        EgressProxy proxy = new EgressProxy(delegate, EgressProxy.Mode.NONE, Set.of());
        assertThrows(SecurityException.class, () ->
                proxy.postJson(URI.create("https://example.com/api"), Map.of(), "{}", Duration.ofSeconds(5)));
    }

    @Test
    void allowlistBlocksUnlisted() {
        HttpTransport delegate = new StubTransport();
        EgressProxy proxy = new EgressProxy(delegate, EgressProxy.Mode.ALLOWLIST, Set.of("allowed.com"));
        assertThrows(SecurityException.class, () ->
                proxy.postJson(URI.create("https://blocked.com/api"), Map.of(), "{}", Duration.ofSeconds(5)));
    }

    @Test
    void allowlistAllowsListed() {
        HttpTransport delegate = new StubTransport();
        EgressProxy proxy = new EgressProxy(delegate, EgressProxy.Mode.ALLOWLIST, Set.of("allowed.com"));
        assertNotNull(proxy.postJson(URI.create("https://allowed.com/api"), Map.of(), "{}", Duration.ofSeconds(5)));
    }

    @Test
    void outboundOnlyAllowsAll() {
        HttpTransport delegate = new StubTransport();
        EgressProxy proxy = new EgressProxy(delegate, EgressProxy.Mode.OUTBOUND_ONLY, Set.of());
        assertNotNull(proxy.postJson(URI.create("https://any.com/api"), Map.of(), "{}", Duration.ofSeconds(5)));
    }

    @Test
    void noneModeBlocksGet() {
        HttpTransport delegate = new StubTransport();
        EgressProxy proxy = new EgressProxy(delegate, EgressProxy.Mode.NONE, Set.of());
        assertThrows(SecurityException.class, () ->
                proxy.get(URI.create("https://example.com/api"), Map.of(), Duration.ofSeconds(5)));
    }

    @Test
    void noneModeBlocksChannel() {
        HttpTransport delegate = new StubTransport();
        EgressProxy proxy = new EgressProxy(delegate, EgressProxy.Mode.NONE, Set.of());
        assertThrows(SecurityException.class, () ->
                proxy.openChannel(URI.create("https://example.com/ws"), Map.of()));
    }
}