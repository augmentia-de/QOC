package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Optional registry role: the agent catalogue/authoring API stays available,
 * while prompt/session execution and MCP tool calls are rejected.
 */
@QuarkusTest
@TestProfile(RegistryModeTest.RegistryProfile.class)
class RegistryModeTest {

    @Test
    void healthExposesRegistryRole() {
        given().get("/api/health")
                .then().statusCode(200)
                .body("role", equalTo("registry"))
                .body("mode", equalTo("server"));
    }

    @Test
    void executionEndpointsAreRejected() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("prompt", "hello"))
                .post("/api/run")
                .then().statusCode(503)
                .body("_tag", equalTo("RegistryMode"));

        given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(503)
                .body("_tag", equalTo("RegistryMode"));
    }

    @Test
    void agentCatalogueStaysAvailable() {
        given().get("/api/agent")
                .then().statusCode(200)
                .body("data", notNullValue());

        given().get("/api/agent/plan")
                .then().statusCode(200)
                .body("name", equalTo("plan"))
                .body("config", notNullValue());
    }

    @Test
    void mcpToolCallsAreRejected() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "tools/call",
                        "params", Map.of("name", "qoc_run_agent")))
                .post("/mcp")
                .then().statusCode(503);
    }

    public static class RegistryProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("engine.role", "registry");
        }
    }
}
