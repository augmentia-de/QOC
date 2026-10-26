package de.augmentia.oc.engine.runtime.tool.web;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code webfetch}: read-only HTTP GET with bounded output and HTML handling.
 * Mirrors the reference tool: text/markdown/html formats, textual MIME types
 * only, 5 MiB cap and a 120 s timeout ceiling.
 */
public final class WebFetchTool {

    public static final int MAX_RESPONSE_BYTES = HtmlMarkdown.MAX_BYTES;
    public static final long DEFAULT_TIMEOUT_SECONDS = 30;
    public static final long MAX_TIMEOUT_SECONDS = 120;
    public static final String DESCRIPTION = """
            Fetch content from an HTTP or HTTPS URL and return it as text, markdown, or HTML. Markdown is the default.
            Use a more targeted tool when one is available. This tool is read-only and bounded in size.""";

    private static final Set<String> FORMATS = Set.of("text", "markdown", "html");
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; QOC-WebFetch/1.0; +https://opencode.ai)";

    private final HttpTransport transport;

    public WebFetchTool(HttpTransport transport) {
        if (transport == null) throw new IllegalArgumentException("transport must not be null");
        this.transport = transport;
    }

    public static ToolDefinition definition(WebFetchTool tool) {
        if (tool == null) throw new IllegalArgumentException("tool must not be null");
        Schema input = Schema.obj(Map.of(
                "url", Schema.str(),
                "format", Schema.opt(Schema.str()),
                "timeout", Schema.opt(Schema.lng())));
        return new ToolDefinition(new ToolName("webfetch"), DESCRIPTION, input, Option.none(),
                (value, context) -> tool.execute(value),
                new ToolOptions(Option.none(), Option.of("webfetch"), true, false));
    }

    private ToolOutcome execute(JsonValue value) {
        String raw = null;
        try {
            raw = JsonArgs.requiredString(value, "url");
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return failure("URL must use http:// or https://");
            }
            validateDestination(uri);
            String format = JsonArgs.string(value, "format");
            format = format == null || format.isBlank()
                    ? "markdown" : format.toLowerCase(Locale.ROOT);
            if (!FORMATS.contains(format)) {
                return failure("format must be text, markdown or html");
            }
            long seconds = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS,
                    JsonArgs.intOr(value, "timeout", (int) DEFAULT_TIMEOUT_SECONDS)));

            try (HttpTransport.ResponseStream response =
                         transport.get(uri, headers(format), Duration.ofSeconds(seconds))) {
                int status = response.status();
                if (status < 200 || status >= 300) {
                    return failure("request failed (HTTP " + status + ")");
                }
                String contentType = header(response.headers(), "content-type");
                String mime = mime(contentType);
                if (mime.startsWith("image/") && !"image/svg+xml".equals(mime)) {
                    return failure("unsupported image content type: " + mime);
                }
                if (!textual(mime)) {
                    return failure("unsupported content type: " + (mime.isBlank() ? "(none)" : mime));
                }
                String body = read(response.body());
                String output = convert(body, contentType, format);
                return new ToolOutcome.Ok(new ToolResult(Option.none(),
                        List.of(new ToolContent.TextItem(output)),
                        Map.of("url", raw, "contentType", contentType, "format", format)));
            }
        } catch (IOException e) {
            return failure("cannot read response for " + raw);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            return failure("unable to fetch " + raw + ": " + e.getMessage());
        }
    }

    /**
     * Blocks literal loopback, link-local, private and metadata destinations.
     * This is a first line of defence, not a DNS-rebinding-proof sandbox.
     */
    private static void validateDestination(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must contain a host");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.equals("metadata")
                || normalized.equals("metadata.google.internal")) {
            throw new IllegalArgumentException("destination is not allowed");
        }
        if (normalized.startsWith("127.") || normalized.startsWith("10.")
                || normalized.startsWith("192.168.") || normalized.startsWith("169.254.")
                || normalized.startsWith("0.") || normalized.equals("::1")
                || normalized.startsWith("fe80:") || normalized.startsWith("fc")
                || normalized.startsWith("fd")) {
            throw new IllegalArgumentException("destination is not allowed");
        }
        if (normalized.startsWith("172.")) {
            String[] parts = normalized.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) {
                        throw new IllegalArgumentException("destination is not allowed");
                    }
                } catch (NumberFormatException ignored) {
                    // Not an IPv4 literal; leave it to normal URL handling.
                }
            }
        }
    }

    private static String convert(String body, String contentType, String format) {
        if (!contentType.toLowerCase(Locale.ROOT).contains("html")) return body;
        return switch (format) {
            case "markdown" -> HtmlMarkdown.toMarkdown(body);
            case "text" -> HtmlMarkdown.toText(body);
            default -> body;
        };
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            if (out.size() + read > MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException(
                        "response too large (exceeds " + MAX_RESPONSE_BYTES + " bytes)");
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, String> headers(String format) {
        return Map.of(
                "User-Agent", USER_AGENT,
                "Accept", accept(format),
                "Accept-Language", "en-US,en;q=0.9");
    }

    private static String accept(String format) {
        return switch (format) {
            case "markdown" -> "text/markdown;q=1.0, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1";
            case "text" -> "text/plain;q=1.0, text/markdown;q=0.9, text/html;q=0.8, */*;q=0.1";
            default -> "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, text/markdown;q=0.7, */*;q=0.1";
        };
    }

    private static String header(Map<String, List<String>> headers, String name) {
        if (headers == null) return "";
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue() == null || entry.getValue().isEmpty()
                        ? "" : entry.getValue().getFirst();
            }
        }
        return "";
    }

    private static String mime(String contentType) {
        if (contentType == null) return "";
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .trim().toLowerCase(Locale.ROOT);
    }

    private static boolean textual(String mime) {
        return mime.isBlank()
                || mime.startsWith("text/")
                || mime.contains("json")
                || mime.contains("xml")
                || mime.contains("javascript")
                || mime.contains("x-www-form-urlencoded")
                || mime.contains("yaml");
    }

    private static ToolOutcome failure(String message) {
        return ReadTool.failure("webfetch", message);
    }
}
