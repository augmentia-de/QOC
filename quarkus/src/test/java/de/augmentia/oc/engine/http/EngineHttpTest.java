package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class EngineHttpTest {

    @Test
    void healthEndpointsAreAvailableWithoutBusinessAuth() {
        given().get("/api/health/live")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
        given().get("/api/health/ready")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }

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
                .body("outcome", equalTo("COMPLETED"))
                .body("text", containsString("Session Engine"))
                .body("finish", equalTo("NORMAL"))
                .body("structured", nullValue());

        given()
                .get("/api/session/" + id)
                .then().statusCode(200)
                .body("id", equalTo(id))
                .body("directory", containsString("qoc"));

        given()
                .get("/api/session/" + id + "/messages")
                .then().statusCode(200)
                .body(containsString("Hallo"));

        given()
                .accept("text/event-stream")
                .get("/api/event?session=" + id + "&live=false")
                .then().statusCode(200)
                .body(containsString("SessionCreated"))
                .body(containsString("InboxDelivered"));

        given()
                .post("/api/session/" + id + "/interrupt")
                .then().statusCode(200)
                .body("interrupted", equalTo(false));

        given().get("/api/agent").then().statusCode(200)
                .body(containsString("build"))
                .body(containsString("plan"));
        given().get("/api/skill").then().statusCode(200)
                .body("data", notNullValue());
        given().get("/api/skill?all=true").then().statusCode(200)
                .body("data", notNullValue());
        given().get("/api/session/" + id + "/todo").then().statusCode(200)
                .body("data", notNullValue());
        given().get("/api/session/" + id + "/question").then().statusCode(200)
                .body("id", equalTo(""));
        given().get("/api/config").then().statusCode(200).body("[1].value", equalTo("gpt-4o-mini"));
        given().get("/api/model").then().statusCode(200).body("data[0].context", equalTo(128000));
    }

    @Test
    void backgroundRegistriesAreExposedAndScopedToTheSession() {
        String first = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");
        String second = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        given().get("/api/session/" + first + "/processes")
                .then().statusCode(200).body("data", notNullValue());
        given().get("/api/session/" + first + "/tasks")
                .then().statusCode(200).body("data", notNullValue());
        given().post("/api/session/" + first + "/processes/sh_missing/kill")
                .then().statusCode(200).body("killed", equalTo(false));
        given().post("/api/session/" + first + "/tasks/task_missing/cancel")
                .then().statusCode(200).body("cancelled", equalTo(false));

        given().delete("/api/session/" + first).then().statusCode(204);
        given().delete("/api/session/" + second).then().statusCode(204);
    }

    @Test
    void treeAndForkExposeTheBranchBoundary() {
        String parent = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("text", "fork me"))
                .post("/api/session/" + parent + "/prompt")
                .then().statusCode(200);

        String boundary = given().get("/api/session/" + parent + "/tree")
                .then().statusCode(200)
                .body("entries.size()", equalTo(2))
                .body("parentSession", equalTo(""))
                .body("leaf", notNullValue())
                .extract().path("entries[0].id");
        int parentSize = given().get("/api/session/" + parent + "/tree")
                .then().statusCode(200)
                .extract().path("entries.size()");

        String child = given()
                .contentType(ContentType.JSON)
                .body(Map.of("boundary", boundary))
                .post("/api/session/" + parent + "/fork")
                .then().statusCode(200)
                .body("id", containsString("ses_"))
                .extract().path("id");

        given().get("/api/session/" + child + "/tree")
                .then().statusCode(200)
                .body("entries.size()", equalTo(1))
                .body("parentSession", equalTo(parent));
        given().get("/api/session/" + child + "/messages")
                .then().statusCode(200)
                .body(containsString("fork me"));
        given().get("/api/session/" + parent + "/tree")
                .then().statusCode(200)
                .body("entries.size()", equalTo(parentSize));

        given().contentType(ContentType.JSON)
                .body(Map.of("boundary", "msg_0000000000000000000000000000000000000000000000000000000000000000"))
                .post("/api/session/" + parent + "/fork")
                .then().statusCode(400);

        given().delete("/api/session/" + child).then().statusCode(204);
        given().delete("/api/session/" + parent).then().statusCode(204);
    }

    @Test
    void switchAgentInAnExistingSession() {
        String buildId = given().get("/api/agent")
                .then().statusCode(200)
                .extract().jsonPath().getString("data.find { it.name == 'build' }.id");
        String planId = given().get("/api/agent")
                .then().statusCode(200)
                .extract().jsonPath().getString("data.find { it.name == 'plan' }.id");

        String id = given()
                .contentType(ContentType.JSON)
                .body(Map.of("agent", buildId))
                .post("/api/session")
                .then().statusCode(200)
                .body("data.agent", equalTo(buildId))
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("agent", "plan"))
                .post("/api/session/" + id + "/agent")
                .then().statusCode(200)
                .body("agent", equalTo(planId))
                .body("name", equalTo("plan"));

        given().get("/api/session/" + id)
                .then().statusCode(200)
                .body("agent", equalTo(planId));

        given().accept("text/event-stream")
                .get("/api/event?session=" + id + "&live=false")
                .then().statusCode(200)
                .body(containsString("SessionAgentSelected"));

        given().contentType(ContentType.JSON)
                .body(Map.of("agent", "does-not-exist"))
                .post("/api/session/" + id + "/agent")
                .then().statusCode(400);

        given().delete("/api/session/" + id).then().statusCode(204);
    }

    @Test
    void deleteSessionRemovesIt() {
        String id = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        given().delete("/api/session/" + id)
                .then().statusCode(204);
        given().get("/api/session/" + id)
                .then().statusCode(404);
    }

    @Test
    void revertRemovesTheLastUserTurn() {
        String id = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("text", "revert me"))
                .post("/api/session/" + id + "/prompt")
                .then().statusCode(200);

        given().get("/api/session/" + id + "/messages")
                .then().statusCode(200)
                .body(containsString("revert me"));

        given().post("/api/session/" + id + "/revert")
                .then().statusCode(200)
                .body("reverted", equalTo(true));

        given().get("/api/session/" + id + "/messages")
                .then().statusCode(200)
                .body(not(containsString("revert me")));
    }

    @Test
    void compactReturnsACompactedResult() {
        String id = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        for (int i = 0; i < 5; i++) {
            given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("text", "compact me " + i))
                    .post("/api/session/" + id + "/prompt")
                    .then().statusCode(200);
        }

        given().post("/api/session/" + id + "/compact")
                .then().statusCode(200)
                .body("compacted", equalTo(true));
    }

    @Test
    void promptWithResponseSchemaReturnsStructuredResult() {
        String id = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "text", "Gib mir ein strukturiertes Ergebnis",
                        "responseSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("answer", Map.of("type", "string")),
                                "required", List.of("answer"))))
                .post("/api/session/" + id + "/prompt")
                .then().statusCode(200)
                .body("finish", equalTo("NORMAL"))
                .body("structured.answer", equalTo("structured"));
    }

    @Test
    void invalidResponseSchemaIsRejected() {
        String id = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "text", "Gib mir ein strukturiertes Ergebnis",
                        "responseSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("answer", Map.of("type", "unknown")))))
                .post("/api/session/" + id + "/prompt")
                .then().statusCode(400)
                .body("message", containsString("responseSchema"));
    }

    @Test
    void unknownSessionIsNotFound() {
        given().get("/api/session/ses_" + "0".repeat(64))
                .then().statusCode(404);
    }
}
