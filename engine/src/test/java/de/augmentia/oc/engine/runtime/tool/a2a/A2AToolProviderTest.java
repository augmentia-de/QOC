package de.augmentia.oc.engine.runtime.tool.a2a;

import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2AToolProviderTest {

    private static AgentCard card(String name) {
        return AgentCard.builder()
                .name(name)
                .description("d")
                .version("1.0")
                .defaultInputModes(java.util.List.of("text/plain"))
                .defaultOutputModes(java.util.List.of("text/plain"))
                .skills(java.util.List.of())
                .supportedInterfaces(java.util.List.of())
                .capabilities(new org.a2aproject.sdk.spec.AgentCapabilities(true, true, true, java.util.List.of()))
                .build();
    }

    @Test
    void loadNullReturnsEmpty() {
        assertThat(A2AToolProvider.load(null)).isEmpty();
    }

    @Test
    void loadEmptyReturnsEmpty() {
        assertThat(A2AToolProvider.load(List.of())).isEmpty();
    }

    @Test
    void loadDisabledEndpointSkipped() {
        var ep = new A2AToolProvider.A2AEndpoint("id", "http://x", false, "tool");
        assertThat(A2AToolProvider.load(List.of(ep))).isEmpty();
    }

    @Test
    void loadEgressBlockedSkipped() {
        var ep = new A2AToolProvider.A2AEndpoint("id", "http://evil.com", true, "tool", Map.of(), List.of("example.com"), List.of(), false);
        assertThat(A2AToolProvider.load(List.of(ep))).isEmpty();
    }

    @Test
    void toolNameExplicit() {
        var ep = new A2AToolProvider.A2AEndpoint("id", "http://x", true, "My Tool!");
        assertThat(A2AToolProvider.toolName(ep, card("agent"))).isEqualTo("my_tool_");
    }

    @Test
    void toolNameFromCard() {
        var ep = new A2AToolProvider.A2AEndpoint("id", "http://x", true, "");
        assertThat(A2AToolProvider.toolName(ep, card("Cool Agent"))).isEqualTo("a2a_cool_agent");
    }

    @Test
    void toolNameFromIdWhenNoCard() {
        var ep = new A2AToolProvider.A2AEndpoint("my-id", "http://x", true, "");
        assertThat(A2AToolProvider.toolName(ep, null)).isEqualTo("a2a_my-id");
    }

    @Test
    void toolNameNullEndpoint() {
        assertThat(A2AToolProvider.toolName(null, card("a"))).isEqualTo("a2a_a");
        assertThat(A2AToolProvider.toolName(null, null)).isEqualTo("a2a_agent");
    }
}
