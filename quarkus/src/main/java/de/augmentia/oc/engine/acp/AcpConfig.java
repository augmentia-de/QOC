package de.augmentia.oc.engine.acp;

/**
 * Configuration for ACP (Agent Client Protocol) mode.
 * ACP is opt-in: default disabled. Enable via env var or system property.
 */
public final class AcpConfig {
    private AcpConfig() {}

    public static boolean enabled() {
        String v = System.getProperty("ENGINE_ACP_ENABLED",
                System.getenv().getOrDefault("ENGINE_ACP_ENABLED", "false"));
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }
}