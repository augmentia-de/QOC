package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.QuestionSource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@QuarkusTest
class QuestionResourceTest {

    @Inject
    WebQuestionSource webQuestionSource;

    @Test
    void listsAndResolvesPendingQuestion() {
        String id = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        var future = new CompletableFuture<QuestionSource.Answer>();
        String questionId = webQuestionSource.registerExternalFuture(
                future,
                SessionId.parse(id),
                List.of(new QuestionSource.Question("Header", "Which option?", List.of(
                        new QuestionSource.Option("A", "first"),
                        new QuestionSource.Option("B", "second")), false)));

        try {
            given().get("/api/questions")
                    .then().statusCode(200)
                    .body("data.size()", greaterThanOrEqualTo(1))
                    .body("data.find { it.id == '" + questionId + "' }.sessionId", equalTo(id));

            given().get("/api/session/" + id + "/question")
                    .then().statusCode(200)
                    .body("id", equalTo(questionId))
                    .body("questions[0].question", equalTo("Which option?"));

            given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("selected", List.of("A"), "text", "because"))
                    .post("/api/session/" + id + "/question/" + questionId + "/reply")
                    .then().statusCode(200)
                    .body("ok", equalTo(true));

            assertThat(future.join().selected()).containsExactly("A");
            assertThat(future.join().text()).isEqualTo("because");
        } finally {
            if (!future.isDone()) {
                future.complete(new QuestionSource.Answer(List.of(), ""));
            }
            webQuestionSource.cleanupExternalFuture(questionId);
            given().delete("/api/session/" + id);
        }
    }
}
