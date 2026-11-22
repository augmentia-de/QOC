package de.augmentia.oc.engine.core.model;

import java.util.List;
import java.util.Objects;

public record AnswerabilityResult(boolean answerable, List<String> missingEntities,
                                 List<String> missingRelations, String suggestedRetrieval) {
    public AnswerabilityResult {
        missingEntities = missingEntities == null ? List.of() : List.copyOf(missingEntities);
        missingRelations = missingRelations == null ? List.of() : List.copyOf(missingRelations);
        if (suggestedRetrieval == null) suggestedRetrieval = "";
    }
}
