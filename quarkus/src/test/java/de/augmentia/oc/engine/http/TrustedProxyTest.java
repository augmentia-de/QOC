package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.endsWith;

@QuarkusTest
@TestProfile(TrustedProxyTest.Profile.class)
class TrustedProxyTest {

    static final Path WORKSPACE = Path.of("/tmp/qoc-trusted-proxy-workspace");

    public static class Profile implements QuarkusTestProfile {
        static {
            try {
                if (Files.exists(WORKSPACE)) {
                    Files.walk(WORKSPACE).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
                Files.createDirectories(WORKSPACE.resolve("alice"));
                Files.writeString(WORKSPACE.resolve("settings.json"),
                        "{ \"security\": { \"mode\": \"proxy\", \"trustedProxies\": [\"127.0.0.1/32\"] } }");
            } catch (Exception ignored) {}
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
        if (!Files.exists(WORKSPACE.resolve("alice"))) {
            Files.createDirectories(WORKSPACE.resolve("alice"));
        }
    }

    @Test
    void trustedProxyHeaderIsAccepted() {
        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Forwarded-User", "alice")
                .body("{}")
                .post("/api/session")
                .then()
                .statusCode(200)
                .body("data.directory", endsWith("alice"));
    }

    @Test
    void untrustedProxyHeaderIsIgnored() {
        given()
                .contentType("application/json")
                .header("X-Forwarded-For", "10.0.0.5")
                .header("X-Forwarded-User", "alice")
                .body("{}")
                .post("/api/session")
                .then()
                .statusCode(401);
    }
}
