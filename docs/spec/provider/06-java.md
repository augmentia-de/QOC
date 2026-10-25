# 06 – Java 21 implementation

> The provider in Java 21: types, adapter, stream, classification, retry.
> Without a reactive library – with virtual threads and `java.net.http`.

## Contents

- [1. The events as `sealed interface`](#1-the-events-as-sealed-interface)
- [2. The error as a closed family](#2-the-error-as-a-closed-family)
- [3. The adapter](#3-the-adapter)
- [4. Reading the stream](#4-reading-the-stream)
- [5. Classifying errors](#5-classifying-errors)
- [6. The retry loop](#6-the-retry-loop)
- [7. Budget and headers](#7-budget-and-headers)
- [8. HTTP and SSE with `java.net.http`](#8-http-and-sse-with-javanethttp)
- [9. WebSocket (long-lived channel)](#9-websocket-long-lived-channel)
- [10. The Java pitfalls](#10-the-java-pitfalls)

## 1. The events as `sealed interface`

```java
public sealed interface ModelEvent {

    record TextStarted(String blockId) implements ModelEvent {}
    record TextDelta(String blockId, String text) implements ModelEvent {}
    record TextEnded(String blockId, String text) implements ModelEvent {}

    record ReasoningStarted(String blockId) implements ModelEvent {}
    record ReasoningDelta(String blockId, String text) implements ModelEvent {}
    record ReasoningEnded(String blockId, String text) implements ModelEvent {}

    record ToolInputStarted(String callId, String name) implements ModelEvent {}
    record ToolInputDelta(String callId, String text) implements ModelEvent {}
    record ToolInputEnded(String callId, String text) implements ModelEvent {}

    record ToolCallRequested(String callId, String name, JsonNode input) implements ModelEvent {}
    record ToolResultProvided(String callId, OutcomePayload outcome, boolean providerExecuted) implements ModelEvent {}
    record ToolFailed(String callId, String message) implements ModelEvent {}

    record Finished(FinishReason reason, TokenCount usage, JsonNode raw) implements ModelEvent {}
    record Failed(ProviderFailure failure) implements ModelEvent {}
}
```

> **`sealed` is not an end in itself here.** The compiler enforces that
> every `switch` handles all cases – and when an event is added, the
> build breaks at every place that does not yet know it. That is exactly
> the difference between “forgotten" and “does not compile" with a
> protocol that has many variants.

## 2. The error as a closed family

```java
public sealed interface ProviderFailure {

    record RateLimited(Duration retryAfter) implements ProviderFailure {}
    record ProviderInternal(Optional<Duration> retryAfter) implements ProviderFailure {}
    record Transport(Delivery delivery, Operation operation, String code) implements ProviderFailure {}
    record InvalidOutput(OutputProblem problem) implements ProviderFailure {}
    record InvalidRequest(RequestProblem problem) implements ProviderFailure {}
    record Authentication(Optional<String> hint) implements ProviderFailure {}
    record QuotaExceeded(Optional<String> hint) implements ProviderFailure {}
    record ContentPolicy() implements ProviderFailure {}
    record UnsupportedOperation(String operation) implements ProviderFailure {}
    record NoRoute() implements ProviderFailure {}
    record UnknownProvider() implements ProviderFailure {}
    record Timeout() implements ProviderFailure {}

    enum Delivery { UNSENT, ACCEPTED, REJECTED }
    enum Operation { SEND, READ }
    enum OutputProblem { INCOMPLETE_STREAM, UNKNOWN }
    enum RequestProblem { CONTEXT_OVERFLOW, OTHER }

    /** The ONE place where retryability is defined. */
    default boolean retryable() {
        return switch (this) {
            case RateLimited r          -> true;
            case ProviderInternal p     -> true;
            case Timeout t              -> true;
            case InvalidOutput o        -> o.problem() == OutputProblem.INCOMPLETE_STREAM;
            case InvalidRequest r       -> r.problem() == RequestProblem.CONTEXT_OVERFLOW;
            case Transport t            -> switch (t.delivery()) {
                case UNSENT   -> true;
                case ACCEPTED -> t.operation() == Operation.READ;
                case REJECTED -> false;
            };
            case Authentication a, QuotaExceeded q, ContentPolicy c,
                 UnsupportedOperation u, NoRoute n, UnknownProvider up -> false;
        };
    }
}
```

> **`default boolean retryable()` is the core.** There is **one**
> method, **one** `switch`, **one** truth. Anyone who distributes the
> decision across several classes soon has two opinions.

## 3. The adapter

```java
public interface ProviderAdapter {
    /** Opens a stream. Throws only on connection setup; everything else comes as an event. */
    Stream<ModelEvent> stream(ModelRequest request) throws ProviderFailure;

    ModelCatalog catalog();
}
```

> **The adapter throws only when the stream does not arise at all.**
> Everything that happens during the stream comes as
> `ModelEvent.Failed` – so the engine can decide whether to retry.

## 4. Reading the stream

```java
public final class ModelCall {

    public Outcome call(ModelRequest request, ProviderAdapter adapter) {
        try (Stream<ModelEvent> events = adapter.stream(request)) {
            Publisher publisher = new Publisher();
            events.forEach(publisher::accept);       // read in FULL
            return publisher.finish();
        }
    }
}
```

**`Publisher.accept` checks the protocol and is the only place that
detects defects:**

```java
final class Publisher {

    private final Map<String, BlockState> blocks = new HashMap<>();
    private final Map<String, CallState> calls = new HashMap<>();
    private ProviderFailure failure;

    void accept(ModelEvent event) {
        switch (event) {
            case ModelEvent.TextStarted e -> beginBlock(e.blockId());
            case ModelEvent.TextDelta e   -> requireOpen(e.blockId());
            case ModelEvent.TextEnded e   -> endBlock(e.blockId());

            case ModelEvent.ToolCallRequested e -> {
                requireOpenCallAbsent(e.callId());
                calls.put(e.callId(), new CallState(e.name(), true, false, false));
                publishToolCalled(e);
            }
            case ModelEvent.ToolResultProvided e -> {
                CallState state = requireOpenCall(e.callId());
                if (state.resultSeen) {
                    if (e.outcome() instanceof OutcomePayload.Success) throw new Defect("double result");
                    return;                       // late error result: harmless
                }
                state.resultSeen = true;
                publishToolResult(e);
            }
            case ModelEvent.Failed e -> failure = e.failure();
            case ModelEvent.Finished e -> {
                if (e.reason() == FinishReason.UNKNOWN) {
                    failure = new ProviderFailure.InvalidOutput(INCOMPLETE_STREAM);
                }
            }
            // … the remaining cases
            default -> { }
        }
    }
}
```

> **The `default` branch is allowed because `switch` over a `sealed`
> interface would not be exhaustive without it – here it is deliberately
> empty**, not error handling. Completeness is enforced by the compiler
> for every new case.

## 5. Classifying errors

```java
public final class ErrorClassifier {

    /** Called ONCE per error – afterwards the engine works only with kind. */
    public ProviderFailure classify(HttpResponse<?> response, JsonNode body, Exception cause) {
        if (cause instanceof HttpTimeoutException) return new ProviderFailure.Timeout();

        if (cause instanceof IOException io && response == null) {
            // Before sending: nothing happened. After sending: unclear.
            return new ProviderFailure.Transport(
                    DELIVERY.get(), OPERATION.get(), io.getClass().getSimpleName());
        }

        int status = response.statusCode();
        String code = body == null ? "" : body.path("error").path("code").asText("");
        String type = body == null ? "" : body.path("error").path("type").asText("");

        return switch (status) {
            case 401, 403 -> new ProviderFailure.Authentication(Optional.empty());
            case 402      -> new ProviderFailure.QuotaExceeded(Optional.empty());
            case 429      -> new ProviderFailure.RateLimited(retryAfter(response));
            case 400      -> isContextOverflow(code, type)
                    ? new ProviderFailure.InvalidRequest(CONTEXT_OVERFLOW)
                    : new ProviderFailure.InvalidRequest(OTHER);
            case 404      -> new ProviderFailure.NoRoute();
            case 408      -> new ProviderFailure.Timeout();
            case 413      -> new ProviderFailure.InvalidRequest(CONTEXT_OVERFLOW);
            case 422      -> new ProviderFailure.ContentPolicy();
            case 501      -> new ProviderFailure.UnsupportedOperation(code);
            default       -> status >= 500
                    ? new ProviderFailure.ProviderInternal(retryAfter(response))
                    : new ProviderFailure.InvalidRequest(OTHER);
        };
    }

    /** Structural features of the provider – NO text matching over the message. */
    private boolean isContextOverflow(String code, String type) {
        return switch (code) {
            case "context_length_exceeded", "context_window_exceeded",
                 "string_above_max_length", "request_too_large" -> true;
            default -> type.equals("invalid_request_error") && code.isEmpty() && false;
        };
    }
}
```

> **`isContextOverflow` reads `code`/`type`, not `message`.** That is the
> whole difference. If a provider delivers only free text, the case
> remains `OTHER` – then no compaction happens and the error is visible
> instead of being silently treated wrongly.

## 6. The retry loop

```java
public final class RetryingCall {

    private static final int MAX_ATTEMPTS = 4;

    public Outcome call(ModelRequest request, ProviderAdapter adapter, Deadline deadline) {
        boolean compacted = false;

        for (int attempt = 0; ; attempt++) {
            try {
                return new ModelCall().call(request, adapter);
            } catch (ProviderFailure failure) {
                if (failure instanceof ProviderFailure.InvalidRequest r
                        && r.problem() == RequestProblem.CONTEXT_OVERFLOW) {
                    if (compacted) throw failure;             // V-42: final
                    compacted = true;
                    request = compaction.compact(request);    // V-41: exactly once
                    continue;
                }

                if (!failure.retryable() || attempt + 1 >= MAX_ATTEMPTS) throw failure;

                Duration wait = switch (failure) {
                    case ProviderFailure.RateLimited r when r.retryAfter() != null -> r.retryAfter();
                    default -> backoff(attempt);
                };
                if (deadline.remaining().compareTo(wait) < 0) throw failure;   // V-45

                sleep(wait.second() > 0 ? wait : backoff(attempt));
            }
        }
    }

    private Duration backoff(int attempt) {
        long base = Math.min(1L << attempt, 30L);            // 1,2,4,8… max 30 s
        long jittered = base + ThreadLocalRandom.current().nextLong(1000);
        return Duration.ofSeconds(jittered);
    }

    private void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();               // restore flag
            throw new Aborted("interrupted");
        }
    }
}
```

> **`Thread.sleep` is correct here** – in a virtual thread it does not
> block a platform thread. A `ScheduledExecutorService` would be overhead
> without benefit.
>
> **`Thread.currentThread().interrupt()` is mandatory.** Anyone who does
> not restore the flag makes the thread insensitive to every later abort.

## 7. Budget and headers

```java
public final class RequestBudget {

    private static final int OUTPUT_FALLBACK = 32_000;
    private static final int OUTPUT_MAX      = 256_000;
    private static final int SUMMARY_MAX     = 32_000;
    private static final int OUTPUT_MIN      = 1_024;
    private static final double ESTIMATE_ERROR = 0.15;

    public int outputLimit(ModelInfo.Limit limit, Kind kind, Optional<InputTokens> input) {
        int model = Math.min(limit.output() > 0 ? limit.output() : OUTPUT_FALLBACK, OUTPUT_MAX);
        int requested = kind == Kind.COMPACTION ? Math.min(model, SUMMARY_MAX) : model;

        if (input.isEmpty() || limit.context() <= 0) return requested;

        int room = limit.context()
                - input.get().measured()
                - (int) Math.ceil(input.get().estimated() * (1 + ESTIMATE_ERROR));

        return Math.min(requested, Math.max(OUTPUT_MIN, room));
    }
}
```

```java
public final class Affinity {

    /** Child inherits from parent, fork from source. */
    public static String of(SessionInfo session) {
        if (session.parentId() != null) return session.parentId();
        if (session.fork() != null)     return session.fork().sessionId();
        return session.id();
    }

    public static Map<String, String> headers(String affinity) {
        return Map.of(
                "x-session-affinity", affinity,
                "X-Session-Id", affinity,
                "x-opencode-session", affinity);
    }

    /** Reshape only when the form matches – otherwise unchanged. */
    public static String promptCacheKey(String affinity) {
        return affinity.matches("ses_[0-9a-f]{64}") ? affinity.substring(4) : affinity;
    }
}
```

## 8. HTTP and SSE with `java.net.http`

```java
public final class HttpProviderAdapter implements ProviderAdapter {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public Stream<ModelEvent> stream(ModelRequest request) throws ProviderFailure {
        HttpRequest http = HttpRequest.newBuilder(request.url())
                .timeout(request.timeout())
                .headers(Affinity.headers(request.affinity()).entrySet().stream()
                        .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
                        .toArray(String[]::new))
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(request.body()))
                .build();

        HttpResponse<Stream<String>> response;
        try {
            response = client.send(http, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ProviderFailure.Transport(Delivery.UNSENT, Operation.SEND, e.getClass().getSimpleName());
        }

        if (response.statusCode() >= 400) {
            // read error body and classify
            JsonNode body = Json.parse(response.body().collect(Collectors.joining("\n")));
            throw classifier.classify(response, body, null);
        }

        return response.body()
                .map(SseParser::parse)          // "data: {...}" → Optional<JsonNode>
                .flatMap(Optional::stream)
                .map(Translator::toModelEvent)
                .takeWhile(Objects::nonNull);
    }
}
```

> **`BodyHandlers.ofLines()` streams line by line** – no buffering of
> the whole response. That is exactly what is needed for SSE.
>
> **`takeWhile(Objects::nonNull)` is not an error filter.** It ends the
> stream at the sentinel (`[DONE]`), which the translator returns as
> `null`.

## 9. WebSocket (long-lived channel)

```java
public final class WebSocketChannel implements AutoCloseable {

    private final WebSocket socket;
    private final BlockingQueue<ModelEvent> inbox = new LinkedBlockingQueue<>();

    /** Blocking pull – runs in a virtual thread. */
    public ModelEvent take() throws InterruptedException {
        return inbox.take();
    }

    @Override public void close() {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
}
```

> **The queue is the decoupling.** The WebSocket listener must not
> block; the engine pulls at its own pace. An unbounded queue would be a
> memory leak with a provider that sends faster than the engine
> processes – that is why it is **bounded**
> (`new LinkedBlockingQueue<>(1024)`) and on overflow a **defect**, not
> a silent drop.

## 10. The Java pitfalls

| # | Pitfall | Countermeasure |
|---|---|---|
| 1 | `catch (Exception)` around the stream → defect becomes a retry | only catch `ProviderFailure`; `Defect`/`Aborted` are own roots |
| 2 | `Stream` not closed with try-with-resources → connection stays open | `Stream` is `AutoCloseable`, always in `try` |
| 3 | `HttpClient.send` blocks the carrier thread | call in virtual threads |
| 4 | SSE lines via `BufferedReader.readLine` without timeout | HTTP timeout **and** overall deadline |
| 5 | `Thread.sleep` in a platform thread | use virtual threads |
| 6 | `InterruptedException` swallowed | restore flag, throw `Aborted` |
| 7 | `Duration` with `retryAfter` in seconds vs. milliseconds confused | one type, one unit (`Duration`) |
| 8 | `JsonNode` mutated after it went into an event | `deepCopy` or treat as immutable |
| 9 | Retry counter as a field of the session | **per call** locally – otherwise a later step keeps counting |
| 10 | `switch` over `sealed` without `default` | not possible – the compiler demands completeness |
