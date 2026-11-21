package de.augmentia.oc.engine.runtime.tool.undo;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolExecutor;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.UndoSpec;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;

import java.util.Map;

public final class UndoToolFactory {

    private UndoToolFactory() {}

    /** Effective name of the generated undo tool for a forward tool. */
    public static String undoToolName(String forwardEffectiveName) {
        if (forwardEffectiveName == null || forwardEffectiveName.isBlank()) {
            throw new IllegalArgumentException("forwardEffectiveName must not be blank");
        }
        return "undo_" + forwardEffectiveName;
    }

    public static ToolDefinition create(ToolDefinition forward, UndoSpec spec, UndoRuntime runtime) {
        if (forward == null || spec == null || runtime == null) {
            throw new IllegalArgumentException("forward tool, spec and runtime must not be null");
        }
        String effective = forward.effectiveName();
        ToolName undoName = new ToolName(undoToolName(effective));
        Schema input = Schema.obj(Map.of(
                "callId", Schema.str(),
                "sessionId", Schema.opt(Schema.str())));
        ToolExecutor executor = (value, context) -> UndoOutcomeMapper.toToolOutcome(
                runtime.executeUndo(value, context, forward, spec), effective);
        ToolOptions options = new ToolOptions(
                Option.none(),
                Option.of("undo_" + normalize(effective)),
                forward.options().codemode(),
                false, false, Option.none(), Option.none());
        return new ToolDefinition(undoName,
                "Undo a prior " + effective + " call. Pass the original callId.",
                input, Option.none(), executor, options);
    }

    private static String normalize(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
