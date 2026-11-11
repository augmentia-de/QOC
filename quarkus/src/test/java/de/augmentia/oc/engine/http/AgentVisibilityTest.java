package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(AgentVisibilityTest.Profile.class)
class AgentVisibilityTest {

    static final Path WORKSPACE = Path.of("/tmp/opencode/qoc-agent-visibility-workspace");

    public static class Profile implements QuarkusTestProfile {
        static {
            try {
                if (Files.exists(WORKSPACE)) {
                    Files.walk(WORKSPACE).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                    });
                }
                Files.createDirectories(WORKSPACE.resolve("alice"));
                Files.writeString(WORKSPACE.resolve("settings.json"), """
                        {
                          "security": { "mode": "proxy", "trustedProxies": ["127.0.0.1/32"] },
                          "agents": [
                            { "name": "research", "roles": ["qoc-user"] },
                            { "name": "secret" }
                          ]
                        }
                        """);
            } catch (Exception ignored) {
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>();
            overrides.put("engine.workspace", WORKSPACE.toString());
            return overrides;
        }
    }

    @BeforeAll
    static void ensureWorkspace() throws Exception {
        Files.createDirectories(WORKSPACE.resolve("alice"));
    }

    @Test
    void nonAdminSeesOnlyAllowedAgents() {
        given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "alice")
                .header("X-Forwarded-Roles", "qoc-user")
                .get("/api/agent")
                .then()
                .statusCode(200)
                .body("canAuthor", org.hamcrest.Matchers.is(false))
                .body("data.name", hasItems("build", "plan", "research"))
                .body("data.name", not(hasItem("secret")));
    }

    @Test
    void adminSeesAllAgents() {
        given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "bob")
                .header("X-Forwarded-Roles", "qoc-admin")
                .get("/api/agent")
                .then()
                .statusCode(200)
                .body("canAuthor", org.hamcrest.Matchers.is(true))
                .body("data.name", hasItem("secret"));
    }

    @Test
    void nonAdminCannotFetchAdminOnlyAgent() {
        given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "alice")
                .header("X-Forwarded-Roles", "qoc-user")
                .get("/api/agent/secret")
                .then()
                .statusCode(403);
    }

    @Test
    void nonAdminCannotCreateSessionWithAdminOnlyAgent() {
        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "alice")
                .header("X-Forwarded-Roles", "qoc-user")
                .body("{\"agent\":\"secret\"}")
                .post("/api/session")
                .then()
                .statusCode(403);
    }

    @Test
    void explicitRoleAllowsSessionCreation() {
        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "alice")
                .header("X-Forwarded-Roles", "qoc-user")
                .body("{\"agent\":\"research\"}")
                .post("/api/session")
                .then()
                .statusCode(200);
    }
}
