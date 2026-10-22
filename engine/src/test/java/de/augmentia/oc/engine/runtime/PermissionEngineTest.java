package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.permission.PermissionDenied;
import de.augmentia.oc.engine.core.contract.permission.PermissionEvaluation;
import de.augmentia.oc.engine.core.contract.permission.PermissionHook;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.permission.Reply;
import de.augmentia.oc.engine.core.error.Aborted;
import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.eval.PermissionCheck;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.runtime.permission.engine.PermissionEngine;
import de.augmentia.oc.engine.runtime.permission.engine.ReplyOutcome;
import de.augmentia.oc.engine.testing.EmptyPolicy;
import de.augmentia.oc.engine.testing.FixedPolicy;
import de.augmentia.oc.engine.testing.InMemoryApprovals;
import de.augmentia.oc.engine.testing.ScriptedApproval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionEngineTest {

    private static final AgentId AGENT = AgentId.create();
    private static final SessionId SESSION = SessionId.create();
    private static final ProjectId PROJECT = ProjectId.create();

    @Test
    void denyIsDecidedBeforeApprovalIsRequested() {
        ScriptedApproval source = new ScriptedApproval(); // would fail if called
        PermissionEngine engine = engine(source);
        PermissionCheck check = check(List.of(new Rule("*", "*", Effect.DENY)), request("read", "a.txt"));

        assertThatThrownBy(() -> engine.assertAllowed(check))
                .isInstanceOf(PermissionDenied.class);
        assertThat(source.actions()).isEmpty();
    }

    @Test
    void approvedAskPasses() {
        PermissionEngine engine = engine(new ScriptedApproval(new ApprovalResult.Approved()));
        engine.assertAllowed(check(List.of(new Rule("*", "*", Effect.ALLOW)), request("read", "a.txt")));
        assertThat(engine.pending().count()).isZero();
    }

    @Test
    void askWithApprovalSourceAsksForTheAction() {
        ScriptedApproval source = new ScriptedApproval(new ApprovalResult.Approved());
        PermissionEngine engine = engine(source);
        engine.assertAllowed(check(List.of(new Rule("*", "*", Effect.ASK)), request("read", "a.txt")));
        assertThat(source.actions()).containsExactly("read");
    }

    @Test
    void rejectionWithoutFeedbackIsAnAbort() {
        PermissionEngine engine = engine(new ScriptedApproval(new ApprovalResult.Denied(Option.none())));
        assertThatThrownBy(() -> engine.assertAllowed(
                check(List.of(new Rule("*", "*", Effect.ASK)), request("read", "a.txt"))))
                .isInstanceOf(Aborted.class);
    }

    @Test
    void rejectionWithFeedbackIsModelFacing() {
        PermissionEngine engine = engine(new ScriptedApproval(
                new ApprovalResult.Denied(Option.some("use the project script"))));
        assertThatThrownBy(() -> engine.assertAllowed(
                check(List.of(new Rule("*", "*", Effect.ASK)), request("read", "a.txt"))))
                .isInstanceOf(PermissionDenied.class)
                .hasMessage("use the project script");
    }

    @Test
    void askWithoutApprovalSourceIsADefect() {
        PermissionEngine engine = new PermissionEngine(Option.none(), Option.none(),
                Option.none(), Option.none());
        assertThatThrownBy(() -> engine.assertAllowed(
                check(List.of(new Rule("*", "*", Effect.ASK)), request("read", "a.txt"))))
                .isInstanceOf(Defect.class);
    }

    @Test
    void policySourceIsAppliedWhenTheCheckCarriesNone() {
        PermissionEngine engine = new PermissionEngine(Option.none(), Option.none(),
                Option.some(new FixedPolicy(List.of(PolicyStatement.deny("tool.use", "read:*")))),
                Option.none());
        assertThatThrownBy(() -> engine.assertAllowed(
                check(List.of(new Rule("*", "*", Effect.ALLOW)), request("read", "a.txt"))))
                .isInstanceOf(PermissionDenied.class);
    }

    @Test
    void emptyPolicyDoesNotChangeTheDecision() {
        PermissionEngine engine = new PermissionEngine(Option.none(), Option.none(),
                Option.some(new EmptyPolicy()), Option.none());
        engine.assertAllowed(check(List.of(new Rule("*", "*", Effect.ALLOW)), request("read", "a.txt")));
    }

    @Test
    void hookCanChangeTheDecision() {
        PermissionHook hook = evaluation -> new Result.Ok<>(new PermissionEvaluation(
                evaluation.sessionId(), evaluation.agent(), evaluation.action(), evaluation.resources(),
                evaluation.metadata(), Effect.DENY, Option.some("blocked")));
        PermissionEngine engine = new PermissionEngine(Option.none(), Option.none(), Option.none(),
                Option.some(hook));
        assertThatThrownBy(() -> engine.assertAllowed(
                check(List.of(new Rule("*", "*", Effect.ALLOW)), request("read", "a.txt"))))
                .isInstanceOf(PermissionDenied.class)
                .hasMessage("blocked");
    }

    @Test
    void onceRemovesTheRequestAndStoresNothing() {
        InMemoryApprovals approvals = new InMemoryApprovals();
        PermissionEngine engine = new PermissionEngine(Option.some(approvals), Option.none(),
                Option.none(), Option.none());
        PermissionRequest request = request("read", "a.txt");
        engine.pending().add(request);

        Option<ReplyOutcome> outcome = engine.reply(SESSION, request.id(), Reply.ONCE, Option.none(), PROJECT);

        assertThat(outcome.orElseThrow()).isInstanceOf(ReplyOutcome.Once.class);
        assertThat(engine.pending().count()).isZero();
        assertThat(approvals.list(PROJECT)).isEmpty();
    }

    @Test
    void alwaysStoresTheSuggestedPatterns() {
        InMemoryApprovals approvals = new InMemoryApprovals();
        PermissionEngine engine = new PermissionEngine(Option.some(approvals), Option.none(),
                Option.none(), Option.none());
        PermissionRequest request = request("shell", "git status", List.of("git status *"));
        engine.pending().add(request);

        ReplyOutcome.Always outcome = (ReplyOutcome.Always) engine.reply(
                SESSION, request.id(), Reply.ALWAYS, Option.none(), PROJECT).orElseThrow();

        assertThat(outcome.savedPatterns()).containsExactly("git status *");
        assertThat(approvals.list(PROJECT)).hasSize(1);
        assertThat(approvals.list(PROJECT).getFirst().resource()).isEqualTo("git status *");
    }

    @Test
    void alwaysWithoutSavePatternStoresNothing() {
        InMemoryApprovals approvals = new InMemoryApprovals();
        PermissionEngine engine = new PermissionEngine(Option.some(approvals), Option.none(),
                Option.none(), Option.none());
        PermissionRequest request = request("read", "a.txt");
        engine.pending().add(request);

        ReplyOutcome.Always outcome = (ReplyOutcome.Always) engine.reply(
                SESSION, request.id(), Reply.ALWAYS, Option.none(), PROJECT).orElseThrow();

        assertThat(outcome.savedPatterns()).isEmpty();
        assertThat(approvals.list(PROJECT)).isEmpty();
    }

    @Test
    void rejectRemovesAllOpenRequestsOfTheSession() {
        PermissionEngine engine = new PermissionEngine(Option.none(), Option.none(),
                Option.none(), Option.none());
        PermissionRequest first = request("read", "a.txt");
        PermissionRequest second = request("read", "b.txt");
        PermissionRequest otherSession = new PermissionRequest(PermissionRequestId.create(),
                SessionId.create(), "read", List.of("c.txt"), List.of(), Map.of(), null, null);
        engine.pending().add(first);
        engine.pending().add(second);
        engine.pending().add(otherSession);

        ReplyOutcome.Reject outcome = (ReplyOutcome.Reject) engine.reply(
                SESSION, first.id(), Reply.REJECT, Option.some("no"), PROJECT).orElseThrow();

        assertThat(outcome.requests()).containsExactlyInAnyOrder(first, second);
        assertThat(outcome.feedback().orElse("")).isEqualTo("no");
        assertThat(engine.pending().list(SESSION)).isEmpty();
        assertThat(engine.pending().list(otherSession.sessionId())).hasSize(1);
    }

    @Test
    void unknownReplyIsEmpty() {
        PermissionEngine engine = new PermissionEngine(Option.none(), Option.none(),
                Option.none(), Option.none());
        assertThat(engine.reply(SESSION, PermissionRequestId.create(), Reply.ONCE,
                Option.none(), PROJECT).isEmpty()).isTrue();
    }

    @Test
    void rejectionHelperUsesTheRightChannel() {
        PermissionRequest request = request("read", "a.txt");
        assertThat(PermissionEngine.rejection(request, Option.none())).isInstanceOf(Aborted.class);
        assertThat(PermissionEngine.rejection(request, Option.some("no")))
                .isInstanceOf(PermissionDenied.class)
                .hasMessage("no");
    }

    @Test
    void alwaysReleasesOtherRequestsThatBecomeAllowed() {
        InMemoryApprovals approvals = new InMemoryApprovals();
        PermissionEngine engine = new PermissionEngine(Option.some(approvals), Option.none(),
                Option.none(), Option.none());

        PermissionRequest first = request("read", "a.txt", List.of("*"));
        PermissionCheck firstCheck = check(List.of(new Rule("read", "*", Effect.ASK)), first);
        PermissionCheck secondCheck = check(List.of(new Rule("read", "b.txt", Effect.ASK)),
                request("read", "b.txt"));
        engine.pending().add(firstCheck);
        engine.pending().add(secondCheck);

        ReplyOutcome.Always outcome = (ReplyOutcome.Always) engine.reply(
                SESSION, first.id(), Reply.ALWAYS, Option.none(), PROJECT).orElseThrow();

        assertThat(outcome.savedPatterns()).containsExactly("*");
        assertThat(outcome.released()).extracting(PermissionRequest::id)
                .containsExactly(secondCheck.request().id());
        assertThat(engine.pending().count()).isZero();
    }

    @Test
    void alwaysDoesNotReleaseRequestsThatStayDenied() {
        InMemoryApprovals approvals = new InMemoryApprovals();
        PermissionEngine engine = new PermissionEngine(Option.some(approvals), Option.none(),
                Option.none(), Option.none());

        PermissionRequest first = request("read", "a.txt", List.of("*"));
        engine.pending().add(check(List.of(new Rule("read", "*", Effect.ASK)), first));
        PermissionCheck denied = check(List.of(new Rule("read", "*.env", Effect.DENY)),
                request("read", "prod.env"));
        engine.pending().add(denied);

        ReplyOutcome.Always outcome = (ReplyOutcome.Always) engine.reply(
                SESSION, first.id(), Reply.ALWAYS, Option.none(), PROJECT).orElseThrow();

        assertThat(outcome.released()).isEmpty();
        assertThat(engine.pending().count()).isEqualTo(1);
    }

    private static PermissionEngine engine(ScriptedApproval source) {
        return new PermissionEngine(Option.none(), Option.some(source), Option.none(), Option.none());
    }

    private static PermissionCheck check(List<Rule> rules, PermissionRequest request) {
        return new PermissionCheck(request, Option.some(AGENT), rules, List.of(), List.of(), List.of());
    }

    private static PermissionRequest request(String action, String resource) {
        return request(action, resource, List.of());
    }

    private static PermissionRequest request(String action, String resource, List<String> save) {
        return new PermissionRequest(PermissionRequestId.create(), SESSION, action, List.of(resource),
                save, Map.of(), null, null);
    }
}
