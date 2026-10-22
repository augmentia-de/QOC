package de.augmentia.oc.engine.adapter;

import com.sun.net.httpserver.HttpServer;
import de.augmentia.oc.engine.adapter.net.JdkHttpTransport;
import de.augmentia.oc.engine.core.port.world.HttpTransport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkHttpTransportTest {

    private HttpServer server;
    private URI base;
    private JdkHttpTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("x-test", "yes");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        transport = new JdkHttpTransport();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void postJsonSendsBodyAndHeadersAndReturnsTheResponse() throws Exception {
        HttpTransport.ResponseStream response = transport.postJson(base.resolve("/echo"),
                Map.of("x-client", "test"), "hello", Duration.ofSeconds(2));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers()).containsKey("x-test");
        assertThat(new String(response.body().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        response.close();
    }

    @Test
    void timeoutIsReportedAsUncheckedIoException() {
        assertThatThrownBy(() -> transport.postJson(base.resolve("/slow"), Map.of(), "",
                Duration.ofMillis(50))).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> transport.postJson(null, Map.of(), "", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.openChannel(null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
