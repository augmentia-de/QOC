package de.augmentia.oc.engine.adapter.memory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bytes-accounting for in-memory storage. Reserves and releases bytes per
 * category. Throws {@link MemoryLimitExceeded} when a limit is exceeded.
 */
public final class MemoryBudget {

    private final long maxBytes;
    private final AtomicLong usedBytes = new AtomicLong(0);

    public MemoryBudget(long maxBytes) {
        this.maxBytes = maxBytes <= 0 ? (1L << 30) : maxBytes;
    }

    public long maxBytes() { return maxBytes; }
    public long usedBytes() { return usedBytes.get(); }

    /** Reserves {@code bytes}. Throws if the budget would be exceeded. */
    public void reserve(long bytes) {
        if (bytes <= 0) return;
        long current = usedBytes.get();
        if (current + bytes > maxBytes) {
            throw new MemoryLimitExceeded(
                    "memory budget exceeded: requested " + bytes + " bytes, used " + current + " of " + maxBytes);
        }
        usedBytes.addAndGet(bytes);
    }

    /** Releases previously reserved bytes. */
    public void release(long bytes) {
        if (bytes <= 0) return;
        usedBytes.addAndGet(-bytes);
    }

    public double usageRatio() {
        return (double) usedBytes.get() / (double) maxBytes;
    }
}
