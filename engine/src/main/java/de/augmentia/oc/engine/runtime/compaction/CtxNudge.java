package de.augmentia.oc.engine.runtime.compaction;

import de.augmentia.oc.engine.core.eval.PromptLoader;

/**
 * Detects compaction responses that did not follow the template and provides
 * the nudge prompt (CTX-NUDGE).
 */
public final class CtxNudge {

    private CtxNudge() {}

    public static String prompt() {
        return PromptLoader.get("ctx-nudge");
    }

    /** True when the response contains the required section headings. */
    public static boolean followsTemplate(String response) {
        if (response == null || response.isBlank()) return false;
        return response.contains("## Objective")
                && response.contains("## Requirements")
                && response.contains("## Decisions")
                && response.contains("## Work State")
                && response.contains("## Next Move");
    }

    /** Returns the nudge prompt when the response is malformed, otherwise null. */
    public static String nudgeIfNeeded(String response) {
        return followsTemplate(response) ? null : prompt();
    }
}
