package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.ModelInfo;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProviderId;

import java.util.List;

/** Shipped model catalog (a snapshot, not a promise). */
public interface CatalogSource {

    List<ModelInfo> models();

    default Option<ModelInfo> find(ProviderId provider, ModelId model) {
        for (ModelInfo info : models()) {
            if (info.providerId().equals(provider) && info.id().equals(model)) {
                return Option.some(info);
            }
        }
        return Option.none();
    }
}
