package de.augmentia.oc.engine.core.model;

import java.util.Map;

public record InstructionState(Seq epochStart, Seq throughSeq,
                               Map<String, String> initialValues,
                               Map<String, String> currentValues) {
    public InstructionState {
        if (epochStart == null || throughSeq == null) throw new IllegalArgumentException("sequence bounds must not be null");
        initialValues = initialValues == null ? Map.of() : Map.copyOf(initialValues);
        currentValues = currentValues == null ? Map.of() : Map.copyOf(currentValues);
    }
}