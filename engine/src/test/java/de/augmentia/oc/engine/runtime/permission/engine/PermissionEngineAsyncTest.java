package de.augmentia.oc.engine.runtime.permission.engine;

import de.augmentia.oc.engine.core.contract.permission.AskMode;
import de.augmentia.oc.engine.core.contract.permission.PermissionDenied;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.permission.Reply;
import de.augmentia.oc.engine.core.error.Aborted;
import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.error.PausedException;
import de.augmentia.oc.engine.core.eval.PermissionCheck;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.testing.ScriptedApproval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers the async assert path and the ask-mode resolution of the permission engine. */
class PermissionEngineAsyncTest {

    private static final AgentId AGENT = AgentId.create();
    private static final SessionId SESSION = SessionId.create();
    private static final ProjectId PROJECT = ProjectId.create();

    @Test
    void constructorToleratesNullOptionsAndAskMode() {
        PermissionEngine engine = new PermissionEngine(null, null, null, null, null);
        assertThatCode(() -> engine.assertAllowed(allowed())).doesNotThrowAnyException();
        assertThat(engine.pending().count()).isZero();
    }

    @Test
    void registerAgentAskModeIgnoresNullArguments() {
        PermissionEngine engine = new PermissionEngine(null, null, null, null);
        engine.registerAgentAskMode(null, AskMode.DENY);
        engine.registerAgentAskMode(AGENT, null);
        // No override registered, no approval source: an ask is a defect.
        assertThatThrownBy(() -> engine.assertAllowed(asked())).isInstanceOf(Defect.class);
    }

    @Test
    void asyncAllowedAndDeniedDecisions() {
        PermissionEngine engine = new PermissionEngine(null, null, null, null);
        assertThatCode(() -> engine.assertAllowedAsync(allowed())).doesNotThrowAnyException();
        assertThatThrownBy(() -> engine.assertAllowedAsync(denied())).isInstanceOf(PermissionDenied.class);
    }

    @Test
    void asyncAskHonoursAllowAndDenyModes() {
        PermissionEngine allow = new PermissionEngine(null, null, null, null, AskMode.ALLOW);
        assertThatCode(() -> allow.assertAllowedAsync(asked())).doesNotThrowAnyException();

        PermissionEngine deny = new PermissionEngine(null, null, null, null, AskMode.DENY);
        assertThatThrownBy(() -> deny.assertAllowedAsync(asked()))
                .isInstanceOf(PermissionDenied.class)
                .hasMessageContaining("auto-denied");
    }

    @Test
    void agentOverrideTakesPrecedenceOverConstructorMode() {
        PermissionEngine engine = new PermissionEngine(null, null, null, null, AskMode.ALLOW);
        engine.registerAgentAskMode(AGENT, AskMode.DENY);
        assertThatThrownBy(() -> engine.assertAllowedAsync(asked()))
                .isInstanceOf(PermissionDenied.class);

        PermissionEngine second = new PermissionEngine(null, null, null, null, AskMode.DENY);
        second.registerAgentAskMode(AGENT, AskMode.ALLOW);
        assertThatCode(() -> second.assertAllowedAsync(asked())).doesNotThrowAnyException();
    }

    @Test
    void agentWithoutOverrideFallsBackToConstructorMode() {
        PermissionEngine engine = new PermissionEngine(null, null, null, null, AskMode.DENY);
        assertThatThrownBy(() -> engine.assertAllowedAsync(asked())).isInstanceOf(PermissionDenied.class);
    }

    @Test
    void asyncAskWithoutSourceIsDefect() {
        PermissionEngine engine = new PermissionEngine(null, null, null, null);
        assertThatThrownBy(() -> engine.assertAllowedAsync(asked())).isInstanceOf(Defect.class);
    }

    @Test
    void asyncAskConsumesApprovalAndConvertsRejections() {
        assertThatCode(() -> engine(new ApprovalResult.Approved()).assertAllowedAsync(asked()))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> engine(new ApprovalResult.Denied(Option.some("nope")))
                .assertAllowedAsync(asked()))
                .isInstanceOf(PermissionDenied.class)
                .hasMessage("nope");

