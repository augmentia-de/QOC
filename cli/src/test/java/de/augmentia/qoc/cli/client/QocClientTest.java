package de.augmentia.qoc.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QocClientTest {

    private HttpServer server;
    private QocClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        server.createContext("/api/session", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"id\":\"s1\"}");
            } else {
                respond(exchange, 200, "{\"data\":[]}");
            }
        });
        server.createContext("/api/session/s1/prompt", exchange ->
                respond(exchange, 200, "{\"text\":\"hi\",\"finish\":\"NORMAL\",\"inputTokens\":1,\"outputTokens\":2}"));
        server.createContext("/api/event", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"_type\":\"TextDelta\",\"text\":\"hi\"}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        server.start();
        client = new QocClient("http://127.0.0.1:" + server.getAddress().getPort(), null);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void health() throws Exception {
        JsonNode r = client.health();
        assertThat(r.path("status").asText()).isEqualTo("ok");
    }

    @Test
    void sessionLifecycle() throws Exception {
        assertThat(client.listSessions().path("data").isArray()).isTrue();
        assertThat(client.createSession().path("id").asText()).isEqualTo("s1");
        JsonNode p = client.runPrompt("s1", "hello");
        assertThat(p.path("text").asText()).isEqualTo("hi");
        assertThat(p.path("finish").asText()).isEqualTo("NORMAL");
    }

    @Test
    void streamEventsReceivesDelta() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonNode> event = new AtomicReference<>();
        QocClient.EventStream stream = client.streamEvents("s1", ev -> {
            event.set(ev);
            latch.countDown();
        });
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(event.get().path("_type").asText()).isEqualTo("TextDelta");
            assertThat(event.get().path("text").asText()).isEqualTo("hi");
        } finally {
            stream.close();
        }
    }

    @Test
    void httpErrorIsReported() throws Exception {
        server.createContext("/api/fail", exchange -> respond(exchange, 500, "boom"));
        assertThatThrownBy(() -> client.get("/api/fail"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("500");
    }

    @Test
    void postErrorIsReported() throws Exception {
        server.createContext("/api/fail", exchange -> respond(exchange, 400, "bad"));
        assertThatThrownBy(() -> client.post("/api/fail", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("400");
    }
}
