package de.augmentia.qoc.temporal.activity;

import de.augmentia.oc.engine.runtime.QocEngine;

/**
 * HITL activity: uses QocEngine to run prompts.
 */
public class HitlActivityImpl implements HitlActivity {

    private final QocEngine engine;

    public HitlActivityImpl(QocEngine engine) {
        this.engine = engine;
    }

    @Override
    public HitlStart startGraph(String input, String threadId) {
        String plan = engine.runPrompt("build", "Create a plan for: " + (input == null ? "" : input)).text();
        return new HitlStart(threadId, plan, "");
    }

    @Override
    public String resumeGraph(String threadId) {
        return engine.runPrompt("build", "Execute approved plan for thread: " + threadId).text();
    }
}
