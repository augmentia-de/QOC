package de.augmentia.oc.engine.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MemoryDiff(Instant since, List<Fact> added, List<Fact> retired, List<Fact> changed) {
    public MemoryDiff {
        Objects.requireNonNull(since, "since");
        added = added == null ? List.of() : List.copyOf(added);
        retired = retired == null ? List.of() : List.copyOf(retired);
        changed = changed == null ? List.of() : List.copyOf(changed);
    }
}
