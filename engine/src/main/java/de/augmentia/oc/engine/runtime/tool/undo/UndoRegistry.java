package de.augmentia.oc.engine.runtime.tool.undo;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.UndoSpec;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UndoRegistry {

    private final Map<ToolName, UndoSpec> specs = new LinkedHashMap<>();

    public synchronized void register(ToolDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("definition must not be null");
        definition.options().undo().ifPresent(spec ->
                specs.put(new ToolName(definition.effectiveName()), spec));
    }

    public synchronized void rebuildFrom(ToolRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        specs.clear();
        for (ToolDefinition definition : registry.list()) {
            register(definition);
        }
    }

    public synchronized Option<UndoSpec> specOf(ToolName name) {
        return Option.of(specs.get(name));
    }

    public synchronized boolean hasUndo(ToolName name) {
        return specs.containsKey(name);
    }

    public synchronized Map<ToolName, UndoSpec> snapshot() {
        return Map.copyOf(specs);
    }

    public synchronized int size() {
        return specs.size();
    }
}
