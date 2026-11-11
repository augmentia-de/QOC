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
import java.util.Map;

/**
 * {@code securerest}: HTTP GET against a protected endpoint, attaching a Bearer token
 * derived from the current principal (raw OIDC token or a freshly issued tool token).
 */
public final class SecureRestTool {

    public static final String DESCRIPTION = """
            Perform an authenticated HTTP GET against a protected endpoint. Attaches a Bearer token
            derived from the current principal (OIDC access token or a freshly issued tool token).""";

    private final HttpTransport transport;

    public SecureRestTool(HttpTransport transport) {
        if (transport == null) throw new IllegalArgumentException("transport must not be null");
        this.transport = transport;
    }

    public static ToolDefinition definition(SecureRestTool tool) {
        if (tool == null) throw new IllegalArgumentException("tool must not be null");
        Schema input = Schema.obj(Map.of(
                "url", Schema.str(),
                "useIssuedToken", Schema.opt(Schema.bool())));
        return new ToolDefinition(new ToolName("securerest"), DESCRIPTION, input, Option.none(),
                (value, context) -> tool.execute(value, context),
                new ToolOptions(Option.none(), Option.of("securerest"), true, false));
    }

    private ToolOutcome execute(JsonValue value, de.augmentia.oc.engine.core.contract.tool.ToolContext context) {
        String raw = null;
        try {
            raw = JsonArgs.requiredString(value, "url");
            URI uri = URI.create(raw);
            boolean useIssued = JsonArgs.boolOr(value, "useIssuedToken", false);

            String token = null;
            if (context != null) {
                if (useIssued && context.toolTokenService() != null && context.toolTokenService().isEnabled()
                        && context.principal() != null && !"anonymous".equals(context.principal().id())) {
                    token = context.toolTokenService().issue(context.principal(), "securerest",
                            context.sessionId().value(), java.util.List.of(), 60);
                } else if (context.principal() != null) {
                    token = context.principal().accessToken();
                }
            }

            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("Accept", "application/json");
            if (token != null && !token.isBlank()) {
                headers.put("Authorization", "Bearer " + token);
            }

            try (HttpTransport.ResponseStream response =
                         transport.get(uri, headers, java.time.Duration.ofSeconds(30))) {
                int status = response.status();
                String body = read(response.body());
                if (status < 200 || status >= 300) {
                    return ReadTool.failure("securerest", "request failed (HTTP " + status + "): " + body);
                }
                return new ToolOutcome.Ok(new ToolResult(Option.none(),
                        List.of(new ToolContent.TextItem(body)),
                        Map.of("url", raw, "status", status)));
            }
        } catch (IOException e) {
            return ReadTool.failure("securerest", "cannot read response for " + raw);
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("securerest", e.getMessage());
        } catch (RuntimeException e) {
            return ReadTool.failure("securerest", "unable to fetch " + raw + ": " + e.getMessage());
        }
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            if (out.size() + read > 5 * 1024 * 1024) {
                throw new IllegalArgumentException("response too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
