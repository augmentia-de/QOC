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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end compaction check with a deterministic provider.
 *
 * <p>It measures the estimated context tokens through the same
 * {@code ContextBuilder} path the model uses, then triggers the public compact
 * endpoint and measures again.</p>
 */
@QuarkusTest
@TestProfile(CompactionE2ETest.Profile.class)
class CompactionE2ETest {

    private static final Path WORKSPACE = Path.of("/tmp/qoc-compaction-e2e");
    private static final int MESSAGE_COUNT = 12;

    @jakarta.inject.Inject
    Engine engine;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>();
            overrides.put("engine.provider.scripted", "true");
            overrides.put("engine.workspace", WORKSPACE.toString());
            overrides.put("engine.instructions.enabled", "true");
            overrides.put("engine.revert.snapshot.enabled", "false");
            overrides.put("engine.llm-log.enabled", "false");
            return overrides;
        }
    }

    @BeforeAll
    static void setupWorkspace() throws Exception {
        Files.createDirectories(WORKSPACE.resolve("src"));
        Files.writeString(WORKSPACE.resolve("AGENTS.md"), "# Compaction E2E\n");
    }

    @Test
    void compactionReducesThePreparedContext() {
        String sessionId = createSession();
        String payload = "Compaction context probe. ".repeat(80);

        for (int i = 0; i < MESSAGE_COUNT; i++) {
            given()
                    .contentType("application/json")
                    .body(Map.of("text", payload + " turn " + i))
                    .post("/api/session/" + sessionId + "/prompt")
                    .then().statusCode(200);
        }

        int before = engine.contextTokens(de.augmentia.oc.engine.core.model.SessionId.parse(sessionId));
        var compact = given()
                .post("/api/session/" + sessionId + "/compact")
                .then().statusCode(200)
                .extract().response();
        int after = engine.contextTokens(de.augmentia.oc.engine.core.model.SessionId.parse(sessionId));

        System.out.println("compaction context size: before=" + before + " tokens, after=" + after + " tokens");
        assertThat(compact.jsonPath().getBoolean("compacted")).isTrue();
        assertThat(before).isGreaterThan(0);
        assertThat(after).isGreaterThan(0);
        assertThat(after).isLessThan(before);

        given()
                .contentType("application/json")
                .body(Map.of("text", "After compaction, answer briefly."))
                .post("/api/session/" + sessionId + "/prompt")
                .then().statusCode(200);
    }

    private static String createSession() {
        return given()
                .contentType("application/json")
                .body(Map.of())
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");
    }
}
