package de.augmentia.oc.engine.runtime.tool.memory;

import de.augmentia.oc.engine.adapter.memory.InMemoryMemoryStore;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.port.store.MemoryStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryToolsTest {

    @Test
    void memoryWriteStoresFact() {
        MemoryStore store = new InMemoryMemoryStore();
        ToolDefinition def = MemoryWriteTool.definition(store, null);
        JsonValue input = JsonValue.of(java.util.Map.of(
                "entity", JsonValue.of("Quarkus"),
                "relation", JsonValue.of("version"),
                "value", JsonValue.of("3.40")));
        ToolOutcome outcome = def.execute().execute(input, new ToolContext(de.augmentia.oc.engine.core.model.SessionId.create(), de.augmentia.oc.engine.core.model.AgentId.create(), de.augmentia.oc.engine.core.model.MessageId.create(), de.augmentia.oc.engine.core.model.CallId.create()));
        assertInstanceOf(ToolOutcome.Ok.class, outcome);
        assertFalse(store.query(new de.augmentia.oc.engine.core.model.MemoryQuery("Quarkus", null, null, null, 10)).isEmpty());
    }

    @Test
    void memoryQueryReturnsFacts() {
        MemoryStore store = new InMemoryMemoryStore();
        store.upsert(new de.augmentia.oc.engine.core.model.Fact("f1", "Quarkus", "version", "3.40",
                java.time.Instant.now(), null, null, java.util.List.of(), 1.0, 0));
        ToolDefinition def = MemoryQueryTool.definition(store, null);
        JsonValue input = JsonValue.of(java.util.Map.of("entity", JsonValue.of("Quarkus")));
        ToolOutcome outcome = def.execute().execute(input, new ToolContext(de.augmentia.oc.engine.core.model.SessionId.create(), de.augmentia.oc.engine.core.model.AgentId.create(), de.augmentia.oc.engine.core.model.MessageId.create(), de.augmentia.oc.engine.core.model.CallId.create()));
        assertInstanceOf(ToolOutcome.Ok.class, outcome);
    }

    @Test
    void memoryWriteRequiresEntity() {
        MemoryStore store = new InMemoryMemoryStore();
        ToolDefinition def = MemoryWriteTool.definition(store, null);
        JsonValue input = JsonValue.of(java.util.Map.of("relation", JsonValue.of("version"), "value", JsonValue.of("3.40")));
        assertThrows(Exception.class, () -> def.execute().execute(input, new ToolContext(de.augmentia.oc.engine.core.model.SessionId.create(), de.augmentia.oc.engine.core.model.AgentId.create(), de.augmentia.oc.engine.core.model.MessageId.create(), de.augmentia.oc.engine.core.model.CallId.create())));
    }
}