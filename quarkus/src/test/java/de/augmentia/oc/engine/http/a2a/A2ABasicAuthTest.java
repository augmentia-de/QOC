package de.augmentia.oc.engine.http.a2a;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

/**
 * Verifies that the Vert.x-based A2A endpoints honour QOC's Basic auth mode.
 * The regular JAX-RS filter never runs for these routes, so this exercises the
 * dedicated {@link A2AAuthenticationMechanism}.
 */
@QuarkusTest
@TestProfile(A2ABasicAuthTest.BasicProfile.class)
class A2ABasicAuthTest {

    @Test
    void requestWithoutCredentialsIsRejected() throws Exception {
        given()
                .contentType("application/json")
                .header("A2A-Version", "1.0")
                .body(requestBody())
                .post("/")
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("Basic"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        given()
                .contentType("application/json")
                .header("A2A-Version", "1.0")
                .auth().basic("opencode", "wrong")
                .body(requestBody())
                .post("/")
                .then().statusCode(401);
    }

    @Test
    void correctCredentialsReachTheAgent() throws Exception {
        given()
                .contentType("application/json")
                .header("A2A-Version", "1.0")
                .auth().basic("opencode", "test-secret")
                .body(requestBody())
                .post("/")
                .then().statusCode(200)
                .body("result", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void agentCardIsPublic() {
        given().get("/.well-known/agent-card.json")
                .then().statusCode(200);
    }

    @Test
    void discoveryRequiresAuthentication() {
        given().get("/api/a2a/agents")
                .then().statusCode(401);
    }

    @Test
    void discoveryWorksWithCredentials() {
        given().auth().basic("opencode", "test-secret")
                .get("/api/a2a/agents")
                .then().statusCode(200)
                .body("data", hasSize(0));
    }

    private static String requestBody() throws Exception {
        MessageSendParams params = new MessageSendParams(A2A.toUserMessage("Hallo"), null, null);
        return JsonUtil.toJson(new SendMessageRequest("1", params));
    }

    public static class BasicProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "engine.a2a.server.enabled", "true",
                    "engine.a2a.server.name", "QOC Test Agent",
                    "engine.a2a.server.base-url", "http://localhost:8080",
                    "engine.auth.password", "test-secret");
        }
    }
}
