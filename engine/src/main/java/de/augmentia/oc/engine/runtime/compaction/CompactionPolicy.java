package de.augmentia.oc.engine.runtime.compaction;

/**
 * Compaction thresholds. The defaults are deliberately conservative and are
 * calibrated against the provider-reported token usage.
 */
public record CompactionPolicy(int keepMessages, int keepRecentTokens, int reserveTokens) {

    public static final CompactionPolicy DEFAULT = new CompactionPolicy(8, 20_000, 16_384);

    public CompactionPolicy {
        if (keepMessages < 1) throw new IllegalArgumentException("keepMessages must be >= 1");
        if (keepRecentTokens < 0) throw new IllegalArgumentException("keepRecentTokens must be >= 0");
        if (reserveTokens < 0) throw new IllegalArgumentException("reserveTokens must be >= 0");
    }
}
