package de.augmentia.oc.engine.core.contract.tool;

import java.util.function.Consumer;

/**
 * Core-owned callback for host-provided tool definitions.
 *
 * <p>The runtime supplies a sink; the host emits zero or more definitions.
 * This keeps {@code core.wiring.ToolDependencies} free of runtime types such as
 * {@code ToolRegistry}.</p>
 */
@FunctionalInterface
public interface ToolRegistrar {

    /**
     * Emits tool definitions into the supplied sink.
     *
     * @param sink receiver for each definition; never {@code null}
     */
    void register(Consumer<ToolDefinition> sink);
}
