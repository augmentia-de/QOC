package de.augmentia.oc.engine.adapter.net;

import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.port.world.HttpTransport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** JDK HTTP client adapter for provider calls and MCP WebSocket channels. */
public final class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;

    public JdkHttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    public JdkHttpTransport(HttpClient client) {
        if (client == null) throw new IllegalArgumentException("client must not be null");
        this.client = client;
    }

    @Override
    public ResponseStream postJson(URI url, Map<String, String> headers, String body, Duration timeout) {
        if (url == null) throw new IllegalArgumentException("url must not be null");
        HttpRequest.Builder request = HttpRequest.newBuilder(url)
                .timeout(timeout == null ? Duration.ofMinutes(2) : timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null) request.header(name, value);
            });
        }
        try {
            HttpResponse<InputStream> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            return new Stream(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted", e));
        }
    }

    @Override
    public Channel openChannel(URI url, Map<String, String> headers) {
        if (url == null) throw new IllegalArgumentException("url must not be null");
        WebSocket.Builder builder = client.newWebSocketBuilder();
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null) builder.header(name, value);
            });
        }
        WebSocketChannel channel = new WebSocketChannel();
        WebSocket socket = builder.buildAsync(url, channel).join();
        channel.attach(socket);
        return channel;
    }

    private record Stream(int status, Map<String, List<String>> headers, InputStream body)
            implements ResponseStream {
        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final class WebSocketChannel implements WebSocket.Listener, Channel {

        private final StringBuilder partial = new StringBuilder();
        private volatile Consumer<String> listener;
        private volatile WebSocket socket;

        @Override
        public void send(String text) {
            WebSocket current = socket;
            if (current == null) throw new Defect("channel is not connected yet");
            current.sendText(text == null ? "" : text, true).join();
        }

        @Override
        public void onMessage(Consumer<String> listener) {
            this.listener = listener;
        }

        void attach(WebSocket webSocket) {
            this.socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            attach(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                Consumer<String> current = listener;
                if (current != null) current.accept(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void close() {
            WebSocket current = socket;
            if (current != null) current.abort();
        }
    }
}
