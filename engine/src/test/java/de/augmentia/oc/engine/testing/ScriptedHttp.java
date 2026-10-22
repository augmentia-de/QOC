package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.port.world.HttpTransport;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** One scripted response per request. */
public final class ScriptedHttp implements HttpTransport {

    private final Deque<Response> responses;
    private final List<Request> requests = new ArrayList<>();
    private final List<ScriptedChannel> channels = new ArrayList<>();

    public ScriptedHttp(Response... responses) {
        this(List.of(responses));
    }

    public ScriptedHttp(List<Response> responses) {
        this.responses = new ArrayDeque<>(responses == null ? List.of() : responses);
    }

    @Override
    public synchronized ResponseStream postJson(URI url, Map<String, String> headers, String body, Duration timeout) {
        requests.add(new Request(url, headers, body, timeout));
        Response response = responses.pollFirst();
        if (response == null) throw new IllegalStateException("ScriptedHttp has no response left");
        return new Stream(response);
    }

    @Override
    public synchronized Channel openChannel(URI url, Map<String, String> headers) {
        ScriptedChannel channel = new ScriptedChannel(url, headers);
        channels.add(channel);
        return channel;
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    public synchronized List<ScriptedChannel> channels() {
        return List.copyOf(channels);
    }

    public record Request(URI url, Map<String, String> headers, String body, Duration timeout) {
        public Request {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public record Response(int status, Map<String, List<String>> headers, byte[] body) {
        public Response {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? new byte[0] : body.clone();
        }

        public static Response text(int status, String body) {
            return new Response(status, Map.of("content-type", List.of("text/plain")),
                    body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static final class Stream implements ResponseStream {

        private final Response response;
        private boolean closed;

        private Stream(Response response) {
            this.response = response;
        }

        @Override
        public int status() {
            return response.status();
        }

        @Override
        public Map<String, List<String>> headers() {
            return response.headers();
        }

        @Override
        public InputStream body() {
            return new ByteArrayInputStream(response.body());
        }

        @Override
        public void close() {
            closed = true;
        }

        public boolean closed() {
            return closed;
        }
    }

    public static final class ScriptedChannel implements Channel {

        private final URI url;
        private final Map<String, String> headers;
        private final List<String> sent = new ArrayList<>();
        private Consumer<String> listener;
        private boolean closed;

        private ScriptedChannel(URI url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        }

        @Override
        public synchronized void send(String text) {
            sent.add(text);
        }

        @Override
        public synchronized void onMessage(Consumer<String> listener) {
            this.listener = listener;
        }

        public synchronized void emit(String text) {
            if (listener != null) listener.accept(text);
        }

        public URI url() {
            return url;
        }

        public Map<String, String> headers() {
            return Map.copyOf(headers);
        }

        public synchronized List<String> sent() {
            return List.copyOf(sent);
        }

        @Override
        public synchronized void close() {
            closed = true;
        }

        public synchronized boolean closed() {
            return closed;
        }
    }
}
