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
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(SkillLoadingE2ETest.Profile.class)
class SkillLoadingE2ETest {

    private static final Pattern SKILL_ID = Pattern.compile("<id>([^<]+)</id>");

    public static class Profile implements QuarkusTestProfile {

        private static final Path GLOBAL_SETTINGS = writeGlobalSettings();
        private static final Path WORKSPACE = workspace();
        private static final HttpServer SERVER = server();

        private static Path writeGlobalSettings() {
            try {
                Path file = Files.createTempFile("qoc-skill-e2e-global", ".json");
                Files.writeString(file, """
                        {
                          "agents": [
                            {
                              "name": "build",
                              "tools": { "allow": ["*"], "deny": [] },
                              "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
                              "skills": { "prompt": "all" }
                            },
                            {
                              "name": "plan",
                              "tools": { "allow": ["read", "glob", "grep", "list", "skill", "skill_list"], "deny": [] },
                              "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
                              "skills": {
                                "allow": ["plan-*"],
                                "deny": ["deploy-*"],
                                "prompt": "top-k",
                                "max": 2,
                                "filter": { "topK": 2, "minScore": 0.0, "thresholdCount": 0 }
                              }
                            },
                            {
                              "name": "router",
                              "tools": { "allow": ["read", "glob", "grep", "list", "skill", "skill_list"], "deny": [] },
                              "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
                              "skills": { "prompt": "none" }
                            },
                            {
                              "name": "limited",
                              "tools": { "allow": ["read", "glob", "grep", "list", "skill", "skill_list"], "deny": [] },
                              "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
                              "skills": { "prompt": "all", "max": 2 }
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
                Path directory = Files.createTempDirectory("qoc-skill-e2e");
                writeSkill(directory, "plan-review", "Review the project plan and check invariants", "PLAN_REVIEW_BODY");
                writeSkill(directory, "deploy", "Deploy the application to production", "DEPLOY_BODY");
                writeSkill(directory, "commit", "Commit staged changes with a conventional message", "COMMIT_BODY");
                directory.toFile().deleteOnExit();
                return directory;
            } catch (IOException e) {
                throw new IllegalStateException("cannot create skill E2E workspace", e);
            }
        }

        private static void writeSkill(Path workspace, String id, String description, String body) throws IOException {
            Path directory = workspace.resolve("skill").resolve(id);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("SKILL.md"), """
                    ---
                    name: %s
                    description: %s
                    ---
                    # %s

                    %s
                    """.formatted(id, description, id, body));
        }

        private static HttpServer server() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/v1/chat/completions", exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String resp = FakeLlm.respond(body);

                    try { java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/opencode/fake-llm-resp.txt"), resp); } catch (Exception ignored) {}
                    byte[] response = resp.getBytes(StandardCharsets.UTF_8);
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
    void resetFakeLlm() {
        System.setProperty("qoc.e2e.mode", "first");
        System.setProperty("qoc.e2e.target", "plan-review");
        System.clearProperty("qoc.e2e.lastSystem");
        System.clearProperty("qoc.e2e.lastTools");
    }

    @Test
    void planAgentSeesOnlyAllowedSkillsAndLoadsThem() {
        String session = createSession("plan");
        String text = prompt(session, "please use the plan review skill");
        assertThat(text).contains("TOOL_RESULT:PLAN_REVIEW_BODY");
        assertThat(FakeLlm.lastSystem())
                .contains("<available_skills>")
                .contains("<id>plan-review</id>")
                .doesNotContain("<id>deploy</id>")
                .doesNotContain("<id>commit</id>");
        assertThat(FakeLlm.lastTools()).contains("skill", "skill_list");
    }

    @Test
    void planAgentBlocksSkillsOutsideItsAllowList() {
        System.setProperty("qoc.e2e.mode", "id");
        System.setProperty("qoc.e2e.target", "commit");
        String session = createSession("plan");
        String text = prompt(session, "load the commit skill");
        assertThat(text).contains("TOOL_DENIED");
        assertThat(FakeLlm.lastSystem()).doesNotContain("<id>commit</id>");
    }

    @Test
    void routerAgentHidesTheSkillBlockAndStillLoadsByTool() {
        System.setProperty("qoc.e2e.mode", "id");
        System.setProperty("qoc.e2e.target", "commit");
        String session = createSession("router");
        String text = prompt(session, "load the commit skill");
        assertThat(text).contains("TOOL_RESULT:COMMIT_BODY");
        assertThat(FakeLlm.lastSystem()).doesNotContain("<available_skills>");
        assertThat(FakeLlm.lastTools()).contains("skill", "skill_list");
    }

    @Test
    void buildAgentSeesAllSkillsAndSkillListReturnsThem() {
        System.setProperty("qoc.e2e.mode", "list");
        String session = createSession("build");
        String text = prompt(session, "list the available skills");
        assertThat(FakeLlm.lastSystem())
                .contains("<id>plan-review</id>")
                .contains("<id>deploy</id>")
                .contains("<id>commit</id>");
        assertThat(text).contains("TOOL_LIST:PLAN_REVIEW");
    }

    @Test
    void maxCapsTheSkillBlock() {
        String session = createSession("limited");
        prompt(session, "hello");
        String system = FakeLlm.lastSystem();
        assertThat(count(system, "<id>")).isEqualTo(2);
        assertThat(system).contains("<id>commit</id>").contains("<id>deploy</id>")
                .doesNotContain("<id>plan-review</id>");
    }

    private static String createSession(String agent) {
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of("agent", agent))
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");
    }

    private static String prompt(String session, String text) {
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of("text", text))
                .post("/api/session/" + session + "/prompt")
                .then().statusCode(200)
                .extract().path("text");
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    static final class FakeLlm {

        private static final String MODE = "qoc.e2e.mode";
        private static final String TARGET = "qoc.e2e.target";
        private static final String LAST_SYSTEM = "qoc.e2e.lastSystem";
        private static final String LAST_TOOLS = "qoc.e2e.lastTools";

        static String lastSystem() {
            return System.getProperty(LAST_SYSTEM, "");
        }

        static List<String> lastTools() {
            String value = System.getProperty(LAST_TOOLS, "");
            return value.isBlank() ? List.of() : List.of(value.split(","));
        }

        static String respond(String body) {
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/opencode/fake-llm-body.txt"), body); } catch (Exception ignored) {}
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
            System.setProperty(LAST_SYSTEM, system);
            System.setProperty(LAST_TOOLS, String.join(",", toolNames(root)));

            if (hasToolResult) {
                return result(toolContent);
            }
            String mode = System.getProperty(MODE, "first");
            if ("list".equals(mode)) {
                return toolCall("skill_list", "{}");
            }
            String id = "id".equals(mode)
                    ? System.getProperty(TARGET, "plan-review")
                    : firstSkillId(system);
            if (id == null) return text("NO_SKILLS");
            return toolCall("skill", "{\"id\":\"" + id + "\"}");
        }

        private static String result(String content) {
            if (content == null) return text("TOOL_RESULT:NONE");
            if (content.contains("PLAN_REVIEW_BODY")) return text("TOOL_RESULT:PLAN_REVIEW_BODY");
            if (content.contains("COMMIT_BODY")) return text("TOOL_RESULT:COMMIT_BODY");
            if (content.contains("DEPLOY_BODY")) return text("TOOL_RESULT:DEPLOY_BODY");
            if (content.contains("not allowed") || content.contains("denied by rule")
                    || content.contains("permission_denied")) {
                return text("TOOL_DENIED");
            }
            if (content.contains("plan-review")) return text("TOOL_LIST:PLAN_REVIEW");
            return text("TOOL_RESULT:UNKNOWN");
        }

        private static List<String> toolNames(JsonValue.Obj root) {
            List<String> names = new ArrayList<>();
            if (!(root.values().get("tools") instanceof JsonValue.Arr toolArray)) return names;
            for (JsonValue item : toolArray.values()) {
                if (!(item instanceof JsonValue.Obj tool)) continue;
                if (!(tool.values().get("function") instanceof JsonValue.Obj function)) continue;
                String name = string(function, "name");
                if (name != null) names.add(name);
            }
            return List.copyOf(names);
        }

        private static String firstSkillId(String system) {
            java.util.regex.Matcher matcher = SKILL_ID.matcher(system);
            return matcher.find() ? matcher.group(1) : null;
        }

        private static String string(JsonValue.Obj object, String key) {
            JsonValue value = object.values().get(key);
            return value instanceof JsonValue.Str str ? str.value() : null;
        }

        private static String text(String value) {
            String start = frame(Map.of("choices", JsonValue.of(List.of(JsonValue.of(Map.of(
                    "delta", JsonValue.of(Map.of("content", JsonValue.of(value)))))))));
            String finish = frame(Map.of("choices", JsonValue.of(List.of(JsonValue.of(Map.of(
                    "delta", JsonValue.of(Map.of()),
                    "finish_reason", JsonValue.of("stop")))))));
            return start + finish + "data: [DONE]\n\n";
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

        private static String frame(Map<String, JsonValue> chunk) {
            return "data: " + Json.encode(JsonValue.of(chunk)) + "\n\n";
        }
    }
}