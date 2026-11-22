package de.augmentia.oc.engine.runtime.provider.client;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemOneHttpClientTest {

    @Test
    void postsWithCustomHeaderAndNormalizesUrl() throws Exception {
        AtomicReference<String> custom = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            custom.set(exchange.getRequestHeaders().getFirst("X-Test"));
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            String body = SystemOneHttpClient.post(
                    "http://localhost:" + server.getAddress().getPort() + "/",
                    "/v1/systemone", null, Map.of("", "ignored", "X-Test", "yes"),
                    "{}", 5, 0);
            assertThat(body).isEqualTo("{\"ok\":true}");
            assertThat(custom.get()).isEqualTo("yes");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsWithoutRetryOn4xx() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = "bad".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            assertThatThrownBy(() -> SystemOneHttpClient.post(
                    "http://localhost:" + server.getAddress().getPort(), "v1/systemone", "k",
                    Map.of(), "{}", 5, 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 400");
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOn5xxAndSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            int n = calls.incrementAndGet();
            String body = n == 1 ? "server error" : "{\"ok\":true}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(n == 1 ? 500 : 200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            String body = SystemOneHttpClient.post(
                    "http://localhost:" + server.getAddress().getPort(), "v1/systemone", "k",
                    Map.of(), "{}", 5, 1);
            assertThat(body).isEqualTo("{\"ok\":true}");
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsOpenOnConnectionRefused() {
        assertThatThrownBy(() -> SystemOneHttpClient.post(
                "http://localhost:1", "v1/systemone", "k", Map.of(), "{}", 1, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TypeSafe request failed");
    }

    @Test
    void defaultsPathWhenBlank() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            String body = SystemOneHttpClient.post(
                    "http://localhost:" + server.getAddress().getPort(), " ", "k",
                    Map.of(), "{}", 5, 0);
            assertThat(body).isEqualTo("{}");
        } finally {
            server.stop(0);
        }
    }
}
