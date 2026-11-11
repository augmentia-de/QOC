package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.runtime.QocEngine;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

/**
 * Dispatches a workflow node to its execution logic.
 * Used as the subagentTool handler in QocEngine's workflow mode.
 */
public interface NodeDispatcher {
    NodeResult dispatch(WorkflowDefinition.Node node, WorkflowDefinition def,
                       WorkflowContext ctx, String input);
}