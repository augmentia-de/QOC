package de.augmentia.oc.engine.core.model;

import java.util.List;
import java.util.Objects;

public record DecisionResult(
        String taskType,
        String answer,
        List<String> answers,
        List<Double> probabilities,
        double margin,
        boolean noul,
        long latencyMillis) {
    public DecisionResult {
        Objects.requireNonNull(taskType, "taskType");
        if (answer == null) answer = "";
        answers = answers == null ? List.of() : List.copyOf(answers);
        probabilities = probabilities == null ? List.of() : List.copyOf(probabilities);
        if (margin < 0) margin = 0;
        if (latencyMillis < 0) latencyMillis = 0;
    }

    public double confidence() {
        double max = 0;
        for (double p : probabilities) if (p > max) max = p;
        // A response may carry an empty or all-zero probability list (e.g. a
        // yes/no answer with only a margin); fall back to the margin.
        return max > 0 ? max : margin;
    }
}
