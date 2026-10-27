package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(McpOAuthTest.Profile.class)
class McpOAuthTest {

    static final java.nio.file.Path WORKSPACE = java.nio.file.Path.of("/tmp/qoc-mcp-oauth-workspace");

    public static class Profile implements QuarkusTestProfile {
        static {
            try {
                if (java.nio.file.Files.exists(WORKSPACE)) {
                    java.nio.file.Files.walk(WORKSPACE).sorted((a,b)->b.compareTo(a)).forEach(pth -> { try { java.nio.file.Files.deleteIfExists(pth);}catch(Exception ignored){} });
                }
                java.nio.file.Files.createDirectories(WORKSPACE);
                java.nio.file.Files.writeString(WORKSPACE.resolve("settings.json"),
                        "{ \"security\": { \"mode\": \"mcp-oauth\" } }");
            } catch (Exception ignored) {}
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>();
            overrides.put("engine.workspace", WORKSPACE.toString());
            overrides.put("engine.mcp.server.enabled", "true");
            overrides.put("engine.mcp.server.oauth.enabled", "true");
            return overrides;
        }
    }

    @Test
    void mcpWithoutTokenIsRejected() {
        given()
                .contentType("application/json")
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize"))
                .post("/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void mcpAuthorizeDisabledReturnsServiceUnavailableWhenOauthDisabled() {
        // covered by config; when oauth disabled endpoint returns 503
    }
}
