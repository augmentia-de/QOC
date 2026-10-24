package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(EngineAgentSelectionTest.Profile.class)
class EngineAgentSelectionTest {

    public static class Profile implements QuarkusTestProfile {

        private static final Path WORKSPACE = workspace();

        private static Path workspace() {
            try {
                Path directory = Files.createTempDirectory("qoc-agent-selection");
                Files.writeString(directory.resolve("settings.json"), """
                        {
                          "agents": [
                            {"name": "build", "hidden": false},
                            {"name": "plan", "hidden": true}
                          ]
                        }
                        """);
                directory.toFile().deleteOnExit();
                directory.resolve("settings.json").toFile().deleteOnExit();
                return directory;
            } catch (IOException e) {
                throw new IllegalStateException("cannot create test workspace", e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("engine.workspace", WORKSPACE.toString());
        }
    }

    @Test
    void createSessionSelectsAnAgentByNameOrId() {
        String planId = given().get("/api/agent")
                .then().statusCode(200)
                .extract().jsonPath().getString("data.find { it.name == 'plan' }.id");
        assertThat(planId).isNotBlank();

        for (String selector : List.of("plan", planId)) {
            String id = given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("agent", selector))
                    .post("/api/session")
                    .then().statusCode(200)
                    .extract().path("id");

            given().get("/api/session/" + id)
                    .then().statusCode(200)
                    .body("agent", equalTo(planId));
        }
    }

    @Test
    void hiddenAgentsRemainAddressableByApi() {
        String hiddenId = given().get("/api/agent")
                .then().statusCode(200)
                .extract().jsonPath().getString("data.find { it.name == 'plan' }.id");

        String id = given()
                .contentType(ContentType.JSON)
                .body(Map.of("agent", hiddenId))
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        given().get("/api/session/" + id)
                .then().statusCode(200)
                .body("agent", equalTo(hiddenId));
    }
}
