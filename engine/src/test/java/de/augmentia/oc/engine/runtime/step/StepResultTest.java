package de.augmentia.oc.engine.runtime.step;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolName;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepResultTest {

    @Test
    void finishedDefaults() {
        StepResult.Finished f = new StepResult.Finished(null, null, null, null);
        assertThat(f.reason()).isEqualTo(FinishReason.UNKNOWN);
        assertThat(f.usage()).isEqualTo(TokenCount.zero());
        assertThat(f.events()).isEmpty();
        assertThat(f.toolCalls()).isEmpty();
    }

    @Test
    void failedDefaults() {
        StepResult.Failed f = new StepResult.Failed(null, null);
        assertThat(f.kind()).isNotNull();
        assertThat(f.message()).isEmpty();
    }

    @Test
    void pausedValidation() {
        StepResult.Paused p = new StepResult.Paused("aid", null);
        assertThat(p.approvalId()).isEqualTo("aid");
        assertThat(p.approvalUrl()).isEmpty();
    }

    @Test
    void toolCallOutcomeValidation() {
        ToolCall call = new ToolCall(CallId.create(), new ToolName("t"), JsonValue.ofNull());
        ToolOutcome outcome = new ToolOutcome.Ok(ToolResult.of("ok"));
        StepResult.ToolCallOutcome o = new StepResult.ToolCallOutcome(call, outcome);
        assertThat(o.call()).isEqualTo(call);
    }

    @Test
    void needsCompactionInstance() {
        assertThat(new StepResult.NeedsCompaction()).isNotNull();
    }

    @Test
    void submittedResultIsSubmit() {
        ToolCall call = new ToolCall(CallId.create(), new ToolName("submit_result"), JsonValue.ofNull());
        ToolOutcome outcome = new ToolOutcome.Ok(ToolResult.of("ok"));
        StepResult.ToolCallOutcome o = new StepResult.ToolCallOutcome(call, outcome);
        assertThat(SubmittedResult.isSubmit(o)).isTrue();
    }

    @Test
    void submittedResultNotSubmitForOther() {
        ToolCall call = new ToolCall(CallId.create(), new ToolName("other"), JsonValue.ofNull());
        ToolOutcome outcome = new ToolOutcome.Ok(ToolResult.of("ok"));
        StepResult.ToolCallOutcome o = new StepResult.ToolCallOutcome(call, outcome);
        assertThat(SubmittedResult.isSubmit(o)).isFalse();
    }

    @Test
    void submittedResultNullSafe() {
        assertThat(SubmittedResult.isSubmit(null)).isFalse();
        assertThat(SubmittedResult.result(null, Schema.str())).isEqualTo(Option.none());
    }
}
