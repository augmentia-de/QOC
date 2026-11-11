package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Exercises the workflow API against the heap-backed profile: the workflow
 * specification lives in memory and the run state is reconstructed from the
 * engine journal.
 */
@QuarkusTest
@TestProfile(WorkflowApiTest.Profile.class)
class WorkflowApiTest {

    private static final Path WORKSPACE = Path.of(
            System.getProperty("java.io.tmpdir"), "qoc-workflow-api-test");

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Files.createDirectories(WORKSPACE);
            } catch (IOException ignored) {
                // The test workspace is best effort; the engine can create it.
            }
            Map<String, String> overrides = new HashMap<>();
            overrides.put("engine.storage", "memory");
            overrides.put("engine.recovery.enabled", "false");
            overrides.put("engine.provider.scripted", "true");
            overrides.put("engine.workspace", WORKSPACE.toString());
            overrides.put("engine.llm-log.enabled", "false");
            overrides.put("engine.workflow.storage", "memory");
            overrides.put("engine.workflow.recovery.enabled", "false");
            overrides.put("quarkus.datasource.jdbc.url", "");
            overrides.put("quarkus.datasource.devservices.enabled", "false");
            overrides.put("quarkus.flyway.enabled", "false");
            return overrides;
        }
    }

    @Test
    void runsAWorkflowAndReadsRunStateFromTheJournal() {
        Map<String, Object> workflow = workflow("wf-test");

        given().contentType(ContentType.JSON)
                .body(workflow)
                .put("/api/workflows/wf-test")
                .then().statusCode(200)
                .body("id", equalTo("wf-test"));

        String runId = given().contentType(ContentType.JSON)
                .body(Map.of("workflow", workflow, "input", "hello"))
                .post("/api/workflows/run")
                .then().statusCode(200)
                .body("valid", equalTo(true))
                .body("runId", not(equalTo("")))
                .body("finish", equalTo("COMPLETED"))
                .body("failed", equalTo(false))
                .extract().path("runId");

        given().get("/api/workflows/runs/" + runId)
                .then().statusCode(200)
                .body("runId", equalTo(runId))
                .body("workflowId", equalTo("wf-test"))
                .body("status", equalTo("COMPLETED"))
                .body("completedNodes", hasItem("start"))
                .body("completedNodes", hasItem("end"))
                .body("resumable", equalTo(false));

        given().get("/api/workflows/runs")
                .then().statusCode(200)
                .body("runs[0].runId", equalTo(runId));

        given().post("/api/workflows/runs/" + runId + "/resume")
                .then().statusCode(409);

        given().get("/api/workflows/wf-test/versions")
                .then().statusCode(200)
                .body("versions.size()", equalTo(1));

        given().get("/api/workflows/wf-test")
                .then().statusCode(200)
                .body("id", equalTo("wf-test"));
    }

    @Test
    void runsAgentNodeAndLinksTheChildSession() {
        Map<String, Object> workflow = workflowWithAgent("wf-agent-test");

        String runId = given().contentType(ContentType.JSON)
                .body(Map.of("workflow", workflow, "input", "hello agent"))
                .post("/api/workflows/run")
                .then().statusCode(200)
                .body("valid", equalTo(true))
                .body("finish", equalTo("COMPLETED"))
                .extract().path("runId");

        String childSessionId = given().get("/api/workflows/runs/" + runId)
                .then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("completedNodes", hasItem("agent"))
                .body("nodeSessions.agent", not(equalTo("")))
                .extract().path("nodeSessions.agent");

        org.assertj.core.api.Assertions.assertThat(childSessionId).isNotEqualTo(runId);
    }

    @Test
    void rejectsInvalidWorkflow() {
        given().contentType(ContentType.JSON)
                .body(Map.of("workflow", Map.of("id", "broken", "nodes", List.of())))
                .post("/api/workflows/run")
                .then().statusCode(400)
                .body("valid", equalTo(false));
    }

    private static Map<String, Object> workflowWithAgent(String id) {
        return Map.of(
                "id", id,
                "name", "Agent Workflow",
                "version", "1.0.0",
                "status", "draft",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start"),
                        Map.of("id", "agent", "type", "agent", "data", Map.of("agentRef", "a1")),
                        Map.of("id", "end", "type", "end")),
                "edges", List.of(
                        Map.of("id", "e1", "source", "start", "target", "agent"),
                        Map.of("id", "e2", "source", "agent", "target", "end")),
                "agents", List.of(
                        Map.of("id", "a1", "name", "Helper", "version", "1.0.0",
                                "systemPrompt", "You are a test helper.")));
    }

    private static Map<String, Object> workflow(String id) {
        return Map.of(
                "id", id,
                "name", "Test Workflow",
                "version", "1.0.0",
                "status", "draft",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start"),
                        Map.of("id", "end", "type", "end")),
                "edges", List.of(
                        Map.of("id", "e1", "source", "start", "target", "end")),
                "agents", List.of());
    }
}
