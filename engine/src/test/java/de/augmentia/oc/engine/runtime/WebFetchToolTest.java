package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.runtime.tool.web.WebFetchTool;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebFetchToolTest {

    @Test
    void convertsHtmlToMarkdown() {
        FakeTransport transport = new FakeTransport(200, "text/html; charset=utf-8", """
                <html><head><script>bad()</script></head><body>
                <h1>Title</h1><p>Hello <strong>world</strong> and <a href="https://example.com">link</a>.</p>
                <ul><li>one</li><li>two</li></ul></body></html>""");

        ToolOutcome outcome = execute(transport, Map.of("url", JsonValue.of("https://example.com")));

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        String text = text(outcome);
        assertThat(text).contains("# Title");
        assertThat(text).contains("**world**");
        assertThat(text).contains("[link](https://example.com)");
        assertThat(text).contains("- one");
        assertThat(text).doesNotContain("bad()");
        assertThat(transport.lastHeaders).containsKey("User-Agent");
    }

    @Test
    void textFormatStripsMarkup() {
        FakeTransport transport = new FakeTransport(200, "text/html", "<p>Hello <b>world</b></p>");

        ToolOutcome outcome = execute(transport, Map.of(
                "url", JsonValue.of("https://example.com"),
                "format", JsonValue.of("text")));

        assertThat(text(outcome)).isEqualTo("Hello world");
    }

    @Test
    void rejectsNonTextualContent() {
        FakeTransport transport = new FakeTransport(200, "application/pdf", "%PDF");

        ToolOutcome outcome = execute(transport, Map.of("url", JsonValue.of("https://example.com/a.pdf")));

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).contains("unsupported content type");
    }

    @Test
    void rejectsNonHttpSchemes() {
        ToolOutcome outcome = execute(new FakeTransport(200, "text/plain", "x"),
                Map.of("url", JsonValue.of("file:///etc/passwd")));

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).contains("http://");
    }

    @Test
    void rejectsLoopbackAndMetadataDestinations() {
        for (String url : List.of("http://127.0.0.1/", "http://localhost/",
                "http://169.254.169.254/latest/meta-data/", "http://10.0.0.1/")) {
            ToolOutcome outcome = execute(new FakeTransport(200, "text/plain", "x"),
                    Map.of("url", JsonValue.of(url)));
            assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
            assertThat(((ToolOutcome.Failed) outcome).error().message())
                    .contains("destination is not allowed");
        }
    }

    @Test
    void reportsHttpErrors() {
        ToolOutcome outcome = execute(new FakeTransport(404, "text/plain", "missing"),
                Map.of("url", JsonValue.of("https://example.com/missing")));

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).contains("HTTP 404");
    }

    @Test
    void htmlFormatReturnsTheRawBody() {
        FakeTransport transport = new FakeTransport(200, "text/html", "<p>raw</p>");
        ToolOutcome outcome = execute(transport, Map.of(
                "url", JsonValue.of("https://example.com"),
                "format", JsonValue.of("html")));
        assertThat(text(outcome)).isEqualTo("<p>raw</p>");
    }

    @Test
    void plainTextIsReturnedUnchanged() {
        FakeTransport transport = new FakeTransport(200, "application/json", "{\"ok\":true}");
        ToolOutcome outcome = execute(transport, Map.of("url", JsonValue.of("https://example.com/api")));
        assertThat(text(outcome)).isEqualTo("{\"ok\":true}");
    }

    @Test
    void emptyContentTypeIsTreatedAsText() {
        FakeTransport transport = new FakeTransport(200, "", "plain");
        assertThat(text(execute(transport, Map.of("url", JsonValue.of("https://example.com")))))
                .isEqualTo("plain");
    }

    @Test
    void rejectsImagesAndInvalidFormats() {
        FakeTransport image = new FakeTransport(200, "image/png", "png");
        assertThat(execute(image, Map.of("url", JsonValue.of("https://example.com/a.png"))))
                .isInstanceOf(ToolOutcome.Failed.class);

        FakeTransport text = new FakeTransport(200, "text/plain", "x");
        assertThat(execute(text, Map.of("url", JsonValue.of("https://example.com"),
                "format", JsonValue.of("pdf"))))
                .isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void rejectsOversizedResponses() {
        FakeTransport transport = new FakeTransport(200, "text/plain",
                "x".repeat(WebFetchTool.MAX_RESPONSE_BYTES + 1));
        ToolOutcome outcome = execute(transport, Map.of("url", JsonValue.of("https://example.com")));
        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).contains("too large");
    }

    @Test
    void reportsTransportErrors() {
        HttpTransport failing = new HttpTransport() {
            @Override
            public ResponseStream get(URI url, Map<String, String> headers, Duration timeout) {
                throw new IllegalStateException("connection refused");
            }

            @Override
            public ResponseStream postJson(URI url, Map<String, String> headers, String body, Duration timeout) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public Channel openChannel(URI url, Map<String, String> headers) {
                throw new UnsupportedOperationException("not used");
            }
        };

        assertThat(execute(failing, Map.of("url", JsonValue.of("https://example.com"))))
                .isInstanceOf(ToolOutcome.Failed.class);
    }

    private static ToolOutcome execute(HttpTransport transport, Map<String, JsonValue> input) {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(),
                MessageId.create(), CallId.create());
        return WebFetchTool.definition(new WebFetchTool(transport)).execute()
                .execute(JsonValue.of(input), context);
    }

    private static String text(ToolOutcome outcome) {
        return ((ToolContent.TextItem) ((ToolOutcome.Ok) outcome).result().content().getFirst()).text();
    }

    private static final class FakeTransport implements HttpTransport {
        private final int status;
        private final String contentType;
        private final String body;
        private Map<String, String> lastHeaders = Map.of();

        private FakeTransport(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        @Override
        public ResponseStream get(URI url, Map<String, String> headers, Duration timeout) {
            this.lastHeaders = Map.copyOf(headers);
            return new Stream(status, Map.of("content-type", List.of(contentType)), body);
        }

        @Override
        public ResponseStream postJson(URI url, Map<String, String> headers, String body, Duration timeout) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Channel openChannel(URI url, Map<String, String> headers) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class Stream implements HttpTransport.ResponseStream {
        private final int status;
        private final Map<String, List<String>> headers;
        private final InputStream body;

        private Stream(int status, Map<String, List<String>> headers, String body) {
            this.status = status;
            this.headers = new LinkedHashMap<>(headers);
            this.body = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public Map<String, List<String>> headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public void close() {}
    }
}
