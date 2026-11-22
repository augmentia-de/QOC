package de.augmentia.oc.engine.runtime.tool.memory;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.store.MemoryStore;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.core.model.MemoryQuery;

import java.util.List;
import java.util.Map;

/**
 * {@code memory_query}: queries the semantic memory.
 * Requires permission {@code memory}.
 */
public final class MemoryQueryTool {

    private MemoryQueryTool() {}

    public static ToolDefinition definition(MemoryStore store, PermissionGate permissionGate) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        Schema input = Schema.obj(Map.of(
                "entity", Schema.opt(Schema.str()),
                "relation", Schema.opt(Schema.str()),
                "text", Schema.opt(Schema.str()),
                "limit", Schema.opt(Schema.intg())));
        return new ToolDefinition(new ToolName("memory_query"),
                "Query semantic memory for facts. Requires permission 'memory'.",
                input, Option.none(),
                (value, context) -> {
                    if (permissionGate != null) {
                        PermissionRequest req = new PermissionRequest(
                                de.augmentia.oc.engine.core.model.PermissionRequestId.create(),
                                context.sessionId(),
                                "memory",
                                List.of("memory"),
                                List.of("memory"),
                                Map.of("tool", "memory_query"),
                                null, null);
                        permissionGate.assertAllowed(req);
                    }
                    String entity = JsonArgs.string(value, "entity");
                    String relation = JsonArgs.string(value, "relation");
                    String text = JsonArgs.string(value, "text");
                    int limit = JsonArgs.intOr(value, "limit", 20);
                    MemoryQuery q = new MemoryQuery(entity, relation, null, text, limit);
                    List<de.augmentia.oc.engine.core.model.Fact> facts = store.query(q);
                    StringBuilder sb = new StringBuilder();
                    sb.append("Found ").append(facts.size()).append(" facts:\n");
                    for (de.augmentia.oc.engine.core.model.Fact f : facts) {
                        sb.append("- ").append(f.entity()).append(" / ").append(f.relation())
                                .append(" = ").append(f.value()).append("\n");
                    }
                    return new ToolOutcome.Ok(ToolResult.of(sb.toString()));
                },
                new de.augmentia.oc.engine.core.contract.tool.ToolOptions(Option.none(), Option.of("memory"), true, false));
    }
}