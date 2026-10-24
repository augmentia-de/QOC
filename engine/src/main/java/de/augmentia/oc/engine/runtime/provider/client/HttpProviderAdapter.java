package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.contract.provider.TransportDelivery;
import de.augmentia.oc.engine.core.contract.provider.TransportOperation;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.core.contract.provider.LlmTrace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * HTTP/SSE provider adapter. It only translates: request to wire format, raw
 * error to failure class. Retry decisions stay outside (V-02). Provider errors
 * are delivered as events, never as a thrown stream abort (V-33).
 */
public final class HttpProviderAdapter implements ProviderAdapter {

    private final HttpTransport transport;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final Function<ModelRequest, String> encoder;
    private final SseReader sseReader;
    private final Supplier<EventTranslator> translatorFactory;
    private final ProviderErrorClassifier classifier;
    private final Duration timeout;
    private final LlmTrace trace;

    public HttpProviderAdapter(HttpTransport transport, URI endpoint, Map<String, String> headers,
                               Function<ModelRequest, String> encoder, SseReader sseReader,
                               Supplier<EventTranslator> translatorFactory, ProviderErrorClassifier classifier,
                               Duration timeout) {
        this(transport, endpoint, headers, encoder, sseReader, translatorFactory, classifier, timeout,
                LlmTrace.noop());
    }

    public HttpProviderAdapter(HttpTransport transport, URI endpoint, Map<String, String> headers,
                               Function<ModelRequest, String> encoder, SseReader sseReader,
                               Supplier<EventTranslator> translatorFactory, ProviderErrorClassifier classifier,
                               Duration timeout, LlmTrace trace) {
        if (transport == null || endpoint == null || encoder == null || sseReader == null
                || translatorFactory == null || classifier == null || timeout == null) {
            throw new IllegalArgumentException("adapter dependencies must not be null");
        }
        this.transport = transport;
        this.endpoint = endpoint;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.encoder = encoder;
        this.sseReader = sseReader;
        this.translatorFactory = translatorFactory;
        this.classifier = classifier;
        this.timeout = timeout;
        this.trace = trace == null ? LlmTrace.noop() : trace;
    }

    @Override
    public Stream<ModelEvent> stream(ModelRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        String body = encoder.apply(request);
        trace.request(endpoint.toString(), body);
        HttpTransport.ResponseStream response = null;
        try {
            response = transport.postJson(endpoint, mergeHeaders(request), body, timeout);
            if (response.status() < 200 || response.status() >= 300) {
                String errorBody = readBody(response);
                trace.error(response.status(), errorBody);
                ModelEvent failure = providerFailure(response.status(), errorBody, retryAfter(response));
                trace.response(response.status(), List.of(failure));
                return Stream.of(failure);
            }

            List<SseReader.SseFrame> frames = sseReader.read(response.body());
            EventTranslator translator = translatorFactory.get();
            List<ModelEvent> events = new ArrayList<>();
            for (SseReader.SseFrame frame : frames) {
                events.addAll(translator.translate(frame));
            }
            StreamValidator validator = new StreamValidator();
            List<ModelEvent> validated = validator.finish(validator.validate(events)).stream().toList();
            trace.response(response.status(), validated);
            return validated.stream();
        } catch (UncheckedIOException e) {
            trace.transportFailure(e.getMessage());
            return Stream.of(new ModelEvent.ProviderFailed(
                    new FailureKind.Transport(TransportDelivery.UNSENT, TransportOperation.SEND, "io"),
                    "transport failure", Option.none(), Option.none()));
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private Map<String, String> mergeHeaders(ModelRequest request) {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putAll(request.headers());
        return Map.copyOf(merged);
    }

    private ModelEvent.ProviderFailed providerFailure(int status, String body, Option<Duration> retryAfter) {
        String code = null;
        String type = null;
        Result<JsonValue> parsed = Json.parse(body);
        if (parsed instanceof Result.Ok<JsonValue> ok && ok.value() instanceof JsonValue.Obj object) {
            JsonValue error = object.values().get("error");
            if (error instanceof JsonValue.Obj errorObject) {
                code = string(errorObject, "code");
                type = string(errorObject, "type");
            }
            if (code == null) code = string(object, "code");
            if (type == null) type = string(object, "type");
        }
        FailureKind kind = classifier.classify(status, code, type, retryAfter);
        return new ModelEvent.ProviderFailed(kind, "", Option.some(status), retryAfter);
    }

    private static String string(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Str str ? str.value() : null;
    }

    private static String readBody(HttpTransport.ResponseStream response) {
        try {
            return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static Option<Duration> retryAfter(HttpTransport.ResponseStream response) {
        List<String> values = response.headers().get("retry-after");
        if (values == null || values.isEmpty()) return Option.none();
        try {
            return Option.some(Duration.ofSeconds(Long.parseLong(values.getFirst().trim())));
        } catch (NumberFormatException e) {
            return Option.none();
        }
    }
}
