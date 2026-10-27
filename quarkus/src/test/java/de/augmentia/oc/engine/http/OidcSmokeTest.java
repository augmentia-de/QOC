package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(OidcSmokeTest.Profile.class)
class OidcSmokeTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>();
            String issuer = System.getenv("OIDC_ISSUER");
            String clientId = System.getenv("OIDC_CLIENT_ID");
            if (issuer != null && !issuer.isBlank()) {
                overrides.put("quarkus.oidc.enabled", "true");
                overrides.put("quarkus.oidc.auth-server-url", issuer);
            }
            if (clientId != null && !clientId.isBlank()) {
                overrides.put("quarkus.oidc.client-id", clientId);
            }
            return overrides;
        }
    }

    @Test
    void withoutTokenIsRejected() {
        Assumptions.assumeTrue(System.getenv("OIDC_ISSUER") != null, "OIDC_ISSUER not set");
        given().get("/api/config")
                .then().statusCode(401);
    }

    @Test
    void withTokenIsAccepted() {
        String token = System.getenv("OIDC_TEST_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isBlank(), "OIDC_TEST_TOKEN not set");
        given().header("Authorization", "Bearer " + token)
                .get("/api/config")
                .then().statusCode(200);
    }
}
