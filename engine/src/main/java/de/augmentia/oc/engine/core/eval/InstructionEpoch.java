package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure instruction-state transitions. The epoch starts at a sequence boundary
 * (for example a compaction) and every delta advances {@code throughSeq}.
 */
public final class InstructionEpoch {

    private InstructionEpoch() {}

    public static InstructionState start(Seq at, Map<String, String> initialValues) {
        if (at == null) throw new IllegalArgumentException("at must not be null");
        Map<String, String> initial = initialValues == null ? Map.of() : Map.copyOf(initialValues);
        return new InstructionState(at, at, initial, initial);
    }

    /**
     * Applies a delta: {@code Some(value)} sets a key, {@code None} removes it.
     * The initial values stay untouched so the epoch boundary remains visible.
     */
    public static InstructionState apply(InstructionState state, Map<String, Option<String>> delta, Seq through) {
        if (state == null) throw new IllegalArgumentException("state must not be null");
        if (through == null) throw new IllegalArgumentException("through must not be null");
        if (delta == null) delta = Map.of();

        Map<String, String> current = new LinkedHashMap<>(state.currentValues());
        for (Map.Entry<String, Option<String>> entry : delta.entrySet()) {
            Option<String> value = entry.getValue();
            if (value == null || value.isEmpty()) {
                current.remove(entry.getKey());
            } else {
                current.put(entry.getKey(), value.orElseThrow());
            }
        }

        Seq newThrough = through.value() > state.throughSeq().value() ? through : state.throughSeq();
        return new InstructionState(state.epochStart(), newThrough, state.initialValues(), current);
    }
}
