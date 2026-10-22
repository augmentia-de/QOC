package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.ModelInfo;

import java.util.List;

/** Immutable catalog snapshot. */
public final class StaticCatalogSource implements CatalogSource {

    private final List<ModelInfo> models;

    public StaticCatalogSource(List<ModelInfo> models) {
        this.models = models == null ? List.of() : List.copyOf(models);
    }

    @Override
    public List<ModelInfo> models() {
        return models;
    }
}
