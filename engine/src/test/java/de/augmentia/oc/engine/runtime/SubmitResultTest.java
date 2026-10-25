package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.step.StepResult;
import de.augmentia.oc.engine.runtime.step.SubmittedResult;
import de.augmentia.oc.engine.runtime.tool.structured.SubmitResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmitResultTest {

    private static final Schema SCHEMA = Schema.obj(Map.of(
            "answer", Schema.str(),
            "note", Schema.opt(Schema.str())));

    @Test
    void definitionIsStrictAndUsesReservedName() {
        ToolDefinition definition = SubmitResult.definition(SCHEMA);
        assertThat(definition.effectiveName()).isEqualTo(SubmitResult.TOOL_NAME);
        assertThat(definition.options().strict()).isTrue();
        assertThat(definition.inputSchema()).isSameAs(SCHEMA);
    }

    @Test
    void withToolAddsTheResultTool() {
        ToolSnapshot snapshot = SubmitResult.withTool(ToolSnapshot.empty(), SCHEMA);
        assertThat(snapshot.list()).extracting(ToolDefinition::effectiveName)
                .containsExactly(SubmitResult.TOOL_NAME);
    }

    @Test
    void reservedNameCollisionIsRejected() {
        ToolDefinition existing = SubmitResult.definition(Schema.obj(Map.of()));
        ToolSnapshot base = new ToolSnapshot(Map.of(new ToolName(SubmitResult.TOOL_NAME), existing));
        assertThatThrownBy(() -> SubmitResult.withTool(base, SCHEMA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void successfulResultIsNormalized() {
        JsonValue input = JsonValue.of(Map.of(
                "answer", JsonValue.of("done"),
                "note", JsonValue.ofNull()));
        StepResult.Finished finished = finished("submit_result", input, ok(input));

        Option<JsonValue> result = SubmittedResult.result(finished, SCHEMA);

        assertThat(result.isPresent()).isTrue();
        JsonValue.Obj object = (JsonValue.Obj) result.orElseThrow();
        assertThat(object.values()).containsEntry("answer", JsonValue.of("done"));
        assertThat(object.values()).doesNotContainKey("note");
    }

    @Test
    void failedCallIsIgnored() {
        JsonValue input = JsonValue.of(Map.of("answer", JsonValue.of("done")));
        StepResult.Finished finished = finished("submit_result", input,
                new ToolOutcome.Failed(new SessionError.ToolExecutionError("bad", "bad")));

        assertThat(SubmittedResult.result(finished, SCHEMA).isEmpty()).isTrue();
    }

    @Test
    void foreignToolIsIgnored() {
        JsonValue input = JsonValue.of(Map.of("answer", JsonValue.of("done")));
        StepResult.Finished finished = finished("other", input, ok(input));

        assertThat(SubmittedResult.result(finished, SCHEMA).isEmpty()).isTrue();
        assertThat(SubmittedResult.isSubmit(finished.toolCalls().getFirst())).isFalse();
    }

    @Test
    void invalidResultIsIgnored() {
        JsonValue input = JsonValue.of(Map.of("answer", JsonValue.of(42)));
        StepResult.Finished finished = finished("submit_result", input, ok(input));

        assertThat(SubmittedResult.result(finished, SCHEMA).isEmpty()).isTrue();
    }

    @Test
    void nullInputsYieldNoResult() {
        assertThat(SubmittedResult.result(null, SCHEMA).isEmpty()).isTrue();
        assertThat(SubmittedResult.result(finished("submit_result", JsonValue.ofNull(),
                new ToolOutcome.Ok(ToolResult.of("ok"))), null).isEmpty()).isTrue();
        assertThat(SubmittedResult.isSubmit(null)).isFalse();
    }

    private static StepResult.Finished finished(String tool, JsonValue input, ToolOutcome outcome) {
        ToolCall call = new ToolCall(CallId.create(), new ToolName(tool), input);
        return new StepResult.Finished(FinishReason.NORMAL, TokenCount.zero(), List.of(),
                List.of(new StepResult.ToolCallOutcome(call, outcome)));
    }

    private static ToolOutcome ok(JsonValue output) {
        return new ToolOutcome.Ok(new ToolResult(Option.some(output), List.of(), Map.of()));
    }
}
