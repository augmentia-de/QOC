package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;

/**
 * Ownership isolation for workflows and agents in proxy mode.
 */
@QuarkusTest
@TestProfile(WorkflowOwnershipTest.Profile.class)
class WorkflowOwnershipTest {

    static final java.nio.file.Path WORKSPACE = java.nio.file.Path.of("/tmp/opencode/qoc-ownership-workspace");

    public static class Profile implements QuarkusTestProfile {
        static {
            try {
                if (java.nio.file.Files.exists(WORKSPACE)) {
                    java.nio.file.Files.walk(WORKSPACE).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
                java.nio.file.Files.createDirectories(WORKSPACE);
                java.nio.file.Files.writeString(WORKSPACE.resolve("settings.json"), """
                        { "security": { "mode": "proxy", "trustedProxies": ["127.0.0.1/32"] } }
                        """);
            } catch (Exception ignored) {
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>();
            overrides.put("engine.workspace", WORKSPACE.toString());
            overrides.put("engine.security.mode", "proxy");
            return overrides;
        }
    }

    @Test
    void workflowCreatedByUserVisibleToOwner() {
        // alice creates a workflow
        String id = given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "alice")
                .header("X-Forwarded-Roles", "user")
                .contentType("application/json")
                .body("{\"name\":\"alice-wf\"}")
                .post("/api/workflows")
                .then()
                .statusCode(201)
                .extract().path("id");

        // alice can read it
        given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "alice")
                .header("X-Forwarded-Roles", "user")
                .get("/api/workflows/" + id)
                .then()
                .statusCode(200);

        // bob cannot read it
        given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "bob")
                .header("X-Forwarded-Roles", "user")
                .get("/api/workflows/" + id)
                .then()
                .statusCode(403);
    }

    @Test
    void adminSeesAllWorkflows() {
        String id = given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "carol")
                .header("X-Forwarded-Roles", "user")
                .contentType("application/json")
                .body("{\"name\":\"carol-wf\"}")
                .post("/api/workflows")
                .then()
                .statusCode(201)
                .extract().path("id");

        // admin can read it
        given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "admin")
                .header("X-Forwarded-Roles", "admin")
                .get("/api/workflows/" + id)
                .then()
                .statusCode(200);
    }

    @Test
    void agentCreatedByUserVisibleToOwner() {
        String id = given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "alice")
                .header("X-Forwarded-Roles", "user")
                .contentType("application/json")
                .body("{\"name\":\"alice-agent\"}")
                .post("/api/agents")
                .then()
                .statusCode(201)
                .extract().path("id");

        // alice can read it
        given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "alice")
                .header("X-Forwarded-Roles", "user")
                .get("/api/agents/" + id)
                .then()
                .statusCode(200);

        // bob cannot read it
        given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "bob")
                .header("X-Forwarded-Roles", "user")
                .get("/api/agents/" + id)
                .then()
                .statusCode(403);
    }

    @Test
    void nonOwnerCannotDeleteWorkflow() {
        String id = given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "alice")
                .header("X-Forwarded-Roles", "user")
                .contentType("application/json")
                .body("{\"name\":\"alice-wf2\"}")
                .post("/api/workflows")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "bob")
                .header("X-Forwarded-Roles", "user")
                .delete("/api/workflows/" + id)
                .then()
                .statusCode(403);
    }
}
