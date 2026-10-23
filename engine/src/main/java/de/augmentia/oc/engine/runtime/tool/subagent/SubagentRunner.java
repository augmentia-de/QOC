package de.augmentia.oc.engine.runtime.tool.subagent;

import de.augmentia.oc.engine.core.model.SessionId;

/**
 * Runs a subagent in a child session and returns its final text response.
 * Implemented by the wiring layer (Engine) so the tool can stay decoupled
 * from session execution details.
 */
@FunctionalInterface
public interface SubagentRunner {

    /**
     * @param parent     the calling session
     * @param agentName  the subagent's agent name
     * @param prompt     the task for the subagent
     * @param depth      current recursion depth (0 = top level)
     * @return the subagent's final text response
     * @throws RuntimeException on failure (propagates as ToolFailure)
     */
    String run(SessionId parent, String agentName, String prompt, int depth);
}