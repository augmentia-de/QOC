package de.augmentia.oc.engine.http;

import com.sun.net.httpserver.HttpServer;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(OrchestrationE2ETest.Profile.class)
class OrchestrationE2ETest {

    public static class Profile implements QuarkusTestProfile {

        private static final Path GLOBAL_SETTINGS = writeGlobalSettings();
        private static final Path WORKSPACE = workspace();
        private static final HttpServer SERVER = server();

        private static Path writeGlobalSettings() {
            try {
                Path file = Files.createTempFile("qoc-orch-e2e-global", ".json");
                Files.writeString(file, """
                        {
                          "agents": [
                            {
                              "name": "orchestrator",
                              "system": "You are the orchestrator. Plan, delegate to subagents, verify results.",
                              "maxSteps": 8,
                              "tools": { "allow": ["*"], "deny": [] },
                              "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
                              "mode": "primary"
                            },
                            {
                              "name": "analyzer",
                              "system": "You are the analyzer subagent. Read and summarize.",
                              "maxSteps": 4,
                              "tools": { "allow": ["read", "glob", "grep", "list"], "deny": [] },
                              "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
                              "mode": "subagent"
                            }
                          ]
                        }
                        """);
                file.toFile().deleteOnExit();
                return file;
            } catch (IOException e) {
                throw new IllegalStateException("cannot create global settings", e);
            }
        }

        private static Path workspace() {
            try {
                Path directory = Files.createTempDirectory("qoc-orch-e2e");
                Path skillDir = directory.resolve("skill").resolve("commit");
                Files.createDirectories(skillDir);
                Files.writeString(skillDir.resolve("SKILL.md"), """
                        ---
                        name: Commit
                        description: Commit staged changes with a conventional message
                        ---
                        # Commit

                        COMMIT_BODY
                        """);
                directory.toFile().deleteOnExit();
                return directory;
            } catch (IOException e) {
                throw new IllegalStateException("cannot create orchestration E2E workspace", e);
            }
        }

