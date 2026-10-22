package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(AuthTest.Profile.class)
class AuthTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("engine.auth.password", "secret");
        }
    }

    @Test
    void requestsWithoutCredentialsAreRejected() {
        given().get("/api/config")
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("Basic"));
    }

    @Test
    void correctBasicCredentialsPass() {
        given().auth().basic("opencode", "secret")
                .get("/api/config")
                .then().statusCode(200);
    }

    @Test
    void wrongPasswordIsRejected() {
        given().auth().basic("opencode", "wrong")
                .get("/api/config")
                .then().statusCode(401);
    }
}
