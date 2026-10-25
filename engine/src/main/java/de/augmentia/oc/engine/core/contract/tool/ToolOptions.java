package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.model.*;

/**
 * Tool execution and provider options.
 *
 * <p>{@code strict} asks the provider to enforce the tool input schema
 * (OpenAI: strict function calling). Strict tools are the portable way to get
 * schema-adherent structured output while other tools are available, because
 * providers often reject {@code response_format} together with tools.</p>
 */
public record ToolOptions(Option<String> namespace, Option<String> permission, boolean codemode,
                          boolean pinned, boolean strict) {
    public ToolOptions {
        if (namespace == null) namespace = Option.none();
        if (permission == null) permission = Option.none();
    }

    /** Backward-compatible constructor; strict is opt-in. */
    public ToolOptions(Option<String> namespace, Option<String> permission, boolean codemode,
                       boolean pinned) {
        this(namespace, permission, codemode, pinned, false);
    }

    public static ToolOptions defaults() {
        return new ToolOptions(Option.none(), Option.none(), true, false, false);
    }

    /** Returns a copy with strict function calling enabled or disabled. */
    public ToolOptions withStrict(boolean strict) {
        return new ToolOptions(namespace, permission, codemode, pinned, strict);
    }
}
