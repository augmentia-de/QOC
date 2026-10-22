package de.augmentia.oc.engine.core.eval;

/**
 * Per-step context-overflow budget: at most one compaction, a second overflow
 * is final (V-41, V-42). The counter is per step, not per session.
 */
public final class OverflowCompaction {

    private boolean used;

    /** @return true exactly once, then false for the rest of the step. */
    public boolean consume() {
        if (used) return false;
        used = true;
        return true;
    }

    public boolean used() {
        return used;
    }
}
