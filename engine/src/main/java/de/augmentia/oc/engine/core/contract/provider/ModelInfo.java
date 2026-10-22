package de.augmentia.oc.engine.core.contract.provider;

import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProviderId;

import java.util.List;

public record ModelInfo(ModelId id, ProviderId providerId, List<String> variants,
                        ModelLimits limits, CostModel cost, ModelCapabilities capabilities,
                        Option<CompactionMode> compaction, Option<ChannelMode> transport) {
    public ModelInfo {
        if (id == null || providerId == null) throw new IllegalArgumentException("model ids must not be null");
        variants = variants == null ? List.of() : List.copyOf(variants);
        if (limits == null) throw new IllegalArgumentException("limits must not be null");
        if (cost == null) throw new IllegalArgumentException("cost must not be null");
        if (capabilities == null) throw new IllegalArgumentException("capabilities must not be null");
        if (compaction == null) compaction = Option.none();
        if (transport == null) transport = Option.none();
    }

    public record ModelLimits(int context, int output) {
        public ModelLimits {
            if (context <= 0 || output <= 0) throw new IllegalArgumentException("limits must be positive");
        }
    }

    public record CostModel(Money input, Money output, Money cacheRead, Money cacheWrite) {
        public CostModel {
            if (input == null || output == null || cacheRead == null || cacheWrite == null) {
                throw new IllegalArgumentException("cost components must not be null");
            }
        }
    }

    public record ModelCapabilities(List<Modality> input, List<Modality> output) {
        public ModelCapabilities {
            input = input == null ? List.of() : List.copyOf(input);
            output = output == null ? List.of() : List.copyOf(output);
        }
    }
}