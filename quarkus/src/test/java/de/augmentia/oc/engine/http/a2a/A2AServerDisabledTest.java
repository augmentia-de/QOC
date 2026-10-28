package de.augmentia.oc.engine.http.a2a;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The A2A server is opt-in. With the default profile the routes must not exist.
 */
@QuarkusTest
class A2AServerDisabledTest {

    @Test
    void agentCardIsHidden() {
        given().get("/.well-known/agent-card.json")
                .then().statusCode(404);
    }

    @Test
    void tenantAgentCardIsHidden() {
        given().get("/.well-known/acme/agent-card.json")
                .then().statusCode(404);
    }

    @Test
    void jsonRpcEndpointIsHidden() {
        given()
                .contentType("application/json")
                .body("""
                        {"jsonrpc":"2.0","id":"1","method":"message/send",
                         "params":{"message":{"role":"ROLE_USER","messageId":"m1",
                         "parts":[{"text":"Hallo"}]}}}
                        """)
                .post("/")
                .then().statusCode(404);
    }

    @Test
    void regularApiStillWorks() {
        given().get("/api/health/live")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }
}
