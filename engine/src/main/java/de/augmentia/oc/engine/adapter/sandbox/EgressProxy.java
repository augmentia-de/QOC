package de.augmentia.oc.engine.adapter.sandbox;

import de.augmentia.oc.engine.core.port.world.HttpTransport;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP transport wrapper that enforces an egress policy (allowlist/none/outbound-only).
 * Delegates to the wrapped transport after checking the host.
 */
public final class EgressProxy implements HttpTransport {

    public enum Mode { NONE, OUTBOUND_ONLY, ALLOWLIST }

    private final HttpTransport delegate;
    private final Mode mode;
    private final Set<String> allowedHosts;

    public EgressProxy(HttpTransport delegate, SandboxConfig config) {
        this.delegate = delegate;
        if (config == null) {
            this.mode = Mode.OUTBOUND_ONLY;
            this.allowedHosts = Set.of();
        } else {
            String net = config.network() == null ? "outbound-only" : config.network().toLowerCase();
            this.mode = switch (net) {
                case "none" -> Mode.NONE;
                case "allowlist" -> Mode.ALLOWLIST;
                default -> Mode.OUTBOUND_ONLY;
            };
            this.allowedHosts = config.allowedHosts() == null ? Set.of() : Set.copyOf(config.allowedHosts());
        }
    }

    public EgressProxy(HttpTransport delegate, Mode mode, Set<String> allowedHosts) {
        this.delegate = delegate;
        this.mode = mode == null ? Mode.OUTBOUND_ONLY : mode;
        this.allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
    }

    private void checkHost(URI url) {
        if (url == null) return;
        String host = url.getHost();
        if (host == null) return;
        switch (mode) {
            case NONE -> throw new SecurityException("egress disabled (mode=none): " + host);
            case ALLOWLIST -> {
                if (!allowedHosts.contains(host) && !allowedHosts.contains("*")) {
                    throw new SecurityException("host not in allowlist: " + host);
                }
            }
            case OUTBOUND_ONLY -> { /* allow all outbound */ }
        }
    }

    @Override
    public ResponseStream postJson(URI url, Map<String, String> headers, String body, Duration timeout) {
        checkHost(url);
        return delegate.postJson(url, headers, body, timeout);
    }

    @Override
    public ResponseStream get(URI url, Map<String, String> headers, Duration timeout) {
        checkHost(url);
        return delegate.get(url, headers, timeout);
    }

    @Override
    public Channel openChannel(URI url, Map<String, String> headers) {
        checkHost(url);
        return delegate.openChannel(url, headers);
    }
}