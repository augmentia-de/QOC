package de.augmentia.qoc.decisiongate;

import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.typesafe.TypeSafeDecisionModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Factories for real decision models.
 *
 * <p>TypeSafe's {@link TypeSafeDecisionModel} speaks the System One
 * {@code POST /v1/systemone} protocol. The same client can target any
 * compatible server via {@link DecisionModelSettings#baseUrl()}.</p>
 */
public final class DecisionModels {

    private DecisionModels() {}

    /** Builds a TypeSafe/System One model from explicit settings. */
    public static DecisionModel typeSafe(DecisionModelSettings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        return TypeSafeDecisionModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.apiKey())
                .modelName(settings.modelName())
                .timeout(settings.timeout())
                .maxRetries(settings.maxRetries())
                .build();
    }

    /** Builds a TypeSafe model from {@code TYPESAFE_*}/{@code QOC_DECISION_*} environment variables. */
    public static DecisionModel typeSafeFromEnvironment() {
        return typeSafe(DecisionModelSettings.fromEnvironment());
    }

    /** Builds a TypeSafe model from an {@code application.properties} file. */
    public static DecisionModel typeSafeFromApplicationProperties(Path file) throws IOException {
        return typeSafe(DecisionModelSettings.fromApplicationProperties(file));
    }
}
