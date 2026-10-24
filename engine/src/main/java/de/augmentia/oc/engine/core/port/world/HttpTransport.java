package de.augmentia.oc.engine.core.port.world;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** HTTP transport for the provider adapter and MCP. */
public interface HttpTransport {

    ResponseStream postJson(URI url, Map<String, String> headers, String body, Duration timeout);

    /**
     * Plain GET for read-only HTTP tools (webfetch). Implementations that do
     * not support it keep the default and fail fast.
     */
    default ResponseStream get(URI url, Map<String, String> headers, Duration timeout) {
        throw new UnsupportedOperationException("GET is not supported by this transport");
    }

    Channel openChannel(URI url, Map<String, String> headers);

    interface ResponseStream extends AutoCloseable {

        int status();

        Map<String, List<String>> headers();

        InputStream body();

        @Override
        void close();
    }

    interface Channel extends AutoCloseable {

        void send(String text);

        void onMessage(Consumer<String> listener);

        @Override
        void close();
    }
}
