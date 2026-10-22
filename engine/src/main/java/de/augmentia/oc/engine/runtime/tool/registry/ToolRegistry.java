package de.augmentia.oc.engine.runtime.tool.registry;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.ToolName;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scoped tool registry. The newest valid registration for an effective name
 * wins; closing a registration re-exposes its predecessor (W-14). A snapshot
 * freezes the visible tools for exactly one model request (W-01).
 */
public final class ToolRegistry {

    private final Map<String, Deque<ScopedRegistration>> registrations = new LinkedHashMap<>();
    private long epoch;

    public synchronized Registration register(ToolDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("definition must not be null");
        String effectiveName = definition.effectiveName();
        ScopedRegistration registration = new ScopedRegistration(effectiveName, definition);
        registrations.computeIfAbsent(effectiveName, key -> new ArrayDeque<>()).addLast(registration);
        epoch++;
        return registration;
    }

    /** All currently active definitions, newest per effective name. */
    public synchronized List<ToolDefinition> list() {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (Deque<ScopedRegistration> scoped : registrations.values()) {
            ScopedRegistration newest = newest(scoped);
            if (newest != null) definitions.add(newest.definition);
        }
        return List.copyOf(definitions);
    }

    public synchronized ToolSnapshot snapshot() {
        return snapshot(List.of());
    }

    /**
     * Catalog visibility (level 1): a deny rule for the tool's action hides it.
     * A denied {@code execute} hides all tools that are reachable only through
     * Code Mode.
     */
    public synchronized ToolSnapshot snapshot(List<Rule> rules) {
        List<Rule> safeRules = rules == null ? List.of() : rules;
        boolean codeModeDenied = PermissionRules.evaluate(safeRules, "execute", "*") == Effect.DENY;

        Map<ToolName, ToolDefinition> visible = new LinkedHashMap<>();
        for (ToolDefinition definition : list()) {
            String action = definition.options().permission().orElse(definition.effectiveName());
            if (PermissionRules.evaluate(safeRules, action, "*") == Effect.DENY) {
                continue;
            }
            if (codeModeDenied && definition.options().codemode()) {
                continue;
            }
            visible.put(new ToolName(definition.effectiveName()), definition);
        }
        return new ToolSnapshot(visible);
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized int size() {
        return list().size();
    }

    /**
     * Rebuilds the visible list from the registered sources without changing
     * precedence. The registry is already authoritative, so this only refreshes
     * the epoch bookkeeping.
     */
    public synchronized List<ToolDefinition> reload() {
        return list();
    }

    private static ScopedRegistration newest(Deque<ScopedRegistration> scoped) {
        for (var iterator = scoped.descendingIterator(); iterator.hasNext(); ) {
            ScopedRegistration registration = iterator.next();
            if (registration.active) return registration;
        }
        return null;
    }

    private final class ScopedRegistration implements Registration {

        private final String effectiveName;
        private final ToolDefinition definition;
        private volatile boolean active = true;

        private ScopedRegistration(String effectiveName, ToolDefinition definition) {
            this.effectiveName = effectiveName;
            this.definition = definition;
        }

        @Override
        public ToolName name() {
            return new ToolName(effectiveName);
        }

        @Override
        public synchronized boolean active() {
            return active;
        }

        @Override
        public void close() {
            synchronized (ToolRegistry.this) {
                if (!active) return;
                active = false;
                Deque<ScopedRegistration> scoped = registrations.get(effectiveName);
                if (scoped != null) {
                    scoped.remove(this);
                    if (scoped.isEmpty()) registrations.remove(effectiveName);
                }
                epoch++;
            }
        }
    }
}
