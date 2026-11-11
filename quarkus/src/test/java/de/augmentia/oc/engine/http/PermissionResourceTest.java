package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.adapter.sql.PendingApprovalSql;
import de.augmentia.oc.engine.core.contract.permission.Reply;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class PermissionResourceTest {

    @Inject
    DataSource dataSource;

    @Inject
    Engine engine;

    @Test
    void listsDurablePendingPermissions() throws Exception {
        String id = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        String requestId = PermissionRequestId.create().value();
        PendingApprovalSql.persistPending(dataSource, requestId, id, "bash",
                "rm -rf /tmp/qoc-test", Instant.now(), Instant.now().plusSeconds(300), null, null);

        try {
            given().get("/api/session/" + id + "/permission")
                    .then().statusCode(200)
                    .body("data.find { it.id == '" + requestId + "' }.action", equalTo("bash"))
                    .body("data.find { it.id == '" + requestId + "' }.resources[0]",
                            equalTo("rm -rf /tmp/qoc-test"));
        } finally {
            engine.resolvePermission(SessionId.parse(id), PermissionRequestId.parse(requestId),
                    Reply.ONCE, Option.none());
            given().delete("/api/session/" + id);
        }
    }
}
