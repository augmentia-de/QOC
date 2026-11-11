package de.augmentia.qoc.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Thin HTTP client for the QOC engine REST API.
 */
public final class QocClient {

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public QocClient(String baseUrl, String token) {
        this(baseUrl, token, Duration.ofSeconds(60));
    }

    public QocClient(String baseUrl, String token, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String baseUrl() { return baseUrl; }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout);
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    public JsonNode get(String path) throws Exception {
        HttpRequest req = request(path).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) throw new RuntimeException("HTTP " + res.statusCode() + ": " + res.body());
        return mapper.readTree(res.body());
    }

    public JsonNode post(String path, Map<String, Object> body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest req = request(path).POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json").build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) throw new RuntimeException("HTTP " + res.statusCode() + ": " + res.body());
        return mapper.readTree(res.body());
    }

    public JsonNode delete(String path) throws Exception {
        HttpRequest req = request(path).DELETE().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) throw new RuntimeException("HTTP " + res.statusCode() + ": " + res.body());
        if (res.body().isBlank()) return mapper.createObjectNode();
        return mapper.readTree(res.body());
    }

    public JsonNode health() throws Exception {
        return get("/api/health");
    }

    public JsonNode listSessions() throws Exception {
        return get("/api/session");
    }

    public JsonNode createSession() throws Exception {
        return createSession("build");
    }

    public JsonNode createSession(String agent) throws Exception {
        return post("/api/session", Map.of("agent", agent));
    }

    public JsonNode runPrompt(String sessionId, String text) throws Exception {
        return post("/api/session/" + sessionId + "/prompt", Map.of("text", text));
    }

    public HttpClient raw() { return http; }
    public ObjectMapper mapper() { return mapper; }

    /**
     * Opens the live SSE stream for a session.
     *
     * <p>{@code after=Long.MAX_VALUE} suppresses replay of historical events;
     * only events published after the subscription are delivered.</p>
     */
    public EventStream streamEvents(String sessionId, Consumer<JsonNode> onEvent) throws Exception {
        String encoded = URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
        HttpRequest req = request("/api/event?session=" + encoded + "&live=true&after=" + Long.MAX_VALUE)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() >= 400) {
            try (InputStream body = res.body()) {
                String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("HTTP " + res.statusCode() + ": " + text);
            }
        }
        return new EventStream(res.body(), mapper, onEvent);
    }

    /** Live SSE subscription. Close it to stop the reader thread. */
    public static final class EventStream implements AutoCloseable {
        private final InputStream body;
        private final ObjectMapper mapper;
        private final Consumer<JsonNode> onEvent;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread reader;

        EventStream(InputStream body, ObjectMapper mapper, Consumer<JsonNode> onEvent) {
            this.body = body;
            this.mapper = mapper;
            this.onEvent = onEvent;
            this.reader = Thread.ofVirtual().name("qoc-sse").start(this::readLoop);
        }

        private void readLoop() {
            StringBuilder data = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while (!closed.get() && (line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                        dispatch(data);
                        data.setLength(0);
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                    }
                }
                dispatch(data);
            } catch (IOException e) {
                if (!closed.get()) {
                    System.err.println("[sse] " + e.getMessage());
                }
            }
        }

        private void dispatch(StringBuilder data) {
            if (data.length() == 0) return;
            try {
                onEvent.accept(mapper.readTree(data.toString()));
            } catch (Exception e) {
                // Malformed event must not terminate the stream.
            }
        }

        @Override
        public void close() {
            closed.set(true);
            try {
                body.close();
            } catch (IOException ignored) {
            }
            reader.interrupt();
        }
    }
}
