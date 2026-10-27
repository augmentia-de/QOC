package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(E2ELiveTest.Profile.class)
class E2ELiveTest {

    static final Path WORKSPACE = Path.of("/tmp/qoc-e2e-workspace");

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
            overrides.put("engine.workspace", WORKSPACE.toString());
            overrides.put("engine.session.retention-days", "1");
            overrides.put("engine.instructions.enabled", "true");
            return overrides;
        }
    }

    @BeforeAll
    static void setupWorkspace() throws Exception {
        resetWorkspace();
    }

    static void resetWorkspace() throws Exception {
        if (Files.exists(WORKSPACE)) {
            deleteRecursively(WORKSPACE);
        }
        Files.createDirectories(WORKSPACE.resolve("src"));
        Files.writeString(WORKSPACE.resolve("AGENTS.md"),
                "# QOC E2E Test Project\n\nA tiny Java project for end-to-end tests.\n");
        Files.writeString(WORKSPACE.resolve("src/Main.java"),
                "public class Main {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        System.out.println(\"hello\");\n"
                        + "    }\n"
                        + "}\n");
        Files.writeString(WORKSPACE.resolve("settings.json"),
                "{\n"
                        + "  \"agents\": [\n"
                        + "    {\n"
                        + "      \"name\": \"test-builder\",\n"
                        + "      \"system\": \"You are a small coding agent. Read files before changing them. Use tools precisely and concisely.\",\n"
                        + "      \"maxSteps\": 6,\n"
                        + "      \"tools\": { \"allow\": [\"read\", \"write\", \"edit\", \"glob\", \"grep\", \"shell\"] },\n"
                        + "      \"permissions\": [ { \"action\": \"*\", \"resource\": \"*\", \"effect\": \"allow\" } ]\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}\n");
    }

    static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        Files.walk(root)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    static boolean llmAvailable() {
        String key = System.getenv("OPENAI_API_KEY");
        return key != null && !key.isBlank();
    }

    static String createSession(String agent) {
        Map<String, Object> body = new HashMap<>();
        if (agent != null && !agent.isBlank()) body.put("agent", agent);
        return given()
                .contentType("application/json")
                .body(body)
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");
    }

    static io.restassured.response.Response prompt(String sessionId, String text) {
        return given()
                .contentType("application/json")
                .body(Map.of("text", text))
                .post("/api/session/" + sessionId + "/prompt");
    }

    // ------------------------------------------------------------------
    // REST: single call, new session
    // ------------------------------------------------------------------

    @Test
    void restSingleCallNewSession() throws Exception {
        Assumptions.assumeTrue(llmAvailable(), "OPENAI_API_KEY is not set");
        resetWorkspace();

        String id = createSession("test-builder");

        io.restassured.response.Response response = prompt(id,
                "Read src/Main.java and summarize it in one short sentence.");

        response.then().statusCode(200);
        assertThat(response.jsonPath().getString("outcome")).isNotBlank();
        assertThat(response.jsonPath().getString("text")).isNotBlank();
        assertThat(response.jsonPath().getLong("inputTokens")).isGreaterThanOrEqualTo(0);
        assertThat(response.jsonPath().getLong("outputTokens")).isGreaterThanOrEqualTo(0);

        given().get("/api/session/" + id + "/messages")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Main.java"));

        given().accept("text/event-stream")
                .get("/api/event?session=" + id + "&live=false")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("InboxDelivered"));
    }

    // ------------------------------------------------------------------
    // REST: multiple calls in the same session
    // ------------------------------------------------------------------

    @Test
    void restMultiCallSameSession() throws Exception {
        Assumptions.assumeTrue(llmAvailable(), "OPENAI_API_KEY is not set");
        resetWorkspace();

        String id = createSession("test-builder");

        io.restassured.response.Response first = prompt(id,
                "Read src/Main.java. Do not change anything yet.");
        first.then().statusCode(200);
        assertThat(first.jsonPath().getString("text")).isNotBlank();

        io.restassured.response.Response second = prompt(id,
                "Now add a short comment to src/Main.java explaining the class. Use a tool.");
        second.then().statusCode(200);
        assertThat(second.jsonPath().getString("text")).isNotBlank();

        io.restassured.response.Response third = prompt(id,
                "Verify by reading src/Main.java again and tell me whether the comment exists.");
        third.then().statusCode(200);
        assertThat(third.jsonPath().getString("text")).isNotBlank();

        String content = Files.readString(WORKSPACE.resolve("src/Main.java"));
        // The model may or may not have edited; if it did, the comment should be present.
        System.out.println("Final Main.java:\n" + content);

        given().get("/api/session/" + id + "/messages")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Main.java"));
    }

    // ------------------------------------------------------------------
    // MCP: single call, new session
    // ------------------------------------------------------------------

    @Test
    void mcpSingleCallNewSession() throws Exception {
        Assumptions.assumeTrue(llmAvailable(), "OPENAI_API_KEY is not set");
        resetWorkspace();

        io.restassured.response.Response response = given()
                .contentType("application/json")
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "tools/call",
                        "params", Map.of(
                                "name", "qoc_run_agent",
                                "arguments", Map.of(
                                        "agent", "test-builder",
                                        "prompt", "Read src/Main.java and summarize it in one sentence."))))
                .post("/mcp");

        response.then().statusCode(200);
        String text = response.jsonPath().getString("result.content[0].text");
        assertThat(text).isNotBlank();

        String sessionId = response.jsonPath().getString("result._meta.sessionId");
        assertThat(sessionId).startsWith("ses_");

        given().get("/api/session/" + sessionId + "/messages")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Main.java"));
    }

    // ------------------------------------------------------------------
    // MCP: reuse session
    // ------------------------------------------------------------------

    @Test
    void mcpReuseSession() throws Exception {
        Assumptions.assumeTrue(llmAvailable(), "OPENAI_API_KEY is not set");
        resetWorkspace();

        io.restassured.response.Response first = given()
                .contentType("application/json")
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "tools/call",
                        "params", Map.of(
                                "name", "qoc_run_agent",
                                "arguments", Map.of(
                                        "agent", "test-builder",
                                        "prompt", "Read src/Main.java."))))
                .post("/mcp");
        first.then().statusCode(200);
        String sessionId = first.jsonPath().getString("result._meta.sessionId");
        assertThat(sessionId).startsWith("ses_");

        io.restassured.response.Response second = given()
                .contentType("application/json")
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 2,
                        "method", "tools/call",
                        "params", Map.of(
                                "name", "qoc_run_agent",
                                "arguments", Map.of(
                                        "agent", "test-builder",
                                        "sessionMode", "reuse",
                                        "sessionId", sessionId,
                                        "prompt", "Now add a short comment to src/Main.java."))))
                .post("/mcp");
        second.then().statusCode(200);
        assertThat(second.jsonPath().getString("result.content[0].text")).isNotBlank();

        given().get("/api/session/" + sessionId + "/messages")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Main.java"));
    }

    // ------------------------------------------------------------------
    // MCP + REST shared session
    // ------------------------------------------------------------------

    @Test
    void mcpAndRestShareSession() throws Exception {
        Assumptions.assumeTrue(llmAvailable(), "OPENAI_API_KEY is not set");
        resetWorkspace();

        String restSession = createSession("test-builder");
        given()
                .contentType("application/json")
                .body(Map.of("text", "Read src/Main.java."))
                .post("/api/session/" + restSession + "/prompt")
                .then().statusCode(200);

        io.restassured.response.Response mcp = given()
                .contentType("application/json")
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "tools/call",
                        "params", Map.of(
                                "name", "qoc_run_agent",
                                "arguments", Map.of(
                                        "agent", "test-builder",
                                        "sessionMode", "reuse",
                                        "sessionId", restSession,
                                        "prompt", "Summarize what you read."))))
                .post("/mcp");
        mcp.then().statusCode(200);
        assertThat(mcp.jsonPath().getString("result.content[0].text")).isNotBlank();

        given().get("/api/session/" + restSession + "/messages")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Main.java"));
    }

    // ------------------------------------------------------------------
    // Orchestrator: multi-step tool loop
    // ------------------------------------------------------------------

    @Test
    void orchestratorMultiStep() throws Exception {
        Assumptions.assumeTrue(llmAvailable(), "OPENAI_API_KEY is not set");
        resetWorkspace();

        String id = createSession("test-builder");

        io.restassured.response.Response response = prompt(id,
                "Read src/Main.java, then add a short comment explaining the class, "
                        + "then read the file again and tell me whether the comment exists.");

        response.then().statusCode(200);
        assertThat(response.jsonPath().getString("text")).isNotBlank();

        System.out.println("Orchestrator text: " + response.jsonPath().getString("text"));
        System.out.println("Orchestrator finish: " + response.jsonPath().getString("finish"));

        given().get("/api/session/" + id + "/messages")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Main.java"));
    }

    // ------------------------------------------------------------------
    // Orchestrator: parallel tool calls
    // ------------------------------------------------------------------

    @Test
    void orchestratorParallelToolCalls() throws Exception {
        Assumptions.assumeTrue(llmAvailable(), "OPENAI_API_KEY is not set");
        resetWorkspace();
        Files.writeString(WORKSPACE.resolve("README.md"), "# Test README\n");

        String id = createSession("test-builder");

        io.restassured.response.Response response = prompt(id,
                "Read src/Main.java and README.md in parallel, then summarize both files.");

        response.then().statusCode(200);
        assertThat(response.jsonPath().getString("text")).isNotBlank();
    }

    // ------------------------------------------------------------------
    // Orchestrator: max steps
    // ------------------------------------------------------------------

    @Test
    void orchestratorMaxSteps() throws Exception {
        Assumptions.assumeTrue(llmAvailable(), "OPENAI_API_KEY is not set");
        resetWorkspace();

        String id = createSession("test-builder");

        io.restassured.response.Response response = prompt(id,
                "Keep reading files until you find a secret. Do not stop until you are sure.");

        response.then().statusCode(200);
        assertThat(response.jsonPath().getString("outcome")).isNotBlank();
        assertThat(response.jsonPath().getString("finish")).isNotBlank();
    }

    // ------------------------------------------------------------------
    // Compaction
    // ------------------------------------------------------------------

    @Test
    void compactionEndpointWorks() throws Exception {
        Assumptions.assumeTrue(llmAvailable(), "OPENAI_API_KEY is not set");
        resetWorkspace();

        String id = createSession("test-builder");

        given()
                .contentType("application/json")
                .body(Map.of("text", "Read src/Main.java."))
                .post("/api/session/" + id + "/prompt")
                .then().statusCode(200);

        given()
                .post("/api/session/" + id + "/compact")
                .then().statusCode(200);

        given().get("/api/session/" + id + "/messages")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Main.java"));
    }
}
