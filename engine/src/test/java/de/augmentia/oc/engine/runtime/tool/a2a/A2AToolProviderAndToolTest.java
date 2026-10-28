package de.augmentia.oc.engine.runtime.tool.a2a;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class A2AToolProviderAndToolTest {

    private static AgentCard card(String base) {
        return AgentCard.builder()
                .name("Stub Agent")
                .description("Stub")
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder().streaming(false).pushNotifications(false).build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), base, null, "1.0")))
                .build();
    }

    @Test
    void loadRegistersToolForEnabledEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        String cardJson = org.a2aproject.sdk.jsonrpc.common.json.JsonUtil.toJson(card(base));
        server.createContext("/.well-known/agent-card.json", ex -> {
            byte[] b = cardJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (var o = ex.getResponseBody()) { o.write(b); }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.start();
        try {
            var ep = new A2AToolProvider.A2AEndpoint("stub", base, true, "a2a_stub", Map.of());
            var cache = A2AAgentCardCache.defaults(java.time.Duration.ofMinutes(5));
            var tools = A2AToolProvider.load(List.of(ep), cache);
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name().value()).isEqualTo("a2a_stub");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeReturnsRemoteText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        String cardJson = org.a2aproject.sdk.jsonrpc.common.json.JsonUtil.toJson(card(base));
        server.createContext("/.well-known/agent-card.json", ex -> {
            byte[] b = cardJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (var o = ex.getResponseBody()) { o.write(b); }
        });
        server.createContext("/", ex -> {
            byte[] req = ex.getRequestBody().readAllBytes();
            String body = new String(req, StandardCharsets.UTF_8);
            String id = "1";
            int idx = body.indexOf("\"id\"");
            if (idx >= 0) {
                int colon = body.indexOf(':', idx);
                int q1 = body.indexOf('"', colon);
                int q2 = body.indexOf('"', q1 + 1);
                if (q1 >= 0 && q2 > q1) id = body.substring(q1 + 1, q2);
            }
            String resp = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"task\":{\"id\":\"t1\",\"contextId\":\"c1\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\",\"timestamp\":\"2026-01-01T00:00:00Z\"},\"artifacts\":[{\"artifactId\":\"a1\",\"name\":\"\",\"description\":\"\",\"parts\":[{\"text\":\"Hello from remote\",\"metadata\":{},\"filename\":\"\",\"mediaType\":\"\"}],\"metadata\":{},\"extensions\":[]}],\"history\":[],\"metadata\":{}}}}";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (var o = ex.getResponseBody()) { o.write(b); }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.start();
        try {
            AgentCard c = A2A.getAgentCard(base);
            ClientConfig cfg = new ClientConfig.Builder().setStreaming(false).setUseClientPreference(true).build();
            Client client = Client.builder(c)
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
                    .clientConfig(cfg)
                    .build();
            var tool = A2ATool.definition(c, client, "a2a_stub");
            var outcome = tool.execute().execute(
                    JsonValue.of(Map.of("text", JsonValue.of("hi"))),
                    new de.augmentia.oc.engine.core.contract.tool.ToolContext(
                            de.augmentia.oc.engine.core.model.SessionId.create(),
                            de.augmentia.oc.engine.core.model.AgentId.create(),
                            de.augmentia.oc.engine.core.model.MessageId.create(),
                            de.augmentia.oc.engine.core.model.CallId.create()));
            assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
            ToolResult r = ((ToolOutcome.Ok) outcome).result();
            String text = r.content().stream()
                    .filter(x -> x instanceof de.augmentia.oc.engine.core.model.ToolContent.TextItem)
                    .map(x -> ((de.augmentia.oc.engine.core.model.ToolContent.TextItem) x).text())
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(text).contains("Hello from remote");
        } finally {
            server.stop(0);
        }
    }
}
