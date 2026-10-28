package de.augmentia.oc.engine.http.a2a;

import de.augmentia.oc.engine.http.AgentCatalogue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TransportProtocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishes the QOC agent catalogue as an A2A agent card under
 * {@code /.well-known/agent-card.json}.
 *
 * <p>Each visible QOC agent becomes an A2A skill. The JSON-RPC binding is
 * advertised for protocol versions 0.3 and 1.0 so that both older clients and
 * the current client SDK can negotiate a compatible version.</p>
 */
@ApplicationScoped
public class A2AAgentCardProducer {
    @Inject
    A2AServerConfig config;

    @Inject
    AgentCatalogue agentCatalogue;

    @Produces
    @PublicAgentCard
    @Singleton
    public AgentCard agentCard() {
        return AgentCard.builder()
                .name(config.name())
                .description(config.description())
                .version(config.version())
                .url(config.baseUrl())
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .supportedInterfaces(List.of(
                        new AgentInterface(TransportProtocol.JSONRPC.asString(),
                                config.endpointUrl(), null, "1.0"),
                        new AgentInterface(TransportProtocol.JSONRPC.asString(),
                                config.endpointUrl(), null, "0.3")))
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(skills())
                .build();
    }

    private List<AgentSkill> skills() {
        List<AgentSkill> skills = new ArrayList<>();
        for (AgentCatalogue.AgentDto agent : agentCatalogue.list()) {
            if (agent.hidden()) {
                continue;
            }
            skills.add(AgentSkill.builder()
                    .id(agent.name())
                    .name(agent.name())
                    .description("QOC agent " + agent.name())
                    .tags(List.of("qoc", "agent"))
                    .examples(List.of())
                    .build());
        }
        if (skills.isEmpty()) {
            skills.add(AgentSkill.builder()
                    .id("qoc")
                    .name("QOC")
                    .description("QOC AI agent runtime")
                    .tags(List.of("qoc"))
                    .examples(List.of())
                    .build());
        }
        return List.copyOf(skills);
    }
}
