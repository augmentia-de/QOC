package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.core.contract.provider.ModelInfo;
import de.augmentia.oc.engine.core.eval.TokenEstimator;

public final class ContextBudgetGate {

    public record Plan(int estimatedInputTokens, int maxInputTokens, int reservedOutputTokens) {
        public boolean exceeded() { return estimatedInputTokens > maxInputTokens; }
    }

    private static final int SAFETY_MARGIN = 512;

    private ContextBudgetGate() {}

    public static Plan check(String nodeId, String systemPrompt, String userPrompt,
                            int reservedOutputTokens, int contextLimit) {
        int reserved = Math.max(1, reservedOutputTokens);
        int maxInput = Math.max(1, contextLimit - reserved - SAFETY_MARGIN);
        int estimated = TokenEstimator.estimate(systemPrompt) + TokenEstimator.estimate(userPrompt);
        if (estimated > maxInput) {
            throw new WorkflowBudgetExceededException(nodeId, estimated, maxInput);
        }
        return new Plan(estimated, maxInput, reserved);
    }

    public static Plan check(String nodeId, String systemPrompt, String userPrompt,
                            de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent agent,
                            ModelInfo model) {
        int reserved = agent != null && agent.maxTokens() > 0 ? agent.maxTokens() : 4096;
        return check(nodeId, systemPrompt, userPrompt, reserved, model.limits().context());
    }
}
