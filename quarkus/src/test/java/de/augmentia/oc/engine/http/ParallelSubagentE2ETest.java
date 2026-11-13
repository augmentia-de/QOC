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

/**
 * HTTP-E2E-Test: Ein Coordinator-Agent ruft in einem Modell-Schritt zwei
 * Research-Subagents auf. Beide laufen in eigenen Child-Sessions; der
 * Coordinator aggregiert die Ergebnisse.
 */
@QuarkusTest
@TestProfile(ParallelSubagentE2ETest.Profile.class)
class ParallelSubagentE2ETest {

    public static class Profile implements QuarkusTestProfile {

        private static final Path GLOBAL_SETTINGS = writeGlobalSettings();
        private static final HttpServer SERVER = server();

        private static Path writeGlobalSettings() {
            try {
                Path file = Files.createTempFile("qoc-parallel-subagents", ".json");
                Files.writeString(file, """
                        {
                          "agents": [
                            {
                              "name": "coordinator",
                              "system": "You are the research coordinator. Delegate independent research tasks to research-a and research-b in parallel.",
                              "maxSteps": 8,
                              "tools": { "allow": ["*"], "deny": [] },
                              "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
                              "mode": "primary"
                            },
                            {
                              "name": "research-a",
                              "system": "You are research-a. Research topic A and answer with RESULT_A.",
                              "maxSteps": 2,
                              "tools": { "allow": [], "deny": ["*"] },
                              "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
                              "mode": "subagent"
                            },
                            {
                              "name": "research-b",
                              "system": "You are research-b. Research topic B and answer with RESULT_B.",
                              "maxSteps": 2,
                              "tools": { "allow": [], "deny": ["*"] },
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
                    "engine.settings.global", GLOBAL_SETTINGS.toString(),
                    "engine.instructions.enabled", "false");
        }
    }

    @BeforeEach
    void reset() {
        FakeLlm.coordinatorCalled = false;
    }

    @Test
    void coordinatorRunsTwoResearchSubagentsAndAggregatesResults() {
        String session = given()
                .contentType(ContentType.JSON)
                .body(Map.of("agent", "coordinator"))
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");

        String text = given()
                .contentType(ContentType.JSON)
                .body(Map.of("text", "Research topic A and topic B in parallel and summarize both."))
                .post("/api/session/" + session + "/prompt")
                .then().statusCode(200)
                .extract().path("text");

        assertThat(text)
                .contains("COORDINATOR_DONE")
                .contains("RESULT_A")
                .contains("RESULT_B");
    }

    /** Deterministic OpenAI-compatible SSE provider for the parallel-subagent test. */
    static final class FakeLlm {

        private static volatile boolean coordinatorCalled = false;

        static String respond(String body) {
            Result<JsonValue> parsed = Json.parse(body);
            if (!(parsed instanceof Result.Ok<JsonValue> ok)
                    || !(ok.value() instanceof JsonValue.Obj root)) {
                return text("PARSE_ERROR");
            }
            String system = "";
            List<String> toolContents = new ArrayList<>();
            if (root.values().get("messages") instanceof JsonValue.Arr messages) {
                for (JsonValue item : messages.values()) {
                    if (!(item instanceof JsonValue.Obj message)) continue;
                    String role = string(message, "role");
                    if ("system".equals(role)) system = string(message, "content");
                    if ("tool".equals(role)) {
                        String content = string(message, "content");
                        if (content != null) toolContents.add(content);
                    }
                }
            }
            if (system == null || system.isBlank()) {
                return text("E2E Title");
            }

            // Der Coordinator-Prompt nennt die Subagent-Namen, daher zuerst
            // den Coordinator erkennen, bevor auf "research-a" geprüft wird.
            if (system.contains("coordinator")) {
                if (!toolContents.isEmpty()) {
                    String joined = String.join(" | ", toolContents);
                    if (joined.contains("RESULT_A") && joined.contains("RESULT_B")) {
                        return text("COORDINATOR_DONE: " + joined);
                    }
                    return text("COORDINATOR_PARTIAL: " + joined);
                }
                if (!coordinatorCalled) {
                    coordinatorCalled = true;
                    return toolCalls(List.of(
                            toolCallArgs("subagent", "{\"agent\":\"research-a\",\"prompt\":\"find result A\"}"),
                            toolCallArgs("subagent", "{\"agent\":\"research-b\",\"prompt\":\"find result B\"}")));
                }
                return text("COORDINATOR_DONE: NO_TOOL_RESULTS");
            }

            if (system.contains("research-a")) {
                return text("RESULT_A");
            }
            if (system.contains("research-b")) {
                return text("RESULT_B");
            }

            return text("E2E");
        }

        private static Map<String, JsonValue> toolCallArgs(String name, String arguments) {
            return Map.of(
                    "name", JsonValue.of(name),
                    "arguments", JsonValue.of(arguments));
        }

        private static String toolCalls(List<Map<String, JsonValue>> calls) {
            List<JsonValue> values = new ArrayList<>();
            for (int index = 0; index < calls.size(); index++) {
                Map<String, JsonValue> call = calls.get(index);
                values.add(JsonValue.of(Map.of(
                        "index", JsonValue.of(index),
                        "id", JsonValue.of("call_" + index),
                        "type", JsonValue.of("function"),
                        "function", JsonValue.of(call))));
            }
            String start = frame(Map.of("choices", JsonValue.of(List.of(JsonValue.of(Map.of(
                    "delta", JsonValue.of(Map.of(
                            "tool_calls", JsonValue.of(values)))))))));
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
