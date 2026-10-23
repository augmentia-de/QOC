package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code <mcp_instructions>} block. Only servers whose tools are not
 * entirely denied contribute (tools/04-mcp.md §7 rule 1).
 */
public final class McpInstructions {

    public static final String KEY = "core/mcp-instructions";

    private McpInstructions() {}

    public static String build(Map<String, List<McpRegistrar.McpToolSpec>> discovered, List<Rule> rules) {
        if (discovered == null || discovered.isEmpty()) return "";
        List<Rule> safeRules = rules == null ? List.of() : rules;
        StringBuilder servers = new StringBuilder();
        boolean any = false;
        for (Map.Entry<String, List<McpRegistrar.McpToolSpec>> entry : discovered.entrySet()) {
            List<McpRegistrar.McpToolSpec> tools = entry.getValue();
            if (tools == null || tools.isEmpty()) continue;
            List<McpRegistrar.McpToolSpec> visible = tools.stream()
                    .filter(tool -> PermissionRules.evaluate(safeRules,
                            entry.getKey() + "_" + tool.name(), "*") != Effect.DENY)
                    .toList();
            if (visible.isEmpty()) continue;
            any = true;
            servers.append("  <server name=\"").append(entry.getKey()).append("\">\n");
            for (McpRegistrar.McpToolSpec tool : visible) {
                servers.append("    <tool>").append(tool.name()).append("</tool>\n");
            }
            servers.append("  </server>\n");
        }
        if (!any) return "";
        return PromptLoader.render("mcp-instructions", Map.of("servers", servers.toString().strip()));
    }
}
