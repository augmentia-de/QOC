package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.port.human.ApprovalSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** One scripted answer per request. */
public final class ScriptedApproval implements ApprovalSource {

    private final Deque<ApprovalResult> answers;
    private final List<String> actions = new ArrayList<>();

    public ScriptedApproval(ApprovalResult... answers) {
        this(List.of(answers));
    }

    public ScriptedApproval(List<ApprovalResult> answers) {
        this.answers = new ArrayDeque<>(answers == null ? List.of() : answers);
    }

    @Override
    public synchronized ApprovalResult request(String action, String context) {
        actions.add(action);
        ApprovalResult answer = answers.pollFirst();
        if (answer == null) throw new IllegalStateException("ScriptedApproval has no answer left");
        return answer;
    }

    public synchronized List<String> actions() {
        return List.copyOf(actions);
    }
}
