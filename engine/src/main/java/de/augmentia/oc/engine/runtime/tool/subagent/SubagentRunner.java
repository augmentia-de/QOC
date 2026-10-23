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
    SubagentResult run(SessionId parent, String agentName, String prompt, int depth);

    /** Result of a subagent run, including usage for cost tracking. */
    record SubagentResult(String text, de.augmentia.oc.engine.core.model.TokenCount usage,
                          de.augmentia.oc.engine.core.model.SessionId childSession) {
        public SubagentResult {
            if (text == null) text = "";
            if (usage == null) usage = de.augmentia.oc.engine.core.model.TokenCount.zero();
        }
        public SubagentResult(String text, de.augmentia.oc.engine.core.model.TokenCount usage) {
            this(text, usage, null);
        }
    }
}