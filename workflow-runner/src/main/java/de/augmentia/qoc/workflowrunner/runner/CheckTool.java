package de.augmentia.qoc.workflowrunner.runner;

import java.nio.file.Path;
import java.util.Map;

/**
 * A built-in check tool executable by {@code mcp_call} nodes with
 * {@code mcpServer == "builtin"} (spec: docs/spec/agents/05-aisdlc-workflow.md,
 * SDW-02: every gate entry state must be produced by a machine check).
 *
 * <p>Implementations run outside the model: they execute commands, parse
 * reports and return a structured result that decision nodes can route on.
 * A check tool never returns prose to be judged — it returns machine-readable
 * state.</p>
 */
public interface CheckTool {

    /** Tool name as referenced by {@code mcpTool} in the node data. */
    String name();

    /**
     * Executes the check.
     *
     * @param workDir  the workflow run's working directory (repo root)
     * @param args     arguments from the node data ({@code mcpArgs}, templates resolved)
     * @param context  the workflow context (read-only access to node outputs)
     * @return structured result; must contain at least a {@code status} key
     */
    Map<String, Object> execute(Path workDir, Map<String, Object> args, WorkflowContext context);
}
