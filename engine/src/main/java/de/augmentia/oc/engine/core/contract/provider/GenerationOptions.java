package de.augmentia.oc.engine.core.contract.provider;

import de.augmentia.oc.engine.core.model.Option;

import java.util.List;

public record GenerationOptions(Option<Double> temperature, Option<Integer> maxOutputTokens,
                                Option<Double> topP, List<String> stop) {
    public GenerationOptions {
        if (temperature == null) temperature = Option.none();
        if (maxOutputTokens == null) maxOutputTokens = Option.none();
        if (topP == null) topP = Option.none();
        stop = stop == null ? List.of() : List.copyOf(stop);
    }

    public static GenerationOptions defaults() {
        return new GenerationOptions(Option.none(), Option.none(), Option.none(), List.of());
    }
}