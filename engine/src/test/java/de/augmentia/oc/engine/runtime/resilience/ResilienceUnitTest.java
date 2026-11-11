package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.ToolName;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienceUnitTest {

    @Test
    void retryPolicyNoRetryForPermanentAndCritical() {
        RetryPolicy p = new RetryPolicy.Default();
        assertThat(p.delay(0, ErrorClassifier.ErrorType.PERMANENT)).isEqualTo(Duration.ZERO);
        assertThat(p.delay(0, ErrorClassifier.ErrorType.CRITICAL)).isEqualTo(Duration.ZERO);
    }

    @Test
    void retryPolicyPositiveForTransient() {
        RetryPolicy p = new RetryPolicy.Default(100, 1000, 0);
        Duration d = p.delay(0, ErrorClassifier.ErrorType.TRANSIENT);
        assertThat(d.toMillis()).isBetween(100L, 1000L);
        Duration d2 = p.delay(3, ErrorClassifier.ErrorType.TRANSIENT);
        assertThat(d2.toMillis()).isGreaterThanOrEqualTo(100L);
    }

    @Test
    void retryPolicyCapsAtMax() {
        RetryPolicy p = new RetryPolicy.Default(1000, 2000, 0);
        Duration d = p.delay(10, ErrorClassifier.ErrorType.TRANSIENT);
        assertThat(d.toMillis()).isLessThanOrEqualTo(2000L);
    }

    @Test
    void retryPolicyJitterWithinRange() {
        RetryPolicy p = new RetryPolicy.Default(100, 1000, 50);
        Duration d = p.delay(0, ErrorClassifier.ErrorType.CORRECTABLE);
        assertThat(d.toMillis()).isBetween(100L, 150L);
    }

    @Test
    void loopDetectorNoLoopWhenEmpty() {
        LoopDetector d = new LoopDetector.Default();
        ToolCall call = new ToolCall(CallId.create(), new ToolName("t"), JsonValue.ofNull());
        assertThat(d.isLoop(List.of(), call, 3)).isFalse();
        assertThat(d.isLoop(null, call, 3)).isFalse();
        assertThat(d.isLoop(List.of(), null, 3)).isFalse();
    }

    @Test
    void loopDetectorDetectsRepeatedCalls() {
        LoopDetector d = new LoopDetector.Default();
        ToolCall call = new ToolCall(CallId.create(), new ToolName("t"), JsonValue.of("x"));
        List<ToolCall> recent = List.of(call, call);
        assertThat(d.isLoop(recent, call, 3)).isTrue();
    }

    @Test
    void loopDetectorBreaksOnDifferentInput() {
        LoopDetector d = new LoopDetector.Default();
        ToolCall a = new ToolCall(CallId.create(), new ToolName("t"), JsonValue.of("x"));
        ToolCall b = new ToolCall(CallId.create(), new ToolName("t"), JsonValue.of("y"));
        List<ToolCall> recent = List.of(a, a);
        assertThat(d.isLoop(recent, b, 3)).isFalse();
    }

    @Test
    void errorClassifierTransient() {
        DefaultErrorClassifier c = new DefaultErrorClassifier();
        assertThat(c.classify(new RuntimeException("HTTP 503"), null)).isEqualTo(ErrorClassifier.ErrorType.TRANSIENT);
        assertThat(c.classify(new RuntimeException("timeout"), null)).isEqualTo(ErrorClassifier.ErrorType.TRANSIENT);
        assertThat(c.classify(new RuntimeException("429 too many requests"), null)).isEqualTo(ErrorClassifier.ErrorType.TRANSIENT);
    }

    @Test
    void errorClassifierPermanent() {
        DefaultErrorClassifier c = new DefaultErrorClassifier();
        assertThat(c.classify(new RuntimeException("403 forbidden"), null)).isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
        assertThat(c.classify(new RuntimeException("400 bad request"), null)).isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
        assertThat(c.classify(new RuntimeException("401 unauthorized"), null)).isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
    }

    @Test
    void errorClassifierCorrectable() {
        DefaultErrorClassifier c = new DefaultErrorClassifier();
        assertThat(c.classify(new IllegalArgumentException("bad"), null)).isEqualTo(ErrorClassifier.ErrorType.CORRECTABLE);
        assertThat(c.classify(new RuntimeException("syntax error"), null)).isEqualTo(ErrorClassifier.ErrorType.CORRECTABLE);
    }

    @Test
    void errorClassifierCriticalOnLoop() {
        DefaultErrorClassifier c = new DefaultErrorClassifier(3);
        ErrorClassifier.ErrorContext ctx = new ErrorClassifier.ErrorContext("tool", "msg", 5);
        assertThat(c.classify(new RuntimeException("x"), ctx)).isEqualTo(ErrorClassifier.ErrorType.CRITICAL);
    }

    @Test
    void errorClassifierNullErrorIsPermanent() {
        DefaultErrorClassifier c = new DefaultErrorClassifier();
        assertThat(c.classify(null, null)).isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
    }

    @Test
    void noOpTriageReturnsNotRetryable() {
        NoOpTriageService t = new NoOpTriageService();
        TriageService.TriageResult r = t.analyze(new TriageService.TriageContext("tool", "err", "{}", "state", 1, 3));
        assertThat(r.retryable()).isFalse();
        assertThat(r.correctedInput()).isEmpty();
        assertThat(r.explanation()).contains("disabled");
    }

    @Test
    void webhookAlerterNullUrlDoesNothing() {
        WebhookAlerter.Default a = new WebhookAlerter.Default(null, "generic");
        a.alert(new WebhookAlerter.Alert("t1", "PERMANENT", "msg", null, null, null));
        // no exception
    }

    @Test
    void webhookAlerterNoneUrlDoesNothing() {
        WebhookAlerter.Default a = new WebhookAlerter.Default("none", "slack");
        a.alert(new WebhookAlerter.Alert("t1", "PERMANENT", "msg", null, List.of("http://x"), null));
    }

    @Test
    void webhookAlerterInvalidUrlDoesNotThrow() {
        WebhookAlerter.Default a = new WebhookAlerter.Default("http://127.0.0.1:1/nope", "generic");
        a.alert(new WebhookAlerter.Alert("t1", "PERMANENT", "msg", "state", List.of(), null));
    }

    @Test
    void preflightCheckerMissingApiKeyFails() {
        DefaultPreflightChecker checker = new DefaultPreflightChecker();
        PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                null, "", null, null);
        PreflightChecker.PreflightResult res = checker.check(ctx);
        assertThat(res.passed()).isFalse();
        assertThat(res.issues()).anyMatch(i -> "auth".equals(i.type()));
    }

    @Test
    void preflightCheckerPlaceholderApiKeyWarns() {
        DefaultPreflightChecker checker = new DefaultPreflightChecker();
        PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                null, "demo-key", null, null);
        PreflightChecker.PreflightResult res = checker.check(ctx);
        // placeholder is non-blocking
        assertThat(res.issues()).anyMatch(i -> "auth".equals(i.type()) && !i.blocking());
    }

    @Test
    void preflightCheckerWorkspaceMissingFails(@TempDir Path tmp) throws Exception {
        Path missing = tmp.resolve("nope");
        DefaultPreflightChecker checker = new DefaultPreflightChecker();
        PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                null, "sk-real", null, missing);
        PreflightChecker.PreflightResult res = checker.check(ctx);
        assertThat(res.passed()).isFalse();
        assertThat(res.issues()).anyMatch(i -> "workspace".equals(i.type()));
    }

    @Test
    void preflightCheckerWorkspaceWritablePasses(@TempDir Path tmp) {
        DefaultPreflightChecker checker = new DefaultPreflightChecker();
        PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                null, "sk-real", null, tmp);
        PreflightChecker.PreflightResult res = checker.check(ctx);
        assertThat(res.issues().stream().noneMatch(i -> "workspace".equals(i.type()))).isTrue();
    }

    @Test
    void preflightCheckerDbUnhealthyFails() {
        DefaultPreflightChecker checker = new DefaultPreflightChecker();
        PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                null, "sk-real", () -> false, null);
        PreflightChecker.PreflightResult res = checker.check(ctx);
        assertThat(res.passed()).isFalse();
        assertThat(res.issues()).anyMatch(i -> "database".equals(i.type()));
    }

    @Test
    void preflightCheckerInvalidProviderUrlFailsDns() {
        DefaultPreflightChecker checker = new DefaultPreflightChecker();
        PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                "http://this-host-does-not-exist.invalid", "sk-real", null, null);
        PreflightChecker.PreflightResult res = checker.check(ctx);
        assertThat(res.passed()).isFalse();
        assertThat(res.issues()).anyMatch(i -> "dns".equals(i.type()));
    }

    @Test
    void preflightCheckerHttpFailureOnClosedPort() {
        DefaultPreflightChecker checker = new DefaultPreflightChecker();
        PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                "http://127.0.0.1:1/", "sk-real", null, null);
        PreflightChecker.PreflightResult res = checker.check(ctx);
        assertThat(res.issues()).anyMatch(i -> "health".equals(i.type()));
    }

    @Test
    void preflightCheckerEmptyContextPasses() {
        DefaultPreflightChecker checker = new DefaultPreflightChecker();
        PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                null, "sk-real", null, null);
        PreflightChecker.PreflightResult res = checker.check(ctx);
        assertThat(res.passed()).isTrue();
    }

    @Test
    void preflightCheckerCustomTimeout() {
        DefaultPreflightChecker checker = new DefaultPreflightChecker(
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build(),
                java.time.Duration.ofMillis(100));
        PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                null, "sk-real", null, null);
        PreflightChecker.PreflightResult res = checker.check(ctx);
        assertThat(res).isNotNull();
    }

    @Test
    void preflightProviderChecksAreCached() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            DefaultPreflightChecker checker = new DefaultPreflightChecker(
                    java.net.http.HttpClient.newHttpClient(), java.time.Duration.ofSeconds(2),
                    java.time.Duration.ofMinutes(1), true, true);
            PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                    url, "sk-real", null, null);
            checker.check(ctx);
            checker.check(ctx);
            assertThat(hits.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preflightChecksCanBeDisabled() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            DefaultPreflightChecker checker = new DefaultPreflightChecker(
                    java.net.http.HttpClient.newHttpClient(), java.time.Duration.ofSeconds(2),
                    java.time.Duration.ofMinutes(1), false, false);
            PreflightChecker.PreflightContext ctx = new PreflightChecker.PreflightContext(
                    url, "sk-real", null, null);
            PreflightChecker.PreflightResult result = checker.check(ctx);
            assertThat(hits.get()).isZero();
            assertThat(result.issues()).noneMatch(i -> "dns".equals(i.type()) || "health".equals(i.type()));
        } finally {
            server.stop(0);
        }
    }
}