        assertThatThrownBy(() -> engine(new ApprovalResult.Denied(Option.none()))
                .assertAllowedAsync(asked()))
                .isInstanceOf(Aborted.class);
    }

    @Test
    void pausedApprovalIsReportedForSyncAndAsync() {
        PausedException async = (PausedException) org.assertj.core.api.Assertions.catchThrowable(() ->
                engine(new ApprovalResult.Paused("ap-1", "https://example.test/approve"))
                        .assertAllowedAsync(asked()));
        assertThat(async.approvalId()).isEqualTo("ap-1");
        assertThat(async.approvalUrl()).isEqualTo("https://example.test/approve");

        PausedException sync = (PausedException) org.assertj.core.api.Assertions.catchThrowable(() ->
                engine(new ApprovalResult.Paused("ap-2", "u")).assertAllowed(asked()));
        assertThat(sync.approvalId()).isEqualTo("ap-2");
    }

    @Test
    void syncAskHonoursAllowAndDenyModes() {
        PermissionEngine allow = new PermissionEngine(null, null, null, null, AskMode.ALLOW);
        assertThatCode(() -> allow.assertAllowed(asked())).doesNotThrowAnyException();

        PermissionEngine deny = new PermissionEngine(null, null, null, null, AskMode.DENY);
        assertThatThrownBy(() -> deny.assertAllowed(asked())).isInstanceOf(PermissionDenied.class);
    }

    @Test
    void longContextIsTruncatedBeforeApproval() {
        PermissionRequest request = new PermissionRequest(PermissionRequestId.create(), SESSION, "shell",
                List.of("r".repeat(800)), List.of(), Map.of(), null, null);
        PermissionCheck check = new PermissionCheck(request, Option.some(AGENT),
                List.of(new Rule("*", "*", Effect.ASK)), List.of(), List.of(), List.of());

        assertThatCode(() -> engine(new ApprovalResult.Approved()).assertAllowed(check))
                .doesNotThrowAnyException();
    }

    @Test
    void replyAcceptsNullFeedbackAndNullFeedbackOption() {
        PermissionEngine engine = new PermissionEngine(null, null, null, null);
        PermissionRequest request = new PermissionRequest(PermissionRequestId.create(), SESSION,
                "read", List.of("a.txt"), List.of(), Map.of(), null, null);
        engine.pending().add(request);

        assertThat(engine.reply(SESSION, request.id(), Reply.ONCE, null, PROJECT).isPresent()).isTrue();

        PermissionRequest second = new PermissionRequest(PermissionRequestId.create(), SESSION,
                "read", List.of("b.txt"), List.of(), Map.of(), null, null);
        engine.pending().add(second);
        assertThat(engine.reply(SESSION, second.id(), Reply.REJECT, null, PROJECT).isPresent()).isTrue();
    }

    @Test
    void replyRejectsNullReplyAndProject() {
        PermissionEngine engine = new PermissionEngine(null, null, null, null);
        assertThatThrownBy(() -> engine.reply(SESSION, PermissionRequestId.create(), null,
                Option.none(), PROJECT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.reply(SESSION, PermissionRequestId.create(), Reply.ONCE,
                Option.none(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static PermissionEngine engine(ApprovalResult answer) {
        return new PermissionEngine(Option.none(), Option.some(new ScriptedApproval(answer)),
                Option.none(), Option.none());
    }

    private static PermissionCheck asked() {
        return new PermissionCheck(request(), Option.some(AGENT),
                List.of(new Rule("*", "*", Effect.ASK)), List.of(), List.of(), List.of());
    }

    private static PermissionCheck allowed() {
        return new PermissionCheck(request(), Option.some(AGENT),
                List.of(new Rule("*", "*", Effect.ALLOW)), List.of(), List.of(), List.of());
    }

    private static PermissionCheck denied() {
        return new PermissionCheck(request(), Option.some(AGENT),
                List.of(new Rule("*", "*", Effect.DENY)), List.of(), List.of(), List.of());
    }

    private static PermissionRequest request() {
        return new PermissionRequest(PermissionRequestId.create(), SESSION, "read",
                List.of("a.txt"), List.of(), Map.of(), null, null);
    }
}
