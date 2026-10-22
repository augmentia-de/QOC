package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.model.Option;

/**
 * Output-budget formula of provider/04 §5.
 *
 * <pre>
 * model    = min(outputLimit &gt; 0 ? outputLimit : 32_000, 256_000)
 * desired  = compaction ? min(model, 32_000) : model
 * room     = contextLimit - measured - ceil(estimated * 1.15)
 * budget   = min(desired, max(1_024, room))
 * </pre>
 */
public final class OutputBudget {

    public static final int FALLBACK_OUTPUT = 32_000;
    public static final int OUTPUT_CEILING = 256_000;
    public static final int COMPACTION_OUTPUT = 32_000;
    public static final int MIN_OUTPUT = 1_024;
    public static final double ESTIMATE_MARGIN = 1.15;

    private OutputBudget() {}

    /**
     * @param outputLimit   provider output limit (0 means unknown)
     * @param contextLimit  provider context window (0 or less means unknown)
     * @param measuredInput provider-measured prompt size, if available
     * @param estimatedInput locally estimated prompt size, if available (0 = none)
     * @param compaction    whether this request is a compaction request
     */
    public static int compute(int outputLimit, int contextLimit, Option<Long> measuredInput,
                              long estimatedInput, boolean compaction) {
        if (measuredInput == null) measuredInput = Option.none();
        if (estimatedInput < 0) throw new IllegalArgumentException("estimatedInput must not be negative");

        int model = Math.min(outputLimit > 0 ? outputLimit : FALLBACK_OUTPUT, OUTPUT_CEILING);
        int desired = compaction ? Math.min(model, COMPACTION_OUTPUT) : model;

        if (contextLimit <= 0 || (measuredInput.isEmpty() && estimatedInput == 0)) {
            return desired;
        }

        long measured = measuredInput.orElse(0L);
        long margin = estimatedInput == 0 ? 0 : (estimatedInput * 115 + 99) / 100;
        long room = (long) contextLimit - measured - margin;

        return (int) Math.min(desired, Math.max(MIN_OUTPUT, room));
    }

    public static int compute(int outputLimit, int contextLimit, long measuredInput,
                              long estimatedInput, boolean compaction) {
        return compute(outputLimit, contextLimit, Option.some(measuredInput), estimatedInput, compaction);
    }
}
