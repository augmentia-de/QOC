package de.augmentia.oc.engine.http;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies the A2A discovery endpoint against a local stub agent.
 *
 * <p>The stub is started before Quarkus so that tool registration at startup
 * warms the shared card cache. The endpoint list also contains a disabled
 * endpoint and an endpoint that fails with HTTP 500.</p>
 */
@QuarkusTest
@TestProfile(A2AAgentsResourceTest.StubProfile.class)
class A2AAgentsResourceTest {

    @Test
    void listsConfiguredAgentsWithCardDetails() {
        given()
                .get("/api/a2a/agents")
                .then().statusCode(200)
                .body("data", hasSize(3))
                .body("data[0].id", is("stub"))
                .body("data[0].status", is("ok"))
                .body("data[0].name", is("Stub Peer"))
                .body("data[0].tool", is("a2a_stub"))
                .body("data[0].cached", is(true))
                .body("data[0].protocolVersion", is("1.0"))
                .body("data[0].skills[0].id", is("stub_skill"))
                .body("data[0].error", org.hamcrest.Matchers.nullValue())
                .body("data[1].id", is("off"))
                .body("data[1].enabled", is(false))
                .body("data[1].status", is("disabled"))
                .body("data[2].id", is("broken"))
                .body("data[2].status", is("error"))
                .body("data[2].error", notNullValue());
    }

    @Test
    void refreshForcesANewCardFetch() {
        given()
                .post("/api/a2a/agents/refresh")
                .then().statusCode(200)
                .body("data[0].status", is("ok"))
                .body("data[0].cached", is(false));
    }

    @Test
    void queryRefreshAlsoReloads() {
        given()
                .get("/api/a2a/agents?refresh=true")
                .then().statusCode(200)
                .body("data[0].status", is("ok"))
                .body("data[0].cached", is(false));
    }

    public static class StubProfile implements QuarkusTestProfile {
        static final int PORT;

        static {
            try {
                String card = JsonUtil.toJson(AgentCard.builder()
                        .name("Stub Peer")
                        .description("Stub A2A peer for discovery tests")
                        .version("2.1.0")
                        .capabilities(AgentCapabilities.builder()
                                .streaming(false)
                                .pushNotifications(false)
                                .build())
                        .defaultInputModes(List.of("text"))
                        .defaultOutputModes(List.of("text"))
                        .skills(List.of(AgentSkill.builder()
                                .id("stub_skill")
                                .name("Stub Skill")
                                .description("Answers stub requests")
                                .tags(List.of("test"))
                                .examples(List.of())
                                .build()))
                        .supportedInterfaces(List.of(new AgentInterface("JSONRPC",
                                "http://127.0.0.1:1", null, "1.0")))
                        .build());
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/.well-known/agent-card.json", exchange ->
                        respond(exchange, 200, card));
                server.createContext("/broken/.well-known/agent-card.json",
                        exchange -> respond(exchange, 500, "{\"error\":\"unavailable\"}"));
                server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "a2a-stub");
                    thread.setDaemon(true);
                    return thread;
                }));
                server.start();
                PORT = server.getAddress().getPort();
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "engine.a2a.endpoints",
                    "id=stub|url=http://127.0.0.1:" + PORT + "|tool=a2a_stub|enabled=true;"
                            + "id=off|url=http://127.0.0.1:1|enabled=false;"
                            + "id=broken|url=http://127.0.0.1:" + PORT + "/broken|enabled=true",
                    "engine.a2a.cache.ttl-seconds", "600");
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
                throws java.io.IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
