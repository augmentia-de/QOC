package com.example.qoctest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;

@RestController
public class TestController {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TestController(@Value("${qoc.base-url}") String baseUrl) {
        this.restClient = RestClient.create(baseUrl);
    }

    @PostMapping("/api/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody Map<String, Object> request) {
        String mode = String.valueOf(request.getOrDefault("mode", "rest"));
        String agent = String.valueOf(request.getOrDefault("agent", "reviewer"));
        String prompt = String.valueOf(request.getOrDefault("prompt", ""));
        Object structuredOutput = request.get("structuredOutput");

        try {
            Map<String, Object> result = "mcp".equalsIgnoreCase(mode)
                    ? runMcp(agent, prompt, structuredOutput)
                    : runRest(agent, prompt, structuredOutput);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> runRest(String agent, String prompt, Object structuredOutput) {
        Map<String, Object> sessionBody = Map.of("agent", agent);
        JsonNode session = restClient.post().uri("/api/session")
                .contentType(MediaType.APPLICATION_JSON)
                .body(sessionBody)
                .retrieve().body(JsonNode.class);
        String sessionId = session.get("id").asText();

        Map<String, Object> promptBody = structuredOutput == null
                ? Map.of("text", prompt)
                : Map.of("text", prompt, "responseSchema", structuredOutput);
        JsonNode response = restClient.post().uri("/api/session/{id}/prompt", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(promptBody)
                .retrieve().body(JsonNode.class);

        return Map.of(
                "mode", "rest",
                "sessionId", sessionId,
                "finish", response.path("finish").asText(),
                "inputTokens", response.path("inputTokens").asLong(),
                "outputTokens", response.path("outputTokens").asLong(),
                "text", response.path("text").asText(),
                "structured", response.has("structured") ? response.get("structured") : null
        );
    }

    private Map<String, Object> runMcp(String agent, String prompt, Object structuredOutput) {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("agent", agent);
        arguments.put("prompt", prompt);
        if (structuredOutput != null) {
            arguments.put("structuredOutput", structuredOutput);
        }

        Map<String, Object> rpc = Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/call",
                "params", Map.of("name", "qoc_run_agent", "arguments", arguments)
        );

        JsonNode response = restClient.post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(rpc)
                .retrieve().body(JsonNode.class);

        JsonNode result = response.get("result");
        String text = result != null && result.has("content") && result.get("content").isArray() && result.get("content").size() > 0
                ? result.get("content").get(0).path("text").asText()
                : "";
        Object structured = result != null && result.has("structuredContent") ? objectMapper.convertValue(result.get("structuredContent"), Object.class) : null;
        JsonNode meta = result != null ? result.get("_meta") : null;

        return Map.of(
                "mode", "mcp",
                "finish", meta != null ? meta.path("finish").asText() : "",
                "inputTokens", meta != null ? meta.path("inputTokens").asLong() : 0,
                "outputTokens", meta != null ? meta.path("outputTokens").asLong() : 0,
                "text", text,
                "structured", structured
        );
    }
}