        private static HttpServer server() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/v1/chat/completions", exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    byte[] response = FakeLlm.respond(body).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
                server.start();
                return server;
            } catch (IOException e) {
                throw new IllegalStateException("cannot start fake LLM server", e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "engine.provider.scripted", "false",
                    "engine.provider.url", "http://127.0.0.1:" + SERVER.getAddress().getPort()
                            + "/v1/chat/completions",
                    "engine.provider.api-key", "test",
                    "engine.workspace", WORKSPACE.toString(),
                    "engine.settings.global", GLOBAL_SETTINGS.toString(),
                    "engine.instructions.enabled", "false");
        }
    }

    @BeforeEach
    void reset() {
        FakeLlm.agentCreated = false;
        System.clearProperty("qoc.orch.last");
    }

    @Test
    void orchestratorCreatesAgentDelegatesAndVerifies() {
        String session = given()
                .contentType(ContentType.JSON)
                .body(Map.of("agent", "orchestrator"))
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        String text = given()
                .contentType(ContentType.JSON)
                .body(Map.of("text", "Create a summarizer agent that can use the commit skill, then delegate a task to it and verify the result."))
                .post("/api/session/" + session + "/prompt")
                .then().statusCode(200)
                .extract().path("text");

        assertThat(text).contains("ORCHESTRATION_DONE:COMMIT_BODY");
    }

    /** Deterministic OpenAI-compatible SSE provider for the orchestration test. */
    static final class FakeLlm {

        static volatile boolean agentCreated = false;

        static String respond(String body) {
            Result<JsonValue> parsed = Json.parse(body);
            if (!(parsed instanceof Result.Ok<JsonValue> ok)
                    || !(ok.value() instanceof JsonValue.Obj root)) {
                return text("PARSE_ERROR");
            }
            String system = "";
            String toolContent = null;
            boolean hasToolResult = false;
            if (root.values().get("messages") instanceof JsonValue.Arr messages) {
                for (JsonValue item : messages.values()) {
                    if (!(item instanceof JsonValue.Obj message)) continue;
                    String role = string(message, "role");
                    if ("system".equals(role)) system = string(message, "content");
                    if ("tool".equals(role)) {
                        hasToolResult = true;
                        toolContent = string(message, "content");
                    }
                }
            }
            if (system == null || system.isBlank()) {
                return text("E2E Title");
            }

            boolean orchestrator = system.contains("orchestrator");
            boolean summarizer = system.contains("summarizer");

            if (summarizer) {
                if (hasToolResult) {
                    return text("SUBAGENT_DONE:COMMIT_BODY");
                }
                return toolCall("skill", "{\"id\":\"commit\"}");
            }

            // orchestrator
            if (hasToolResult) {
                if (toolContent != null && (toolContent.contains("SUBAGENT_DONE") || toolContent.contains("COMMIT_BODY"))) {
                    return text("ORCHESTRATION_DONE:COMMIT_BODY");
                }
                if (toolContent != null && toolContent.contains("created agent")) {
                    return toolCall("subagent", "{\"agent\":\"summarizer\",\"prompt\":\"summarize the plan using the commit skill\"}");
                }
                return text("ORCHESTRATION_FAILED:" + (toolContent == null ? "none" : toolContent));
            }
            if (!agentCreated) {
                agentCreated = true;
                String skills = "{\"enabled\":true,\"allow\":[\"commit\"],\"deny\":[],\"max\":5,\"prompt\":\"top-k\",\"core\":[],\"filter\":{\"topK\":5,\"minScore\":0.0,\"thresholdCount\":0}}";
                String args = "{\"name\":\"summarizer\",\"system\":\"You are the summarizer subagent. Use skills.\","
                        + "\"mode\":\"subagent\",\"hidden\":true,\"maxSteps\":4,"
                        + "\"toolAllow\":[\"skill\",\"skill_list\"],\"toolDeny\":[],"
                        + "\"skills\":" + skills + "}";
                return toolCall("agent_create", args);
            }
            return toolCall("subagent", "{\"agent\":\"summarizer\",\"prompt\":\"summarize the plan using the commit skill\"}");
        }

        private static String toolCall(String name, String arguments) {
            Map<String, JsonValue> call = Map.of(
                    "index", JsonValue.of(0),
                    "id", JsonValue.of("call_1"),
                    "type", JsonValue.of("function"),
                    "function", JsonValue.of(Map.of(
                            "name", JsonValue.of(name),
                            "arguments", JsonValue.of(arguments))));
            String start = frame(Map.of("choices", JsonValue.of(List.of(JsonValue.of(Map.of(
                    "delta", JsonValue.of(Map.of(
                            "tool_calls", JsonValue.of(List.of(JsonValue.of(call)))))))))));
            String finish = frame(Map.of("choices", JsonValue.of(List.of(JsonValue.of(Map.of(
                    "delta", JsonValue.of(Map.of()),
                    "finish_reason", JsonValue.of("tool_calls")))))));
            return start + finish + "data: [DONE]\n\n";
        }

        private static String text(String value) {
            String start = frame(Map.of("choices", JsonValue.of(List.of(JsonValue.of(Map.of(
                    "delta", JsonValue.of(Map.of("content", JsonValue.of(value)))))))));
            String finish = frame(Map.of("choices", JsonValue.of(List.of(JsonValue.of(Map.of(
                    "delta", JsonValue.of(Map.of()),
                    "finish_reason", JsonValue.of("stop")))))));
            return start + finish + "data: [DONE]\n\n";
        }

        private static String frame(Map<String, JsonValue> chunk) {
            return "data: " + Json.encode(JsonValue.of(chunk)) + "\n\n";
        }

        private static String string(JsonValue.Obj object, String key) {
            JsonValue value = object.values().get(key);
            return value instanceof JsonValue.Str str ? str.value() : null;
        }
    }
}