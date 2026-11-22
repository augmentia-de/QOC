package de.augmentia.oc.engine.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record DecisionRequest(
        String taskType,
        String question,
        List<String> questions,
        String context,
        Map<String, Object> options) {
    public DecisionRequest {
        Objects.requireNonNull(taskType, "taskType");
        if (question == null) question = "";
        questions = questions == null ? List.of() : List.copyOf(questions);
        if (context == null) context = "";
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
