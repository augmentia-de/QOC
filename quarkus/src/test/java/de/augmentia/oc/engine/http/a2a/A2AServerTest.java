package de.augmentia.oc.engine.http.a2a;

import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.runtime.tool.a2a.A2ATool;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(A2AServerTest.EnabledProfile.class)
class A2AServerTest {

    @TestHTTPResource("/")
    URL base;

    @Test
    void agentCardIsPublished() {
        given().get("/.well-known/agent-card.json")
                .then().statusCode(200)
                .body("name", equalTo("QOC Test Agent"))
                .body("supportedInterfaces", hasSize(2))
                .body("supportedInterfaces[0].protocolBinding", equalTo("JSONRPC"))
                .body("skills", notNullValue());
    }

    @Test
    void a2aClientCanExecuteAPromptAndUseThePhase1Tool() throws Exception {
        String agentBase = base.toString().replaceAll("/+$", "");
        AgentCard discovered = A2A.getAgentCard(agentBase);
        // The card advertises the configured public base URL. Point the client at
        // the random test port instead so the round-trip stays hermetic.
        AgentCard card = AgentCard.builder(discovered)
                .url(agentBase)
                .supportedInterfaces(List.of(
                        new AgentInterface(TransportProtocol.JSONRPC.asString(), agentBase)))
                .build();
        Client client = Client.builder(card)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
                .clientConfig(new ClientConfig.Builder().setStreaming(false).build())
                .build();

        var tool = A2ATool.definition(card, client, "a2a_qoc");
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(),
                MessageId.create(), CallId.create());
        ToolOutcome outcome = tool.execute().execute(
                new JsonValue.Obj(Map.of("text", JsonValue.of("Hallo"))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        ToolResult result = ((ToolOutcome.Ok) outcome).result();
        String text = result.content().stream()
                .filter(c -> c instanceof de.augmentia.oc.engine.core.model.ToolContent.TextItem)
                .map(c -> ((de.augmentia.oc.engine.core.model.ToolContent.TextItem) c).text())
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(text).contains("Session Engine");
    }

    public static class EnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "engine.a2a.server.enabled", "true",
                    "engine.a2a.server.name", "QOC Test Agent",
                    "engine.a2a.server.base-url", "http://localhost:8080");
        }
    }
}
