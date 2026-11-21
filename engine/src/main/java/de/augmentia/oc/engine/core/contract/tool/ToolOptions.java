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
 *
 * <p>{@code undo} declares the compensating action for this tool (Saga
 * pattern). It is absent for tools without a meaningful inverse.</p>
 */
public record ToolOptions(Option<String> namespace, Option<String> permission, boolean codemode,
                          boolean pinned, boolean strict,
                          Option<java.util.function.Function<JsonValue, String>> approvalMessage,
                          Option<UndoSpec> undo) {
    public ToolOptions {
        if (namespace == null) namespace = Option.none();
        if (permission == null) permission = Option.none();
        if (approvalMessage == null) approvalMessage = Option.none();
        if (undo == null) undo = Option.none();
    }

    /** Backward-compatible constructor; strict is opt-in, no approval message. */
    public ToolOptions(Option<String> namespace, Option<String> permission, boolean codemode,
                       boolean pinned) {
        this(namespace, permission, codemode, pinned, false, Option.none(), Option.none());
    }

    /** Backward-compatible constructor; strict is opt-in. */
    public ToolOptions(Option<String> namespace, Option<String> permission, boolean codemode,
                       boolean pinned, boolean strict) {
        this(namespace, permission, codemode, pinned, strict, Option.none(), Option.none());
    }

    /** Backward-compatible constructor with an approval-message function. */
    public ToolOptions(Option<String> namespace, Option<String> permission, boolean codemode,
                       boolean pinned, boolean strict,
                       java.util.function.Function<JsonValue, String> approvalMessage) {
        this(namespace, permission, codemode, pinned, strict,
                approvalMessage == null ? Option.none() : Option.some(approvalMessage),
                Option.none());
    }

    public static ToolOptions defaults() {
        return new ToolOptions(Option.none(), Option.none(), true, false, false,
                Option.none(), Option.none());
    }

    /** Returns a copy with strict function calling enabled or disabled. */
    public ToolOptions withStrict(boolean strict) {
        return new ToolOptions(namespace, permission, codemode, pinned, strict, approvalMessage, undo);
    }

    /** Returns a copy with an approval-message function. */
    public ToolOptions withApprovalMessage(java.util.function.Function<JsonValue, String> fn) {
        return new ToolOptions(namespace, permission, codemode, pinned, strict,
                fn == null ? Option.none() : Option.some(fn), undo);
    }

    /** Returns a copy with a compensating action declared. */
    public ToolOptions withUndo(UndoSpec spec) {
        return new ToolOptions(namespace, permission, codemode, pinned, strict, approvalMessage,
                spec == null ? Option.none() : Option.some(spec));
    }
}
