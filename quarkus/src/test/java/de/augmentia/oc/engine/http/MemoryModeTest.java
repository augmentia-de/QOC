package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Boots the engine without a database: all storage ports are in-memory and the
 * datasource is intentionally inactive.
 */
@QuarkusTest
@TestProfile(MemoryModeTest.Profile.class)
class MemoryModeTest {

    private static final Path WORKSPACE = Path.of(
            System.getProperty("java.io.tmpdir"), "qoc-memory-mode-test");

    @Inject
    WebApprovalSource approvals;

    @BeforeAll
    static void setupWorkspace() throws IOException {
        Files.createDirectories(WORKSPACE.resolve("src"));
        Files.writeString(WORKSPACE.resolve("AGENTS.md"), "# Memory mode\n");
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>();
            overrides.put("engine.storage", "memory");
            overrides.put("engine.recovery.enabled", "false");
            overrides.put("engine.provider.scripted", "true");
            overrides.put("engine.preflight.enabled", "false");
            overrides.put("engine.workspace", WORKSPACE.toString());
            overrides.put("engine.instructions.enabled", "true");
            overrides.put("engine.revert.snapshot.enabled", "true");
            overrides.put("engine.llm-log.enabled", "false");
            overrides.put("quarkus.datasource.jdbc.url", "");
            overrides.put("quarkus.datasource.devservices.enabled", "false");
            overrides.put("quarkus.flyway.enabled", "false");
            return overrides;
        }
    }

    @Test
    void startsWithoutDatabaseAndReportsReady() {
        given().get("/api/health/live")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
        given().get("/api/health/ready")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void promptRunsOnInMemoryStorage() {
        String id = given()
                .contentType(ContentType.JSON)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("text", "Hallo Memory"))
                .post("/api/session/" + id + "/prompt")
                .then().statusCode(200)
                .body("outcome", equalTo("COMPLETED"));

        given().get("/api/session/" + id + "/messages")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Hallo Memory"));
    }

    @Test
    void approvalsResolveWithoutPersistence() throws Exception {
        SessionId session = SessionId.create();
        PermissionRequestId request = PermissionRequestId.create();
        CompletableFuture<ApprovalResult> result = CompletableFuture.supplyAsync(
                () -> approvals.request(request, session, "shell", "test"));

        for (int i = 0; i < 100 && !approvals.hasPending(session.value(), request.value()); i++) {
            Thread.sleep(10);
        }
        assertThat(approvals.hasPending(session.value(), request.value())).isTrue();
        assertThat(approvals.resolve(session.value(), request.value(), new ApprovalResult.Approved()))
                .isTrue();
        assertThat(result.get(5, TimeUnit.SECONDS)).isInstanceOf(ApprovalResult.Approved.class);
    }
}
