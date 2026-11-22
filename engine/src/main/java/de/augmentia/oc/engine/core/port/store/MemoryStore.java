package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.AnswerabilityResult;
import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.model.MemoryDiff;
import de.augmentia.oc.engine.core.model.MemoryQuery;
import de.augmentia.oc.engine.core.model.SessionId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Optional port for semantic long-term memory. Null-safe: a null instance
 * means memory is disabled.
 */
public interface MemoryStore {
    void upsert(Fact fact);

    void retire(String factId, String reason, String supersededBy);

    List<Fact> query(MemoryQuery query);

    Optional<Fact> asOf(String entity, String relation, Instant instant);

    MemoryDiff diff(Instant since);

    AnswerabilityResult checkAnswerable(String question);
}
