package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.ModelInfo;
import de.augmentia.oc.engine.core.contract.provider.Modality;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProviderId;
import de.augmentia.oc.engine.runtime.provider.client.StaticCatalogSource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSourceTest {

    @Test
    void findsAModelByProviderAndId() {
        ModelId model = ModelId.create();
        ProviderId provider = ProviderId.create();
        ModelInfo info = info(model, provider);
        StaticCatalogSource catalog = new StaticCatalogSource(List.of(info));

        assertThat(catalog.models()).containsExactly(info);
        assertThat(catalog.find(provider, model).orElseThrow()).isEqualTo(info);
    }

    @Test
    void unknownModelIsEmpty() {
        StaticCatalogSource catalog = new StaticCatalogSource(List.of(info(ModelId.create(), ProviderId.create())));
        assertThat(catalog.find(ProviderId.create(), ModelId.create()).isEmpty()).isTrue();
    }

    @Test
    void emptyCatalogHasNoModels() {
        assertThat(new StaticCatalogSource(null).models()).isEmpty();
    }

    private static ModelInfo info(ModelId model, ProviderId provider) {
        return new ModelInfo(model, provider, List.of(),
                new ModelInfo.ModelLimits(1000, 500),
                new ModelInfo.CostModel(Money.zero(), Money.zero(), Money.zero(), Money.zero()),
                new ModelInfo.ModelCapabilities(List.of(Modality.TEXT), List.of(Modality.TEXT)),
                Option.none(), Option.none());
    }
}
