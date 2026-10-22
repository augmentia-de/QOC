package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.contract.permission.PermissionEvaluation;
import de.augmentia.oc.engine.core.contract.permission.PermissionHook;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.eval.PermissionCheck;
import de.augmentia.oc.engine.core.eval.PermissionDecision;
import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.core.eval.PolicyRules;
import de.augmentia.oc.engine.core.eval.PolicyStatement;
import de.augmentia.oc.engine.core.json.DecodeError;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionRulesTest {

    private static final AgentId AGENT = AgentId.create();
    private static final SessionId SESSION = SessionId.create();

    @Test
    void lastMatchingRuleWins() {
        List<Rule> rules = List.of(
                new Rule("read", "*", Effect.ALLOW),
                new Rule("read", "*secret*", Effect.DENY));
        assertThat(PermissionRules.evaluate(rules, "read", "my-secret.txt")).isEqualTo(Effect.DENY);

        List<Rule> reversed = List.of(
                new Rule("read", "*secret*", Effect.DENY),
                new Rule("read", "*", Effect.ALLOW));
        assertThat(PermissionRules.evaluate(reversed, "read", "my-secret.txt")).isEqualTo(Effect.ALLOW);
    }

    @Test
    void noMatchMeansAsk() {
        List<Rule> rules = List.of(new Rule("read", "a.txt", Effect.ALLOW));
        assertThat(PermissionRules.evaluate(rules, "read", "b.txt")).isEqualTo(Effect.ASK);
    }

    @Test
    void emptyRuleSetDeniesEverything() {
        PermissionDecision decision = decide(List.of(), List.of(), List.of(), "read", "a.txt");
        assertThat(decision).isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void withoutAgentEverythingIsDenied() {
        PermissionRequest request = request("read", "a.txt");
        PermissionCheck check = new PermissionCheck(request, Option.none(), List.of(), List.of(),
                List.of(), List.of());
        assertThat(PermissionRules.decide(check)).isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void denyBeatsStoredApproval() {
        List<Rule> rules = List.of(new Rule("edit", "*.env", Effect.DENY));
        List<SavedApproval> approvals = List.of(new SavedApproval(PermissionRequestId.create(),
                ProjectId.create(), "edit", "*"));
        PermissionDecision decision = decide(rules, List.of(), approvals, "edit", "prod.env");
        assertThat(decision).isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void storedApprovalAllowsWhenNoDenyExists() {
        List<Rule> rules = List.of(new Rule("edit", "*.env", Effect.ASK));
        List<SavedApproval> approvals = List.of(new SavedApproval(PermissionRequestId.create(),
                ProjectId.create(), "edit", "*.env"));
        PermissionDecision decision = decide(rules, List.of(), approvals, "edit", "prod.env");
        assertThat(decision).isInstanceOf(PermissionDecision.Allowed.class);
    }

    @Test
    void askBeatsAllowWithSeveralResources() {
        List<Rule> rules = List.of(
                new Rule("read", "a.txt", Effect.ALLOW),
                new Rule("read", "b.txt", Effect.ASK));
        PermissionDecision decision = decideWithResources(rules, "read", List.of("a.txt", "b.txt"));
        assertThat(decision).isInstanceOf(PermissionDecision.Ask.class);
    }

    @Test
    void oneDeniedResourceBlocksTheWholeOperation() {
        List<Rule> rules = List.of(
                new Rule("read", "a.txt", Effect.ALLOW),
                new Rule("read", "b.txt", Effect.DENY));
        PermissionDecision decision = decideWithResources(rules, "read", List.of("a.txt", "b.txt"));
        assertThat(decision).isInstanceOf(PermissionDecision.Denied.class);
        PermissionDecision.Denied denied = (PermissionDecision.Denied) decision;
        assertThat(denied.triggeringRules()).hasSize(1);
    }

    @Test
    void agentRulesComeBeforeSessionRules() {
        // Session allow wins because it comes last.
        PermissionRequest request = request("read", "a.txt");
        PermissionCheck check = new PermissionCheck(request, Option.some(AGENT),
                List.of(new Rule("*", "*", Effect.DENY)),
                List.of(new Rule("read", "*", Effect.ALLOW)),
                List.of(), List.of());
        assertThat(PermissionRules.decide(check)).isInstanceOf(PermissionDecision.Allowed.class);

        // Session deny wins over an earlier agent allow.
        PermissionCheck reversed = new PermissionCheck(request, Option.some(AGENT),
                List.of(new Rule("read", "*", Effect.ALLOW)),
                List.of(new Rule("*", "*", Effect.DENY)),
                List.of(), List.of());
        assertThat(PermissionRules.decide(reversed)).isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void policyDenyBeatsStoredApproval() {
        List<PolicyStatement> policies = List.of(PolicyStatement.deny("tool.use", "shell:sudo *"));
        List<Rule> rules = List.of(new Rule("shell", "*", Effect.ALLOW));
        List<SavedApproval> approvals = List.of(new SavedApproval(PermissionRequestId.create(),
                ProjectId.create(), "shell", "*"));
        PermissionDecision decision = decide(rules, List.of(), approvals, "shell", "sudo rm -rf /", policies);
        assertThat(decision).isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void policyAllowGrantsNothing() {
        List<PolicyStatement> policies = List.of(PolicyStatement.allow("tool.use", "read:*"));
        PermissionDecision decision = decide(List.of(), List.of(), List.of(), "read", "a.txt", policies);
        // The default deny rule is lifted, but no rule grants access -> ask.
        assertThat(decision).isInstanceOf(PermissionDecision.Ask.class);
    }

    @Test
    void policyAllowOnlyLiftsABroaderDenial() {
        List<PolicyStatement> policies = List.of(PolicyStatement.allow("tool.use", "read:a.txt"));
        List<Rule> rules = List.of(new Rule("read", "*", Effect.DENY));
        assertThat(decide(rules, List.of(), List.of(), "read", "a.txt", policies))
                .isInstanceOf(PermissionDecision.Ask.class);
        assertThat(decide(rules, List.of(), List.of(), "read", "b.txt", policies))
                .isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void hookCanChangeEffectAndMessage() {
        PermissionHook hook = evaluation -> new Result.Ok<>(new PermissionEvaluation(
                evaluation.sessionId(), evaluation.agent(), evaluation.action(),
                evaluation.resources(), evaluation.metadata(), Effect.DENY,
                Option.some("blocked by organization")));

        PermissionDecision decision = decide(List.of(new Rule("read", "*", Effect.ALLOW)), List.of(),
                List.of(), "read", "a.txt", List.of(), Option.some(hook));
        assertThat(decision).isInstanceOf(PermissionDecision.Denied.class);
        assertThat(((PermissionDecision.Denied) decision).reason()).isEqualTo("blocked by organization");
    }

    @Test
    void hookCanUpgradeAskToAllow() {
        PermissionHook hook = evaluation -> new Result.Ok<>(new PermissionEvaluation(
                evaluation.sessionId(), evaluation.agent(), evaluation.action(),
                evaluation.resources(), evaluation.metadata(), Effect.ALLOW,
                Option.some("allowed")));

        PermissionDecision decision = decide(List.of(new Rule("read", "*", Effect.ASK)), List.of(),
                List.of(), "read", "a.txt", List.of(), Option.some(hook));
        assertThat(decision).isInstanceOf(PermissionDecision.Allowed.class);
        assertThat(((PermissionDecision.Allowed) decision).message().orElse("")).isEqualTo("allowed");
    }

    @Test
    void failingHookIsADefect() {
        PermissionHook hook = evaluation -> new Result.Err<>(new DecodeError("$", "hook failed"));
        assertThatThrownBy(() -> decide(List.of(new Rule("read", "*", Effect.ALLOW)), List.of(),
                List.of(), "read", "a.txt", List.of(), Option.some(hook)))
                .isInstanceOf(Defect.class);
    }

    @Test
    void wildcardPatternsAreAnchoredAndSupportStarSpace() {
        List<Rule> rules = List.of(new Rule("shell", "git status *", Effect.ALLOW));
        assertThat(PermissionRules.evaluate(rules, "shell", "git status")).isEqualTo(Effect.ALLOW);
        assertThat(PermissionRules.evaluate(rules, "shell", "git status -sb")).isEqualTo(Effect.ALLOW);
        assertThat(PermissionRules.evaluate(rules, "shell", "git stash")).isEqualTo(Effect.ASK);
        assertThat(PermissionRules.evaluate(rules, "shell", "reread git status")).isEqualTo(Effect.ASK);
    }

    @Test
    void policyStatementRejectsAsk() {
        assertThatThrownBy(() -> new PolicyStatement(Effect.ASK, "tool.use", "*"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyCatalogHelpers() {
        List<PolicyStatement> policies = List.of(
                PolicyStatement.deny(PolicyRules.PROVIDER_USE, "prv_*"),
                PolicyStatement.deny(PolicyRules.INTEGRATION_USE, "mcp:*"),
                PolicyStatement.allow(PolicyRules.TOOL_USE, "read:*"));

        assertThat(PolicyRules.deniesProvider(policies, "prv_123")).isTrue();
        assertThat(PolicyRules.deniesProvider(policies, "other")).isFalse();
        assertThat(PolicyRules.deniesIntegration(policies, "mcp:github")).isTrue();
        assertThat(PolicyRules.deniesIntegration(policies, "skill:review")).isFalse();
        assertThat(PolicyRules.deniesToolUse(policies, "read", "a")).isFalse();
        assertThat(PolicyRules.allowsToolUse(policies, "read", "a")).isTrue();
    }

    private static PermissionDecision decide(List<Rule> agentRules, List<Rule> sessionRules,
                                             List<SavedApproval> approvals, String action, String resource) {
        return decide(agentRules, sessionRules, approvals, action, resource, List.of(), Option.none());
    }

    private static PermissionDecision decide(List<Rule> rules, List<Rule> ignored,
                                             List<SavedApproval> approvals, String action,
                                             String resource, List<PolicyStatement> policies) {
        return decide(rules, List.of(), approvals, action, resource, policies, Option.none());
    }

    private static PermissionDecision decide(List<Rule> agentRules, List<Rule> sessionRules,
                                             List<SavedApproval> approvals, String action, String resource,
                                             List<PolicyStatement> policies, Option<PermissionHook> hook) {
        PermissionCheck check = new PermissionCheck(request(action, resource), Option.some(AGENT),
                agentRules, sessionRules, approvals, policies);
        return PermissionRules.decide(check, hook);
    }

    private static PermissionDecision decideWithResources(List<Rule> rules, String action, List<String> resources) {
        PermissionRequest request = new PermissionRequest(PermissionRequestId.create(), SESSION,
                action, resources, List.of(), Map.of(), null, null);
        PermissionCheck check = new PermissionCheck(request, Option.some(AGENT),
                rules, List.of(), List.of(), List.of());
        return PermissionRules.decide(check);
    }

    private static PermissionRequest request(String action, String resource) {
        return new PermissionRequest(PermissionRequestId.create(), SESSION, action,
                List.of(resource), List.of(), Map.of(), null, null);
    }
}
