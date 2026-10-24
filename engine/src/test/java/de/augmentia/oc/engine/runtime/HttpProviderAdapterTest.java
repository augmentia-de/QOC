package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProviderId;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.runtime.provider.client.HttpProviderAdapter;
import de.augmentia.oc.engine.runtime.provider.client.JsonEventTranslator;
import de.augmentia.oc.engine.runtime.provider.client.ProviderErrorClassifier;
import de.augmentia.oc.engine.runtime.provider.client.SseReader;
import de.augmentia.oc.engine.testing.ScriptedHttp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpProviderAdapterTest {

    private static final URI ENDPOINT = URI.create("https://provider.test/v1/chat");

    @Test
    void translatesASuccessfulStream() {
        String sse = sse("{\"type\":\"text.started\",\"blockId\":\"b1\"}",
                "{\"type\":\"text.delta\",\"blockId\":\"b1\",\"text\":\"hi\"}",
                "{\"type\":\"text.ended\",\"blockId\":\"b1\",\"text\":\"hi\"}",
                "{\"type\":\"finished\",\"reason\":\"normal\"}");
        ScriptedHttp http = new ScriptedHttp(stream(sse));
        HttpProviderAdapter adapter = adapter(http);

        List<ModelEvent> events = adapter.stream(request(Map.of())).toList();

        assertThat(events).hasSize(4);
        assertThat(events.getLast()).isInstanceOf(ModelEvent.Finished.class);
    }

    @Test
    void providerErrorsAreEventsNotExceptions() {
        ScriptedHttp http = new ScriptedHttp(new ScriptedHttp.Response(429,
                Map.of("retry-after", List.of("2")),
                "{\"error\":{\"code\":\"rate_limit\",\"type\":\"rate_limit_error\"}}"
                        .getBytes(StandardCharsets.UTF_8)));
        HttpProviderAdapter adapter = adapter(http);

        List<ModelEvent> events = adapter.stream(request(Map.of())).toList();

        assertThat(events).hasSize(1);
        ModelEvent.ProviderFailed failure = (ModelEvent.ProviderFailed) events.getFirst();
        assertThat(failure.kind()).isInstanceOf(FailureKind.RateLimited.class);
        assertThat(failure.status().orElse(0)).isEqualTo(429);
        assertThat(failure.retryAfter().orElseThrow()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void contextOverflowIsClassifiedFromTheCode() {
        ScriptedHttp http = new ScriptedHttp(new ScriptedHttp.Response(400, Map.of(),
                "{\"error\":{\"code\":\"context_length_exceeded\",\"type\":\"invalid_request_error\"}}"
                        .getBytes(StandardCharsets.UTF_8)));
        HttpProviderAdapter adapter = adapter(http);

        ModelEvent.ProviderFailed failure = (ModelEvent.ProviderFailed) adapter.stream(request(Map.of()))
                .toList().getFirst();
        assertThat(failure.kind()).isInstanceOf(FailureKind.InvalidRequest.class);
    }

    @Test
    void truncatedStreamBecomesAnIncompleteOutputEvent() {
        String sse = sse("{\"type\":\"text.started\",\"blockId\":\"b1\"}",
                "{\"type\":\"text.ended\",\"blockId\":\"b1\",\"text\":\"partial\"}");
        ScriptedHttp http = new ScriptedHttp(stream(sse));
        HttpProviderAdapter adapter = adapter(http);

        List<ModelEvent> events = adapter.stream(request(Map.of())).toList();

        assertThat(events.getLast()).isInstanceOf(ModelEvent.ProviderFailed.class);
        assertThat(((ModelEvent.ProviderFailed) events.getLast()).kind())
                .isInstanceOf(FailureKind.InvalidOutput.class);
    }

    @Test
    void contentFilterBecomesContentPolicyFailure() {
        String sse = sse("{\"type\":\"finished\",\"reason\":\"content_filter\"}");
        ScriptedHttp http = new ScriptedHttp(stream(sse));
        HttpProviderAdapter adapter = adapter(http);

        ModelEvent.ProviderFailed failure = (ModelEvent.ProviderFailed) adapter.stream(request(Map.of()))
                .toList().getFirst();
        assertThat(failure.kind()).isInstanceOf(FailureKind.ContentPolicy.class);
    }

    @Test
    void transportFailureBecomesAnEvent() {
        HttpProviderAdapter adapter = adapter(new FailingTransport());

        List<ModelEvent> events = adapter.stream(request(Map.of())).toList();

        assertThat(events).hasSize(1);
        assertThat(((ModelEvent.ProviderFailed) events.getFirst()).kind())
                .isInstanceOf(FailureKind.Transport.class);
    }

    @Test
    void requestHeadersAreMerged() {
        ScriptedHttp http = new ScriptedHttp(stream(sse("{\"type\":\"finished\",\"reason\":\"normal\"}")));
        HttpProviderAdapter adapter = adapter(http);

        adapter.stream(request(Map.of("x-session-affinity", "ses_abc"))).toList();

        assertThat(http.requests().getFirst().headers()).containsEntry("x-session-affinity", "ses_abc");
    }

    @Test
    void tracesTheRequestAndResponse() {
        String sse = sse("{\"type\":\"text.started\",\"blockId\":\"b1\"}",
                "{\"type\":\"text.delta\",\"blockId\":\"b1\",\"text\":\"hi\"}",
                "{\"type\":\"text.ended\",\"blockId\":\"b1\",\"text\":\"hi\"}",
                "{\"type\":\"finished\",\"reason\":\"normal\"}");
        ScriptedHttp http = new ScriptedHttp(stream(sse));
        CaptureTrace trace = new CaptureTrace();
        HttpProviderAdapter adapter = new HttpProviderAdapter(http, ENDPOINT, Map.of(),
                request -> "{\"encoded\":true}", new SseReader(), JsonEventTranslator::new,
                new ProviderErrorClassifier(), Duration.ofSeconds(5), trace);

        adapter.stream(request(Map.of())).toList();

        assertThat(trace.endpoint).isEqualTo(ENDPOINT.toString());
        assertThat(trace.requestBody).isEqualTo("{\"encoded\":true}");
        assertThat(trace.status).isEqualTo(200);
        assertThat(trace.events).extracting(Object::getClass)
                .contains(ModelEvent.TextDelta.class, ModelEvent.Finished.class);
    }

    private HttpProviderAdapter adapter(HttpTransport transport) {
        return new HttpProviderAdapter(transport, ENDPOINT, Map.of("authorization", "Bearer x"),
                request -> "{}", new SseReader(), JsonEventTranslator::new,
                new ProviderErrorClassifier(), Duration.ofSeconds(5));
    }

    private static ScriptedHttp.Response stream(String body) {
        return new ScriptedHttp.Response(200, Map.of("content-type", List.of("text/event-stream")),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static String sse(String... data) {
        StringBuilder text = new StringBuilder();
        for (String line : data) {
            text.append("data: ").append(line).append("\n\n");
        }
        return text.toString();
    }

    private static ModelRequest request(Map<String, String> headers) {
        return new ModelRequest(ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of(), List.of(), List.of(), Option.none(), GenerationOptions.defaults(),
                Map.of(), headers, Option.none());
    }

    private static final class CaptureTrace implements de.augmentia.oc.engine.core.contract.provider.LlmTrace {
        private String endpoint;
        private String requestBody;
        private int status;
        private List<ModelEvent> events = List.of();

        @Override
        public void request(String endpoint, String body) {
            this.endpoint = endpoint;
            this.requestBody = body;
        }

        @Override
        public void response(int status, List<ModelEvent> events) {
            this.status = status;
            this.events = events;
        }

        @Override
        public void error(int status, String body) {}

        @Override
        public void transportFailure(String message) {}
    }

    private static final class FailingTransport implements HttpTransport {
        @Override
        public ResponseStream postJson(URI url, Map<String, String> headers, String body, Duration timeout) {
            throw new UncheckedIOException(new IOException("connection refused"));
        }

        @Override
        public Channel openChannel(URI url, Map<String, String> headers) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
