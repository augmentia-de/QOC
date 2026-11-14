package de.augmentia.qoc.workflowrunner.generate;

/**
 * Utility to strip markdown fences and surrounding prose from raw LLM replies,
 * so only the JSON object remains. Ported from quad's WorkflowPlanParser.
 */
public final class StripFences {
    private StripFences() {}

    public static String strip(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        t = t.replaceFirst("(?s)^```(?:json)?\\s*", "");
        t = t.replaceFirst("(?s)\\s*```\\s*$", "");
        // Some compatible models emit a bare 'json' token before the object.
        t = t.replaceFirst("(?s)^json\\s*(?=\\{)", "");
        // Reasoning models prepend a sentence: cut everything before the first '{' / after last '}'.
        int firstBrace = t.indexOf('{');
        int lastBrace = t.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            t = t.substring(firstBrace, lastBrace + 1);
        }
        return t;
    }
}
