package de.augmentia.qoc.workflowrunner.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeSafeDecisionClientTest {

    private HttpServer server;
    private String baseUrl;

    private void startServer(String responseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", (HttpExchange ex) -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void classifyMapsChoiceYesNoScale() throws Exception {
        String resp = "{\"model\":\"fake-model\",\"answers\":{" +
                "\"team\":{\"type\":\"choice\",\"choice\":\"billing\",\"probabilities\":{\"billing\":0.91,\"support\":0.09},\"confidence\":0.9}," +
                "\"urgent\":{\"type\":\"noul\",\"noul\":0.97}," +
                "\"score\":{\"type\":\"score\",\"score\":0.8,\"probabilities\":[0.1,0.1,0.8],\"confidence\":0.8}" +
                "},\"usage\":{\"inputTokens\":10,\"outputTokens\":5}}";
        startServer(resp);

        Map<String, Object> config = Map.of(
                "baseUrl", baseUrl,
                "questions", List.of(
                        Map.of("name", "team", "type", "choice", "text", "Which team?",
                                "options", Map.of("billing", "Payments", "support", "Usage")),
                        Map.of("name", "urgent", "type", "yesno", "text", "Urgent?"),
                        Map.of("name", "score", "type", "scale", "text", "Score?",
                                "levels", List.of("low", "med", "high"))
                ),
                "thresholds", Map.of("margin", 0.2, "urgent", 0.8)
        );

        Map<String, Object> result = TypeSafeDecisionClient.classify(config, "help me");

        assertEquals("fake-model", result.get("modelName"));
        Map<?, ?> answers = (Map<?, ?>) result.get("answers");
        Map<?, ?> team = (Map<?, ?>) answers.get("team");
        assertEquals("billing", team.get("value"));
        assertEquals(0.91, ((Map<?, ?>) team.get("probabilities")).get("billing"));
        assertEquals(0.82, (double) team.get("margin"), 1e-9);

        Map<?, ?> urgent = (Map<?, ?>) answers.get("urgent");
        assertEquals("yes", urgent.get("value"));
        assertEquals(0.97, urgent.get("probability"));

        Map<?, ?> score = (Map<?, ?>) answers.get("score");
        assertEquals(0.8, score.get("mean"));

        assertFalse((Boolean) result.get("lowMargin"));

        // output mapping
        Map<String, Object> state = new java.util.HashMap<>();
        TypeSafeDecisionClient.applyOutputMapping(config, result, state);
        assertEquals("billing", state.get("team"));
        assertEquals("yes", state.get("urgent"));
    }

    @Test
    void lowMarginSetWhenChoiceMarginSmall() throws Exception {
        String resp = "{\"model\":\"m\",\"answers\":{" +
                "\"team\":{\"type\":\"choice\",\"choice\":\"billing\",\"probabilities\":{\"billing\":0.55,\"support\":0.45}}" +
                "},\"usage\":{\"inputTokens\":1,\"outputTokens\":1}}";
        startServer(resp);

        Map<String, Object> config = Map.of(
                "baseUrl", baseUrl,
                "questions", List.of(
                        Map.of("name", "team", "type", "choice", "text", "t",
                                "options", Map.of("billing", "b", "support", "s"))
                ),
                "thresholds", Map.of("margin", 0.2)
        );
        Map<String, Object> result = TypeSafeDecisionClient.classify(config, "x");
        assertTrue((Boolean) result.get("lowMargin"));
    }

    @Test
    void classifyInputMap() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", (HttpExchange ex) -> {
            bodyRef.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String r = "{\"model\":\"m\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.9}},\"usage\":{}}";
            byte[] bytes = r.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();

        Map<String, Object> config = Map.of(
                "baseUrl", url,
                "questions", List.of(Map.of("name", "q", "type", "yesno", "text", "ok?"))
        );
        TypeSafeDecisionClient.classify(config, Map.of("ticket", "hello"));

        String body = bodyRef.get();
        assertTrue(body.contains("\"ticket\":\"hello\"") || body.contains("\"ticket\": \"hello\""));
    }

    @Test
    void failOnHttpError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", (HttpExchange ex) -> {
            byte[] bytes = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();

        Map<String, Object> config = Map.of(
                "baseUrl", url,
                "maxRetries", 0,
                "questions", List.of(Map.of("name", "q", "type", "yesno", "text", "ok?"))
        );
        try {
            TypeSafeDecisionClient.classify(config, "x");
            throw new AssertionError("expected exception");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("HTTP 500") || e.getMessage().contains("TypeSafe"));
        }
    }
}
