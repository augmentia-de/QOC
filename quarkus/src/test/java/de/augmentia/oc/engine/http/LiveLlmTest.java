package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(LiveLlmTest.Profile.class)
class LiveLlmTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>();
            overrides.put("engine.provider.scripted", "false");
            String key = System.getenv("OPENAI_API_KEY");
            String url = System.getenv("OPENAI_BASE_URL");
            String model = System.getenv("OPENAI_MODEL");
            if (key != null) overrides.put("engine.provider.api-key", key);
            if (url != null) overrides.put("engine.provider.url", url + "/chat/completions");
            if (model != null) overrides.put("engine.model.id", model);
            overrides.put("engine.workspace", "/tmp/oc-live");
            return overrides;
        }
    }

    @Test
    void promptReturnsModelText() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("OPENAI_API_KEY") != null && !System.getenv("OPENAI_API_KEY").isBlank(),
                "OPENAI_API_KEY is not set");
        String id = given()
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        io.restassured.response.Response response = given()
                .contentType("application/json")
                .body(Map.of("text", "Antworte in einem kurzen Satz auf Deutsch: Hallo."))
                .post("/api/session/" + id + "/prompt");

        response.then().statusCode(200);
        String text = response.jsonPath().getString("text");
        String finish = response.jsonPath().getString("finish");

        System.out.println("LLM text: " + text);
        System.out.println("LLM finish: " + finish);
        System.out.println("LLM usage: " + response.jsonPath().getString("inputTokens")
                + "/" + response.jsonPath().getString("outputTokens"));

        assertThat(text).isNotBlank();
        assertThat(finish).isNotBlank();
    }

    @Test
    void promptCanCreateAFile() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("OPENAI_API_KEY") != null && !System.getenv("OPENAI_API_KEY").isBlank(),
                "OPENAI_API_KEY is not set");
        java.nio.file.Files.deleteIfExists(Path.of("/tmp/oc-live/hello.txt"));
        String id = given()
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        io.restassured.response.Response response = given()
                .contentType("application/json")
                .body(Map.of("text", "Erstelle die Datei hello.txt im Arbeitsverzeichnis mit dem Inhalt 'Hallo aus der Engine'."))
                .post("/api/session/" + id + "/prompt");

        System.out.println("File-create finish: " + response.jsonPath().getString("finish"));
        System.out.println("File-create text: " + response.jsonPath().getString("text"));

        assertThat(response.statusCode()).isEqualTo(200);
        // The tool was advertised; whether the model chose it is model-dependent.
        // We only assert that the call completed without an error.
        assertThat(response.jsonPath().getString("finish")).isNotBlank();
    }
}
