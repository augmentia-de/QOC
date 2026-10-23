package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.runtime.tool.mcp.McpFailure;
import de.augmentia.oc.engine.runtime.tool.mcp.McpOAuth;
import de.augmentia.oc.engine.testing.ScriptedHttp;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpOAuthTest {

    private static final String METADATA = """
            {"authorization_endpoint":"https://auth.example/authorize",
             "token_endpoint":"https://auth.example/token",
             "registration_endpoint":"https://auth.example/register",
             "scopes_supported":["mcp","read"]}""";
    private static final String TOKEN = """
            {"access_token":"access-123","refresh_token":"refresh-456","expires_in":3600}""";

    @Test
    void discoversMetadata() {
        ScriptedHttp transport = new ScriptedHttp(new ScriptedHttp.Response(200, Map.of(),
                METADATA.getBytes()));
        McpOAuth.OAuthMetadata metadata = McpOAuth.discover(transport, "https://server.example/mcp");

        assertThat(metadata.authorizationEndpoint()).isEqualTo("https://auth.example/authorize");
        assertThat(metadata.tokenEndpoint()).isEqualTo("https://auth.example/token");
        assertThat(metadata.registrationEndpoint().orElseThrow()).isEqualTo("https://auth.example/register");
        assertThat(metadata.scopes()).containsExactly("mcp", "read");
    }

    @Test
    void buildsAuthorizationUrl() {
        McpOAuth.OAuthMetadata metadata = new McpOAuth.OAuthMetadata(
                "https://auth.example/authorize", "https://auth.example/token",
                de.augmentia.oc.engine.core.model.Option.none(), List.of("mcp"));
        String url = McpOAuth.authorizationUrl(metadata, "client-1", "http://localhost/cb",
                "state-42", "challenge", List.of("mcp"));

        assertThat(url).startsWith("https://auth.example/authorize?");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("client_id=client-1");
        assertThat(url).contains("state=state-42");
        assertThat(url).contains("code_challenge=challenge");
        assertThat(url).contains("code_challenge_method=S256");
    }

    @Test
    void exchangesCodeForToken() {
        ScriptedHttp transport = new ScriptedHttp(new ScriptedHttp.Response(200, Map.of(),
                TOKEN.getBytes()));
        McpOAuth.OAuthToken token = McpOAuth.exchangeCode(transport, "https://auth.example/token",
                "client-1", "secret", "auth-code", "http://localhost/cb", "verifier");

        assertThat(token.accessToken()).isEqualTo("access-123");
        assertThat(token.refreshToken().orElseThrow()).isEqualTo("refresh-456");
        assertThat(token.expiresAt().orElseThrow()).isAfter(java.time.Instant.now());
    }

    @Test
    void missingAccessTokenFails() {
        ScriptedHttp transport = new ScriptedHttp(new ScriptedHttp.Response(200, Map.of(),
                "{\"token_type\":\"bearer\"}".getBytes()));
        assertThatThrownBy(() -> McpOAuth.exchangeCode(transport, "https://auth.example/token",
                "client-1", null, "code", "http://localhost/cb", null))
                .isInstanceOf(McpFailure.class);
    }

    @Test
    void refreshesToken() {
        ScriptedHttp transport = new ScriptedHttp(new ScriptedHttp.Response(200, Map.of(),
                "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":1800}".getBytes()));
        McpOAuth.OAuthToken token = McpOAuth.refreshToken(transport, "https://auth.example/token",
                "client-1", "secret", "old-refresh");

        assertThat(token.accessToken()).isEqualTo("new-access");
        assertThat(token.refreshToken().orElseThrow()).isEqualTo("new-refresh");
    }

    @Test
    void missingRefreshTokenFails() {
        ScriptedHttp transport = new ScriptedHttp();
        assertThatThrownBy(() -> McpOAuth.refreshToken(transport, "https://auth.example/token",
                "client-1", null, ""))
                .isInstanceOf(McpFailure.class);
    }
}
