package de.augmentia.oc.engine.runtime.provider.client;

import com.sun.net.httpserver.HttpServer;

import de.augmentia.oc.engine.core.model.DecisionRequest;
import de.augmentia.oc.engine.core.model.DecisionResult;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TypeSafeDecisionAdapterTest {

    @Test
    void isAvailableReflectsBaseUrl() {
        TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("https://x", "m", "k", 5, 0);
        assertThat(a.isAvailable()).isTrue();
        TypeSafeDecisionAdapter b = new TypeSafeDecisionAdapter("", "m", "k", 5, 0);
        assertThat(b.isAvailable()).isFalse();
    }

    @Test
    void decideReturnsParsedResultOnSuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "{\"model\":\"m\",\"answers\":{\"q0\":{\"type\":\"yesno\",\"noul\":0.1,\"probability\":0.9,\"margin\":0.5}},\"lowMargin\":false,\"usage\":{\"inputTokens\":10,\"outputTokens\":5}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 0);
            DecisionResult r = a.decide(new DecisionRequest("triage", "q", java.util.List.of(), "ctx", java.util.Map.of()));
            assertThat(r.answer()).isEqualTo("no");
            assertThat(r.noul()).isFalse();
            assertThat(r.probabilities()).isNotEmpty();
            assertThat(r.latencyMillis()).isGreaterThanOrEqualTo(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideChoiceType() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "{\"model\":\"m\",\"answers\":{\"q0\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":0.8,\"b\":0.2},\"margin\":0.6}},\"lowMargin\":false}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 0);
            DecisionResult r = a.decide(new DecisionRequest("classify", "q", java.util.List.of(), "ctx",
                    java.util.Map.of("type", "choice", "options", java.util.Map.of("a", "A", "b", "B"))));
            assertThat(r.answer()).isEqualTo("a");
            assertThat(r.probabilities()).contains(0.8);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideScaleType() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "{\"model\":\"m\",\"answers\":{\"q0\":{\"type\":\"scale\",\"score\":0.7,\"probabilities\":[0.2,0.8],\"margin\":0.6}},\"lowMargin\":false}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 0);
            DecisionResult r = a.decide(new DecisionRequest("scale", "q", java.util.List.of(), "ctx",
                    java.util.Map.of("type", "scale", "levels", java.util.List.of("low", "high"))));
            assertThat(r.answer()).isEqualTo("0.7");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideMultipleQuestions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "{\"model\":\"m\",\"answers\":{\"q0\":{\"type\":\"yesno\",\"noul\":0.1},\"q1\":{\"type\":\"yesno\",\"noul\":0.9}},\"lowMargin\":false}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 0);
            DecisionResult r = a.decide(new DecisionRequest("triage", "q", java.util.List.of("q1", "q2"), "ctx", java.util.Map.of()));
            assertThat(r.answers()).hasSize(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideYesnoNoulDerivesYes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "{\"model\":\"m\",\"answers\":{\"q0\":{\"type\":\"yesno\",\"noul\":0.9}},\"lowMargin\":false}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 0);
            DecisionResult r = a.decide(new DecisionRequest("triage", "q", java.util.List.of(), "ctx", java.util.Map.of()));
            assertThat(r.answer()).isEqualTo("yes");
            assertThat(r.noul()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideFailOpenOnHttpError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "boom";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 0);
            DecisionResult r = a.decide(new DecisionRequest("triage", "q", java.util.List.of(), "ctx", java.util.Map.of()));
            assertThat(r.noul()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideFailOpenOnConnectionRefused() {
        TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:1", "m", "k", 1, 0);
        DecisionResult r = a.decide(new DecisionRequest("triage", "q", java.util.List.of(), "ctx", java.util.Map.of()));
        assertThat(r.noul()).isTrue();
    }

    @Test
    void decideTruncatesLongInput() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "{\"model\":\"m\",\"answers\":{\"q0\":{\"type\":\"yesno\",\"noul\":0.1}},\"lowMargin\":false}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            String longCtx = "x".repeat(2500);
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 0);
            DecisionResult r = a.decide(new DecisionRequest("triage", "q", java.util.List.of(), longCtx, java.util.Map.of()));
            assertThat(r.noul()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideFailOpenOn4xx() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "bad request";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 0);
            DecisionResult r = a.decide(new DecisionRequest("triage", "q", java.util.List.of(), "ctx", java.util.Map.of()));
            assertThat(r.noul()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideFailOpenOnParseError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "not json";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 0);
            DecisionResult r = a.decide(new DecisionRequest("triage", "q", java.util.List.of(), "ctx", java.util.Map.of()));
            assertThat(r.noul()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideFailOpenOnNonObjectResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "[1,2,3]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 0);
            DecisionResult r = a.decide(new DecisionRequest("triage", "q", java.util.List.of(), "ctx", java.util.Map.of()));
            assertThat(r.noul()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideInterruptedDuringRetrySleep() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "server error";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 2);
            Thread t = new Thread(() -> {
                try { a.decide(new DecisionRequest("triage", "q", java.util.List.of(), "ctx", java.util.Map.of())); }
                catch (RuntimeException ignored) {}
            });
            t.start();
            Thread.sleep(100);
            t.interrupt();
            t.join(2000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decideRetriesOn5xx() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
        server.createContext("/v1/systemone", exchange -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                String body = "server error";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } else {
                String body = "{\"model\":\"m\",\"answers\":{\"q0\":{\"type\":\"yesno\",\"noul\":0.1}},\"lowMargin\":false}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            TypeSafeDecisionAdapter a = new TypeSafeDecisionAdapter("http://localhost:" + port, "m", "k", 5, 1);
            DecisionResult r = a.decide(new DecisionRequest("triage", "q", java.util.List.of(), "ctx", java.util.Map.of()));
            assertThat(r.noul()).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
