package de.augmentia.oc.engine.runtime.step;

import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.runtime.tool.structured.SubmitResult;

/**
 * Extracts the structured answer from a completed step. This lives next to
 * {@link StepResult} so the tool package does not depend on the step package.
 */
public final class SubmittedResult {

    private SubmittedResult() {}

    public static boolean isSubmit(StepResult.ToolCallOutcome outcome) {
        return outcome != null && SubmitResult.TOOL_NAME.equals(outcome.call().name().value());
    }

    /**
     * Returns the normalized arguments of the first successful submit call.
     * Failed calls are ignored so the model can retry after a validation error.
     */
    public static Option<JsonValue> result(StepResult.Finished finished, Schema schema) {
        if (finished == null || schema == null) return Option.none();
        for (StepResult.ToolCallOutcome outcome : finished.toolCalls()) {
            if (!isSubmit(outcome)) continue;
            if (!(outcome.outcome() instanceof ToolOutcome.Ok ok)) continue;
            ToolCall call = outcome.call();
            JsonValue value = ok.result().output().orElse(call.input());
            Result<JsonValue> decoded = Json.decode(value, schema);
            if (decoded instanceof Result.Ok<JsonValue> okValue) {
                return Option.some(okValue.value());
            }
            return Option.none();
        }
        return Option.none();
    }
}
