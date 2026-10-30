package de.augmentia.qoc.infratest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class InfratestResourceTest {

    @Test
    void createReadUpdateDeleteById() {
        Response created = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":" alpha ","content":"first"}
                        """)
                .when().post("/infratest")
                .then()
                .statusCode(201)
                .header("Location", containsString("/infratest/"))
                .body("name", is("alpha"))
                .body("content", is("first"))
                .extract().response();

        long id = created.jsonPath().getLong("id");

        given()
                .when().get("/infratest/{id}", id)
                .then()
                .statusCode(200)
                .body("id", is((int) id))
                .body("name", is("alpha"))
                .body("content", is("first"));

        given()
                .when().get("/infratest")
                .then()
                .statusCode(200)
                .body("id", hasItem((int) id));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"beta","content":"second"}
                        """)
                .when().put("/infratest/{id}", id)
                .then()
                .statusCode(200)
                .body("id", is((int) id))
                .body("name", is("beta"))
                .body("content", is("second"));

        given()
                .when().delete("/infratest/{id}", id)
                .then()
                .statusCode(204);

        given()
                .when().get("/infratest/{id}", id)
                .then()
                .statusCode(404);
    }

    @Test
    void rejectsBlankName() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"   ","content":"value"}
                        """)
                .when().post("/infratest")
                .then()
                .statusCode(400);
    }

    @Test
    void rejectsTooLongName() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + "x".repeat(256) + "\"}")
                .when().post("/infratest")
                .then()
                .statusCode(400);
    }

    @Test
    void unknownIdReturnsNotFoundForEveryOperation() {
        long id = 999_999_999L;

        given()
                .when().get("/infratest/{id}", id)
                .then()
                .statusCode(404);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"missing","content":"value"}
                        """)
                .when().put("/infratest/{id}", id)
                .then()
                .statusCode(404);

        given()
                .when().delete("/infratest/{id}", id)
                .then()
                .statusCode(404);
    }
}
