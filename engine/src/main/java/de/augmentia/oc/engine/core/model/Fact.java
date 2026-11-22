package de.augmentia.oc.engine.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A temporal fact in the semantic memory. New facts retire older ones
 * (retire, not delete); {@code as_of} reads historical state.
 */
public record Fact(
        String factId,
        String entity,
        String relation,
        Object value,
        Instant eventTime,
        Instant validFrom,
        Instant validTo,
        List<String> provenance,
        double confidence,
        int tier) {

    public Fact {
        Objects.requireNonNull(factId, "factId");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(relation, "relation");
        if (eventTime == null) eventTime = Instant.now();
        if (validFrom == null) validFrom = eventTime;
        if (validTo == null) validTo = Instant.MAX;
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
        if (confidence < 0) confidence = 0;
        if (confidence > 1) confidence = 1;
        if (tier < 0) tier = 0;
    }

    public boolean isRetired() {
        return validTo != null && validTo.isBefore(Instant.now());
    }
}
