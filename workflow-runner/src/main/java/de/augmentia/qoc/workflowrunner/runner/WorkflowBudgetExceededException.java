package de.augmentia.qoc.workflowrunner.runner;

public class WorkflowBudgetExceededException extends RuntimeException {
    private final String nodeId;
    private final int estimatedTokens;
    private final int maxInputTokens;

    public WorkflowBudgetExceededException(String nodeId, int estimatedTokens, int maxInputTokens) {
        super("Workflow node " + nodeId + " estimated " + estimatedTokens
                + " tokens, exceeding the budget of " + maxInputTokens
                + ". Reduce contextInclude or insert a compact node before this node.");
        this.nodeId = nodeId;
        this.estimatedTokens = estimatedTokens;
        this.maxInputTokens = maxInputTokens;
    }

    public String nodeId() { return nodeId; }
    public int estimatedTokens() { return estimatedTokens; }
    public int maxInputTokens() { return maxInputTokens; }
}
