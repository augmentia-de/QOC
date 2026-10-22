package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.StoredEvent;

import java.util.List;

/**
 * Append-only event log, one gapless sequence per aggregate.
 * {@code history} returns events strictly after the given sequence
 * ({@code Seq.zero()} starts at the beginning).
 */
public interface Journal {

    Seq append(EventDraft draft);

    List<Seq> appendAll(List<EventDraft> drafts);

    List<StoredEvent> history(String aggregateId, Seq after);

    List<StoredEvent> historyOfType(String aggregateId, String type, Seq after);
}
