package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.json.JsonValue;
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
                          boolean pinned, boolean strict,
                          Option<java.util.function.Function<JsonValue, String>> approvalMessage) {
    public ToolOptions {
        if (namespace == null) namespace = Option.none();
        if (permission == null) permission = Option.none();
        if (approvalMessage == null) approvalMessage = Option.none();
    }

    /** Backward-compatible constructor; strict is opt-in, no approval message. */
    public ToolOptions(Option<String> namespace, Option<String> permission, boolean codemode,
                       boolean pinned) {
        this(namespace, permission, codemode, pinned, false, Option.none());
    }

    /** Backward-compatible constructor; strict is opt-in. */
    public ToolOptions(Option<String> namespace, Option<String> permission, boolean codemode,
                       boolean pinned, boolean strict) {
        this(namespace, permission, codemode, pinned, strict, Option.none());
    }

    public static ToolOptions defaults() {
        return new ToolOptions(Option.none(), Option.none(), true, false, false, Option.none());
    }

    /** Returns a copy with strict function calling enabled or disabled. */
    public ToolOptions withStrict(boolean strict) {
        return new ToolOptions(namespace, permission, codemode, pinned, strict, approvalMessage);
    }

    /** Returns a copy with an approval-message function. */
    public ToolOptions withApprovalMessage(java.util.function.Function<JsonValue, String> fn) {
        return new ToolOptions(namespace, permission, codemode, pinned, strict,
                fn == null ? Option.none() : Option.some(fn));
    }
}
