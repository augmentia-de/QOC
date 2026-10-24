package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.StoredEvent;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.store.Journal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory journal: gapless per aggregate, synchronous append. */
public final class InMemoryJournal implements Journal {

    private final Map<String, List<StoredEvent>> events = new LinkedHashMap<>();
    private final Clock clock;

    public InMemoryJournal() {
        this(null);
    }

    public InMemoryJournal(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Seq append(EventDraft draft) {
        if (draft == null) throw new IllegalArgumentException("draft must not be null");
        List<StoredEvent> aggregate = events.computeIfAbsent(draft.aggregateId(), key -> new ArrayList<>());
        Seq seq = Seq.of(aggregate.size() + 1L);
        StoredEvent stored = StoredEvent.from(draft.aggregateId(), seq, draft,
                clock == null ? Instant.EPOCH : clock.now());
        aggregate.add(stored);
        return seq;
    }

    @Override
    public synchronized List<Seq> appendAll(List<EventDraft> drafts) {
        if (drafts == null) throw new IllegalArgumentException("drafts must not be null");
        List<Seq> sequences = new ArrayList<>(drafts.size());
        for (EventDraft draft : drafts) {
            sequences.add(append(draft));
        }
        return List.copyOf(sequences);
    }

    @Override
    public synchronized List<StoredEvent> history(String aggregateId, Seq after) {
        Seq from = after == null ? Seq.zero() : after;
        return events.getOrDefault(aggregateId, List.of()).stream()
                .filter(event -> event.seq().value() > from.value())
                .toList();
    }

    @Override
    public synchronized List<StoredEvent> historyOfType(String aggregateId, String type, Seq after) {
        Seq from = after == null ? Seq.zero() : after;
        return events.getOrDefault(aggregateId, List.of()).stream()
                .filter(event -> event.seq().value() > from.value())
                .filter(event -> event.type().equals(type))
                .toList();
    }

    @Override
    public synchronized void deleteAggregate(String aggregateId) {
        events.remove(aggregateId);
    }

    public synchronized int count(String aggregateId) {
        return events.getOrDefault(aggregateId, List.of()).size();
    }
}
