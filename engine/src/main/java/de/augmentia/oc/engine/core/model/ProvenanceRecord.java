package de.augmentia.oc.engine.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A provenance record linking an assistant answer to the context blocks,
 * tool results and sources that influenced it.
 *
 * <p>Stores IDs and hashes only, never content. Optional feature: when
 * disabled, no records are produced.</p>
 */
public record ProvenanceRecord(
        MessageId assistantMessageId,
        List<ContextBlockRef> contextBlocks,
        List<String> toolResultIds,
        List<SourceRef> sources,
        Option<ModelRef> model,
        String promptHash,
        Instant createdAt) {

    public ProvenanceRecord {
        Objects.requireNonNull(assistantMessageId, "assistantMessageId");
        contextBlocks = contextBlocks == null ? List.of() : List.copyOf(contextBlocks);
        toolResultIds = toolResultIds == null ? List.of() : List.copyOf(toolResultIds);
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (model == null) model = Option.none();
        if (promptHash == null) promptHash = "";
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
