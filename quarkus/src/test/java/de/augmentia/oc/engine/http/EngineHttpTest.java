package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class EngineHttpTest {

    @Test
    void createSessionPromptAndReadItBack() {
        String id = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .body("id", containsString("ses_"))
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("text", "Hallo"))
                .post("/api/session/" + id + "/prompt")
                .then().statusCode(200)
                .body("text", containsString("Session Engine"))
                .body("finish", equalTo("NORMAL"));

        given()
                .get("/api/session/" + id)
                .then().statusCode(200)
                .body("id", equalTo(id))
                .body("directory", containsString("qoc"));

        given()
                .accept("text/event-stream")
                .get("/api/event?session=" + id)
                .then().statusCode(200)
                .body(containsString("SessionCreated"))
                .body(containsString("InboxDelivered"));
    }

    @Test
    void unknownSessionIsNotFound() {
        given().get("/api/session/ses_" + "0".repeat(64))
                .then().statusCode(404);
    }
}
