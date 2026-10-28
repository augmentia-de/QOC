package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider.A2AEndpoint;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of a configured A2A endpoint for the discovery API and UI.
 *
 * <p>Exactly one of the three shapes applies: {@code disabled} (configured but
 * switched off), {@code ok} (card resolved), or {@code error} (card could not
 * be fetched). Card-derived fields are {@code null} in the latter two cases.</p>
 */
public record A2AAgentDiscovery(
        String id,
        String url,
        String tool,
        boolean enabled,
        String status,
        String name,
        String description,
        String version,
        String protocolVersion,
        List<Skill> skills,
        String fetchedAt,
        boolean cached,
        String error
) {
    public record Skill(String id, String name, String description) {
    }

    public A2AAgentDiscovery {
        skills = skills == null ? List.of() : List.copyOf(skills);
        if (status == null) status = "error";
        if (id == null) id = "";
        if (url == null) url = "";
        if (tool == null) tool = "";
    }

    public static A2AAgentDiscovery disabled(A2AEndpoint endpoint, String tool) {
        return new A2AAgentDiscovery(endpoint.id(), endpoint.url(), tool, false, "disabled",
                null, null, null, null, List.of(), null, false, null);
    }

    public static A2AAgentDiscovery ok(A2AEndpoint endpoint, String tool, AgentCard card,
                                       Instant fetchedAt, boolean cached) {
        return new A2AAgentDiscovery(endpoint.id(), endpoint.url(), tool, true, "ok",
                card.name(), card.description(), card.version(), protocolVersion(card),
                skills(card), fetchedAt == null ? null : fetchedAt.toString(), cached, null);
    }

    public static A2AAgentDiscovery error(A2AEndpoint endpoint, String tool, String message) {
        return new A2AAgentDiscovery(endpoint.id(), endpoint.url(), tool, true, "error",
                null, null, null, null, List.of(), null, false,
                message == null || message.isBlank() ? "agent card unavailable" : message);
    }

    private static String protocolVersion(AgentCard card) {
        List<AgentInterface> interfaces = card.supportedInterfaces();
        if (interfaces == null || interfaces.isEmpty()) {
            return null;
        }
        return interfaces.getFirst().protocolVersion();
    }

    private static List<Skill> skills(AgentCard card) {
        List<Skill> result = new ArrayList<>();
        for (AgentSkill skill : card.skills()) {
            result.add(new Skill(skill.id(), skill.name(), skill.description()));
        }
        return result;
    }
}
