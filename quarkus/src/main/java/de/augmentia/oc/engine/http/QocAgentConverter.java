package de.augmentia.oc.engine.http;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;

import java.util.List;
import java.util.Locale;

/**
 * Converts QOC engine agents ({@link EngineConfig.AgentConfig}) into workflow
 * agents ({@link WorkflowDefinition.Agent}) so both systems share one store.
 */
public final class QocAgentConverter {

    private QocAgentConverter() {}

    public static Agent fromQoc(EngineConfig.AgentConfig cfg) {
        if (cfg == null) return null;
        String name = cfg.name() == null || cfg.name().isBlank() ? "agent" : cfg.name();
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (id.isBlank()) id = "agent-" + System.currentTimeMillis();
        // tools: QOC uses allow/deny patterns; workflow uses a flat list.
        // "*" in toolAllow means "all tools" -> keep "*" sentinel (not empty,
        // because empty means "no tools" in the workflow-agent semantics).
        List<String> tools = (cfg.toolAllow() == null)
                ? List.of("*")
                : (cfg.toolAllow().isEmpty() ? List.of() : List.copyOf(cfg.toolAllow()));
        List<String> guardrailsIn = cfg.permissions() == null ? List.of() : List.of();
        return new Agent(
                id,
                name,
                "1.0.0",
                cfg.hidden() ? "hidden" : "",
                cfg.model() == null ? "" : cfg.model(),
                0.3,
                4096,
                1.0,
                tools,
                guardrailsIn,
                List.of(),
                List.of(),
                false,
                !cfg.hidden(),
                cfg.system() == null ? "" : cfg.system(),
                null,       // responseSchema
                null,       // ownerId (global/shared)
                cfg.rolesAllowed() == null ? List.of("*") : List.copyOf(cfg.rolesAllowed()),
                cfg.mode() == null ? "all" : cfg.mode(),
                List.of(),   // skills (QOC skills are patterns, not names)
                10, 1.0, false);
    }
}