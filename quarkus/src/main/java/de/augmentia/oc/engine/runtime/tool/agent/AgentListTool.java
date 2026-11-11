package de.augmentia.oc.engine.runtime.tool.agent;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.http.AgentCatalogue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code agent_list}: lists available agents that can be used as subagents. */
public final class AgentListTool {

    private AgentListTool() {
    }

    public static ToolDefinition definition(AgentCatalogue catalogue) {
        if (catalogue == null) return null;
        Schema input = Schema.obj(Map.of(), false);
        return new ToolDefinition(new ToolName("agent_list"),
                "List available agents that can be used as subagents. Returns agent names and whether they can run as subagents.",
                input, Option.none(),
                (value, context) -> execute(catalogue), null);
    }

    private static ToolOutcome execute(AgentCatalogue catalogue) {
        try {
            List<AgentCatalogue.AgentDto> agents = catalogue.list();
            List<Map<String, Object>> result = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            for (AgentCatalogue.AgentDto a : agents) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", a.name());
                entry.put("hidden", a.hidden());
                entry.put("subagent", catalogue.isSubagent(a.name()));
                result.add(entry);
                sb.append(a.name())
                        .append(catalogue.isSubagent(a.name()) ? " [subagent]" : "")
                        .append("\n");
            }
            List<ToolContent> content = new ArrayList<>();
            content.add(new ToolContent.TextItem(sb.toString()));
            return new ToolOutcome.Ok(new ToolResult(Option.none(), content, Map.of("count", result.size())));
        } catch (Exception e) {
            return new ToolOutcome.Failed(
                    new de.augmentia.oc.engine.core.model.SessionError.ToolExecutionError("agent_list", e.getMessage()));
        }
    }
}
