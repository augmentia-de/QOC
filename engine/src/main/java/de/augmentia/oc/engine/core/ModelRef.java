package de.augmentia.oc.engine.core;

public record ModelRef(ProviderId provider, ModelId model) {
    public ModelRef {
        if (provider == null || model == null) {
            throw new IllegalArgumentException("ModelRef requires provider and model");
        }
    }

    public static ModelRef of(ProviderId provider, ModelId model) {
        return new ModelRef(provider, model);
    }

    public static ModelRef parse(String value) {
        if (value == null || !value.contains("/")) {
            throw new IllegalArgumentException("ModelRef must be provider/model");
        }
        String[] parts = value.split("/", 2);
        return new ModelRef(ProviderId.parse(parts[0]), ModelId.parse(parts[1]));
    }

    @Override
    public String toString() {
        return provider + "/" + model;
    }
}