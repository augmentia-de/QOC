package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Principal;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.core.port.world.ToolTokenService;
import de.augmentia.oc.engine.runtime.tool.web.SecureRestTool;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecureRestToolTest {

    private static ToolOutcome execute(HttpTransport transport, Map<String, JsonValue> input,
                                      Principal principal, ToolTokenService svc) {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(),
                MessageId.create(), CallId.create(), null, principal, svc);
        return SecureRestTool.definition(new SecureRestTool(transport)).execute()
                .execute(JsonValue.of(input), context);
    }

    @Test
    void sendsBearerTokenFromPrincipal() {
        FakeTransport transport = new FakeTransport(200, "application/json", "{\"ok\":true}");
        Principal p = new Principal("alice", "Alice", List.of("admin"), List.of(), Map.of(), "raw-oidc-token");

        ToolOutcome outcome = execute(transport, Map.of("url", JsonValue.of("http://example.com/api/admin/ping")), p, ToolTokenService.disabled());

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(transport.lastHeaders.get("Authorization")).isEqualTo("Bearer raw-oidc-token");
    }

    @Test
    void issuesTokenWhenRequested() {
        FakeTransport transport = new FakeTransport(200, "application/json", "{\"ok\":true}");
        Principal p = new Principal("alice", "Alice", List.of("admin"), List.of(), Map.of(), null);
        ToolTokenService svc = new de.augmentia.oc.engine.adapter.security.HmacToolTokenService("secret");

        ToolOutcome outcome = execute(transport, Map.of(
                "url", JsonValue.of("http://example.com/api/admin/ping"),
                "useIssuedToken", JsonValue.of(true)), p, svc);

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        String auth = transport.lastHeaders.get("Authorization");
        assertThat(auth).startsWith("Bearer ");
        assertThat(auth).isNotEqualTo("Bearer raw-oidc-token");
    }

    @Test
    void anonymousHasNoToken() {
        FakeTransport transport = new FakeTransport(200, "application/json", "{\"ok\":true}");
        ToolOutcome outcome = execute(transport, Map.of("url", JsonValue.of("http://example.com/api/admin/ping")),
                Principal.anonymous(), ToolTokenService.disabled());

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(transport.lastHeaders).doesNotContainKey("Authorization");
    }

    @Test
    void reportsHttpErrors() {
        FakeTransport transport = new FakeTransport(403, "application/json", "{\"error\":\"forbidden\"}");
        Principal p = new Principal("bob", "Bob", List.of(), List.of(), Map.of(), "tok");
        ToolOutcome outcome = execute(transport, Map.of("url", JsonValue.of("http://example.com/api/admin/ping")), p, ToolTokenService.disabled());

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).contains("HTTP 403");
    }

    private static final class FakeTransport implements HttpTransport {
        private final int status;
        private final String contentType;
        private final String body;
        Map<String, String> lastHeaders = Map.of();

        private FakeTransport(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        @Override
        public ResponseStream get(URI url, Map<String, String> headers, Duration timeout) {
            this.lastHeaders = new LinkedHashMap<>(headers);
            return new Stream(status, Map.of("content-type", List.of(contentType)), body);
        }

        @Override
        public ResponseStream postJson(URI url, Map<String, String> headers, String body, Duration timeout) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Channel openChannel(URI url, Map<String, String> headers) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class Stream implements HttpTransport.ResponseStream {
        private final int status;
        private final Map<String, List<String>> headers;
        private final InputStream body;

        private Stream(int status, Map<String, List<String>> headers, String body) {
            this.status = status;
            this.headers = new LinkedHashMap<>(headers);
            this.body = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int status() { return status; }

        @Override
        public Map<String, List<String>> headers() { return headers; }

        @Override
        public InputStream body() { return body; }

        @Override
        public void close() { }
    }
}
