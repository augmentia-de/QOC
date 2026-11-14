package de.augmentia.qoc.workflowrunner.generate;

/**
 * Configuration for workflow generation (opt-in).
 */
public final class GenerateConfig {
    private GenerateConfig() {}

    public static boolean enabled() {
        String v = System.getProperty("ENGINE_WORKFLOW_GENERATE_ENABLED",
                System.getenv().getOrDefault("ENGINE_WORKFLOW_GENERATE_ENABLED", "false"));
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    public static String model() {
        String v = System.getProperty("ENGINE_WORKFLOW_GENERATE_MODEL",
                System.getenv().getOrDefault("ENGINE_WORKFLOW_GENERATE_MODEL", ""));
        return v == null ? "" : v.trim();
    }

    public static int maxSteps() {
        String v = System.getProperty("ENGINE_WORKFLOW_GENERATE_MAX_STEPS",
                System.getenv().getOrDefault("ENGINE_WORKFLOW_GENERATE_MAX_STEPS", "10"));
        try { return Math.max(1, Integer.parseInt(v.trim())); }
        catch (NumberFormatException e) { return 10; }
    }
}
