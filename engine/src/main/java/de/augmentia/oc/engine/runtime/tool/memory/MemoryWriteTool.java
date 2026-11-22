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
import de.augmentia.oc.engine.core.model.Fact;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code memory_write}: stores a fact in the semantic memory.
 * Requires permission {@code memory}.
 */
public final class MemoryWriteTool {

    private MemoryWriteTool() {}

    public static ToolDefinition definition(MemoryStore store, PermissionGate permissionGate) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        Schema input = Schema.obj(Map.of(
                "entity", Schema.str(),
                "relation", Schema.str(),
                "value", Schema.str(),
                "provenance", Schema.opt(Schema.str())));
        return new ToolDefinition(new ToolName("memory_write"),
                "Store a fact in semantic memory. Requires permission 'memory'.",
                input, Option.none(),
                (value, context) -> {
                    if (permissionGate != null) {
                        PermissionRequest req = new PermissionRequest(
                                de.augmentia.oc.engine.core.model.PermissionRequestId.create(),
                                context.sessionId(),
                                "memory",
                                List.of("memory"),
                                List.of("memory"),
                                Map.of("tool", "memory_write"),
                                null, null);
                        permissionGate.assertAllowed(req);
                    }
                    String entity = JsonArgs.string(value, "entity");
                    String relation = JsonArgs.string(value, "relation");
                    String val = JsonArgs.string(value, "value");
                    String prov = JsonArgs.string(value, "provenance");
                    List<String> provenance = (prov == null || prov.isBlank())
                            ? List.of() : List.of(prov);
                    String factId = "fact_" + UUID.nameUUIDFromBytes(
                            (entity + "|" + relation + "|" + val).getBytes()).toString().replace("-", "");
                    Fact fact = new Fact(factId, entity, relation, val, Instant.now(),
                            Instant.now(), null, provenance, 1.0, 0);
                    store.upsert(fact);
                    return new ToolOutcome.Ok(ToolResult.of("stored fact " + factId));
                },
                new de.augmentia.oc.engine.core.contract.tool.ToolOptions(Option.none(), Option.of("memory"), true, false));
    }
}