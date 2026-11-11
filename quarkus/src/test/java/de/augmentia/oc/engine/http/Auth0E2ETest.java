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
import static org.hamcrest.Matchers.equalTo;

/**
 * Auth0 OIDC end-to-end tests. Require environment variables:
 *   OIDC_ISSUER     - Auth0 issuer URL (e.g. https://dev-xxx.us.auth0.com/)
 *   OIDC_CLIENT_ID  - Auth0 client ID
 *   OIDC_TEST_TOKEN - A valid Auth0 access token (for withToken tests)
 *   OIDC_ADMIN_TOKEN - An Auth0 access token with admin role
 *   OIDC_USER_TOKEN  - An Auth0 access token with user role (no admin)
 *
 * Tests are skipped when the required env vars are not set.
 */
@QuarkusTest
@TestProfile(Auth0E2ETest.Profile.class)
class Auth0E2ETest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>();
            String issuer = System.getenv("OIDC_ISSUER");
            if (issuer != null && !issuer.isBlank()) {
                overrides.put("quarkus.oidc.enabled", "true");
                overrides.put("quarkus.oidc.auth-server-url", issuer);
                overrides.put("quarkus.oidc.token.issuer", issuer);
            }
            String clientId = System.getenv("OIDC_CLIENT_ID");
            if (clientId != null && !clientId.isBlank()) {
                overrides.put("quarkus.oidc.client-id", clientId);
            }
            String audience = System.getenv("OIDC_AUDIENCE");
            if (audience != null && !audience.isBlank()) {
                overrides.put("quarkus.oidc.token.audience", audience);
            }
            overrides.put("engine.security.mode", "oidc");
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
    void withValidTokenIsAccepted() {
        String token = System.getenv("OIDC_TEST_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isBlank(), "OIDC_TEST_TOKEN not set");
        given().header("Authorization", "Bearer " + token)
                .get("/api/config")
                .then().statusCode(200);
    }

    @Test
    void withInvalidTokenIsRejected() {
        Assumptions.assumeTrue(System.getenv("OIDC_ISSUER") != null, "OIDC_ISSUER not set");
        given().header("Authorization", "Bearer invalid.token.here")
                .get("/api/config")
                .then().statusCode(401);
    }

    @Test
    void adminTokenCanAccessAdminEndpoints() {
        String token = System.getenv("OIDC_ADMIN_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isBlank(), "OIDC_ADMIN_TOKEN not set");
        given().header("Authorization", "Bearer " + token)
                .get("/api/metrics")
                .then().statusCode(200);
    }

    @Test
    void userTokenCannotAccessAdminEndpoints() {
        String token = System.getenv("OIDC_USER_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isBlank(), "OIDC_USER_TOKEN not set");
        given().header("Authorization", "Bearer " + token)
                .get("/api/metrics")
                .then().statusCode(403);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = System.getenv("OIDC_EXPIRED_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isBlank(), "OIDC_EXPIRED_TOKEN not set");
        given().header("Authorization", "Bearer " + token)
                .get("/api/config")
                .then().statusCode(401);
    }
}
