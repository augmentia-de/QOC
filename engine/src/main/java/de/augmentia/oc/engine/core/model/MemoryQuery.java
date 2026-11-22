package de.augmentia.oc.engine.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MemoryQuery(String entity, String relation, Instant asOf, String text, int limit) {
    public MemoryQuery {
        if (limit <= 0) limit = 20;
    }
}
