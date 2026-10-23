package de.augmentia.oc.engine.runtime.permission.engine;

import de.augmentia.oc.engine.core.contract.permission.PermissionDenied;
import de.augmentia.oc.engine.core.contract.permission.PermissionHook;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.error.Aborted;
import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.eval.PermissionCheck;
import de.augmentia.oc.engine.core.eval.PermissionDecision;
import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.contract.permission.Reply;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.ApprovalSource;
import de.augmentia.oc.engine.core.port.human.PolicySource;
import de.augmentia.oc.engine.core.port.store.ApprovalStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Permission engine: deny-before-approval and strict channel separation.
 *
 * <ul>
 *   <li>Rule denial → {@link PermissionDenied} (model-facing).</li>
 *   <li>User rejection without feedback → {@link Aborted} (model sees nothing).</li>
 *   <li>User rejection with feedback → {@link PermissionDenied} with that text.</li>
 * </ul>
 */
public final class PermissionEngine {

    private final Option<ApprovalStore> approvals;
    private final Option<ApprovalSource> approvalSource;
    private final Option<PolicySource> policySource;
    private final Option<PermissionHook> hook;
    private final PendingApprovals pending = new PendingApprovals();

    public PermissionEngine(Option<ApprovalStore> approvals, Option<ApprovalSource> approvalSource,
                            Option<PolicySource> policySource, Option<PermissionHook> hook) {
        this.approvals = approvals == null ? Option.none() : approvals;
        this.approvalSource = approvalSource == null ? Option.none() : approvalSource;
        this.policySource = policySource == null ? Option.none() : policySource;
        this.hook = hook == null ? Option.none() : hook;
    }

    public PendingApprovals pending() {
        return pending;
    }

    public PermissionDecision decide(PermissionCheck check) {
        return PermissionRules.decide(withPolicies(check), hook);
    }

    /**
     * Checks and, on {@code ask}, consults the human approval source. Deny is
     * always decided before an approval is requested.
     */
    public void assertAllowed(PermissionCheck check) {
        PermissionDecision decision = decide(check);
        switch (decision) {
            case PermissionDecision.Allowed allowed -> {
                return;
            }
            case PermissionDecision.Denied denied ->
                    throw new PermissionDenied(check.request(), denied.reason());
            case PermissionDecision.Ask ignored -> {
                PermissionRequest request = check.request();
                pending.add(check);
                if (approvalSource.isEmpty()) {
                    throw new Defect("no approval source configured for an ask decision");
                }
                ApprovalResult answer = approvalSource.orElseThrow()
                        .request(request.id(), request.sessionId(), request.action(), context(check));
                pending.remove(request.sessionId(), request.id());
                switch (answer) {
                    case ApprovalResult.Approved approved -> {
                        return;
                    }
                    case ApprovalResult.Denied denied -> throw rejection(request, denied.feedback());
                }
            }
        }
    }

    public Option<ReplyOutcome> reply(SessionId session, PermissionRequestId id, Reply reply,
                                      Option<String> feedback, ProjectId project) {
        if (reply == null) throw new IllegalArgumentException("reply must not be null");
        if (project == null) throw new IllegalArgumentException("project must not be null");
        if (feedback == null) feedback = Option.none();

        Option<PermissionRequest> removed = pending.remove(session, id);
        if (removed.isEmpty()) return Option.none();
        PermissionRequest request = removed.orElseThrow();

        return switch (reply) {
            case ONCE -> Option.some(new ReplyOutcome.Once(request));
            case ALWAYS -> Option.some(always(session, request, project));
            case REJECT -> {
                List<PermissionRequest> all = new ArrayList<>();
                all.add(request);
                all.addAll(pending.removeAll(session));
                yield Option.some(new ReplyOutcome.Reject(all, feedback));
            }
        };
    }

    /** Converts a user rejection into the correct channel. */
    public static RuntimeException rejection(PermissionRequest request, Option<String> feedback) {
        if (feedback == null) feedback = Option.none();
        if (feedback.isPresent()) {
            return new PermissionDenied(request, feedback.orElseThrow());
        }
        return new Aborted("permission rejected");
    }

    private ReplyOutcome.Always always(SessionId session, PermissionRequest request, ProjectId project) {
        if (request.save().isEmpty()) {
            return new ReplyOutcome.Always(request, List.of());
        }
        List<String> saved = new ArrayList<>();
        for (String pattern : request.save()) {
            if (approvals.isPresent()) {
                approvals.orElseThrow().add(project, request.action(), pattern);
                saved.add(pattern);
            }
        }

        // P-12: re-evaluate the other open requests; they do not hang.
        List<PermissionRequest> released = new ArrayList<>();
        if (approvals.isPresent()) {
            var stored = approvals.orElseThrow().list(project);
            for (PermissionCheck pendingCheck : pending.checks(session)) {
                PermissionCheck updated = new PermissionCheck(pendingCheck.request(), pendingCheck.agent(),
                        pendingCheck.agentRules(), pendingCheck.sessionRules(), stored, pendingCheck.policies());
                if (decide(updated) instanceof PermissionDecision.Allowed) {
                    pending.remove(session, pendingCheck.request().id());
                    released.add(pendingCheck.request());
                }
            }
        }
        return new ReplyOutcome.Always(request, saved, released);
    }

    private PermissionCheck withPolicies(PermissionCheck check) {
        if (!check.policies().isEmpty() || policySource.isEmpty()) {
            return check;
        }
        return new PermissionCheck(check.request(), check.agent(), check.agentRules(),
                check.sessionRules(), check.approvals(), policySource.orElseThrow().statements());
    }

    private static String context(PermissionCheck check) {
        return check.request().action() + ": " + String.join(", ", check.request().resources());
    }
}
