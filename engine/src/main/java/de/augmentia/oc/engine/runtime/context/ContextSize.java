package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.eval.TokenEstimator;
import de.augmentia.oc.engine.core.model.ToolContent;

/**
 * Provider-independent estimate of the context that one prepared request sends
 * to the model. Used for compaction diagnostics and end-to-end checks.
 */
public final class ContextSize {

    private static final int MESSAGE_OVERHEAD = 4;
    private static final int TOOL_OVERHEAD = 8;

    private ContextSize() {}

    public static int estimate(Prepared prepared) {
        if (prepared == null) return 0;
        int tokens = 0;
        for (String system : prepared.system()) {
            tokens += MESSAGE_OVERHEAD + TokenEstimator.estimate(system);
        }
        for (ModelMessage message : prepared.messages()) {
            tokens += estimate(message);
        }
        for (ToolDefinition tool : prepared.tools()) {
            tokens += TOOL_OVERHEAD
                    + TokenEstimator.estimate(tool.effectiveName())
                    + TokenEstimator.estimate(tool.description())
                    + TokenEstimator.estimate(String.valueOf(tool.inputSchema()));
        }
        return tokens;
    }

    public static int estimate(ModelMessage message) {
        if (message == null) return 0;
        return switch (message) {
            case ModelMessage.User user -> MESSAGE_OVERHEAD + content(user.content());
            case ModelMessage.Assistant assistant -> MESSAGE_OVERHEAD + content(assistant.content());
            case ModelMessage.ToolResult result -> MESSAGE_OVERHEAD
                    + TokenEstimator.estimate(result.name().value())
                    + estimate(result.outcome());
        };
    }

    private static int content(java.util.List<ModelContent> content) {
        int tokens = 0;
        for (ModelContent item : content) {
            tokens += switch (item) {
                case ModelContent.Text text -> TokenEstimator.estimate(text.text());
                case ModelContent.Media media -> Math.max(1, media.data().length / 4);
                case ModelContent.ToolCall call -> TokenEstimator.estimate(call.name().value())
                        + TokenEstimator.estimate(String.valueOf(call.input()));
            };
        }
        return tokens;
    }

    private static int estimate(OutcomePayload outcome) {
        return switch (outcome) {
            case OutcomePayload.Text text -> TokenEstimator.estimate(text.text());
            case OutcomePayload.Json json -> TokenEstimator.estimate(String.valueOf(json.value()));
            case OutcomePayload.Content content -> {
                int tokens = 0;
                for (ToolContent item : content.items()) {
                    if (item instanceof ToolContent.TextItem text) {
                        tokens += TokenEstimator.estimate(text.text());
                    }
                }
                yield tokens;
            }
        };
    }
}
