package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.contract.permission.PermissionEvaluation;
import de.augmentia.oc.engine.core.contract.provider.ChannelMode;
import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.CompactionMode;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.error.PausedException;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.EventClass;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.human.ApprovalSource;
import de.augmentia.oc.engine.core.port.human.NotificationChannel;
import de.augmentia.oc.engine.runtime.execution.ExecutionOutcome;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MiscRecordsTest {

    @Test
    void pausedException() {
        PausedException e = new PausedException("aid", "url");
        assertThat(e.approvalId()).isEqualTo("aid");
        assertThat(e.approvalUrl()).isEqualTo("url");
    }

    @Test
    void approvalResultPaused() {
        ApprovalResult.Paused p = new ApprovalResult.Paused("aid", null);
        assertThat(p.approvalUrl()).isEmpty();
    }

    @Test
    void eventClassValues() {
        assertThat(EventClass.values()).contains(EventClass.DURABLE, EventClass.EPHEMERAL);
    }

    @Test
    void executionOutcomeFailed() {
        ExecutionOutcome.Failed f = new ExecutionOutcome.Failed(
                new de.augmentia.oc.engine.core.model.SessionError.ToolExecutionError("e", "boom"));
        assertThat(f.error().message()).contains("boom");
    }

    @Test
    void compactionModeNative() {
        CompactionMode.Native n = new CompactionMode.Native(true);
        assertThat(n.encrypted()).isTrue();
    }

    @Test
    void channelMode() {
        assertThat(ChannelMode.WEBSOCKET).isNotNull();
    }

    @Test
    void choiceFree() {
        assertThat(new Choice.Free()).isNotNull();
        assertThat(new Choice.None()).isNotNull();
        assertThat(new Choice.Required()).isNotNull();
    }

    @Test
    void choiceOne() {
        Choice.One o = new Choice.One(new ToolName("t"));
        assertThat(o.name().value()).isEqualTo("t");
    }

    @Test
    void modelEventRecords() {
        assertThat(new ModelEvent.LlmRequest("ep", "body").endpoint()).isEqualTo("ep");
        assertThat(new ModelEvent.LlmResponse("body").body()).isEqualTo("body");
        CallId id = CallId.create();
        assertThat(new ModelEvent.ToolFailed(id, "msg").message()).isEqualTo("msg");
        assertThat(new ModelEvent.ProviderFailed(new de.augmentia.oc.engine.core.contract.provider.FailureKind.InvalidOutput(
                de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification.UNKNOWN), "m", Option.none(), Option.none()).message()).isEqualTo("m");
    }

    @Test
    void hooksDefault() {
        Hooks h = new Hooks() {
            public ToolCall beforeToolCall(ToolContext c, ToolCall call) { return call; }
            public ToolResult afterToolCall(ToolContext c, ToolResult r) { return r; }
        };
        PermissionEvaluation pe = new PermissionEvaluation(
                de.augmentia.oc.engine.core.model.SessionId.create(),
                de.augmentia.oc.engine.core.model.Option.none(),
                "read", java.util.List.of(), java.util.Map.of(),
                de.augmentia.oc.engine.core.model.Effect.ALLOW);
        assertThat(h.evaluatePermission(pe)).isEqualTo(pe);
    }

    @Test
    void notificationChannelDefault() {
        // default sendQuestion delegates to send; use a capturing impl
        class N implements NotificationChannel {
            String lastAction;
            public void send(de.augmentia.oc.engine.core.model.PermissionRequestId r, de.augmentia.oc.engine.core.model.SessionId s, String action, String ctx, String url) {
                lastAction = action;
            }
        }
        N n = new N();
        n.sendQuestion("qid", null, "q", "url");
        assertThat(n.lastAction).isEqualTo("question");
    }

    @Test
    void approvalSourceDefault() {
        ApprovalSource src = (rid, sid, action, ctx) -> new ApprovalResult.Approved();
        ApprovalResult r = src.requestAsync(null, null, "a", "c", "url");
        assertThat(r).isInstanceOf(ApprovalResult.Approved.class);
    }
}
